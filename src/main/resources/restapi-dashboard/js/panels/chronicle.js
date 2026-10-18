// Chronicle Stats: server totals, boards per window and world, the stats catalogue and the series charts.

import { h, card, table, tile, time, badge, playerLink, select, seg, search } from '../dom.js';
import { num, idText, duration, bytes, hm, day } from '../format.js';
import { chartBlock } from '../charts.js';
import { slot, fill, refreshButton, suiteError } from './common.js';
import { keyIcon, withIcon } from '../lively.js';
import { poll } from '../settings.js';

/** A value in the unit Chronicle gives for the stat. */
export function statValue(def, v) {
  if (v == null) return '-';
  switch (def && def.unit) {
    case 'seconds': return duration(v);
    case 'metres': return `${num(v)} m`;
    case 'hit_points': return `${num(v)} HP`;
    case 'timestamp': return v ? time(v) : 'never';
    default: return num(v);
  }
}

/** The catalogue entry for a key, falling back to the prefix entry for keys such as zonetime:Emerald_Wilds. */
export function statDef(defs, key) {
  if (!defs) return undefined;
  if (defs.has(key)) return defs.get(key);
  const i = String(key).indexOf(':');
  return i > 0 ? defs.get(key.slice(0, i + 1)) : undefined;
}

const RES = { minute: { label: 'Last 3 hours', span: 3 * 3600000, x: hm }, hour: { label: 'Last 2 days', span: 2 * 86400000, x: (t) => `${day(t)} ${hm(t)}` }, day: { label: 'Last 30 days', span: 30 * 86400000, x: day } };

function seriesFormat(unit, v) {
  if (unit === 'bytes') return bytes(v);
  if (unit === 'mb') return `${num(v)} MB`;
  return num(v, Math.abs(v) < 10 && v % 1 ? 1 : 0);
}

export default {
  mount(root, ctx) {
    const sum = slot();
    const serverSlot = slot();
    const recordsSlot = slot();
    const boardSlot = slot();
    const statsSlot = slot();
    const seriesSlot = slot();
    let stats = new Map();
    let boardStat = 'playtime';
    let win = 'all';
    let world = '';
    let seriesId = 'tick.avg_ms';
    let res = 'minute';
    let statFilter = '';
    let statList = [];
    const boardTools = h('div', { class: 'toolbar' });
    const seriesTools = h('div', { class: 'toolbar' });

    root.append(sum, h('div', { class: 'grid' },
      h('div', { class: 'span-12' }, card('Server series', seriesSlot, { actions: seriesTools })),
      h('div', { class: 'span-6' }, card('Server totals', serverSlot, { flush: true, actions: refreshButton(() => serverTask.now()) })),
      h('div', { class: 'span-6' }, card('Board', boardSlot, { flush: true, actions: boardTools })),
      h('div', { class: 'span-12' }, card('Recent records', recordsSlot, { flush: true })),
      h('div', { class: 'span-12' }, card('Stats catalogue', statsSlot, { flush: true, actions: search('Filter stats', '', (v) => { statFilter = v; renderStats(); }) }))));

    const renderStats = () => {
      const rows = statList.filter((s) => !statFilter || `${s.key} ${s.label} ${s.category} ${s.owner}`.toLowerCase().includes(statFilter));
      statsSlot.replaceChildren(table([
        { key: 'key', label: 'Key', render: (s) => h('code', { text: s.key }) },
        { key: 'label', label: 'Label' },
        { key: 'category', label: 'Category' },
        { key: 'unit', label: 'Unit' },
        { key: 'aggregation', label: 'Aggregation' },
        { key: 'scope', label: 'Scope' },
        { key: 'board', label: 'Board', render: (s) => (s.board ? badge('board', 'ok') : '') },
        { key: 'windowed', label: 'Windows', render: (s) => (s.windowed ? 'yes' : 'no') },
        { key: 'owner', label: 'Owner' },
      ], rows, { state: { sortKey: 'category', sortDir: 'asc' }, tall: true, emptyText: 'No stat matches the filter.' }));
    };

    ctx.every(poll.fast(6), () => fill(sum, async () => {
      let s;
      try { s = await ctx.get('/api/chronicle'); } catch (e) { return suiteError(e, 'Chronicle Stats'); }
      return h('div', { class: 'tiles' },
        tile('Version', s.version, { sub: `API ${s.apiVersion}` }),
        tile('Stats', num(s.stats)),
        tile('Boards', num(s.boards)),
        tile('Series', num(s.series)),
        tile('Online', num(s.online), { unit: 'players' }),
        tile('Export', s.exported ? 'written' : 'none', { sub: s.updatedAt ? h('span', null, 'Updated ', time(s.updatedAt)) : null }));
    }));

    ctx.every(poll.slow(5), () => fill(statsSlot, async () => {
      try { statList = (await ctx.get('/api/chronicle/stats', { timeout: 20000 })).stats; } catch (e) { return suiteError(e, 'Chronicle Stats'); }
      stats = new Map(statList.map((s) => [s.key, s]));
      renderStats();
      return undefined;
    }));

    const serverTask = ctx.every(poll.slow(), async () => {
      let d;
      try { d = await ctx.get('/api/chronicle/server'); } catch (e) { serverSlot.replaceChildren(suiteError(e, 'Chronicle Stats')); recordsSlot.replaceChildren(); return false; }
      const rows = Object.entries(d.server).map(([key, value]) => ({ key, value, label: stats.get(key)?.label || idText(key) }));
      serverSlot.replaceChildren(table([
        { key: 'label', label: 'Stat', render: (r) => withIcon(keyIcon(r.key), h('span', { title: r.key, text: r.label })) },
        { key: 'value', label: 'Total', num: true, render: (r) => statValue(statDef(stats, r.key), r.value) },
      ], rows, { state: { sortKey: 'label', sortDir: 'asc' }, tall: true }));
      recordsSlot.replaceChildren(table([
        { key: 'at', label: 'When', cls: 'nowrap', render: (r) => time(r.at) },
        { key: 'label', label: 'Record', cls: 'wrap' },
        { key: 'names', label: 'Holder', render: (r) => h('span', null, (r.names || []).map((n, i) => [i ? ', ' : '', playerLink(n, r.holders?.[i])])) },
        { key: 'world', label: 'World' },
        { key: 'valueText', label: 'Value', num: true, render: (r) => r.valueText || num(r.value) },
      ], d.recentRecords || [], { emptyText: 'No records yet.' }));
      return true;
    });

    const loadBoard = () => fill(boardSlot, async () => {
      const q = new URLSearchParams({ window: win, limit: '20' });
      if (world) q.set('world', world);
      try {
        const d = await ctx.get(`/api/chronicle/top/${encodeURIComponent(boardStat)}?${q}`);
        if (d.warming) return h('div', { class: 'empty', text: 'This board is still being built, try again in a moment.' });
        const def = statDef(stats, boardStat);
        return [table([
          { key: 'rank', label: 'Rank', num: true },
          { key: 'name', label: 'Player', render: (r) => (r.hidden ? h('span', { class: 'faint', text: 'hidden' }) : playerLink(r.name, r.uuid)) },
          { key: 'value', label: stats.get(boardStat)?.label || idText(boardStat), num: true, render: (r) => statValue(def, r.value) },
          { key: 'movement', label: 'Move', num: true, render: (r) => (r.movement > 0 ? h('span', { style: { color: 'var(--ok)' }, text: `up ${r.movement}` }) : r.movement < 0 ? h('span', { style: { color: 'var(--bad)' }, text: `down ${-r.movement}` }) : '') },
        ], d.rows, { emptyText: 'Nobody is on this board for the window.' }), h('div', { class: 'table-foot' }, `${num(d.total)} on the board, built `, time(d.builtAt))];
      } catch (e) {
        if (e.status === 404) return h('div', { class: 'empty', text: e.message });
        return suiteError(e, 'Chronicle Stats');
      }
    });

    ctx.every(poll.slow(2), async () => {
      try {
        const [b, w] = await Promise.all([ctx.get('/api/chronicle/boards'), ctx.cached('/api/worlds', 30000)]);
        if (!b.boards.includes(boardStat)) boardStat = b.boards[0];
        const plain = b.boards.filter((k) => !k.includes(':'));
        const keyed = b.boards.filter((k) => k.includes(':'));
        boardTools.replaceChildren(
          h('select', { 'aria-label': 'Board', onchange: (e) => { boardStat = e.target.value; loadBoard(); } },
            h('optgroup', { label: 'Stats' }, plain.map((k) => h('option', { value: k, selected: k === boardStat ? true : null, text: stats.get(k)?.label || idText(k) }))),
            keyed.length ? h('optgroup', { label: 'Per id' }, keyed.map((k) => h('option', { value: k, selected: k === boardStat ? true : null, text: idText(k) }))) : null),
          b.windows ? seg([['all', 'All'], ['month', 'Month'], ['week', 'Week'], ['today', 'Today']], win, (v) => { win = v; loadBoard(); }) : null,
          b.windows ? select([['', 'All worlds'], ...w.worlds.map((x) => [x.name, x.name])], world, (v) => { world = v; loadBoard(); }, { 'aria-label': 'World' }) : null);
      } catch (e) { boardSlot.replaceChildren(suiteError(e, 'Chronicle Stats')); return false; }
      return loadBoard();
    });

    const loadSeries = () => fill(seriesSlot, async () => {
      const to = Date.now();
      const from = to - RES[res].span;
      let d;
      try { d = await ctx.get(`/api/chronicle/series/${encodeURIComponent(seriesId)}?resolution=${res}&from=${from}&to=${to}`); } catch (e) { return suiteError(e, 'Chronicle Stats'); }
      const pts = d.points;
      const fmt = (v) => seriesFormat(d.unit, v);
      return chartBlock([
        { name: `${seriesId} avg`, points: pts.map((p) => [p.at, p.avg]), area: true },
        { name: 'max', points: pts.map((p) => [p.at, p.max]), dashed: true, color: 'var(--c2)' },
      ], { xFormat: RES[res].x, format: fmt, unit: d.unit === 'mb' || d.unit === 'bytes' ? '' : d.unit, height: 220, title: seriesId, xMin: from, xMax: to, emptyText: 'No samples in this range.' });
    });

    ctx.every(poll.slow(2), async () => {
      try {
        const s = await ctx.get('/api/chronicle/series');
        const all = [...(s.performance || []), ...(s.activity || [])];
        if (!all.includes(seriesId)) seriesId = all[0];
        seriesTools.replaceChildren(
          h('select', { 'aria-label': 'Series', onchange: (e) => { seriesId = e.target.value; loadSeries(); } },
            s.performance && s.performance.length ? h('optgroup', { label: 'Performance' }, s.performance.map((k) => h('option', { value: k, selected: k === seriesId ? true : null, text: k }))) : null,
            s.activity && s.activity.length ? h('optgroup', { label: 'Activity' }, s.activity.map((k) => h('option', { value: k, selected: k === seriesId ? true : null, text: k }))) : null),
          seg(Object.entries(RES).map(([k, v]) => [k, v.label]), res, (v) => { res = v; loadSeries(); }));
      } catch (e) { seriesSlot.replaceChildren(suiteError(e, 'Chronicle Stats')); return false; }
      return loadSeries();
    });
    return undefined;
  },
};
