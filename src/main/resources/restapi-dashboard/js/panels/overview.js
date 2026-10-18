// Overview: health, server facts, worlds, heap, siblings, stop state, backups and the latest events.

import { h, card, tile, kv, table, time, meter, badge, dot, pageHead, errorBox } from '../dom.js';
import { num, ms, tps, mb, duration, absTime, yesNo } from '../format.js';
import { sparkline } from '../charts.js';
import { slot, fill } from './common.js';
import { eventSummary, typeBadge } from './events.js';
import { modIcon } from '../lively.js';
import { poll } from '../settings.js';

export default {
  mount(root, ctx) {
    const tiles = h('div', { class: 'tiles' });
    const stall = h('div');
    const worldsSlot = slot();
    const factsSlot = slot();
    const stopSlot = slot();
    const backupSlot = slot();
    const eventsSlot = slot();
    const siblingsSlot = slot();

    root.append(
      pageHead('Overview', 'Live state of the server, refreshed every 5 seconds while this page is open.'),
      stall, tiles,
      h('div', { class: 'grid' },
        h('div', { class: 'span-12' }, card('Worlds', worldsSlot, { flush: true, note: 'TPS trend covers the samples this tab has taken.' })),
        h('div', { class: 'span-4' }, card('Server', factsSlot)),
        h('div', { class: 'span-4' }, card('Suite mods', siblingsSlot, { actions: h('a', { href: '#/suite', text: 'Open suite' }) })),
        h('div', { class: 'span-4' }, card('Stop countdown', stopSlot), h('div', { class: 'mt' }, card('Backups', backupSlot))),
        h('div', { class: 'span-12' }, card('Latest events', eventsSlot, { flush: true, actions: h('a', { href: '#/events', text: 'All events' }) }))));

    ctx.every(poll.fast(), async () => {
      let s;
      try {
        s = await ctx.get('/api/server');
      } catch (e) {
        tiles.replaceChildren(errorBox(e, 'Server status'));
        return false;
      }
      ctx.recordServer(s);
      const hl = ctx.health();
      const stalled = s.worlds.filter((w) => w.stalled);
      stall.replaceChildren(stalled.length ? h('div', { class: 'notice bad stall-banner', role: 'alert' }, h('strong', { text: `${stalled.map((w) => w.name).join(', ')} stalled.` }), ' The world thread has not answered a sample for three intervals.') : '');
      const heapRatio = s.jvm.heapUsedMb / s.jvm.heapMaxMb;
      const bound = s.siblings.filter((x) => x.bound).length;
      tiles.replaceChildren(
        tile('Health', hl ? (hl.status === 'ok' ? 'Healthy' : hl.status) : 'Unknown', { sub: hl ? `RestAPI ${hl.restapi}` : 'No answer from /api/health', tone: hl && hl.status === 'ok' ? '' : 'bad' }),
        tile('Players online', num(s.players.online), { unit: `of ${num(s.server.maxPlayers)}`, meterEl: meter(s.players.online, s.server.maxPlayers, { tone: 'ok' }) }),
        tile('Uptime', duration(s.server.uptimeSeconds), { sub: h('span', { title: absTime(s.server.bootedAt) }, 'Booted ', time(s.server.bootedAt)) }),
        tile('Heap used', mb(s.jvm.heapUsedMb), { unit: `of ${mb(s.jvm.heapMaxMb)}`, meterEl: meter(s.jvm.heapUsedMb, s.jvm.heapMaxMb), sub: `${num(heapRatio * 100)} % of max, ${mb(s.jvm.heapCommittedMb)} committed` }),
        tile('Threads', num(s.jvm.threads), { sub: `${num(s.jvm.cpus)} CPUs` }),
        tile('System load', num(s.jvm.systemLoad, 2), { sub: `1 min average, process CPU ${num((s.jvm.processCpu || 0) * 100)} %` }),
        tile('Suite bound', num(bound), { unit: `of ${num(s.siblings.length)}`, sub: s.siblings.filter((x) => !x.bound).map((x) => x.id).join(', ') || 'every sibling bound' }),
      );

      const ring = ctx.ring();
      worldsSlot.replaceChildren(table([
        { key: 'name', label: 'World', cls: 'nowrap', render: (w) => h('span', null, dot(w.stalled ? 'bad' : 'ok'), h('a', { href: '#/worlds', text: w.name })) },
        { key: 'players', label: 'Players', num: true },
        { key: 'tps', label: 'TPS', num: true, render: (w) => `${tps(w.tps)} of ${w.targetTps}` },
        { key: 'trend', label: 'Trend', render: (w) => sparkline(ring.map((r) => r.worlds[w.name]?.tps ?? null), { min: 0, max: w.targetTps }) },
        { key: 'tickAvgMs', label: 'Tick avg', num: true, render: (w) => ms(w.tickAvgMs) },
        { key: 'tickMaxMs', label: 'Tick max', num: true, render: (w) => ms(w.tickMaxMs) },
        { key: 'chunks', label: 'Chunks', num: true },
        { key: 'entities', label: 'Entities', num: true },
        { key: 'sampledAgoMs', label: 'Sampled', num: true, render: (w) => `${num(w.sampledAgoMs / 1000, 1)} s ago` },
        { key: 'stalled', label: 'State', render: (w) => (w.stalled ? badge('stalled', 'bad') : badge('ticking', 'ok')) },
      ], s.worlds, { emptyText: 'No worlds loaded.' }));

      factsSlot.replaceChildren(kv([
        ['Name', s.server.name],
        ['MOTD', s.server.motd || null],
        ['Version', `${s.server.version} on ${s.server.patchline}`],
        ['Revision', h('code', { title: s.server.revision, text: (s.server.revision || '').slice(0, 12) })],
        ['Phase', badge(s.server.phase, s.server.phase === 'running' ? 'ok' : 'warn')],
        ['Booted', time(s.server.bootedAt)],
        ['View radius', `${num(s.server.maxViewRadius)} chunks`],
        ['Password', yesNo(s.server.passwordSet)],
        ['Java', s.jvm.java],
        ['RestAPI', `${s.restapi}, ${num(s.api.count)} routes`],
      ]));

      siblingsSlot.replaceChildren(h('ul', { class: 'list-plain' }, s.siblings.map((m) => h('li', null,
        h('span', { class: 'mod-name' }, dot(m.bound ? 'ok' : m.installed ? 'warn' : ''), modIcon(m.id, 18), h('a', { href: `#/suite/${m.id}`, text: m.plugin.replace('Ryvrook:', '') })),
        h('span', { class: 'muted' }, m.bound ? `${m.version}, ${num(m.routes)} routes` : (m.reason || 'not bound'))))));
      return true;
    });

    ctx.every(poll.fast(), () => fill(stopSlot, async () => {
      const st = await ctx.get('/api/server/stop');
      if (!st.pending) return h('div', { class: 'muted', text: 'No stop is counting down.' });
      return [
        h('div', { class: 'countdown', text: duration(st.secondsLeft) }),
        kv([['Stop id', h('code', { text: st.stop })], ['Restart', yesNo(st.restart)], ['Exit code', st.exitCode]]),
      ];
    }, 'Stop state'));

    ctx.every(poll.slow(), () => fill(backupSlot, async () => {
      const c = await ctx.get('/api/server/config');
      const b = c.backup || {};
      if (!b.configured) return h('div', { class: 'muted', text: 'Backups are not configured in config.json.' });
      return kv([
        ['Enabled', b.enabled ? badge('on', 'ok') : badge('off', 'warn')],
        ['Every', `${num(b.frequencyMinutes)} min`],
        ['Directory', h('code', { text: b.directory })],
        ['Kept', `${num(b.maxCount)} backups, ${num(b.archiveMaxCount)} archives`],
      ]);
    }, 'Server config'));

    ctx.every(poll.fast(3), () => fill(eventsSlot, async () => {
      const d = await ctx.get('/api/events/recent?limit=10');
      return table([
        { key: 'time', label: 'When', cls: 'nowrap', render: (e) => time(e.time) },
        { key: 'type', label: 'Type', render: typeBadge },
        { key: 'detail', label: 'Detail', cls: 'wrap', render: (e) => eventSummary(e, { bust: true }) },
      ], d.events.slice().reverse(), { emptyText: 'No events in the ring yet.' });
    }, 'Recent events'));
  },
};
