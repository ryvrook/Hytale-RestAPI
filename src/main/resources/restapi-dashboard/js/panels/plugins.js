// Plugins: every loaded plugin with state, version and dependencies, the jars not loaded, and a page per
// plugin. With --allow-writes each plugin gets load, unload and reload, all behind a confirm.
// RestAPI never gets buttons, the API answers 403 self_refused for itself whatever the key.

import { h, card, table, kv, pageHead, search, errorBox, empty, badge, dot, idBadge } from '../dom.js';
import { num } from '../format.js';
import { write } from '../api.js';
import { act, confirmAfter, writable, wbutton } from '../ui.js';
import { slot, fill, refreshButton, stateBadge } from './common.js';
import { pluginIcon, modLabel, isLively } from '../lively.js';
import { poll } from '../settings.js';

const SELF = 'Ryvrook:RestAPI';
const enc = encodeURIComponent;
const deps = (o) => Object.entries(o || {});

/** A plugin id as a link that breaks only after the colon or a dot, never inside a word. */
function idLink(id) {
  const a = h('a', { href: `#/plugins/${enc(id)}`, title: id });
  const parts = id.split(/(?<=[:.])/);
  parts.forEach((x, i) => { a.append(x); if (i < parts.length - 1) a.append(document.createElement('wbr')); });
  return a;
}

function depList(o) {
  const e = deps(o);
  if (!e.length) return h('span', { class: 'faint', text: 'none' });
  return h('span', { class: 'tag-list' }, e.map(([id, v]) => idBadge(v === '*' ? id : `${id} ${v}`, `${id} ${v}`)));
}

/** What a reload or unload means for RestAPI's own routes, from /api/suite. */
function suiteNote(id, suite) {
  const m = Object.values(suite || {}).find((x) => x.plugin === id);
  if (!m) return '';
  if (id === 'Ryvrook:TrackerAPI' || id === 'Ryvrook:Coinage') return ` RestAPI lists it as an optional dependency, so RestAPI reloads too and the API is gone for a moment.`;
  return ` RestAPI rebinds its /api/${m.id} routes once it is back.`;
}

const SELF_TEXT = 'No buttons. RestAPI refuses to load, unload or reload itself (403 self_refused). Run /plugin reload Ryvrook:RestAPI on the server console.';

/** Load, unload and reload buttons for one plugin. after() refreshes the table that showed them. */
export function pluginButtons(ctx, p, loaded, after) {
  if (!writable()) return null;
  if (p.id === SELF) return h('span', { class: 'faint self-note', title: SELF_TEXT, text: 'none, see the note' });
  const run = (action, btn) => ctx.suite().catch(() => ({})).then((suite) => {
    const what = { load: `Load ${p.id}?`, unload: `Unload ${p.id}? Plugins that depend on it unload too.`, reload: `Reload ${p.id}?` }[action];
    confirmAfter(btn, `${what}${action === 'load' ? '' : suiteNote(p.id, suite)}`, action[0].toUpperCase() + action.slice(1), async () => {
      const ok = await act(`${action[0].toUpperCase() + action.slice(1)} of ${p.id}`, async () => {
        const { status, data } = await write('POST', `/api/plugins/${enc(p.id)}/${action}`, action === 'reload' ? undefined : {}, { idempotency: true, timeout: 70000 });
        const more = data.unloaded && data.unloaded.length > 1 ? `, with ${data.unloaded.slice(1).join(', ')}` : '';
        return status === 202 ? `${p.id} is still starting.` : `${p.id} is ${String(data.state).toLowerCase()}${more}, took ${num(data.millis)} ms.`;
      });
      if (ok) after();
    });
  });
  const wrap = h('span', { class: 'actions row-actions w-ctl' });
  if (loaded) {
    const reload = wbutton('Reload', () => run('reload', reload), 'btn small');
    const unload = wbutton('Unload', () => run('unload', unload), 'btn small danger-soft');
    wrap.append(reload, unload);
  } else {
    const load = wbutton('Load', () => run('load', load), 'btn small');
    wrap.append(load);
  }
  return wrap;
}

export default {
  mount(root, ctx, rest) {
    if (rest[0]) return detail(root, ctx, rest.join('/'));
    const pluginsSlot = slot();
    const availSlot = slot();
    let pFilter = '';
    let plugins = [];
    const pState = { sortKey: 'id', sortDir: 'asc' };
    const pNote = h('span');

    const renderPlugins = () => {
      const rows = plugins.filter((p) => !pFilter || `${p.id} ${p.name} ${p.version} ${p.state} ${(p.authors || []).join(' ')}`.toLowerCase().includes(pFilter));
      const states = {};
      for (const p of plugins) states[p.state] = (states[p.state] || 0) + 1;
      pNote.textContent = `${num(plugins.length)} plugins, ${Object.entries(states).map(([s, n]) => `${num(n)} ${s.toLowerCase()}`).join(', ')}`;
      pluginsSlot.replaceChildren(table([
        { key: 'id', label: 'Id', render: (p) => modLabel(pluginIcon(p.id, 20), idLink(p.id)) },
        { key: 'version', label: 'Version', cls: 'nowrap' },
        { key: 'state', label: 'State', cls: 'nowrap', render: (p) => (isLively() ? h('span', null, dot(String(p.state).toUpperCase() === 'ENABLED' ? 'ok' : String(p.state).toUpperCase() === 'FAILED' ? 'bad' : 'warn'), stateBadge(p.state)) : stateBadge(p.state)) },
        { key: 'dependencies', label: 'Dependencies', cls: 'wrap', sort: (p) => deps(p.dependencies).length, render: (p) => depList(p.dependencies) },
        { key: 'optionalDependencies', label: 'Optional', cls: 'wrap', sort: (p) => deps(p.optionalDependencies).length, render: (p) => depList(p.optionalDependencies) },
        ...(writable() ? [{ key: 'act', label: 'Actions', sortable: false, cls: 'nowrap', render: (p) => pluginButtons(ctx, p, true, () => { pTask.now(); aTask.now(); }) }] : []),
      ], rows, { state: pState, tall: true, emptyText: 'No plugin matches the filter.' }));
    };

    root.append(
      pageHead('Plugins', writable() ? 'Plugins with their state and dependencies. Load, unload and reload ask first.' : 'Plugins with their state and dependencies, read every minute or on refresh.'),
      writable() ? h('div', { class: 'notice self' }, h('strong', { text: 'RestAPI has no buttons here. ' }), 'It refuses to load, unload or reload itself through the API (403 self_refused), since that would cut off the request that asked. Use /plugin reload Ryvrook:RestAPI on the server console.') : '',
      card('Loaded plugins', pluginsSlot, { flush: true, note: pNote, actions: [search('Filter plugins', '', (v) => { pFilter = v; renderPlugins(); }), refreshButton(() => pTask.now())] }),
      h('div', { class: 'mt' }, card('Jars found but not loaded', availSlot, { flush: true, note: 'Manifests the engine found in the mods folders, such as a jar added after the boot.' })));

    let pSeen = '';
    const pTask = ctx.every(poll.slow(), () => fill(pluginsSlot, async () => {
      const list = (await ctx.get('/api/plugins', { timeout: 20000 }).catch((e) => { pSeen = ''; throw e; })).plugins;
      const seen = JSON.stringify(list);
      if (seen === pSeen) return undefined;
      pSeen = seen;
      plugins = list;
      renderPlugins();
      return undefined;
    }, 'Plugins'));

    const aTask = ctx.every(poll.slow(), () => fill(availSlot, async () => {
      const d = await ctx.get('/api/plugins/available');
      return table([
        { key: 'id', label: 'Id', cls: 'wrap' },
        { key: 'version', label: 'Version', cls: 'nowrap' },
        { key: 'description', label: 'Description', cls: 'wrap' },
        { key: 'jar', label: 'Jar', cls: 'wrap', render: (p) => (p.jar ? h('code', { text: p.jar }) : '') },
        ...(writable() ? [{ key: 'act', label: 'Actions', cls: 'nowrap', render: (p) => pluginButtons(ctx, p, false, () => { pTask.now(); aTask.now(); }) }] : []),
      ], d.plugins, { emptyText: 'Every jar in the mods folders is loaded.' });
    }, 'Available plugins'));
    return undefined;
  },
};

function detail(root, ctx, id) {
  const body = slot();
  const acts = h('div');
  const head = pageHead(id, 'One plugin from /api/plugins/{id}.', null, h('a', { href: '#/plugins', text: 'Plugins' }));
  const icon = pluginIcon(id, 32);
  if (icon) { head.querySelector('h1').classList.add('mod-name'); head.querySelector('h1').prepend(icon); }
  root.append(head, card(null, [acts, body]));
  const task = ctx.every(poll.slow(), () => fill(body, async () => {
    let p;
    try { p = await ctx.get(`/api/plugins/${enc(id)}`); } catch (e) {
      if (e.status === 404) {
        let jar = null;
        try { jar = (await ctx.get('/api/plugins/available', { quiet: true })).plugins.find((x) => x.id === id); } catch { /* the plugins scope may be missing */ }
        acts.replaceChildren(jar ? h('div', { class: 'form-row' }, badge('not loaded', 'warn'), pluginButtons(ctx, jar, false, () => task.now())) : '');
        return empty(jar ? `${id} is in the mods folders but not loaded.` : `No plugin with the id ${id} is loaded.`);
      }
      return errorBox(e, 'Plugin');
    }
    const buttons = pluginButtons(ctx, p, true, () => task.now());
    acts.replaceChildren(p.id === SELF ? h('div', { class: 'notice self', text: SELF_TEXT }) : buttons ? h('div', { class: 'form-row', style: { marginBottom: '12px' } }, buttons) : '');
    return kv([
      ['Name', p.name], ['Group', p.group], ['Version', p.version], ['State', stateBadge(p.state)], ['Type', p.type],
      ['Description', p.description], ['Authors', (p.authors || []).join(', ') || null],
      ['Website', p.website ? h('a', { href: p.website, target: '_blank', rel: 'noopener noreferrer', text: p.website }) : null],
      ['Server version', p.serverVersion], ['Dependencies', depList(p.dependencies)], ['Optional', depList(p.optionalDependencies)],
      ['Jar', p.jar ? h('code', { text: p.jar }) : null], ['Data folder', p.dataFolder ? h('code', { text: p.dataFolder }) : null],
    ]);
  }, 'Plugin'));
  return undefined;
}
