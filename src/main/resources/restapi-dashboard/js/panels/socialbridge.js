// SocialBridge: version and a lookup for account links in both directions.

import { h, card, kv, tile, errorBox, playerLink } from '../dom.js';
import { slot, fill, suiteError } from './common.js';
import { write } from '../api.js';
import { act, confirmAfter, writable, input, field, formRow, wbutton, choose } from '../ui.js';
import { poll } from '../settings.js';

export default {
  mount(root, ctx) {
    const sum = slot();
    const out = h('div', { class: 'muted', text: 'Enter a player name or UUID, or a Discord user id.' });
    const input = h('input', { type: 'text', placeholder: 'Player name, UUID or Discord user id', 'aria-label': 'Lookup', autocomplete: 'off' });

    async function look(kind) {
      const q = input.value.trim();
      if (!q) { input.focus(); return; }
      out.replaceChildren(h('div', { class: 'loading', text: 'Looking up' }));
      const path = kind === 'player' ? `/api/socialbridge/links/${encodeURIComponent(q)}` : `/api/socialbridge/users/${encodeURIComponent(q)}`;
      try {
        const d = await ctx.get(path);
        const names = await ctx.names();
        out.replaceChildren(kv([
          ['Service', d.service],
          ['Discord user id', h('code', { text: d.user })],
          ['Player', playerLink(d.name || names.get(d.player), d.player)],
          ['UUID', h('code', { text: d.player })],
        ]));
      } catch (e) {
        if (e.status === 404 && e.code === 'not_linked') {
          out.replaceChildren(h('p', { style: { margin: 0 }, text: kind === 'player' ? `${q} is not linked to a Discord account.` : `Discord user ${q} is not linked to a player.` }));
        } else if (e.status === 404) {
          out.replaceChildren(h('p', { style: { margin: 0 }, text: e.message }));
        } else {
          out.replaceChildren(errorBox(e, 'Lookup'));
        }
      }
    }

    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') look(/^\d{15,20}$/.test(input.value.trim()) ? 'user' : 'player'); });
    root.append(sum, card('Link lookup', [
      h('div', { class: 'lookup' }, input,
        h('button', { type: 'button', class: 'btn', text: 'Find by player', onclick: () => look('player') }),
        h('button', { type: 'button', class: 'btn', text: 'Find by Discord id', onclick: () => look('user') })),
      out,
    ], { note: writable() ? 'Account links in both directions.' : 'Read only. Start the dashboard with --allow-writes to post to a channel.' }), ...(writable() ? [h('div', { class: 'mt' }, postCard())] : []));

    ctx.every(poll.slow(), () => fill(sum, async () => {
      let d;
      try { d = await ctx.get('/api/socialbridge'); } catch (e) { return suiteError(e, 'SocialBridge'); }
      return h('div', { class: 'tiles' }, tile('Version', d.version), tile('API version', String(d.apiVersion)));
    }));
  },
};

/** Posts a line or an embed to a SocialBridge channel. Needs socialbridge.write. */
function postCard() {
  const channel = choose([['events', 'events'], ['chat', 'chat'], ['status', 'status']], { value: 'status', attrs: { 'aria-label': 'Channel' } });
  const text = h('textarea', { rows: 3, maxlength: 2000, placeholder: 'Restart in 10 minutes', 'aria-label': 'Text' });
  const title = input({ placeholder: 'Embed title, optional', 'aria-label': 'Title', maxlength: 256 });
  const colour = input({ placeholder: '#3BA55C', 'aria-label': 'Colour, optional', maxlength: 7, size: 8 });
  const go = wbutton('Post', () => {
    if (!text.value.trim()) return text.focus();
    confirmAfter(go, `Post this to the ${channel.value} channel?`, 'Post', async () => {
      const ok = await act('Post', async () => {
        const { data } = await write('POST', '/api/socialbridge/post', { channel: channel.value, text: text.value.trim(), ...(title.value.trim() ? { title: title.value.trim() } : {}), ...(colour.value.trim() ? { colour: colour.value.trim() } : {}) });
        return `Posted to ${data.channel}.`;
      });
      if (ok) text.value = '';
    });
  }, 'btn primary');
  return card('Post to a channel', [field('Text', text, 'block'), formRow(field('Channel', channel), field('Title', title), field('Colour', colour), go)], { note: 'Needs socialbridge.write. SocialBridge answers 409 when the channel is off.', cls: 'w-ctl' });
}
