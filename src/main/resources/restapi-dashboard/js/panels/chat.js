// Chat: chat, join, leave, death and command events, newest at the bottom, and a send box when writes are on.
// A broadcast or a whisper is not an event on the API, so what this page sends is shown as its own line.

import { h, card, pageHead, button, search, playerLink, seg } from '../dom.js';
import { clock, absTime, num, idText } from '../format.js';
import { write } from '../api.js';
import { act, writable, input, choose, onlineNames, wbutton } from '../ui.js';

const TYPES = ['chat', 'join', 'leave', 'death', 'command'];
const MAX = 600;
/** Lines this tab sent, kept while the tab is open so they survive a visit to another page. */
const sent = [];

function lineText(e) {
  switch (e.type) {
    case 'chat': return e.text != null ? e.text : `${num(e.length)} characters, text hidden by events.chatText`;
    case 'join': return `joined in ${e.world || 'a world'}`;
    case 'leave': {
      const s = /serverDisconnectReason='([^']*)'/.exec(e.reason || '');
      const c = /clientDisconnectType=(\w+)/.exec(e.reason || '');
      if (s && s[1] && s[1] !== 'null') return `left, ${s[1]}`;
      return `left${c && c[1] !== 'null' ? ` (${c[1].toLowerCase()})` : ''}`;
    }
    case 'death': {
      const by = e.killer || (e.killerNpc ? idText(e.killerNpc) : null);
      return `died in ${e.world || 'a world'}, ${e.cause || 'unknown cause'}${by ? `, killed by ${by}` : ''}`;
    }
    case 'command': return `/${e.command}`;
    default: return '';
  }
}

export default {
  mount(root, ctx) {
    let rows = [];
    let filter = '';
    let paused = false;
    let held = [];
    let stick = true;
    const types = new Set(TYPES);
    const feed = h('div', { class: 'feed chat-feed', role: 'log', 'aria-live': 'off', tabindex: '0' });
    const status = h('span', { class: 'live', text: 'Connecting' });
    const info = h('span', { class: 'faint' });
    const pauseBtn = button('Pause', () => {
      paused = !paused;
      pauseBtn.textContent = paused ? 'Resume' : 'Pause';
      pauseBtn.classList.toggle('on', paused);
      status.classList.toggle('paused', paused);
      if (!paused) { add(held); held = []; stick = true; }
      render();
    });

    const who = (e) => (e.local ? h('span', { class: 'chat-who you', text: e.whoText })
      : e.player ? playerLink(e.player, e.uuid) : h('span', { class: 'chat-who', text: e.by || 'Server' }));
    const lineEl = (e) => h('div', { class: `chat-ln t-${e.local ? 'sent' : e.type}`, dataset: { type: e.local ? 'sent' : e.type } },
      h('span', { class: 't', title: absTime(e.time), text: clock(e.time) }),
      h('span', { class: 'chat-kind', text: e.local ? e.kind : e.type }),
      h('span', { class: 'chat-body' }, who(e), ' ', h('span', { class: e.type === 'chat' || e.local ? 'chat-text' : 'chat-meta', text: e.local ? e.text : lineText(e) })));

    const matches = (e) => (e.local || types.has(e.type)) && (!filter || `${e.player || e.by || e.whoText || ''} ${e.local ? e.text : lineText(e)}`.toLowerCase().includes(filter));
    const sortKey = (e) => (typeof e.time === 'number' ? e.time : Date.parse(e.time));

    function add(list) {
      const seen = new Set(rows.map((r) => r.id).filter(Boolean));
      for (const e of list) if (!e.id || !seen.has(e.id)) rows.push(e);
      rows.sort((a, b) => sortKey(a) - sortKey(b));
      if (rows.length > MAX) rows.splice(0, rows.length - MAX);
    }
    function render() {
      const shown = rows.filter(matches);
      feed.replaceChildren(...(shown.length ? shown.map(lineEl) : [h('div', { class: 'empty', text: filter ? 'No line matches the filter.' : 'Nothing said yet.' })]));
      info.textContent = `${num(shown.length)} lines shown${held.length ? `, ${num(held.length)} waiting while paused` : ''}`;
      if (stick && !paused) feed.scrollTop = feed.scrollHeight;
    }
    feed.addEventListener('scroll', () => { stick = feed.scrollHeight - feed.scrollTop - feed.clientHeight < 30; });

    const chips = h('div', { class: 'chips', role: 'group', 'aria-label': 'Line types' }, TYPES.map((t) => {
      const c = h('button', { type: 'button', class: 'chip on', 'aria-pressed': 'true', text: t, onclick: () => {
        if (types.has(t)) types.delete(t); else types.add(t);
        c.classList.toggle('on', types.has(t));
        c.setAttribute('aria-pressed', types.has(t) ? 'true' : 'false');
        render();
      } });
      return c;
    }));

    root.append(
      pageHead('Chat', 'What players say, plus joins, leaves, deaths and commands, newest at the bottom.', [status, pauseBtn]),
      card(null, h('div', { class: 'toolbar', style: { justifyContent: 'space-between' } }, h('div', { class: 'toolbar' }, chips, search('Filter names or text', '', (v) => { filter = v; render(); })), info)),
      h('div', { class: 'mt' }, card(null, [feed, writable() ? sendBox() : h('div', { class: 'table-foot', text: 'Read only. Start the dashboard with --allow-writes to broadcast or whisper from here.' })], { flush: true })));

    function sendBox() {
      let mode = 'broadcast';
      const text = input({ placeholder: 'Message to every player', 'aria-label': 'Message', maxlength: 1000, class: 'grow' });
      const color = input({ placeholder: '#ffaa00', 'aria-label': 'Colour, optional', maxlength: 7, size: 8 });
      const target = choose([], { placeholder: 'Player name or UUID', attrs: { 'aria-label': 'Whisper to', hidden: true } });
      const fillTargets = () => onlineNames(ctx).then((list) => { const v = target.value; target.replaceChildren(h('option', { value: '', text: 'Pick a player' }), ...list.map((n) => h('option', { value: n, text: n, selected: n === v ? true : null }))); });
      const modeSeg = seg([['broadcast', 'Broadcast'], ['whisper', 'Whisper']], mode, (v) => {
        mode = v;
        target.hidden = v !== 'whisper';
        text.placeholder = v === 'whisper' ? 'Message to one player' : 'Message to every player';
        if (v === 'whisper') fillTargets();
      });
      const send = wbutton('Send', async () => {
        const msg = text.value.trim();
        if (!msg) return text.focus();
        const c = color.value.trim();
        if (mode === 'whisper' && !target.value) return target.focus();
        const ok = await act(mode === 'whisper' ? `Whisper to ${target.value}` : 'Broadcast', async () => {
          if (mode === 'whisper') {
            await write('POST', `/api/players/${encodeURIComponent(target.value)}/message`, { text: msg, ...(c ? { color: c } : {}) });
            return `Whispered to ${target.value}.`;
          }
          const { data } = await write('POST', '/api/broadcast', { text: msg, ...(c ? { color: c } : {}) });
          return `Broadcast reached ${num(data.recipients)} ${data.recipients === 1 ? 'player' : 'players'}.`;
        }, { buttons: [send] });
        if (!ok) return;
        const e = { local: true, kind: mode === 'whisper' ? 'whisper' : 'broadcast', whoText: mode === 'whisper' ? `You to ${target.value}` : 'You to everyone', text: msg, time: Date.now() };
        sent.push(e);
        if (sent.length > 100) sent.shift();
        add([e]);
        stick = true;
        render();
        text.value = '';
        text.focus();
      }, 'btn primary');
      text.addEventListener('keydown', (e) => { if (e.key === 'Enter') send.click(); });
      return h('div', { class: 'send-box w-ctl' }, modeSeg, target, text, color, send);
    }

    ctx.get(`/api/events/recent?types=${TYPES.join(',')}&limit=200`, { quiet: true })
      .catch((e) => (e.code === 'bad_limit' ? ctx.get(`/api/events/recent?types=${TYPES.join(',')}&limit=50`) : Promise.reject(e)))
      .then((d) => { add(d.events); add(sent); render(); }, (e) => { feed.replaceChildren(h('div', { class: 'err', text: `Recent events failed. ${e.message}` })); })
      .finally(() => {
        let lastId = rows.reduce((a, r) => (r.id && r.id > a ? r.id : a), 0);
        ctx.stream(`/api/events?types=${TYPES.join(',')}`, {
          events: TYPES,
          resume: (path, id) => `${path}&since=${encodeURIComponent(id || lastId || '')}`,
          onState: (s) => {
            status.className = `live${s === 'down' ? ' down' : paused ? ' paused' : ''}`;
            status.textContent = s === 'open' ? 'Live' : s === 'down' ? 'Stream down, retrying' : 'Connecting';
          },
          onEvent: (type, data) => {
            if (!data || typeof data !== 'object') return;
            if (data.id > lastId) lastId = data.id;
            if (paused) { held.push(data); info.textContent = `${num(held.length)} waiting while paused`; return; }
            add([data]);
            render();
          },
        });
      });
  },
};
