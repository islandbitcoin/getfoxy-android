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
