// The login page. It takes the view and the theme from this browser's settings, hands the part of the address
// after # back to the server so the page asked for opens after login, and counts down a rate limit wait.
// Served by RestAPI the page has no form, and ?link= says why a login link did not work.

import { applyLook, get } from './settings.js';

applyLook();
document.documentElement.dataset.view = get('view') === 'data' ? 'data' : 'lively';

const hash = document.getElementById('login-hash');
// After a wrong password the page comes back without the part after #, the server keeps it in the field then.
if (hash && location.hash) hash.value = location.hash;

const msg = document.getElementById('login-msg');
const field = document.getElementById('login-password');
const submit = document.getElementById('login-submit');
let left = Number(msg && msg.dataset.wait) || 0;
if (left > 0) {
  const tick = () => {
    left--;
    if (left > 0) {
      msg.textContent = `Too many wrong passwords from this address. Try again in ${left} s.`;
      setTimeout(tick, 1000);
      return;
    }
    msg.textContent = 'You can try again now.';
    if (field) { field.disabled = false; field.focus(); }
    if (submit) submit.disabled = false;
  };
  setTimeout(tick, 1000);
}

const LINK_TEXT = {
  used: 'That login link was used already or has run out. Run /restapi dashboard login again for a new one.',
  wait: 'Too many login links that did not work came from this address. Wait a minute, then run /restapi dashboard login again.',
};
const why = new URLSearchParams(location.search).get('link');
if (msg && why && LINK_TEXT[why]) {
  msg.textContent = LINK_TEXT[why];
  msg.classList.remove('empty-msg');
}
