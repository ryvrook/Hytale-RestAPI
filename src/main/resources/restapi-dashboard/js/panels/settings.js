// Settings: the viewer's own choices for this browser, the connection facts and the reverse proxy
// configuration for putting the dashboard behind a domain. Nothing here talks to the game server.

import { h, card, kv, seg, select, pageHead, badge, codeWrap, time } from '../dom.js';
import { caps, write, loadCaps, logout, explain } from '../api.js';
import { toast, copyText, copyLinkButton } from '../ui.js';
import * as settings from '../settings.js';
import { ACCENTS, RANGES } from '../settings.js';

const README = 'https://github.com/ryvrook/Hytale-RestAPI#readme';
const NEXT = 'Takes effect on the next refresh.';

function row(label, control, hint, attrs = {}) {
  return h('div', { class: 'set-row', ...attrs },
    h('div', { class: 'set-label', text: label }),
    h('div', { class: 'set-ctl' }, control, hint ? h('span', { class: 'set-hint' }, hint) : null));
}

/** A seg control bound to one setting. */
function segFor(key, options) {
  const el = seg(options, settings.get(key), (v) => settings.set({ [key]: v }));
  el.dataset.setting = key;
  return el;
}

function numberRow(key, label, unitText, hint) {
  const [lo, hi] = RANGES[key];
  const inp = h('input', { type: 'number', min: lo, max: hi, step: 1, value: settings.get(key), 'aria-label': label, inputmode: 'numeric', 'data-setting': key });
  const note = h('span', { class: 'set-saved', 'aria-live': 'polite' });
  inp.addEventListener('change', () => {
    settings.set({ [key]: inp.value });
    inp.value = settings.get(key);
    note.textContent = NEXT;
  });
  return row(label, [inp, h('span', { class: 'unit', text: unitText }), note], hint);
}

/** A small yes or cancel box after the anchor. Not a write control, it changes only this browser. */
function ask(anchor, text, yesLabel, run) {
  const old = anchor.parentElement.querySelector('.confirm');
  if (old) old.remove();
  const yes = h('button', { type: 'button', class: 'btn danger', text: yesLabel });
  const no = h('button', { type: 'button', class: 'btn', text: 'Cancel' });
  const box = h('div', { class: 'confirm', role: 'alertdialog', 'aria-label': text }, h('span', { class: 'confirm-text', text }), h('span', { class: 'actions' }, yes, no));
  no.addEventListener('click', () => box.remove());
  yes.addEventListener('click', () => { box.remove(); run(); });
  anchor.parentElement.after(box);
  yes.focus();
}

// ---------- reverse proxy configuration ----------

function proxyTarget() {
  const port = caps.port || Number(location.port) || 8130;
  // Served by RestAPI, the proxy talks to RestAPI's own listener.
  if (caps.auth === 'session' && caps.listen) return caps.listen;
  return `127.0.0.1:${port}`;
}

/** True when RestAPI serves the page itself and a login link opens the session. */
const served = () => caps.auth === 'session';

function siteOf(url) {
  try { const u = new URL(url); return { host: u.host, https: u.protocol === 'https:', path: u.pathname }; } catch { return null; }
}

function caddyConf(site) {
  const host = site ? site.host : 'dash.example.com';
  const address = site && !site.https ? `http://${host}` : host;
  return [
    `${address} {`,
    ...(served() ? [] : ['\tbasic_auth {', '\t\tadmin <paste the hash from caddy hash-password>', '\t}']),
    `\treverse_proxy ${proxyTarget()} {`,
    '\t\t# Hand each line of the console and event streams on at once.',
    '\t\tflush_interval -1',
    '\t}',
    '}',
  ].join('\n');
}

function nginxConf(site) {
  const host = site ? site.host.replace(/:\d+$/, '') : 'dash.example.com';
  const https = !site || site.https;
  const location = [
    ...(served() ? [] : ['    auth_basic "RestAPI dashboard";', '    auth_basic_user_file /etc/nginx/restapi-dashboard.htpasswd;', '']),
    '    location / {',
    `        proxy_pass http://${proxyTarget()};`,
    '        proxy_http_version 1.1;',
    '        proxy_set_header Connection "";',
    '        proxy_set_header Host $host;',
    '        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;',
    '        proxy_set_header X-Forwarded-Proto $scheme;',
    '        # Server Sent Events, the console and event streams, need these three.',
    '        proxy_buffering off;',
    '        proxy_cache off;',
    '        proxy_read_timeout 1h;',
    '    }',
  ];
  if (!https) return ['server {', '    listen 80;', `    server_name ${host};`, '', ...location, '}'].join('\n');
  return [
    'server {',
    '    listen 443 ssl;',
    '    http2 on;',
    `    server_name ${host};`,
    '',
    `    ssl_certificate     /etc/letsencrypt/live/${host}/fullchain.pem;`,
    `    ssl_certificate_key /etc/letsencrypt/live/${host}/privkey.pem;`,
    '',
    ...location,
    '}',
    '',
    'server {',
    '    listen 80;',
    `    server_name ${host};`,
    '    return 301 https://$host$request_uri;',
    '}',
  ].join('\n');
}

function copyButton(getText, label = 'Copy') {
  return h('button', { type: 'button', class: 'btn small', text: label, onclick: async () => {
    toast(await copyText(getText()) ? 'Copied to the clipboard.' : 'The browser blocked the clipboard. Select the text and copy it by hand.', 'ok');
  } });
}

// ---------- the page ----------

export default {
  mount(root, ctx) {
    let apiName = '';
    let apiVersion = '';

    const draw = () => {
      root.replaceChildren(
        pageHead('Settings', 'Your own choices for this dashboard, kept in this browser only. They change nothing on the server.'),
        h('div', { class: 'cols even set-page' },
          h('div', { class: 'stack' }, appearance(), dashboard(), refresh(), about(), keep()),
          h('div', { class: 'stack' }, connection(), password(), domain())));
      balance();
    };

    // The right column is shorter when there is no password and no control mode, so the last card of the left
    // column moves over when that evens the two out. Side by side only, a phone has one column.
    function balance() {
      const [left, right] = root.querySelectorAll(':scope > .cols > .stack');
      if (!left || !right || left.offsetTop !== right.offsetTop) return;
      const last = left.lastElementChild;
      const end = (col) => col.lastElementChild.getBoundingClientRect().bottom;
      if (last && end(left) - end(right) > last.offsetHeight + 14) right.append(last);
    }

    // Appearance
    function appearance() {
      const swatches = h('div', { class: 'swatches', role: 'group', 'aria-label': 'Accent colour', 'data-setting': 'accent' });
      const choices = [{ id: '', label: 'View default' }, ...ACCENTS];
      const mark = () => { for (const b of swatches.children) b.setAttribute('aria-pressed', b.dataset.accent === settings.get('accent') ? 'true' : 'false'); };
      for (const a of choices) {
        const light = settings.isLight();
        const sw = h('span', { class: `sw${a.id ? '' : ' auto'}`, style: a.id ? { background: light ? a.light : a.dark } : null });
        swatches.append(h('button', { type: 'button', class: 'swatch-btn', dataset: { accent: a.id }, title: a.id ? `${a.label} accent` : 'Blue in Just data, gold in Lively', onclick: () => { settings.set({ accent: a.id }); mark(); } }, sw, h('span', { text: a.label })));
      }
      mark();
      return card('Appearance', h('div', { class: 'set-rows' },
        row('View', segFor('view', [['lively', 'Lively'], ['data', 'Just data']]), 'The same switch as in the header.'),
        row('Theme', segFor('theme', [['system', 'Follow system'], ['dark', 'Dark'], ['light', 'Light']])),
        row('Density', segFor('density', [['comfortable', 'Comfortable'], ['compact', 'Compact']]), 'Compact takes about a quarter off the padding of rows and cards.'),
        row('Accent colour', swatches, 'Links, the current page in the nav and the main buttons.'),
        row('Font size', segFor('fontSize', [['small', 'Small'], ['default', 'Default'], ['large', 'Large']]), 'Scales the page content. The header and the nav keep their size.')));
    }

    // Dashboard
    function dashboard() {
      const name = h('input', { type: 'text', maxlength: 60, value: settings.get('displayName'), placeholder: apiName || 'The name the API reports', 'aria-label': 'Display name', autocomplete: 'off', 'data-setting': 'displayName' });
      let t = null;
      name.addEventListener('input', () => { clearTimeout(t); t = setTimeout(() => settings.set({ displayName: name.value }), 150); });
      name.addEventListener('change', () => { clearTimeout(t); settings.set({ displayName: name.value }); });
      const landing = select(ctx.sections.map((s) => [s.id, s.label]), settings.get('landing'), (v) => settings.set({ landing: v }), { 'aria-label': 'Landing page', 'data-setting': 'landing' });
      const me = h('input', { type: 'text', maxlength: 40, value: settings.get('me'), placeholder: 'Your player name', 'aria-label': 'Your player name', autocomplete: 'off', spellcheck: 'false', 'data-setting': 'me' });
      me.addEventListener('change', () => { settings.set({ me: me.value }); me.value = settings.get('me'); });
      return card('Dashboard', h('div', { class: 'set-rows' },
        row('Display name', name, apiName ? `Shown in the header in place of ${apiName}. Leave it empty to keep that name.` : 'Shown in the header in place of the name the API reports. Leave it empty to keep that name.'),
        row('Landing page', landing, 'The page that opens when the address has no page in it.'),
        row('Times in tables', segFor('times', [['relative', 'Relative'], ['absolute', 'Absolute']]), 'Relative reads 4 min ago. The other form shows on hover.'),
        row('Clock', segFor('clock', [['24', '24 hour'], ['12', '12 hour']])),
        row('Your player name', me, 'The player Go to on a player page teleports. Go to also keeps the name you pick there.')));
    }

    // Refresh
    function refresh() {
      const hidden = h('input', { type: 'checkbox', checked: settings.get('pauseHidden') ? true : null, 'data-setting': 'pauseHidden' });
      hidden.addEventListener('change', () => settings.set({ pauseHidden: hidden.checked }));
      return card('Refresh', h('div', { class: 'set-rows' },
        numberRow('fastPoll', 'Fast poll', 's', 'Server, worlds and players, 3 to 30 seconds. Lighter lists follow at a multiple of it.'),
        numberRow('slowPoll', 'Slow poll', 's', 'Heavy lists such as plugins, counters and stats, 30 to 300 seconds.'),
        numberRow('followSeconds', 'Follow interval', 's', 'How often Follow on a player page refreshes, 1 to 10 seconds.'),
        numberRow('consoleLines', 'Console lines kept', 'lines', 'The console page keeps this many lines, 100 to 2000.'),
        row('Hidden tab', h('label', { class: 'check' }, hidden, h('span', { text: 'Pause all polling while the tab is hidden' })))),
      { note: NEXT });
    }

    // Connection
    function connection() {
      const field = h('input', { type: 'url', value: settings.get('publicUrl'), placeholder: caps.publicUrl || 'https://dash.example.com', 'aria-label': 'Public address', autocomplete: 'off', spellcheck: 'false', 'data-setting': 'publicUrl' });
      const note = h('span', { class: 'set-saved', 'aria-live': 'polite' });
      field.addEventListener('change', () => {
        const v = field.value.trim();
        if (v && !settings.cleanUrl(v)) {
          field.classList.add('invalid');
          note.className = 'set-hint';
          note.textContent = 'Give an http or https address, such as https://dash.example.com.';
          return;
        }
        field.classList.remove('invalid');
        settings.set({ publicUrl: v });
        field.value = settings.get('publicUrl');
        draw();
      });
      const pub = settings.publicUrl();
      const facts = kv([
        ['Mode', caps.mode === 'live' ? 'Served by RestAPI' : caps.mode === 'proxy' ? 'Live, forwarding to RestAPI' : caps.mode === 'mock' ? 'Mock data' : 'Not known'],
        served() && caps.who ? ['Logged in as', `${caps.who}, from /restapi dashboard login`] : null,
        ['Changes', caps.writes ? badge('Control on', 'warn') : 'Read only'],
        ['Game icons', caps.assets ? 'Read from Assets.zip' : 'No Assets.zip found'],
        ['Character pictures', caps.chronicle ? 'From Chronicle Stats' : 'Letter busts'],
        ['RestAPI address', caps.upstream ? codeWrap(caps.upstream) : caps.mode === 'mock' ? 'None, the mock answers' : null],
        ['Listens on', caps.listen ? h('span', null, h('code', { text: caps.listen }), caps.remote ? ' ' : null, caps.remote ? badge('open to the network', 'bad') : null) : null],
        ['Public address', pub ? codeWrap(pub) : 'Not set'],
        ['Link to this page', h('span', { class: 'set-actions' }, Object.assign(codeWrap(settings.pageLink('settings')), { className: 'idwrap page-link' }), copyLinkButton('settings'))],
      ]);
      return card('Connection', [facts,
        served() ? h('p', { class: 'set-text mt' }, `RestAPI serves this page itself at ${caps.upstream ? `${caps.upstream}/dashboard/` : 'this address'}, on its own listener. The page asks RestAPI for everything relative to that address.`) : null,
        h('div', { class: 'set-rows mt' },
        row('Public address', [field, note], caps.publicUrl && !settings.get('publicUrl')
          ? `Set by --public-url to ${caps.publicUrl}. A value here takes its place in this browser.`
          : 'The address people reach this dashboard at when it sits behind a domain. Copied links use it.'))]);
    }

    // Password
    function password() {
      // RestAPI opens sessions from login links, there is no password to set.
      if (served()) return null;
      const note = 'Asked for by server.js';
      const both = h('p', { class: 'set-text' }, 'The Caddy and nginx configuration in the next card is the other way to protect the dashboard. Both can be used together, the proxy asks for its password first and the dashboard for this one after it.');
      if (caps.auth == null) {
        return card('Password', [
          h('p', { class: 'set-text' }, badge('Not available'), ' The server.js that serves this page is older than the password. Restart it with the current server.js to set one here.'),
          both,
        ], { note, id: 'password-card' });
      }
      const set = caps.auth === 'password';
      const min = caps.passwordMin || 12;
      const pw = (which, label, auto) => h('input', { type: 'password', autocomplete: auto, 'aria-label': label, maxlength: 1024, spellcheck: 'false', dataset: { pw: which } });
      const cur = set ? pw('current', 'Current password', 'current-password') : null;
      const next = pw('next', 'New password', 'new-password');
      const again = pw('repeat', 'Repeat the new password', 'new-password');
      const msg = h('p', { class: 'pw-msg', id: 'pw-msg', role: 'status', 'aria-live': 'polite' });
      const say = (text, tone = 'bad') => { msg.textContent = text; msg.className = `pw-msg ${tone}`; };
      const done = async (text) => {
        await loadCaps();
        window.dispatchEvent(new Event('restapi:auth'));
        draw();
        toast(text, 'ok');
        document.getElementById('password-card')?.scrollIntoView({ block: 'nearest' });
      };
      const send = async (body, btns) => {
        for (const b of btns) b.disabled = true;
        try {
          return await write('POST', '/dashboard/password', body);
        } catch (e) {
          say(e.code === 'wrong_password' ? 'The current password is not right.' : e.code === 'rate_limited' || e.code === 'password_needed' || e.code === 'too_short' ? e.message : explain(e));
          return null;
        } finally {
          for (const b of btns) b.disabled = false;
        }
      };
      const setBtn = h('button', { type: 'submit', class: 'btn primary', id: 'pw-set', text: set ? 'Change password' : 'Set password' });
      const removeBtn = set ? h('button', { type: 'button', class: 'btn danger-soft', id: 'pw-remove', text: 'Remove password' }) : null;
      const outBtn = caps.session ? h('button', { type: 'button', class: 'btn', id: 'pw-logout', text: 'Log out', onclick: () => { outBtn.disabled = true; logout(); } }) : null;
      const btns = [setBtn, removeBtn].filter(Boolean);
      const form = h('form', { class: 'pw-form', id: 'pw-form', autocomplete: 'on', novalidate: true },
        h('div', { class: 'set-rows' },
          set ? row('Current password', cur) : null,
          row('New password', next, `At least ${min} characters, no other rules. A sentence of a few words is easy to type and hard to guess.`),
          row('Repeat', again)),
        msg,
        h('div', { class: 'set-actions' }, setBtn, removeBtn, outBtn));
      form.addEventListener('submit', async (e) => {
        e.preventDefault();
        if (set && !cur.value) { say('Give the current password first.'); cur.focus(); return; }
        if ([...next.value].length < min) { say(`The new password needs at least ${min} characters.`); next.focus(); return; }
        if (next.value !== again.value) { say('The two new passwords differ.'); again.focus(); return; }
        say('', '');
        const r = await send({ current: set ? cur.value : undefined, next: next.value }, btns);
        if (r) await done(set ? 'Password changed. Every other browser has to log in again.' : 'Password set. Every other browser has to log in now, this one stays logged in.');
      });
      if (removeBtn) {
        removeBtn.addEventListener('click', () => {
          if (!cur.value) { say('Give the current password to remove it.'); cur.focus(); return; }
          say('', '');
          ask(removeBtn, 'Remove the password? Anyone who reaches the dashboard can then use it without one.', 'Remove', async () => {
            const r = await send({ current: cur.value, next: '' }, btns);
            if (r) await done('Password removed. The dashboard no longer asks for one.');
          });
        });
      }
      return card('Password', [
        kv([
          ['Password', set ? h('span', { class: 'pw-state' }, badge('Set', 'ok'), caps.authSince ? time(caps.authSince) : null) : badge('Not set')],
          ['This browser', caps.session ? 'Logged in' : 'No login needed'],
          ['Sessions', 'End after 12 hours without use, or 7 days at most, and when server.js restarts'],
        ]),
        h('p', { class: 'set-text mt' }, 'A password here protects the dashboard itself. server.js asks for it before it serves any page or passes any request on to RestAPI. The API key never leaves the server either way.'),
        both,
        caps.remoteNeedsPassword && set ? h('div', { class: 'notice' }, 'server.js runs with --allow-remote, so the password can be changed but not removed.') : null,
        form,
      ], { note, id: 'password-card' });
    }

    // About
    function about() {
      const docs = caps.upstream ? `${caps.upstream.replace(/\/$/, '')}/docs` : null;
      return card('About', kv([
        ['Dashboard', ctx.version],
        ['RestAPI', apiVersion || 'Not known yet'],
        ['RestAPI README', h('a', { href: README, target: '_blank', rel: 'noopener noreferrer', text: 'github.com/ryvrook/Hytale-RestAPI' })],
        ['Docs page', docs ? h('a', { href: docs, target: '_blank', rel: 'noopener noreferrer', text: docs }) : 'The mock has none. Against a live server this links to its /docs page.'],
      ]));
    }

    // Export, import and reset
    function keep() {
      const file = h('input', { type: 'file', accept: 'application/json,.json', hidden: true, id: 'settings-file', 'aria-label': 'Settings file' });
      file.addEventListener('change', async () => {
        const f = file.files && file.files[0];
        if (!f) return;
        try {
          const r = settings.importText(await f.text());
          draw();
          toast(`Imported ${r.applied} settings from ${f.name}${r.ignored.length ? `, left out ${r.ignored.join(', ')}` : ''}.`, 'ok');
        } catch (e) {
          toast(`${f.name} was not imported. ${e.message}`, 'bad');
        }
        file.value = '';
      });
      const exportBtn = h('button', { type: 'button', class: 'btn', id: 'settings-export', text: 'Export', onclick: () => {
        const blob = new Blob([settings.exportText()], { type: 'application/json' });
        const a = h('a', { href: URL.createObjectURL(blob), download: 'restapi-dashboard-settings.json' });
        document.body.append(a);
        a.click();
        setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
        toast('Saved restapi-dashboard-settings.json.', 'ok');
      } });
      const importBtn = h('button', { type: 'button', class: 'btn', id: 'settings-import', text: 'Import', onclick: () => file.click() });
      const resetBtn = h('button', { type: 'button', class: 'btn danger-soft', id: 'settings-reset', text: 'Reset to defaults' });
      resetBtn.addEventListener('click', () => ask(resetBtn, 'Put every setting back to its default?', 'Reset', () => {
        settings.reset();
        draw();
        toast('Every setting is back to its default.', 'ok');
      }));
      return card('Keep your settings', [
        h('p', { class: 'set-text' }, 'Export saves these settings as a file, Import reads one back, in this browser or another. Nothing goes to the server.'),
        h('div', { class: 'set-actions' }, exportBtn, importBtn, resetBtn, file)]);
    }

    // Attach to a domain
    function domain() {
      const pub = settings.publicUrl();
      const site = pub ? siteOf(pub) : null;
      let which = 'caddy';
      const pre = h('pre', { class: 'conf', 'data-conf': 'caddy' });
      const cmd = h('div');
      const show = () => {
        pre.dataset.conf = which;
        pre.textContent = which === 'caddy' ? caddyConf(site) : nginxConf(site);
        cmd.replaceChildren(which === 'caddy'
          ? h('p', { class: 'set-text' }, 'Make the password hash with the command below and put it in place of the line that asks for it. Caddy gets and renews the HTTPS certificate on its own.', h('code', { class: 'conf-cmd', text: "caddy hash-password --plaintext 'a long password'" }))
          : h('p', { class: 'set-text' }, 'Make the password file with the command below, from the apache2-utils package. The certificate paths are the ones certbot uses, change them if yours live elsewhere.', h('code', { class: 'conf-cmd', text: 'sudo htpasswd -c /etc/nginx/restapi-dashboard.htpasswd admin' })));
      };
      show();
      const pick = seg([['caddy', 'Caddy'], ['nginx', 'nginx']], which, (v) => { which = v; show(); });
      pick.dataset.setting = 'proxy-kind';
      const subPath = site && site.path && site.path !== '/';
      if (served()) {
        return card('Attach to a domain', [
          h('p', { class: 'set-text' }, `RestAPI serves this page on its own listener, ${caps.listen || proxyTarget()}, next to the API. A reverse proxy with https in front of RestAPI is enough, as the RestAPI README describes, and the page comes along under /dashboard/. Sessions come from login links of /restapi dashboard login, so the proxy needs no password of its own, though one does no harm.`),
          h('p', { class: 'set-text' }, site
            ? `The configuration below is made for ${site.host} from the public address.`
            : 'Set docs.serverUrl in RestAPI.json, or the public address in the Connection card, and the configuration below fills in your host. Until then it uses dash.example.com.'),
          caps.writes ? h('div', { class: 'notice bad' }, h('strong', { text: 'Writes are on. ' }), 'Anyone with a session can change the server, so hand out login links only to yourself.') : null,
          h('div', { class: 'conf-head' }, pick, copyButton(() => pre.textContent, 'Copy configuration')),
          pre,
          h('p', { class: 'set-text' }, 'Add the proxy to http.trustedProxies in RestAPI.json so the audit log sees the real address and the session cookie is marked Secure over https.'),
        ], { note: 'Caddy or nginx in front of RestAPI' });
      }
      return card('Attach to a domain', [
        h('p', { class: 'set-text' }, `server.js listens on ${caps.listen || proxyTarget()}, which only this machine can reach, and it should stay that way. To open the dashboard from elsewhere, run a reverse proxy on the same machine that asks for a password and passes requests on to that address. The browser never sees the API key, server.js still adds it.`),
        h('p', { class: 'set-text' }, site
          ? `The configuration below is made for ${site.host} from the public address and for port ${caps.port || location.port || 8130}.`
          : 'Set the public address in the Connection card and the configuration below fills in your host. Until then it uses dash.example.com.'),
        subPath ? h('div', { class: 'notice' }, `The public address has the path ${site.path}. The dashboard asks for /api and its files from the root of its address, so give it a host of its own, such as a subdomain.`) : null,
        caps.writes ? h('div', { class: 'notice bad' }, h('strong', { text: 'Control mode is on. ' }), 'Anyone who gets past the password can change the server, so pick a long one.') : null,
        h('div', { class: 'conf-head' }, pick, copyButton(() => pre.textContent, 'Copy configuration')),
        pre,
        cmd,
        h('p', { class: 'set-text' }, 'Start server.js with --public-url and the address so every browser starts with it. server.js refuses --bind with --allow-remote until a dashboard password is set, and even then a proxy with https in front keeps that password off the network in clear text.'),
      ], { note: 'Caddy or nginx in front of server.js' });
    }

    // The API's name and version, for the placeholders and About. Drawn first without them.
    draw();
    const health = ctx.health();
    if (health && health.restapi) apiVersion = health.restapi;
    ctx.get('/api/server', { quiet: true }).then((s) => {
      apiName = s?.server?.name || '';
      apiVersion = apiVersion || s?.restapi || '';
      // Keep what the viewer is typing, so only draw again when no field has focus.
      if (!root.contains(document.activeElement) || document.activeElement === document.body) draw();
      else {
        const n = root.querySelector('[data-setting=displayName]');
        if (n && apiName) n.placeholder = apiName;
      }
    }, () => {});
    // A change made outside this page, the header switch or another tab, shows here at once.
    ctx.cleanup(settings.onChange((keys) => {
      if (keys.includes('view')) for (const b of root.querySelectorAll('[data-setting=view] button')) { const on = b.textContent === (settings.get('view') === 'data' ? 'Just data' : 'Lively'); b.classList.toggle('on', on); b.setAttribute('aria-pressed', on ? 'true' : 'false'); }
    }));
  },
};
