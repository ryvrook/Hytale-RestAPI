// Pieces for the write controls: the toast line, the inline confirm box, small forms and the action runner.
// Every write control carries the w-ctl class so the checks can prove none exist while writes are off.

import { h } from './dom.js';
import { caps, explain } from './api.js';
import { pageLink } from './settings.js';

// ---------- toast line ----------

let toastEl = null;
let toastTimer = null;

/** One line at the top of the page with the result of the last write. tone is ok, bad or info. */
export function toast(text, tone = 'ok') {
  if (!toastEl) {
    toastEl = h('div', { class: 'toast', role: 'status', 'aria-live': 'polite', hidden: true });
    document.body.append(toastEl);
  }
  clearTimeout(toastTimer);
  toastEl.className = `toast ${tone}`;
  toastEl.replaceChildren(
    h('span', { class: 'toast-text', text }),
    h('button', { type: 'button', class: 'toast-close', 'aria-label': 'Close', text: 'Close', onclick: () => { toastEl.hidden = true; } }));
  toastEl.hidden = false;
  toastEl.dataset.at = String(Date.now());
  toastTimer = setTimeout(() => { if (toastEl) toastEl.hidden = true; }, tone === 'bad' ? 15000 : 7000);
}

export function lastToast() {
  return toastEl && !toastEl.hidden ? { text: toastEl.querySelector('.toast-text')?.textContent || '', tone: toastEl.className.replace('toast ', '') } : null;
}

// ---------- confirm ----------

let openConfirm = null;

/**
 * A small confirm box placed right after anchor. run() is awaited when the admin says yes.
 * Returns a promise that settles with true when confirmed and run finished, false when cancelled.
 */
export function confirmAfter(anchor, text, yesLabel, run) {
  if (openConfirm) openConfirm.cancel();
  return new Promise((resolve) => {
    const yes = h('button', { type: 'button', class: 'btn danger', text: yesLabel });
    const no = h('button', { type: 'button', class: 'btn', text: 'Cancel' });
    const box = h('div', { class: 'confirm w-ctl', role: 'alertdialog', 'aria-label': text }, h('span', { class: 'confirm-text', text }), h('span', { class: 'actions' }, yes, no));
    const close = () => { box.remove(); if (openConfirm && openConfirm.box === box) openConfirm = null; };
    // Cancelling hands the focus back to the button that asked, so the keyboard does not lose its place.
    const cancel = () => { const had = box.contains(document.activeElement); close(); if (had && anchor.isConnected) anchor.focus(); resolve(false); };
    no.addEventListener('click', cancel);
    box.addEventListener('keydown', (e) => { if (e.key === 'Escape') cancel(); });
    yes.addEventListener('click', async () => {
      yes.disabled = true; no.disabled = true;
      try { await run(); } finally { close(); resolve(true); }
    });
    openConfirm = { box, cancel };
    const host = anchor.closest('.form-row, .actions, td, .action-bar') || anchor;
    host.after(box);
    yes.focus();
  });
}

// ---------- running a write ----------

/**
 * Runs fn, a write. The returned text goes to the toast. On error the API's message does.
 * Buttons passed in are disabled while it runs.
 */
export async function act(what, fn, { buttons = [], quietOk = false } = {}) {
  for (const b of buttons) if (b) b.disabled = true;
  try {
    const msg = await fn();
    if (!quietOk) toast(typeof msg === 'string' && msg ? msg : `${what} done.`, 'ok');
    return true;
  } catch (e) {
    toast(`${what} failed. ${explain(e)}`, 'bad');
    return false;
  } finally {
    for (const b of buttons) if (b) b.disabled = false;
  }
}

// ---------- form pieces ----------

export const writable = () => caps.writes === true;

export function input(attrs = {}) {
  return h('input', { type: 'text', autocomplete: 'off', spellcheck: 'false', ...attrs });
}

export function numInput(attrs = {}) {
  return h('input', { type: 'number', inputmode: 'decimal', ...attrs });
}

export function field(label, control, cls = '') {
  return h('label', { class: `field ${cls}`.trim() }, h('span', { class: 'field-label', text: label }), control);
}

export function checkbox(label, checked = false) {
  const box = h('input', { type: 'checkbox', checked: checked ? true : null });
  return { el: h('label', { class: 'check' }, box, h('span', { text: label })), box };
}

/** A row of fields and buttons that wraps on narrow screens. */
export function formRow(...children) {
  return h('div', { class: 'form-row w-ctl' }, children);
}

export function wbutton(text, onClick, cls = 'btn') {
  return h('button', { type: 'button', class: `${cls} w-ctl`, text, onclick: onClick });
}

/** A select filled with options [[value, label]], with an optional first empty choice. */
export function choose(options, { value, placeholder, attrs = {} } = {}) {
  const el = h('select', attrs, placeholder ? h('option', { value: '', text: placeholder }) : null,
    options.map(([v, l]) => h('option', { value: v, selected: v === value ? true : null, text: l })));
  return el;
}

/** The online player names, for the selects of teleport and the like. */
export async function onlineNames(ctx) {
  try {
    const d = await ctx.get('/api/players', { quiet: true });
    return d.players.map((p) => p.name).filter(Boolean).sort((a, b) => a.localeCompare(b));
  } catch { return []; }
}

// ---------- links ----------

/** Puts text on the clipboard. Answers true when it got there. */
export async function copyText(text) {
  try { await navigator.clipboard.writeText(text); return true; } catch { /* no clipboard access, try the old way */ }
  const ta = h('textarea', { readonly: true, style: { position: 'fixed', left: '-9999px', top: '0' } });
  ta.value = text;
  document.body.append(ta);
  ta.select();
  let ok = false;
  try { ok = document.execCommand('copy'); } catch { ok = false; }
  ta.remove();
  return ok;
}

/** A button that copies the absolute link of a page, on the public address when one is set. Not a write control. */
export function copyLinkButton(hash, label = 'Copy link') {
  const b = h('button', { type: 'button', class: 'btn copy-link', text: label, title: 'Copy the address of this page' });
  b.addEventListener('click', async () => {
    const url = pageLink(hash);
    b.dataset.copied = url;
    const ok = await copyText(url);
    toast(ok ? `Copied ${url}` : `Copy this link by hand, ${url}`, ok ? 'ok' : 'info');
  });
  return b;
}
