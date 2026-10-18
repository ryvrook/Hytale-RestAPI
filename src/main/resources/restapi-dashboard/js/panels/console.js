// Console: the newest log lines and a live tail. With --allow-writes a command box sits under the tail,
// and each run's output is shown inline, marked with its Idempotency-Key, plus a batch box.

import { h, card, pageHead, button, select, search, errorBox, badge, kv, idBadge } from '../dom.js';
import { clock, absTime, num } from '../format.js';
import { write, newKey, explain } from '../api.js';
import { writable, wbutton, checkbox, toast } from '../ui.js';
import { consoleLines } from '../settings.js';

const LEVELS = ['FINEST', 'FINER', 'FINE', 'CONFIG', 'INFO', 'WARNING', 'SEVERE'];
/** Commands sent from this tab, newest last, for the arrow keys. Kept while the tab is open. */
const history = [];
/** Runs sent from this tab, shown in the tail in time order with the log lines. */
const runs = [];

const OUTCOME_TONE = { done: 'ok', failed: 'warn', timed_out: 'warn', denied: 'bad', error: 'bad' };
const outcomeText = (r) => ({ done: 'done', failed: 'failed', timed_out: 'timed out, may still be running', denied: 'denied by the deny list', error: 'not sent' }[r.outcome] || r.outcome);

export default {
  mount(root, ctx) {
    let level = 'INFO';
    let count = 200;
    let text = '';
    let paused = false;
    let held = [];
    let lines = [];
    let lastId = 0;
    let live = null;
    const box = h('div', { class: 'console', role: 'log', 'aria-live': 'off', tabindex: '0' });
    const status = h('span', { class: 'live', text: 'Connecting' });
    const info = h('span', { class: 'faint' });
    const pauseBtn = button('Pause', () => {
      paused = !paused;
      pauseBtn.textContent = paused ? 'Resume' : 'Pause';
      pauseBtn.classList.toggle('on', paused);
      if (!paused) { stick = true; push(held); held = []; scroll(); }
      setInfo();
    });

    const lineEl = (l) => h('div', { class: 'ln', dataset: { level: l.level } },
      h('span', { class: 't', title: absTime(l.time), text: clock(l.time) }),
      h('span', { class: `lv ${l.level}`, text: l.level }),
      h('span', { class: 'lg', title: l.logger, text: l.logger }),
      h('span', { class: 'msg', text: l.message }));

    const runEl = (r) => h('div', { class: `run ${r.outcome}`, dataset: { run: r.runId || '', key: r.key || '' } },
      h('div', { class: 'run-head' },
        h('span', { class: 't', title: absTime(r.at), text: clock(r.at) }),
        h('span', { class: 'run-cmd', text: `> ${r.command}` }),
        h('span', { class: 'run-key', title: `Idempotency-Key ${r.key}`, text: `run ${r.runId || 'not started'}, key ${String(r.key || '').slice(0, 8)}` })),
      ...(r.output || []).map((o) => h('div', { class: 'run-out', text: o })),
      (r.output || []).length ? null : h('div', { class: 'run-out faint', text: 'No output.' }),
      h('div', { class: 'run-foot' }, badge(outcomeText(r), OUTCOME_TONE[r.outcome] || ''), r.millis != null ? ` in ${num(r.millis)} ms` : '', r.replayed ? ' (replayed answer)' : '', r.note ? ` ${r.note}` : ''));

    const matches = (l) => !text || l.message.toLowerCase().includes(text) || (l.logger || '').toLowerCase().includes(text);
    const setInfo = () => {
      info.textContent = `${num(lines.length)} lines kept${text ? `, ${num(lines.filter(matches).length)} match` : ''}${held.length ? `, ${num(held.length)} waiting while paused` : ''}`;
    };
    let stick = true;
    const scroll = () => { if (!paused && stick) box.scrollTop = box.scrollHeight; };

    function redraw() {
      const items = [...lines.filter(matches).map((l) => ({ at: Date.parse(l.time), el: () => lineEl(l) })), ...runs.map((r) => ({ at: r.at, el: () => runEl(r) }))];
      items.sort((a, b) => a.at - b.at);
      box.replaceChildren(...items.map((x) => x.el()));
      setInfo();
      scroll();
    }

    function push(list) {
      const fresh = list.filter((l) => l.id > lastId);
      if (!fresh.length) return;
      for (const l of fresh) { lines.push(l); lastId = Math.max(lastId, l.id); }
      const over = lines.length - consoleLines();
      if (over > 0) { lines.splice(0, over); for (let i = 0; i < over && box.firstChild; i++) box.firstChild.remove(); }
      for (const l of fresh) if (matches(l)) box.append(lineEl(l));
      setInfo();
      scroll();
    }

    box.addEventListener('scroll', () => {
      stick = box.scrollHeight - box.scrollTop - box.clientHeight < 30;
    });

    let generation = 0;
    async function start() {
      const gen = ++generation;
      if (live) { live.close(); live = null; }
      lines = []; lastId = 0; held = [];
      box.replaceChildren(h('div', { class: 'loading', text: 'Loading' }));
      try {
        const d = await ctx.get(`/api/console?lines=${count}&level=${level}`);
        if (gen !== generation) return;
        lines = d.lines.slice();
        lastId = d.lastId || (lines.at(-1)?.id ?? 0);
        redraw();
      } catch (e) {
        if (gen !== generation) return;
        box.replaceChildren(errorBox(e, 'Console'));
      }
      live = ctx.stream(`/api/console/stream?level=${level}&lines=0`, {
        events: ['log'],
        onState: (s) => {
          status.className = `live${s === 'down' ? ' down' : ''}`;
          status.textContent = s === 'open' ? 'Live' : s === 'down' ? 'Stream down, retrying' : 'Connecting';
        },
        onEvent: (type, l) => {
          if (!l || typeof l !== 'object') return;
          if (paused) { held.push(l); if (held.length > consoleLines()) held.splice(0, held.length - consoleLines()); setInfo(); return; }
          push([l]);
        },
      });
    }

    // ----- one command -----
    function commandCard() {
      const cmd = h('input', { type: 'text', class: 'cmd-input w-ctl', placeholder: 'say Restart in ten minutes', 'aria-label': 'Console command', autocomplete: 'off', spellcheck: 'false', maxlength: 2000 });
      let pos = history.length;
      const denyBox = h('div', { class: 'deny-box', hidden: true });
      const send = wbutton('Run', async () => {
        const line = cmd.value.trim().replace(/^\//, '');
        if (!line) { cmd.focus(); return; }
        if (history.at(-1) !== line) history.push(line);
        if (history.length > 100) history.shift();
        pos = history.length;
        cmd.value = '';
        send.disabled = true;
        const key = newKey();
        const r = { command: line, key, at: Date.now() };
        try {
          const { status: st, data } = await write('POST', '/api/commands', { command: line }, { idempotency: key, allowStatus: [202, 403], timeout: 70000 });
          if (st === 403 && !data?.outcome) throw Object.assign(new Error(data?.message || 'Forbidden'), { status: 403 });
          Object.assign(r, data);
          if (r.outcome === 'denied') toast(`Denied. ${data.message || r.output?.[0] || ''}`, 'bad');
          else if (r.outcome === 'timed_out') toast(`${line} timed out after its timeout and may still be running.`, 'info');
        } catch (e) {
          Object.assign(r, { outcome: 'error', output: [], note: explain(e) });
          toast(`Command failed. ${explain(e)}`, 'bad');
        } finally {
          send.disabled = false;
        }
        runs.push(r);
        if (runs.length > 50) runs.shift();
        box.append(runEl(r));
        stick = true;
        scroll();
        cmd.focus();
      }, 'btn primary');
      cmd.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') { e.preventDefault(); send.click(); }
        else if (e.key === 'ArrowUp') { if (pos > 0) { pos--; cmd.value = history[pos]; e.preventDefault(); } }
        else if (e.key === 'ArrowDown') { if (pos < history.length) { pos++; cmd.value = history[pos] || ''; e.preventDefault(); } }
      });
      const denyBtn = wbutton('Deny list', async () => {
        denyBox.hidden = !denyBox.hidden;
        denyBtn.classList.toggle('on', !denyBox.hidden);
        if (denyBox.hidden) return;
        denyBox.replaceChildren(h('div', { class: 'loading', text: 'Loading' }));
        try {
          const d = await ctx.get('/api/commands/deny');
          denyBox.replaceChildren(kv([
            ['Always refused', h('span', { class: 'tag-list' }, (d.fixed || []).map((c) => idBadge(c)))],
            ['Refused by config', (d.configured || []).length ? h('span', { class: 'tag-list' }, d.configured.map((c) => idBadge(c))) : 'none'],
          ]));
        } catch (e) { denyBox.replaceChildren(errorBox(e, 'Deny list')); }
      });
      return h('div', { class: 'cmd-bar w-ctl' },
        h('div', { class: 'form-row' }, h('span', { class: 'prompt', text: '>' }), cmd, send, denyBtn),
        h('div', { class: 'faint cmd-hint', text: 'Runs as RestAPI with a fresh Idempotency-Key. Up and down arrows walk the history. stop and shutdown are refused, use the Server page.' }),
        denyBox);
    }

    // ----- batch -----
    function batchCard() {
      const area = h('textarea', { class: 'w-ctl', rows: 5, placeholder: 'say Restart soon\nlist\nsay Saving now', 'aria-label': 'Commands, one per line', spellcheck: 'false' });
      const stopOn = checkbox('Stop at the first line that fails', true);
      const out = h('div');
      const go = wbutton('Run batch', async () => {
        const list = area.value.split('\n').map((l) => l.trim().replace(/^\//, '')).filter(Boolean);
        if (!list.length) { area.focus(); return; }
        go.disabled = true;
        try {
          const { data, key } = await write('POST', '/api/commands/batch', { commands: list, stopOnError: stopOn.box.checked }, { idempotency: true, timeout: 120000 });
          out.replaceChildren(h('div', { class: 'faint', style: { margin: '8px 0' }, text: `Key ${key}. ${data.results.length} of ${list.length} lines ran${data.stopped ? ', stopped at the first failure' : ''}.` }),
            h('ol', { class: 'batch-results' }, list.map((line, i) => {
              const r = data.results[i];
              return h('li', null,
                h('div', null, h('code', { text: line }), ' ', r ? badge(outcomeText(r), OUTCOME_TONE[r.outcome] || '') : badge('not run'), r ? h('span', { class: 'faint', text: ` ${num(r.millis)} ms` }) : null),
                r && r.output.length ? h('pre', { class: 'run-pre', text: r.output.join('\n') }) : null);
            })));
          toast(`Batch ran ${data.results.length} of ${list.length} lines${data.stopped ? ' and stopped at a failure' : ''}.`, data.results.every((r) => r.outcome === 'done') ? 'ok' : 'info');
        } catch (e) {
          out.replaceChildren(errorBox(e, 'Batch'));
          toast(`Batch failed. ${explain(e)}`, 'bad');
        } finally { go.disabled = false; }
      }, 'btn primary');
      return card('Batch', [area, h('div', { class: 'form-row' }, stopOn.el, go), out], { note: 'One command per line, run one after the other. One Idempotency-Key covers the batch.' });
    }

    root.append(
      pageHead('Console', writable() ? 'The server log RestAPI keeps in memory, then every new line as it is written. Commands run from the box under the tail.' : 'The server log RestAPI keeps in memory, then every new line as it is written. Read only.', [status, pauseBtn]),
      card(null, h('div', { class: 'toolbar', style: { justifyContent: 'space-between' } },
        h('div', { class: 'toolbar' },
          h('label', { class: 'muted' }, 'Lowest level '),
          select(LEVELS.map((l) => [l, l]), level, (v) => { level = v; start(); }, { 'aria-label': 'Lowest level' }),
          select([[100, '100 lines'], [200, '200 lines'], [500, '500 lines'], [1000, '1000 lines']].map(([v, t]) => [String(v), t]), String(count), (v) => { count = Number(v); start(); }, { 'aria-label': 'Lines' }),
          search('Filter text or logger', '', (v) => { text = v; redraw(); })),
        info)),
      h('div', { class: 'mt' }, card(null, [box, writable() ? commandCard() : null], { flush: true })),
      writable() ? h('div', { class: 'mt' }, batchCard()) : '');
    start();
  },
};
