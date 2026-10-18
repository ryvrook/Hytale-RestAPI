// Suite: one card per sibling mod from /api/suite, then a page per bound mod.

import { h, card, kv, badge, pageHead, errorBox, codeWrap } from '../dom.js';
import { num } from '../format.js';
import { slot, fill, notBoundCard, boundBadge, refreshButton } from './common.js';

import tracker from './tracker.js';
import feats from './feats.js';
import chronicle from './chronicle.js';
import atlas from './atlas.js';
import lullaby from './lullaby.js';
import socialbridge from './socialbridge.js';
import coinage from './coinage.js';
import dominion from './dominion.js';
import { modIcon } from '../lively.js';
import { poll } from '../settings.js';

export const SUITE_MODS = [
  ['tracker', 'TrackerAPI'], ['feats', 'Feats'], ['chronicle', 'Chronicle Stats'], ['atlas', 'Atlas'],
  ['lullaby', 'Lullaby'], ['socialbridge', 'SocialBridge'], ['coinage', 'Coinage'], ['dominion', 'Dominion'],
];
const PAGES = { tracker, feats, chronicle, atlas, lullaby, socialbridge, coinage, dominion };
const LABEL = Object.fromEntries(SUITE_MODS);

export default {
  mount(root, ctx, rest) {
    if (rest[0] && PAGES[rest[0]]) return modPage(root, ctx, rest[0], rest.slice(1));
    const cards = slot();
    root.append(pageHead('Suite', 'Sibling mods RestAPI looks for. A bound mod mounts its routes under /api and gets a page here.', refreshButton(() => t.now())), cards);
    const t = ctx.every(poll.fast(6), () => fill(cards, async () => {
      const s = await ctx.get('/api/suite');
      return h('div', { class: 'mod-cards' }, s.mods.map((m) => h('section', { class: 'card mod-card' },
        h('header', null, h('h2', { class: 'mod-name' }, modIcon(m.id, 24), h('a', { href: `#/suite/${m.id}`, text: LABEL[m.id] || m.plugin })), boundBadge(m)),
        h('div', { class: 'body' },
          kv([
            ['Plugin', codeWrap(m.plugin)],
            ['Version', m.installed ? `${m.version}, needs ${m.minimum}` : `needs ${m.minimum}`],
            ['Hooks', m.hooks ? `yes, since ${m.hooksSince}` : 'no'],
            ['Bind mode', m.mode],
            ['Routes', `${num(Array.isArray(m.routes) ? m.routes.length : m.routes)} under /api/${m.id}`],
            ['Reason', m.reason || undefined],
            ['Waiting for', m.waiting && m.waiting.length ? m.waiting.join(', ') : undefined],
          ]),
          m.adds ? h('p', { text: m.adds }) : null,
          Array.isArray(m.routes) && m.routes.length ? h('details', { class: 'mt' }, h('summary', { text: 'Routes' }), h('ul', { class: 'list-plain' }, m.routes.map((r) => h('li', null, codeWrap(r))))) : null))));
    }, 'Suite'));
    return undefined;
  },
};

function modPage(root, ctx, id, rest) {
  const label = LABEL[id];
  const sub = h('nav', { class: 'subnav', 'aria-label': 'Suite mods' }, SUITE_MODS.map(([k, l]) => h('a', { href: `#/suite/${k}`, class: k === id ? 'active' : null, text: l })));
  const head = pageHead(label, null, null, h('a', { href: '#/suite', text: 'Suite' }));
  const body = h('div', null, h('div', { class: 'loading', text: 'Loading' }));
  root.append(head, sub, body);
  ctx.suite().then((table) => {
    for (const a of sub.children) {
      const m = table[a.getAttribute('href').split('/').pop()];
      if (m && !m.bound) a.classList.add('off');
    }
    const m = table[id];
    const p = head.querySelector('h1');
    const icon = modIcon(id, 32);
    if (icon) { p.classList.add('mod-name'); p.prepend(icon); }
    if (m) p.append(' ', m.bound ? badge(m.version, 'ok') : badge('not bound', 'warn'));
    body.replaceChildren();
    if (!m || !m.bound) { body.append(notBoundCard(m, label)); return; }
    PAGES[id].mount(body, ctx, m, rest);
  }).catch((e) => body.replaceChildren(errorBox(e, 'Suite table')));
  return undefined;
}

