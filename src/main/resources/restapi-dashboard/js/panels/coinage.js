// Coinage: ledger totals, currencies, the rich list, shared accounts, today's report, closed days and the newest moves.

import { h, card, table, tile, time, badge, playerLink, seg, kv } from '../dom.js';
import { num, day } from '../format.js';
import { chartBlock } from '../charts.js';
import { slot, fill, suiteError } from './common.js';
import { write } from '../api.js';
import { act, confirmAfter, writable, input, field, formRow, wbutton } from '../ui.js';
import { coin, withIcon } from '../lively.js';
import { poll } from '../settings.js';

export default {
  mount(root, ctx) {
    const sum = slot();
    const cur = slot();
    const top = slot();
    const accounts = slot();
    const today = slot();
    const days = slot();
    const ledger = slot();
    let by = 'balance';
    let minorText = (m) => num(m);
    let primarySymbol = null;
    const useCurrencies = (list) => {
      const p = list.find((c) => c.primary) || list[0];
      if (p) { minorText = (m) => (m == null ? '-' : `${num(m / 10 ** p.decimals, p.decimals)} ${p.plural}`); primarySymbol = p.symbol; }
    };
    // "1,840.00 Coins" as a value and its unit, so a tile breaks between them and never inside the number.
    const split = (text, opts = {}) => { const i = text.lastIndexOf(' '); return i > 0 ? [text.slice(0, i), { ...opts, unit: text.slice(i + 1) }] : [text, opts]; };
    const ensureCurrencies = async () => useCurrencies((await ctx.cached('/api/coinage/currencies', 300000)).currencies);

    // The rich list and the supply chart share one stack so the column ends level with today's report.
    root.append(sum, h('div', { class: 'cols even' },
      h('div', { class: 'stack' }, card('Rich list', top, { flush: true, actions: seg([['balance', 'Balance'], ['networth', 'Net worth']], by, (v) => { by = v; topTask.now(); }) }), card('Supply over closed days', days)),
      h('div', { class: 'stack' }, card('Today so far', today))),
    h('div', { class: 'grid mt' },
      h('div', { class: 'span-12' }, card('Currencies', cur, { flush: true })),
      h('div', { class: 'span-12' }, card('Shared accounts', accounts, { flush: true })),
      h('div', { class: 'span-12' }, card('Newest ledger moves', ledger, { flush: true }))));
    if (writable()) sum.after(h('div', { class: 'mb' }, moveCard(() => { topTask.now(); ledgerTask.now(); accTask.now(); })));

    ctx.every(poll.fast(6), () => fill(sum, async () => {
      let s;
      try { await ensureCurrencies(); s = await ctx.get('/api/coinage'); } catch (e) { return suiteError(e, 'Coinage'); }
      return h('div', { class: 'tiles' },
        tile('Version', s.version, { sub: `API ${s.apiVersion}` }),
        tile('Supply', ...split(minorText(s.supply), { sub: `primary currency ${s.primary}` })),
        tile('Burned', ...split(minorText(s.burned))),
        tile('Transactions', num(s.transactions), { sub: `last id ${num(s.lastId)}` }),
        tile('Accounts', num(s.accounts), { sub: `${num(s.players)} players, ${num(s.shared)} shared` }),
        tile('Currencies', num((s.currencies || []).length), { sub: (s.currencies || []).join(', ') }));
    }));

    ctx.every(poll.slow(5), () => fill(cur, async () => {
      let d;
      try { d = await ctx.get('/api/coinage/currencies'); } catch (e) { return suiteError(e, 'Coinage'); }
      useCurrencies(d.currencies);
      return table([
        { key: 'id', label: 'Id', render: (c) => h('span', null, h('code', { text: c.id }), c.primary ? ' ' : null, c.primary ? badge('primary', 'info') : null) },
        { key: 'plural', label: 'Name', render: (c) => `${c.singular}, ${c.plural}` },
        { key: 'symbol', label: 'Symbol', render: (c) => withIcon(coin(c.symbol), `${c.symbol} ${c.symbolAfter ? 'after' : 'before'}`) },
        { key: 'decimals', label: 'Decimals', num: true },
        { key: 'payable', label: 'Payable', render: (c) => (c.payable ? 'yes' : 'no') },
        { key: 'startingBalance', label: 'Starting balance', num: true, render: (c) => `${num(c.startingBalance / 10 ** c.decimals, c.decimals)} ${c.plural}` },
        { key: 'maxBalance', label: 'Largest balance', num: true, render: (c) => `${num(c.maxBalance / 10 ** c.decimals, 0)} ${c.plural}` },
        { key: 'rate', label: 'Rate', num: true, render: (c) => (c.primary ? 'primary' : c.rate ? `${num(c.rate / 10 ** c.decimals, c.decimals)} ${c.plural} per primary unit` : 'not exchangeable') },
      ], d.currencies);
    }));

    const topTask = ctx.every(poll.slow(), () => fill(top, async () => {
      let d;
      await ensureCurrencies().catch(() => {});
      try { d = await ctx.get(`/api/coinage/top?by=${by}&limit=10`); } catch (e) { return suiteError(e, 'Coinage'); }
      return table([
        { key: 'rank', label: 'Rank', num: true },
        { key: 'name', label: 'Player', render: (r) => playerLink(r.name, r.uuid) },
        { key: 'value', label: by === 'networth' ? 'Net worth' : 'Balance', num: true, render: (r) => withIcon(coin(primarySymbol), r.value.text) },
      ], d.rows, { emptyText: 'Nobody holds money yet.' });
    }));

    const accTask = ctx.every(poll.slow(), () => fill(accounts, async () => {
      let d;
      try { d = await ctx.get('/api/coinage/accounts?type=shared'); } catch (e) { return suiteError(e, 'Coinage'); }
      const names = await ctx.names();
      return table([
        { key: 'name', label: 'Account' },
        { key: 'handle', label: 'Handle', render: (a) => (a.handle ? h('code', { text: a.handle }) : '') },
        { key: 'owner', label: 'Owner', render: (a) => (a.owner ? playerLink(names.get(a.owner), a.owner) : h('span', { class: 'faint', text: 'server' })) },
        { key: 'status', label: 'Status', render: (a) => badge(a.status, a.status === 'active' ? 'ok' : 'warn') },
        { key: 'members', label: 'Members', num: true },
        { key: 'balances', label: 'Balance', num: true, sort: (a) => Object.values(a.balances)[0]?.minor, render: (a) => Object.values(a.balances).map((b) => b.text).join(', ') },
        { key: 'createdAt', label: 'Opened', cls: 'nowrap', render: (a) => time(a.createdAt) },
      ], d.accounts, { emptyText: 'No shared accounts.' });
    }));

    ctx.every(poll.slow(), () => fill(today, async () => {
      let d;
      try { await ensureCurrencies(); d = await ctx.get('/api/coinage/reports/today'); } catch (e) { return suiteError(e, 'Coinage'); }
      // The DailyReport record, an empty object while Coinage has no report for today.
      if (!d || d.supplyEnd == null) return h('div', { class: 'muted', text: 'Coinage has no report for today yet.' });
      const src = Object.entries(d.volumeBySource || {});
      const counts = d.countBySource || {};
      return [
        kv([
          ['Supply', minorText(d.supplyEnd)], ['Treasury', minorText(d.treasuryEnd)], ['Minted', minorText(d.minted)], ['Burned', minorText(d.burned)],
          ['Volume', minorText(d.volume)], ['Transactions', num(d.transactions)], ['Active accounts', num(d.accountsActive)], ['Players seen', num(d.playersSeen)],
        ]),
        src.length ? h('div', { class: 'mt' }, h('h3', { text: 'Volume by source', style: { marginBottom: '6px' } }),
          kv(src.map(([k, v]) => [k, counts[k] != null ? `${minorText(v)} in ${num(counts[k])} moves` : minorText(v)]))) : null,
      ];
    }));

    ctx.every(poll.slow(5), () => fill(days, async () => {
      let d;
      try { await ensureCurrencies(); d = await ctx.get('/api/coinage/reports/days'); } catch (e) { return suiteError(e, 'Coinage'); }
      const pts = d.days.map((x) => [Date.parse(x.day), x.supplyEnd]);
      return chartBlock([{ name: 'supply', points: pts, area: true }], { xFormat: day, format: (v) => minorText(v).replace(/ \S+$/, ''), height: 150, title: 'Supply', emptyText: 'No closed days yet.' });
    }));

    const ledgerTask = ctx.every(poll.fast(6), () => fill(ledger, async () => {
      let d;
      try { d = await ctx.get('/api/coinage/ledger?limit=25'); } catch (e) { return suiteError(e, 'Coinage'); }
      const names = await ctx.names();
      // from and to are account ids, a shared account has no player page.
      const who = (u) => (!u ? h('span', { class: 'faint', text: 'none' }) : names.has(u) ? playerLink(names.get(u), u) : h('code', { title: u, text: String(u).slice(0, 8) }));
      return table([
        { key: 'id', label: 'Id', num: true },
        { key: 'at', label: 'When', cls: 'nowrap', render: (t) => time(t.at) },
        { key: 'type', label: 'Type', render: (t) => badge(t.type, t.type === 'deposit' ? 'ok' : t.type === 'withdraw' ? 'warn' : '') },
        { key: 'from', label: 'From', render: (t) => who(t.from) },
        { key: 'to', label: 'To', render: (t) => who(t.to) },
        { key: 'amount', label: 'Amount', num: true, sort: (t) => t.amount.minor, render: (t) => t.amount.text },
        { key: 'initiator', label: 'Made by', render: (t) => (t.initiator && t.initiator.name ? `${t.initiator.name}${t.initiator.type ? ` (${t.initiator.type})` : ''}` : '') },
        { key: 'plugin', label: 'Plugin' },
        { key: 'reason', label: 'Reason', cls: 'wrap' },
      ], d.transactions, { tall: true, emptyText: 'The ledger is empty.' });
    }));
  },
};

/** Give, take and transfer through the Coinage ledger, with the key as the initiator. Needs coinage.write. */
function moveCard(after) {
  let kind = 'give';
  const from = input({ placeholder: 'Player or account handle', 'aria-label': 'From', maxlength: 64 });
  const to = input({ placeholder: 'Player or account handle', 'aria-label': 'To', maxlength: 64 });
  const amount = input({ placeholder: '12.50', 'aria-label': 'Amount', inputmode: 'decimal', maxlength: 20, class: 'short' });
  const reason = input({ placeholder: 'Reason, shown in the ledger', 'aria-label': 'Reason', maxlength: 120, class: 'grow' });
  const fromField = field('From', from);
  const toField = field('To', to);
  const show = () => { fromField.hidden = kind === 'give'; toField.hidden = kind === 'take'; };
  const kinds = h('div', { class: 'seg', role: 'group' }, [['give', 'Give'], ['take', 'Take'], ['transfer', 'Transfer']].map(([v, l]) => {
    const b = h('button', { type: 'button', class: v === kind ? 'on' : null, text: l, 'aria-pressed': v === kind ? 'true' : 'false' });
    b.addEventListener('click', () => { kind = v; for (const x of kinds.children) { x.classList.toggle('on', x === b); x.setAttribute('aria-pressed', x === b ? 'true' : 'false'); } show(); });
    return b;
  }));
  show();
  const go = wbutton('Move money', () => {
    const body = { amount: amount.value.trim(), ...(reason.value.trim() ? { reason: reason.value.trim() } : {}) };
    if (kind !== 'give') { if (!from.value.trim()) return from.focus(); body.from = from.value.trim(); }
    if (kind !== 'take') { if (!to.value.trim()) return to.focus(); body.to = to.value.trim(); }
    if (!body.amount) return amount.focus();
    const what = kind === 'give' ? `Give ${body.amount} to ${body.to}` : kind === 'take' ? `Take ${body.amount} from ${body.from}` : `Move ${body.amount} from ${body.from} to ${body.to}`;
    confirmAfter(go, `${what}? The ledger keeps it under this API key.`, 'Move', async () => {
      const ok = await act(what, async () => {
        const { data } = await write('POST', `/api/coinage/${kind}`, body, { idempotency: true });
        return `${data.message || `${what} done.`} Transaction ${data.transaction ? data.transaction.id : ''}.`.replace(' .', '.');
      });
      if (ok) { amount.value = ''; after(); }
    });
  }, 'btn primary');
  return card('Move money', [formRow(kinds, fromField, toField, field('Amount', amount), field('Reason', reason, 'grow'), go)], { note: 'Needs coinage.write. Amounts are in the currency units, such as 12.50.', cls: 'w-ctl' });
}
