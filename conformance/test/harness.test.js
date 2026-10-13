'use strict';
/* harness.test.js — the checks can say no.
 *
 * A suite that passes is only worth something if it fails when the thing it
 * guards is broken. Each test here breaks one thing on purpose — an action the
 * contract does not name, a field too many or too few, a value a string bridge
 * cannot carry, a request straight from the page, a URL left in the DOM, an
 * answer outside the contract, a later-phase action a P1 host does not serve,
 * a byte flipped in core/, a link or a reordered list, a dot-file upstream —
 * and asserts the harness catches it.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');
const { boot, wait } = require('../lib/session');
const { check, validateSchema } = require('../lib/schema');
const { replyProblems, CONTRACT, makeHost, answerBody } = require('../lib/host');
const TapRules = require('../lib/tap-rules');

const ROOT = path.join(__dirname, '..', '..');
const kinds = (vs) => vs.map((v) => v.kind).sort();

test('schema: required, optional, unions, and no field it does not name', () => {
  const s = { fields: { a: 'string', b: 'integer?', c: 'object|null', d: 'string[]?' } };
  validateSchema(s);
  assert.deepEqual(check(s, { a: 'x', c: null }), []);
  assert.deepEqual(check(s, { a: 'x', b: 2, c: {}, d: ['y'] }), []);
  assert.equal(check(s, { c: null }).length, 1, 'a missing required field');
  assert.equal(check(s, { a: 1, c: null }).length, 1, 'a wrong type');
  assert.equal(check(s, { a: 'x', b: 1.5, c: null }).length, 1, 'a number that is not an integer');
  assert.equal(check(s, { a: 'x', c: null, e: 1 }).length, 1, 'a field the schema does not name');
  assert.equal(check(s, { a: 'x', c: null, d: [1] }).length, 1, 'string[] with a number in it');
  assert.throws(() => validateSchema({ fields: { a: 'strnig' } }), /unknown type/);
});

test('the fake host refuses its own answers when they leave the contract', () => {
  const by = (n) => CONTRACT.find((e) => e.name === n);
  assert.deepEqual(replyProblems(by('seedStatus'), '{"exists":true}', null), []);
  assert.ok(replyProblems(by('seedStatus'), 'yes', null).length, 'text where JSON is due');
  assert.ok(replyProblems(by('seedStatus'), '{"exists":"yes"}', null).length, 'a wrong type in the JSON');
  assert.ok(replyProblems(by('notify'), 'sent', null).length, 'a text not among the values');
  assert.ok(replyProblems(by('mintRequest'), '{}', null).length, 'not status\\nbody');
  assert.deepEqual(replyProblems(by('mintRequest'), '200\n{}', null), []);
  assert.ok(replyProblems(by('haptic'), 'ok', null).length, 'an answer to a message that takes none');
});

test('the host and the network sentinel catch what a broken page would do', async () => {
  const s = await boot({ seed: true, tor: 'up' });
  try {
    await s.settle(1500);
    assert.deepEqual(s.violations(), [], 'a clean boot first');
    const w = s.window;
    const post = (m) => w.webkit.messageHandlers.foxy.postMessage(m);

    // an action the contract does not name, and the iOS answer to it
    post({ action: 'seedRead', id: 'h1' });
    assert.deepEqual(kinds(s.host.violations), ['unknown']);
    assert.ok(s.host.answered.some(([n, t, e]) => n === 'seedRead' && t === null && e === 'Unknown action: seedRead'));
    s.host.violations.length = 0;

    // a field too many, a field missing, a wrong type
    post({ action: 'notify', id: 'h2', kind: 'sent', amount: 21 });
    post({ action: 'copy', id: 'h3', text: 'x' });
    post({ action: 'awake', id: 'h4', on: 'yes' });
    assert.deepEqual(kinds(s.host.violations), ['payload', 'payload', 'payload']);
    s.host.violations.length = 0;

    // what a string bridge cannot carry
    post({ action: 'log', id: 'h5', text: 'x', fn() {} });
    post({ action: 'candles', id: 'h6', g: NaN, span: 86400 });
    assert.ok(kinds(s.host.violations).includes('not-json'));
    s.host.violations.length = 0;

    // an actionless message the contract does not list
    post({ widget: 'x' });
    assert.deepEqual(kinds(s.host.violations), ['unknown']);
    s.host.violations.length = 0;

    // the page reaching out by itself, every way it could
    w.eval("fetch('https://mint.example/v1/info').catch(function () {})");
    w.eval("try { var x = new XMLHttpRequest(); x.open('GET', 'https://mint.example/v1/keys'); x.send(); } catch (e) {}");
    w.eval("try { new WebSocket('wss://mint.example/v1/ws'); } catch (e) {}");
    w.eval("try { new EventSource('https://mint.example/events'); } catch (e) {}");
    w.eval("navigator.sendBeacon('https://mint.example/beacon', 'x')");
    w.eval("try { new RTCPeerConnection(); } catch (e) {}");
    w.eval("var i = document.createElement('img'); i.src = 'https://tracker.example/p.png'; document.body.appendChild(i)");
    w.eval("var l = document.createElement('link'); l.rel = 'preconnect'; l.href = 'https://tracker.example/'; document.head.appendChild(l)");
    w.eval("var sc = document.createElement('script'); sc.src = 'https://cdn.example/x.js'; document.head.appendChild(sc)");
    await wait(300);
    const got = new Set(s.violations().map((v) => v.kind));
    for (const k of ['fetch', 'xhr', 'websocket', 'eventsource', 'beacon', 'webrtc', 'dom', 'subresource']) {
      assert.ok(got.has(k), 'the sentinel saw ' + k + ': ' + JSON.stringify([...got]));
    }

    // URLs with no scheme of their own, which resolve off the phone all the same
    const dom = () => s.violations().filter((v) => v.kind === 'dom').map((v) => v.detail).join('\n');
    w.eval("var r = document.createElement('img'); r.src = '//cdn.example/rel.png'; document.body.appendChild(r)");
    w.eval("var d = document.createElement('div'); d.setAttribute('style', 'background: url(//cdn.example/bg.png)'); document.body.appendChild(d)");
    w.eval("var e = document.createElement('div'); document.body.appendChild(e); e.style.backgroundImage = 'url(\"https://cdn.example/set.png\")'");
    w.eval("var st = document.createElement('style'); st.textContent = 'a { background: url(//cdn.example/sheet.png) }'; document.head.appendChild(st)");
    await wait(300);
    assert.match(dom(), /<img src="\/\/cdn\.example\/rel\.png">/, 'a protocol-relative <img src>');
    assert.match(dom(), /style="url\(\/\/cdn\.example\/bg\.png\)"/, 'a style attribute with a protocol-relative url()');
    assert.match(dom(), /style="url\(https:\/\/cdn\.example\/set\.png\)"/, 'a style set from script');
    assert.match(dom(), /<style> with a remote url\(\/\/cdn\.example\/sheet\.png\)/, 'a <style> with a protocol-relative url()');
  } finally {
    s.close();
  }
});

test('leaves(): a URL is judged where it resolves, not by how it is spelled', () => {
  const { leaves, cssLeaves, BASE } = require('../lib/page');
  for (const u of ['//evil.example/x.png', 'https://cdn.example/x', 'HTTP://cdn.example/', 'wss://relay.example', 'ftp://f.example/x', ' //cdn.example/x ']) {
    assert.equal(leaves(u), true, u + ' leaves');
  }
  for (const u of ['', 'qrcode.js', '../x.js', BASE + 'a.js', '/assets/web/a.js', 'blob:https://appassets.androidplatform.net/1',
    'data:image/png;base64,AA', '#top', 'javascript:void 0', 'about:blank', 'https://appassets.androidplatform.net/x']) {
    assert.equal(leaves(u), false, JSON.stringify(u) + ' stays');
  }
  assert.deepEqual(cssLeaves('a:url(img/a.png); b:url("//cdn.example/b.png"); c:url( https://t.example/c )'), ['//cdn.example/b.png', 'https://t.example/c']);
});

test('a P1 host refuses, as a violation, a later-phase action the contract gives no p1 stub', async () => {
  const s = await boot({ seed: true, phase: 'P1' });
  try {
    await s.settle(300);
    s.host.violations.length = 0;
    const post = (m) => s.window.webkit.messageHandlers.foxy.postMessage(m);
    post({ action: 'seedShow', id: 'p1a', verify: false });
    assert.deepEqual(kinds(s.host.violations), ['unserved']);
    assert.ok(s.host.answered.some(([n, t, e]) => n === 'seedShow' && t === null && e === 'Unknown action: seedShow'));
    s.host.violations.length = 0;
    // a stubbed one answers its stub, inside the contract
    post({ action: 'price', id: 'p1b', agree: false });
    assert.deepEqual(s.host.violations, []);
    assert.ok(s.host.answered.some(([n, t, e]) => n === 'price' && t === null && e === 'Foxy is not connected to Tor.'));
    // and a P1 action is served as ever
    post({ action: 'seedStatus', id: 'p1c' });
    assert.ok(s.host.answered.some(([n, t]) => n === 'seedStatus' && t === '{"exists":true}'));
    assert.deepEqual(s.host.replyProblems, []);
  } finally {
    s.close();
  }
});

test('a P2 host serves P1 and P2 actions, stubs later ones, and refuses a later one with no stub', async () => {
  const { laterThan } = require('../lib/host');
  assert.equal(laterThan('P3', 'P2'), true);
  assert.equal(laterThan('LATER', 'P2'), true);
  assert.equal(laterThan('P2', 'P2'), false);
  assert.equal(laterThan('P1', 'P2'), false);
  assert.throws(() => laterThan('P9', 'P2'), /unknown phase/);
  const s = await boot({ seed: true, tor: 'up', phase: 'P2' });
  try {
    await s.settle(300);
    s.host.violations.length = 0;
    const post = (m) => s.window.webkit.messageHandlers.foxy.postMessage(m);
    // a P2 action is served: the route is up, so the price is a price, not the P1 refusal
    post({ action: 'price', id: 'p2a', agree: false });
    assert.ok(s.host.answered.some(([n, t, e]) => n === 'price' && t === '65000.5' && e === null));
    // the Orbot actions are LATER on Android: their stubs answer
    post({ action: 'orbotRefresh', id: 'p2b' });
    assert.ok(s.host.answered.some(([n, t]) => n === 'orbotRefresh' && t === 'none'));
    // a P3 action with a stub answers it; one without is unserved
    post({ action: 'captured', id: 'p2c' });
    assert.ok(s.host.answered.some(([n, t]) => n === 'captured' && t === 'no'));
    assert.deepEqual(s.host.violations, []);
    post({ action: 'seedShow', id: 'p2d', verify: false });
    assert.deepEqual(kinds(s.host.violations), ['unserved']);
    assert.match(s.host.violations[0].detail, /^P2 has no answer for P3 action "seedShow"/);
    assert.deepEqual(s.host.replyProblems, []);
  } finally {
    s.close();
  }
});

test('host.global: a contract global, called on a later turn after nothing else, refused unlisted or from too early a phase', async () => {
  const s = await boot({ seed: true, tor: 'up', phase: 'P3' });
  try {
    await s.settle(300);
    s.host.violations.length = 0;
    const w = s.window;
    const got = [];
    w.__foxyBluetoothAnswer = (state) => got.push(state);
    s.host.global('__foxyBluetoothAnswer', 'unsupported');
    assert.deepEqual(got, [], 'not inside the call that asked for it');
    await wait(20);
    assert.deepEqual(got, ['unsupported']);
    assert.ok(s.host.pushes.includes('__foxyBluetoothAnswer'));
    // bluetoothAsk on a P3 host: the push lands before the reply (iOS's order)
    const order = [];
    w.__foxyBluetoothAnswer = (state) => order.push('push ' + state);
    const real = w.FoxyWallet._scanResult;
    w.FoxyWallet._scanResult = function (id, text, error) { if (id === 'bt-h') order.push('reply ' + text); return real.apply(this, arguments); };
    w.webkit.messageHandlers.foxy.postMessage({ action: 'bluetoothAsk', id: 'bt-h' });
    await wait(50);
    w.FoxyWallet._scanResult = real;
    assert.deepEqual(order, ['push unsupported', 'reply unsupported']);
    assert.deepEqual(s.host.violations, []);
    // a name that is not a global of the contract
    s.host.global('_privacy', {});
    assert.deepEqual(kinds(s.host.violations), ['unknown']);
  } finally {
    s.close();
  }
  // a P2 host has no Bluetooth screen answer to push: bluetoothAsk is P3
  const p2 = await boot({ seed: true, tor: 'up', phase: 'P2' });
  try {
    await p2.settle(300);
    p2.host.violations.length = 0;
    p2.host.global('__foxyBluetoothAnswer', 'unsupported');
    assert.deepEqual(kinds(p2.host.violations), ['unserved']);
    p2.host.violations.length = 0;
    p2.window.webkit.messageHandlers.foxy.postMessage({ action: 'bluetoothAsk', id: 'bt-p2' });
    assert.deepEqual(kinds(p2.host.violations), ['unserved'], 'bluetoothAsk has no p1 stub: a P2 host may not lean on it');
  } finally {
    p2.close();
  }
});

test('tap-rules: the fake host refuses what TapRules.kt refuses (FoxyTests/TapProtocolTests.swift:41-80 vectors)', () => {
  const invoice = 'lnbc10n1pn9xyzpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpusp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9qrsgq';
  const req = 'creqApGF0gaNhdGVub3N0cmFheDQ3YWJjZGVmMTIzNDU2Nzg5MGFiY2RlZjEyMzQ1Njc4OTBhYmNk';
  assert.ok(TapRules.isPayload(`{"v":2,"inv":"${invoice}"}`));
  assert.ok(TapRules.isPayload(`{"v":2,"req":"${req}"}`));
  assert.ok(TapRules.isPayload(`{"v":2,"inv":"${invoice}","req":"${req}"}`));
  assert.ok(!TapRules.isPayload('{"v":2}'));
  assert.ok(TapRules.isPayload(`{"v":2,"req":"${req}="}`), 'padded, as cashu-ts writes it');
  assert.ok(TapRules.isPayload(`{"v":2,"req":"${req}=="}`));
  assert.ok(TapRules.isRequest(req + '='));
  assert.ok(!TapRules.isPayload('{"v":2,"inv":"bc1qxy2kgdygjrsqtzq2n0"}'));
  assert.ok(!TapRules.isPayload('{"v":2,"req":"cashuBo2F0gaJhaUgA"}'));
  assert.ok(!TapRules.isPayload(`{"v":3,"inv":"${invoice}"}`));
  assert.ok(!TapRules.isPayload(invoice));
  assert.ok(!TapRules.isPayload('{'));
  assert.ok(!TapRules.isPayload('{"v":2,"inv":"lnbc10n1' + 'q'.repeat(5000) + '"}'));
  assert.ok(!TapRules.isPayload(`{"v":true,"inv":"${invoice}"}`), 'true is not the number two');
  assert.ok(TapRules.isPayload(`{"v":2.0,"inv":"${invoice}"}`));
  assert.ok(TapRules.isPayload(JSON.stringify({ v: 2, inv: 5, req })), 'a half that is not a string counts as missing');
  assert.ok(!TapRules.isPayload('[' + JSON.stringify({ v: 2, inv: invoice }) + ']'));
  assert.ok(TapRules.isInvoice(invoice));
  assert.ok(TapRules.isInvoice(invoice.toUpperCase()));
  assert.ok(!TapRules.isInvoice('cashuBo2F0gaJhaUgA'));
  assert.ok(!TapRules.isInvoice('bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh'));
  assert.ok(!TapRules.isInvoice('lnbc10n1' + 'q'.repeat(3000)));
  assert.ok(!TapRules.isInvoice('lnbc10n1pn9<script>'));
  assert.ok(!TapRules.isInvoice('lnbc10n1pN9xyz' + 'q'.repeat(40)));
});

test('host.pushAs and o.unknown: a push after its reply, refused from too early a phase or off-shape; unknown only for an unserved, unstubbed action', async () => {
  const s = await boot({ seed: true, tor: 'up', phase: 'P3' });
  try {
    await s.settle(300);
    s.host.violations.length = 0;
    const w = s.window;
    // tapReceiveStart on a P3 host: the reply lands before the _tap push (iOS's order), and only the first makes a receiver
    const order = [];
    const realReply = w.FoxyWallet._scanResult;
    const realTap = w.FoxyWallet._tap;
    w.FoxyWallet._scanResult = function (id, text, error) { if (/^tr-/.test(id)) order.push('reply ' + id + ' ' + text); return realReply.apply(this, arguments); };
    w.FoxyWallet._tap = function (ev) { order.push('push ' + ev.side + ' ' + ev.stage); return realTap.apply(this, arguments); };
    const post = (m) => w.webkit.messageHandlers.foxy.postMessage(m);
    post({ action: 'tapReceiveStart', id: 'tr-1', payload: '', early: true });
    assert.deepEqual(order, [], 'nothing inside the call that asked');
    await wait(50);
    post({ action: 'tapReceiveStart', id: 'tr-2', payload: '', early: false });
    await wait(50);
    post({ action: 'tapReceiveStop', id: 'tr-3' });
    post({ action: 'tapReceiveStart', id: 'tr-4', payload: '', early: true });
    await wait(50);
    w.FoxyWallet._scanResult = realReply;
    w.FoxyWallet._tap = realTap;
    assert.deepEqual(order, ['reply tr-1 ok', 'push receive unsupported', 'reply tr-2 ok', 'reply tr-3 ok', 'reply tr-4 ok', 'push receive unsupported']);
    assert.deepEqual(s.host.violations, []);
    // a payload Android (TapRules.kt) refuses: the page's own no-invoice, no-request advert (26d-tap.js), refused
    // with TapRules' words, no push and no receiver made; the next good start is still the first receiver
    order.length = 0;
    w.FoxyWallet._scanResult = function (id, text, error) { if (/^tr-/.test(id)) order.push('reply ' + id + ' ' + (error || text)); return realReply.apply(this, arguments); };
    w.FoxyWallet._tap = function (ev) { order.push('push ' + ev.side + ' ' + ev.stage); return realTap.apply(this, arguments); };
    post({ action: 'tapReceiveStop', id: 'tr-5' });
    post({ action: 'tapReceiveStart', id: 'tr-6', payload: JSON.stringify({ v: 2, up: true, ask: 1 }), early: false });
    await wait(50);
    post({ action: 'tapReceiveStart', id: 'tr-7', payload: '', early: true });
    await wait(50);
    w.FoxyWallet._scanResult = realReply;
    w.FoxyWallet._tap = realTap;
    assert.deepEqual(order, ['reply tr-5 ok', 'reply tr-6 Not something a payer could pay.', 'reply tr-7 ok', 'push receive unsupported']);
    assert.deepEqual(s.host.violations, []);
    // a name that is not a push of the contract, and a _tap the contract's schema does not allow
    s.host.pushAs('__foxyBluetoothAnswer', 'x');
    s.host.pushAs('_tap', { side: 'receive' });
    assert.deepEqual(kinds(s.host.violations), ['payload', 'unknown']);
  } finally {
    s.close();
  }
  // a P2 host has no tap receive to answer: tapReceiveStart and _tap are P3
  const p2 = await boot({ seed: true, tor: 'up', phase: 'P2', unknown: new Set(['tapReceiveStart']) });
  try {
    await p2.settle(300);
    assert.deepEqual(kinds(p2.host.violations), [], 'naming a P3 action unknown on a P2 host is allowed: it is later than P2 and has no stub');
    p2.host.pushAs('_tap', { side: 'receive', stage: 'unsupported' });
    assert.deepEqual(kinds(p2.host.violations), ['unserved']);
  } finally {
    p2.close();
  }
  // o.unknown may not hide an action the host serves, or one with a stub
  for (const [phase, name] of [['P3', 'tapReceiveStart'], ['P3', 'tapPayStart'], ['P3', 'tapPayStop'], ['P3', 'nostrSend'], ['P3', 'nope']]) {
    const h = await boot({ seed: true, tor: 'up', phase, unknown: new Set([name]) });
    try {
      assert.ok(h.host.violations.some((v) => v.kind === 'unknown' && v.detail.includes(name)), name + ' refused as unknown on ' + phase);
    } finally {
      h.close();
    }
  }
  // and on a P3 host, no LATER action is left without a stub (shakeStart was the example until ENG-670 made
  // it P3, tapPayStop until ENG-671 did, inboxClose until ENG-672 did, nostrSend until ENG-677 did), so
  // o.unknown has nothing to name there, and an action outside the contract altogether, posted raw, is
  // answered as the Kotlin bridge answers it, "Unknown action: …", and is itself the one violation
  const unstubbed = CONTRACT.filter((e) => e.kind === 'action' && e.phase === 'LATER' && !e.p1).map((e) => e.name);
  assert.deepEqual(unstubbed, [], 'every LATER action has a stub');
  const outside = 'nostrRelayList';
  assert.ok(!CONTRACT.some((e) => e.name === outside), outside + ' is not in contract.json');
  const p3 = await boot({ seed: true, tor: 'up', phase: 'P3' });
  try {
    await p3.settle(300);
    p3.host.violations.length = 0;
    p3.window.webkit.messageHandlers.foxy.postMessage({ action: outside, id: 'n-p3', target: 'nprofile1x', body: 'cashuBx' });
    await wait(50);
    assert.deepEqual(p3.host.answered.filter((a) => a[0] === outside).slice(-1), [[outside, null, 'Unknown action: ' + outside]]);
    assert.deepEqual(kinds(p3.host.violations), ['unknown']);
    assert.ok(p3.host.violations[0].detail.includes(outside));
  } finally {
    p3.close();
  }
});

test('the asset origin serves core/web and nothing above it', async () => {
  const s = await boot({ seed: true, tor: 'up' });
  try {
    await s.settle(500);
    const w = s.window;
    w.eval("var a = document.createElement('script'); a.src = 'qrcode.js?again'; document.head.appendChild(a)");
    w.eval("var b = document.createElement('script'); b.src = '../../../package.json'; document.head.appendChild(b)");
    await wait(300);
    assert.deepEqual(s.violations(), [], 'a relative path that climbs out answers 404 and leaves nothing: it never reaches the network');
  } finally {
    s.close();
  }
});

test('verify-core.sh passes on core/ as committed and fails, saying why, on every way core/ can be wrong', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'foxy-core-'));
  try {
    const copy = () => {
      fs.rmSync(path.join(tmp, 'core'), { recursive: true, force: true });
      fs.cpSync(path.join(ROOT, 'core'), path.join(tmp, 'core'), { recursive: true });
      fs.cpSync(path.join(ROOT, 'tools'), path.join(tmp, 'tools'), { recursive: true });
    };
    const run = () => spawnSync('sh', [path.join(tmp, 'tools', 'verify-core.sh')], { encoding: 'utf8' });
    copy();
    assert.equal(run().status, 0, 'the committed core verifies');

    const f = path.join(tmp, 'core', 'web', 'foxy-wallet.js');
    const b = fs.readFileSync(f);
    b[b.length >> 1] ^= 1;
    fs.writeFileSync(f, b);
    let r = run();
    assert.equal(r.status, 1, 'one bit flipped in foxy-wallet.js');
    assert.match(r.stderr, /foxy-wallet\.js differs/);

    copy();
    fs.writeFileSync(path.join(tmp, 'core', 'web', 'extra.js'), 'window.x = 1;');
    r = run();
    assert.equal(r.status, 1, 'an extra script');
    assert.match(r.stderr, /not in SHA256SUMS: extra\.js/);

    copy();
    fs.rmSync(path.join(tmp, 'core', 'web', 'qrcode.js'));
    r = run();
    assert.equal(r.status, 1, 'a missing file');
    assert.match(r.stderr, /missing from core\/web: qrcode\.js/);

    copy();
    fs.appendFileSync(path.join(tmp, 'core', 'SHA256SUMS'), '0'.repeat(64) + '  zzz.js\n');
    r = run();
    assert.equal(r.status, 1, 'a sums file edited to match nothing');
    assert.match(r.stderr, /missing from core\/web: zzz\.js/);
    assert.match(r.stderr, /page hash [0-9a-f]{64} is not PIN's/);

    // a link in core/web: the WebView would follow it
    copy();
    fs.symlinkSync('qrcode.js', path.join(tmp, 'core', 'web', 'link.js'));
    r = run();
    assert.equal(r.status, 1, 'a symlink');
    assert.match(r.stderr, /holds something that is not a file: link\.js/);

    // two lines swapped: reported as out of order, and only that (no false missing/extra)
    copy();
    const sums = path.join(tmp, 'core', 'SHA256SUMS');
    const lines = fs.readFileSync(sums, 'utf8').split('\n');
    [lines[1], lines[2]] = [lines[2], lines[1]];
    fs.writeFileSync(sums, lines.join('\n'));
    r = run();
    assert.equal(r.status, 1, 'sums out of order');
    assert.match(r.stderr, /not sorted as bytes/);
    assert.doesNotMatch(r.stderr, /missing from core\/web|not in SHA256SUMS/, 'no file is reported missing or extra when all are present');

    // a path listed twice
    copy();
    const once = fs.readFileSync(sums, 'utf8').split('\n');
    once.splice(2, 0, once[1]);
    fs.writeFileSync(sums, once.join('\n'));
    r = run();
    assert.equal(r.status, 1, 'a path listed twice');
    assert.match(r.stderr, /lists a path twice/);

    // a malformed line
    copy();
    fs.writeFileSync(sums, fs.readFileSync(sums, 'utf8').replace(/^([0-9a-f]{64})  /, '$1 '));
    r = run();
    assert.equal(r.status, 1, 'a malformed line');
    assert.match(r.stderr, /SHA256SUMS has a malformed line/);

    // the licence changed by one byte
    copy();
    const lic = path.join(tmp, 'core', 'UPSTREAM-LICENSE');
    const lb = fs.readFileSync(lic);
    lb[0] ^= 1;
    fs.writeFileSync(lic, lb);
    r = run();
    assert.equal(r.status, 1, 'the licence edited');
    assert.match(r.stderr, /UPSTREAM-LICENSE is missing or is not the licence PIN records/);

    // PIN's page-hash edited
    copy();
    const pinFile = path.join(tmp, 'core', 'PIN');
    const pinText = fs.readFileSync(pinFile, 'utf8');
    fs.writeFileSync(pinFile, pinText.replace(/^page-hash=([0-9a-f])/m, (m, c) => 'page-hash=' + (c === '0' ? '1' : '0')));
    r = run();
    assert.equal(r.status, 1, 'PIN page-hash edited');
    assert.match(r.stderr, /page hash [0-9a-f]{64} is not PIN's/);

    // PIN's commit not a full sha
    copy();
    fs.writeFileSync(pinFile, pinText.replace(/^commit=([0-9a-f]{12})[0-9a-f]+$/m, 'commit=$1'));
    r = run();
    assert.equal(r.status, 1, 'PIN commit shortened');
    assert.match(r.stderr, /PIN's commit is not a full sha/);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});


const IOS = process.env.FOXY_IOS_REF || '';
const pinOf = (k) => (new RegExp('^' + k + '=(.*)$', 'm').exec(fs.readFileSync(path.join(ROOT, 'core', 'PIN'), 'utf8')) || [])[1];

/* A scratch copy of tools/ and core/ to run the scripts on, so the repository's own core/ is never touched. */
function scratch() {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'foxy-core-'));
  fs.cpSync(path.join(ROOT, 'core'), path.join(tmp, 'core'), { recursive: true });
  fs.cpSync(path.join(ROOT, 'tools'), path.join(tmp, 'tools'), { recursive: true });
  return tmp;
}
const git = (cwd, ...a) => spawnSync('git', a, { cwd, encoding: 'utf8', env: { ...process.env, GIT_CONFIG_NOSYSTEM: '1', GIT_AUTHOR_NAME: 't', GIT_AUTHOR_EMAIL: 't@t', GIT_COMMITTER_NAME: 't', GIT_COMMITTER_EMAIL: 't@t' } });

test('verify-core.sh --against: the committed core is Web/ at PIN, and a flipped byte is not', { skip: !IOS && 'FOXY_IOS_REF not set' }, () => {
  const tmp = scratch();
  try {
    const run = () => spawnSync('sh', [path.join(tmp, 'tools', 'verify-core.sh'), '--against', IOS], { encoding: 'utf8' });
    let r = run();
    assert.equal(r.status, 0, r.stderr);
    // flip a byte and re-pin the sums and PIN to match, so only --against can tell
    const f = path.join(tmp, 'core', 'web', 'foxy-wallet.js');
    const b = fs.readFileSync(f);
    b[b.length >> 1] ^= 1;
    fs.writeFileSync(f, b);
    const sha = (x) => require('crypto').createHash('sha256').update(x).digest('hex');
    const sums = path.join(tmp, 'core', 'SHA256SUMS');
    const text = fs.readFileSync(sums, 'utf8').replace(/^[0-9a-f]{64}(  foxy-wallet\.js)$/m, sha(b) + '$1');
    fs.writeFileSync(sums, text);
    const pinFile = path.join(tmp, 'core', 'PIN');
    fs.writeFileSync(pinFile, fs.readFileSync(pinFile, 'utf8').replace(/^page-hash=.*$/m, 'page-hash=' + sha(text)));
    r = run();
    assert.equal(r.status, 1, 'a byte flipped, with sums and PIN made to agree');
    assert.match(r.stderr, /core\/web is not Web\/ at [0-9a-f]{40}/);
    assert.doesNotMatch(r.stderr, /differs|page hash/, 'the offline checks were satisfied; only --against caught it');
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test('sync-core.sh at PIN\'s commit reproduces core/ byte for byte, and verify-core.sh then passes', { skip: !IOS && 'FOXY_IOS_REF not set' }, () => {
  const tmp = scratch();
  try {
    // a clone whose origin is the repository PIN names, as sync-core records it
    const src = path.join(tmp, 'ios');
    fs.mkdirSync(src);
    assert.equal(git(src, 'init', '--quiet').status, 0);
    const fetched = git(src, 'fetch', '--quiet', '--depth', '1', IOS, pinOf('commit'));
    assert.equal(fetched.status, 0, fetched.stderr);
    assert.equal(git(src, 'remote', 'add', 'origin', pinOf('repo')).status, 0);
    fs.rmSync(path.join(tmp, 'core'), { recursive: true, force: true });
    const r = spawnSync('sh', [path.join(tmp, 'tools', 'sync-core.sh'), pinOf('commit'), src], { encoding: 'utf8' });
    assert.equal(r.status, 0, r.stderr);
    const d = spawnSync('diff', ['-r', path.join(ROOT, 'core'), path.join(tmp, 'core')], { encoding: 'utf8' });
    assert.equal(d.status, 0, 'core/ as synced differs from core/ as committed:\n' + d.stdout.slice(0, 2000));
    const v = spawnSync('sh', [path.join(tmp, 'tools', 'verify-core.sh')], { encoding: 'utf8' });
    assert.equal(v.status, 0, v.stderr);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test('sync-core.sh refuses a Web/ holding dot-files, and leaves core/ as it was', () => {
  const tmp = scratch();
  try {
    const src = path.join(tmp, 'upstream');
    fs.mkdirSync(path.join(src, 'Web', '.cache'), { recursive: true });
    fs.writeFileSync(path.join(src, 'Web', 'index.html'), '<!doctype html>\n');
    fs.writeFileSync(path.join(src, 'Web', '.DS_Store'), 'x');
    fs.writeFileSync(path.join(src, 'Web', '.cache', 'a'), 'x');
    fs.writeFileSync(path.join(src, 'LICENSE'), 'MIT\n');
    assert.equal(git(src, 'init', '--quiet').status, 0);
    assert.equal(git(src, 'add', '.').status, 0);
    assert.equal(git(src, 'commit', '--quiet', '-m', 'x').status, 0);
    const commit = git(src, 'rev-parse', 'HEAD').stdout.trim();
    const r = spawnSync('sh', [path.join(tmp, 'tools', 'sync-core.sh'), commit, src], { encoding: 'utf8' });
    assert.equal(r.status, 1, 'refused');
    assert.match(r.stderr, /holds dot-files[^\n]*\.DS_Store/);
    assert.match(r.stderr, /\.cache/);
    const d = spawnSync('diff', ['-r', path.join(ROOT, 'core'), path.join(tmp, 'core')], { encoding: 'utf8' });
    assert.equal(d.status, 0, 'core/ untouched');
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

/* ENG-666: the fake host's card owner key and signed time are the phone's. A fake that
 * derived another key, or signed for a label Android refuses, would make every card
 * flow a test of nothing, so lib/card-owner.js is held here to the same vectors the
 * Kotlin is (app/src/test/resources/fixtures: iOS's card-owner-vectors.json and the
 * card's own owner.json), to CardOwner.kt's six labels, and to the page's time key. */
test('card-owner.js: the owner key and its signatures are the iOS vectors, the labels CardOwner.kt\'s, the time key the page\'s', () => {
  const CardOwner = require('../lib/card-owner');
  const FIX = path.join(ROOT, 'app', 'src', 'test', 'resources', 'fixtures');
  const V = JSON.parse(fs.readFileSync(path.join(FIX, 'card-owner-vectors.json'), 'utf8'));
  assert.equal(CardOwner.N.toString(16), V.n);
  assert.equal(V.cases.length, 3);
  let signed = 0;
  for (const c of V.cases) {
    const seed = c.mnemonic ? CardOwner.seedOf(c.mnemonic) : Buffer.from(c.seed, 'hex');
    assert.equal(seed.toString('hex'), c.seed, 'BIP-39, as Node worked it out');
    const d = CardOwner.scalar(seed, Buffer.from(c.cardKey, 'hex'));
    assert.equal(d.toString('hex'), c.scalar);
    assert.equal(CardOwner.publicKey(d).toString('hex'), c.pub);
    const [text, error] = CardOwner.ownerKey({ key: c.cardKey.toUpperCase() }, seed);
    assert.equal(error, null);
    assert.equal(text, '{"pub":"' + c.pub + '"}');
    assert.deepEqual(c.signatures.map((x) => x.label), CardOwner.LABELS);
    for (const s of c.signatures) {
      const m = { key: c.cardKey, label: s.label, nonce: s.nonce, value: s.value };
      const r = CardOwner.signRequest(m);
      assert.ok(r, s.label);
      assert.equal(CardOwner.message(r.label, r.nonce, r.value).toString('hex'), s.message);
      assert.ok(CardOwner.verify(c.pub, Buffer.from(s.message, 'hex'), s.sig), 'Node\'s ' + s.label);
      const ours = JSON.parse(CardOwner.ownerSign(m, seed)[0]).sig;
      assert.ok(CardOwner.verify(c.pub, Buffer.from(s.message, 'hex'), ours), 'ours, ' + s.label);
      assert.equal(CardOwner.verify(c.pub, Buffer.concat([Buffer.from(s.message, 'hex'), Buffer.from([0])]), ours), false, 'and not another message');
      signed += 1;
    }
  }
  assert.equal(signed, 18);
  for (const r of V.reductions) assert.equal((BigInt('0x' + r.hash) % CardOwner.N).toString(16).padStart(64, '0'), r.reduced);
  // the card's own proofs: the six verify over the message this signs; lock and tag are refused
  const card = JSON.parse(fs.readFileSync(path.join(FIX, 'card-owner-proofs.json'), 'utf8'));
  const refused = [];
  for (const p of card.proofs) {
    const label = p.label.replace(/^FoxyCard\//, '');
    const r = CardOwner.signRequest({ key: V.cases[0].cardKey, label, nonce: p.nonce, value: p.value });
    if (!r) { refused.push(label); continue; }
    assert.ok(CardOwner.verify(card.ownerKey, CardOwner.message(r.label, r.nonce, r.value), p.signature), p.label);
  }
  assert.deepEqual(refused, ['lock', 'tag']);
  // the labels are CardOwner.kt's, in its order
  const kt = fs.readFileSync(path.join(ROOT, 'app', 'src', 'main', 'java', 'io', 'islandbitcoin', 'foxy', 'keychain', 'CardOwner.kt'), 'utf8');
  const enumBody = kt.slice(kt.indexOf('enum class Label'), kt.indexOf('fun accepts'));
  assert.deepEqual([...enumBody.matchAll(/^\s+[A-Z_]+\("([a-z-]+)"\)/gm)].map((m) => m[1]), CardOwner.LABELS);
  // and refusals, before anything is derived, with no seed or with one
  const seed = CardOwner.seedOf('abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about');
  const key = V.cases[0].cardKey, nonce = '000102030405060708090a0b0c0d0e0f';
  for (const bad of [{ label: 'lock' }, { label: 'tag', value: '01' }, { label: 'time' }, { label: 'FoxyCard/load' }, { label: 'change-pin', value: '313233' },
    { label: 'set-limit', value: '00' }, { label: 'load', value: '00' }, { label: 'reset', value: '00' }, { nonce: 'ab' }, { key: '04' + key.slice(2) },
    { value: undefined }, { nonce: 'é'.repeat(16) }]) {
    const m = Object.assign({ key, label: 'load', nonce, value: '' }, bad);
    assert.deepEqual(CardOwner.ownerSign(m, seed), [null, 'bad request'], JSON.stringify(bad));
    assert.deepEqual(CardOwner.ownerSign(m, null), [null, 'bad request'], 'refused before the seed: ' + JSON.stringify(bad));
  }
  assert.deepEqual(CardOwner.ownerKey({ key: '04' + key.slice(2) }, seed), [null, 'bad request']);
  assert.deepEqual(CardOwner.ownerKey({ key }, null), [null, 'no seed']);
  // the time: the page's key, the four bytes big-endian, and a clock no card can be told
  const page = fs.readFileSync(path.join(ROOT, 'core', 'web', 'foxy-wallet.js'), 'utf8');
  assert.equal(/CARD_TIME_KEY = '([0-9a-f]+)'/.exec(page)[1], CardOwner.TIME_PUBLIC);
  const [t] = CardOwner.cardTime(1800000000999);
  const j = JSON.parse(t);
  assert.deepEqual(Object.keys(j).sort(), ['sig', 'time']);
  assert.equal(j.time, 1800000000);
  assert.equal(CardOwner.timeMessage(1800000000).toString('hex'), '466f7879436172642f74696d65' + '6b49d200');
  assert.ok(CardOwner.verify(CardOwner.TIME_PUBLIC, CardOwner.timeMessage(1800000000), j.sig));
  assert.equal(CardOwner.verify(CardOwner.TIME_PUBLIC, CardOwner.timeMessage(1800000001), j.sig), false);
  assert.deepEqual(CardOwner.cardTime(-1), [null, CardOwner.UNTELLABLE]);
  assert.deepEqual(CardOwner.cardTime(4294967296000), [null, CardOwner.UNTELLABLE]);
});

/* ENG-667: the fake host's card session is Android's. A fake gate that let more through
 * than CardGate.kt, or less, or said other words, would make the card flows a test of
 * something else, so lib/card-host.js is held here to the Kotlin source (CardGate.kt's
 * applets and instructions, CardLink.kt's words, CardBridge.kt's refusals) and to the
 * page's own CARD_AID and CARD_INS, and walked over every class, instruction and P1.
 * ENG-682: the gate carries card protocol 2.0's applet and REFRESH (53) beside the
 * pinned page's 1.x applet and 31; the page sends every one of those 31. */
test('card-host.js: the gate is CardGate.kt\'s and the page\'s, every byte, and the words CardLink.kt\'s', () => {
  const CardHost = require('../lib/card-host');
  const KT = path.join(ROOT, 'app', 'src', 'main', 'java', 'io', 'islandbitcoin', 'foxy');
  const gateKt = fs.readFileSync(path.join(KT, 'flashcard', 'CardGate.kt'), 'utf8');
  const linkKt = fs.readFileSync(path.join(KT, 'flashcard', 'CardLink.kt'), 'utf8');
  const bridgeKt = fs.readFileSync(path.join(KT, 'bridge', 'CardBridge.kt'), 'utf8');
  const listed = /val INSTRUCTIONS: Set<Int> = setOf\(([\s\S]*?)\n    \)/.exec(gateKt)[1].replace(/\/\/.*$/gm, '');
  const kotlin = new Set(Array.from(listed.matchAll(/0x([0-9A-Fa-f]{2})/g), (m) => parseInt(m[1], 16)));
  assert.equal(kotlin.size, 32);
  assert.deepEqual([...CardHost.INSTRUCTIONS].sort((a, b) => a - b), [...kotlin].sort((a, b) => a - b), 'CardGate.kt\'s 32');
  const ktHex = (name) => Array.from(new RegExp('val ' + name + ': ByteArray = byteArrayOf\\((.*)\\)$', 'm').exec(gateKt)[1].matchAll(/0x([0-9A-Fa-f]{2})/g), (m) => m[1].toLowerCase()).join('');
  assert.equal(ktHex('CASHU_APPLET'), CardHost.CASHU_APPLET, 'CardGate.kt\'s 2.0 applet');
  assert.equal(ktHex('FOXY_APPLET'), CardHost.FOXY_APPLET, 'CardGate.kt\'s 1.x applet');
  assert.ok(!CardHost.INSTRUCTIONS.has(0x52), 'no SET_TAG, as upstream');
  const page = fs.readFileSync(path.join(ROOT, 'core', 'web', 'foxy-wallet.js'), 'utf8');
  assert.ok(page.includes("var CARD_AID = '" + CardHost.FOXY_APPLET + "';"), 'the page\'s applet: 1.x\'s until the v0.1.3 re-pin');
  const table = /var CARD_INS = \{([\s\S]*?)\};/.exec(page)[1];
  const pageIns = new Set(Array.from(table.matchAll(/\b[a-zA-Z]+: '([0-9a-f]{2})'/g), (m) => parseInt(m[1], 16)));
  assert.deepEqual([...pageIns, 0x53].sort((a, b) => a - b), [...kotlin].sort((a, b) => a - b), 'the page\'s CARD_INS, and 2.0\'s REFRESH besides');
  for (const [word, kt] of [['unavailable', 'UNAVAILABLE'], ['cancelled', 'CANCELLED'], ['timedOut', 'TIMED_OUT'], ['lost', 'LOST']]) {
    assert.ok(linkKt.includes(kt + '("' + CardHost.WORDS[word] + '")'), kt + ': ' + CardHost.WORDS[word]);
  }
  assert.ok(bridgeKt.includes('const val NOT_A_COMMAND = "' + CardHost.NOT_A_COMMAND + '"'));
  assert.ok(bridgeKt.includes('const val NOT_FOXYS = "' + CardHost.NOT_FOXYS + '"'));
  assert.ok(gateKt.includes('const val SCANNING = "' + CardHost.SCANNING + '"'));
  // every class, instruction and P1: the choosing by name, or one of the 32 under B0, nothing else
  const h = (n) => n.toString(16).padStart(2, '0');
  const hex = (s) => Buffer.from(s, 'hex');
  for (let cla = 0; cla < 256; cla++) {
    for (let ins = 0; ins < 256; ins++) {
      const c = CardHost.read(hex(h(cla) + h(ins) + '0000'));
      const want = (cla === 0xb0 && kotlin.has(ins)) ? ins : null;
      assert.equal(c, want, h(cla) + ' ' + h(ins));
    }
  }
  for (let p = 0; p < 256; p++) for (const ins of kotlin) assert.equal(CardHost.read(hex('b0' + h(ins) + h(p) + h(255 - p) + '02abcd00')), ins);
  assert.equal(CardHost.read(hex('00a404000af0464f5859434152440100')), 'select');
  assert.equal(CardHost.read(hex('00a404000bf04341534855434152440100')), 'select', '2.0\'s applet');
  for (const other of ['00a404000af043415348554341524400', '00a404000bf04341534855434152444d00', '00a4040008d27600008501020100']) {
    assert.equal(CardHost.read(hex(other)), null, other + ': the 2.0 package, its opener, Flash 0.x');
  }
  assert.equal(CardHost.read(hex('00a4040007d276000085010100')), null, 'the NFC tag applet is not chosen');
  assert.equal(CardHost.allows(hex('b011000004'), false), false, 'nothing of the applet\'s before it is chosen');
  assert.equal(CardHost.read(hex('b04000000531323334')), null, 'a length that says five and carries four');
  assert.equal(CardHost.line('one\ntwo\u0007\tthree four'), 'onetwothreefour');
  assert.equal(CardHost.line('a'.repeat(500)).length, 120);
  assert.equal(CardHost.bytes('0g'), null);
});

test('the onion doors (ENG-672): the fake host answers as DeliveryBridge does, and refuses what it refuses', async () => {
  // the payer's answer, byte for byte the strings DeliveryBridgeTest holds Kotlin to
  assert.equal(answerBody(200, 'ignored'), '{"ok":true}');
  assert.equal(answerBody(422, 'Token already spent / "twice"'), '{"error":"Token already spent \\/ \\"twice\\""}');
  assert.equal(answerBody(409, ''), '{"error":""}');
  const by = (n) => CONTRACT.find((e) => e.name === n);
  const h = makeHost({ phase: 'P3', tor: 'up' });
  const open = h.answerers.inboxOpen();
  assert.deepEqual(replyProblems(by('inboxOpen'), open[0], open[1]), []);
  const onion = JSON.parse(open[0]).onion;
  assert.match(onion, /^http:\/\/[a-z2-7]{55}d\.onion\/[0-9a-f]{32}$/);
  assert.deepEqual(JSON.parse(h.answerers.inboxOpen()[0]).onion, onion, 'the same address until it closes');
  // a post to an address this phone is not answering is 404, and so is one to an address it closed
  assert.equal(await h.deliver('http://' + 'a'.repeat(55) + 'd.onion/00', '{}'), '404\n');
  h.answerers.inboxClose();
  assert.equal(await h.deliver(onion, '{}'), '404\n', 'closed: not answered');
  // an answer key nobody is waiting on
  assert.deepEqual(h.answerers.inboxAnswer({ answer: 'nope', status: 200, text: '' }), [null, 'No payment is waiting on that answer.']);
  // a post: Tor only, a v3 onion only, a body
  assert.deepEqual(h.answerers.onionPost({ url: 'http://mint.example.com/', body: '{}' }), [null, 'That is not an onion address.']);
  assert.deepEqual(h.answerers.onionPost({ url: onion, body: '' }), [null, 'That payment is too large to send.']);
  assert.deepEqual(h.answerers.onionPost({ url: onion, body: '{}' }), [null, 'Their phone could not be reached over Tor.'], 'no peer');
  const off = makeHost({ phase: 'P3', tor: 'connecting' });
  assert.deepEqual(off.answerers.inboxOpen(), [null, 'No onion address: Tor is not ready.']);
  assert.deepEqual(off.answerers.onionPost({ url: onion, body: '{}' }), [null, 'Foxy is not connected to Tor.']);
  for (const e of [h.answerers.onionPost({ url: onion, body: '' }), off.answerers.inboxOpen(), off.answerers.onionPost({ url: onion, body: '{}' })]) {
    assert.ok(by('onionPost').reply.errors.includes(e[1]) || by('inboxOpen').reply.errors.includes(e[1]), e[1] + ' is an error the contract lists');
  }
  // a P2 host may not push _requestPaid (P3)
  const p2 = makeHost({ phase: 'P2', tor: 'up' });
  p2.state.inbox = onion;
  assert.equal(await p2.deliver(onion, '{}'), '404\n');
  assert.deepEqual(kinds(p2.violations), ['unserved']);
});

test('the Nostr doors (ENG-677): the fake host answers as DeliveryBridge and NostrDelivery do, and its nprofile is NIP-19\'s', async () => {
  const { encode, decode, nprofile, readNprofile } = require('../lib/nprofile');
  const { INBOX_RELAYS, FALLBACK_RELAYS } = require('../lib/host');
  // NIP-19's own worked example, written and read; and BIP-173's strings, re-encoded
  const example = 'nprofile1qqsrhuxx8l9ex335q7he0f09aej04zpazpl0ne2cgukyawd24mayt8gpp4mhxue69uhhytnc9e3k7mgpz4mhxue69uhkg6nzv9ejuumpv34kytnrdaksjlyr9p';
  assert.equal(nprofile('3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d', ['wss://r.x.com', 'wss://djbas.sadkb.com']), example);
  assert.deepEqual(readNprofile(example), { pubkey: '3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d', relays: ['wss://r.x.com', 'wss://djbas.sadkb.com'] });
  assert.equal(readNprofile(example.slice(0, -1) + 'q'), null, 'a wrong checksum');
  for (const v of ['abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw', 'split1checkupstagehandshakeupstreamerranterredcaperred2y9e3w']) {
    const d = decode(v);
    assert.equal(encode(d.prefix, d.bytes), v);
  }
  assert.deepEqual(INBOX_RELAYS, FALLBACK_RELAYS.slice(0, 4));

  const by = (n) => CONTRACT.find((e) => e.name === n);
  const h = makeHost({ phase: 'P3', tor: 'up' });
  const open = h.answerers.inboxOpen();
  assert.deepEqual(replyProblems(by('inboxOpen'), open[0], open[1]), []);
  const got = JSON.parse(open[0]);
  assert.deepEqual(Object.keys(got), ['onion', 'nostr'], 'the onion first, then the key, as iOS joins them');
  assert.equal(readNprofile(got.nostr).pubkey, h.state.nostr.key);
  assert.deepEqual(readNprofile(got.nostr).relays, INBOX_RELAYS);
  assert.equal(JSON.parse(h.answerers.inboxOpen()[0]).nostr, got.nostr, 'the same key until it closes');
  // a payment for this key reaches the page; one for another key, or after the close, does not
  assert.equal(h.deliverNostr('00'.repeat(32), '{}'), false);
  assert.equal(h.deliverNostr(h.state.nostr.key, '{}'), true);
  assert.deepEqual(h.pushes, ['_requestPaid']);
  const key = h.state.nostr.key;
  h.answerers.inboxClose();
  assert.equal(h.state.nostr, null);
  assert.equal(h.deliverNostr(key, '{}'), false, 'closed: not heard');
  assert.notEqual(JSON.parse(h.answerers.inboxOpen()[0]).nostr, got.nostr, 'a fresh key for the next request');
  // nobody waits on a Nostr payment's answer
  assert.deepEqual(h.answerers.inboxAnswer({ answer: '', status: 200, text: '' }), [null, 'No payment is waiting on that answer.']);
  // no relay listening: the onion alone
  const deaf = makeHost({ phase: 'P3', tor: 'up', nostrDeaf: true });
  assert.deepEqual(Object.keys(JSON.parse(deaf.answerers.inboxOpen()[0])), ['onion']);

  // nostrSend: DeliveryBridge's refusals, then how many relays took it
  const target = nprofile('11'.repeat(32), ['wss://one.example', 'ws://plain.example', 'wss://one.example', 'wss://two.example']);
  assert.deepEqual(h.answerers.nostrSend({ body: '{}' }), [null, 'That request names no Nostr address.']);
  assert.deepEqual(h.answerers.nostrSend({ target: '', body: '{}' }), [null, 'That request names no Nostr address.']);
  assert.deepEqual(h.answerers.nostrSend({ target, body: '' }), [null, 'That payment is too large to send over Nostr.']);
  assert.deepEqual(h.answerers.nostrSend({ target, body: 'a'.repeat(32768) }), [null, 'That payment is too large to send over Nostr.']);
  assert.deepEqual(h.answerers.nostrSend({ target, body: 'é'.repeat(16384) }), [null, 'That payment is too large to send over Nostr.']);
  assert.deepEqual(h.answerers.nostrSend({ target: 'nprofile1bent', body: '{}' }), [null, 'The payment could not be sent over Nostr.']);
  assert.deepEqual(h.answerers.nostrSend({ target, body: 'a'.repeat(32767) }), ['2', null], 'two wss relays, each once');
  assert.deepEqual(h.answerers.nostrSend({ target: nprofile('11'.repeat(32), []), body: '{}' }), ['4', null], 'none named: the fallbacks');
  const off = makeHost({ phase: 'P3', tor: 'connecting' });
  assert.deepEqual(off.answerers.nostrSend({ target, body: '{}' }), [null, 'No relay took the payment: Foxy is not connected to Tor.']);
  const errors = by('nostrSend').reply.errors;
  for (const e of ['That request names no Nostr address.', 'That payment is too large to send over Nostr.', 'The payment could not be sent over Nostr.']) {
    assert.ok(errors.includes(e), e + ' is an error the contract lists');
  }
  assert.ok(errors.some((e) => e.startsWith('No relay took the payment: ')));
  assert.equal(by('nostrSend').phase, 'P3');
  // a P2 host may not push _requestPaid (P3)
  const p2 = makeHost({ phase: 'P2', tor: 'up' });
  p2.state.nostr = { key: '22'.repeat(32), nprofile: '' };
  assert.equal(p2.deliverNostr('22'.repeat(32), '{}'), false);
  assert.deepEqual(kinds(p2.violations), ['unserved']);
});
