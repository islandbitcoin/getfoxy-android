'use strict';
/* contract.test.js — contract.json is well formed, true to the pinned iOS
 * commit, and complete in both directions.
 *
 * Both directions, as the iOS repository's tests/bridge-matrix.js does it:
 * from the page outwards (every action the shipped page can post is in the
 * contract) and from native outwards (every action FoxyBridge.handlers serves
 * is in the contract, checked when FOXY_IOS_REF names a clone of getfoxy/iOS).
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const { validateSchema } = require('../lib/schema');
const { checkRefs, checkDocRefs, pin } = require('../lib/refs');
const { upToDate } = require('../tools/contract-table');
const { replyProblems } = require('../lib/host');

const CONTRACT = require('../contract.json');
const ROOT = path.join(__dirname, '..', '..');
const WEB = path.join(ROOT, 'core', 'web');
const IOS = process.env.FOXY_IOS_REF || '';

const PHASES = new Set(['P1', 'P2', 'P3', 'LATER']);
const KINDS = { 'page->native': new Set(['action', 'message']), 'native->page': new Set(['push', 'global', 'script', 'read']) };
const FORMATS = new Set(['text', 'json', 'json-array', 'json-or-text', 'status-body', 'none']);

test('contract.json: an array of entries, each with what docs/BRIDGE.md promises', () => {
  assert.ok(Array.isArray(CONTRACT), 'contract.json is an array');
  const names = new Set();
  for (const e of CONTRACT) {
    for (const k of ['name', 'direction', 'kind', 'domain', 'phase', 'payloadSchema', 'reply', 'iosRef', 'pageRef', 'behaviour']) {
      assert.ok(k in e, e.name + ' has ' + k);
    }
    assert.ok(!names.has(e.name), e.name + ' is listed once');
    names.add(e.name);
    assert.ok(KINDS[e.direction], e.name + ': direction ' + e.direction);
    assert.ok(KINDS[e.direction].has(e.kind), e.name + ': kind ' + e.kind + ' with direction ' + e.direction);
    assert.ok(PHASES.has(e.phase), e.name + ': phase ' + e.phase);
    validateSchema(e.payloadSchema);
    assert.ok(FORMATS.has(e.reply.format), e.name + ': reply format ' + e.reply.format);
    if (e.reply.json) validateSchema(e.reply.json);
    if (e.reply.items && typeof e.reply.items === 'object') validateSchema(e.reply.items);
    assert.ok(Array.isArray(e.iosRef) && e.iosRef.length > 0, e.name + ' names where iOS does it');
    if (e.kind === 'action') {
      assert.equal(e.payloadSchema.fields.action, 'string', e.name + ' carries action');
      assert.equal(e.payloadSchema.fields.id, 'string', e.name + ' carries id');
      assert.match(e.reply.via, /_scanResult/, e.name + ' is answered through _scanResult');
    }
  }
});

test('contract.json: every p1 stub is on a later-phase action and is an answer its reply allows', () => {
  const stubbed = CONTRACT.filter((e) => 'p1' in e);
  assert.ok(stubbed.length > 0, 'some entries carry a p1 stub');
  for (const e of stubbed) {
    assert.equal(e.kind, 'action', e.name + ': only an action is answered, so only an action has a p1 stub');
    assert.notEqual(e.phase, 'P1', e.name + ': a P1 action is served in P1, not stubbed');
    assert.deepEqual(Object.keys(e.p1).sort(), ['error', 'text', 'why'], e.name + ': p1 is { text, error, why }');
    assert.ok((e.p1.text === null) !== (e.p1.error === null), e.name + ': p1 gives a text or an error, not both');
    assert.ok(typeof e.p1.why === 'string' && e.p1.why.length > 0, e.name + ': p1 says why');
    assert.deepEqual(replyProblems(e, e.p1.text, e.p1.error), [], e.name + ': p1 fits the reply');
    if (e.p1.error !== null) assert.ok(e.reply.errors.includes(e.p1.error), e.name + ': p1 error is one the contract lists');
  }
  // what ROUTE.md says P1 answers: the refusal, for every action that needs a route
  for (const n of ['price', 'candles', 'mintRequest']) {
    assert.equal(CONTRACT.find((e) => e.name === n).p1.error, 'Foxy is not connected to Tor.', n + ' answers the refusal in P1');
  }
});

test('contract.json: every iOS file:line is real at the pinned commit', (t) => {
  const r = checkRefs(CONTRACT, IOS || null);
  assert.deepEqual(r.problems, []);
  assert.ok(r.checked > 0);
  if (!IOS) t.diagnostic(r.skipped + ' refs outside Web/ not checked here: set FOXY_IOS_REF to a clone of ' + pin().repo + ' (CI does)');
  else t.diagnostic(r.checked + ' refs checked against ' + pin().commit.slice(0, 12));
});

/* Every action name the shipped page can post, read from the files themselves. */
function pageActions() {
  const found = new Map();
  const add = (name, where) => { if (!found.has(name)) found.set(name, where); };
  for (const f of ['foxy-wallet.js', 'foxy-tor-gate.js', 'foxy-send-progress.js', 'foxy-receive-progress.js', 'foxy-move-progress.js', 'index.html']) {
    const src = fs.readFileSync(path.join(WEB, f), 'utf8');
    for (const m of src.matchAll(/(?:bridgeAsk|nativeJson|bridgeSeed)\(\s*'([A-Za-z][A-Za-z0-9]*)'/g)) add(m[1], f);
    for (const m of src.matchAll(/(?:bridgeAsk|nativeJson)\(\s*[A-Za-z_]+\s*\?\s*'([A-Za-z][A-Za-z0-9]*)'\s*:\s*'([A-Za-z][A-Za-z0-9]*)'/g)) { add(m[1], f); add(m[2], f); }
    for (const m of src.matchAll(/action:\s*'([A-Za-z][A-Za-z0-9]*)'/g)) add(m[1], f);
  }
  return found;
}

test('the page asks for nothing the contract does not list', () => {
  const asked = pageActions();
  const actions = new Set(CONTRACT.filter((e) => e.kind === 'action').map((e) => e.name));
  // what the page names but refuses itself, never posting it (bridgeSeed's WORD_ACTIONS)
  const refusedByPage = new Set(['seedRead', 'seedWrite', 'seedDelete', 'seedSecrets']);
  const missing = [...asked.keys()].filter((a) => !actions.has(a) && !refusedByPage.has(a));
  assert.deepEqual(missing, [], 'posted by the page and not in contract.json');
  assert.ok(asked.size > 50, 'the page scan found ' + asked.size + ' actions');
});

test('every action in the contract is one the page asks for, or says why not', () => {
  const asked = pageActions();
  const unasked = CONTRACT.filter((e) => e.kind === 'action' && !asked.has(e.name)).map((e) => e.name);
  // isDebug: served by iOS, asked by nobody at the pinned commit (the page reads window.FOXY_DEBUG)
  assert.deepEqual(unasked, ['isDebug']);
  for (const name of unasked) assert.deepEqual(CONTRACT.find((e) => e.name === name).pageRef, [], name + ' has no caller to cite');
});

test('the two actionless messages are the ones the shipped page posts', () => {
  const html = fs.readFileSync(path.join(WEB, 'index.html'), 'utf8');
  assert.match(html, /postMessage\(\{ haptic: kind \}\)/);
  assert.match(html, /postMessage\(\{ haptic: 'pulse' \}\)/);
  assert.match(html, /bridge\.foxy\.postMessage\(payload\)/);
  assert.deepEqual(CONTRACT.filter((e) => e.kind === 'message').map((e) => e.name).sort(), ['haptic', 'username']);
});

test('every push the contract names is defined by the shipped page', () => {
  const wallet = fs.readFileSync(path.join(WEB, 'foxy-wallet.js'), 'utf8');
  const gate = fs.readFileSync(path.join(WEB, 'foxy-tor-gate.js'), 'utf8');
  const html = fs.readFileSync(path.join(WEB, 'index.html'), 'utf8');
  // Pushes the app puts on the wallet at boot rather than the wallet declaring them
  // (build/app/06-wallet-boot-and-restore.js), each with the assignment index.html ships.
  const SET_BY_APP = { _cardTapped: /W\._cardTapped = \(\) => this\.cardTapped\(\);/ };
  for (const e of CONTRACT.filter((x) => x.kind === 'push' || x.kind === 'read')) {
    if (e.name in SET_BY_APP) {
      assert.match(html, SET_BY_APP[e.name], e.name + ' is put on FoxyWallet by the app in index.html');
      assert.ok(!new RegExp('\\n    ' + e.name + ': ').test(wallet), e.name + ' is not also a member in foxy-wallet.js: take it off SET_BY_APP');
      continue;
    }
    assert.ok(new RegExp('\\n    ' + e.name + ': ').test(wallet), e.name + ' is a FoxyWallet member in foxy-wallet.js');
  }
  for (const name of Object.keys(SET_BY_APP)) {
    assert.ok(CONTRACT.some((e) => e.name === name && e.kind === 'push'), name + ' (SET_BY_APP) is a push in contract.json');
  }
  assert.match(gate, /window\.__foxyBluetoothAnswer = function/);
  assert.match(wallet, /window\.__foxyUptime/);
});

test('against the iOS source: the contract lists exactly the actions FoxyBridge.handlers serves', { skip: !IOS && 'FOXY_IOS_REF not set' }, () => {
  const swift = execFileSync('git', ['-C', IOS, 'show', pin().commit + ':Foxy/Bridge/FoxyBridge.swift'], { encoding: 'utf8' });
  const served = [...swift.matchAll(/"([A-Za-z][A-Za-z0-9]*)": FoxyBridge\.([A-Za-z][A-Za-z0-9]*),/g)].map((m) => m[1]).sort();
  const listed = CONTRACT.filter((e) => e.kind === 'action').map((e) => e.name).sort();
  assert.deepEqual(listed, served);
  const seed = execFileSync('git', ['-C', IOS, 'show', pin().commit + ':Foxy/Bridge/NativeSeedBridge.swift'], { encoding: 'utf8' });
  const seedActions = [...(/nativeSeedActions: Set<String> = \[([^\]]*)\]/.exec(seed) || ['', ''])[1].matchAll(/"([A-Za-z0-9]+)"/g)].map((m) => m[1]);
  assert.ok(seedActions.length > 15);
  for (const a of seedActions) assert.ok(listed.includes(a), a + ' (nativeSeedActions) is in the contract');
});

test('against the iOS source: every evaluateJavaScript target is a push in the contract', { skip: !IOS && 'FOXY_IOS_REF not set' }, () => {
  const files = execFileSync('git', ['-C', IOS, 'ls-tree', '-r', '--name-only', pin().commit, 'Foxy'], { encoding: 'utf8' }).split('\n').filter((f) => f.endsWith('.swift'));
  const named = new Set();
  for (const f of files) {
    const src = execFileSync('git', ['-C', IOS, 'show', pin().commit + ':' + f], { encoding: 'utf8' });
    for (const m of src.matchAll(/window\.FoxyWallet\.(_[A-Za-z]+)/g)) named.add(m[1]);
    for (const m of src.matchAll(/window\.(__foxy[A-Za-z0-9]+)/g)) named.add(m[1]);
  }
  const pushes = new Set(CONTRACT.filter((e) => e.direction === 'native->page').map((e) => e.name));
  const missing = [...named].filter((n) => !pushes.has(n));
  assert.deepEqual(missing, [], 'native evaluates into the page and the contract does not say so');
});

test('docs: every iOS file:line the documents cite is real at the pinned commit', (t) => {
  const dir = path.join(ROOT, 'docs');
  let checked = 0, skipped = 0;
  const problems = [];
  for (const f of fs.readdirSync(dir).filter((x) => x.endsWith('.md'))) {
    const r = checkDocRefs(fs.readFileSync(path.join(dir, f), 'utf8'), IOS || null, 'docs/' + f);
    checked += r.checked; skipped += r.skipped; problems.push(...r.problems);
  }
  assert.deepEqual(problems, []);
  t.diagnostic(checked + ' refs in docs/ checked' + (skipped ? ', ' + skipped + ' need FOXY_IOS_REF' : ''));
});

test('docs/BRIDGE.md states the counts contract.json has', () => {
  const doc = fs.readFileSync(path.join(ROOT, 'docs', 'BRIDGE.md'), 'utf8');
  const n = (f) => CONTRACT.filter(f).length;
  const actions = n((e) => e.kind === 'action');
  const messages = n((e) => e.kind === 'message');
  const toPage = n((e) => e.direction === 'native->page');
  assert.ok(doc.includes(actions + ' actions and ' + messages + ' actionless messages'), actions + ' actions, ' + messages + ' messages');
  assert.ok(doc.includes('(' + toPage + ' native → page items)'), toPage + ' native → page');
  assert.ok(doc.includes(CONTRACT.length + '\ncontract entries') || doc.includes(CONTRACT.length + ' contract entries'), CONTRACT.length + ' entries');
});

test('docs/BRIDGE.md ends with the table contract.json generates', () => {
  assert.ok(upToDate(), 'run: node conformance/tools/contract-table.js --write');
});
