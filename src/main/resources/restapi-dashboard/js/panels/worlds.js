// Worlds: one card per world with every field of /api/worlds/{world}, the Lullaby block, and with
// --allow-writes the controls for time, weather, saving, ticking and unloading.
// Cards are made once per world and only their figures are redrawn, so a control being used keeps its state.

import { h, card, kv, badge, pageHead, errorBox, meter, empty, codeWrap } from '../dom.js';
import { num, ms, tps, yesNo, idText, duration, pct } from '../format.js';
import { write } from '../api.js';
import { act, confirmAfter, writable, input, numInput, field, formRow, wbutton, choose } from '../ui.js';
import { moonText } from './common.js';
import { isLively, skyBand, moonGlyph, atlasInfo, miniMap } from '../lively.js';
import { poll } from '../settings.js';

const enc = encodeURIComponent;

function dayBar(progress, hour) {
  const needle = h('span', { class: 'needle', style: { left: `calc(${(progress * 100).toFixed(2)}% - 1.5px)` } });
  const night = hour < 6 || hour >= 19;
  return h('div', { class: 'dial' },
    h('div', { class: 'clock', title: 'In game hour' }, `${String(hour).padStart(2, '0')}:00`),
    h('div', { class: 'daybar', role: 'meter', 'aria-label': 'Day progress', 'aria-valuenow': Math.round(progress * 100), 'aria-valuemin': 0, 'aria-valuemax': 100, title: `${num(progress * 100, 1)} % through the day` }, needle),
    h('span', { class: 'muted', text: night ? 'night' : 'day' }));
}

function lullabyBlock(l) {
  if (!l) return null;
  if (!l.enabled) return h('div', { class: 'mt' }, h('h3', { text: 'Lullaby' }), h('p', { class: 'muted', style: { margin: '6px 0 0' }, text: `Sleep skipping is off in this world. ${num(l.nightsSkipped)} nights skipped before.` }));
  return h('div', { class: 'mt' }, h('h3', { text: 'Lullaby', style: { marginBottom: '8px' } }), kv([
    ['Sleep progress', meter(l.progress, 1, { tone: 'ok', text: pct(l.progress) })],
    ['In bed', `${num(l.inBed)} of ${num(l.eligible)} eligible, ${num(l.online)} online`],
    ['Ready', num(l.ready)],
    ['Needed', `${num(l.needed)} by ${l.requirement}, ${num(l.missing)} missing`],
    ['Nights skipped', num(l.nightsSkipped)],
    ['Cooldown', l.cooldownSeconds ? duration(l.cooldownSeconds) : 'none'],
  ]));
}

function details(w, l, mapEl) {
  const facts = kv([
    ['Display name', w.displayName],
    ['Game time', w.gameTime ? w.gameTime.replace('T', ' ').slice(0, 16) : null],
    ['Moon', isLively() ? h('span', { class: 'ic-label' }, moonGlyph(w.moonPhase, 16), moonText(w.moonPhase)) : moonText(w.moonPhase)],
    ['Weather', w.weather ? idText(w.weather) : 'clear'],
    ['Time', w.timePaused ? 'paused' : 'running'],
    ['Day and night', `${duration(w.daytimeSeconds)} day, ${duration(w.nighttimeSeconds)} night`],
    ['Ticking', yesNo(w.ticking)],
    ['Paused', yesNo(w.paused)],
    ['Tick', num(w.tick)],
    ['Game mode', w.gameMode],
    ['PvP', yesNo(w.pvp)],
    ['Fall damage', yesNo(w.fallDamage)],
    ['Seed', w.seed != null ? h('code', { text: String(w.seed) }) : null],
    ['Save path', w.savePath ? codeWrap(w.savePath) : null],
    ['Weather types', w.weatherNames ? `${num(w.weatherNames.length)} known` : undefined],
  ]);
  return [
    w.stalled ? h('div', { class: 'notice bad', role: 'alert' }, h('strong', { text: 'Stalled.' }), ` No sample for ${num(w.sampledAgoMs / 1000)} s.`) : null,
    skyBand(w),
    dayBar(w.dayProgress ?? 0, w.hour ?? 0),
    h('div', { class: 'tiles' },
      h('div', { class: 'tile' }, h('div', { class: 'label', text: 'TPS' }), h('div', { class: 'value', text: tps(w.tps) }), h('div', { class: 'sub', text: `target ${num(w.targetTps)}` })),
      h('div', { class: 'tile' }, h('div', { class: 'label', text: 'Tick avg' }), h('div', { class: 'value', text: ms(w.tickAvgMs) }), h('div', { class: 'sub', text: `max ${ms(w.tickMaxMs)}` })),
      h('div', { class: 'tile' }, h('div', { class: 'label', text: 'Players' }), h('div', { class: 'value', text: num(w.players) }), h('div', { class: 'sub', text: `${num(w.chunks)} chunks` })),
      h('div', { class: 'tile' }, h('div', { class: 'label', text: 'Entities' }), h('div', { class: 'value', text: num(w.entities) }), h('div', { class: 'sub', text: `sampled ${num(w.sampledAgoMs / 1000, 1)} s ago` }))),
    mapEl ? h('div', { class: 'world-split' }, facts, mapEl) : facts,
    lullabyBlock(l),
  ];
}

/** The controls of one world. state() gives the latest detail, refresh() redraws the cards after a write. */
function controls(name, state, worldCount, refresh) {
  const box = h('div', { class: 'world-controls w-ctl' });
  const hour = numInput({ min: 0, max: 24, step: 0.5, placeholder: '6', 'aria-label': `Hour in ${name}`, class: 'short' });
  const setHour = wbutton('Set hour', async () => {
    if (hour.value === '') return hour.focus();
    if (await act(`Time in ${name}`, async () => { const { data } = await write('PUT', `/api/worlds/${enc(name)}/time`, { hour: Number(hour.value) }); return `${name} is now at ${String(data.hour).padStart(2, '0')}:00.`; })) refresh();
  });
  const weather = choose([], { placeholder: 'Weather', attrs: { 'aria-label': `Weather in ${name}` } });
  const setWeather = wbutton('Force weather', async () => {
    if (!weather.value) return weather.focus();
    if (await act(`Weather in ${name}`, async () => { await write('PUT', `/api/worlds/${enc(name)}/weather`, { weather: weather.value }); return `Forced ${idText(weather.value)} in ${name}.`; })) refresh();
  });
  const clearWeather = wbutton('Clear weather', async () => {
    if (await act(`Weather in ${name}`, async () => { await write('PUT', `/api/worlds/${enc(name)}/weather`, { weather: null }); return `${name} has its weather back from the environment.`; })) refresh();
  });
  const save = wbutton('Save', async () => {
    await act(`Save of ${name}`, async () => { const { data } = await write('POST', `/api/worlds/${enc(name)}/save`); return `Saved ${name} in ${num(data.millis)} ms.`; }, { buttons: [save] });
  });
  const pause = wbutton('Pause', () => {
    const w = state();
    const to = !w.paused;
    confirmAfter(pause, to ? `Pause ${name}? Nothing moves there until it resumes.` : `Resume ${name}?`, to ? 'Pause' : 'Resume', async () => {
      if (await act(`${to ? 'Pausing' : 'Resuming'} ${name}`, async () => { await write('PUT', `/api/worlds/${enc(name)}/ticking`, { paused: to }, { idempotency: true }); return `${name} is ${to ? 'paused' : 'running again'}.`; })) refresh();
    });
  });
  const tick = wbutton('Stop ticking', () => {
    const w = state();
    const to = !w.ticking;
    confirmAfter(tick, to ? `Start the tick of ${name} again?` : `Stop the tick of ${name}? Chunks stay loaded but nothing runs.`, to ? 'Start' : 'Stop', async () => {
      if (await act(`Ticking of ${name}`, async () => { await write('PUT', `/api/worlds/${enc(name)}/ticking`, { ticking: to }, { idempotency: true }); return `${name} ${to ? 'ticks again' : 'stopped ticking'}.`; })) refresh();
    });
  }, 'btn danger-soft');
  const unload = wbutton('Unload', () => confirmAfter(unload, `Unload ${name}? Players there move to the default world.`, 'Unload', async () => {
    if (await act(`Unload of ${name}`, async () => { await write('POST', `/api/worlds/${enc(name)}/unload`, undefined, { idempotency: true }); return `${name} is unloading.`; })) refresh();
  }), 'btn danger-soft');
  box.append(
    formRow(field('Hour', hour), setHour, field('Weather', weather), setWeather, clearWeather),
    formRow(save, pause, tick, unload));
  let filled = false;
  return {
    el: box,
    update(w) {
      if (!filled && w.weatherNames) {
        filled = true;
        weather.append(...w.weatherNames.map((n) => h('option', { value: n, text: idText(n) })));
      }
      pause.textContent = w.paused ? 'Resume' : 'Pause';
      tick.textContent = w.ticking === false ? 'Start ticking' : 'Stop ticking';
      // The API never unloads the default world or the last one.
      unload.hidden = !!w.default || worldCount() < 2;
    },
  };
}

export default {
  mount(root, ctx) {
    const list = h('div', { class: 'grid' }, h('div', { class: 'span-12 loading', text: 'Loading' }));
    const cards = new Map();
    let count = 0;
    let task;
    const refresh = () => task && task.now();

    let loadBox = null;
    if (writable()) {
      const nameIn = input({ placeholder: 'World folder name', 'aria-label': 'World to load', maxlength: 64, class: 'grow' });
      const load = wbutton('Load', () => {
        const n = nameIn.value.trim();
        if (!n) return nameIn.focus();
        confirmAfter(load, `Load the saved world ${n} from the universe folder?`, 'Load', async () => {
          if (await act(`Load of ${n}`, async () => { await write('POST', `/api/worlds/${enc(n)}/load`, undefined, { idempotency: true }); return `Loaded ${n}.`; })) { nameIn.value = ''; refresh(); }
        });
      });
      loadBox = card('Load a saved world', formRow(field('World', nameIn, 'grow'), load), { note: 'The API lists loaded worlds only, so type the folder name under universe/worlds.' });
    }
    root.append(...[pageHead('Worlds', writable() ? 'Every loaded world, refreshed every 5 seconds, with its controls.' : 'Every loaded world, refreshed every 5 seconds.'), list, loadBox ? h('div', { class: 'mt' }, loadBox) : null].filter(Boolean));

    task = ctx.every(poll.fast(), async () => {
      let ws;
      try {
        ws = await ctx.get('/api/worlds');
      } catch (e) {
        list.replaceChildren(h('div', { class: 'span-12' }, errorBox(e, 'Worlds')));
        cards.clear();
        return false;
      }
      count = ws.worlds.length;
      const suite = await ctx.suite().catch(() => ({}));
      const lullabyOn = suite.lullaby && suite.lullaby.bound;
      // The Lively view draws a mini map per world, around the players there or around 0, 0.
      const [atlas, online] = isLively() ? await Promise.all([atlasInfo(ctx), ctx.get('/api/players', { quiet: true }).then((d) => d.players, () => [])]) : [null, []];
      const [all, lullaby] = await Promise.all([
        Promise.all(ws.worlds.map((w) => ctx.get(`/api/worlds/${enc(w.name)}`).then((d) => d, (e) => ({ __error: e, ...w })))),
        lullabyOn ? Promise.all(ws.worlds.map((w) => ctx.get(`/api/lullaby/worlds/${enc(w.name)}`, { quiet: true }).catch(() => null))) : [],
      ]);
      if (!all.length) { list.replaceChildren(h('div', { class: 'span-12' }, empty('No worlds are loaded.'))); cards.clear(); return true; }
      const names = new Set(all.map((w) => w.name));
      for (const [n, c] of cards) if (!names.has(n)) { c.wrap.remove(); cards.delete(n); }
      if (!list.querySelector('.world-card')) list.replaceChildren();
      for (const w of all) {
        let c = cards.get(w.name);
        if (!c) {
          const title = h('h2');
          const body = h('div');
          const ctl = writable() ? controls(w.name, () => cards.get(w.name).w, () => count, refresh) : null;
          const sec = card(title, [body, ctl ? ctl.el : null], { cls: 'world-card' });
          c = { wrap: h('div', { class: 'span-6' }, sec), sec, title, body, ctl, w };
          cards.set(w.name, c);
          list.append(c.wrap);
        }
        c.w = w;
        const l = lullaby.find((x) => x && x.world === w.name) || null;
        c.title.replaceChildren(...[`${w.name} `, w.default ? badge('default', 'info') : null, w.default ? ' ' : null,
          w.stalled ? badge('stalled', 'bad') : w.paused ? badge('paused', 'warn') : w.ticking === false ? badge('not ticking', 'warn') : badge('ticking', 'ok')].filter(Boolean));
        c.sec.classList.toggle('alert', !!w.stalled);
        let mapEl = null;
        if (atlas && atlas.worlds.has(w.name)) {
          const here = online.filter((p) => p.world === w.name && p.position);
          const cx = here.length ? here.reduce((a, p) => a + p.position.x, 0) / here.length : 0;
          const cz = here.length ? here.reduce((a, p) => a + p.position.z, 0) / here.length : 0;
          if (!c.map) c.map = miniMap(atlas.base, { zoom: 0, size: 200, tile: 110 });
          c.map.update(w.name, cx, cz, here.length ? `Around ${here.length === 1 ? 'the player' : `${num(here.length)} players`}` : 'Around 0, 0');
          mapEl = c.map.el;
        }
        c.body.replaceChildren(...[w.__error ? errorBox(w.__error, `World ${w.name}`) : details(w, l, mapEl)].flat().filter(Boolean));
        if (c.ctl) c.ctl.update(w);
      }
      return true;
    });
  },
};
