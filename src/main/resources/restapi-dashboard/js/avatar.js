/* Chronicle avatar renderer.
 *
 * Draws a Hytale player character with plain WebGL 1. The input is the answer of api/avatar/<uuid>.
 *
 *   { hash, assetVersion, parts: [ { slot, model, texture, gradient, baseColor } ... ] }
 *
 * The first part is the body (Characters/Player.blockymodel), its node tree is the skeleton. Every other part
 * is an attachment model whose root nodes are named after skeleton nodes (Head, Chest, R-Arm, L-Eye-Attachment
 * and so on). The children of an attachment root are grafted onto the skeleton node of the same name, the
 * root's own position, orientation and offset are authoring leftovers and are ignored.
 *
 * Paths are relative to the asset pack's Common folder and load from api/asset/<path> unless opts.asset maps
 * them. Models load with fetch, textures as images, so the page needs connect-src and img-src self only.
 *
 * One page holds at most one WebGL context. A single shared renderer draws every character into its own hidden
 * canvas and each view copies the result into its 2D canvas. A view that is off screen or in a hidden tab is not
 * drawn. A bust (opts.bust) is drawn once, head and shoulders, and kept as an image per skin hash and size, so a
 * list of players costs one render per look. A lost context is rebuilt from the copies kept in memory.
 *
 * Blockymodel conventions used here, established in the research write up on rendering avatars.
 *   - one unit is one texel and 1/64 of a block, +Y is up and the character faces +Z
 *   - node transform is T(position) R(orientation), the shape is drawn at T(offset) S(stretch) inside it,
 *     and the children of a node live in the frame T(position) R(orientation) T(offset)
 *   - box faces are front +Z, back -Z, left -X, right +X, top +Y and bottom -Y, the face texel size comes from
 *     settings.size before stretch, a negative stretch mirrors the geometry
 *   - a face's UV rectangle starts at offset, mirror flips an axis around that corner, then angle rotates
 *     the rectangle around the same corner in 90 degree steps
 *   - a greyscale texture is tinted through a 256 wide gradient strip, the grey level is the x index,
 *     pixels that are not grey keep their colour. Without a strip the base colour scales the grey level.
 *
 * API, Chronicle.avatar.mount(canvas, avatarJson, opts) returns { ready, dispose() }. ready settles once the
 * character is drawn for the first time. Options are bust, asset(path), yaw, pitch, turn (radians per second),
 * distance, lookY, interactive (false turns dragging off), fit and margin. A full view fits the camera to the
 * character unless distance is given or fit is false, so hats and tall hair stay inside the canvas with margin
 * (a share of the character's height, 0.12 by default) of air above and below.
 */
(function () {
  "use strict";

  // ---- 4x4 matrices, column major as WebGL expects ------------------------------------------------
  const M = {
    ident: () => [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1],
    mul(a, b) {
      const o = new Array(16);
      for (let c = 0; c < 4; c++) {
        for (let r = 0; r < 4; r++) {
          o[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3];
        }
      }
      return o;
    },
    translate(x, y, z) { const m = M.ident(); m[12] = x; m[13] = y; m[14] = z; return m; },
    quat(q) {
      const x = q.x || 0, y = q.y || 0, z = q.z || 0, w = q.w == null ? 1 : q.w;
      return [1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0,
        2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0,
        2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0, 0, 0, 0, 1];
    },
    rotX(a) { const c = Math.cos(a), s = Math.sin(a); return [1, 0, 0, 0, 0, c, s, 0, 0, -s, c, 0, 0, 0, 0, 1]; },
    rotY(a) { const c = Math.cos(a), s = Math.sin(a); return [c, 0, -s, 0, 0, 1, 0, 0, s, 0, c, 0, 0, 0, 0, 1]; },
    rotZ(a) { const c = Math.cos(a), s = Math.sin(a); return [c, s, 0, 0, -s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]; },
    perspective(fovy, aspect, near, far) {
      const f = 1 / Math.tan(fovy / 2), nf = 1 / (near - far);
      return [f / aspect, 0, 0, 0, 0, f, 0, 0, 0, 0, (far + near) * nf, -1, 0, 0, 2 * far * near * nf, 0];
    },
  };

  // ---- box faces ----------------------------------------------------------------------------------
  // Corners are listed top left, top right, bottom right, bottom left as seen from outside the face, so the
  // texture's x runs to the viewer's right and its y runs down. That order is clockwise from outside.
  const FACES = {
    front: { n: [0, 0, 1], dims: "xy", c: [[-1, 1, 1], [1, 1, 1], [1, -1, 1], [-1, -1, 1]] },
    back: { n: [0, 0, -1], dims: "xy", c: [[1, 1, -1], [-1, 1, -1], [-1, -1, -1], [1, -1, -1]] },
    left: { n: [-1, 0, 0], dims: "zy", c: [[-1, 1, -1], [-1, 1, 1], [-1, -1, 1], [-1, -1, -1]] },
    right: { n: [1, 0, 0], dims: "zy", c: [[1, 1, 1], [1, 1, -1], [1, -1, -1], [1, -1, 1]] },
    top: { n: [0, 1, 0], dims: "xz", c: [[-1, 1, -1], [1, 1, -1], [1, 1, 1], [-1, 1, 1]] },
    bottom: { n: [0, -1, 0], dims: "xz", c: [[-1, -1, 1], [1, -1, 1], [1, -1, -1], [-1, -1, -1]] },
  };
  // A quad is one box face lying in its plane. settings.normal picks the face, its texture is always "front".
  const QUAD_FACE = { "+Z": "front", "-Z": "back", "+X": "right", "-X": "left", "+Y": "top", "-Y": "bottom" };

  /**
   * Texture corners of one face. They start at the offset, mirror around it, then rotate around it. Each corner is
   * pulled 0.1 texel inwards, otherwise a face edge on a texel boundary samples the next region of the atlas.
   */
  function faceUV(layout, w, h, texW, texH) {
    const angle = (((layout.angle || 0) % 360) + 360) % 360;
    const mirror = layout.mirror || {};
    const off = layout.offset || { x: 0, y: 0 };
    const pts = [[0, 0], [w, 0], [w, h], [0, h]].map(([x, y]) => {
      if (mirror.x) x = -x;
      if (mirror.y) y = -y;
      if (angle === 90) return [-y, x];
      if (angle === 180) return [-x, -y];
      if (angle === 270) return [y, -x];
      return [x, y];
    });
    const cu = (pts[0][0] + pts[2][0]) / 2, cv = (pts[0][1] + pts[2][1]) / 2;
    return pts.map(([u, v]) => [(off.x + u + 0.1 * Math.sign(cu - u)) / texW,
      (off.y + v + 0.1 * Math.sign(cv - v)) / texH]);
  }

  /** Appends the triangles of one shape to out (x y z nx ny nz u v per vertex). Returns the vertex count. */
  function buildShape(shape, texW, texH, out) {
    const size = (shape.settings && shape.settings.size) || {};
    const st = shape.stretch || { x: 1, y: 1, z: 1 };
    const layouts = shape.textureLayout || {};
    let faces, half;
    if (shape.type === "box") {
      faces = Object.keys(FACES).filter((f) => layouts[f]).map((f) => [f, f]);
      half = { x: size.x / 2, y: size.y / 2, z: size.z / 2 };
    } else {
      const f = QUAD_FACE[(shape.settings && shape.settings.normal) || "+Z"];
      if (!layouts.front || !f) return 0;
      faces = [[f, "front"]];
      // The quad's size.x runs along the face's horizontal axis and size.y along its vertical one.
      const d = FACES[f].dims;
      half = { x: 0, y: 0, z: 0 };
      half[d[0]] = size.x / 2;
      half[d[1]] = size.y / 2;
    }
    const flip = st.x * st.y * st.z < 0; // a mirrored shape needs its winding reversed to stay outward
    let count = 0;
    for (const [face, layoutName] of faces) {
      const def = FACES[face];
      const dims = shape.type === "box" ? [size[def.dims[0]], size[def.dims[1]]] : [size.x, size.y];
      if (!(dims[0] * dims[1])) continue; // the edges of a zero thickness box would only leave stray pixels
      const uv = faceUV(layouts[layoutName], dims[0], dims[1], texW, texH);
      const n = [def.n[0] * Math.sign(st.x || 1), def.n[1] * Math.sign(st.y || 1), def.n[2] * Math.sign(st.z || 1)];
      const order = flip ? [0, 2, 1, 0, 3, 2] : [0, 1, 2, 0, 2, 3];
      for (const i of order) {
        const c = def.c[i];
        out.push(c[0] * half.x * st.x, c[1] * half.y * st.y, c[2] * half.z * st.z, n[0], n[1], n[2], uv[i][0], uv[i][1]);
      }
      count += 6;
    }
    return count;
  }

  // ---- loading ------------------------------------------------------------------------------------

  /** Runs a loader, once more after a short pause if it fails. */
  function retry(load) {
    return load().catch(() => new Promise((r) => setTimeout(r, 250)).then(load));
  }

  /** Each file is fetched once per page. Many busts of one page share the body model and most strips. */
  const files = new Map();
  const FILES_MAX = 400;

  function shared(key, load) {
    let p = files.get(key);
    if (!p) {
      p = retry(load);
      p.catch(() => files.delete(key));
      files.set(key, p);
      if (files.size > FILES_MAX) files.delete(files.keys().next().value);
    }
    return p;
  }

  function loadJson(url) {
    return shared("j|" + url, () => fetch(url, { credentials: "same-origin" }).then((r) => {
      if (!r.ok) throw new Error("could not load " + url);
      return r.json();
    }));
  }

  function loadImage(url) {
    return shared("i|" + url, () => new Promise((resolve, reject) => {
      const img = new Image();
      img.onload = () => resolve(img);
      img.onerror = () => reject(new Error("could not load " + url));
      img.src = url;
    }));
  }

  /** "#rrggbb" to [r, g, b], or null. */
  function hexColour(s) {
    const m = /^#?([0-9a-f]{6})$/i.exec(String(s || ""));
    if (!m) return null;
    const v = parseInt(m[1], 16);
    return [(v >> 16) & 255, (v >> 8) & 255, v & 255];
  }

  /**
   * Paints img into a canvas. With a gradient every grey texel takes the strip colour at x = grey level, with a
   * base colour the grey level scales that colour. Texels that are not grey keep their colour, alpha is kept.
   */
  function tint(img, gradient, baseColor) {
    const cv = document.createElement("canvas");
    cv.width = img.width;
    cv.height = img.height;
    const g = cv.getContext("2d", { willReadFrequently: true });
    g.drawImage(img, 0, 0);
    const base = gradient ? null : hexColour(baseColor);
    if (!gradient && !base) return cv;
    let lut = null, scale = 1;
    if (gradient) {
      const gc = document.createElement("canvas");
      gc.width = gradient.width;
      gc.height = gradient.height;
      const gg = gc.getContext("2d", { willReadFrequently: true });
      gg.drawImage(gradient, 0, 0);
      lut = gg.getImageData(0, gradient.height >> 1, gradient.width, 1).data;
      scale = (gradient.width - 1) / 255;
    }
    const data = g.getImageData(0, 0, cv.width, cv.height);
    const p = data.data;
    for (let i = 0; i < p.length; i += 4) {
      if (p[i + 3] === 0 || Math.abs(p[i] - p[i + 1]) > 2 || Math.abs(p[i + 1] - p[i + 2]) > 2) continue;
      if (lut) {
        const k = Math.round(p[i] * scale) * 4;
        p[i] = lut[k];
        p[i + 1] = lut[k + 1];
        p[i + 2] = lut[k + 2];
      } else {
        const l = p[i] / 255;
        p[i] = Math.round(base[0] * l * 1.25 > 255 ? 255 : base[0] * l * 1.25);
        p[i + 1] = Math.round(base[1] * l * 1.25 > 255 ? 255 : base[1] * l * 1.25);
        p[i + 2] = Math.round(base[2] * l * 1.25 > 255 ? 255 : base[2] * l * 1.25);
      }
    }
    g.putImageData(data, 0, 0);
    return cv;
  }

  // ---- characters ---------------------------------------------------------------------------------

  const v3 = (p) => [p ? p.x || 0 : 0, p ? p.y || 0 : 0, p ? p.z || 0 : 0];

  /** Turns a blockymodel node into a scene node, appending its shape geometry to verts. */
  function sceneNode(src, tex, verts) {
    const node = { name: src.name, pos: v3(src.position), rot: M.quat(src.orientation || {}),
      offset: v3(src.shape && src.shape.offset), draw: null, kids: [] };
    const s = src.shape;
    if (s && s.visible !== false && (s.type === "box" || s.type === "quad")) {
      const first = verts.length / 8;
      const count = buildShape(s, tex.w, tex.h, verts);
      if (count) node.draw = { first, count, tex: tex.index, both: !!s.doubleSided, full: s.shadingMode === "fullbright" };
    }
    for (const c of src.children || []) node.kids.push(sceneNode(c, tex, verts));
    return node;
  }

  function index(node, map) {
    node.bone = true;
    if (!map[node.name]) map[node.name] = node;
    node.kids.forEach((k) => index(k, map));
    return map;
  }

  /**
   * Loads every part of a description and builds the scene. The result holds plain arrays and tinted canvases
   * only, so it can be uploaded again after a lost context. Parts that fail to load are left out.
   */
  async function buildCharacter(desc, asset) {
    const parts = (await Promise.all((desc.parts || []).map(async (part) => {
      try {
        const [model, img, grad] = await Promise.all([
          loadJson(asset(part.model)),
          loadImage(asset(part.texture)),
          part.gradient ? loadImage(asset(part.gradient)).catch(() => null) : null,
        ]);
        return { part, model, img, canvas: tint(img, grad, grad ? null : part.baseColor) };
      } catch (e) {
        return null;
      }
    }))).filter(Boolean);
    if (!parts.length || !parts[0].model || !parts[0].model.nodes) throw new Error("the character has no body");
    const verts = [];
    const textures = parts.map((p) => p.canvas);
    const tex = (i) => ({ w: parts[i].img.width, h: parts[i].img.height, index: i });
    // The body's node tree is the skeleton, every other part grafts its roots' children onto it.
    const roots = parts[0].model.nodes.map((n) => sceneNode(n, tex(0), verts));
    const bones = {};
    roots.forEach((r) => index(r, bones));
    parts.slice(1).forEach((p, k) => {
      for (const root of p.model.nodes || []) {
        const target = bones[root.name] || bones.Origin || roots[0];
        const grafted = sceneNode(root, tex(k + 1), verts);
        if (grafted.draw) target.kids.push({ name: root.name, pos: [0, 0, 0], rot: M.ident(), offset: [0, 0, 0], draw: grafted.draw, kids: [], at: grafted.offset });
        target.kids.push(...grafted.kids);
      }
    });
    return { roots, verts: new Float32Array(verts), textures, gpu: null, users: 0 };
  }

  /** Characters by skin hash, shared by every view of the same look. */
  const characters = new Map();

  function acquire(desc, asset) {
    const key = (desc.hash || JSON.stringify(desc.parts)) + "|" + (desc.assetVersion || "");
    let entry = characters.get(key);
    if (!entry) {
      entry = { key, promise: buildCharacter(desc, asset), users: 0 };
      entry.promise.catch(() => characters.delete(key));
      characters.set(key, entry);
    }
    entry.users++;
    return entry;
  }

  function release(entry) {
    if (!entry || --entry.users > 0) return;
    // Kept a little while, a page that swaps views of the same player reuses it.
    setTimeout(() => {
      if (entry.users > 0 || characters.get(entry.key) !== entry) return;
      characters.delete(entry.key);
      entry.promise.then((ch) => renderer.free(ch)).catch(() => {});
    }, 30000);
  }

  // ---- the shared renderer ------------------------------------------------------------------------

  const VS = `attribute vec3 aPos; attribute vec3 aNorm; attribute vec2 aUv;
    uniform mat4 uProj, uView, uModel; uniform float uFull;
    varying vec2 vUv; varying float vLight;
    void main() {
      vec3 n = normalize((uView * uModel * vec4(aNorm, 0.0)).xyz);
      vLight = mix(0.62 + 0.38 * max(dot(n, normalize(vec3(-0.35, 0.55, 0.75))), 0.0), 1.0, uFull);
      vUv = aUv;
      gl_Position = uProj * uView * uModel * vec4(aPos, 1.0);
    }`;
  const FS = `precision mediump float; uniform sampler2D uTex; varying vec2 vUv; varying float vLight;
    void main() {
      vec4 c = texture2D(uTex, vUv);
      if (c.a < 0.5) discard;
      gl_FragColor = vec4(c.rgb * vLight, 1.0);
    }`;

  const MAX_SIDE = 2048;

  /** One WebGL context for the whole page, created on first use and rebuilt after a loss. */
  const renderer = {
    canvas: null,
    gl: null,
    prog: null,
    lost: false,
    generation: 0,
    uploaded: new Set(),

    init() {
      if (this.gl || this.lost) return !!this.gl;
      const cv = document.createElement("canvas");
      cv.width = 256;
      cv.height = 256;
      const gl = cv.getContext("webgl", { antialias: true, preserveDrawingBuffer: true, alpha: true, premultipliedAlpha: true });
      if (!gl) throw new Error("WebGL is not available");
      cv.addEventListener("webglcontextlost", (e) => {
        e.preventDefault();
        this.lost = true;
        this.prog = null;
        // The buffers and textures died with the context. The characters keep their arrays and canvases.
        for (const ch of this.uploaded) ch.gpu = null;
        this.uploaded.clear();
      });
      cv.addEventListener("webglcontextrestored", () => {
        this.lost = false;
        this.generation++;
        this.setup();
        views.forEach((v) => { v.dirty = true; });
        schedule();
      });
      this.canvas = cv;
      this.gl = gl;
      this.setup();
      return true;
    },

    setup() {
      const gl = this.gl;
      const make = (type, src) => {
        const s = gl.createShader(type);
        gl.shaderSource(s, src);
        gl.compileShader(s);
        if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
        return s;
      };
      const p = gl.createProgram();
      gl.attachShader(p, make(gl.VERTEX_SHADER, VS));
      gl.attachShader(p, make(gl.FRAGMENT_SHADER, FS));
      gl.linkProgram(p);
      if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p));
      const u = (n) => gl.getUniformLocation(p, n);
      this.prog = { p, aPos: gl.getAttribLocation(p, "aPos"), aNorm: gl.getAttribLocation(p, "aNorm"),
        aUv: gl.getAttribLocation(p, "aUv"), uProj: u("uProj"), uView: u("uView"), uModel: u("uModel"),
        uFull: u("uFull"), uTex: u("uTex") };
    },

    upload(ch) {
      const gl = this.gl;
      const vbo = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, vbo);
      gl.bufferData(gl.ARRAY_BUFFER, ch.verts, gl.STATIC_DRAW);
      const textures = ch.textures.map((canvas) => {
        const t = gl.createTexture();
        gl.bindTexture(gl.TEXTURE_2D, t);
        gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, canvas);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        return t;
      });
      ch.gpu = { vbo, textures };
      this.uploaded.add(ch);
    },

    free(ch) {
      if (!ch || !ch.gpu || !this.gl || this.lost) return;
      this.gl.deleteBuffer(ch.gpu.vbo);
      ch.gpu.textures.forEach((t) => this.gl.deleteTexture(t));
      ch.gpu = null;
      this.uploaded.delete(ch);
    },

    /**
     * Draws a character at w by h pixels into the shared canvas and returns the canvas, or null while the context
     * is lost. The picture sits in the bottom left corner of the canvas, w by h in size.
     */
    render(ch, cam, pose, w, h) {
      if (!this.init() || this.lost || this.gl.isContextLost()) return null;
      const gl = this.gl, prog = this.prog;
      w = Math.max(1, Math.min(MAX_SIDE, w));
      h = Math.max(1, Math.min(MAX_SIDE, h));
      if (this.canvas.width < w || this.canvas.height < h) {
        this.canvas.width = Math.max(this.canvas.width, w);
        this.canvas.height = Math.max(this.canvas.height, h);
      }
      if (!ch.gpu) this.upload(ch);
      gl.viewport(0, 0, w, h);
      gl.enable(gl.SCISSOR_TEST);
      gl.scissor(0, 0, w, h);
      gl.clearColor(0, 0, 0, 0);
      gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
      gl.disable(gl.SCISSOR_TEST);
      gl.enable(gl.DEPTH_TEST);
      gl.frontFace(gl.CW);
      gl.cullFace(gl.BACK);
      gl.useProgram(prog.p);
      gl.bindBuffer(gl.ARRAY_BUFFER, ch.gpu.vbo);
      gl.enableVertexAttribArray(prog.aPos);
      gl.enableVertexAttribArray(prog.aNorm);
      gl.enableVertexAttribArray(prog.aUv);
      gl.vertexAttribPointer(prog.aPos, 3, gl.FLOAT, false, 32, 0);
      gl.vertexAttribPointer(prog.aNorm, 3, gl.FLOAT, false, 32, 12);
      gl.vertexAttribPointer(prog.aUv, 2, gl.FLOAT, false, 32, 24);
      gl.activeTexture(gl.TEXTURE0);
      gl.uniform1i(prog.uTex, 0);
      gl.uniformMatrix4fv(prog.uProj, false, M.perspective(cam.fov || 0.5, w / h, 10, 1000));
      const view = M.mul(M.mul(M.mul(M.translate(0, 0, -cam.distance), M.rotX(cam.pitch)), M.rotY(cam.yaw)), M.translate(0, -cam.lookY, 0));
      gl.uniformMatrix4fv(prog.uView, false, view);
      const draw = (node, parent) => {
        let m = M.mul(parent, M.translate(node.pos[0], node.pos[1], node.pos[2]));
        m = M.mul(m, node.rot);
        if (node.bone && pose[node.name]) m = M.mul(m, pose[node.name]);
        if (node.draw) {
          const at = node.at || node.offset;
          const d = node.draw;
          gl.uniformMatrix4fv(prog.uModel, false, M.mul(m, M.translate(at[0], at[1], at[2])));
          gl.uniform1f(prog.uFull, d.full ? 1 : 0);
          gl.bindTexture(gl.TEXTURE_2D, ch.gpu.textures[d.tex]);
          if (d.both) gl.disable(gl.CULL_FACE); else gl.enable(gl.CULL_FACE);
          gl.drawArrays(gl.TRIANGLES, d.first, d.count);
        }
        const frame = M.mul(m, M.translate(node.offset[0], node.offset[1], node.offset[2]));
        for (const k of node.kids) draw(k, frame);
      };
      for (const r of ch.roots) draw(r, M.ident());
      return this.canvas;
    },
  };

  /** Copies the last render into a 2D canvas of the same pixel size. */
  function blit(src, target, w, h) {
    const g = target.getContext("2d");
    if (!g) return;
    g.clearRect(0, 0, target.width, target.height);
    g.drawImage(src, 0, src.height - h, w, h, 0, 0, target.width, target.height);
  }

  // ---- motion -------------------------------------------------------------------------------------

  const reducedMotion = typeof matchMedia === "function" ? matchMedia("(prefers-reduced-motion: reduce)") : null;
  const calm = () => !!(reducedMotion && reducedMotion.matches);

  /** Idle pose, a slow breath through the chest and arms and a gentle look around. t in seconds. */
  function idlePose(t) {
    const breath = Math.sin(t * 1.6);
    return {
      Chest: M.rotX(-0.015 * breath),
      Head: M.mul(M.rotY(0.10 * Math.sin(t * 0.45)), M.rotX(0.03 * Math.sin(t * 0.7) + 0.01 * breath)),
      "R-Arm": M.rotZ(-0.02 - 0.02 * breath),
      "L-Arm": M.rotZ(0.02 + 0.02 * breath),
    };
  }

  // ---- views and the shared frame loop ------------------------------------------------------------

  const views = new Set();
  let raf = 0;

  function schedule() {
    if (!raf && views.size) raf = requestAnimationFrame(tick);
  }

  function tick(now) {
    raf = 0;
    let again = false;
    for (const v of views) {
      if (v.draw(now)) again = true;
    }
    if (again) schedule();
  }

  if (typeof document !== "undefined") {
    document.addEventListener("visibilitychange", () => {
      views.forEach((v) => { v.dirty = true; });
      schedule();
    });
  }
  if (reducedMotion && reducedMotion.addEventListener) {
    reducedMotion.addEventListener("change", () => {
      views.forEach((v) => { v.dirty = true; });
      schedule();
    });
  }

  function defaultAsset(desc) {
    const v = desc && desc.assetVersion ? "?v=" + encodeURIComponent(desc.assetVersion) : "";
    return (p) => "api/asset/" + String(p).split("/").map(encodeURIComponent).join("/") + v;
  }

  function pixelSize(canvas) {
    const dpr = window.devicePixelRatio || 1;
    const w = Math.round((canvas.clientWidth || canvas.width) * dpr) || canvas.width;
    const h = Math.round((canvas.clientHeight || canvas.height) * dpr) || canvas.height;
    return [Math.max(1, Math.min(MAX_SIDE, w)), Math.max(1, Math.min(MAX_SIDE, h))];
  }

  // ---- busts --------------------------------------------------------------------------------------

  /** Finished busts by look and pixel size, each a small canvas. */
  const busts = new Map();
  const BUSTS_MAX = 300;
  const BUST_CAMERA = { yaw: 0.38, pitch: 0.06, distance: 170, lookY: 101 };

  function bustKey(desc, w, h) {
    return (desc.hash || JSON.stringify(desc.parts)) + "|" + (desc.assetVersion || "") + "|" + w + "x" + h;
  }

  function mountBust(canvas, desc, opts) {
    let disposed = false;
    const [w, h] = pixelSize(canvas);
    const key = bustKey(desc, w, h);
    const paint = (img) => {
      if (disposed) return;
      canvas.width = w;
      canvas.height = h;
      const g = canvas.getContext("2d");
      g.clearRect(0, 0, w, h);
      g.drawImage(img, 0, 0);
    };
    const hit = busts.get(key);
    if (hit) {
      paint(hit);
      return { ready: Promise.resolve(), dispose() { disposed = true; } };
    }
    const entry = acquire(desc, opts.asset || defaultAsset(desc));
    const ready = entry.promise.then((ch) => new Promise((resolve, reject) => {
      // The render waits for a frame so a list of busts shares one pass of the loop.
      requestAnimationFrame(() => {
        try {
          if (disposed) return resolve();
          const cam = Object.assign({}, BUST_CAMERA, pick(opts, ["yaw", "pitch", "distance", "lookY"]));
          const src = renderer.render(ch, cam, {}, w, h);
          if (!src) return reject(new Error("WebGL is not available right now"));
          const copy = document.createElement("canvas");
          copy.width = w;
          copy.height = h;
          blit(src, copy, w, h);
          busts.set(key, copy);
          if (busts.size > BUSTS_MAX) busts.delete(busts.keys().next().value);
          paint(copy);
          resolve();
        } catch (e) {
          reject(e);
        }
      });
    })).finally(() => release(entry));
    return { ready, dispose() { disposed = true; } };
  }

  function pick(o, keys) {
    const out = {};
    for (const k of keys) if (o[k] != null) out[k] = o[k];
    return out;
  }

  // ---- framing ------------------------------------------------------------------------------------

  /**
   * The rest pose bounds of a character, lowest and highest point and the largest distance from the vertical
   * axis, found by walking the scene the way render does. Kept on the character, the arrays never change.
   */
  function bounds(ch) {
    if (ch.bounds) return ch.bounds;
    const v = ch.verts;
    let lo = Infinity, hi = -Infinity, reach = 0;
    const walk = (node, parent) => {
      const m = M.mul(M.mul(parent, M.translate(node.pos[0], node.pos[1], node.pos[2])), node.rot);
      if (node.draw) {
        const at = node.at || node.offset;
        const t = M.mul(m, M.translate(at[0], at[1], at[2]));
        for (let i = node.draw.first; i < node.draw.first + node.draw.count; i++) {
          const x = v[i * 8], y = v[i * 8 + 1], z = v[i * 8 + 2];
          const wy = t[1] * x + t[5] * y + t[9] * z + t[13];
          const wx = t[0] * x + t[4] * y + t[8] * z + t[12];
          const wz = t[2] * x + t[6] * y + t[10] * z + t[14];
          if (wy < lo) lo = wy;
          if (wy > hi) hi = wy;
          const r = Math.sqrt(wx * wx + wz * wz);
          if (r > reach) reach = r;
        }
      }
      const frame = M.mul(m, M.translate(node.offset[0], node.offset[1], node.offset[2]));
      for (const k of node.kids) walk(k, frame);
    };
    for (const r of ch.roots) walk(r, M.ident());
    ch.bounds = isFinite(lo) && hi > lo ? { lo, hi, reach } : null;
    return ch.bounds;
  }

  /**
   * Sets the distance and look height of cam so the whole character fits a w by h picture at any turn, with
   * margin of its height as air above and below. The near side of the character comes closer to the camera as
   * it turns, so its reach is added to the distance.
   */
  function frameCamera(cam, ch, w, h, margin) {
    const b = bounds(ch);
    if (!b) return;
    const half = (b.hi - b.lo) / 2 * (1 + 2 * margin);
    const tan = Math.tan((cam.fov || 0.5) / 2);
    const along = half / tan;
    const across = b.reach * (1 + margin) / (tan * Math.max(0.2, w / h));
    cam.lookY = (b.lo + b.hi) / 2;
    cam.distance = Math.max(along, across) + b.reach;
  }

  // ---- full views ---------------------------------------------------------------------------------

  /**
   * Mounts a character in canvas. Returns { ready, dispose() } at once. ready settles after the first frame or
   * fails when the character cannot load. dispose stops drawing, removes every listener and lets go of the
   * character, call it when the canvas leaves the page.
   */
  function mount(canvas, desc, opts) {
    opts = opts || {};
    if (!desc || !Array.isArray(desc.parts) || !desc.parts.length) {
      return { ready: Promise.reject(new Error("the avatar has no parts")), dispose() {} };
    }
    try {
      renderer.init();
    } catch (e) {
      return { ready: Promise.reject(e), dispose() {} };
    }
    if (opts.bust) return mountBust(canvas, desc, opts);

    const entry = acquire(desc, opts.asset || defaultAsset(desc));
    const interactive = opts.interactive !== false;
    const turn = opts.turn == null ? 0.25 : opts.turn;
    const cam = { yaw: opts.yaw == null ? 0.5 : opts.yaw, pitch: opts.pitch == null ? 0.08 : opts.pitch,
      distance: opts.distance || 300, lookY: opts.lookY || 62 };
    const fit = opts.fit !== false && opts.distance == null;
    const margin = opts.margin == null ? 0.12 : Math.max(0, Math.min(0.5, Number(opts.margin) || 0));
    let drag = null, visible = true, last = 0, character = null, settle = null, fail = null;
    const ready = new Promise((resolve, reject) => { settle = resolve; fail = reject; });

    const view = {
      dirty: true,
      /** Draws when on screen and moving or changed. True when it wants another frame. */
      draw(now) {
        if (!character || !visible || document.hidden) return false;
        const moving = !calm();
        const dt = last ? Math.min(0.1, (now - last) / 1000) : 0;
        last = now;
        if (!this.dirty && !moving) return false;
        if (!drag && !calm()) cam.yaw += turn * dt;
        const [w, h] = pixelSize(canvas);
        if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
        if (fit) frameCamera(cam, character, w, h, margin);
        const src = renderer.render(character, cam, calm() ? {} : idlePose(now / 1000), w, h);
        if (!src) return false;
        blit(src, canvas, w, h);
        this.dirty = false;
        if (settle) { settle(); settle = null; }
        return moving;
      },
    };

    const down = (e) => {
      if (!interactive) return;
      drag = { x: e.clientX, y: e.clientY };
      if (canvas.setPointerCapture) canvas.setPointerCapture(e.pointerId);
    };
    const move = (e) => {
      if (!drag) return;
      cam.yaw += (e.clientX - drag.x) * 0.01;
      cam.pitch = Math.max(-0.6, Math.min(0.8, cam.pitch + (e.clientY - drag.y) * 0.006));
      drag = { x: e.clientX, y: e.clientY };
      view.dirty = true;
      schedule();
    };
    const up = () => { drag = null; };
    if (interactive) {
      canvas.addEventListener("pointerdown", down);
      canvas.addEventListener("pointermove", move);
      window.addEventListener("pointerup", up);
      window.addEventListener("pointercancel", up);
      canvas.style.touchAction = "none";
    }

    let io = null;
    if (typeof IntersectionObserver === "function") {
      io = new IntersectionObserver((list) => {
        for (const e of list) visible = e.isIntersecting;
        if (visible) { view.dirty = true; last = 0; schedule(); }
      });
      io.observe(canvas);
    }
    let ro = null;
    if (typeof ResizeObserver === "function") {
      ro = new ResizeObserver(() => { view.dirty = true; schedule(); });
      ro.observe(canvas);
    }

    let disposed = false;
    entry.promise.then((ch) => {
      if (disposed) return;
      character = ch;
      views.add(view);
      schedule();
    }, (e) => { if (fail) fail(e); });

    return {
      ready,
      dispose() {
        if (disposed) return;
        disposed = true;
        views.delete(view);
        if (io) io.disconnect();
        if (ro) ro.disconnect();
        if (interactive) {
          canvas.removeEventListener("pointerdown", down);
          canvas.removeEventListener("pointermove", move);
          window.removeEventListener("pointerup", up);
          window.removeEventListener("pointercancel", up);
        }
        release(entry);
        if (settle) { settle(); settle = null; }
      },
    };
  }

  const A = window.Chronicle || (window.Chronicle = {});
  A.avatar = { mount, tint, faceUV };
})();
