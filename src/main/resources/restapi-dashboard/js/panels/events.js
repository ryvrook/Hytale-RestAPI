// Events: the ring from /api/events/recent and a live tail from the /api/events stream.

import { h, card, table, time, badge, pageHead, button, playerLink, keepScroll } from '../dom.js';
import { num, idText, hostOf } from '../format.js';
import { write } from '../api.js';
import { act, confirmAfter, writable, input, field, formRow, wbutton, choose, checkbox } from '../ui.js';
import { fill, slot } from './common.js';
import { poll } from '../settings.js';

export const EVENT_TYPES = ['join', 'ready', 'leave', 'chat', 'death', 'command', 'world', 'plugin', 'server'];
const TONES = { join: 'ok', ready: 'info', leave: '', chat: 'info', death: 'bad', command: '', world: 'warn', plugin: 'warn', server: 'warn' };

export function typeBadge(e) {
  return badge(e.action ? `${e.type} ${e.action}` : e.type, TONES[e.type] || '');
}

/** One line that says what happened, built from the event's own fields. */
export function eventSummary(e, { bust = false } = {}) {
  // The Who column of the event log has the bust already, so the sentence keeps a plain name unless asked.
  const who = e.player ? playerLink(e.player, e.uuid, { noBust: !bust }) : null;
  switch (e.type) {
    case 'join': return h('span', null, who, ` joined in ${e.world || 'a world'}`);
    case 'ready': return h('span', null, who, ` is ready in ${e.world}`);
    case 'leave': {
      const s = /serverDisconnectReason='([^']*)'/.exec(e.reason || '');
      if (s && s[1] && s[1] !== 'null') return h('span', null, who, ` was disconnected by the server, ${s[1]}`);
      const m = /clientDisconnectType=(\w+)/.exec(e.reason || '');
      return h('span', null, who, ' left', m && m[1] !== 'null' ? ` (${m[1].toLowerCase()})` : e.reason && !m ? ` (${e.reason})` : '');
    }
    case 'chat': return h('span', null, who, ' said ', h('q', { text: e.text != null ? e.text : `${num(e.length)} characters, text hidden` }));
    case 'death': {
      const by = e.killer || (e.killerNpc ? idText(e.killerNpc) : null);
      const subject = who || (e.npc ? idText(e.npc) : 'Something');
      const pos = e.position ? ` at ${num(e.position.x)}, ${num(e.position.y)}, ${num(e.position.z)}` : '';
      return h('span', null, subject, ` died in ${e.world || 'a world'}${pos}, cause ${e.cause || 'unknown'}${by ? `, killed by ${by}` : ''}`);
    }
    case 'command': return h('span', null, h('span', { text: e.by || 'Someone' }), ' ran ', h('code', { text: `/${e.command}` }));
    case 'world': return h('span', null, `World ${e.world} ${e.action || 'changed'}`);
    case 'plugin': return h('span', null, `${e.plugin} ${e.version || ''} went from ${e.from} to ${e.to}`);
    case 'server': return h('span', null, `Server ${e.action}`);
    default: {
      const rest = Object.entries(e).filter(([k]) => !['id', 'type', 'time', 'action'].includes(k));
      return h('code', { text: rest.map(([k, v]) => `${k}=${typeof v === 'object' ? JSON.stringify(v) : v}`).join(' ') });
    }
  }
}

function whoOf(e) {
  if (e.player) return playerLink(e.player, e.uuid);
  if (e.by) return h('span', { text: e.by });
  return null;
}

export default {
  mount(root, ctx) {
    const MAX = 500;
    let rows = [];
    let paused = false;
    let held = [];
    let liveCount = 0;
    let lastId = 0;
    const types = new Set(EVENT_TYPES);
    const status = h('span', { class: 'live', text: 'Connecting' });
    const info = h('span', { class: 'faint' });
    const listSlot = h('div');
    const pauseBtn = button('Pause', () => {
      paused = !paused;
      pauseBtn.textContent = paused ? 'Resume' : 'Pause';
      pauseBtn.classList.toggle('on', paused);
      if (!paused && held.length) { add(held); held = []; }
      render();
    });
    const chips = h('div', { class: 'chips', role: 'group', 'aria-label': 'Event types' }, EVENT_TYPES.map((t) => {
      const c = h('button', { type: 'button', class: 'chip on', 'aria-pressed': 'true', text: t, onclick: () => {
        if (types.has(t)) types.delete(t); else types.add(t);
        c.classList.toggle('on', types.has(t));
        c.setAttribute('aria-pressed', types.has(t) ? 'true' : 'false');
        render();
      } });
      return c;
    }));

    root.append(
      pageHead('Events and webhooks', 'Newest first. The ring from /api/events/recent, then every new event from the live stream. Webhooks below.', [status, pauseBtn]),
      card(null, [h('div', { class: 'toolbar', style: { justifyContent: 'space-between' } }, chips, info)], {}),
      h('div', { class: 'mt' }, card('Event log', listSlot, { flush: true })),
      h('div', { class: 'mt' }, webhooksCard(ctx)));

    function add(list) {
      for (const e of list) {
        if (e.id && e.id <= lastId && rows.some((r) => r.id === e.id)) continue;
        rows.unshift(e);
        if (e.id > lastId) lastId = e.id;
      }
      rows.sort((a, b) => b.id - a.id);
      if (rows.length > MAX) rows.length = MAX;
    }

    function render() {
      const shown = rows.filter((e) => types.has(e.type));
      info.textContent = `${num(shown.length)} shown of ${num(rows.length)}, ${num(liveCount)} arrived live${held.length ? `, ${num(held.length)} waiting while paused` : ''}`;
      keepScroll(listSlot, () => listSlot.replaceChildren(table([
        { key: 'time', label: 'When', cls: 'nowrap', render: (e) => time(e.time) },
        { key: 'type', label: 'Type', render: typeBadge },
        { key: 'who', label: 'Who', render: whoOf },
        { key: 'detail', label: 'Detail', cls: 'wrap', render: eventSummary },
      ], shown, { emptyText: 'No events of the chosen types.', tall: true })));
    }

    const first = fill(listSlot, async () => {
      // events.ring can be set as low as 50, and a limit above it is a 400 bad_limit.
      const d = await ctx.get('/api/events/recent?limit=200', { quiet: true }).catch((e) => {
        if (e.code === 'bad_limit') return ctx.get('/api/events/recent?limit=50');
        throw e;
      });
      add(d.events);
      render();
      return undefined;
    }, 'Recent events');

    first.then(() => {
      ctx.stream('/api/events', {
        events: [...EVENT_TYPES, 'gap', 'overflow'],
        resume: (path, id) => `${path}?since=${encodeURIComponent(id || lastId || '')}`,
        onState: (s) => {
          status.className = `live${s === 'down' ? ' down' : paused ? ' paused' : ''}`;
          status.textContent = s === 'open' ? 'Live' : s === 'down' ? 'Stream down, retrying' : 'Connecting';
        },
        onEvent: (type, data) => {
          if (type === 'gap' || type === 'overflow') return;
          liveCount++;
          if (paused) { held.push(data); if (held.length > MAX) held.splice(0, held.length - MAX); info.textContent = `${num(held.length)} waiting while paused`; return; }
          add([data]);
          render();
        },
      });
    });
  },
};

/** Webhooks with their delivery state, and with --allow-writes create, delete and test. */
function webhooksCard(ctx) {
  const body = slot();
  let secretNote = null;
  let form = null;
  if (writable()) {
    const url = input({ placeholder: 'https://example.com/hytale', 'aria-label': 'Webhook URL', maxlength: 2000, class: 'grow' });
    const format = choose([['json', 'JSON, signed'], ['discord', 'Discord channel']], { value: 'json', attrs: { 'aria-label': 'Format' } });
    const unsigned = checkbox('No signature');
    const boxes = EVENT_TYPES.map((t) => [t, checkbox(t)]);
    const out = h('div');
    const create = wbutton('Create webhook', async () => {
      if (!url.value.trim()) return url.focus();
      const types = boxes.filter(([, c]) => c.box.checked).map(([t]) => t);
      await act('New webhook', async () => {
        const { data } = await write('POST', '/api/webhooks', { url: url.value.trim(), format: format.value, ...(types.length ? { types } : {}), ...(unsigned.box.checked ? { secret: null } : {}) });
        out.replaceChildren(data.secret ? h('div', { class: 'notice' }, h('strong', { text: `Webhook ${data.id} is set up. ` }), 'Its signing secret shows this once, copy it now. ', h('code', { class: 'secret', text: data.secret })) : h('div', { class: 'notice', text: `Webhook ${data.id} is set up without a signature.` }));
        url.value = '';
        task.now();
        return `Created webhook ${data.id}.`;
      }, { buttons: [create] });
    }, 'btn primary');
    form = h('div', { class: 'card-form' },
      formRow(field('URL', url, 'grow'), field('Format', format), unsigned.el, create),
      formRow(h('span', { class: 'field-label', text: 'Events, none ticked sends every type' }), ...boxes.map(([, c]) => c.el)), out);
    secretNote = out;
  }
  const task = ctx.every(poll.fast(6), () => fill(body, async () => {
    const d = await ctx.get('/api/webhooks');
    return [
      d.enabled ? null : h('div', { class: 'notice', style: { margin: '10px' }, text: 'Webhooks are switched off in the settings, nothing is delivered.' }),
      table([
        { key: 'id', label: 'Id', render: (w) => h('code', { text: w.id }) },
        { key: 'url', label: 'Host', cls: 'nowrap', render: (w) => h('span', { title: w.url, text: hostOf(w.url) }) },
        { key: 'types', label: 'Events', cls: 'wrap', render: (w) => (w.types && w.types.length ? w.types.join(', ') : 'every type') },
        { key: 'format', label: 'Format' },
        { key: 'delivered', label: 'Delivered', num: true },
        { key: 'failed', label: 'Failed', num: true },
        { key: 'queued', label: 'Queued', num: true },
        { key: 'lastStatus', label: 'Last answer', render: (w) => (!w.lastStatus ? (w.lastError ? badge('no answer', 'bad') : h('span', { class: 'faint', text: 'none yet' })) : badge(`HTTP ${w.lastStatus}`, w.lastStatus < 300 ? 'ok' : 'bad')) },
        { key: 'lastAt', label: 'Last delivery', cls: 'nowrap', render: (w) => time(w.lastAt) },
        { key: 'lastError', label: 'Last error', cls: 'wrap', render: (w) => w.lastError || '' },
        ...(writable() ? [{ key: 'act', label: 'Actions', cls: 'nowrap', render: (w) => {
          const test = wbutton('Test', async () => {
            await act(`Test of ${w.id}`, async () => {
              const { data } = await write('POST', `/api/webhooks/${encodeURIComponent(w.id)}/test`);
              task.now();
              if (!data.ok) throw Object.assign(new Error(`The target answered HTTP ${data.status} in ${num(data.millis)} ms.`), { code: 'test_failed' });
              return `${w.id} answered HTTP ${data.status} in ${num(data.millis)} ms.`;
            }, { buttons: [test] });
          }, 'btn small');
          const del = wbutton('Delete', () => confirmAfter(del, `Delete webhook ${w.id} to ${hostOf(w.url)}? Queued deliveries are dropped.`, 'Delete', async () => {
            if (await act(`Delete of ${w.id}`, async () => { await write('DELETE', `/api/webhooks/${encodeURIComponent(w.id)}`); return `Deleted webhook ${w.id}.`; })) task.now();
          }), 'btn small danger-soft');
          return h('span', { class: 'actions row-actions' }, test, del);
        } }] : []),
      ], d.webhooks, { emptyText: 'No webhooks are set up.' }),
    ];
  }, 'Webhooks'));
  void secretNote;
  return card('Webhooks', [form, body], { flush: true, note: 'Each hook gets its own queue. A failed delivery is tried again a few times before it counts as failed.' });
}
