// Drives headless Chrome through the dashboard RestAPI serves, for DashboardBrowserCheck. Never prints the login
// link or a cookie value. Exit code 1 when a check fails.
//
// DASH_LINK      the login link from /restapi dashboard login
// OTHER_URL      a page on another origin of the same site that POSTs to the API with the browser's cookies
// CDP_PORT       Chrome's debugging port
// DASHBOARD_TOOLS the dashboard checkout's tools folder, for cdp.mjs
// OUT            where screenshots go

import path from 'node:path';
import { pathToFileURL } from 'node:url';

const { openChrome, sleep } = await import(pathToFileURL(path.join(process.env.DASHBOARD_TOOLS, 'cdp.mjs')).href);
const LINK = process.env.DASH_LINK;
const OTHER = process.env.OTHER_URL;
const OUT = process.env.OUT;
const origin = new URL(LINK).origin;
const results = [];
const check = (name, ok, note = '') => { results.push({ name, ok: !!ok, note }); console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${note ? `  (${note})` : ''}`); };

const page = await openChrome({ width: 1440, height: 1000 });
const { send, evaluate, waitFor } = page;
let phase = 'start';
const problems = [];
const csp = [];
const missing = [];
const statuses = [];
const reqs = new Map();
page.on((method, p) => {
  if (method === 'Runtime.exceptionThrown') problems.push(`${phase} exception ${p.exceptionDetails.exception?.description || p.exceptionDetails.text}`);
  if (method === 'Runtime.consoleAPICalled' && (p.type === 'error' || p.type === 'assert')) problems.push(`${phase} console.${p.type} ${p.args.map((a) => a.value ?? a.description ?? '').join(' ')}`);
  if (method === 'Log.entryAdded') {
    const t = `${p.entry.text} ${p.entry.url || ''}`;
    if (/Content Security Policy/i.test(t)) csp.push(`${phase} ${t}`);
    else if (p.entry.level === 'error' && !/status of 40[0-9]/.test(t)) problems.push(`${phase} log.${p.entry.source} ${t}`);
  }
  if (method === 'Audits.issueAdded' && p.issue.code === 'ContentSecurityPolicyIssue') csp.push(`${phase} ${JSON.stringify(p.issue.details.contentSecurityPolicyIssueDetails)}`);
  if (method === 'Network.requestWillBeSent') reqs.set(p.requestId, { method: p.request.method, url: p.request.url });
  if (method === 'Network.loadingFailed' && p.blockedReason) csp.push(`${phase} blocked ${p.blockedReason} ${reqs.get(p.requestId)?.url || ''}`);
  if (method === 'Network.responseReceived') {
    const r = reqs.get(p.requestId) || {};
    const u = p.response.url.replace(origin, '');
    statuses.push({ phase, method: r.method, url: p.response.url, status: p.response.status });
    if (p.response.status === 404 && u.startsWith('/api/')) missing.push(u.split('?')[0]);
    else if (p.response.status >= 400 && !(phase === 'logout' && p.response.status === 401) && !(phase === 'reused link')) {
      if (!(phase === 'read only' && p.response.status === 403) && !(phase === 'other origin')) problems.push(`${phase} http ${p.response.status} ${r.method} ${u}`);
    }
  }
});

const shot = (name) => page.shot(path.join(OUT, `${name}.png`));

try {
  await send('Page.enable');
  await send('Runtime.enable');
  await send('Log.enable');
  await send('Network.enable');
  await send('Audits.enable');
  await page.scheme('dark');

  phase = 'login';
  await send('Page.navigate', { url: LINK });
  const landed = await waitFor(`location.pathname === '/dashboard/' && document.querySelector('main h1')?.textContent && location.hash`, 10000);
  check('the login link opens the page under /dashboard/', landed === '#/overview', `hash ${landed}`);
  const { cookies } = await send('Network.getCookies', { urls: [origin] });
  const c = cookies.find((x) => x.name === 'restapi_session');
  check('the session cookie is HttpOnly, SameSite Strict, Path / and not readable by the page',
    c && c.httpOnly && c.sameSite === 'Strict' && c.path === '/' && !(await evaluate('document.cookie')).includes('restapi_session'),
    c ? `httpOnly ${c.httpOnly}, sameSite ${c.sameSite}, path ${c.path}, value of ${c.value.length} characters` : 'no cookie');
  const capsNow = await evaluate(`fetch('capabilities').then((r) => r.json())`);
  check('capabilities answer the session', capsNow.session === true && capsNow.auth === 'session' && capsNow.mode === 'live' && capsNow.writes === false && capsNow.assets === true && capsNow.who === 'browser-check',
    JSON.stringify({ session: capsNow.session, mode: capsNow.mode, writes: capsNow.writes, assets: capsNow.assets, who: capsNow.who }));
  await sleep(2500);
  const header = await evaluate(`({ name: document.getElementById('server-name').textContent, health: document.getElementById('health-pill').textContent, logout: !document.getElementById('logout-btn').hidden, control: !document.getElementById('control-pill').hidden })`);
  check('the header shows the server, health and Log out, and no Control pill', header.name.includes('Orbis Test Realm') && header.health === 'Healthy' && header.logout && !header.control, JSON.stringify(header));
  const icons = statuses.filter((s) => s.url.includes('/dashboard/assets/'));
  check('the Lively view read the icon index under /dashboard/assets', icons.some((s) => s.url.endsWith('/dashboard/assets/index.json') && s.status === 200), icons.map((s) => `${s.status} ${s.url.replace(origin, '')}`).join(', '));
  await shot('lively-overview');

  phase = 'views';
  await evaluate(`document.querySelector('#view-switch [data-view=data]').click()`);
  const data = await waitFor(`document.documentElement.dataset.view === 'data' && document.querySelector('#view-switch [data-view=data]').getAttribute('aria-pressed')`, 4000);
  await sleep(800);
  await shot('data-overview');
  await evaluate(`document.querySelector('#view-switch [data-view=lively]').click()`);
  const lively = await waitFor(`document.documentElement.dataset.view === 'lively' && document.querySelector('#view-switch [data-view=lively]').getAttribute('aria-pressed')`, 4000);
  check('Just data and Lively switch both ways', data === 'true' && lively === 'true');

  phase = 'settings';
  await evaluate(`location.hash = '#/settings'`);
  const set = await waitFor(`document.querySelector('main h1')?.textContent === 'Settings' && document.querySelector('main').innerText`, 6000);
  check('Settings hides the Password card and says RestAPI serves the page',
    set && !(await evaluate(`!!document.getElementById('password-card')`)) && set.includes('Served by RestAPI') && set.includes('RestAPI serves this page'), '');
  await shot('settings');

  phase = 'read only';
  const ro = await evaluate(`fetch('../api/players/Steve/kick', { method: 'POST' }).then(async (r) => ({ status: r.status, body: await r.json() }))`);
  check('a POST from the page answers 403 read_only while writes are off', ro.status === 403 && ro.body.error === 'read_only', `${ro.status} ${ro.body.error}`);

  phase = 'other origin';
  await send('Page.navigate', { url: OTHER });
  await waitFor(`document.getElementById('done')?.textContent === 'sent'`, 6000);
  await sleep(500);
  const cross = statuses.filter((s) => s.phase === 'other origin' && s.url.startsWith(origin));
  const post = cross.find((s) => s.method === 'POST');
  const read = cross.find((s) => s.method === 'GET');
  check('a POST from another origin with the cookie answers 403', post && post.status === 403, cross.map((s) => `${s.method} ${s.status} ${s.url.replace(origin, '')}`).join(', '));
  check('a GET from another origin with the cookie answers 403 too', read && read.status === 403);

  phase = 'logout';
  await send('Page.navigate', { url: `${origin}/dashboard/#/overview` });
  await waitFor(`!document.getElementById('logout-btn').hidden`, 6000);
  await evaluate(`document.getElementById('logout-btn').click()`);
  const out = await waitFor(`location.pathname === '/dashboard/login' && document.querySelector('.login-card')?.innerText`, 6000);
  check('Log out lands on the login view with the command to run', out && out.includes('/restapi dashboard login') && !(await evaluate(`!!document.getElementById('login-password')`)), out ? out.split('\n').slice(0, 2).join(' / ') : '');
  const after = await evaluate(`fetch('../api/server').then((r) => r.status)`);
  const capsOut = await evaluate(`fetch('capabilities').then((r) => r.text())`);
  check('after log out the API is closed and capabilities say only auth session', after === 401 && capsOut.trim() === '{"auth":"session"}', `${after} ${capsOut.trim()}`);
  await shot('login');

  phase = 'reused link';
  await send('Page.navigate', { url: LINK });
  const reused = await waitFor(`location.pathname === '/dashboard/login' && location.search === '?link=used' && document.getElementById('login-msg')?.textContent`, 6000);
  check('the same link a second time shows that it was used', reused && reused.startsWith('That login link was used already'), reused || '');
  await shot('login-used');
} catch (e) {
  check('the drive ran to the end', false, e.message);
} finally {
  await page.close();
}

check('no exception, console error or failed request', problems.length === 0, problems.slice(0, 8).join(' | '));
check('nothing blocked by the Content Security Policy', csp.length === 0, csp.slice(0, 8).join(' | '));
console.log(`API routes the stub does not answer, asked for by the page: ${[...new Set(missing)].sort().join(', ') || 'none'}`);
const bad = results.filter((r) => !r.ok);
console.log(`${results.length} checks, ${results.length - bad.length} passed, ${bad.length} failed. Screenshots in ${OUT}`);
process.exit(bad.length ? 1 : 0);
