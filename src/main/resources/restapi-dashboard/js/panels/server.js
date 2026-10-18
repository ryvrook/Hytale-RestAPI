// Server: config editor, save all worlds, backups with job polling, stop or restart with a countdown,
// the command deny list and the command catalogue. Read only views stay when writes are off.

import { codeWrap, h, card, table, kv, badge, pageHead, time, search, idBadge } from '../dom.js';
import { num, yesNo, duration } from '../format.js';
import { write } from '../api.js';
import { act, confirmAfter, writable, input, numInput, field, formRow, wbutton, checkbox, toast } from '../ui.js';
import { slot, fill, refreshButton } from './common.js';
import { poll } from '../settings.js';

export default {
  mount(root, ctx) {
    const config = slot();
    const stop = slot();
    const backup = slot();
    const save = h('div');
    const deny = slot();
    const cmds = slot();
    const cNote = h('span');
    let cFilter = '';
    let commands = [];

    root.append(
      pageHead('Server', writable() ? 'Configuration, saving, backups and stopping. Changes ask first and land in config.json.' : 'Configuration, backups, the stop state and the commands. Read only.',
        refreshButton(() => Promise.all([configTask.now(), stopTask.now()]))),
      h('div', { class: 'cols even' },
        // Read only, the config card is short, so the deny list goes to the other stack to keep both columns level.
        h('div', { class: 'stack' }, card('Server config', config), writable() ? card('Commands the API refuses', deny) : null),
        h('div', { class: 'stack' }, card('Stop or restart', stop), card('Backups', backup), writable() ? card('Save worlds', save) : card('Commands the API refuses', deny))),
      h('div', { class: 'mt' }, card('Command catalogue', cmds, { flush: true, note: cNote, actions: [search('Search commands', '', (v) => { cFilter = v; renderCommands(); }), refreshButton(() => cmdTask.now())] })));

    // ----- config -----
    let editing = false;
    const configTask = ctx.every(poll.slow(), () => fill(config, async () => {
      if (editing) return undefined;
      const c = await ctx.get('/api/server/config');
      const levels = Object.entries(c.logLevels || {});
      const fixed = kv([
        ['Password', c.passwordSet ? 'set' : 'none'],
        ['Default world', c.defaultWorld], ['Default game mode', c.defaultGameMode],
        ['View radius limit', `${num(c.maxViewRadiusLimit)} chunks`],
        ['Log levels', levels.length ? h('span', { class: 'tag-list' }, levels.map(([k, v]) => idBadge(`${k} ${v}`))) : 'defaults'],
      ]);
      if (!writable()) {
        return [kv([
          ['Name', c.name], ['MOTD', c.motd || null], ['Max players', num(c.maxPlayers)],
          ['View radius', `${num(c.maxViewRadius)} chunks`], ['Join permission needed', yesNo(c.requireJoinPermission)],
        ]), h('div', { class: 'mt' }, fixed)];
      }
      const name = input({ value: c.name, maxlength: 64, 'aria-label': 'Server name' });
      const motd = input({ value: c.motd || '', maxlength: 256, 'aria-label': 'MOTD' });
      const maxPlayers = numInput({ value: c.maxPlayers, min: 1, max: 1000, step: 1, 'aria-label': 'Max players', class: 'short' });
      const radius = numInput({ value: c.maxViewRadius, min: 2, max: c.maxViewRadiusLimit, step: 1, 'aria-label': 'View radius', class: 'short' });
      const join = checkbox('Joining needs the join permission', c.requireJoinPermission);
      const watch = () => { editing = true; };
      for (const el of [name, motd, maxPlayers, radius, join.box]) el.addEventListener('input', watch);
      const saveBtn = wbutton('Save changes', () => {
        const next = {};
        if (name.value.trim() !== c.name) next.name = name.value.trim();
        if (motd.value !== (c.motd || '')) next.motd = motd.value;
        if (Number(maxPlayers.value) !== c.maxPlayers) next.maxPlayers = Number(maxPlayers.value);
        if (Number(radius.value) !== c.maxViewRadius) next.maxViewRadius = Number(radius.value);
        if (join.box.checked !== c.requireJoinPermission) next.requireJoinPermission = join.box.checked;
        const keys = Object.keys(next);
        if (!keys.length) { toast('Nothing changed, the config stays as it is.', 'info'); return; }
        confirmAfter(saveBtn, `Write ${keys.join(', ')} to config.json?`, 'Save', async () => {
          if (await act('Config change', async () => { await write('PATCH', '/api/server/config', next); return `Saved ${keys.join(', ')} to config.json.`; })) { editing = false; configTask.now(); }
        });
      }, 'btn primary');
      const reset = wbutton('Undo edits', () => { editing = false; configTask.now(); });
      return [
        h('div', { class: 'form-grid w-ctl' }, field('Name', name), field('MOTD', motd), field('Max players', maxPlayers), field('View radius, chunks', radius), join.el),
        formRow(saveBtn, reset),
        h('div', { class: 'mt' }, fixed),
      ];
    }, 'Server config'));

    // ----- stop -----
    let pending = false;
    let shown = null;
    let countEl = null;
    // The card is drawn again only when the stop changes, so a form being filled in or an open confirm stays.
    const stopTask = ctx.every(poll.fast(), () => fill(stop, async () => {
      const s = await ctx.get('/api/server/stop');
      pending = !!s.pending;
      const key = s.pending ? s.stop : 'idle';
      if (key === shown) { if (countEl && s.pending) countEl.textContent = duration(s.secondsLeft); return undefined; }
      shown = key;
      const out = [];
      if (s.pending) {
        countEl = h('div', { class: 'countdown', text: duration(s.secondsLeft) });
        out.push(countEl,
          kv([['Stop id', h('code', { text: s.stop })], ['Restart after', yesNo(s.restart)], ['Exit code', s.exitCode], ['Reason', s.reason || undefined]]));
        if (writable()) {
          const cancel = wbutton('Cancel the countdown', () => confirmAfter(cancel, 'Call off the stop and tell the players?', 'Call off', async () => {
            if (await act('Cancel', async () => { await write('POST', '/api/server/stop/cancel'); return 'The countdown was called off.'; })) { shown = null; stopTask.now(); }
          }), 'btn');
          out.push(formRow(cancel));
        }
        return out;
      }
      out.push(h('p', { class: 'muted', style: { margin: '0 0 10px' }, text: 'No stop is counting down.' }));
      if (writable()) {
        const delay = numInput({ value: 60, min: 0, max: 86400, step: 1, 'aria-label': 'Seconds before the stop', class: 'short' });
        const reason = input({ placeholder: 'Reason, shown to players', maxlength: 200, 'aria-label': 'Reason', class: 'grow' });
        const announce = checkbox('Announce in chat', true);
        const schedule = (restart) => (btn) => confirmAfter(btn, `${restart ? 'Restart' : 'Stop'} the server in ${duration(Number(delay.value) || 0)}? ${restart ? 'It exits with code 8 for the service to start it again.' : 'It stays down until someone starts it.'}`, restart ? 'Restart' : 'Stop', async () => {
          if (await act(restart ? 'Restart' : 'Stop', async () => {
            const { data } = await write('POST', '/api/server/stop', { confirm: 'stop', delaySeconds: Number(delay.value) || 0, restart, announce: announce.box.checked, ...(reason.value.trim() ? { reason: reason.value.trim() } : {}) }, { idempotency: true });
            return `${restart ? 'Restart' : 'Stop'} ${data.stop} counts down from ${duration(data.secondsLeft)}.`;
          })) { shown = null; stopTask.now(); }
        });
        const stopBtn = wbutton('Stop', () => schedule(false)(stopBtn), 'btn danger');
        const restartBtn = wbutton('Restart', () => schedule(true)(restartBtn), 'btn danger');
        out.push(h('div', { class: 'w-ctl' }, formRow(field('Seconds', delay), field('Reason', reason, 'grow')), formRow(announce.el, restartBtn, stopBtn)));
      }
      return out;
    }, 'Stop state'));
    // A running countdown ticks every second, otherwise every 5 seconds.
    const tick = setInterval(() => { if (pending && !document.hidden) stopTask.now(); }, 1000);
    ctx.cleanup(() => clearInterval(tick));

    // ----- backups -----
    let job = null;
    const jobBox = h('div');
    const showJob = () => {
      if (!job) { jobBox.replaceChildren(); return; }
      jobBox.replaceChildren(h('div', { class: 'job' },
        badge(job.state, job.state === 'done' ? 'ok' : job.state === 'failed' ? 'bad' : 'info'), ' ',
        h('code', { text: job.job }), ' started ', time(job.startedAt),
        job.file ? h('div', { class: 'muted' }, 'Written to ', h('code', { text: job.file })) : null,
        job.reason ? h('div', { class: 'err', text: job.reason }) : null));
    };
    const pollJob = async () => {
      while (job && job.state === 'running' && !ctx.signal.aborted) {
        await new Promise((r) => setTimeout(r, 1000));
        try { job = await ctx.get(`/api/server/backup/${encodeURIComponent(job.job)}`, { quiet: true }); } catch (e) { if (e.code === 'aborted') return; job = { ...job, state: 'failed', reason: e.message }; }
        showJob();
      }
      if (job && job.state === 'done') toast(`Backup ${job.job} finished.`, 'ok');
      if (job && job.state === 'failed') toast(`Backup ${job.job} failed. ${job.reason || ''}`, 'bad');
    };
    ctx.every(poll.slow(), () => fill(backup, async () => {
      const c = await ctx.get('/api/server/config');
      const b = c.backup || {};
      const facts = b.configured ? kv([
        ['Automatic', b.enabled ? badge('on', 'ok') : badge('off', 'warn')],
        ['Every', `${num(b.frequencyMinutes)} min`],
        ['Directory', h('code', { text: b.directory })],
        ['Kept', `${num(b.maxCount)} backups, ${num(b.archiveMaxCount)} archives`],
      ]) : h('p', { class: 'muted', style: { margin: 0 }, text: 'No backup folder is set in config.json or with --backup-dir, so a backup answers 409.' });
      if (!writable()) return facts;
      const start = wbutton('Start a backup now', () => confirmAfter(start, 'Save every world and write a backup zip now? Saving pauses for a moment.', 'Start', async () => {
        await act('Backup', async () => {
          const { data } = await write('POST', '/api/server/backup');
          job = data;
          showJob();
          pollJob();
          return `Backup ${data.job} is running.`;
        });
      }), 'btn primary');
      return [facts, formRow(start), jobBox];
    }, 'Backups'));

    // ----- save -----
    if (writable()) {
      const result = h('div');
      const all = wbutton('Save all worlds', async () => {
        await act('Save', async () => {
          const { data } = await write('POST', '/api/server/save', {});
          result.replaceChildren(table([
            { key: 'world', label: 'World' },
            { key: 'state', label: 'State', render: (r) => badge(r.state, r.state === 'saved' ? 'ok' : 'bad') },
            { key: 'millis', label: 'Took', num: true, render: (r) => `${num(r.millis)} ms` },
          ], data.worlds));
          return `Saved ${data.worlds.length} ${data.worlds.length === 1 ? 'world' : 'worlds'}.`;
        }, { buttons: [all] });
      }, 'btn primary');
      save.append(h('p', { class: 'muted', style: { margin: '0 0 10px' }, text: 'Writes the chunks, config and resources of every loaded world, as /world save does for one.' }), formRow(all), result);
    }

    // ----- deny list and catalogue -----
    ctx.every(poll.slow(), () => fill(deny, async () => {
      const d = await ctx.get('/api/commands/deny');
      return kv([
        ['Always refused', h('span', { class: 'tag-list' }, (d.fixed || []).map((c) => idBadge(c)))],
        ['Refused by config', (d.configured || []).length ? h('span', { class: 'tag-list' }, d.configured.map((c) => idBadge(c))) : 'none'],
      ]);
    }, 'Deny list'));

    const cState = { sortKey: 'name', sortDir: 'asc' };
    function renderCommands() {
      const rows = commands.filter((c) => !cFilter || `${c.name} ${(c.aliases || []).join(' ')} ${c.description || ''} ${c.permission || ''} ${(c.subcommands || []).join(' ')}`.toLowerCase().includes(cFilter));
      cNote.textContent = `${num(rows.length)} of ${num(commands.length)} commands`;
      cmds.replaceChildren(table([
        { key: 'name', label: 'Command', cls: 'nowrap', render: (c) => h('code', { text: `/${c.name}` }) },
        { key: 'aliases', label: 'Aliases', cls: 'wrap', render: (c) => (c.aliases && c.aliases.length ? c.aliases.join(', ') : '') },
        { key: 'description', label: 'Description', cls: 'wrap' },
        { key: 'permission', label: 'Permission', cls: 'wrap', render: (c) => (c.permission ? codeWrap(c.permission) : '') },
        { key: 'subcommands', label: 'Subcommands', cls: 'wrap', sort: (c) => (c.subcommands || []).length, render: (c) => (c.subcommands && c.subcommands.length ? c.subcommands.join(', ') : '') },
      ], rows, { state: cState, tall: true, emptyText: 'No command matches the search.' }));
    }
    let cSeen = '';
    const cmdTask = ctx.every(poll.slow(), () => fill(cmds, async () => {
      const list = (await ctx.get('/api/commands/list', { timeout: 20000 }).catch((e) => { cSeen = ''; throw e; })).commands;
      const seen = JSON.stringify(list);
      if (seen === cSeen) return undefined;
      cSeen = seen;
      commands = list;
      renderCommands();
      return undefined;
    }, 'Command list'));
  },
};
