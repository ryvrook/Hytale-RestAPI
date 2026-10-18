// Inline SVG charts drawn from plain arrays. No libraries.

import { svg } from './dom.js';
import { num, hm, isNum } from './format.js';

export const COLORS = ['var(--c1)', 'var(--c2)', 'var(--c3)', 'var(--c4)', 'var(--c5)', 'var(--c6)'];

const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

/** A small line of the last values, for table cells. */
export function sparkline(values, { width = 80, height = 20, min, max } = {}) {
  const v = values.filter(isNum);
  if (v.length < 2) return svg(`<svg class="spark" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" aria-hidden="true"></svg>`);
  const lo = min ?? Math.min(...v);
  const hi = max ?? Math.max(...v);
  const span = hi - lo || 1;
  const step = width / (values.length - 1);
  let d = '';
  values.forEach((x, i) => {
    if (!isNum(x)) return;
    const y = height - 2 - ((x - lo) / span) * (height - 4);
    d += `${d ? 'L' : 'M'}${(i * step).toFixed(1)},${y.toFixed(1)}`;
  });
  return svg(`<svg class="spark" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" aria-hidden="true"><path d="${d}"/></svg>`);
}

function niceStep(range, ticks) {
  const raw = range / ticks;
  const mag = 10 ** Math.floor(Math.log10(raw));
  const n = raw / mag;
  // The nice step nearest the raw one, so a fixed top such as 8 GB gets steps of 2 rather than one line at 5.
  return (n >= 7 ? 10 : n >= 3 ? 5 : n >= 1.5 ? 2 : 1) * mag;
}

/**
 * A line chart over time.
 * series: [{ name, points: [[t, v], ...], color, dashed, area }]
 * opts: { height, yMin, yMax, unit, format(v), xFormat(t), title }
 */
export function lineChart(series, opts = {}) {
  const W = Math.max(240, Math.round(opts.width || 640));
  const H = opts.height || 180;
  const padR = 10, padT = 12, padB = 22;
  let padL = 46;
  const all = series.flatMap((s) => s.points.filter((p) => isNum(p[1])));
  const fmt = opts.format || ((v) => num(v, Math.abs(v) < 10 && v % 1 ? 1 : 0));
  const xfmt = opts.xFormat || hm;
  if (all.length < 2) {
    return svg(`<svg viewBox="0 0 ${W} ${H}" width="${W}" height="${H}" role="img" aria-label="${esc(opts.title || 'chart')}"><text x="${W / 2}" y="${H / 2}" text-anchor="middle">${esc(opts.emptyText || 'Collecting samples, the chart fills in as the page polls.')}</text></svg>`);
  }
  const xs = all.map((p) => p[0]);
  const x0 = opts.xMin ?? Math.min(...xs);
  const x1 = opts.xMax ?? Math.max(...xs);
  let y0 = opts.yMin ?? Math.min(...all.map((p) => p[1]));
  let y1 = opts.yMax ?? Math.max(...all.map((p) => p[1]));
  if (opts.yMin == null && y0 > 0 && y0 < (y1 - y0) * 1.5) y0 = 0;
  if (y1 === y0) { y1 = y0 + 1; }
  const step = niceStep(y1 - y0, 4);
  if (opts.yMax == null) y1 = Math.ceil(y1 / step) * step;
  if (opts.yMin == null) y0 = Math.floor(y0 / step) * step;
  // Room for the widest axis label, about 6 px a character at 10.5 px, so labels never leave the box.
  let widest = 0;
  for (let v = y0; v <= y1 + step * 1e-6; v += step) widest = Math.max(widest, String(fmt(v)).length);
  padL = Math.max(34, Math.ceil(widest * 6.2) + 10);
  const sx = (t) => padL + ((t - x0) / (x1 - x0 || 1)) * (W - padL - padR);
  const sy = (v) => padT + (1 - (v - y0) / (y1 - y0)) * (H - padT - padB);

  let g = '';
  for (let v = y0; v <= y1 + step * 1e-6; v += step) {
    const y = sy(v).toFixed(1);
    g += `<line class="grid-line" x1="${padL}" x2="${W - padR}" y1="${y}" y2="${y}"/><text x="${padL - 6}" y="${y}" dy="3.5" text-anchor="end">${esc(fmt(v))}</text>`;
  }
  const ticks = Math.max(1, Math.min(5, Math.floor((W - padL) / 110)));
  for (let i = 0; i <= ticks; i++) {
    const t = x0 + ((x1 - x0) * i) / ticks;
    g += `<text x="${sx(t).toFixed(1)}" y="${H - 6}" text-anchor="${i === 0 ? 'start' : i === ticks ? 'end' : 'middle'}">${esc(xfmt(t))}</text>`;
  }
  g += `<line class="axis" x1="${padL}" x2="${W - padR}" y1="${H - padB}" y2="${H - padB}"/>`;
  if (opts.threshold != null && opts.threshold >= y0 && opts.threshold <= y1) {
    const y = sy(opts.threshold).toFixed(1);
    g += `<line x1="${padL}" x2="${W - padR}" y1="${y}" y2="${y}" stroke="var(--bad)" stroke-dasharray="4 3" stroke-width="1"/>`;
  }
  series.forEach((s, i) => {
    const color = s.color || COLORS[i % COLORS.length];
    let d = '';
    let first = null, last = null;
    for (const [t, v] of s.points) {
      if (!isNum(v)) { d += ' '; continue; }
      const x = sx(t).toFixed(1), y = sy(v).toFixed(1);
      d += `${!d || d.endsWith(' ') ? 'M' : 'L'}${x},${y}`;
      if (first == null) first = x;
      last = x;
    }
    if (s.area && first != null) g += `<path class="area" fill="${color}" d="${d.trim()}L${last},${sy(y0)}L${first},${sy(y0)}Z"/>`;
    g += `<path class="line" stroke="${color}" ${s.dashed ? 'stroke-dasharray="5 4"' : ''} d="${d.trim()}"><title>${esc(s.name)}</title></path>`;
  });
  return svg(`<svg viewBox="0 0 ${W} ${H}" width="${W}" height="${H}" role="img" aria-label="${esc(opts.title || 'chart')}">${g}</svg>`);
}

/** Chart plus legend with the latest value of each series. */
export function chartBlock(series, opts = {}) {
  const wrap = document.createElement('div');
  wrap.className = 'chart';
  let drawn = 0;
  const draw = () => {
    const w = wrap.clientWidth;
    if (!w || Math.abs(w - drawn) < 4) return;
    drawn = w;
    const el = lineChart(series, { ...opts, width: w });
    if (wrap.firstChild && wrap.firstChild.nodeName.toLowerCase() === 'svg') wrap.firstChild.replaceWith(el);
    else wrap.prepend(el);
  };
  wrap.append(lineChart(series, opts));
  if (typeof ResizeObserver === 'function') {
    const ro = new ResizeObserver(() => { if (!wrap.isConnected) { ro.disconnect(); return; } draw(); });
    ro.observe(wrap);
  }
  const legend = document.createElement('div');
  legend.className = 'legend';
  series.forEach((s, i) => {
    const lastPoint = [...s.points].reverse().find((p) => isNum(p[1]));
    const span = document.createElement('span');
    const sw = document.createElement('i');
    sw.className = 'swatch';
    sw.style.background = s.color || COLORS[i % COLORS.length];
    const b = document.createElement('b');
    b.textContent = lastPoint ? (opts.format || ((v) => num(v, 1)))(lastPoint[1]) + (opts.unit ? ` ${opts.unit}` : '') : '-';
    span.append(sw, `${s.name} `, b);
    legend.append(span);
  });
  wrap.append(legend);
  return wrap;
}
