// Router, polling scheduler, heartbeat and the client side sample ring.

import { get, onStatus, clearErrors, stream, caps, loadCaps, logout } from './api.js';
import { h, refreshTimes, errorBox, svg } from './dom.js';
import { clock, relTime, duration, absTime } from './format.js';
import { currentView, applyView, prepare as prepareLively, modIcon } from './lively.js';
import * as settings from './settings.js';

import overview from './panels/overview.js';
import performance from './panels/performance.js';
import players from './panels/players.js';
import worlds from './panels/worlds.js';
import suite, { SUITE_MODS } from './panels/suite.js';
import events from './panels/events.js';
import consolePanel from './panels/console.js';
import plugins from './panels/plugins.js';
import chat from './panels/chat.js';
import moderation from './panels/moderation.js';
import serverPanel from './panels/server.js';
import settingsPanel from './panels/settings.js';

/** The version of this dashboard, shown on the Settings page. */
export const DASHBOARD_VERSION = '1.6.0';

const SECTIONS = [
  { id: 'overview', label: 'Overview', panel: overview },
  { id: 'performance', label: 'Performance', panel: performance },
  { id: 'chat', label: 'Chat', panel: chat },
  { id: 'console', label: 'Console', panel: consolePanel },
  { id: 'players', label: 'Players', panel: players },
  { id: 'moderation', label: 'Moderation', panel: moderation },
  { id: 'worlds', label: 'Worlds', panel: worlds },
  { id: 'plugins', label: 'Plugins', panel: plugins },
  { id: 'server', label: 'Server', panel: serverPanel },
  { id: 'events', label: 'Events and webhooks', panel: events },
  { id: 'suite', label: 'Suite', panel: suite },
];
/** Pinned to the bottom of the nav, apart from the sections. */
const SETTINGS = { id: 'settings', label: 'Settings', panel: settingsPanel };
const GEAR = '<svg class="gear" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>';
/** Old links keep working. The Admin page became Moderation and Server. */
const ALIASES = { admin: 'moderation' };


const $ = (id) => document.getElementById(id);
const main = $('main');
const nav = $('nav');

// ---------- sample ring (last 10 minutes) ----------

const RING_MS = 10 * 60 * 1000;
const ring = [];
const ringListeners = new Set();

function recordServer(s) {
  if (!s || !s.jvm) return;
  const now = Date.now();
  const last = ring.at(-1);
  if (last && now - last.t < 2000) return;
  ring.push({
    t: now,
    worlds: Object.fromEntries((s.worlds || []).map((w) => [w.name, { tps: w.tps, avg: w.tickAvgMs, max: w.tickMaxMs, stalled: w.stalled }])),
    heapUsed: s.jvm.heapUsedMb, heapCommitted: s.jvm.heapCommittedMb, heapMax: s.jvm.heapMaxMb,
    players: s.players ? s.players.online : null, load: s.jvm.systemLoad, cpu: s.jvm.processCpu,
  });
  while (ring.length && now - ring[0].t > RING_MS) ring.shift();
  for (const fn of ringListeners) fn(ring);
}

// ---------- shared caches ----------

const cache = new Map();
/** GET with a short cache, for lookups that many panels share such as the suite table and player names. */
async function cached(path, maxAgeMs = 30000, opts) {
  const c = cache.get(path);
  if (c && Date.now() - c.at < maxAgeMs) return c.promise;
  const promise = get(path, opts);
  cache.set(path, { at: Date.now(), promise });
  promise.catch(() => cache.delete(path));
  return promise;
}

async function suiteTable() {
  const s = await cached('/api/suite', 15000);
  return Object.fromEntries(s.mods.map((m) => [m.id, m]));
}

/** uuid to name, from the players RestAPI knows. */
async function names() {
  const out = new Map();
  try {
    const d = await cached('/api/players/offline?limit=500', 60000);
    for (const p of d.players) if (p.name) out.set(p.uuid, p.name);
  } catch { /* names are a nicety */ }
  return out;
}

// ---------- scheduler ----------

let current = null;

function makeCtx(params) {
  const tasks = [];
  const cleanups = [];
  const abort = new AbortController();
  const ctx = {
    params,
    signal: abort.signal,
    sections: SECTIONS,
    version: DASHBOARD_VERSION,
    get: (path, opts = {}) => get(path, { signal: abort.signal, ...opts }),
    cached,
    suite: suiteTable,
    names,
    ring: () => ring,
    onRing(fn) { ringListeners.add(fn); cleanups.push(() => ringListeners.delete(fn)); },
    recordServer,
    health: () => lastHealth,
    /** Run fn now and then every ms while the tab is visible. A failing task backs off up to a minute. */
    every(ms, fn, { immediate = true } = {}) {
      // ms is a number or a getter from settings.poll, read again before every wait so a new value counts at once.
      const msOf = () => Math.max(1000, Number(typeof ms === 'function' ? ms() : ms) || 5000);
      const t = { msOf, fn, delay: msOf(), timer: null, running: false, lastRun: 0 };
      const run = async () => {
        if (abort.signal.aborted) return;
        if (document.hidden && settings.pauseHidden()) { t.timer = null; return; }
        t.running = true;
        t.lastRun = Date.now();
        let ok = true;
        try { const r = await fn(); if (r === false) ok = false; } catch (e) { ok = false; if (e && e.code !== 'aborted') console.warn(e); }
        t.running = false;
        if (abort.signal.aborted) return;
        const base = t.msOf();
        t.delay = ok ? base : Math.min(t.delay * 2, Math.max(60000, base));
        // now() can start a run while another is in flight, so drop any timer the other run set to keep one chain.
        clearTimeout(t.timer);
        t.timer = setTimeout(run, t.delay);
        stamp();
      };
      t.run = run;
      tasks.push(t);
      if (immediate) run(); else t.timer = setTimeout(run, t.msOf());
      return { now: () => { clearTimeout(t.timer); return run(); } };
    },
    stream(path, opts) {
      // A panel can ask for a stream after an await, when the user has already left the section.
      if (abort.signal.aborted) return { close() {}, get lastId() { return null; } };
      const s = stream(path, opts);
      cleanups.push(() => s.close());
      return s;
    },
    cleanup(fn) { cleanups.push(fn); },
    resume() {
      for (const t of tasks) {
        if (t.running) continue;
        if (!t.timer || Date.now() - t.lastRun >= t.msOf()) { clearTimeout(t.timer); t.run(); }
      }
    },
    pause() {
      for (const t of tasks) { clearTimeout(t.timer); t.timer = null; }
    },
    destroy() {
      abort.abort();
      for (const t of tasks) clearTimeout(t.timer);
      for (const fn of cleanups) { try { fn(); } catch { /* ignore */ } }
    },
  };
  return ctx;
}

function parseHash() {
  const raw = location.hash.replace(/^#\/?/, '');
  const parts = raw.split('/').filter(Boolean).map((p) => { try { return decodeURIComponent(p); } catch { return p; } });
  const id = ALIASES[parts[0]] || parts[0];
  const section = id === SETTINGS.id ? SETTINGS : SECTIONS.find((s) => s.id === id) || SECTIONS[0];
  return { section, rest: parts.slice(1) };
}

function renderNav(active, rest) {
  nav.replaceChildren();
  for (const s of SECTIONS) {
    const a = h('a', { href: `#/${s.id}`, class: s === active && !(s.id === 'suite' && rest[0]) ? 'active' : null, text: s.label });
    if (s === active) a.setAttribute('aria-current', 'page');
    nav.append(a);
    if (s.id === 'suite') {
      for (const [id, label] of SUITE_MODS) {
        const icon = modIcon(id, 18);
        nav.append(h('a', { href: `#/suite/${id}`, class: `sub${active.id === 'suite' && rest[0] === id ? ' active' : ''}` }, icon ? h('span', { class: 'mod-name' }, icon, h('span', { text: label })) : label));
      }
    }
  }
  const on = active === SETTINGS;
  const a = h('a', { href: `#/${SETTINGS.id}`, class: `nav-settings${on ? ' active' : ''}`, title: 'Your own settings for this dashboard' }, h('span', { class: 'mod-name' }, svg(GEAR), h('span', { text: SETTINGS.label })));
  if (on) a.setAttribute('aria-current', 'page');
  nav.append(h('div', { class: 'nav-sep', 'aria-hidden': 'true' }), a);
}

function route() {
  const { section, rest } = parseHash();
  if (current) current.ctx.destroy();
  renderNav(section, rest);
  const ctx = makeCtx(rest);
  current = { section, ctx };
  main.replaceChildren();
  try {
    section.panel.mount(main, ctx, rest);
  } catch (e) {
    console.error(e);
    main.append(errorBox(e, `${section.label} page`));
  }
  document.title = `${section.label} · ${settings.get('displayName') || 'RestAPI dashboard'}`;
  window.scrollTo(0, 0);
  stamp();
}

function stamp() {
  $('updated').textContent = `Updated ${clock(Date.now())}`;
}

// ---------- header ----------

let lastHealth = null;
async function heartbeat() {
  const pill = $('health-pill');
  if (document.hidden && settings.pauseHidden()) return;
  try {
    const d = await get('/api/health', { timeout: 6000, allowStatus: [503] });
    lastHealth = d;
    const status = d.status || 'unknown';
    const tone = status === 'ok' ? 'ok' : status === 'stalled' ? 'bad' : 'warn';
    pill.className = `pill ${tone}`;
    const stalled = d.stalledWorlds && d.stalledWorlds.length ? `, stalled ${d.stalledWorlds.join(', ')}` : '';
    pill.textContent = status === 'ok' ? 'Healthy' : status === 'stalled' ? 'World stalled' : status;
    pill.title = `RestAPI ${d.restapi || ''}, status ${status}${stalled}${d.uptimeSeconds != null ? `, RestAPI up ${duration(d.uptimeSeconds)}` : ''}${d.siblingsBound != null ? `, ${d.siblingsBound} suite mods bound` : ''}`;
  } catch (e) {
    lastHealth = null;
    pill.className = 'pill bad';
    pill.textContent = 'Unreachable';
    pill.title = e.message;
  }
}


// ---------- quick jump ----------

let jumpItems = null;
async function jumpList() {
  const withLogout = (list) => (caps.session ? [...list, { label: 'Log out', kind: 'end this session', run: logout }] : list);
  if (jumpItems && Date.now() - jumpItems.at < 60000) return withLogout(jumpItems.list);
  const list = [];
  for (const s of [...SECTIONS, SETTINGS]) list.push({ label: s.label, kind: 'page', href: `#/${s.id}` });
  for (const [id, label] of SUITE_MODS) list.push({ label, kind: 'suite page', href: `#/suite/${id}` });
  const [pl, wl, plug] = await Promise.all([
    cached('/api/players/offline?limit=500', 60000, { quiet: true }).catch(() => null),
    cached('/api/worlds', 30000, { quiet: true }).catch(() => null),
    cached('/api/plugins', 60000, { quiet: true }).catch(() => null),
  ]);
  for (const p of pl?.players || []) if (p.name) list.push({ label: p.name, kind: p.online ? 'player, online' : 'player', href: `#/players/${encodeURIComponent(p.name)}` });
  for (const w of wl?.worlds || []) list.push({ label: w.name, kind: 'world', href: '#/worlds' });
  for (const p of plug?.plugins || []) list.push({ label: p.id, kind: 'plugin', href: `#/plugins/${encodeURIComponent(p.id)}` });
  jumpItems = { at: Date.now(), list };
  return withLogout(list);
}

function setupPalette() {
  const box = $('palette');
  const inp = $('palette-input');
  const ul = $('palette-list');
  if (!box || !inp || !ul) return;
  box.hidden = false;
  let shown = [];
  let pick = 0;
  const close = () => { ul.hidden = true; shown = []; };
  const go = (item) => { if (!item) return; if (item.run) item.run(); else location.hash = item.href; inp.value = ''; close(); inp.blur(); };
  const draw = () => {
    ul.replaceChildren(...shown.map((it, i) => {
      const li = h('li', { role: 'option', class: i === pick ? 'on' : null, 'aria-selected': i === pick ? 'true' : 'false' }, h('span', { text: it.label }), h('span', { class: 'kind', text: it.kind }));
      li.addEventListener('mousedown', (e) => { e.preventDefault(); go(it); });
      return li;
    }));
    ul.hidden = !shown.length;
  };
  const update = async () => {
    const q = inp.value.trim().toLowerCase();
    if (!q) { close(); return; }
    const list = await jumpList();
    if (inp.value.trim().toLowerCase() !== q) return;
    const starts = list.filter((x) => x.label.toLowerCase().startsWith(q));
    const has = list.filter((x) => !x.label.toLowerCase().startsWith(q) && x.label.toLowerCase().includes(q));
    shown = [...starts, ...has].slice(0, 8);
    pick = 0;
    draw();
  };
  inp.addEventListener('input', update);
  inp.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowDown') { pick = Math.min(shown.length - 1, pick + 1); draw(); e.preventDefault(); }
    else if (e.key === 'ArrowUp') { pick = Math.max(0, pick - 1); draw(); e.preventDefault(); }
    else if (e.key === 'Enter') { go(shown[pick]); e.preventDefault(); }
    else if (e.key === 'Escape') { inp.value = ''; close(); inp.blur(); }
  });
  inp.addEventListener('blur', () => setTimeout(close, 100));
  document.addEventListener('keydown', (e) => {
    if (e.key !== '/' || e.metaKey || e.ctrlKey || e.altKey) return;
    const t = e.target;
    if (t && (t.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName))) return;
    e.preventDefault();
    inp.focus();
  });
}

/** The server's name in the header, or the display name from the settings in its place. */
let apiServer = null;
function drawServerName() {
  const el = $('server-name');
  const own = settings.get('displayName');
  const name = own || (apiServer ? apiServer.name : '');
  el.textContent = name ? `${name}${apiServer && apiServer.version ? ` · ${apiServer.version}` : ''}` : '';
  el.title = own && apiServer ? `The API calls it ${apiServer.name}` : '';
}

/** The Live or Mock data pill, from server.js. RestAPI serving the page itself has no such route and no pill. */
async function loadModePill() {
  if (caps.auth === 'session') return;
  try {
    const info = await get('/_dashboard/info', { timeout: 4000 });
    const pill = $('mode-pill');
    pill.hidden = false;
    pill.className = 'pill mode';
    pill.textContent = info.mode === 'live' ? 'Live' : 'Mock data';
    pill.title = info.mode === 'live' ? `Forwarding to ${info.upstream}` : 'Seeded data from mock/data, started with node server.js';
  } catch { /* the pill stays hidden */ }
}

async function loadInfo() {
  try {
    const s = await get('/api/server', { timeout: 6000, quiet: true });
    apiServer = { name: s.server.name, version: s.server.version };
    drawServerName();
    recordServer(s);
  } catch { /* the overview shows the error */ }
}

onStatus(({ errors }) => {
  const badge = $('error-badge');
  const recent = errors.filter((e) => Date.now() - e.at < 5 * 60000);
  badge.hidden = recent.length === 0;
  // On a phone only the count shows, the words hide by CSS and the title still says what failed.
  badge.replaceChildren(String(recent.length), h('span', { class: 'pill-more', text: ` failed ${recent.length === 1 ? 'request' : 'requests'}` }));
  badge.setAttribute('aria-label', `${recent.length} failed ${recent.length === 1 ? 'request' : 'requests'}`);
  badge.title = recent[0] ? `${recent[0].path} ${recent[0].message}` : '';
  const drawer = $('error-drawer');
  if (!drawer.hidden) fillDrawer(errors);
});

function fillDrawer(errors) {
  const drawer = $('error-drawer');
  drawer.replaceChildren(
    h('div', { class: 'toolbar', style: { justifyContent: 'space-between', marginBottom: '8px' } },
      h('h2', { text: 'Failed requests' }),
      h('div', { class: 'actions' },
        h('button', { type: 'button', class: 'btn', text: 'Clear', onclick: () => { clearErrors(); drawer.hidden = true; } }),
        h('button', { type: 'button', class: 'btn', text: 'Close', onclick: () => { drawer.hidden = true; } }))),
    errors.length ? h('ul', { class: 'list-plain' }, errors.map((e) => h('li', null,
      h('div', null, h('code', { text: e.path || '' }), h('div', { class: 'muted', text: `${e.status ? `HTTP ${e.status} ` : ''}${e.code || ''}. ${e.message}` })),
      h('span', { class: 'faint', title: absTime(e.at), text: relTime(e.at) })))) : h('div', { class: 'empty', text: 'No failed requests.' }));
}

$('error-badge').addEventListener('click', () => {
  const drawer = $('error-drawer');
  drawer.hidden = !drawer.hidden;
  if (!drawer.hidden) {
    import('./api.js').then(({ onStatus: on }) => { const off = on(({ errors }) => fillDrawer(errors)); off(); });
  }
});

// ---------- the dashboard password ----------

/** The Log out entry next to the badges, shown while this browser has a session. */
function markAuth() {
  const b = $('logout-btn');
  if (b) {
    b.hidden = !caps.session;
    if (caps.auth === 'session') b.title = 'End this browser\'s session, a new link from /restapi dashboard login opens it again';
  }
  jumpItems = null;
}
$('logout-btn')?.addEventListener('click', (e) => { e.currentTarget.disabled = true; logout(); });
// The Settings page sends this after it set, changed or removed the password.
window.addEventListener('restapi:auth', markAuth);

// ---------- start ----------

document.addEventListener('visibilitychange', () => {
  if (!current) return;
  if (document.hidden) { if (settings.pauseHidden()) current.ctx.pause(); }
  else { current.ctx.resume(); heartbeat(); }
});

window.addEventListener('hashchange', route);
setInterval(heartbeat, 10000);
setInterval(() => refreshTimes(), 15000);
heartbeat();
loadInfo();
setupPalette();
// The landing page from the settings opens when the address names no page.
if (!location.hash) history.replaceState(null, '', `#/${[...SECTIONS, SETTINGS].some((s) => s.id === settings.get('landing')) ? settings.get('landing') : 'overview'}`);
// The view switch in the header. It sets the view in the settings, and the listener below draws the open section
// again without a reload. The Settings page holds the same switch.
function markViewSwitch() {
  const box = $('view-switch');
  if (!box) return;
  for (const b of box.querySelectorAll('button')) b.setAttribute('aria-pressed', b.dataset.view === document.documentElement.dataset.view ? 'true' : 'false');
}
function setupViewSwitch() {
  const box = $('view-switch');
  if (!box) return;
  markViewSwitch();
  box.addEventListener('click', (e) => {
    const b = e.target.closest('button[data-view]');
    if (!b || b.dataset.view === document.documentElement.dataset.view) return;
    settings.set({ view: b.dataset.view });
  });
}

/** Draws the open section again where the viewer was, after a setting that changes what panels draw. */
function redraw() {
  if (!current || !started) return;
  const y = window.scrollY;
  route();
  window.scrollTo(0, y);
}

let started = false;
settings.onChange(async (keys) => {
  if (keys.includes('view')) {
    applyView(settings.get('view'));
    markViewSwitch();
    await prepareLively();
    redraw();
  }
  if (keys.includes('displayName')) {
    drawServerName();
    if (current) document.title = `${current.section.label} · ${settings.get('displayName') || 'RestAPI dashboard'}`;
  }
  if (keys.includes('times') || keys.includes('clock')) {
    refreshTimes();
    stamp();
    // The Settings page has no times, and drawing it again would take the focus from its controls.
    if (current && current.section !== SETTINGS) redraw();
  }
});

settings.applyLook();
applyView(currentView());
setupViewSwitch();

// The capabilities decide whether write controls are drawn at all, so the first page waits for them.
loadCaps().then(() => { settings.setServerPublicUrl(caps.publicUrl); loadModePill(); return prepareLively(); }).then(() => {
  $('control-pill').hidden = !caps.writes;
  if (caps.auth === 'session') $('control-pill').title = 'dashboard.writes is on in RestAPI, the page can change the server';
  markAuth();
  document.documentElement.dataset.writes = caps.writes ? 'on' : 'off';
  started = true;
  route();
});
