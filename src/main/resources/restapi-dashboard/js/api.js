// Fetch wrapper, error tracking and Server Sent Events with reconnect backoff.
// The page talks to its own server only. In live mode that server adds the key.

// ---------- addresses ----------

/**
 * The API root, worked out once from the page address: everything before /dashboard/ when the page is served
 * there, by RestAPI itself or by server.js, else /. RestAPI may sit under http.basePath or behind a proxy that
 * serves it under a sub path, so the page never asks for an absolute path of its own.
 */
export const ROOT = (() => {
  const p = location.pathname;
  const i = p.indexOf('/dashboard/');
  return i >= 0 ? p.slice(0, i + 1) : '/';
})();
/** The page's own routes (capabilities, assets, chronicle, login, logout, password) live under ROOT dashboard/. */
export const PAGE = `${ROOT}dashboard/`;

const PAGE_ROUTES = /^\/(assets|chronicle|login|logout)(\/|$|\?)/;

/**
 * The address to ask for a path written from the server root, such as /api/server or /assets/index.json.
 * API paths go under ROOT, the page's own routes under PAGE. Full URLs pass as they are.
 */
export function url(path) {
  const s = String(path);
  if (!s.startsWith('/') || s.startsWith('//')) return s;
  if (s.startsWith('/dashboard/')) return ROOT + s.slice(1);
  if (PAGE_ROUTES.test(s)) return PAGE + s.slice(1);
  return ROOT + s.slice(1);
}

export class ApiError extends Error {
  constructor(status, code, message, path) {
    super(message);
    this.status = status;
    this.code = code;
    this.path = path;
  }
}

const listeners = new Set();
const errors = [];
let lastOk = null;

function notify() {
  for (const fn of listeners) fn({ errors: errors.slice(), lastOk });
}

export function onStatus(fn) {
  listeners.add(fn);
  fn({ errors: errors.slice(), lastOk });
  return () => listeners.delete(fn);
}

export function clearErrors() {
  errors.length = 0;
  notify();
}

function recordError(err) {
  errors.unshift({ at: Date.now(), path: err.path, status: err.status, code: err.code, message: err.message });
  if (errors.length > 30) errors.length = 30;
  notify();
}

/** Codes that mean "this is the expected answer for this question", not a failure of the dashboard. */
const QUIET = new Set(['not_linked', 'not_online', 'not_in_world', 'no_board']);

/**
 * GET a path. Returns parsed JSON, or text with { text: true }.
 * Options: timeout in ms, text, quiet (do not count a failure in the header badge), signal.
 */
export async function get(path, { timeout = 10000, text = false, quiet = false, signal, allowStatus } = {}) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(new DOMException('timeout', 'TimeoutError')), timeout);
  const onAbort = () => ctrl.abort(signal.reason);
  if (signal) signal.addEventListener('abort', onAbort, { once: true });
  let res;
  try {
    res = await fetch(url(path), { signal: ctrl.signal, headers: { Accept: text ? 'text/plain' : 'application/json' }, cache: 'no-store' });
  } catch (e) {
    clearTimeout(timer);
    if (signal) signal.removeEventListener('abort', onAbort);
    if (signal && signal.aborted) throw new ApiError(0, 'aborted', 'Request cancelled.', path);
    const timedOut = e && (e.name === 'TimeoutError' || (ctrl.signal.reason && ctrl.signal.reason.name === 'TimeoutError'));
    const err = new ApiError(0, timedOut ? 'timeout' : 'network', timedOut ? `No answer within ${Math.round(timeout / 1000)} s.` : 'The dashboard server did not answer.', path);
    if (!quiet) recordError(err);
    throw err;
  }
  clearTimeout(timer);
  if (signal) signal.removeEventListener('abort', onAbort);
  const body = await res.text();
  let data = null;
  if (!text || !res.ok) {
    try { data = body ? JSON.parse(body) : null; } catch { data = null; }
  }
  if (!res.ok && !(allowStatus && allowStatus.includes(res.status))) {
    const code = data && data.error ? data.error : `http_${res.status}`;
    const message = data && data.message ? data.message : (body.slice(0, 200) || res.statusText);
    const err = new ApiError(res.status, code, message, path);
    if (code === 'login_required') { toLogin(); throw err; }
    if (!quiet && !QUIET.has(code)) recordError(err);
    throw err;
  }
  lastOk = Date.now();
  notify();
  if (text) return body;
  if (data === null && body) throw new ApiError(res.status, 'bad_json', 'The answer was not JSON.', path);
  return data;
}

/** Fetch several paths at once, each result is { ok, data } or { ok: false, error }. */
export async function getAll(paths, opts) {
  return Promise.all(paths.map((p) => get(p, opts).then((data) => ({ ok: true, data }), (error) => ({ ok: false, error }))));
}

/** 404 or 503 from a suite route means the mod is not bound right now. */
export function isUnbound(err) {
  return err instanceof ApiError && (err.status === 404 || err.status === 503) && (err.code === 'sibling_gone' || err.code === 'not_found' || err.code === 'http_404');
}

/**
 * Server Sent Events with our own reconnect so the backoff grows and the dashboard can show the state.
 * handlers: { onEvent(type, data, id), onState('connecting' | 'open' | 'down'), resumeParam(lastId) }
 */
export function stream(path, { events = ['message'], onEvent, onState, resume } = {}) {
  let es = null;
  let closed = false;
  let delay = 1000;
  let timer = null;
  let lastId = null;

  const open = () => {
    if (closed) return;
    onState && onState('connecting');
    const target = url(resume ? resume(path, lastId) : path);
    es = new EventSource(target);
    es.onopen = () => { delay = 1000; onState && onState('open'); };
    const handle = (type) => (ev) => {
      if (ev.lastEventId) lastId = ev.lastEventId;
      let data = ev.data;
      try { data = JSON.parse(ev.data); } catch { /* text data */ }
      onEvent && onEvent(type, data, ev.lastEventId);
    };
    for (const t of events) es.addEventListener(t, handle(t));
    es.onerror = () => {
      if (closed) return;
      es.close();
      onState && onState('down', delay);
      recordError(new ApiError(0, 'stream', `Stream dropped, trying again in ${Math.round(delay / 1000)} s.`, path));
      timer = setTimeout(open, delay);
      delay = Math.min(delay * 2, 30000);
    };
  };
  open();
  return {
    close() {
      closed = true;
      clearTimeout(timer);
      if (es) es.close();
    },
    get lastId() { return lastId; },
  };
}

// ---------- writes ----------

/** What the dashboard server allows. Read once at start from /dashboard/capabilities. */
export const caps = {
  mode: null, writes: false, assets: false, chronicle: false, images: false, upstream: null, listen: null, port: null, remote: false, publicUrl: null,
  // The dashboard password. auth is null when the server is older than the password and cannot have one.
  // 'session' means RestAPI serves the page and a login link from /restapi dashboard login opens a session.
  auth: null, authSince: null, session: false, passwordMin: 12, remoteNeedsPassword: false,
  who: null, restapi: null,
  loaded: false,
};

export async function loadCaps() {
  try {
    const d = await get('/dashboard/capabilities', { timeout: 4000, quiet: true });
    caps.mode = d.mode || null;
    caps.writes = d.writes === true;
    caps.assets = d.assets === true;
    caps.chronicle = d.chronicle === true;
    caps.upstream = typeof d.upstream === 'string' ? d.upstream : null;
    caps.listen = typeof d.listen === 'string' ? d.listen : null;
    caps.port = Number.isInteger(d.port) ? d.port : null;
    caps.remote = d.remote === true;
    caps.publicUrl = typeof d.publicUrl === 'string' ? d.publicUrl : null;
    caps.auth = d.auth === 'password' || d.auth === 'none' || d.auth === 'session' ? d.auth : null;
    caps.authSince = typeof d.authSince === 'string' ? d.authSince : null;
    caps.session = d.session === true;
    caps.passwordMin = Number.isInteger(d.passwordMin) ? d.passwordMin : 12;
    caps.remoteNeedsPassword = d.remoteNeedsPassword === true;
    caps.who = typeof d.who === 'string' ? d.who : null;
    caps.restapi = typeof d.restapi === 'string' ? d.restapi : null;
    // With a password set, or served by RestAPI, and no session the server says only that, and the page belongs
    // on the login page.
    if ((caps.auth === 'password' || caps.auth === 'session') && !('mode' in d)) toLogin();
    // An older server.js lists no assets flag and serves only css and js, so the page asks it for no image.
    caps.images = 'assets' in d;
  } catch {
    // An older server.js has no capabilities route, so the page stays read only.
    caps.writes = false;
  }
  caps.loaded = true;
  return caps;
}

// ---------- the dashboard password ----------

let leaving = false;
/** Opens the login page, which brings the viewer back to this page after the password. */
export function toLogin() {
  if (leaving) return;
  leaving = true;
  const here = `${location.pathname}${location.search}`;
  const home = here === ROOT || here === PAGE;
  location.assign(`${PAGE}login${here && !home ? `?next=${encodeURIComponent(here)}` : ''}${location.hash}`);
}

/** Ends this browser's session and opens the login page. */
export async function logout() {
  try { await fetch(`${PAGE}logout`, { method: 'POST', headers: { Accept: 'application/json' }, cache: 'no-store' }); } catch { /* the login page shows either way */ }
  leaving = true;
  location.assign(`${PAGE}login${location.hash}`);
}

/** A readable sentence for an error from get() or write(). */
export function explain(e) {
  if (!e) return 'Unknown error.';
  if (!(e instanceof ApiError)) return String(e.message || e);
  if (e.code === 'missing_scope') {
    const m = /needs the (\S+) scope/.exec(e.message || '');
    return m ? `The API key lacks the ${m[1]} scope.` : `The API key lacks a scope this needs. ${e.message}`;
  }
  if (e.code === 'read_only') {
    return caps.auth === 'session'
      ? 'The dashboard is read only. Turn writes on with /restapi dashboard writes on.'
      : 'The dashboard was started without --allow-writes, so nothing can be changed.';
  }
  if (e.code === 'timeout' || e.code === 'network') return e.message;
  const status = e.status ? `HTTP ${e.status} ${e.code}.` : `${e.code}.`;
  return `${status} ${e.message || ''}`.trim();
}

/**
 * Sends one change. body is sent as JSON. idempotency adds a fresh Idempotency-Key, or the one given.
 * Returns { status, data }. A status outside 2xx throws ApiError unless it is in allowStatus,
 * which the console uses for the 403 a denied command answers with its own result.
 */
export async function write(method, path, body, { idempotency = false, timeout = 30000, allowStatus = [] } = {}) {
  const headers = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (idempotency) headers['Idempotency-Key'] = typeof idempotency === 'string' ? idempotency : newKey();
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeout);
  let res;
  try {
    res = await fetch(url(path), { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: ctrl.signal, cache: 'no-store' });
  } catch (e) {
    const timedOut = ctrl.signal.aborted;
    throw new ApiError(0, timedOut ? 'timeout' : 'network', timedOut ? `No answer within ${Math.round(timeout / 1000)} s. The change may still land.` : 'The dashboard server did not answer.', path);
  } finally {
    clearTimeout(timer);
  }
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = null; }
  if (!res.ok && !allowStatus.includes(res.status)) {
    const code = data && data.error ? data.error : `http_${res.status}`;
    const message = data && data.message ? data.message : (text.slice(0, 200) || res.statusText);
    const err = new ApiError(res.status, code, message, path);
    err.data = data;
    if (code === 'login_required') toLogin();
    throw err;
  }
  lastOk = Date.now();
  notify();
  return { status: res.status, data, key: headers['Idempotency-Key'] || null };
}

export function newKey() {
  if (globalThis.crypto && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  return `k${Date.now().toString(36)}${Math.random().toString(36).slice(2, 12)}`;
}
