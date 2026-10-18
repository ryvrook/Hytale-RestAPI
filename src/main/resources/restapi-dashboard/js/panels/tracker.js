// TrackerAPI: summary, server totals, leaderboards, recent events and the journal tail.

import { codeWrap, h, card, table, tile, time, search, select, playerLink, badge } from '../dom.js';
import { num, bytes, idText } from '../format.js';
import { slot, fill, refreshButton, suiteError } from './common.js';
import { keyIcon, withIcon } from '../lively.js';
import { poll } from '../settings.js';

/** A short readable line from a TrackerAPI event body. */
export function trackerDetail(kind, ev, names) {
  if (!ev) return '';
  const nm = (u) => (u && names.get(u)) || (u ? String(u).slice(0, 8) : null);
  switch (kind) {
    case 'entity_died': {
      const v = ev.victim || {};
      const k = ev.killer || {};
      // /recent writes the kind in lower case, the journal in upper case.
      const isPlayer = (x) => String(x.kind || '').toLowerCase() === 'player';
      const victim = isPlayer(v) ? (v.name || nm(v.uuid)) : idText(v.typeId);
      const killer = isPlayer(k) ? (k.name || nm(k.uuid)) : k.typeId ? idText(k.typeId) : null;
      return `${victim} died${killer ? ` to ${killer}` : ''}${ev.causeId ? `, ${ev.causeId}` : ''}${ev.ranged ? ', ranged' : ''}${ev.boss ? ', boss' : ''}`;
    }
    case 'block_broken': return `${idText(ev.blockId)} at ${ev.x}, ${ev.y}, ${ev.z}${ev.toolItemId ? ` with ${idText(ev.toolItemId)}` : ''}`;
    case 'block_placed': return `${idText(ev.blockId || ev.itemId)} at ${ev.x}, ${ev.y}, ${ev.z}`;
    case 'item_consumed': return idText(ev.itemId);
    case 'item_crafted': return `${num(ev.quantity || 1)} ${idText(ev.itemId)}${ev.benchId ? ` at ${idText(ev.benchId)}` : ''}`;
    case 'game_time_sampled': return `hour ${ev.hour}, day ${num(ev.gameDay)}, moon phase ${ev.moonPhase}`;
    case 'moon_phase_changed': return `moon phase ${ev.phase}`;
    default: {
      const skip = new Set(['kind', 'at', 'seq', 'world', 'player']);
      const parts = Object.entries(ev).filter(([k, v]) => !skip.has(k) && v != null && typeof v !== 'object').slice(0, 5);
      return parts.map(([k, v]) => `${k} ${v}`).join(', ');
    }
  }
}

export default {
  mount(root, ctx) {
    const sumSlot = slot();
    const serverSlot = slot();
    const lbSlot = slot();
    const recentSlot = slot();
    const journalSlot = slot();
    const kindsSlot = slot();
    let filter = '';
    let counters = null;
    const sState = { sortKey: 'value', sortDir: 'desc' };
    const serverNote = h('span');
    let lbKey = null;
    const lbPicker = h('span');

    const renderServer = () => {
      if (!counters) return;
      const rows = Object.entries(counters).map(([key, value]) => ({ key, value })).filter((r) => !filter || r.key.toLowerCase().includes(filter));
      serverNote.textContent = `${num(rows.length)} of ${num(Object.keys(counters).length)} counters`;
      serverSlot.replaceChildren(table([
        { key: 'key', label: 'Counter', cls: 'wrap', render: (r) => withIcon(keyIcon(r.key), codeWrap(r.key)) },
        { key: 'value', label: 'Total', num: true },
      ], rows, { state: sState, limit: 300, tall: true, emptyText: 'No counter matches the filter.' }));
    };

    root.append(
      sumSlot,
      h('div', { class: 'cols even' },
        h('div', { class: 'stack' }, card('Server totals', serverSlot, { flush: true, cls: 'fill-card', note: serverNote, actions: [search('Filter counters', '', (v) => { filter = v; renderServer(); }), refreshButton(() => serverTask.now())] })),
        h('div', { class: 'stack' }, card('Leaderboard', lbSlot, { flush: true, actions: lbPicker }))),
      h('div', { class: 'grid mt' },
        h('div', { class: 'span-12' }, card('Recent events', recentSlot, { flush: true, note: 'The newest events RestAPI kept from TrackerAPI.' })),
        h('div', { class: 'span-12' }, card('Journal tail', journalSlot, { flush: true, note: 'The newest lines of the journal on disk.' })),
        h('div', { class: 'span-12' }, card('Event kinds', kindsSlot, { flush: true }))));

    ctx.every(poll.fast(3), () => fill(sumSlot, async () => {
      let s;
      try { s = await ctx.get('/api/tracker'); } catch (e) { return suiteError(e, 'TrackerAPI'); }
      return h('div', { class: 'tiles' },
        tile('Event kinds', num(s.kinds), { sub: `${num(s.kindsTracked)} tracked` }),
        tile('Queue', num(s.queued), { unit: `of ${num(s.queueCapacity)}` }),
        tile('Journal', num(s.journalLines), { unit: 'lines', sub: `${bytes(s.journalBytes)}, ${num(s.journalPending)} pending` }),
        tile('Known players', num(s.knownPlayers), { sub: `${num(s.online)} online` }),
        tile('Leaderboards', num(s.leaderboards), { sub: `${num(s.subscriptions)} subscriptions` }),
        tile('Recent kept', num(s.recentKept), { unit: 'events', sub: h('span', null, 'Stats since ', time(s.statsSince)) }));
    }));

    let serverSeen = '';
    const serverTask = ctx.every(poll.slow(), () => fill(serverSlot, async () => {
      let next;
      try { next = (await ctx.get('/api/tracker/server', { timeout: 20000 })).counters; } catch (e) { serverSeen = ''; return suiteError(e, 'TrackerAPI'); }
      // Redraw only when a total moved, the table is long and keeps its scroll that way.
      const seen = JSON.stringify(next);
      if (seen === serverSeen) return undefined;
      serverSeen = seen;
      counters = next;
      renderServer();
      return undefined;
    }));

    const loadBoard = () => fill(lbSlot, async () => {
      if (!lbKey) return h('div', { class: 'empty', text: 'No leaderboards.' });
      try {
        const d = await ctx.get(`/api/tracker/leaderboards/${encodeURIComponent(lbKey)}?limit=25`);
        return table([
          { key: 'rank', label: 'Rank', num: true },
          { key: 'name', label: 'Player', render: (r) => playerLink(r.name, r.uuid) },
          { key: 'value', label: idText(lbKey), num: true },
        ], d.rows, { emptyText: 'Nobody has a value for this key yet.' });
      } catch (e) { return suiteError(e, 'TrackerAPI'); }
    });
    ctx.every(poll.slow(), async () => {
      try {
        const d = await ctx.get('/api/tracker/leaderboards');
        if (!lbKey || !d.keys.includes(lbKey)) lbKey = d.keys.includes('playtime') ? 'playtime' : d.keys[0];
        lbPicker.replaceChildren(select(d.keys.map((k) => [k, idText(k)]), lbKey, (v) => { lbKey = v; loadBoard(); }, { 'aria-label': 'Leaderboard key' }));
      } catch (e) { lbSlot.replaceChildren(suiteError(e, 'TrackerAPI')); return false; }
      return loadBoard();
    });

    ctx.every(poll.fast(2), () => fill(recentSlot, async () => {
      let d;
      try { d = await ctx.get('/api/tracker/recent?limit=60'); } catch (e) { return suiteError(e, 'TrackerAPI'); }
      const names = await ctx.names();
      return table([
        { key: 'at', label: 'When', cls: 'nowrap', render: (e) => time(e.at) },
        { key: 'kind', label: 'Kind', render: (e) => h('code', { text: e.kind }) },
        { key: 'world', label: 'World' },
        { key: 'player', label: 'Player', render: (e) => (e.player ? playerLink(names.get(e.player), e.player) : '') },
        { key: 'event', label: 'Detail', cls: 'wrap', render: (e) => trackerDetail(e.kind, e.event, names) },
      ], d.events, { tall: true, emptyText: 'No recent events.' });
    }));

    ctx.every(poll.slow(), () => fill(journalSlot, async () => {
      let d;
      try { d = await ctx.get('/api/tracker/journal?limit=40', { timeout: 20000 }); } catch (e) { return suiteError(e, 'TrackerAPI'); }
      const names = await ctx.names();
      return table([
        { key: 'seq', label: 'Seq', num: true },
        { key: 'at', label: 'When', cls: 'nowrap', render: (e) => time(e.at) },
        { key: 'kind', label: 'Kind', render: (e) => h('code', { text: e.kind }) },
        { key: 'world', label: 'World' },
        { key: 'player', label: 'Player', render: (e) => (e.player ? playerLink(names.get(e.player), e.player) : '') },
        { key: 'event', label: 'Detail', cls: 'wrap', render: (e) => trackerDetail(e.kind, e.event, names) },
      ], d.entries, { tall: true, emptyText: 'The journal is empty.' });
    }));

    ctx.every(poll.slow(), () => fill(kindsSlot, async () => {
      let d;
      try { d = await ctx.get('/api/tracker/kinds'); } catch (e) { return suiteError(e, 'TrackerAPI'); }
      return table([
        { key: 'id', label: 'Kind', render: (k) => h('code', { text: k.id }) },
        { key: 'tracked', label: 'Tracked', render: (k) => (k.tracked ? badge('yes', 'ok') : badge('no')) },
        { key: 'journaled', label: 'Journaled', render: (k) => (k.journaled ? 'yes' : 'no') },
        { key: 'highVolume', label: 'High volume', render: (k) => (k.highVolume ? badge('high', 'warn') : '') },
        { key: 'published', label: 'Published', num: true },
        { key: 'dropped', label: 'Dropped', num: true, render: (k) => (k.dropped ? badge(num(k.dropped), 'bad') : num(0)) },
      ], d.kinds, { state: { sortKey: 'published', sortDir: 'desc' }, tall: true });
    }));
    return undefined;
  },
};
