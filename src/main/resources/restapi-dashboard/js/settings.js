// The viewer's own settings, kept in this browser under one localStorage key. No imports, so every module can
// read them, format.js and lively.js included. The page applies the look settings to the root element here.

export const KEY = 'restapi-dash.settings';

/** The accent palette. Each colour has a value for the dark themes and one for the light themes. */
export const ACCENTS = [
  { id: 'blue', label: 'Blue', dark: '#3d8bfd', light: '#1f6fe0' },
  { id: 'gold', label: 'Gold', dark: '#d8b366', light: '#8c6619' },
  { id: 'green', label: 'Green', dark: '#3fb67a', light: '#1e8a52' },
  { id: 'teal', label: 'Teal', dark: '#46c1c9', light: '#13838b' },
  { id: 'violet', label: 'Violet', dark: '#b48cf0', light: '#7a4cc0' },
  { id: 'rose', label: 'Rose', dark: '#f0729a', light: '#c23a64' },
];

export const DEFAULTS = Object.freeze({
  view: 'lively',
  theme: 'system',
  density: 'comfortable',
  accent: '',
  fontSize: 'default',
  displayName: '',
  landing: 'overview',
  times: 'relative',
  clock: '24',
  me: '',
  fastPoll: 5,
  slowPoll: 60,
  followSeconds: 2,
  consoleLines: 2000,
  pauseHidden: true,
  publicUrl: '',
});

const ONE_OF = {
  view: ['lively', 'data'],
  theme: ['system', 'dark', 'light'],
  density: ['comfortable', 'compact'],
  accent: ['', ...ACCENTS.map((a) => a.id)],
  fontSize: ['small', 'default', 'large'],
  times: ['relative', 'absolute'],
  clock: ['24', '12'],
};
export const RANGES = {
  fastPoll: [3, 30],
  slowPoll: [30, 300],
  followSeconds: [1, 10],
  consoleLines: [100, 2000],
};

/** A public address as the page uses it, http or https with a trailing slash, or '' when it is not one. */
export function cleanUrl(v) {
  const s = String(v || '').trim();
  if (!s) return '';
  try {
    const u = new URL(s);
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return '';
    if (u.username || u.password || u.search || u.hash) return '';
    return `${u.origin}${u.pathname.endsWith('/') ? u.pathname : `${u.pathname}/`}`;
  } catch { return ''; }
}

/** One value checked against its rule. Answers undefined for a value that cannot be used. */
function clean(key, v) {
  if (!(key in DEFAULTS)) return undefined;
  if (key in ONE_OF) { const s = v == null ? '' : String(v); return ONE_OF[key].includes(s) ? s : undefined; }
  if (key in RANGES) {
    const n = Number(v);
    if (!Number.isFinite(n)) return undefined;
    const [lo, hi] = RANGES[key];
    return Math.round(Math.min(hi, Math.max(lo, n)));
  }
  if (key === 'pauseHidden') return typeof v === 'boolean' ? v : undefined;
  if (key === 'publicUrl') { const s = String(v ?? '').trim(); return s === '' ? '' : (cleanUrl(s) || undefined); }
  if (key === 'landing') return typeof v === 'string' && /^[a-z]+$/.test(v) ? v : undefined;
  if (key === 'displayName') return String(v ?? '').replace(/\s+/g, ' ').trim().slice(0, 60);
  if (key === 'me') return String(v ?? '').trim().slice(0, 40);
  return undefined;
}

function readStore() {
  let raw = null;
  try { raw = localStorage.getItem(KEY); } catch { /* storage is off */ }
  if (raw) { try { const o = JSON.parse(raw); if (o && typeof o === 'object') return o; } catch { /* broken, start again */ } }
  // Before the Settings page the view and the player name had keys of their own.
  const old = {};
  try {
    const v = localStorage.getItem('restapi-dash.view');
    if (v) old.view = v;
    const me = localStorage.getItem('restapi-dash.me');
    if (me) old.me = me;
  } catch { /* storage is off */ }
  return old;
}

const state = { ...DEFAULTS };
for (const [k, v] of Object.entries(readStore())) { const c = clean(k, v); if (c !== undefined) state[k] = c; }

function save() {
  // Only values that differ from the defaults are kept, so a later default reaches viewers who never changed it.
  const out = {};
  for (const k of Object.keys(DEFAULTS)) if (state[k] !== DEFAULTS[k]) out[k] = state[k];
  try {
    localStorage.setItem(KEY, JSON.stringify(out));
    localStorage.removeItem('restapi-dash.view');
    localStorage.removeItem('restapi-dash.me');
  } catch { /* kept for this visit only */ }
}

const listeners = new Set();
/** fn(changedKeys, state) after every change. Returns a function that stops listening. */
export function onChange(fn) { listeners.add(fn); return () => listeners.delete(fn); }
function notify(keys) {
  if (!keys.length) return;
  for (const fn of [...listeners]) { try { fn(keys, state); } catch (e) { console.warn(e); } }
}

export const get = (key) => state[key];
export const all = () => ({ ...state });

/** Sets several values. Unknown keys and unusable values are left out. Answers the keys that changed. */
export function set(patch) {
  const changed = [];
  for (const [k, v] of Object.entries(patch || {})) {
    const c = clean(k, v);
    if (c === undefined || state[k] === c) continue;
    state[k] = c;
    changed.push(k);
  }
  if (changed.length) { save(); applyLook(); notify(changed); }
  return changed;
}

export function reset() {
  const changed = Object.keys(DEFAULTS).filter((k) => state[k] !== DEFAULTS[k]);
  Object.assign(state, DEFAULTS);
  save();
  applyLook();
  notify(changed);
  return changed;
}

/** The settings as a file, every key with its value. */
export function exportText() {
  return `${JSON.stringify({ kind: 'restapi-dashboard-settings', version: 1, settings: all() }, null, 2)}\n`;
}

/** Reads a settings file. Answers { applied, ignored } with key names, or throws when the text is not one. */
export function importText(text) {
  let o;
  try { o = JSON.parse(text); } catch { throw new Error('The file is not JSON.'); }
  const src = o && typeof o === 'object' && o.settings && typeof o.settings === 'object' ? o.settings : o;
  if (!src || typeof src !== 'object' || Array.isArray(src)) throw new Error('The file holds no settings.');
  const next = { ...DEFAULTS };
  const ignored = [];
  let known = 0;
  for (const [k, v] of Object.entries(src)) {
    if (!(k in DEFAULTS)) { ignored.push(k); continue; }
    known++;
    const c = clean(k, v);
    if (c === undefined) ignored.push(k); else next[k] = c;
  }
  if (!known) throw new Error('The file holds no settings this page knows.');
  const changed = Object.keys(DEFAULTS).filter((k) => state[k] !== next[k]);
  Object.assign(state, next);
  save();
  applyLook();
  notify(changed);
  const applied = Object.keys(src).filter((k) => k in DEFAULTS && !ignored.includes(k)).length;
  return { applied, ignored, changed };
}

// ---------- refresh values for the panels ----------

/**
 * Interval getters for ctx.every. poll.fast() is the fast poll, poll.fast(3) three times it, poll.slow() the slow
 * poll. Each answers a function, so a changed value counts from the next refresh on.
 */
export const poll = {
  fast: (n = 1) => () => state.fastPoll * 1000 * n,
  slow: (n = 1) => () => state.slowPoll * 1000 * n,
};
export const followMs = () => state.followSeconds * 1000;
export const consoleLines = () => state.consoleLines;
export const pauseHidden = () => state.pauseHidden;

// ---------- server facts the page learns at start ----------

let serverPublicUrl = '';
/** The address server.js was started with by --public-url, used while the viewer has none of their own. */
export function setServerPublicUrl(v) { serverPublicUrl = cleanUrl(v); }
export const publicUrl = () => state.publicUrl || serverPublicUrl;

/** An absolute link to a page of this dashboard, on the public address when one is set. */
export function pageLink(hash = location.hash) {
  const base = publicUrl() || `${location.origin}${location.pathname}`;
  const h = String(hash || '').replace(/^#?\/?/, '');
  return `${base}${h ? `#/${h}` : ''}`;
}

// ---------- the look, applied to the root element ----------

const light = () => {
  if (state.theme === 'light') return true;
  if (state.theme === 'dark') return false;
  try { return matchMedia('(prefers-color-scheme: light)').matches; } catch { return false; }
};
export const isLight = light;

export function applyLook() {
  const root = document.documentElement;
  if (state.theme === 'system') delete root.dataset.theme; else root.dataset.theme = state.theme;
  root.dataset.density = state.density;
  root.dataset.font = state.fontSize;
  const a = ACCENTS.find((x) => x.id === state.accent);
  if (a) {
    const lt = light();
    const c = lt ? a.light : a.dark;
    root.dataset.accent = a.id;
    root.style.setProperty('--accent', c);
    root.style.setProperty('--accent-soft', `color-mix(in srgb, ${c} ${lt ? 13 : 17}%, transparent)`);
    // Text on a filled accent button, dark on the bright dark theme colours and white on the deep light ones.
    root.style.setProperty('--accent-ink', lt ? '#ffffff' : '#10141a');
  } else {
    delete root.dataset.accent;
    for (const p of ['--accent', '--accent-soft', '--accent-ink']) root.style.removeProperty(p);
  }
}

try { matchMedia('(prefers-color-scheme: light)').addEventListener('change', () => { if (state.theme === 'system') applyLook(); }); } catch { /* old browser */ }
