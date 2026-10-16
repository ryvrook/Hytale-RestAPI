// The RestAPI docs page. Reads openapi.json from the same folder, renders every route, and sends requests with a
// key that lives only in this script's memory. No outside scripts, no storage, no inline code, so the page runs
// under a Content-Security-Policy of default-src 'self'.
(function () {
  'use strict';

  var base = new URL('.', location.href);
  var tryIt = document.documentElement.getAttribute('data-try-it') !== 'false';
  var key = '';
  var spec = null;
  var METHODS = ['get', 'post', 'put', 'patch', 'delete'];

  function $(id) { return document.getElementById(id); }

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) { e.className = cls; }
    if (text !== undefined && text !== null) { e.textContent = String(text); }
    return e;
  }

  function headers(extra) {
    var h = extra || {};
    if (key) { h['Authorization'] = 'Bearer ' + key; }
    return h;
  }

  function showError(text) {
    var e = $('error');
    e.textContent = text || '';
    e.hidden = !text;
  }

  function load() {
    showError('');
    fetch(new URL('openapi.json', base), { headers: headers(), cache: 'no-store' })
      .then(function (r) {
        if (r.status === 401) {
          throw new Error('openapi.json needs a key with the read scope here. Paste one above and press Load again.');
        }
        if (!r.ok) { throw new Error('openapi.json answered ' + r.status + '.'); }
        return r.json();
      })
      .then(function (doc) { spec = doc; render(); })
      .catch(function (e) { showError(e.message); });
  }

  function render() {
    var info = spec.info || {};
    document.title = (info.title || 'RestAPI') + ' docs';
    $('title').textContent = info.title || 'RestAPI';
    var count = 0;
    Object.keys(spec.paths || {}).forEach(function (p) {
      METHODS.forEach(function (m) { if (spec.paths[p][m]) { count++; } });
    });
    $('meta').textContent = 'Version ' + (info.version || '?') + ', OpenAPI ' + spec.openapi + ', ' + count
      + (count === 1 ? ' route' : ' routes') + ' mounted now.';
    $('about').textContent = info.description || '';

    var byTag = {};
    var order = [];
    (spec.tags || []).forEach(function (t) { byTag[t.name] = { tag: t, ops: [] }; order.push(t.name); });
    Object.keys(spec.paths || {}).forEach(function (path) {
      METHODS.forEach(function (m) {
        var op = spec.paths[path][m];
        if (!op) { return; }
        var tag = (op.tags && op.tags[0]) || 'other';
        if (!byTag[tag]) { byTag[tag] = { tag: { name: tag }, ops: [] }; order.push(tag); }
        byTag[tag].ops.push({ path: path, method: m, op: op });
      });
    });

    var toc = $('toc');
    var groups = $('groups');
    toc.textContent = '';
    groups.textContent = '';
    order.forEach(function (name) {
      var g = byTag[name];
      if (!g.ops.length) { return; }
      var id = 'g-' + name.replace(/[^a-z0-9]+/gi, '-');
      var link = el('a', null, name + ' (' + g.ops.length + ')');
      link.href = '#' + id;
      toc.appendChild(link);
      var section = el('section', 'group');
      section.id = id;
      section.appendChild(el('h2', null, name));
      if (g.tag.description) { section.appendChild(el('p', 'muted', g.tag.description)); }
      g.ops.forEach(function (o) { section.appendChild(operation(o)); });
      groups.appendChild(section);
    });
    applyFilter();
  }

  function operation(o) {
    var op = o.op;
    var d = el('details', 'op');
    d.setAttribute('data-search', [o.method, o.path, op['x-scope'], (op.tags || []).join(' '), op.summary || '']
      .join(' ').toLowerCase());
    var s = el('summary');
    s.appendChild(el('span', 'method m-' + o.method, o.method.toUpperCase()));
    s.appendChild(el('span', 'path', o.path));
    s.appendChild(el('span', 'sum', op.summary || ''));
    var chips = el('span', 'chips');
    chips.appendChild(el('span', 'chip', op['x-scope']));
    if (op['x-access'] && op['x-access'] !== 'read') {
      chips.appendChild(el('span', 'chip' + (op['x-access'] === 'dangerous' ? ' danger' : ''), op['x-access']));
    }
    if (op['x-open']) { chips.appendChild(el('span', 'chip open', 'no key needed')); }
    s.appendChild(chips);
    d.appendChild(s);

    var body = el('div', 'body');
    d.appendChild(body);
    var filled = false;
    d.addEventListener('toggle', function () {
      if (d.open && !filled) { filled = true; fill(body, o); }
    });
    return d;
  }

  function fill(body, o) {
    var op = o.op;
    if (op.description && op.description !== op.summary) { body.appendChild(el('p', null, op.description)); }

    var params = op.parameters || [];
    if (params.length) {
      body.appendChild(el('h4', null, 'Parameters'));
      var t = el('table');
      params.forEach(function (p) {
        var tr = el('tr');
        tr.appendChild(el('td', null, p.name));
        tr.appendChild(el('td', 'muted', p['in']));
        tr.appendChild(el('td', null, p.description || ''));
        t.appendChild(tr);
      });
      body.appendChild(t);
    }

    var reqExample = example(op.requestBody && op.requestBody.content);
    if (reqExample !== null) {
      body.appendChild(el('h4', null, 'Request body'));
      body.appendChild(el('pre', null, reqExample));
    }

    var responses = op.responses || {};
    Object.keys(responses).forEach(function (code) {
      var r = responses[code];
      if (r.$ref) { return; }
      var ex = example(r.content);
      body.appendChild(el('h4', null, 'Answer ' + code));
      body.appendChild(el('p', 'muted', r.description || ''));
      if (ex !== null) { body.appendChild(el('pre', null, ex)); }
    });

    if (tryIt) { body.appendChild(tryForm(o, reqExample)); }
  }

  function example(content) {
    if (!content) { return null; }
    var types = Object.keys(content);
    for (var i = 0; i < types.length; i++) {
      var ex = content[types[i]].example;
      if (ex === undefined) { continue; }
      return typeof ex === 'string' ? ex : JSON.stringify(ex, null, 2);
    }
    return null;
  }

  function tryForm(o, reqExample) {
    var op = o.op;
    var box = el('div', 'try');
    box.appendChild(el('h4', null, 'Send'));
    var inputs = [];
    (op.parameters || []).forEach(function (p) {
      var label = el('label');
      label.appendChild(el('span', null, p.name + (p['in'] === 'path' ? '' : ' (query)')));
      var input = el('input');
      input.type = 'text';
      input.spellcheck = false;
      label.appendChild(input);
      box.appendChild(label);
      inputs.push({ p: p, input: input });
    });
    var bodyInput = null;
    if (o.method !== 'get' && o.method !== 'delete') {
      bodyInput = el('textarea');
      bodyInput.spellcheck = false;
      bodyInput.value = reqExample !== null ? reqExample : '';
      box.appendChild(bodyInput);
    }
    var button = el('button', null, o.method === 'get' ? 'Send' : 'Send ' + o.method.toUpperCase());
    button.type = 'button';
    box.appendChild(button);
    var status = el('p', 'status');
    var out = el('pre');
    out.hidden = true;
    box.appendChild(status);
    box.appendChild(out);

    button.addEventListener('click', function () {
      var path = o.path;
      var query = [];
      var missing = null;
      inputs.forEach(function (x) {
        var v = x.input.value.trim();
        if (x.p['in'] === 'path') {
          if (!v) { missing = missing || x.p.name; }
          path = path.replace('{' + x.p.name + '}', encodeURIComponent(v));
        } else if (v) {
          query.push(encodeURIComponent(x.p.name) + '=' + encodeURIComponent(v));
        }
      });
      if (missing) { status.className = 'status bad'; status.textContent = 'Fill in ' + missing + ' first.'; return; }
      if (o.method !== 'get' && !confirmWrite(op)) { return; }
      var url = new URL(path.replace(/^\//, '') + (query.length ? '?' + query.join('&') : ''), base);
      send(o, url, bodyInput ? bodyInput.value : null, status, out);
    });
    return box;
  }

  function confirmWrite(op) {
    if (op['x-access'] === 'dangerous') {
      return window.confirm('This route is marked dangerous. Send it to the server?');
    }
    return true;
  }

  function send(o, url, bodyText, status, out) {
    var init = { method: o.method.toUpperCase(), headers: headers(), cache: 'no-store' };
    if (bodyText !== null && bodyText.trim()) {
      init.headers['Content-Type'] = 'application/json';
      init.body = bodyText;
    }
    var stream = isStream(o.op);
    var abort = new AbortController();
    init.signal = abort.signal;
    status.className = 'status';
    status.textContent = stream ? 'Reading the stream for 10 seconds' : 'Sending';
    out.hidden = true;
    var started = Date.now();
    fetch(url, init).then(function (r) {
      status.className = 'status' + (r.ok ? '' : ' bad');
      status.textContent = r.status + ' ' + r.statusText + ' in ' + (Date.now() - started) + ' ms';
      if (stream && r.ok && r.body) { return readStream(r, abort, out); }
      return r.text().then(function (text) {
        out.textContent = pretty(text);
        out.hidden = !text;
      });
    }).catch(function (e) {
      if (e.name === 'AbortError') { return; }
      status.className = 'status bad';
      status.textContent = 'The request failed, ' + e.message;
    });
  }

  function isStream(op) {
    var ok = op.responses && op.responses['200'];
    return !!(ok && ok.content && ok.content['text/event-stream']);
  }

  function readStream(r, abort, out) {
    var reader = r.body.getReader();
    var decoder = new TextDecoder();
    out.textContent = '';
    out.hidden = false;
    var timer = setTimeout(function () { abort.abort(); }, 10000);
    function next() {
      return reader.read().then(function (part) {
        if (part.done) { clearTimeout(timer); return; }
        out.textContent += decoder.decode(part.value, { stream: true });
        return next();
      });
    }
    return next();
  }

  function pretty(text) {
    try { return JSON.stringify(JSON.parse(text), null, 2); } catch (e) { return text; }
  }

  function applyFilter() {
    var q = $('filter').value.trim().toLowerCase();
    var ops = document.querySelectorAll('.op');
    for (var i = 0; i < ops.length; i++) {
      ops[i].hidden = q !== '' && ops[i].getAttribute('data-search').indexOf(q) < 0;
    }
    var groups = document.querySelectorAll('.group');
    for (var j = 0; j < groups.length; j++) {
      groups[j].hidden = groups[j].querySelectorAll('.op:not([hidden])').length === 0;
    }
  }

  document.addEventListener('DOMContentLoaded', function () {
    $('key').addEventListener('input', function (e) { key = e.target.value.trim(); });
    $('filter').addEventListener('input', applyFilter);
    $('reload').addEventListener('click', load);
    load();
  });
}());
