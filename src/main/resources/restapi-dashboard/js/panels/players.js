// Players: who is online, everyone the server knows, and a page per player.

import { codeWrap, h, card, table, kv, time, badge, lifeBar, meter, pageHead, search, seg, playerLink, errorBox, empty } from '../dom.js';
import { num, ms, idText, yesNo, duration, NONE } from '../format.js';
import { slot, fill, suiteError } from './common.js';
import { statValue, statDef } from './chronicle.js';
import { writable, copyLinkButton } from '../ui.js';
import { actionBar, groupsEditor, quickMenu, popOpen } from './actions.js';
import { trackerDetail } from './tracker.js';
import { isLively, viewer, miniMap, atlasInfo, itemText, keyIcon, withIcon, medal } from '../lively.js';
import { poll, followMs } from '../settings.js';

const pos = (p) => (p ? `${num(p.x)}, ${num(p.y)}, ${num(p.z)}` : NONE);

export default {
  mount(root, ctx, rest) {
    if (rest[0]) return detail(root, ctx, rest[0]);
    const onlineSlot = slot();
    const knownSlot = slot();
    const sortOnline = { sortKey: 'name', sortDir: 'asc' };
    const sortKnown = { sortKey: 'online', sortDir: 'desc' };
    let filter = '';
    let known = [];
    let onlineNames = new Set();
    const renderKnown = () => {
      const rows = known.filter((p) => !filter || (p.name || '').toLowerCase().includes(filter) || p.uuid.includes(filter));
      knownSlot.replaceChildren(table([
        { key: 'name', label: 'Last name seen', render: (p) => (p.name ? playerLink(p.name, p.uuid) : h('a', { href: `#/players/${p.uuid}`, class: 'faint', text: 'name not known' })) },
        { key: 'uuid', label: 'UUID', render: (p) => h('code', { text: p.uuid }) },
        { key: 'online', label: 'State', sort: (p) => (p.online ? 1 : 0), render: (p) => (p.online ? badge('online', 'ok') : badge('offline')) },
      ], rows, { state: sortKnown, emptyText: filter ? 'No player matches the filter.' : 'No players have joined yet.' }));
    };

    root.append(
      pageHead('Players', writable() ? 'Online players refresh every 5 seconds. Actions on a row message, kick or teleport. A name opens the full page with every action.' : 'Online players refresh every 5 seconds. Pick a name for everything the API and the suite mods keep about that player.'),
      card('Online now', onlineSlot, { flush: true }),
      h('div', { class: 'mt' }, card('Known players', knownSlot, { flush: true, note: 'From /api/players/offline, the names the server has on file.', actions: search('Filter by name or UUID', '', (v) => { filter = v; renderKnown(); }) })));

    const onlineTask = ctx.every(poll.fast(), () => fill(onlineSlot, async () => {
      // An open quick menu belongs to a row, so the table waits until it is closed.
      if (popOpen()) return undefined;
      const d = await ctx.get('/api/players');
      onlineNames = new Set(d.players.map((p) => p.uuid));
      const late = d.worldsLate && d.worldsLate.length ? h('div', { class: 'table-foot', text: `Worlds that did not answer in time, ${d.worldsLate.join(', ')}. Their players show without live figures.` }) : null;
      return [table([
        { key: 'name', label: 'Name', render: (p) => playerLink(p.name, p.uuid) },
        { key: 'world', label: 'World' },
        { key: 'position', label: 'Position', num: true, sortable: false, render: (p) => pos(p.position) },
        { key: 'pingMs', label: 'Ping', num: true, render: (p) => ms(p.pingMs, 0) },
        { key: 'gameMode', label: 'Game mode' },
        { key: 'health', label: 'Health', sort: (p) => p.health?.value, render: (p) => (p.health ? lifeBar(p.health.value, p.health.max) : NONE) },
        ...(writable() ? [{ key: 'act', label: '', sortable: false, cls: 'nowrap', render: (p) => quickMenu(ctx, p.name || p.uuid, () => onlineTask.now()) }] : []),
      ], d.players, { state: sortOnline, emptyText: 'Nobody is online.' }), late];
    }, 'Online players'));

    ctx.every(poll.slow(), () => fill(knownSlot, async () => {
      const d = await ctx.get('/api/players/offline?limit=500');
      known = d.players;
      renderKnown();
      return undefined;
    }, 'Known players'));
    return undefined;
  },
};

function detail(root, ctx, who) {
  const enc = encodeURIComponent(who);
  const head = h('div');
  const main = slot();
  const groupsList = h('div', null, h('div', { class: 'loading', text: 'Loading' }));
  const modBox = h('div', { class: 'mt' });
  const groups = h('div', null, groupsList, modBox);
  const inv = slot();
  const tracker = slot();
  const counters = slot();
  const feats = slot();
  const chronicle = slot();
  const social = slot();
  const coinage = slot();
  const dominion = slot();
  const grid = h('div', { class: 'grid' });
  // Suite cards go into two pairs of stacks so short cards sit under each other beside the long tables.
  const leftA = h('div', { class: 'stack' });
  const rightA = h('div', { class: 'stack' });
  const leftB = h('div', { class: 'stack' });
  const rightB = h('div', { class: 'stack' });
  const pairA = h('div', { class: 'cols mt' }, leftA, rightA);
  const pairB = h('div', { class: 'cols even mt' }, leftB, rightB);
  root.append(head, grid, pairA, pairB);
  head.append(pageHead(who, 'Loading', null, h('a', { href: '#/players', text: 'Players' })));

  const add = (span, title, el, opts) => { const w = h('div', { class: span }, card(title, el, opts)); grid.append(w); return w; };
  const put = (stack, title, el, opts) => stack.append(card(title, el, opts));
  let last = null;
  let lastHead = '';
  let invSeen = '';
  let invTask = null;
  let groupsTask = null;
  let trackerTask = null;
  let lastTracker = null;
  const refreshAll = () => { mainTask.now(); if (invTask) invTask.now(); if (groupsTask) groupsTask.now(); if (editor) editor.reload(); };
  const bar = actionBar(ctx, who, () => last, refreshAll);
  const editor = writable() ? groupsEditor(ctx, who) : null;
  if (editor) groupsList.replaceChildren(editor.el);

  // Follow: every 2 seconds while on (the Follow interval on the Settings page), plus a feed of what the player does. Off by default.
  const followBtn = h('button', { type: 'button', class: 'btn follow', 'aria-pressed': 'false', text: 'Follow' });
  const feedBox = h('div', { class: 'feed follow-feed', role: 'log' });
  const followCard = h('div', { class: 'span-12', hidden: true }, card('What they are doing', feedBox, { note: 'Chat, commands, kills, deaths, blocks, items and zone changes of this player, newest first, at most 100.' }));
  let follow = null;
  followBtn.addEventListener('click', () => { if (follow) stopFollow(); else startFollow(); });
  ctx.cleanup(() => stopFollow());

  if (bar) add('span-12', 'Actions', bar.el);
  grid.append(followCard);
  add('span-7', 'Player', main);
  const groupsCol = add('span-5', 'Groups and moderation', groups);
  // The mini map sits under the groups in the Lively view, so both columns end close together.
  const mapCard = h('div', { class: 'mt', hidden: true });
  groupsCol.append(mapCard);
  add('span-12', 'Inventory', inv);

  // The Lively view adds the character in a frame and an Atlas mini map around the player. Both are made once,
  // so the 5 second refresh and Follow move them without drawing them again.
  let look = null;
  let map = null;
  const atlasP = atlasInfo(ctx);
  ctx.cleanup(() => { if (look) look.dispose(); });
  const sheet = async (p, info) => {
    if (!isLively()) return info;
    if (!look) look = viewer(p.name, p.uuid);
    const atlas = await atlasP;
    if (atlas && p.online && p.position && atlas.worlds.has(p.world)) {
      if (!map) {
        // With writes on the groups editor makes that column taller, so the map is a little smaller there.
        map = miniMap(atlas.base, { zoom: -1, size: writable() ? 170 : 200, tile: 120 });
        mapCard.replaceChildren(card('Map', map.el, { cls: 'map-card', note: 'Atlas tiles around the player, refreshed at most every 30 seconds.' }));
      }
      map.update(p.world, p.position.x, p.position.z, `${num(p.position.x)}, ${num(p.position.z)} in ${p.world}`);
      mapCard.hidden = false;
    } else {
      mapCard.hidden = true;
    }
    return h('div', { class: 'player-sheet' }, look ? look.el : null, h('div', { class: 'player-facts' }, info));
  };

  const mainTask = ctx.every(poll.fast(), () => fill(main, async () => {
    const p = await ctx.get(`/api/players/${enc}`);
    const wasOnline = last ? last.online : null;
    last = p;
    if (bar && wasOnline !== p.online) bar.refresh();
    // The head is drawn again only when what it shows changed, so the 5 second refresh keeps the focus on its buttons.
    const headKey = JSON.stringify([p.name, p.online, p.banned, p.uuid]);
    if (headKey !== lastHead) { lastHead = headKey; head.replaceChildren(pageHead(p.name || 'Name not known', h('span', null, p.online ? badge('online', 'ok') : badge('offline'), ' ', p.banned ? badge('banned', 'bad') : null, ' ', h('code', { text: p.uuid })), [copyLinkButton(`players/${encodeURIComponent(p.name || p.uuid)}`), followBtn], h('a', { href: '#/players', text: 'Players' }))); }
    const stats = p.stats || {};
    const bars = ['health', 'stamina', 'mana', 'oxygen'].filter((k) => stats[k]).map((k) => [idText(k[0].toUpperCase() + k.slice(1)), lifeBar(stats[k].value, stats[k].max, 1)]);
    modBox.replaceChildren(kv([
      ['Operator', yesNo(p.op)],
      ['Banned', p.banned ? badge('banned', 'bad') : 'no'],
      ['Ban reason', p.ban ? p.ban.reason : undefined],
      ['Ban until', p.ban ? (p.ban.until ? time(p.ban.until) : 'permanent') : undefined],
      ['Join grant', yesNo(p.joinGrant)],
    ]));
    if (!p.online) return sheet(p, h('p', { class: 'muted', style: { margin: 0 }, text: 'Offline. The server keeps no position, ping or stats for offline players, the suite mods below still have their history.' }));
    return sheet(p, kv([
      ['World', p.world],
      ['Position', pos(p.position)],
      ['Head rotation', p.headRotation ? `yaw ${num(p.headRotation.yaw, 1)} deg, pitch ${num(p.headRotation.pitch, 1)} deg` : null],
      ['Ping', ms(p.pingMs, 0)],
      ['Game mode', p.gameMode],
      ['Language', p.language],
      ...bars,
    ]));
  }, 'Player'));

  invTask = ctx.every(poll.fast(3), () => fill(inv, async () => {
    let d;
    try {
      d = await ctx.get(`/api/players/${enc}/inventory`);
    } catch (e) {
      invSeen = '';
      if (e.status === 409) return h('p', { class: 'muted', style: { margin: 0 }, text: 'Inventory is only readable while the player is in a world.' });
      throw e;
    }
    // Unchanged contents are not drawn again, so the item icons do not fade in anew every 15 seconds.
    const seen = JSON.stringify(d.sections || {});
    if (seen === invSeen) return undefined;
    invSeen = seen;
    const sections = Object.entries(d.sections || {});
    return h('div', { class: 'inv-grid' }, sections.map(([name, s]) => h('section', null,
      h('h4', null, idText(name[0].toUpperCase() + name.slice(1)), h('span', { text: `${num(s.items.length)} of ${num(s.capacity)} slots` })),
      s.items.length ? table([
        { key: 'slot', label: 'Slot', num: true },
        { key: 'item', label: 'Item', cls: 'wrap', render: (i) => itemText(i.item) },
        { key: 'quantity', label: 'Qty', num: true },
        { key: 'durability', label: 'Wear', render: (i) => (i.maxDurability ? meter(i.durability, i.maxDurability, { tone: i.durability / i.maxDurability < 0.25 ? 'bad' : 'ok', text: `${num((i.durability / i.maxDurability) * 100)} %` }) : '') },
      ], s.items) : empty('Empty'))));
  }, 'Inventory'));

  if (!editor) groupsTask = ctx.every(poll.slow(), () => fill(groupsList, async () => {
    const g = await ctx.get(`/api/players/${enc}/groups`);
    return kv([['Groups', g.groups.length ? h('span', { class: 'tag-list' }, g.groups.map((x) => badge(x, 'info'))) : 'none']]);
  }, 'Groups'));

  // Suite blocks, only for bound mods.
  ctx.suite().then((suite) => {
    const on = (id) => suite[id] && suite[id].bound;
    if (on('tracker')) {
      put(leftA, 'TrackerAPI live state', tracker);
      put(rightA, 'TrackerAPI counters', counters, { cls: 'fill-card' });
      trackerTask = ctx.every(poll.fast(), () => fill(tracker, async () => {
        try {
          const t = await ctx.get(`/api/tracker/players/${enc}`, { quiet: true });
          if (follow && lastTracker && t.zone !== lastTracker.zone) follow.add({ key: `zone-${t.sampledAt}`, at: Date.now(), kind: 'zone', text: `entered ${idText(t.zone)}${t.biome ? `, ${idText(t.biome)}` : ''}` });
          lastTracker = t;
          return kv([
            ['Zone', idText(t.zone)], ['Region', idText(t.region)], ['Biome', idText(t.biome)],
            ['Position', `${num(t.x)}, ${num(t.y)}, ${num(t.z)} in ${t.world}`],
            ['Moving', ['swimming', 'gliding', 'riding', 'creative'].filter((k) => t[k]).join(', ') || 'on foot'],
            ['Session', t.sessionStart ? h('span', null, duration((Date.now() - t.sessionStart) / 1000), ', started ', time(t.sessionStart)) : null],
            ['Sampled', time(t.sampledAt)],
          ]);
        } catch (e) {
          if (e.status === 404) return h('p', { class: 'muted', style: { margin: 0 }, text: 'Not online, TrackerAPI keeps live state for online players only.' });
          return suiteError(e, 'TrackerAPI');
        }
      }));
      let cFilter = '';
      let cData = null;
      const cState = { sortKey: 'value', sortDir: 'desc' };
      const cBox = h('div', { class: 'fill-box' });
      const renderCounters = () => {
        const rows = Object.entries(cData.counters).map(([key, value]) => ({ key, value })).filter((r) => !cFilter || r.key.toLowerCase().includes(cFilter));
        cBox.replaceChildren(table([
          { key: 'key', label: 'Counter', cls: 'wrap', render: (r) => withIcon(keyIcon(r.key), codeWrap(r.key)) },
          { key: 'value', label: 'Value', num: true },
        ], rows, { state: cState, limit: 200, tall: true, emptyText: 'No counter matches.' }));
      };
      // The filter box is made once and the table is redrawn only when a counter moved, so typing keeps focus.
      const cCount = h('span', { class: 'faint' });
      const cTools = h('div', { class: 'toolbar', style: { marginBottom: '8px' } }, search('Filter counters', cFilter, (v) => { cFilter = v; renderCounters(); }), cCount);
      let cSeen = '';
      ctx.every(poll.slow(), () => fill(counters, async () => {
        let next;
        try { next = await ctx.get(`/api/tracker/counters/${enc}`); } catch (e) { cSeen = ''; return suiteError(e, 'TrackerAPI'); }
        const seen = JSON.stringify(next.counters);
        if (seen === cSeen) return undefined;
        const first = !cSeen;
        cSeen = seen;
        cData = next;
        cCount.textContent = `${num(Object.keys(cData.counters).length)} counters`;
        renderCounters();
        return first ? [cTools, cBox] : undefined;
      }));
    }
    if (on('feats')) {
      put(leftB, 'Feats', feats);
      ctx.every(poll.slow(), () => fill(feats, async () => {
        let f;
        try { f = await ctx.get(`/api/feats/players/${enc}`); } catch (e) { return suiteError(e, 'Feats'); }
        const byTier = {};
        for (const u of f.unlocked) (byTier[u.tier] ||= []).push(u);
        const order = ['PLATINUM', 'GOLD', 'SILVER', 'BRONZE'];
        return [
          kv([
            ['Points', `${num(f.points)} points, rank ${num(f.rank)}`],
            ['Unlocked', meter(f.unlockedCount, f.featCount, { tone: 'ok', text: `${num(f.unlockedCount)} of ${num(f.featCount)}` })],
            ['Title', f.title],
          ]),
          h('div', { class: 'mt' }, Object.keys(byTier).sort((a, b) => order.indexOf(a) - order.indexOf(b)).map((t) => h('details', { open: t === 'PLATINUM' || t === 'GOLD' ? true : null },
            h('summary', null, medal(t, `${idText(t[0] + t.slice(1).toLowerCase())}, ${num(byTier[t].length)} unlocked`) || `${idText(t[0] + t.slice(1).toLowerCase())}, ${num(byTier[t].length)} unlocked`),
            isLively()
              ? h('div', { class: 'tag-list', style: { margin: '6px 0 10px' } }, byTier[t].map((u) => h('span', { class: `tier-chip t-${t.toLowerCase()}` }, h('span', { class: 'medal', 'aria-hidden': 'true' }), h('span', { class: 'tier-name', text: u.name }), h('b', { text: num(u.points) }))))
              : h('div', { class: 'tag-list', style: { margin: '6px 0 10px' } }, byTier[t].map((u) => badge(`${u.name} ${u.points}`, '')))))),
        ];
      }));
    }
    if (on('chronicle')) {
      put(rightB, 'Chronicle Stats', chronicle, { cls: 'fill-card' });
      let win = 'all';
      let cat = null;
      const box = h('div', { class: 'fill-box' });
      const load = () => fill(chronicle, async () => {
        let c;
        try { c = await ctx.get(`/api/chronicle/players/${enc}?window=${win}`); } catch (e) { return suiteError(e, 'Chronicle Stats'); }
        // The catalogue gives each stat its unit, so playtime reads as a duration. Without it values show as plain numbers.
        const defs = await ctx.cached('/api/chronicle/stats', 300000, { timeout: 20000 }).then((d) => new Map(d.stats.map((s) => [s.key, s])), () => new Map());
        const cats = Object.keys(c.categories || {});
        if (!cat || !cats.includes(cat)) cat = cats.includes('general') ? 'general' : cats[0];
        const renderCat = () => {
          const rows = Object.entries(c.categories[cat] || {}).map(([key, value]) => ({ key, value }));
          box.replaceChildren(table([
            { key: 'key', label: 'Stat', cls: 'wrap', render: (r) => withIcon(keyIcon(r.key), codeWrap(r.key)) },
            { key: 'value', label: 'Value', num: true, render: (r) => (cat === 'feats' ? (r.value ? time(r.value) : 'locked') : statValue(statDef(defs, r.key), r.value)) },
          ], rows, { state: { sortKey: 'value', sortDir: 'desc' }, tall: true, emptyText: 'No stats in this category.' }));
        };
        renderCat();
        return [
          h('div', { class: 'toolbar', style: { marginBottom: '8px' } },
            seg([['all', 'All time'], ['month', 'Month'], ['week', 'Week'], ['today', 'Today']], win, (v) => { win = v; load(); }),
            h('select', { 'aria-label': 'Category', onchange: (e) => { cat = e.target.value; renderCat(); } }, cats.map((k) => h('option', { value: k, selected: k === cat ? true : null, text: `${k} (${Object.keys(c.categories[k]).length})` })))),
          c.tracked === false ? h('div', { class: 'notice', text: 'Chronicle does not track this player.' }) : null,
          box,
        ];
      }, 'Chronicle Stats');
      ctx.every(poll.slow(), load);
    }
    if (on('socialbridge')) {
      put(leftA, 'SocialBridge', social);
      ctx.every(poll.slow(), () => fill(social, async () => {
        try {
          const l = await ctx.get(`/api/socialbridge/links/${enc}`);
          return kv([['Service', l.service], ['Discord user id', h('code', { text: l.user })]]);
        } catch (e) {
          if (e.status === 404 && e.code === 'not_linked') return h('p', { style: { margin: 0 }, text: 'Not linked to a Discord account.' });
          return suiteError(e, 'SocialBridge');
        }
      }));
    }
    if (on('coinage')) {
      put(leftA, 'Coinage', coinage);
      ctx.every(poll.slow(), () => fill(coinage, async () => {
        try {
          const b = await ctx.get(`/api/coinage/balances/${enc}`);
          return kv([
            ...Object.entries(b.balances).map(([cur, a]) => [idText(cur[0].toUpperCase() + cur.slice(1)), a.text]),
            ['Rank', `${num(b.rank)} by ${b.currency}`],
            ['Net worth', b.netWorth ? b.netWorth.total.text : null],
            ['Status', b.status],
          ]);
        } catch (e) {
          if (e.status === 404) return h('p', { class: 'muted', style: { margin: 0 }, text: 'Coinage has no account for this player.' });
          return suiteError(e, 'Coinage');
        }
      }));
    }
    if (on('dominion')) {
      put(leftA, 'Dominion', dominion);
      ctx.every(poll.slow(), () => fill(dominion, async () => {
        try {
          const d = await ctx.get(`/api/dominion/players/${enc}`, { quiet: true });
          if (!d.faction) return kv([['Faction', 'none'], ['Power', num(d.power, 1)]]);
          return kv([
            ['Faction', h('a', { href: '#/suite/dominion', text: `${d.faction.name} [${d.faction.tag}]` })],
            ['Rank', d.rankName || d.rank], ['Power', num(d.power, 1)], ['Kills', num(d.kills)], ['Deaths', num(d.deaths)], ['Joined', time(d.joinedAt)],
          ]);
        } catch (e) {
          // Dominion only knows online players and faction members, anyone else is a 404 player_not_found.
          if (e.status === 404 && e.code === 'player_not_found') return kv([['Faction', 'none'], ['Power', 'not known while offline']]);
          return suiteError(e, 'Dominion');
        }
      }));
    }
  }).catch((e) => grid.append(h('div', { class: 'span-12' }, errorBox(e, 'Suite table'))));

  function startFollow() {
    const items = [];
    const keys = new Set();
    const render = () => {
      feedBox.replaceChildren(...(items.length ? items.map((x) => h('div', { class: 'follow-ln' },
        h('span', { class: 'faint nowrap' }, time(x.at)), badge(x.kind, KIND_TONE[x.kind] || ''), h('span', { class: 'follow-text', text: x.text }))) : [empty('Watching. Nothing yet.')]));
    };
    const add = (x) => {
      if (keys.has(x.key)) return;
      keys.add(x.key);
      items.push(x);
      items.sort((a, b) => b.at - a.at);
      if (items.length > 100) for (const gone of items.splice(100)) keys.delete(gone.key);
      render();
    };
    const mine = (e) => {
      const n = (last && last.name ? last.name : who).toLowerCase();
      const u = last ? last.uuid : null;
      return (e.uuid && e.uuid === u) || (e.player && String(e.player).toLowerCase() === n) || (e.by && String(e.by).toLowerCase() === n) || (e.killer && String(e.killer).toLowerCase() === n);
    };
    const fromEvent = (e) => {
      if (!mine(e)) return;
      const text = e.type === 'chat' ? `said "${e.text != null ? e.text : `${e.length} characters`}"`
        : e.type === 'command' ? `ran /${e.command}`
          : e.type === 'death' ? (e.killer && String(e.killer).toLowerCase() === (last?.name || who).toLowerCase() ? `killed ${e.player || e.npc}` : `died, ${e.cause || 'unknown cause'}${e.killerNpc ? ` to ${idText(e.killerNpc)}` : e.killer ? ` to ${e.killer}` : ''}`)
            : e.type === 'join' ? `joined in ${e.world}` : e.type === 'leave' ? 'left the server' : e.type === 'ready' ? `is ready in ${e.world}` : e.type;
      add({ key: `e${e.id}`, at: Date.parse(e.time), kind: e.type === 'death' && text.startsWith('killed') ? 'kill' : e.type, text });
    };
    const kindOf = (k) => (k === 'entity_died' ? 'kill' : k.startsWith('block_') ? 'block' : k.startsWith('item_') ? 'item' : k.split('_')[0]);
    const fromTracker = async () => {
      if (!last || !trackerTask) return;
      try {
        const d = await ctx.get('/api/tracker/recent?limit=100', { quiet: true });
        const names = new Map([[last.uuid, last.name]]);
        for (const e of d.events) {
          if (e.player !== last.uuid) continue;
          add({ key: `t${e.seq}`, at: e.at, kind: kindOf(e.kind), text: trackerDetail(e.kind, e.event, names) });
        }
      } catch { /* TrackerAPI may be gone, the event stream still feeds the list */ }
    };
    follow = { add, timer: null, stream: null };
    followBtn.classList.add('on');
    followBtn.setAttribute('aria-pressed', 'true');
    followBtn.textContent = 'Following';
    followCard.hidden = false;
    render();
    ctx.get('/api/events/recent?limit=200', { quiet: true }).then((d) => d.events.forEach(fromEvent), () => {});
    follow.stream = ctx.stream('/api/events', { events: ['chat', 'command', 'death', 'join', 'leave', 'ready'], onEvent: (type, e) => { if (follow && e && typeof e === 'object') fromEvent(e); } });
    const beat = () => { if (document.hidden) return; mainTask.now(); if (trackerTask) trackerTask.now(); fromTracker(); };
    // The Follow interval comes from the settings and is read again before every wait.
    const loop = () => { if (!follow) return; beat(); follow.timer = setTimeout(loop, followMs()); };
    loop();
  }
  function stopFollow() {
    if (!follow) return;
    clearTimeout(follow.timer);
    if (follow.stream) follow.stream.close();
    follow = null;
    followBtn.classList.remove('on');
    followBtn.setAttribute('aria-pressed', 'false');
    followBtn.textContent = 'Follow';
    followCard.hidden = true;
  }
  return undefined;
}

const KIND_TONE = { chat: 'info', command: '', kill: 'warn', death: 'bad', block: '', item: '', zone: 'ok', join: 'ok', leave: '' };


