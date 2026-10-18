// The Lively view. Game icons, mod icons, busts, medals, sky bands and Atlas mini maps on top of the plain tables.
// Every helper here answers null or plain text in the Just data view, so that view never asks for an image.
// No imports from dom.js, which imports this file for the busts in player links.

import { caps, url } from './api.js';
import * as settings from './settings.js';

/** 'lively' or 'data', from the viewer's settings. Lively unless the viewer picked Just data. */
export function currentView() {
  return settings.get('view') === 'data' ? 'data' : 'lively';
}
export const isLively = () => document.documentElement.dataset.view === 'lively';

export function applyView(v) {
  settings.set({ view: v });
  document.documentElement.dataset.view = v === 'data' ? 'data' : 'lively';
}

function el(tag, attrs, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k === 'class') e.className = v;
    else if (k === 'text') e.textContent = v;
    else if (k === 'style') for (const [sk, sv] of Object.entries(v)) { if (sk.startsWith('--')) e.style.setProperty(sk, String(sv)); else e.style[sk] = sv; }
    else e.setAttribute(k, v === true ? '' : v);
  }
  for (const c of kids.flat()) if (c != null && c !== false) e.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return e;
}

const idText = (id) => String(id ?? '').replace(/_/g, ' ');

// ---------- game icons from Assets.zip ----------

let index = null;
let indexLoad = null;

/** Reads the names of the icons server.js can serve, once, so the page only asks for icons that exist. */
export function prepare() {
  if (!isLively() || !caps.assets || index) return Promise.resolve();
  if (!indexLoad) {
    indexLoad = fetch(url('/assets/index.json')).then((r) => (r.ok ? r.json() : null)).then((d) => {
      if (d) index = { items: new Set(d.items || []), markers: new Set(d.markers || []), models: new Set(d.models || []) };
    }, () => {}).finally(() => { if (!index) indexLoad = null; });
  }
  return indexLoad;
}

function img(src, size, cls, alt = '') {
  const i = el('img', { src, width: size, height: size, alt, class: cls, loading: 'lazy', decoding: 'async', draggable: 'false' });
  i.addEventListener('load', () => i.classList.add('in'), { once: true });
  // A missing picture leaves the text where it was.
  i.addEventListener('error', () => i.remove(), { once: true });
  return i;
}

/** An icon of the game, items, markers or models, or null when the view is Just data or the game has none. */
export function gameIcon(kind, name, size = 24) {
  if (!isLively() || !index || !name || !index[kind] || !index[kind].has(name)) return null;
  return img(url(`/assets/${kind}/${encodeURIComponent(name)}.png`), size, `gi gi-${kind}`);
}

/** The icon for a counter or stat key such as items_gained:Ore_Iron or kill:Boar, items before creatures. */
export function keyIcon(key, size = 24) {
  const s = String(key || '');
  const name = s.includes(':') ? s.slice(s.lastIndexOf(':') + 1) : s;
  return gameIcon('items', name, size) || gameIcon('models', name, size);
}

/** A mod icon and its label with the 8 px gap every icon and label pair uses, or the label alone. */
export function modLabel(icon, content) {
  if (!icon) return content;
  return el('span', { class: 'mod-name' }, icon, el('span', { class: 'ic-text' }, content));
}

/** content with the icon in front of it in a fixed box, or content alone. */
export function withIcon(icon, content) {
  if (!icon) return content;
  return el('span', { class: 'ic-label' }, el('span', { class: 'ic-box' }, icon), el('span', { class: 'ic-text' }, content));
}

/** An item id as its name, with the game's icon in front in the Lively view. */
export function itemText(id, text) {
  const label = text ?? idText(id);
  return withIcon(gameIcon('items', id), label);
}

// ---------- the owner's mod icons ----------

export const PLUGIN_ICON = {
  'Ryvrook:TrackerAPI': 'tracker', 'Ryvrook:Feats': 'feats', 'Ryvrook:ChronicleStats': 'chronicle', 'Ryvrook:Atlas': 'atlas',
  'Ryvrook:Coinage': 'coinage', 'Ryvrook:Dominion': 'dominion', 'Ryvrook:Lullaby': 'lullaby', 'Ryvrook:SocialBridge': 'socialbridge',
  'Ryvrook:RestAPI': 'restapi',
};

/** A bundled mod icon by suite id (tracker, feats and so on, or restapi). An older server.js serves no images. */
export function modIcon(id, size = 20) {
  if (!isLively() || !caps.images || !id) return null;
  // RestAPI's own icon is the header mark, the same file serves here.
  const box = el('span', { class: 'mi-box', style: { width: `${size}px`, height: `${size}px` } });
  box.append(img(id === 'restapi' ? 'img/restapi-64.png' : `img/mods/${id}.png`, size, 'mi'));
  return box;
}
export const pluginIcon = (pluginId, size = 20) => modIcon(PLUGIN_ICON[pluginId], size);

// ---------- busts ----------

const hue = (s) => { let x = 0; for (const c of String(s || '')) x = (x * 31 + c.charCodeAt(0)) >>> 0; return x % 360; };
const initialOf = (name) => ((String(name || '').match(/[A-Za-z0-9]/) || ['?'])[0]).toUpperCase();
const PX = { xs: 20, sm: 32, md: 44, lg: 88 };

/** A head and shoulders bust with the player's initial, a real character when Chronicle answers. Lively only. */
export function bust(name, uuid, size = 'xs') {
  if (!isLively()) return null;
  const who = uuid || name;
  const b = el('span', { class: `bust ${size}`, style: { '--bust-h': hue(who) }, 'aria-hidden': 'true' },
    el('span', { class: 'bust-initial', 'data-initial': initialOf(name || uuid) }));
  if (!caps.chronicle || !who) b.classList.add('letter');
  if (caps.chronicle && who) {
    b.dataset.bust = who;
    b.append(el('canvas', { width: PX[size] * 2, height: PX[size] * 2 }));
    watch(b);
  }
  return b;
}

// Character data from Chronicle, by uuid or name, null when there is none. Kept for the visit.
const skins = new Map();
let queue = [];
let running = 0;
let misses = 0;
let hits = 0;
const pump = () => {
  while (running < 4 && queue.length) {
    const job = queue.shift();
    running++;
    job().finally(() => { running--; pump(); });
  }
};
const enqueue = (fn) => new Promise((res, rej) => { queue.push(() => fn().then(res, rej)); pump(); });

function skinOf(who) {
  if (!caps.chronicle || !who) return Promise.resolve(null);
  if (!skins.has(who)) {
    // A Chronicle without pictures for anyone stops being asked after eight misses in a row.
    if (misses >= 8 && !hits) return Promise.resolve(null);
    skins.set(who, enqueue(() => fetch(url(`/chronicle/api/avatar/${encodeURIComponent(who)}`), { headers: { Accept: 'application/json' } }).then((r) => (r.ok ? r.json() : null)))
      .then((d) => { if (d && Array.isArray(d.parts) && d.parts.length) { hits++; misses = 0; return d; } misses++; return null; }, () => { misses++; return null; }));
  }
  return skins.get(who);
}

let avatarScript = null;
function loadAvatar() {
  const have = () => (window.Chronicle && window.Chronicle.avatar && window.Chronicle.avatar.mount ? window.Chronicle.avatar : null);
  if (have()) return Promise.resolve(have());
  if (!avatarScript) {
    avatarScript = new Promise((resolve) => {
      const s = document.createElement('script');
      s.src = 'js/avatar.js';
      s.onload = () => resolve(have());
      s.onerror = () => resolve(null);
      document.head.append(s);
    });
  }
  return avatarScript;
}
const assetUrl = (p) => url(`/chronicle/api/asset/${String(p).split('/').map(encodeURIComponent).join('/')}`);

// Handles of drawn busts, let go once their element has left the page.
const handles = new Set();
function sweep() {
  for (const x of handles) if (!x.el.isConnected) { try { x.handle.dispose(); } catch { /* gone */ } handles.delete(x); }
}

let io = null;
function watch(b) {
  if (!('IntersectionObserver' in window)) { draw(b); return; }
  if (!io) io = new IntersectionObserver((entries) => { for (const en of entries) if (en.isIntersecting) { io.unobserve(en.target); draw(en.target); } }, { rootMargin: '200px' });
  io.observe(b);
}

function draw(b) {
  if (!isLively()) return;
  sweep();
  skinOf(b.dataset.bust).then((skin) => {
    if (!skin || !b.isConnected) return null;
    return loadAvatar().then((av) => {
      if (!av || !b.isConnected) return null;
      const canvas = b.querySelector('canvas');
      const handle = av.mount(canvas, skin, { bust: true, asset: assetUrl, size: canvas.width, hash: skin.skinHash || skin.hash });
      if (!handle) return null;
      handles.add({ el: b, handle });
      return Promise.resolve(handle.ready).then(() => { if (b.isConnected) b.classList.add('has-render'); });
    });
  }).catch(() => { /* the letter bust stays */ });
}

/**
 * The full character in a frame, turning by drag, for the player page. Without Chronicle, WebGL or a skin it
 * keeps the silhouette with the initial. Made once per page, so the 5 second refresh does not redraw it.
 */
export function viewer(name, uuid) {
  if (!isLively()) return null;
  const who = uuid || name;
  const hint = el('span', { class: 'viewer-hint', text: 'Drag to turn' });
  const box = el('div', { class: 'viewer', style: { '--bust-h': hue(who) } },
    el('div', { class: 'viewer-fallback', 'aria-hidden': 'true' }, el('span', { class: 'vf-head' }), el('span', { class: 'vf-body' }), el('span', { class: 'viewer-initial', 'data-initial': initialOf(name || uuid) })));
  let handle = null;
  if (caps.chronicle && who) {
    const canvas = el('canvas', { role: 'img', 'aria-label': `${name || 'This player'} in game` });
    box.append(canvas, hint);
    skinOf(who).then((skin) => {
      if (!skin) { box.classList.add('no-skin'); hint.textContent = 'Chronicle has no character look for this player yet'; return null; }
      return loadAvatar().then((av) => {
        if (!av) return null;
        handle = av.mount(canvas, skin, { asset: assetUrl, hash: skin.skinHash || skin.hash });
        return handle && Promise.resolve(handle.ready).then(() => box.classList.add('has-render'), () => { box.classList.add('no-skin'); hint.textContent = 'This browser cannot draw the character'; });
      });
    }).catch(() => {});
  }
  return { el: box, dispose() { if (handle && handle.dispose) handle.dispose(); } };
}

// ---------- medals, coins, swatches ----------

const TIERS = ['bronze', 'silver', 'gold', 'platinum'];
/** A medal for a feat tier with the tier name beside it, or the plain name in Just data. */
export function medal(tier, label) {
  const t = String(tier || '').toLowerCase();
  const text = label ?? (t ? t[0].toUpperCase() + t.slice(1) : '-');
  if (!isLively() || !TIERS.includes(t)) return null;
  return el('span', { class: `medal-label t-${t}` }, el('span', { class: 'medal', 'aria-hidden': 'true' }), el('span', { text }));
}

/** A round disc with a currency symbol, Lively only. */
export function coin(symbol) {
  if (!isLively() || !symbol) return null;
  return el('span', { class: 'coin', 'aria-hidden': 'true', text: String(symbol).slice(0, 2) });
}

// ---------- worlds ----------

const SVGNS = 'http://www.w3.org/2000/svg';
function svgEl(tag, attrs) {
  const e = document.createElementNS(SVGNS, tag);
  for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
  return e;
}

/** A moon of phase 0 (full) to 7, drawn as a lit disc with its shadow. The engine counts eight phases. */
export function moonGlyph(phase, size = 18) {
  const s = svgEl('svg', { viewBox: '0 0 20 20', width: size, height: size, class: 'moon', 'aria-hidden': 'true' });
  const p = ((Number(phase) || 0) % 8 + 8) % 8;
  s.append(svgEl('circle', { cx: 10, cy: 10, r: 8, class: 'moon-lit' }));
  if (p !== 0) {
    // Phases 1 to 3 wane from the right, 5 to 7 wax from the left, 4 is the new moon.
    const k = p <= 4 ? p / 4 : (8 - p) / 4;
    const rx = Math.abs(1 - 2 * k) * 8;
    const side = p <= 4 ? 1 : 0;
    const d = k >= 1 ? 'M10 2 A8 8 0 1 1 10 18 A8 8 0 1 1 10 2Z'
      : `M10 2 A8 8 0 0 ${side} 10 18 A${rx.toFixed(2)} 8 0 0 ${k > 0.5 ? 1 - side : side} 10 2Z`;
    s.append(svgEl('path', { d, class: 'moon-dark' }));
  }
  return s;
}

function skyKind(hour) {
  if (hour < 5 || hour >= 21) return 'night';
  if (hour < 8) return 'dawn';
  if (hour < 18) return 'day';
  return 'dusk';
}
function weatherKind(name) {
  const w = String(name || '');
  if (/storm|thunder|blood|terror/i.test(w)) return 'storm';
  if (/rain/i.test(w)) return 'rain';
  if (/snow/i.test(w)) return 'snow';
  if (/fog|haze|cloud|sand|ash|gloom|spooky/i.test(w)) return 'cloud';
  return 'clear';
}

/** A band of sky that follows the hour and the weather, with the sun or the moon where the day has got to. */
export function skyBand(w) {
  if (!isLively()) return null;
  const hour = Number(w.hour) || 0;
  const kind = skyKind(hour);
  const progress = Math.max(0, Math.min(1, Number(w.dayProgress) || 0));
  const band = el('div', { class: `sky sky-${kind} wx-${weatherKind(w.weather)}`, 'aria-hidden': 'true' });
  const night = kind === 'night';
  const body = night ? el('span', { class: 'sky-body' }) : el('span', { class: 'sky-body sun' });
  if (night) body.append(moonGlyph(w.moonPhase, 18));
  body.style.left = `calc(${(progress * 100).toFixed(1)}% - 9px)`;
  band.append(body);
  return band;
}

// ---------- Atlas mini maps ----------

/** Chunks along a tile side at an Atlas zoom, times 32 blocks a chunk. Zoom 0 is 16 chunks, each step doubles. */
export const blocksPerTile = (zoom) => 32 * (zoom >= 0 ? 16 << zoom : 16 >> -zoom);

/** The Atlas address and its public worlds when Atlas answers healthy, or null. */
export async function atlasInfo(ctx) {
  if (!isLively()) return null;
  try {
    const suite = await ctx.suite();
    if (!suite.atlas || !suite.atlas.bound) return null;
    const [a, w] = await Promise.all([ctx.cached('/api/atlas', 60000, { quiet: true }), ctx.cached('/api/atlas/worlds', 60000, { quiet: true })]);
    if (!a || !a.url || !a.health || !a.health.ok) return null;
    const base = a.url.endsWith('/') ? a.url : `${a.url}/`;
    return { base, worlds: new Set((w.worlds || []).filter((x) => x.publicVisible !== false).map((x) => x.name)) };
  } catch { return null; }
}

/**
 * A mini map of 3 by 3 Atlas tiles with a marker in the middle. update(world, x, z) moves the marker at once,
 * and changes the tiles at most every 30 seconds, since Atlas limits tile requests per minute.
 */
export function miniMap(base, { zoom = -1, size = 200, tile = 120, label = 'Open in Atlas' } = {}) {
  const grid = el('div', { class: 'mm-grid', style: { width: `${tile * 3}px`, height: `${tile * 3}px` } });
  const dot = el('span', { class: 'mm-dot' });
  const view = el('div', { class: 'mm-view', style: { width: `${size}px`, height: `${size}px` } }, grid, dot);
  const link = el('a', { class: 'mm-link', target: '_blank', rel: 'noopener noreferrer', text: label });
  const caption = el('span', { class: 'mm-at faint' });
  const box = el('div', { class: 'minimap', style: { width: `${size}px` } }, view, el('div', { class: 'mm-foot' }, caption, link));
  const B = blocksPerTile(zoom);
  let built = null;
  const build = (world, tx0, ty0) => {
    grid.replaceChildren();
    for (let j = 0; j < 3; j++) {
      for (let i = 0; i < 3; i++) {
        const im = el('img', { src: `${base}api/tiles/${encodeURIComponent(world)}/${zoom}/${tx0 + i}/${ty0 + j}.png`, width: tile, height: tile, alt: '', decoding: 'async', draggable: 'false', style: { left: `${i * tile}px`, top: `${j * tile}px` } });
        im.addEventListener('load', () => im.classList.add('in'), { once: true });
        im.addEventListener('error', () => im.remove(), { once: true });
        grid.append(im);
      }
    }
    built = { world, tx0, ty0, at: Date.now() };
  };
  return {
    el: box,
    update(world, x, z, text) {
      if (!Number.isFinite(x) || !Number.isFinite(z)) return;
      const fx = x / B;
      const fz = z / B;
      const tx0 = Math.floor(fx) - 1;
      const ty0 = Math.floor(fz) - 1;
      const moved = !built || built.world !== world || built.tx0 !== tx0 || built.ty0 !== ty0;
      if (!built || built.world !== world || (moved && Date.now() - built.at >= 30000)) build(world, tx0, ty0);
      // Place the grid so the spot sits in the middle, kept inside the tiles that are loaded.
      const px = (fx - built.tx0) * tile;
      const pz = (fz - built.ty0) * tile;
      const left = Math.max(size - tile * 3, Math.min(0, size / 2 - px));
      const top = Math.max(size - tile * 3, Math.min(0, size / 2 - pz));
      grid.style.transform = `translate(${left.toFixed(1)}px, ${top.toFixed(1)}px)`;
      dot.style.left = `${Math.max(4, Math.min(size - 4, left + px)).toFixed(1)}px`;
      dot.style.top = `${Math.max(4, Math.min(size - 4, top + pz)).toFixed(1)}px`;
      const q = new URLSearchParams({ world, x: String(Math.round(x)), z: String(Math.round(z)), zoom: String(-1 - zoom) });
      link.href = `${base}?${q}`;
      caption.textContent = text || `${Math.round(x)}, ${Math.round(z)}`;
    },
  };
}

/** The engine marker image Atlas serves for a marker icon name, or null. */
export function markerIcon(base, name, size = 20) {
  if (!isLively() || !base || !name || !/^[A-Za-z0-9_]{1,40}$/.test(name)) return null;
  return img(`${base}api/marker-icons/${name}.png`, size, 'gi gi-marker');
}
