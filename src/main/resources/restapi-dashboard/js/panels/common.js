// Pieces several panels share.

import { h, card, errorBox, loading, badge, button, codeWrap, keepScroll } from '../dom.js';

/** A container that shows Loading until the first fill. */
export function slot(text) {
  return h('div', { class: 'slot' }, loading(text));
}

/** Runs fn and puts what it returns in the slot, or an error box. Returns false on failure for the scheduler. */
export async function fill(el, fn, what) {
  try {
    const out = await fn();
    if (out !== undefined) keepScroll(el, () => el.replaceChildren(...[out].flat().filter(Boolean)));
    return true;
  } catch (e) {
    if (e && e.code === 'aborted') return true;
    el.replaceChildren(errorBox(e, what));
    return false;
  }
}

export function refreshButton(onClick, text = 'Refresh') {
  const b = button(text, async () => {
    b.disabled = true;
    try { await onClick(); } finally { b.disabled = false; }
  });
  return b;
}

/** The card a suite section shows when its mod is not bound. */
export function notBoundCard(mod, label) {
  const reason = mod ? (mod.reason || (mod.installed ? 'installed but not bound' : 'not installed')) : 'not listed by /api/suite';
  return card(`${label} is not bound`, [
    h('p', { style: { margin: '0 0 10px' } }, `RestAPI reports ${label} as ${reason}, so its routes are not mounted and this section has nothing to read.`),
    mod ? h('dl', { class: 'kv' },
      h('dt', { text: 'Plugin' }), h('dd', null, codeWrap(mod.plugin)),
      h('dt', { text: 'Installed' }), h('dd', { text: mod.installed ? `yes, ${mod.version}` : 'no' }),
      h('dt', { text: 'Minimum version' }), h('dd', { text: mod.minimum || '-' }),
      h('dt', { text: 'Bind mode' }), h('dd', { text: mod.mode || '-' }),
      h('dt', { text: 'Once bound' }), h('dd', { text: mod.adds || '-' })) : null,
  ], { cls: 'unbound' });
}

export function stateBadge(state) {
  const s = String(state || '').toUpperCase();
  return badge(s || 'UNKNOWN', s === 'ENABLED' ? 'ok' : s === 'FAILED' ? 'bad' : s ? 'warn' : '');
}

export function boundBadge(m) {
  if (m.bound) return badge('bound', 'ok');
  if (m.installed) return badge('not bound', 'warn');
  return badge('not installed', '');
}

/** Turns a suite route error into a plain sentence for 404 and 503 sibling_gone. */
export function suiteError(e, label) {
  if (e && (e.code === 'sibling_gone' || (e.status === 404 && (e.code === 'not_found' || e.code === 'http_404')))) {
    return h('div', { class: 'notice' }, `${label} stopped answering (${e.code}). It may have been unloaded, the section comes back when it binds again.`);
  }
  return errorBox(e, label);
}

/** The engine reports the moon phase as a number, shown as is. */
export const moonText = (n) => (n == null ? '-' : `Phase ${n}`);
