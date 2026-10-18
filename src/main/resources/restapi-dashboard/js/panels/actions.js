// Player actions shared by the player page, the players list and the moderation page.
// Nothing here is drawn unless the dashboard server was started with --allow-writes.

import { h, badge } from '../dom.js';
import { write } from '../api.js';
import { act, confirmAfter, toast, input, numInput, field, formRow, wbutton, choose, onlineNames, checkbox, writable } from '../ui.js';
import * as settings from '../settings.js';
import { isLively, gameIcon } from '../lively.js';

const enc = encodeURIComponent;
export const INVENTORY_SECTIONS = ['hotbar', 'storage', 'armor', 'utility', 'tool', 'backpack'];

/** Changes made from this page, newest last, for the moderation feed. Lives as long as the tab. */
export const localLog = [];
const logListeners = new Set();
export function onLocalLog(fn) { logListeners.add(fn); return () => logListeners.delete(fn); }
export function logLocal(kind, player, detail) {
  const e = { local: true, kind, player, detail, time: Date.now() };
  localLog.push(e);
  if (localLog.length > 200) localLog.shift();
  for (const fn of logListeners) fn(e);
}

/** Kicks, bans and the like, each one write with its toast. Returns true on success. */
export const playerWrites = {
  message: (name, text, color) => act(`Message to ${name}`, async () => {
    await write('POST', `/api/players/${enc(name)}/message`, { text, ...(color ? { color } : {}) });
    return `Sent the message to ${name}.`;
  }),
  title: (name, title, subtitle, stay) => act(`Title for ${name}`, async () => {
    await write('POST', `/api/players/${enc(name)}/title`, { title, ...(subtitle ? { subtitle } : {}), ...(stay ? { stay } : {}) });
    return `Showed the title to ${name}.`;
  }),
  kick: (name, reason) => act(`Kick of ${name}`, async () => {
    const { data } = await write('POST', `/api/players/${enc(name)}/kick`, reason ? { reason } : {});
    logLocal('kick', name, reason || 'no reason given');
    return data.connectionsClosed ? `Kicked ${name}.` : `${name} was not connected, nothing to close.`;
  }),
  ban: (name, reason, minutes) => act(`Ban of ${name}`, async () => {
    const { data } = await write('POST', '/api/bans', { player: name, ...(reason ? { reason } : {}), ...(minutes ? { minutes } : {}) });
    logLocal('ban', name, `${minutes ? `${minutes} min` : 'permanent'}${reason ? `, ${reason}` : ''}`);
    return data.created ? `Banned ${name}${minutes ? ` for ${minutes} min` : ''}.` : `${name} was already banned, the ban in place stays.`;
  }),
  unban: (name) => act(`Lifting the ban on ${name}`, async () => {
    await write('DELETE', `/api/bans/${enc(name)}`);
    logLocal('unban', name, 'ban lifted');
    return `Lifted the ban on ${name}.`;
  }),
  teleport: (name, target) => act(`Teleport of ${name}`, async () => {
    const { data } = await write('POST', `/api/players/${enc(name)}/teleport`, target);
    const p = data.position;
    return `Teleported ${name} to ${target.to ? target.to : `${Math.round(p.x)}, ${Math.round(p.y)}, ${Math.round(p.z)}`} in ${data.world}.`;
  }),
  heal: (name) => act(`Healing ${name}`, async () => { await write('POST', `/api/players/${enc(name)}/heal`); return `Healed ${name}.`; }),
  kill: (name) => act(`Kill of ${name}`, async () => { await write('POST', `/api/players/${enc(name)}/kill`); logLocal('kill', name, 'killed'); return `Killed ${name}.`; }),
  gamemode: (name, mode) => act(`Game mode of ${name}`, async () => { await write('PUT', `/api/players/${enc(name)}/gamemode`, { mode }); return `${name} is now in ${mode}.`; }),
  give: (name, item, amount) => act(`Give to ${name}`, async () => {
    const { data } = await write('POST', `/api/players/${enc(name)}/give`, { item, amount });
    return `Gave ${data.given} ${item} to ${name}${data.remainder ? `, ${data.remainder} did not fit` : ''}.`;
  }),
  clear: (name, sections) => act(`Clearing the inventory of ${name}`, async () => {
    const { data } = await write('POST', `/api/players/${enc(name)}/clear`, sections.length ? { sections } : {});
    const parts = Object.entries(data.removed || {}).map(([k, v]) => `${v} from ${k}`);
    logLocal('clear', name, sections.length ? sections.join(', ') : 'every section');
    return `Cleared ${name}, ${parts.join(', ') || 'nothing was there'}.`;
  }),
  refer: (name, host, port) => act(`Referral of ${name}`, async () => {
    await write('POST', `/api/players/${enc(name)}/refer`, { host, port });
    logLocal('refer', name, `${host}:${port}`);
    return `Sent ${name} to ${host}:${port}.`;
  }),
};

const num = (el) => (el.value === '' ? null : Number(el.value));

/**
 * The action bar of a player page. info() gives the latest player detail ({ name, online, world }),
 * after() runs when a write finished so the page refreshes the panel it touched.
 */
export function actionBar(ctx, name, info, after = () => {}) {
  if (!writable()) return null;
  const panel = h('div', { class: 'action-panel w-ctl', hidden: true });
  const bar = h('div', { class: 'action-bar w-ctl', role: 'toolbar', 'aria-label': `Actions for ${name}` });
  let openKey = null;
  const done = (ok) => { if (ok) after(); return ok; };

  const forms = {
    message: () => {
      const text = input({ placeholder: 'Message text', 'aria-label': 'Message text', maxlength: 1000, class: 'grow' });
      const color = input({ placeholder: '#55ff55', 'aria-label': 'Colour, optional', maxlength: 7, size: 8 });
      const send = wbutton('Send message', async () => { if (!text.value.trim()) return text.focus(); if (await playerWrites.message(name, text.value.trim(), color.value.trim())) text.value = ''; }, 'btn primary');
      text.addEventListener('keydown', (e) => { if (e.key === 'Enter') send.click(); });
      return formRow(field('Text', text, 'grow'), field('Colour', color), send);
    },
    title: () => {
      const t = input({ placeholder: 'Title', 'aria-label': 'Title', maxlength: 200 });
      const s = input({ placeholder: 'Subtitle, optional', 'aria-label': 'Subtitle', maxlength: 200 });
      const stay = numInput({ min: 0, max: 60, step: 0.5, value: 4, 'aria-label': 'Seconds on screen', class: 'short' });
      return formRow(field('Title', t), field('Subtitle', s), field('Seconds', stay), wbutton('Show title', async () => { if (!t.value.trim()) return t.focus(); await playerWrites.title(name, t.value.trim(), s.value.trim(), num(stay)); }, 'btn primary'));
    },
    kick: () => {
      const reason = input({ placeholder: 'Reason, optional', 'aria-label': 'Reason', maxlength: 200, class: 'grow' });
      const go = wbutton('Kick', () => confirmAfter(go, `Kick ${name}${reason.value.trim() ? ` for "${reason.value.trim()}"` : ''}?`, 'Kick', async () => done(await playerWrites.kick(name, reason.value.trim()))), 'btn danger');
      return formRow(field('Reason', reason, 'grow'), go);
    },
    ban: () => {
      const reason = input({ placeholder: 'Reason, optional', 'aria-label': 'Reason', maxlength: 200, class: 'grow' });
      const minutes = numInput({ min: 1, step: 1, placeholder: 'perm', 'aria-label': 'Minutes, empty for a permanent ban', class: 'short' });
      const go = wbutton('Ban', () => {
        const m = num(minutes);
        confirmAfter(go, `Ban ${name} ${m ? `for ${m} min` : 'permanently'}? An online player is kicked.`, 'Ban', async () => done(await playerWrites.ban(name, reason.value.trim(), m)));
      }, 'btn danger');
      return formRow(field('Reason', reason, 'grow'), field('Minutes', minutes), go);
    },
    teleport: () => {
      const x = numInput({ step: 'any', placeholder: 'x', 'aria-label': 'x', class: 'short' });
      const y = numInput({ step: 'any', placeholder: 'y', 'aria-label': 'y', class: 'short' });
      const z = numInput({ step: 'any', placeholder: 'z', 'aria-label': 'z', class: 'short' });
      const world = choose([], { placeholder: 'Same world', attrs: { 'aria-label': 'World' } });
      const to = choose([], { placeholder: 'Player name or UUID', attrs: { 'aria-label': 'Player to go to' } });
      ctx.get('/api/worlds', { quiet: true }).then((d) => d.worlds.forEach((w) => world.append(h('option', { value: w.name, text: w.name }))), () => {});
      onlineNames(ctx).then((list) => list.filter((n) => n !== name).forEach((n) => to.append(h('option', { value: n, text: n }))));
      const coords = wbutton('Teleport to coordinates', async () => {
        if ([x, y, z].some((el) => el.value === '')) return x.focus();
        done(await playerWrites.teleport(name, { x: num(x), y: num(y), z: num(z), ...(world.value ? { world: world.value } : {}) }));
      }, 'btn primary');
      const toPlayer = wbutton('Teleport to player', async () => { if (!to.value) return to.focus(); done(await playerWrites.teleport(name, { to: to.value })); }, 'btn primary');
      return h('div', null, formRow(field('x', x), field('y', y), field('z', z), field('World', world), coords), formRow(field('Or to', to), toPlayer));
    },
    bring: () => {
      const other = choose([], { placeholder: 'Player name or UUID', attrs: { 'aria-label': 'Player to bring' } });
      onlineNames(ctx).then((list) => list.filter((n) => n !== name).forEach((n) => other.append(h('option', { value: n, text: n }))));
      return formRow(field('Bring', other), wbutton(`Teleport them to ${name}`, async () => { if (!other.value) return other.focus(); done(await playerWrites.teleport(other.value, { to: name })); }, 'btn primary'));
    },
    goto: () => {
      const me = choose([], { placeholder: 'The player you play as', attrs: { 'aria-label': 'Your player' } });
      const saved = settings.get('me');
      onlineNames(ctx).then((list) => list.filter((n) => n !== name).forEach((n) => me.append(h('option', { value: n, text: n, selected: n === saved ? true : null }))));
      return formRow(field('You play as', me), wbutton(`Go to ${name}`, async () => {
        if (!me.value) return me.focus();
        settings.set({ me: me.value });
        done(await playerWrites.teleport(me.value, { to: name }));
      }, 'btn primary'), h('span', { class: 'faint' }, 'Kept with your ', h('a', { href: '#/settings', text: 'settings' }), '.'));
    },
    gamemode: () => {
      const mode = choose([['Adventure', 'Adventure'], ['Creative', 'Creative']], { value: info()?.gameMode, attrs: { 'aria-label': 'Game mode' } });
      return formRow(field('Game mode', mode), wbutton('Set game mode', async () => done(await playerWrites.gamemode(name, mode.value)), 'btn primary'));
    },
    give: () => {
      const item = input({ placeholder: 'Ingredient_Bar_Iron', 'aria-label': 'Item id', maxlength: 100, class: 'grow' });
      const amount = numInput({ min: 1, max: 10000, step: 1, value: 1, 'aria-label': 'Amount', class: 'short' });
      // The Lively view shows the item's icon next to the box once the id names a known item.
      const preview = h('span', { class: 'give-preview', 'aria-hidden': 'true' });
      if (isLively()) {
        let t = null;
        item.addEventListener('input', () => { clearTimeout(t); t = setTimeout(() => { const ic = gameIcon('items', item.value.trim(), 24); preview.replaceChildren(...(ic ? [ic] : [])); }, 250); });
      }
      return formRow(field('Item id', item, 'grow'), isLively() ? preview : null, field('Amount', amount), wbutton('Give', async () => { if (!item.value.trim()) return item.focus(); done(await playerWrites.give(name, item.value.trim(), num(amount) || 1)); }, 'btn primary'));
    },
    clear: () => {
      const boxes = INVENTORY_SECTIONS.map((s) => [s, checkbox(s)]);
      const go = wbutton('Clear', () => {
        const pick = boxes.filter(([, c]) => c.box.checked).map(([s]) => s);
        confirmAfter(go, `Empty ${pick.length ? pick.join(', ') : 'every section'} of ${name}? Items are gone for good.`, 'Clear', async () => done(await playerWrites.clear(name, pick)));
      }, 'btn danger');
      return formRow(h('span', { class: 'field-label', text: 'Sections, none ticked clears all' }), ...boxes.map(([, c]) => c.el), go);
    },
    refer: () => {
      const host = input({ placeholder: 'lobby.example.com', 'aria-label': 'Host', maxlength: 253, class: 'grow' });
      const port = numInput({ min: 1, max: 65535, step: 1, value: 5520, 'aria-label': 'Port', class: 'short' });
      const go = wbutton('Refer', () => {
        if (!host.value.trim()) return host.focus();
        confirmAfter(go, `Send ${name} to ${host.value.trim()}:${port.value}? They leave this server.`, 'Refer', async () => done(await playerWrites.refer(name, host.value.trim(), num(port))));
      }, 'btn danger');
      return formRow(field('Host', host, 'grow'), field('Port', port), go);
    },
  };

  const buttons = [
    ['message', 'Message'], ['title', 'Title'], ['teleport', 'Teleport'], ['bring', 'Bring here'], ['goto', 'Go to'],
    ['heal', 'Heal'], ['gamemode', 'Game mode'], ['give', 'Give'], ['kick', 'Kick'], ['ban', 'Ban'], ['kill', 'Kill'], ['clear', 'Clear'], ['refer', 'Refer'],
  ];
  const offlineOk = new Set(['ban']);
  const danger = new Set(['kick', 'ban', 'kill', 'clear', 'refer']);

  const render = () => {
    const online = !!info()?.online;
    bar.replaceChildren(...buttons.filter(([k]) => online || offlineOk.has(k)).map(([k, l]) => {
      const b = h('button', { type: 'button', class: `btn${danger.has(k) ? ' danger-soft' : ''}${openKey === k ? ' on' : ''}`, text: l, 'aria-expanded': openKey === k ? 'true' : 'false', dataset: { action: k } });
      b.addEventListener('click', () => {
        if (k === 'heal') { playerWrites.heal(name).then(done); return; }
        if (k === 'kill') { confirmAfter(b, `Kill ${name}? They drop what the death rules say and respawn.`, 'Kill', async () => done(await playerWrites.kill(name))); return; }
        openKey = openKey === k ? null : k;
        panel.hidden = !openKey;
        panel.replaceChildren(...(openKey ? [forms[openKey]()] : []));
        render();
        const first = panel.querySelector('input, select');
        if (first) first.focus();
      });
      return b;
    }));
    if (!online) bar.append(h('span', { class: 'faint', text: 'Offline, only a ban applies.' }));
    if (openKey && !online && !offlineOk.has(openKey)) { openKey = null; panel.hidden = true; panel.replaceChildren(); }
  };
  render();
  return { el: h('div', { class: 'actions-wrap' }, bar, panel), refresh: render };
}

/**
 * The groups editor for any player name. Adds and removes against /api/players/{p}/groups.
 * Every group change is dangerous on the API, so each one asks first.
 */
export function groupsEditor(ctx, name, after = () => {}) {
  const box = h('div', { class: 'groups-editor' }, h('div', { class: 'loading', text: 'Loading groups' }));
  const load = async () => {
    let mine, all;
    try {
      [mine, all] = await Promise.all([ctx.get(`/api/players/${enc(name)}/groups`), ctx.get('/api/permissions/groups')]);
    } catch (e) {
      box.replaceChildren(h('div', { class: 'err' }, e.status === 404 ? `No player called ${name} has joined this server.` : e.message));
      return;
    }
    const prot = new Set(all.groups.filter((g) => g.protected).map((g) => g.name));
    const list = h('div', { class: 'tag-list' }, mine.groups.length ? mine.groups.map((g) => {
      const chip = h('span', { class: 'chip-edit' }, h('code', { text: g }));
      if (writable()) {
        if (prot.has(g)) chip.append(h('span', { class: 'faint', title: 'In players.protectedGroups, the API may not change it', text: 'protected' }));
        else {
          const x = wbutton('Remove', () => confirmAfter(x, `Take ${name} out of ${g}?`, 'Remove', async () => {
            const ok = await act(`Removing ${g}`, async () => { await write('DELETE', `/api/players/${enc(name)}/groups/${enc(g)}`); logLocal('group', name, `removed from ${g}`); return `${name} left ${g}.`; });
            if (ok) { await load(); after(); }
          }), 'btn small danger-soft');
          chip.append(x);
        }
      }
      return chip;
    }) : h('span', { class: 'faint', text: 'In no group.' }));
    const out = [h('div', { class: 'muted', style: { marginBottom: '6px' } }, h('strong', { text: mine.name || name }), ` is in ${mine.groups.length} ${mine.groups.length === 1 ? 'group' : 'groups'}.`), list];
    if (writable()) {
      const rest = all.groups.map((g) => g.name).filter((g) => !mine.groups.includes(g));
      const pick = choose(rest.map((g) => [g, prot.has(g) ? `${g} (protected)` : g]), { placeholder: 'Add to group', attrs: { 'aria-label': 'Group to add' } });
      const add = wbutton('Add', () => {
        if (!pick.value) return pick.focus();
        const g = pick.value;
        confirmAfter(add, `Add ${name} to ${g}?`, 'Add', async () => {
          const ok = await act(`Adding ${g}`, async () => { await write('POST', `/api/players/${enc(name)}/groups/${enc(g)}`); logLocal('group', name, `added to ${g}`); return `${name} joined ${g}.`; });
          if (ok) { await load(); after(); }
        });
      }, 'btn');
      out.push(formRow(pick, add));
    }
    box.replaceChildren(...out);
  };
  load();
  return { el: box, reload: load };
}

/** A small menu under a button, placed on the page so table scrolling does not clip it. */
export function popMenu(anchor, build, ctx) {
  closePop();
  const r = anchor.getBoundingClientRect();
  const menu = h('div', { class: 'pop w-ctl', role: 'dialog' });
  document.body.append(menu);
  const width = Math.min(340, document.documentElement.clientWidth - 32);
  menu.style.width = `${width}px`;
  menu.style.top = `${r.bottom + window.scrollY + 4}px`;
  menu.style.left = `${Math.max(16, Math.min(r.right + window.scrollX - width, document.documentElement.clientWidth - width - 16))}px`;
  build(menu, closePop);
  const away = (e) => { if (!menu.contains(e.target) && e.target !== anchor) closePop(); };
  const esc = (e) => { if (e.key === 'Escape') { closePop(); if (anchor.isConnected) anchor.focus(); } };
  setTimeout(() => document.addEventListener('mousedown', away), 0);
  document.addEventListener('keydown', esc);
  current = { menu, off: () => { document.removeEventListener('mousedown', away); document.removeEventListener('keydown', esc); } };
  if (ctx) ctx.cleanup(closePop);
  return menu;
}
let current = null;
export function closePop() {
  if (!current) return;
  current.off();
  current.menu.remove();
  current = null;
}
export const popOpen = () => !!current;

/** The per row quick menu of the online players table: message, kick and teleport to another player. */
export function quickMenu(ctx, name, after) {
  const b = h('button', { type: 'button', class: 'btn small w-ctl', text: 'Actions', 'aria-haspopup': 'dialog', 'aria-label': `Actions for ${name}` });
  b.addEventListener('click', () => popMenu(b, (menu, close) => {
    const body = h('div');
    const show = (what) => {
      if (what === 'message') {
        const t = input({ placeholder: `Message to ${name}`, 'aria-label': 'Message text', maxlength: 1000 });
        const send = wbutton('Send', async () => { if (!t.value.trim()) return t.focus(); if (await playerWrites.message(name, t.value.trim())) close(); }, 'btn primary');
        t.addEventListener('keydown', (e) => { if (e.key === 'Enter') send.click(); });
        body.replaceChildren(formRow(t, send));
        t.focus();
      } else if (what === 'kick') {
        const reason = input({ placeholder: 'Reason, optional', 'aria-label': 'Reason', maxlength: 200 });
        const go = wbutton('Kick', () => confirmAfter(go, `Kick ${name}?`, 'Kick', async () => { if (await playerWrites.kick(name, reason.value.trim())) { close(); after(); } }), 'btn danger');
        body.replaceChildren(formRow(reason, go));
        reason.focus();
      } else {
        const to = choose([], { placeholder: 'Player name or UUID', attrs: { 'aria-label': 'Teleport to' } });
        onlineNames(ctx).then((list) => list.filter((n) => n !== name).forEach((n) => to.append(h('option', { value: n, text: n }))));
        body.replaceChildren(formRow(to, wbutton('Teleport', async () => { if (!to.value) return to.focus(); if (await playerWrites.teleport(name, { to: to.value })) { close(); after(); } }, 'btn primary')));
      }
    };
    menu.append(
      h('div', { class: 'pop-head' }, h('strong', { text: name }), h('a', { href: `#/players/${enc(name)}`, text: 'Open page' })),
      h('div', { class: 'actions' }, wbutton('Message', () => show('message')), wbutton('Kick', () => show('kick'), 'btn danger-soft'), wbutton('Teleport to', () => show('tp'))),
      body);
  }, ctx));
  return b;
}

/** A moderation line from an API event, or null when the event is not about moderation. */
const MOD_COMMANDS = /^(kick|ban|unban|pardon|tempban|whitelist|op|deop|perm|kill|clear|gamemode|gm|mute)\b/i;
export function moderationOf(e) {
  if (e.type === 'command' && MOD_COMMANDS.test(e.command || '')) return { tone: 'warn', text: `${e.by || 'Someone'} ran /${e.command}` };
  if (e.type === 'leave') {
    const m = /serverDisconnectReason='([^']*)'/.exec(e.reason || '');
    if (m && m[1] && m[1] !== 'null') return { tone: 'bad', text: `${e.player || 'A player'} was disconnected by the server, ${m[1]}` };
  }
  return null;
}

export { badge, toast };
