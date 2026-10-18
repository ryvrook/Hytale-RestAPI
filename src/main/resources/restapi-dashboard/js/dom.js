// Small DOM helpers. Data is always set as text, never as markup.

import { num, NONE, isNum, timeText, timeTitle } from './format.js';
import { bust } from './lively.js';

export function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v == null || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k === 'text') el.textContent = v;
      else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
      else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
      else if (k === 'dataset') Object.assign(el.dataset, v);
      else if (v === true) el.setAttribute(k, '');
      else el.setAttribute(k, v);
    }
  }
  append(el, children);
  return el;
}

export function append(el, children) {
  for (const c of children.flat(Infinity)) {
    if (c == null || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return el;
}

/** SVG built from a trusted string made by our own chart code. */
export function svg(markup) {
  const t = document.createElement('template');
  t.innerHTML = markup.trim();
  return t.content.firstElementChild;
}

export const frag = (...c) => append(document.createDocumentFragment(), c);

/**
 * Runs fn, which redraws el, and puts the scroll of el's long tables and feeds back where it was, so a poll that
 * redraws a table does not throw the reader back to its top.
 */
export function keepScroll(el, fn) {
  const sel = '.table-wrap, .feed';
  const before = [...el.querySelectorAll(sel)].map((e) => [e.scrollTop, e.scrollLeft]);
  const out = fn();
  if (before.some(([t, l]) => t || l)) {
    [...el.querySelectorAll(sel)].forEach((e, i) => { if (before[i]) { e.scrollTop = before[i][0]; e.scrollLeft = before[i][1]; } });
  }
  return out;
}

/** A time element with relative text and the absolute time on hover. Kept fresh by refreshTimes. */
export function time(t) {
  if (t == null || t === '') return h('span', { class: 'faint', text: NONE });
  const el = h('time', { datetime: typeof t === 'number' ? new Date(t).toISOString() : t, title: timeTitle(t), text: timeText(t) });
  el.dataset.rel = typeof t === 'number' ? String(t) : t;
  return el;
}

export function refreshTimes(root = document) {
  const now = Date.now();
  for (const el of root.querySelectorAll('time[data-rel]')) {
    const v = el.dataset.rel;
    const t = /^\d+$/.test(v) ? Number(v) : v;
    el.textContent = timeText(t, now);
    el.title = timeTitle(t);
  }
}

export function badge(text, tone = '') {
  return h('span', { class: `badge ${tone}`.trim(), text });
}

export function dot(tone) {
  return h('span', { class: `dot ${tone || ''}`.trim() });
}

export function meter(value, max, { tone, text, title } = {}) {
  const ratio = isNum(value) && isNum(max) && max > 0 ? Math.max(0, Math.min(1, value / max)) : 0;
  const t = tone || (ratio > 0.9 ? 'bad' : ratio > 0.75 ? 'warn' : 'ok');
  const bar = h('div', { class: `meter ${t}`, role: 'meter', 'aria-valuenow': isNum(value) ? value : 0, 'aria-valuemin': 0, 'aria-valuemax': isNum(max) ? max : 1, title },
    h('span', { style: { width: `${(ratio * 100).toFixed(1)}%` } }));
  if (text === undefined) return bar;
  return h('div', { class: 'meter-row' }, bar, h('span', { class: 'meter-text', text }));
}

/** Health style bar, low is bad. */
export function lifeBar(value, max, digits = 0) {
  const ratio = isNum(value) && max ? value / max : 0;
  const tone = ratio < 0.25 ? 'bad' : ratio < 0.5 ? 'warn' : 'ok';
  return meter(value, max, { tone, text: `${num(value, digits)} of ${num(max, digits)}` });
}

export function card(title, body, { actions, note, cls = '', flush = false, id } = {}) {
  const head = title || actions || note
    ? h('header', null, h('div', null, typeof title === 'string' ? h('h2', { text: title }) : title, note ? h('div', { class: 'note' }, note) : null), actions ? h('div', { class: 'actions' }, actions) : null)
    : null;
  return h('section', { class: `card ${cls}`.trim(), id }, head, h('div', { class: `body${flush ? ' flush' : ''}` }, body));
}

export function tile(label, value, { unit, sub, meterEl, title, tone } = {}) {
  return h('div', { class: 'tile', title },
    h('div', { class: 'label', text: label }),
    h('div', { class: `value${tone ? ` ${tone}` : ''}` }, h('span', null, value), unit ? h('small', { text: unit }) : null),
    meterEl ? h('div', { class: 'tile-meter' }, meterEl) : null,
    sub != null ? h('div', { class: 'sub' }, sub) : null);
}

export function kv(pairs) {
  const dl = h('dl', { class: 'kv' });
  for (const pair of pairs) {
    // A null pair is a row that does not apply here.
    if (!pair) continue;
    const [k, v] = pair;
    if (v === undefined) continue;
    dl.append(h('dt', { text: k }), h('dd', null, v == null || v === '' ? h('span', { class: 'faint', text: NONE }) : v));
  }
  return dl;
}

export function empty(text) {
  return h('div', { class: 'empty', text });
}

export function loading(text = 'Loading') {
  return h('div', { class: 'loading', text });
}

/** An error from api.js as a readable box. */
export function errorBox(err, what) {
  const status = err && err.status ? `HTTP ${err.status}` : err && err.code === 'timeout' ? 'Timed out' : 'No answer';
  const code = err && err.code && err.status ? ` ${err.code}` : '';
  return h('div', { class: 'err', role: 'alert' },
    h('strong', { text: `${what ? `${what} failed. ` : ''}${status}${code}` }),
    h('span', { text: (err && err.message) || String(err) }));
}

/**
 * A table. columns: [{ key, label, num, render(row), sort(row), cls, title }].
 * Sorting is kept on the element between re-renders when opts.state is passed.
 */
export function table(columns, rows, { emptyText = 'Nothing to show.', state, tall = false, foot, rowClass, limit } = {}) {
  const st = state || {};
  let data = rows.slice();
  if (st.sortKey) {
    const col = columns.find((c) => c.key === st.sortKey);
    if (col) {
      const get = col.sort || ((r) => r[col.key]);
      data.sort((a, b) => {
        const x = get(a), y = get(b);
        let r;
        if (x == null && y == null) r = 0; else if (x == null) r = 1; else if (y == null) r = -1;
        else if (typeof x === 'number' && typeof y === 'number') r = x - y;
        else r = String(x).localeCompare(String(y), 'en', { numeric: true });
        return st.sortDir === 'desc' ? -r : r;
      });
    }
  }
  const total = data.length;
  if (limit && data.length > limit) data = data.slice(0, limit);
  const box = h('div', { class: 'tbl' });
  const wrap = h('div', { class: `table-wrap${tall ? ' tall' : ''}` });
  const render = () => {
    const thead = h('thead', null, h('tr', null, columns.map((c) => {
      const sortable = !!state && c.sortable !== false;
      const th = h('th', { class: [c.num ? 'num' : '', sortable ? 'sortable' : ''].join(' ').trim() || null, title: c.title, scope: 'col' }, c.label,
        st.sortKey === c.key ? h('span', { class: 'dir', text: st.sortDir === 'desc' ? '↓' : '↑' }) : null);
      if (sortable) {
        th.addEventListener('click', () => {
          if (st.sortKey === c.key) st.sortDir = st.sortDir === 'desc' ? 'asc' : 'desc';
          else { st.sortKey = c.key; st.sortDir = c.num ? 'desc' : 'asc'; }
          box.replaceWith(table(columns, rows, { emptyText, state, tall, foot, rowClass, limit }));
        });
      }
      return th;
    })));
    const tbody = h('tbody');
    if (!data.length) tbody.append(h('tr', null, h('td', { colspan: columns.length, class: 'empty', text: emptyText })));
    for (const r of data) {
      if (r && r.__group) { tbody.append(h('tr', { class: 'group' }, h('td', { colspan: columns.length }, r.__group))); continue; }
      tbody.append(h('tr', { class: rowClass ? rowClass(r) : null }, columns.map((c) => {
        const v = c.render ? c.render(r) : r[c.key];
        const shown = v == null || v === '' ? (c.num ? NONE : h('span', { class: 'faint', text: NONE })) : typeof v === 'number' ? num(v) : v;
        return h('td', { class: [c.num ? 'num' : '', c.cls || ''].join(' ').trim() || null }, shown);
      })));
    }
    wrap.append(h('table', null, thead, tbody));
  };
  render();
  const footText = foot || (limit && total > limit ? `Showing ${num(limit)} of ${num(total)} rows.` : null);
  box.append(wrap);
  if (footText) box.append(h('div', { class: 'table-foot', text: footText }));
  return box;
}

export function seg(options, value, onChange) {
  const el = h('div', { class: 'seg', role: 'group' });
  for (const [v, label] of options) {
    el.append(h('button', { type: 'button', class: v === value ? 'on' : null, 'aria-pressed': v === value ? 'true' : 'false', text: label, onclick: () => {
      for (const b of el.children) { b.classList.remove('on'); b.setAttribute('aria-pressed', 'false'); }
      el.children[options.findIndex((o) => o[0] === v)].classList.add('on');
      el.children[options.findIndex((o) => o[0] === v)].setAttribute('aria-pressed', 'true');
      onChange(v);
    } }));
  }
  return el;
}

export function select(options, value, onChange, attrs = {}) {
  const el = h('select', attrs, options.map(([v, label]) => h('option', { value: v, selected: v === value ? true : null, text: label })));
  el.addEventListener('change', () => onChange(el.value));
  return el;
}

export function search(placeholder, value, onInput) {
  const el = h('input', { type: 'search', placeholder, value: value || '', 'aria-label': placeholder });
  el.addEventListener('input', () => onInput(el.value.trim().toLowerCase()));
  return el;
}

export function button(text, onClick, cls = 'btn') {
  return h('button', { type: 'button', class: cls, text, onclick: onClick });
}

export function link(href, text) {
  return h('a', { href, text });
}

export function playerLink(name, uuid, { noBust = false } = {}) {
  const label = name || (uuid ? String(uuid).slice(0, 8) : NONE);
  if (!uuid && !name) return h('span', { class: 'faint', text: NONE });
  const a = h('a', { href: `#/players/${encodeURIComponent(name || uuid)}`, text: label, title: uuid || null });
  // The Lively view puts the player's bust in front of the name, the Just data view keeps the plain link.
  const b = noBust ? null : bust(name, uuid, 'xs');
  return b ? h('span', { class: 'plink' }, b, a) : a;
}

export function pageHead(title, sub, actions, crumbs) {
  return h('div', { class: 'page-head' },
    h('div', null, crumbs ? h('div', { class: 'crumbs' }, crumbs) : null, h('h1', { text: title }), sub ? h('p', null, sub) : null),
    actions ? h('div', { class: 'actions' }, actions) : null);
}

/** A code element for long ids such as counter keys and permissions, with break points after : _ . / and -. */
export function codeWrap(text) {
  const el = h('code', { class: 'idwrap', title: String(text) });
  const parts = String(text).split(/(?<=[:_./-])/);
  parts.forEach((p, i) => { el.append(p); if (i < parts.length - 1) el.append(document.createElement('wbr')); });
  return el;
}

/** A badge for an id, a permission or a label pair, breaking only after : _ . / or - or a space, never inside a word. */
export function idBadge(text, title) {
  const el = codeWrap(text);
  el.className = 'badge idwrap';
  if (title) el.title = title;
  return el;
}
