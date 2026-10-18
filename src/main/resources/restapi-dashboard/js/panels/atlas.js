// Atlas: map link, bind and port, health probe, render state, worlds, layers, markers and hidden players.

import { h, card, table, kv, tile, badge, playerLink, meter } from '../dom.js';
import { num, yesNo } from '../format.js';
import { slot, fill, suiteError } from './common.js';
import { atlasInfo, markerIcon, withIcon } from '../lively.js';
import { poll } from '../settings.js';

export default {
  mount(root, ctx) {
    const sum = slot();
    const render = slot();
    const worlds = slot();
    const layers = slot();
    const markers = slot();
    const hidden = slot();
    const address = slot();
    // The address and the hidden players stack on the left beside the render queue, so neither column runs long.
    root.append(sum, h('div', { class: 'cols even' },
      h('div', { class: 'stack' }, card('Map address', address), card('Hidden players', hidden, { flush: true })),
      h('div', { class: 'stack' }, card('Render queue', render))),
    h('div', { class: 'grid mt' },
      h('div', { class: 'span-12' }, card('Worlds', worlds, { flush: true })),
      h('div', { class: 'span-12' }, card('Marker layers', layers, { flush: true })),
      h('div', { class: 'span-12' }, card('Markers', markers, { flush: true }))));

    ctx.every(poll.fast(6), () => fill(sum, async () => {
      let a;
      try { a = await ctx.get('/api/atlas'); } catch (e) { address.replaceChildren(suiteError(e, 'Atlas')); return suiteError(e, 'Atlas'); }
      const hl = a.health || {};
      address.replaceChildren(kv([
        ['Public URL', a.url ? h('a', { href: a.url, target: '_blank', rel: 'noopener noreferrer', text: a.url }) : null],
        ['Public URL set', yesNo(a.publicUrlSet)],
        ['Bind', `${a.bind}:${a.port}`],
        ['Base path', a.basePath || '/'],
        ['Config read', yesNo(a.configRead)],
        ['Health', hl.ok ? badge('ok', 'ok') : badge(hl.reachable ? `HTTP ${hl.httpStatus}` : 'unreachable', 'bad')],
      ]));
      return [
        h('div', { class: 'tiles' },
          tile('Map', hl.ok ? 'Reachable' : hl.reachable ? `HTTP ${hl.httpStatus}` : 'Down', { tone: hl.ok ? '' : 'bad', sub: hl.version ? `Atlas web ${hl.version}` : 'Health probe from RestAPI' }),
          tile('Layers', num(a.layers)),
          tile('Markers', num(a.markers)),
          tile('Port', String(a.port), { sub: `bound to ${a.bind}` })),
      ];
    }));

    ctx.every(poll.fast(), () => fill(render, async () => {
      let r;
      try { r = await ctx.get('/api/atlas/render'); } catch (e) { return suiteError(e, 'Atlas'); }
      const per = Object.entries(r.queuedPerWorld || {});
      return kv([
        ['State', r.paused ? badge('paused', 'warn') : r.running || r.queued ? badge('rendering', 'info') : badge('idle', 'ok')],
        ['Queued', `${num(r.queued)} tiles`],
        ['Running', `${num(r.running)} tiles`],
        ['Done', `${num(r.done)} tiles since start`],
        ['Budget', meter(r.budgetLeft, r.budgetPerMinute, { tone: 'ok', text: `${num(r.budgetLeft)} of ${num(r.budgetPerMinute)} per min left` })],
        ['Queued per world', per.length ? per.map(([w, n]) => `${w} ${num(n)}`).join(', ') : 'none'],
      ]);
    }));

    ctx.every(poll.fast(6), () => fill(worlds, async () => {
      let d;
      try { d = await ctx.get('/api/atlas/worlds'); } catch (e) { return suiteError(e, 'Atlas'); }
      return table([
        { key: 'name', label: 'World' },
        { key: 'loaded', label: 'Loaded', render: (w) => yesNo(w.loaded) },
        { key: 'players', label: 'Players', num: true },
        { key: 'exploredChunks', label: 'Explored chunks', num: true },
        { key: 'queuedTiles', label: 'Queued tiles', num: true },
        { key: 'publicVisible', label: 'On the public map', render: (w) => (w.publicVisible ? badge('public', 'ok') : badge('hidden')) },
      ], d.worlds, { emptyText: 'Atlas maps no worlds.' });
    }));

    ctx.every(poll.slow(), () => fill(layers, async () => {
      let d;
      try { d = await ctx.get('/api/atlas/layers'); } catch (e) { return suiteError(e, 'Atlas'); }
      return table([
        { key: 'key', label: 'Layer', render: (l) => h('code', { text: l.key }) },
        { key: 'label', label: 'Label' },
        { key: 'plugin', label: 'Plugin' },
        { key: 'tier', label: 'Tier' },
        { key: 'markers', label: 'Markers', num: true },
        { key: 'worlds', label: 'Worlds', render: (l) => (l.worlds || []).join(', ') },
      ], d.layers || [], { emptyText: 'No other plugin has put a layer on the map.' });
    }));

    ctx.every(poll.slow(), () => fill(markers, async () => {
      let d;
      try { d = await ctx.get('/api/atlas/markers?limit=200'); } catch (e) { return suiteError(e, 'Atlas'); }
      const rows = (d.layers || []).flatMap((l) => (l.markers || []).map((m) => ({ ...m, layer: l.key })));
      const atlas = await atlasInfo(ctx);
      return table([
        { key: 'layer', label: 'Layer', render: (m) => h('code', { text: m.layer }) },
        { key: 'label', label: 'Label', cls: 'wrap', render: (m) => withIcon(atlas ? markerIcon(atlas.base, m.icon) : null, m.label) },
        { key: 'kind', label: 'Kind', render: (m) => (m.icon && atlas ? `${m.kind}, ${m.icon}` : m.kind) },
        { key: 'world', label: 'World' },
        { key: 'x', label: 'X', num: true },
        { key: 'z', label: 'Z', num: true },
        { key: 'id', label: 'Id', render: (m) => h('code', { text: m.id }) },
      ], rows, { state: { sortKey: 'layer', sortDir: 'asc' }, tall: true, emptyText: 'No markers.' });
    }));

    ctx.every(poll.slow(), () => fill(hidden, async () => {
      let d;
      try { d = await ctx.get('/api/atlas/hidden'); } catch (e) { return suiteError(e, 'Atlas'); }
      if (d.known === false) return h('div', { class: 'empty', text: 'This Atlas version does not say who is hidden.' });
      return table([
        { key: 'name', label: 'Player', render: (p) => playerLink(p.name, p.uuid) },
        { key: 'by', label: 'Hidden by', render: (p) => (p.by === 'self' ? 'their own choice' : p.by || '') },
      ], d.players, { emptyText: 'Nobody is hidden from the map.' });
    }));
  },
};
