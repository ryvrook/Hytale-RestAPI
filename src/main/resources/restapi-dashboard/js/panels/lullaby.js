// Lullaby: version, mode, nights skipped and the sleep state per world.

import { h, card, table, tile, badge, meter } from '../dom.js';
import { num, pct, duration } from '../format.js';
import { slot, suiteError } from './common.js';
import { poll } from '../settings.js';

export default {
  mount(root, ctx) {
    const sum = slot();
    const rows = slot();
    root.append(sum, card('Worlds', rows, { flush: true, note: 'Refreshed every 5 seconds.' }));
    ctx.every(poll.fast(), async () => {
      let d;
      try { d = await ctx.get('/api/lullaby'); } catch (e) { sum.replaceChildren(suiteError(e, 'Lullaby')); rows.replaceChildren(h('div', { class: 'empty', text: 'No sleep state to show until Lullaby answers.' })); return false; }
      sum.replaceChildren(h('div', { class: 'tiles' },
        tile('Version', d.version, { sub: `API ${d.apiVersion}` }),
        tile('Mode', d.mode, { sub: d.mode === 'native' ? 'Uses the engine sleep system' : 'Skips the night itself' }),
        tile('Nights skipped', num(d.nightsSkipped)),
        tile('Ignored players', num(d.ignoredPlayers), { sub: d.sleepersKnown ? 'Sleepers are known' : 'Sleepers not readable' })));
      rows.replaceChildren(table([
        { key: 'world', label: 'World' },
        { key: 'enabled', label: 'Skipping', render: (w) => (w.enabled ? badge('on', 'ok') : badge('off')) },
        { key: 'progress', label: 'Progress', sort: (w) => w.progress, render: (w) => meter(w.progress, 1, { tone: 'ok', text: pct(w.progress) }) },
        { key: 'inBed', label: 'In bed', num: true },
        { key: 'ready', label: 'Ready', num: true },
        { key: 'eligible', label: 'Eligible', num: true },
        { key: 'online', label: 'Online', num: true },
        { key: 'needed', label: 'Needed', num: true, render: (w) => `${num(w.needed)} by ${w.requirement}` },
        { key: 'missing', label: 'Missing', num: true },
        { key: 'cooldownSeconds', label: 'Cooldown', num: true, render: (w) => (w.cooldownSeconds ? duration(w.cooldownSeconds) : 'none') },
        { key: 'nightsSkipped', label: 'Nights skipped', num: true },
      ], d.worlds, { emptyText: 'Lullaby watches no worlds.' }));
      return true;
    });
  },
};
