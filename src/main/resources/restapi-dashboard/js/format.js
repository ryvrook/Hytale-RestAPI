// Number, size, duration and time formatting. Every figure carries its unit.

import * as settings from './settings.js';

const nf = new Intl.NumberFormat('en-GB');
const nfCache = new Map();
function fmt(digits) {
  if (!nfCache.has(digits)) nfCache.set(digits, new Intl.NumberFormat('en-GB', { minimumFractionDigits: digits, maximumFractionDigits: digits }));
  return nfCache.get(digits);
}

export const NONE = '-';

export function isNum(v) {
  return typeof v === 'number' && Number.isFinite(v);
}

/** 12,345 or 12.35 with fixed digits. */
export function num(v, digits = 0) {
  if (!isNum(v)) return NONE;
  return digits ? fmt(digits).format(v) : nf.format(Math.round(v));
}

/** A number with its unit, such as 23.1 ms. */
export function unit(v, u, digits = 0) {
  if (!isNum(v)) return NONE;
  return `${num(v, digits)} ${u}`;
}

export const ms = (v, d = 1) => unit(v, 'ms', d);
export const tps = (v) => unit(v, 'TPS', 1);
export const pct = (v, d = 0) => (isNum(v) ? `${num(v * 100, d)} %` : NONE);

/** Compact counts, 1.2k, 3.4M. */
export function compact(v) {
  if (!isNum(v)) return NONE;
  const a = Math.abs(v);
  if (a >= 1e9) return `${num(v / 1e9, 1)}B`;
  if (a >= 1e6) return `${num(v / 1e6, 1)}M`;
  if (a >= 1e4) return `${num(v / 1e3, 1)}k`;
  return num(v);
}

export function bytes(v) {
  if (!isNum(v)) return NONE;
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  let x = v;
  while (Math.abs(x) >= 1024 && i < units.length - 1) { x /= 1024; i++; }
  return `${num(x, i === 0 ? 0 : x < 10 ? 2 : 1)} ${units[i]}`;
}

export const mb = (v) => (isNum(v) ? bytes(v * 1048576) : NONE);

/** 6 h 48 min, 3 d 2 h, 42 s. */
export function duration(seconds) {
  if (!isNum(seconds)) return NONE;
  let s = Math.max(0, Math.round(seconds));
  const d = Math.floor(s / 86400); s -= d * 86400;
  const h = Math.floor(s / 3600); s -= h * 3600;
  const m = Math.floor(s / 60); s -= m * 60;
  if (d) return `${d} d ${h} h`;
  if (h) return `${h} h ${m} min`;
  if (m) return `${m} min ${s} s`;
  return `${s} s`;
}

/** Epoch milliseconds from a number or an ISO string. */
export function toMs(t) {
  if (t == null || t === '') return null;
  if (typeof t === 'number') return t;
  const v = Date.parse(t);
  return Number.isNaN(v) ? null : v;
}

export function relTime(t, now = Date.now()) {
  const v = toMs(t);
  if (v == null) return NONE;
  const diff = (now - v) / 1000;
  const a = Math.abs(diff);
  let out;
  if (a < 5) return diff >= 0 ? 'just now' : 'in a moment';
  if (a < 60) out = `${Math.round(a)} s`;
  else if (a < 3600) out = `${Math.round(a / 60)} min`;
  else if (a < 86400) out = `${num(a / 3600, a < 36000 ? 1 : 0)} h`;
  else out = `${num(a / 86400, a < 864000 ? 1 : 0)} d`;
  return diff >= 0 ? `${out} ago` : `in ${out}`;
}

// The clock follows the viewer's 12 or 24 hour setting, so the formats are made again when it changes.
const fmts = new Map();
function dtf(kind) {
  const h12 = settings.get('clock') === '12';
  const key = `${kind}-${h12}`;
  if (!fmts.has(key)) {
    const hc = { hour12: h12 };
    const opts = {
      abs: { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit', ...hc },
      clock: { hour: '2-digit', minute: '2-digit', second: '2-digit', ...hc },
      hm: { hour: '2-digit', minute: '2-digit', ...hc },
      short: { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', ...hc },
      day: { month: 'short', day: 'numeric' },
    }[kind];
    fmts.set(key, new Intl.DateTimeFormat('en-GB', opts));
  }
  return fmts.get(key);
}

export function absTime(t) {
  const v = toMs(t);
  return v == null ? NONE : dtf('abs').format(v);
}
export function clock(t) {
  const v = toMs(t);
  return v == null ? NONE : dtf('clock').format(v);
}
export function hm(t) {
  const v = toMs(t);
  return v == null ? NONE : dtf('hm').format(v);
}
export function day(t) {
  const v = toMs(t);
  return v == null ? NONE : dtf('day').format(v);
}

/** A table time as the viewer likes it, relative such as 4 min ago, or absolute, the clock today and the date before. */
export function timeText(t, now = Date.now()) {
  if (settings.get('times') !== 'absolute') return relTime(t, now);
  const v = toMs(t);
  if (v == null) return NONE;
  return new Date(v).toDateString() === new Date(now).toDateString() ? dtf('clock').format(v) : dtf('short').format(v);
}
/** The other form of timeText, for the hover title. */
export function timeTitle(t) {
  return settings.get('times') === 'absolute' ? `${absTime(t)}, ${relTime(t)}` : absTime(t);
}

/** Ore_Iron_Basalt becomes Ore Iron Basalt. */
export function idText(id) {
  if (id == null) return NONE;
  return String(id).replace(/_/g, ' ');
}

export function shortUuid(u) {
  return u ? String(u).slice(0, 8) : NONE;
}

export function yesNo(v) {
  return v === true ? 'yes' : v === false ? 'no' : NONE;
}

export function plural(n, one, many = `${one}s`) {
  return `${num(n)} ${n === 1 ? one : many}`;
}

export function hostOf(url) {
  try { return new URL(url).host; } catch { return url || NONE; }
}
