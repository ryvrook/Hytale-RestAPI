// Moderation: bans, the whitelist, permission groups with a lookup for any player, and a feed of
// kicks, bans and moderation commands. The read only views stay, the controls come with --allow-writes.

import { h, card, table, badge, pageHead, time, playerLink, empty, idBadge } from '../dom.js';
import { num } from '../format.js';
import { write } from '../api.js';
import { act, confirmAfter, writable, input, numInput, field, formRow, wbutton } from '../ui.js';
import { slot, fill, refreshButton } from './common.js';
import { playerWrites, groupsEditor, moderationOf, localLog, onLocalLog, logLocal } from './actions.js';
import { poll } from '../settings.js';

const enc = encodeURIComponent;

export default {
  mount(root, ctx) {
    const bans = slot();
    const white = slot();
    const groups = slot();
    const lookupOut = h('div');
    const feed = h('div', { class: 'feed mod-feed', role: 'log' });
    const tasks = [];

    // ----- bans -----
    let banAdd = null;
    if (writable()) {
      const who = input({ placeholder: 'Player name or UUID', 'aria-label': 'Player to ban', maxlength: 64 });
      const reason = input({ placeholder: 'Reason, optional', 'aria-label': 'Reason', maxlength: 200, class: 'grow' });
      const minutes = numInput({ min: 1, step: 1, placeholder: 'perm', 'aria-label': 'Minutes, empty for a permanent ban', class: 'short' });
      const go = wbutton('Ban', () => {
        const name = who.value.trim();
        if (!name) return who.focus();
        const m = minutes.value === '' ? null : Number(minutes.value);
        confirmAfter(go, `Ban ${name} ${m ? `for ${m} min` : 'permanently'}? An online player is kicked.`, 'Ban', async () => {
          if (await playerWrites.ban(name, reason.value.trim(), m)) { who.value = ''; reason.value = ''; minutes.value = ''; bansTask.now(); }
        });
      }, 'btn danger');
      banAdd = h('div', { class: 'card-form' }, formRow(field('Player', who), field('Reason', reason, 'grow'), field('Minutes', minutes), go));
    }

    // ----- whitelist -----
    let wlState = null;
    const wlToggle = writable() ? wbutton('Turn on', () => {
      const on = !(wlState && wlState.enabled);
      confirmAfter(wlToggle, on ? 'Require the join permission? Only listed players can join after this.' : 'Let anyone join again?', on ? 'Turn on' : 'Turn off', async () => {
        if (await act('Whitelist switch', async () => { await write('PUT', '/api/whitelist', { enabled: on }); logLocal('whitelist', null, on ? 'turned on' : 'turned off'); return on ? 'The whitelist is on.' : 'The whitelist is off.'; })) whiteTask.now();
      });
    }) : null;
    let wlAdd = null;
    if (writable()) {
      const who = input({ placeholder: 'Player name or UUID', 'aria-label': 'Player to add', maxlength: 64, class: 'grow' });
      const add = wbutton('Add', async () => {
        const name = who.value.trim();
        if (!name) return who.focus();
        if (await act(`Adding ${name}`, async () => { const { data } = await write('POST', '/api/whitelist', { player: name }); logLocal('whitelist', name, 'added'); return data.changed ? `${data.name || name} may join.` : `${data.name || name} was already listed.`; })) { who.value = ''; whiteTask.now(); }
      });
      who.addEventListener('keydown', (e) => { if (e.key === 'Enter') add.click(); });
      wlAdd = h('div', { class: 'card-form' }, formRow(field('Player', who, 'grow'), add));
    }

    // ----- group lookup -----
    const lookup = h('input', { type: 'text', placeholder: 'Player name or UUID', 'aria-label': 'Player to look up', autocomplete: 'off' });
    const openEditor = () => {
      const name = lookup.value.trim();
      if (!name) { lookup.focus(); return; }
      lookupOut.replaceChildren(groupsEditor(ctx, name).el);
    };
    lookup.addEventListener('keydown', (e) => { if (e.key === 'Enter') openEditor(); });

    root.append(
      pageHead('Moderation', writable() ? 'Bans, the whitelist and permission groups. Every change asks first and the result shows at the top.' : 'Bans, the whitelist and permission groups. Read only.',
        refreshButton(() => Promise.all(tasks.map((t) => t.now())))),
      card('Bans', [banAdd, bans], { flush: true }),
      h('div', { class: 'cols even mt' },
        h('div', { class: 'stack' },
          card('Whitelist', [wlAdd, white], { flush: true, actions: wlToggle }),
          card('Groups of a player', [h('div', { class: 'lookup' }, lookup, h('button', { type: 'button', class: 'btn', text: writable() ? 'Open editor' : 'Look up', onclick: openEditor })), lookupOut],
            { note: writable() ? 'Add or remove groups for any player who has joined.' : 'The groups of any player who has joined.' })),
        h('div', { class: 'stack' }, card('Moderation feed', feed, { flush: true, cls: 'fill-card', note: 'Kicks, bans and moderation commands from the event stream, plus changes made here.' }))),
      h('div', { class: 'mt' }, card('Permission groups', groups, { flush: true })));

    const bansTask = ctx.every(poll.slow(), () => fill(bans, async () => {
      const d = await ctx.get('/api/bans?limit=200');
      return table([
        { key: 'name', label: 'Player', render: (b) => playerLink(b.name, b.uuid) },
        { key: 'reason', label: 'Reason', cls: 'wrap' },
        { key: 'since', label: 'Since', cls: 'nowrap', sort: (b) => b.since, render: (b) => time(b.since) },
        { key: 'until', label: 'Until', cls: 'nowrap', sort: (b) => b.until || '9999', render: (b) => (b.permanent ? badge('permanent', 'bad') : time(b.until)) },
        { key: 'inEffect', label: 'State', sort: (b) => (b.inEffect ? 1 : 0), render: (b) => (b.inEffect ? badge('in effect', 'bad') : badge('expired')) },
        { key: 'by', label: 'By', render: (b) => (b.key ? h('span', null, 'key ', h('code', { text: b.key })) : b.by ? playerLink(null, b.by) : 'console') },
        ...(writable() ? [{ key: 'act', label: '', sortable: false, cls: 'nowrap', render: (b) => {
          if (!b.inEffect) return '';
          const name = b.name || b.uuid;
          const lift = wbutton('Lift', async () => { if (await playerWrites.unban(name)) bansTask.now(); }, 'btn small');
          lift.dataset.lift = name;
          return lift;
        } }] : []),
      ], d.bans, { state: { sortKey: 'since', sortDir: 'desc' }, emptyText: 'Nobody is banned.', foot: d.total > d.bans.length ? `Showing ${num(d.bans.length)} of ${num(d.total)} bans.` : null });
    }, 'Bans'));
    tasks.push(bansTask);

    const whiteTask = ctx.every(poll.slow(), () => fill(white, async () => {
      const w = await ctx.get('/api/whitelist');
      wlState = w;
      if (wlToggle) wlToggle.textContent = w.enabled ? 'Turn off' : 'Turn on';
      return [
        h('div', { style: { padding: '10px 14px' } }, w.enabled ? badge('on, joining needs the join permission', 'warn') : badge('off, anyone may join', 'ok')),
        table([
          { key: 'name', label: 'Player', render: (p) => playerLink(p.name, p.uuid) },
          { key: 'uuid', label: 'UUID', cls: 'wrap', render: (p) => h('code', { text: p.uuid }) },
          ...(writable() ? [{ key: 'act', label: '', cls: 'nowrap', render: (p) => {
            const name = p.name || p.uuid;
            const rm = wbutton('Remove', () => confirmAfter(rm, `Take the join permission from ${name}?`, 'Remove', async () => {
              if (await act(`Removing ${name}`, async () => { await write('DELETE', `/api/whitelist/${enc(name)}`); logLocal('whitelist', name, 'removed'); return `${name} no longer holds the join permission.`; })) whiteTask.now();
            }), 'btn small danger-soft');
            return rm;
          } }] : []),
        ], w.players, { emptyText: 'Nobody holds the join permission.' }),
      ];
    }, 'Whitelist'));
    tasks.push(whiteTask);

    tasks.push(ctx.every(poll.slow(), () => fill(groups, async () => {
      const d = await ctx.get('/api/permissions/groups');
      return table([
        { key: 'name', label: 'Group', render: (g) => h('code', { text: g.name }) },
        { key: 'parent', label: 'Parent', render: (g) => (g.parent ? h('code', { text: g.parent }) : '') },
        { key: 'protected', label: 'Protected', render: (g) => (g.protected ? badge('protected', 'warn') : 'no') },
        { key: 'permissions', label: 'Permissions', cls: 'wrap', sort: (g) => g.permissions.length, render: (g) => (g.permissions.length ? h('span', { class: 'tag-list' }, g.permissions.map((p) => idBadge(p))) : h('span', { class: 'faint', text: 'inherits only' })) },
      ], d.groups, { state: { sortKey: 'name', sortDir: 'asc' }, emptyText: 'No permission groups.' });
    }, 'Permission groups')));

    // ----- feed -----
    const items = [];
    const renderFeed = () => {
      const list = items.slice().sort((a, b) => b.at - a.at).slice(0, 100);
      feed.replaceChildren(...(list.length ? list.map((x) => h('div', { class: 'mod-ln' },
        h('span', { class: 'faint nowrap' }, time(x.at)),
        badge(x.label, x.tone),
        h('span', { class: 'mod-text', text: x.text }))) : [empty('No kicks, bans or moderation commands in the event ring.')]));
    };
    const fromEvent = (e) => { const m = moderationOf(e); if (m) items.push({ id: e.id, at: Date.parse(e.time), label: e.type === 'command' ? 'command' : 'disconnect', tone: m.tone, text: m.text }); };
    const fromLocal = (e) => items.push({ at: e.time, label: `${e.kind}, from here`, tone: 'info', text: `${e.player ? `${e.player}, ` : ''}${e.detail}` });
    localLog.forEach(fromLocal);
    ctx.cleanup(onLocalLog((e) => { fromLocal(e); renderFeed(); }));
    ctx.get('/api/events/recent?types=command,leave&limit=200', { quiet: true }).then((d) => d.events.forEach(fromEvent), () => {}).finally(() => {
      renderFeed();
      ctx.stream('/api/events?types=command,leave', {
        events: ['command', 'leave'],
        onEvent: (type, e) => { if (e && typeof e === 'object' && !items.some((x) => x.id === e.id)) { fromEvent(e); renderFeed(); } },
      });
    });
  },
};
