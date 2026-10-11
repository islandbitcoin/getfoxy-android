'use strict';
/* harness.test.js — the checks can say no.
 *
 * A suite that passes is only worth something if it fails when the thing it
 * guards is broken. Each test here breaks one thing on purpose — an action the
 * contract does not name, a field too many or too few, a value a string bridge
 * cannot carry, a request straight from the page, a URL left in the DOM, an
 * answer outside the contract, a byte flipped in core/ — and asserts the
 * harness catches it.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');
const { boot, wait } = require('../lib/session');
const { check, validateSchema } = require('../lib/schema');
const { replyProblems, CONTRACT } = require('../lib/host');

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
  } finally {
    s.close();
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

test('verify-core.sh passes on core/ as committed and fails on a flipped byte, an extra file and a missing one', () => {
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
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});
