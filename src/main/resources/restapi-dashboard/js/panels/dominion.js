// Dominion: factions, running sieges, finished sieges, relations and claims.

import { h, card, table, tile, time, badge, playerLink, kv } from '../dom.js';
import { num } from '../format.js';
import { slot, fill, suiteError } from './common.js';
import { write } from '../api.js';
import { act, confirmAfter, writable, input, numInput, field, formRow, wbutton, choose } from '../ui.js';
import { poll } from '../settings.js';

const enc = encodeURIComponent;
/** One Dominion staff call. The answer is {ok, message}, or 409 refused with Dominion's reason. */
const staff = (what, path, body) => act(what, async () => { const { data } = await write('POST', path, body); return data.message || `${what} done.`; });

const swatch = (c) => h('span', { class: 'swatch', style: { background: /^#[0-9a-f]{3,8}$/i.test(c || '') ? c : 'var(--faint)' } });
const REL_TONE = { ally: 'ok', truce: 'info', enemy: 'bad' };

function siegeColumns(names) {
  return [
    { key: 'attacker', label: 'Attacker', render: (s) => s.attacker?.tag || h('span', { class: 'faint', text: 'gone' }) },
    { key: 'defender', label: 'Defender', render: (s) => s.defender?.tag || h('span', { class: 'faint', text: 'gone' }) },
    { key: 'phase', label: 'Phase', render: (s) => badge(s.phase, s.phase === 'capturing' ? 'warn' : s.phase === 'breached' ? 'bad' : s.phase === 'defended' ? 'ok' : '') },
    { key: 'world', label: 'Where', render: (s) => `${s.world} chunk ${s.chunk ? s.chunk.join(', ') : '-'}` },
    { key: 'startedAt', label: 'Started', cls: 'nowrap', render: (s) => time(s.startedAt) },
    { key: 'phaseEndsAt', label: 'Phase ends', cls: 'nowrap', render: (s) => time(s.phaseEndsAt) },
    { key: 'startedBy', label: 'Started by', render: (s) => (s.startedBy ? playerLink(names.get(s.startedBy), s.startedBy) : '') },
    { key: 'blocksBroken', label: 'Blocks', num: true },
    { key: 'containersOpened', label: 'Containers', num: true },
    { key: 'attackerDeaths', label: 'Attacker deaths', num: true },
    { key: 'defenderDeaths', label: 'Defender deaths', num: true },
  ];
}

export default {
  mount(root, ctx) {
    const sum = slot();
    const factions = slot();
    const sieges = slot();
    const history = slot();
    const relations = slot();
    const claims = slot();
    const factionDetail = h('div');
    root.append(sum, h('div', { class: 'grid' },
      h('div', { class: 'span-12' }, card('Factions', factions, { flush: true, note: 'Ranking order, then the safe and war zones. Pick a tag for members.' })),
      h('div', { class: 'span-12' }, factionDetail),
      h('div', { class: 'span-12' }, card('Sieges running', sieges, { flush: true })),
      h('div', { class: 'span-12' }, card('Finished sieges', history, { flush: true })),
      h('div', { class: 'span-6' }, card('Relations', relations, { flush: true })),
      h('div', { class: 'span-6' }, card('Claims', claims, { flush: true }))));
    const refreshAll = () => { factionsTask.now(); claimsTask.now(); sumTask.now(); };
    if (writable()) sum.after(h('div', { class: 'mb' }, staffCard(refreshAll)));

    // The detail route has no rank, the list row passes it in.
    const showFaction = async (tag, rank) => {
      factionDetail.replaceChildren(card(`Faction ${tag}`, h('div', { class: 'loading', text: 'Loading' })));
      try {
        const f = await ctx.get(`/api/dominion/factions/${encodeURIComponent(tag)}`);
        factionDetail.replaceChildren(card(h('h2', null, swatch(f.color), `${f.name} [${f.tag}]`), [
          kv([
            ['Rank', rank == null ? 'unranked' : num(rank)], ['Power', num(f.power, 1)], ['Bank', num(f.bank, 2)], ['Points', num(f.points)],
            ['Relations', f.relations && f.relations.length ? h('span', { class: 'tag-list' }, f.relations.map((r) => badge(`${r.relation} ${r.tag || 'a faction that is gone'}`, REL_TONE[r.relation] || ''))) : 'none'],
            ['Upgrades', Object.entries(f.upgrades || {}).map(([k, v]) => `${k} ${v}`).join(', ') || 'none'],
          ]),
          h('div', { class: 'mt' }, table([
            { key: 'name', label: 'Member', render: (m) => playerLink(m.name, m.uuid) },
            { key: 'rankName', label: 'Rank', render: (m) => m.rankName || m.rank },
            { key: 'power', label: 'Power', num: true, render: (m) => num(m.power, 1) },
            { key: 'kills', label: 'Kills', num: true },
            { key: 'deaths', label: 'Deaths', num: true },
            { key: 'joinedAt', label: 'Joined', cls: 'nowrap', render: (m) => time(m.joinedAt) },
            { key: 'lastSeen', label: 'Last seen', cls: 'nowrap', render: (m) => time(m.lastSeen) },
          ], f.memberList || [], { emptyText: 'No members.' })),
          writable() ? factionStaff(f, () => { refreshAll(); showFaction(tag, rank); }, () => { refreshAll(); factionDetail.replaceChildren(); }) : null,
        ], { actions: h('button', { type: 'button', class: 'btn', text: 'Close', onclick: () => factionDetail.replaceChildren() }) }));
      } catch (e) {
        factionDetail.replaceChildren(suiteError(e, 'Dominion'));
      }
    };

    const sumTask = ctx.every(poll.fast(6), () => fill(sum, async () => {
      let s;
      try { s = await ctx.get('/api/dominion'); } catch (e) { return suiteError(e, 'Dominion'); }
      return h('div', { class: 'tiles' },
        tile('Factions', num(s.factions), { sub: `${num(s.zones)} zones` }),
        tile('Members', num(s.members)),
        tile('Claims', num(s.claims), { unit: 'chunks' }),
        tile('Worlds with claims', num((s.worldsWithClaims || []).length), { sub: (s.worldsWithClaims || []).join(', ') || 'none' }));
    }));

    const factionsTask = ctx.every(poll.fast(6), () => fill(factions, async () => {
      let d;
      try { d = await ctx.get('/api/dominion/factions'); } catch (e) { return suiteError(e, 'Dominion'); }
      const names = await ctx.names();
      return table([
        { key: 'rank', label: 'Rank', num: true, render: (f) => (f.rank == null ? h('span', { class: 'faint', text: 'zone' }) : num(f.rank)) },
        { key: 'tag', label: 'Tag', render: (f) => h('span', null, swatch(f.color), f.type === 'normal' ? h('a', { href: '#/suite/dominion', text: f.tag, onclick: (e) => { e.preventDefault(); showFaction(f.tag, f.rank); } }) : f.tag) },
        { key: 'name', label: 'Name' },
        { key: 'type', label: 'Type' },
        { key: 'leader', label: 'Leader', render: (f) => (f.leader ? playerLink(names.get(f.leader), f.leader) : '') },
        { key: 'members', label: 'Members', num: true },
        { key: 'power', label: 'Power', num: true, render: (f) => num(f.power, 1) },
        { key: 'claims', label: 'Claims', num: true },
        { key: 'bank', label: 'Bank', num: true, render: (f) => num(f.bank, 2) },
        { key: 'points', label: 'Points', num: true },
        { key: 'raidable', label: 'Raidable', render: (f) => (f.raidable ? badge('raidable', 'bad') : '') },
      ], d.factions, { emptyText: 'No factions yet.' });
    }));

    ctx.every(poll.fast(2), () => fill(sieges, async () => {
      let d;
      try { d = await ctx.get('/api/dominion/sieges'); } catch (e) { return suiteError(e, 'Dominion'); }
      const names = await ctx.names();
      // Before Dominion 0.7.0 a siege is only a map point with the faction it belongs to and when it ends.
      if (d.sidesKnown === false) {
        return [
          h('div', { class: 'notice', style: { margin: '10px' }, text: 'This Dominion version only shows the siege banners, not both sides.' }),
          table([
            { key: 'faction', label: 'Faction', render: (s) => h('span', null, swatch(s.color), s.faction || '-') },
            { key: 'label', label: 'Label', cls: 'wrap' },
            { key: 'world', label: 'Where', render: (s) => `${s.world} at ${num(s.x)}, ${num(s.z)}` },
            { key: 'endsAt', label: 'Ends', cls: 'nowrap', render: (s) => time(s.endsAt) },
          ], d.sieges, { emptyText: 'No siege is running.' }),
        ];
      }
      return table(siegeColumns(names), d.sieges, { emptyText: 'No siege is running.' });
    }));

    ctx.every(poll.slow(), () => fill(history, async () => {
      try {
        const d = await ctx.get('/api/dominion/sieges/history?limit=20');
        const names = await ctx.names();
        return table(siegeColumns(names), d.sieges, { emptyText: 'No siege has finished yet.' });
      } catch (e) {
        if (e.status === 501) return h('div', { class: 'empty', text: 'This Dominion version keeps no siege history.' });
        return suiteError(e, 'Dominion');
      }
    }));

    ctx.every(poll.slow(), () => fill(relations, async () => {
      let d;
      try { d = await ctx.get('/api/dominion/relations'); } catch (e) { return suiteError(e, 'Dominion'); }
      return table([
        { key: 'a', label: 'Faction' },
        { key: 'relation', label: 'Relation', render: (r) => badge(r.relation, REL_TONE[r.relation] || '') },
        { key: 'b', label: 'Other' },
        { key: 'mutual', label: 'Both ways', render: (r) => (r.mutual ? 'yes' : 'no') },
      ], d.relations, { emptyText: 'Every pair is neutral.' });
    }));

    const claimsTask = ctx.every(poll.slow(), () => fill(claims, async () => {
      let d;
      try { d = await ctx.get('/api/dominion/claims'); } catch (e) { return suiteError(e, 'Dominion'); }
      return table([
        { key: 'faction', label: 'Faction', render: (t) => h('span', null, swatch(t.color), t.faction || t.name) },
        { key: 'world', label: 'World' },
        { key: 'type', label: 'Type' },
        { key: 'count', label: 'Chunks', num: true },
        { key: 'raidable', label: 'State', render: (t) => (t.breached ? badge('breached', 'bad') : t.raidable ? badge('raidable', 'warn') : badge('held', 'ok')) },
      ], d.territories, { emptyText: 'Nothing is claimed.' });
    }));
  },
};

/** Leader, unclaim all and disband for one faction. Needs dominion.write, each asks first. */
function factionStaff(f, after, gone) {
  const leader = choose((f.memberList || []).filter((m) => m.uuid !== f.leader).map((m) => [m.name || m.uuid, `${m.name || m.uuid}, ${m.rankName || m.rank}`]), { placeholder: 'New leader', attrs: { 'aria-label': 'New leader' } });
  const setLeader = wbutton('Set leader', () => {
    if (!leader.value) return leader.focus();
    confirmAfter(setLeader, `Make ${leader.value} the leader of ${f.name}?`, 'Set leader', async () => { if (await staff('Leader change', `/api/dominion/factions/${enc(f.tag)}/leader`, { player: leader.value })) after(); });
  });
  const unclaim = wbutton('Unclaim all', () => confirmAfter(unclaim, `Release every claim of ${f.name} in every world?`, 'Unclaim all', async () => { if (await staff('Unclaim all', `/api/dominion/factions/${enc(f.tag)}/unclaim-all`)) after(); }), 'btn danger-soft');
  const disband = wbutton('Disband', () => confirmAfter(disband, `Disband ${f.name}? The bank is paid out and the faction is gone.`, 'Disband', async () => { if (await staff('Disband', `/api/dominion/factions/${enc(f.tag)}/disband`)) gone(); }), 'btn danger');
  return h('div', { class: 'mt w-ctl' }, h('h3', { text: 'Staff actions', style: { marginBottom: '6px' } }), formRow(field('Leader', leader), setLeader, unclaim, disband));
}

/** Player power and single chunk unclaims. */
function staffCard(after) {
  const who = input({ placeholder: 'Player name or UUID', 'aria-label': 'Player', maxlength: 64 });
  const power = numInput({ step: 0.5, placeholder: '12.5', 'aria-label': 'Power', class: 'short' });
  const setPower = wbutton('Set power', () => {
    if (!who.value.trim()) return who.focus();
    if (power.value === '') return power.focus();
    confirmAfter(setPower, `Set the power of ${who.value.trim()} to ${power.value}? Dominion clamps it to the limit.`, 'Set power', async () => { if (await staff('Power change', `/api/dominion/players/${enc(who.value.trim())}/power`, { power: Number(power.value) })) after(); });
  });
  const world = input({ value: 'default', 'aria-label': 'World', maxlength: 64, class: 'short' });
  const cx = numInput({ step: 1, placeholder: '3', 'aria-label': 'Chunk x', class: 'short' });
  const cz = numInput({ step: 1, placeholder: '-4', 'aria-label': 'Chunk z', class: 'short' });
  const unclaim = wbutton('Unclaim chunk', () => {
    if (cx.value === '' || cz.value === '') return cx.focus();
    confirmAfter(unclaim, `Release chunk ${cx.value}, ${cz.value} in ${world.value}?`, 'Unclaim', async () => { if (await staff('Unclaim', '/api/dominion/unclaim', { world: world.value.trim(), chunkX: Number(cx.value), chunkZ: Number(cz.value) })) after(); });
  }, 'btn danger-soft');
  return card('Staff actions', [formRow(field('Player', who), field('Power', power), setPower), formRow(field('World', world), field('Chunk x', cx), field('Chunk z', cz), unclaim)],
    { note: 'Needs dominion.write. Pick a faction tag below for leader, unclaim all and disband.', cls: 'w-ctl' });
}
