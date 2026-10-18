// Feats: catalogue summary, the points board, world records and recent unlocks.

import { h, card, table, tile, time, badge, playerLink } from '../dom.js';
import { num, idText } from '../format.js';
import { slot, fill, refreshButton, suiteError } from './common.js';
import { medal } from '../lively.js';
import { poll } from '../settings.js';

const TIER_TONE = { PLATINUM: 'info', GOLD: 'warn', SILVER: '', BRONZE: '' };
const tierBadge = (t) => medal(t) || badge(t ? t[0] + t.slice(1).toLowerCase() : '-', TIER_TONE[String(t).toUpperCase()] || '');

export default {
  mount(root, ctx) {
    const sum = slot();
    const top = slot();
    const recs = slot();
    const recent = slot();
    let catalogue = new Map();
    const tasks = [];
    root.append(sum, h('div', { class: 'grid' },
      h('div', { class: 'span-6' }, card('Points board', top, { flush: true, actions: refreshButton(() => Promise.all(tasks.map((t) => t.now()))) })),
      h('div', { class: 'span-6' }, card('World records', recs, { flush: true })),
      h('div', { class: 'span-12' }, card('Recent unlocks', recent, { flush: true }))));

    tasks.push(ctx.every(poll.slow(5), () => fill(sum, async () => {
      let d;
      try { d = await ctx.get('/api/feats', { timeout: 20000 }); } catch (e) { return suiteError(e, 'Feats'); }
      catalogue = new Map(d.feats.map((f) => [f.id, f]));
      const count = (fn) => d.feats.filter(fn).length;
      const cats = new Set(d.feats.map((f) => f.category));
      const points = d.feats.reduce((a, f) => a + (f.points || 0), 0);
      return h('div', { class: 'tiles' },
        tile('Feats', num(d.count), { sub: `${num(cats.size)} categories, ${num(points)} points in all` }),
        tile('Personal', num(count((f) => f.scope === 'PERSONAL'))),
        tile('World and first', num(count((f) => f.scope !== 'PERSONAL'))),
        tile('Hidden', num(count((f) => f.hidden))),
        tile('Platinum', num(count((f) => f.tier === 'PLATINUM')), { sub: `${num(count((f) => f.tier === 'GOLD'))} gold, ${num(count((f) => f.tier === 'SILVER'))} silver, ${num(count((f) => f.tier === 'BRONZE'))} bronze` }));
    })));

    tasks.push(ctx.every(poll.slow(), () => fill(top, async () => {
      let d;
      try { d = await ctx.get('/api/feats/top'); } catch (e) { return suiteError(e, 'Feats'); }
      return table([
        { key: 'rank', label: 'Rank', num: true },
        { key: 'name', label: 'Player', render: (r) => playerLink(r.name, r.uuid) },
        { key: 'points', label: 'Points', num: true },
        { key: 'unlocked', label: 'Unlocked', num: true },
        { key: 'lastUnlockAt', label: 'Last unlock', cls: 'nowrap', render: (r) => time(r.lastUnlockAt) },
      ], d.players, { emptyText: 'Nobody has points yet.' });
    })));

    tasks.push(ctx.every(poll.slow(), () => fill(recs, async () => {
      let d;
      try { d = await ctx.get('/api/feats/records'); } catch (e) { return suiteError(e, 'Feats'); }
      const rows = [
        ...(d.milestones.length ? [{ __group: 'World milestones' }] : []), ...d.milestones,
        ...(d.firsts.length ? [{ __group: 'First to unlock' }] : []), ...d.firsts,
      ];
      return table([
        { key: 'feat', label: 'Feat', render: (r) => { const f = catalogue.get(r.feat); const m = f && medal(f.tier, ''); return m ? h('span', { class: 'medal-label' }, m.firstChild, f.name) : (f?.name || idText(r.feat)); } },
        { key: 'byName', label: 'By', render: (r) => (r.by ? playerLink(r.byName, r.by) : h('span', { class: 'faint', text: 'the server' })) },
        { key: 'at', label: 'When', cls: 'nowrap', render: (r) => time(r.at) },
      ], rows, { emptyText: 'No records yet.' });
    })));

    tasks.push(ctx.every(poll.fast(6), () => fill(recent, async () => {
      let d;
      try { d = await ctx.get('/api/feats/recent?limit=50'); } catch (e) { return suiteError(e, 'Feats'); }
      return [
        table([
          { key: 'at', label: 'When', cls: 'nowrap', render: (r) => time(r.at) },
          { key: 'name', label: 'Player', render: (r) => playerLink(r.name, r.uuid) },
          { key: 'featName', label: 'Feat', render: (r) => h('span', { title: catalogue.get(r.feat)?.description || '' }, r.featName, r.banner ? ' ' : null, r.banner ? badge('banner', 'info') : null) },
          { key: 'tier', label: 'Tier', render: (r) => tierBadge(String(r.tier).toUpperCase()) },
          { key: 'category', label: 'Category' },
          { key: 'points', label: 'Points', num: true },
        ], d.unlocks, { tall: true, emptyText: 'No unlocks yet.' }),
        h('div', { class: 'table-foot' }, `Source ${d.source}${d.exportedAt ? ', exported ' : ''}`, d.exportedAt ? time(d.exportedAt) : null),
      ];
    })));
  },
};
