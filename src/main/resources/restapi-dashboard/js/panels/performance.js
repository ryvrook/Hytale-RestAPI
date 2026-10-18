// Performance: charts from the client ring and the Prometheus /metrics text as a table.

import { h, card, table, pageHead, search, badge, errorBox, idBadge, keepScroll } from '../dom.js';
import { num, mb, clock } from '../format.js';
import { chartBlock, COLORS } from '../charts.js';
import { slot, fill, refreshButton } from './common.js';
import { poll } from '../settings.js';

/** Prometheus text to [{ name, help, type, samples: [{ labels, value }] }]. */
export function parseMetrics(text) {
  const byName = new Map();
  const family = (name) => {
    if (!byName.has(name)) byName.set(name, { name, help: '', type: '', samples: [] });
    return byName.get(name);
  };
  for (const raw of text.split('\n')) {
    const line = raw.trim();
    if (!line) continue;
    let m;
    if ((m = /^# HELP (\S+) (.*)$/.exec(line))) { family(m[1]).help = m[2]; continue; }
    if ((m = /^# TYPE (\S+) (\S+)$/.exec(line))) { family(m[1]).type = m[2]; continue; }
    if (line.startsWith('#')) continue;
    m = /^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{(.*)\})?\s+(\S+)/.exec(line);
    if (!m) continue;
    const labels = {};
    if (m[3]) {
      const re = /([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"/g;
      let l;
      while ((l = re.exec(m[3]))) labels[l[1]] = l[2].replace(/\\"/g, '"').replace(/\\\\/g, '\\');
    }
    let name = m[1];
    if (!byName.has(name)) {
      const base = name.replace(/_(bucket|sum|count|total)$/, '');
      if (byName.has(base)) name = base;
    }
    family(name).samples.push({ labels, value: Number(m[4]) });
  }
  return [...byName.values()];
}

export default {
  mount(root, ctx) {
    const banner = h('div');
    const charts = h('div', { class: 'grid perf-grid' });
    const metricsSlot = slot();
    let filter = '';
    let families = [];
    const filterBox = search('Filter metrics, labels or help', '', (v) => { filter = v; renderMetrics(); });
    let fetchedAt = null;
    const metricsNote = h('span');

    root.append(
      pageHead('Performance', 'Charts cover the last 10 minutes of samples this tab took from /api/server every 5 seconds.'),
      banner, charts,
      h('div', { class: 'mt' }, card('Prometheus metrics', metricsSlot, {
        flush: true,
        note: metricsNote,
        actions: [filterBox, refreshButton(() => metricsTask.now())],
      })));

    const box = (title, el, span = 'span-6', note) => h('div', { class: span }, card(title, el, { note }));

    function renderCharts() {
      const ring = ctx.ring();
      const worldNames = [...new Set(ring.flatMap((r) => Object.keys(r.worlds)))];
      const series = (fn) => ring.map((r) => [r.t, fn(r)]);
      const x = { xFormat: clock, xMin: ring.length ? Math.max(ring[0].t, Date.now() - 600000) : undefined };
      const stalled = worldNames.filter((w) => ring.at(-1)?.worlds[w]?.stalled);
      banner.replaceChildren(stalled.length
        ? h('div', { class: 'notice bad stall-banner', role: 'alert' }, h('strong', { text: `${stalled.join(', ')} stalled.` }), ' The world thread stopped answering samples. TPS for that world reads 0 until it recovers.')
        : '');
      charts.replaceChildren(
        box('TPS per world', chartBlock(worldNames.map((w, i) => ({ name: w, color: COLORS[i % COLORS.length], points: series((r) => r.worlds[w]?.tps ?? null) })),
          { ...x, yMin: 0, yMax: 30, unit: 'TPS', format: (v) => num(v, 1), title: 'TPS per world' })),
        box('Tick time per world', chartBlock(worldNames.flatMap((w, i) => [
          { name: `${w} avg`, color: COLORS[i % COLORS.length], points: series((r) => r.worlds[w]?.avg ?? null) },
          { name: `${w} max`, color: COLORS[i % COLORS.length], dashed: true, points: series((r) => r.worlds[w]?.max ?? null) },
        ]), { ...x, yMin: 0, unit: 'ms', threshold: 33.3, format: (v) => num(v, v < 10 ? 1 : 0), title: 'Tick time' }), 'span-6', 'The red line is 33.3 ms, the budget of one tick at 30 TPS.'),
        // Heap is drawn in GB, so the axis steps are whole gigabytes rather than odd figures such as 4.88 GB.
        box('Heap', chartBlock([
          { name: 'used', points: series((r) => (r.heapUsed == null ? null : r.heapUsed / 1024)), area: true },
          { name: 'committed', points: series((r) => (r.heapCommitted == null ? null : r.heapCommitted / 1024)), dashed: true },
        ], { ...x, yMin: 0, yMax: ring.at(-1)?.heapMax != null ? ring.at(-1).heapMax / 1024 : undefined, format: gb, title: 'Heap' }), 'span-6', ring.length ? `Max ${mb(ring.at(-1).heapMax)}` : null),
        box('Players online', chartBlock([{ name: 'players', points: series((r) => r.players), color: 'var(--c3)', area: true }],
          { ...x, yMin: 0, format: (v) => num(v), title: 'Players' }), 'span-3'),
        box('Load', chartBlock([
          { name: 'system load', points: series((r) => r.load), color: 'var(--c4)' },
          { name: 'process CPU x10', points: series((r) => (r.cpu == null ? null : r.cpu * 10)), color: 'var(--c6)', dashed: true },
        ], { ...x, yMin: 0, format: (v) => num(v, 1), title: 'Load' }), 'span-3', 'Process CPU is scaled by 10 to share the axis.'),
      );
    }

    const failNote = h('div');
    banner.after(failNote);
    ctx.every(poll.fast(), async () => {
      try {
        const s = await ctx.get('/api/server');
        ctx.recordServer(s);
        failNote.replaceChildren();
      } catch (e) {
        if (e.code !== 'aborted') failNote.replaceChildren(errorBox(e, 'Server sample'));
        throw e;
      } finally {
        renderCharts();
      }
    });

    function renderMetrics() {
      const rows = [];
      let samples = 0;
      for (const f of families) {
        const match = (s) => !filter || f.name.toLowerCase().includes(filter) || f.help.toLowerCase().includes(filter)
          || Object.entries(s.labels).some(([k, v]) => `${k}=${v}`.toLowerCase().includes(filter));
        const list = f.samples.filter(match);
        if (!list.length && !(filter && (f.name.includes(filter) || f.help.toLowerCase().includes(filter)))) continue;
        rows.push({ __group: h('span', null, h('code', { text: f.name }), ' ', f.type ? badge(f.type) : null, ' ', h('span', { class: 'help', text: f.help })) });
        for (const s of list) { rows.push({ ...s, family: f }); samples++; }
      }
      metricsNote.textContent = `${num(families.length)} metrics, ${num(samples)} samples shown${fetchedAt ? `, read at ${clock(fetchedAt)}` : ''}`;
      keepScroll(metricsSlot, () => metricsSlot.replaceChildren(table([
        { key: 'labels', label: 'Labels', cls: 'wrap', render: (r) => {
          const e = Object.entries(r.labels);
          return e.length ? h('span', { class: 'tag-list' }, e.map(([k, v]) => idBadge(`${k}=${v}`))) : h('span', { class: 'faint', text: 'no labels' });
        } },
        { key: 'value', label: 'Value', num: true, render: (r) => fmtMetric(r.family.name, r.value) },
      ], rows, { emptyText: filter ? 'No metric matches the filter.' : 'No metrics.', tall: true })));
    }

    const metricsTask = ctx.every(poll.fast(3), () => fill(metricsSlot, async () => {
      const text = await ctx.get('/metrics', { text: true });
      families = parseMetrics(text);
      fetchedAt = Date.now();
      renderMetrics();
      return undefined;
    }, '/metrics'));
  },
};

/** Gigabytes for the heap chart, whole numbers on the axis and two digits for the latest values. */
function gb(v) {
  return `${num(v, Number.isInteger(Math.round(v * 1000) / 1000) ? 0 : 2)} GB`;
}

function fmtMetric(name, v) {
  if (!Number.isFinite(v)) return String(v);
  if (name.endsWith('_bytes')) return `${num(v)} B (${mb(v / 1048576)})`;
  if (name.endsWith('_seconds') || name.endsWith('_seconds_total')) return `${num(v, v % 1 ? 3 : 0)} s`;
  if (name.endsWith('_ms')) return `${num(v, 2)} ms`;
  if (name.includes('tps')) return `${num(v, 1)} TPS`;
  return v % 1 ? num(v, 3) : num(v);
}
