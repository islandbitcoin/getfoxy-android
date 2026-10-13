'use strict';
/* flows.test.js — the shipped page, booted and driven, against the contract.
 *
 * Each flow loads core/web/index.html as it ships (lib/page.js), with the fake
 * native side (lib/host.js) answering as the contract says. After every flow:
 *
 *   - no violation: every message the page posted is in contract.json with a
 *     payload of the shape it gives, and survived the string bridge;
 *   - the network sentinel saw nothing: no fetch, XHR, WebSocket, beacon,
 *     WebRTC, subresource or DOM URL outside the asset origin;
 *   - every answer the fake host gave fits the contract's reply.
 *
 * The flows drive the page the way the app and a person do: boot on a fresh
 * install and on a wallet with a seed, with Tor connecting, failing, up and
 * opted out of; a migration from page storage; the wallet's own calls for
 * everything a screen asks native for; a receive, a send and a token round
 * trip on a mint made of cashu-ts; and the later-phase doors (tap, inbox,
 * card), whose messages are checked even though Android serves them later.
 *
 * The last test is the coverage: every action in the contract was posted by
 * some flow, or is named in NOT_EXERCISED with the reason.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const { boot, wait } = require('../lib/session');
const { CONTRACT, snapshot } = require('../lib/host');

const seen = new Map();
const pushesSeen = new Set();
const sessions = [];

async function flow(opts, body) {
  const s = await boot(opts);
  sessions.push(s);
  let violations;
  try {
    await body(s);
    await s.settle(300);
  } finally {
    for (const [k, v] of s.host.asked) seen.set(k, (seen.get(k) || 0) + v);
    for (const p of s.host.pushes) pushesSeen.add(p);
    violations = s.violations();
    s.close();
  }
  assert.deepEqual(violations, [], 'violations');
  assert.deepEqual(s.host.replyProblems, [], 'the fake host answered outside the contract');
  return s;
}

const routeActions = (s) => ['mintRequest', 'price', 'candles'].reduce((n, a) => n + (s.host.asked.get(a) || 0), 0);
const shows = (s, text) => s.window.document.body.textContent.includes(text);

/* No route, no request, proven over a window rather than at an instant: for
 * QUIET_MS after the page has visibly settled, poll and fail the moment it
 * asks anything that needs a route. The window is longer than the gate's own
 * re-check (watchMs, 2 s) and than the wallet's first retry, so a page that
 * asked price "a little later" with the route closed is caught here; a slower
 * machine only makes the window start later, never shorter. */
const QUIET_MS = 5000;
async function staysQuiet(s, what) {
  const end = Date.now() + QUIET_MS;
  while (Date.now() < end) {
    assert.equal(routeActions(s), 0, 'nothing that needs a route was asked ' + what);
    await wait(50);
  }
}

test('boot: a wallet with a seed, Tor up', async () => {
  const s = await flow({ seed: true, tor: 'up' }, async (s) => {
    await s.until(() => s.W.connected === true, 30000, 'the page to connect to its default mint over mintRequest');
    await s.until(() => s.host.asked.get('covered') > 0 && s.host.asked.get('price') > 0, 30000, 'covered and price');
  });
  for (const a of ['covered', 'captured', 'hostsKnown', 'seedStatus', 'privacy', 'mintRequest', 'price', 'log']) {
    assert.ok(s.host.asked.get(a) > 0, 'boot asked ' + a);
  }
  assert.ok(s.host.asked.get('username') > 0, 'the actionless {username} message was posted and ignored');
  assert.ok(s.host.mint.requests.includes('GET /v1/keys'), 'the mint was asked for its keys, through the bridge');
});

test('boot: a fresh install makes its seed on the phone', async () => {
  const s = await flow({ seed: false, tor: 'up' }, async (s) => { await s.until(() => s.host.asked.get('seedCreate') > 0, 30000, 'seedCreate'); });
  assert.ok(s.host.asked.get('seedStatus') > 0);
  assert.ok(s.host.asked.get('seedCreate') > 0, 'no seed: the page asked the phone to make one');
  assert.equal(s.host.state.seed, true);
});

test('boot: words and counters left in page storage go to the phone once', async () => {
  const words = 'abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about';
  const s = await flow({ seed: false, tor: 'up', storage: { 'foxy.seed.v1': words, 'foxy.counter.v1': JSON.stringify({ '009a1f293253e41e': 7 }) } },
    async (s) => { await s.until(() => s.host.asked.get('countersImport') > 0, 30000, 'countersImport'); });
  assert.ok(s.host.asked.get('seedMigrate') > 0, 'seedMigrate');
  assert.ok(s.host.asked.get('countersImport') > 0, 'countersImport');
  assert.equal(s.host.asked.get('seedCreate') || 0, 0, 'migrated words, so no new seed');
});

test('route closed: Tor connecting then failing, the page asks no mint, price or chart', async () => {
  await flow({ seed: true, tor: 'connecting' }, async (s) => {
    // settled into Tor connecting: the page asked privacy and shows the connecting screen
    await s.until(() => s.host.asked.get('privacy') > 0 && shows(s, 'SECURING YOUR CONNECTION'), 30000, 'the connecting screen');
    await staysQuiet(s, 'while Tor was connecting');
    s.torFailed();
    // settled into Tor failed: the failure screen is up and the gate is polling privacy on it
    const before = s.host.asked.get('privacy');
    await s.until(() => shows(s, 'CANNOT CONNECT TO TOR') && s.host.asked.get('privacy') >= before + 2, 30000, 'the failed screen, re-checking');
    await staysQuiet(s, 'after Tor failed');
    // what the gate's buttons do
    await s.W.torRetry();
    await s.W.orbotRefresh();
    await s.W.orbotAccess();
    await s.W.refreshPrivacy();
    assert.equal(routeActions(s), 0, 'nor after RETRY');
    // CONTINUE WITHOUT TOR: native asks; the fake phone says yes
    await s.W.setUnprotected(true);
    assert.equal(s.host.state.unprotected, true);
    await s.until(() => routeActions(s) > 0, 30000, 'the page to go on once the person opened the route');
    await s.W.setUnprotected(false);
    assert.equal(s.host.state.unprotected, false);
  });
});

test('P1: a host serving only P1 actions and the p1 stubs boots to a defined state, and nothing leaves', async () => {
  // The flow to run against the Kotlin bridge in P1 (docs/BRIDGE.md §2, P1 answers):
  // a later-phase action with no p1 stub is an 'unserved' violation and fails it.
  const s = await flow({ seed: true, phase: 'P1' }, async (s) => {
    const W = s.W, G = s.window.FoxyGate;
    // no Tor in P1: the gate settles on CANNOT CONNECT TO TOR, re-checking privacy, offering PROCEED OFFLINE
    await s.until(() => shows(s, 'CANNOT CONNECT TO TOR') && shows(s, 'PROCEED OFFLINE') && s.host.asked.get('privacy') >= 2,
      30000, 'the no-Tor screen, re-checking');
    assert.equal(W.connected, false, 'not connected to a mint');
    await staysQuiet(s, 'on the no-Tor screen');
    // the gate's buttons, answered by their p1 stubs
    await W.torRetry();
    await W.setUnprotected(true);
    const p = W.privacy();
    assert.equal(p.tor, 'none', 'the page holds the no-Tor snapshot');
    assert.equal(p.unprotected, false, 'no unprotected path until P2');
    assert.equal(await W.orbotRefresh(), 'none');
    await W.orbotAccess();
    assert.equal(await W.captureCheck(), false);
    // PROCEED OFFLINE: the wallet itself refuses what needs a route
    G.proceedOffline();
    await assert.rejects(async () => W.rate({ fresh: true }), /offline/);
    await assert.rejects(async () => W.candles(3600, 86400), /offline/);
    await staysQuiet(s, 'working offline');
  });
  for (const a of ['hostsKnown', 'captured', 'privacy', 'seedStatus', 'torRetry', 'unprotected', 'orbotRefresh', 'orbotAccess']) {
    assert.ok(s.host.asked.get(a) > 0, 'P1 boot asked ' + a);
  }
  assert.equal(s.host.pushes.includes('_privacy'), false, 'P1 pushes no _privacy');
});

test('P2: a host serving P1 and P2 actions goes from connecting to up, mints and melts, and leans on nothing later without a stub', async () => {
  // The flow to run against the Kotlin bridge in P2: P1 and P2 served, the P3 and
  // LATER actions answered by their p1 stubs, and any without a stub an 'unserved'
  // violation that fails it.
  const s = await flow({ seed: true, tor: 'connecting', phase: 'P2' }, async (s) => {
    const W = s.W;
    // Tor connecting: the route is closed, and the page holds to it on a P2 host too
    await s.until(() => s.host.asked.get('privacy') > 0 && shows(s, 'SECURING YOUR CONNECTION'), 30000, 'the connecting screen');
    await staysQuiet(s, 'while Tor was connecting, on a P2 host');
    // Tor up, pushed as native pushes it
    s.torUp();
    await s.until(() => W.connected === true, 30000, 'the page to connect to its default mint once Tor is up');
    // mint
    const inv = await W.invoice(64, 'conformance p2');
    assert.ok(await W.claim(inv.hash || inv.quote || inv.id), 'the invoice was claimed: minted');
    assert.ok(s.host.mint.requests.includes('POST /v1/mint/bolt11'), 'minted over mintRequest');
    // melt
    await W.pay('lnbc80n1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypq').catch(() => {});
    assert.ok(s.host.mint.requests.includes('POST /v1/melt/quote/bolt11'), 'a melt quote, over mintRequest');
    assert.ok(s.host.mint.requests.includes('POST /v1/melt/bolt11'), 'and the melt');
    // a funded wallet's home listens for a tap (LATER): the stub ends each search at once
    await assert.rejects(W.tapPay(), /Stopped\./);
    // a PASTE button on screen asks for the native control (P3), without waiting on the answer
    const pane = s.window.document.createElement('div');
    pane.getBoundingClientRect = () => ({ left: 20, top: 400, width: 300, height: 60 });
    assert.equal(W.startPasteButton(pane, () => {}), true);
    W.stopPasteButton();
    // the gate's buttons a P2 host serves itself, and the two Orbot ones it stubs (LATER on Android)
    await W.torRetry();
    assert.equal(await W.orbotRefresh(), 'none');
    await W.orbotAccess();
  });
  assert.ok(s.host.pushes.includes('_privacy'), 'a P2 host pushes _privacy');
  for (const a of ['privacy', 'hostsKnown', 'mintRequest', 'price', 'captured', 'seedStatus']) {
    assert.ok(s.host.asked.get(a) > 0, 'the P2 flow asked ' + a);
  }
});

/* The first-install screens, as a person meets them: the warning's PROCEED, then
 * the Bluetooth screen (foxy-tor-gate.js). A fresh install has neither key in page
 * storage, so the gate puts the warning up by itself. Returns the APPROVE button. */
async function toBluetoothScreen(s) {
  const d = s.window.document;
  await s.until(() => d.getElementById('ftg-warn-go'), 30000, 'the first-launch warning');
  d.getElementById('ftg-warn-go').click();
  await s.until(() => d.getElementById('ftg-bt-go'), 10000, 'the Bluetooth screen after PROCEED');
  return d.getElementById('ftg-bt-go');
}

test('P3: a host serving P1, P2 and P3 actions answers every platform door the screens ask, and leans on nothing later without a stub', async () => {
  // The flow to run against the Kotlin bridge in P3 (ENG-649): P1, P2 and P3 served,
  // the LATER actions answered by their p1 stubs, and any without a stub an 'unserved'
  // violation that fails it.
  // Tor up from the start: the P2 flow covers connecting to up on the same phase rule
  const s = await flow({ seed: true, tor: 'up', phase: 'P3' }, async (s) => {
    const W = s.W, w = s.window;
    // a fresh install: PROCEED on the warning, then APPROVE on the Bluetooth screen (ENG-656), which must close it
    (await toBluetoothScreen(s)).click();
    await s.until(() => !w.document.getElementById('ftg-bt'), 10000, 'the Bluetooth screen to close after APPROVE');
    await s.until(() => W.connected === true, 30000, 'the page to connect to its default mint');
    // the screen is not captured, and native's doors answer
    assert.equal(await W.captureCheck(), false);
    assert.equal(await W.scan('Scan an ecash token'), 'cashuBconformance');
    await W.clipboard();
    assert.equal(W.share('cashuBconformance'), true);
    assert.equal(W.notify('received'), true);
    assert.equal(await W.biometric('Unlock Foxy', true), 'yes');
    // the camera pane and the paste button, at rectangles the page measured (jsdom lays nothing out)
    const pane = w.document.createElement('div');
    pane.getBoundingClientRect = () => ({ left: 20, top: 400, width: 300, height: 200 });
    assert.equal(W.startPreview(pane, () => {}), true);
    W.stopPreview();
    const paste = w.document.createElement('div');
    paste.getBoundingClientRect = () => ({ left: 20, top: 600, width: 300, height: 60 });
    assert.equal(W.startPasteButton(paste, () => {}), true);
    W.stopPasteButton();
    await W.openSettings();
    await W.openCompany('strike');
    // the receive screen's TAP (ENG-656): "ok", with no radio, and let go
    await W.tapReceive('', true);
    await W.tapReceiveStop();
    // the seed's own screens, typed words adopted and forgotten, the protection, and the wipe
    await W.showSeedNative({ verify: true });
    const candidate = await W.enterSeedNative();
    const found = await W.scanSeed({ candidate }, [W.mintUrl]);
    await W.adoptScan(found, { candidate }).catch(() => {});
    await W.forgetSeedCandidate(candidate);
    await W.seedProtection();
    await W.setSeedProtection('device').catch(() => {});
    const app = s.logic();
    assert.equal(app.copySecret('cashuBconformance-token'), true);
    app.haptic('success', true);
    await W.wipeDevice().catch(() => {});
    await s.settle(800);
  });
  const p3 = CONTRACT.filter((e) => e.kind === 'action' && e.phase === 'P3').map((e) => e.name);
  assert.equal(p3.length, 38);
  // The page asks a card's owner key and its signed time only inside a card session, which
  // opens with cardBegin: they are asked of a P3 host, as the page's card code asks them, by
  // the flows after this one (ENG-666), and the session itself by the ENG-667 flows.
  const inACardSession = ['cardOwnerKey', 'cardOwnerSign', 'cardTime', 'cardBegin', 'cardSend', 'cardSay', 'cardAgain', 'cardEnd'];
  // And tap to pay's receiving side only over a link a payer has made, on a phone with
  // Bluetooth: asked of a P3 host by the ENG-670 flows below (IN_A_TAP_LINK).
  assert.deepEqual(p3.filter((a) => !inACardSession.includes(a) && !IN_A_TAP_LINK.includes(a) && !(s.host.asked.get(a) > 0)), [], 'every other P3 action was asked of the P3 host');
  assert.ok(s.host.asked.get('haptic') > 0, 'and a haptic');
});

/* Ask native as the page's card code does (08a-flashcard.js `cardOwnerPub`, `cardProof`,
 * `cardTold`: nativeJson over bridgeSeed): its own message shape, through the page's
 * window.webkit.messageHandlers.foxy, answered through FoxyWallet._scanResult to a
 * resolver kept in FoxyWallet._scans, as bridgeSeed keeps one. Not a flow of the page's,
 * so the coverage list (NOT_EXERCISED) still says the page posts these only in a card session. */
function askAsPage(s, action, extra) {
  const w = s.window;
  return new Promise((ok, no) => {
    const id = 's' + Date.now() + Math.random().toString(36).slice(2, 7);
    s.W._scans[id] = { ok: (t) => ok(String(t)), no: (e) => no(new Error(String((e && e.message) || e))) };
    w.webkit.messageHandlers.foxy.postMessage(Object.assign({ action, id }, extra));
  });
}

test('P3 (ENG-666): a card\'s owner key, its proofs and the signed time are answered as the page\'s card code takes them', async () => {
  // The fake phone holds abandon … about, the first case of the iOS vectors; its card key is secp256k1's G.
  const V = require('../../app/src/test/resources/fixtures/card-owner-vectors.json');
  const CardOwner = require('../lib/card-owner');
  const c = V.cases[0];
  const nonce = '000102030405060708090a0b0c0d0e0f';
  const s = await boot({ seed: true, tor: 'up', phase: 'P3', nowMs: () => 1800000000250 });
  try {
    await s.until(() => s.W.connected === true, 30000, 'the page to connect to its default mint');
    // cardOwnerPub: { key } → { pub }, which the page takes only as 04 and 128 hex
    const pub = JSON.parse(await askAsPage(s, 'cardOwnerKey', { key: c.cardKey })).pub;
    assert.match(pub, /^04[0-9a-f]{128}$/);
    assert.equal(pub, c.pub, 'the iPhone\'s owner key for the same words and card');
    // cardProof: { key, label, nonce, value } → { sig }, which the page takes as 8 to 72 bytes of hex
    for (const [label, value] of [['change-pin', '31323334'], ['set-limit', '000186a0'], ['set-owner', '04' + 'ab'.repeat(64)], ['load', ''], ['reset', '']]) {
      const sig = JSON.parse(await askAsPage(s, 'cardOwnerSign', { key: c.cardKey, label, nonce, value: String(value || '') })).sig;
      assert.match(sig, /^(?:[0-9a-f]{2}){8,72}$/);
      assert.ok(CardOwner.verify(pub, CardOwner.message(label, Buffer.from(nonce, 'hex'), Buffer.from(value, 'hex')), sig), label + ' verifies against the owner key');
    }
    await assert.rejects(askAsPage(s, 'cardOwnerSign', { key: c.cardKey, label: 'lock', nonce, value: '' }), /^Error: bad request$/, 'LOCK_CARD is never signed');
    // cardTold: {} → { time, sig }, a whole number of seconds the card checks against the page's own time key
    const told = JSON.parse(await askAsPage(s, 'cardTime', {}));
    assert.equal(told.time, 1800000000);
    assert.match(told.sig, /^(?:[0-9a-f]{2}){8,72}$/);
    assert.ok(CardOwner.verify(s.W.cardTimeKey, CardOwner.timeMessage(told.time), told.sig), 'the page\'s CARD_TIME_KEY takes it');
    for (const a of ['cardOwnerKey', 'cardOwnerSign', 'cardTime']) assert.ok(s.host.asked.get(a) > 0, a + ' asked of the P3 host');
    await s.settle(300);
  } finally {
    const violations = s.violations();
    s.close();
    assert.deepEqual(violations, [], 'violations');
    assert.deepEqual(s.host.replyProblems, [], 'the fake host answered outside the contract');
  }
});

test('the card actions on a P2 host are a violation: Android serves them from P3 (ENG-666)', async () => {
  const s = await boot({ seed: true, tor: 'up', phase: 'P2' });
  try {
    await assert.rejects(askAsPage(s, 'cardTime', {}), /Unknown action: cardTime/);
    assert.ok(s.host.violations.some((v) => v.kind === 'unserved' && v.detail.includes('cardTime')));
  } finally {
    s.close();
  }
});

/* ENG-667: a card session, as the page runs one, against Android's half of it (lib/card-host.js:
 * CardBridge.kt and CardGate.kt's rules) with iOS's own model of the card behind it
 * (lib/flashcard-card.js, getfoxy/iOS tests/flashcard-card.js at the pin). The page's own card code
 * opens the sheet (cardBegin), chooses the applet and talks to it (cardSend) through the gate, says
 * how it goes (cardSay), and closes it (cardEnd): a new card set up with an owner and a PIN, money
 * put on it and its balance read, a payment taken from it at the conformance mint, a card taken away
 * part way, and a phone that cannot read one. Every message is held to the contract as any flow's. */
const { makeCard } = require('../lib/flashcard-card');
const CardHost = require('../lib/card-host');

test('P3 (ENG-667): a card is set up, loaded, read and paid from through the card session, every command through the gate', async () => {
  // the intro film never ends in jsdom, and the custody notice would sit over home: both as already seen
  const storage = { 'foxy.intro.seen': '2026-10-12', 'foxy.custody.told': JSON.stringify({ Minibits: true }) };
  const s = await flow({ seed: true, tor: 'up', phase: 'P3', storage, p2pk: true }, async (s) => {
    const W = s.W;
    await fundedWallet(s, 3000);
    const card = makeCard({ window: s.window, format: 4 });
    s.host.state.card = card;
    const tap = (fn) => W.cardSession('Tap behind the phone.', fn);
    // set up: an owner (this phone's key for it), a PIN, the record of this mint
    await tap((link) => W.cardSetUp(link, { pin: '1234', recoverable: true }));
    assert.ok(s.host.asked.get('cardOwnerKey') > 0 && s.host.asked.get('cardOwnerSign') > 0, 'set up under the owner\'s key, from this phone\'s words');
    // money onto it, and read back
    const added = await tap((link) => W.cardAdd(link, { sats: 1000, pin: '1234' }));
    assert.equal(added.sats, 1000);
    assert.equal(card.balance(), 1000, 'the card holds what was put on it');
    // the line on the sheet changed as the card screens change it (`link.say`, cardSession's own)
    const seen = await tap((link) => link.say('Keep the card there').then(() => W.cardLook(link)));
    assert.ok(s.host.cardSheet.includes('say: Keep the card there'));
    assert.equal(seen.balance, 1000, 'and the page reads it back through the gate');
    // a payment from it, taken by this phone at the mint
    const paid = await tap((link) => W.cardPay(link, { sats: 300, pin: '1234', hold: true }));
    assert.equal(paid.sats, 300);
    assert.equal(card.balance(), 700, 'what left the card is what was paid');
    // what native saw: every command carried was the applet's choosing or one of its 31, after the choosing
    const carried = s.host.cardCarried;
    assert.ok(carried.length > 20, carried.length + ' commands carried');
    let chosen = false;
    for (const hex of carried) {
      const c = CardHost.read(Buffer.from(hex, 'hex'));
      assert.ok(c === 'select' || (chosen && CardHost.INSTRUCTIONS.has(c)), hex.slice(0, 12) + ' went through the gate');
      if (c === 'select') chosen = true;
    }
    assert.ok(!carried.some((h) => /^b052/.test(h) || /^b050/.test(h) || /^b033/.test(h)), 'no SET_TAG, lock or PIN-form limit reached the card');
    const sheet = s.host.cardSheet;
    assert.equal(sheet.filter((l) => l.startsWith('begin: ')).length, 4, 'four sheets: ' + sheet.join(' | '));
    assert.equal(sheet.filter((l) => l.startsWith('connected: Reading the card')).length, 4, 'each told connected');
    assert.equal(sheet.filter((l) => l.startsWith('end: ')).length, 4, 'and each closed');
    assert.ok(s.host.asked.get('cardSay') > 0, 'the page said how the tap went on the sheet');
    assert.ok(s.host.pushes.includes('_card'), 'progress pushed');

    // the card taken away after two commands: the page is told it was lost, and the sheet ends in red
    s.host.state.cardPull = 2;
    const lost = await tap((link) => W.cardLook(link)).then(() => 'read', (e) => e);
    assert.notEqual(lost, 'read', 'a card taken away is not a card read');
    assert.ok(sheet.includes('lost: the tag was lost'), 'lost pushed: ' + sheet.slice(-4).join(' | '));
    assert.match(sheet[sheet.length - 1], /^error: /, 'the sheet closes with the reason in red');

    // a phone that cannot read a card, and a sheet nobody holds a card to
    s.host.state.card = null;
    const noNfc = await tap((link) => W.cardLook(link)).then(() => null, (e) => e);
    assert.equal(noNfc && noNfc.card, 'no-nfc', 'NFC is not available on this phone');
    s.host.state.card = 'away';
    const none = await tap((link) => W.cardLook(link)).then(() => null, (e) => e);
    assert.equal(none && none.card, 'cancelled', 'the sheet timed out');
    assert.equal(none.message, 'No card was tapped.');
  });
  for (const a of ['cardBegin', 'cardSend', 'cardSay', 'cardEnd']) assert.ok(s.host.asked.get(a) > 0, a + ' asked of the P3 host');
});

test('P3 (ENG-667): the card that leaves part way is looked for again by the same sheet (cardAgain), and the applet chosen again', async () => {
  const storage = { 'foxy.intro.seen': '2026-10-12', 'foxy.custody.told': JSON.stringify({ Minibits: true }) };
  const s = await flow({ seed: true, tor: 'up', phase: 'P3', storage, p2pk: true }, async (s) => {
    const W = s.W;
    await fundedWallet(s, 1000);
    const card = makeCard({ window: s.window, format: 4 });
    s.host.state.card = card;
    await W.cardSession('Tap behind the phone.', (link) => W.cardSetUp(link, { pin: '1234', recoverable: true }));
    // the page's own retry: its fn fails with the card gone, `opts.again` gives the line, and the sheet looks again
    let tries = 0;
    const read = await W.cardSession('Tap behind the phone.', (link) => {
      tries += 1;
      if (tries === 1) s.host.state.cardPull = 1;
      return W.cardLook(link);
    }, { again: (e) => (e && e.card === 'gone' ? 'Hold the card there again' : '') });
    assert.equal(tries, 2, 'the read ran again in the same sheet');
    assert.equal(read.balance, 0);
    const sheet = s.host.cardSheet;
    assert.ok(sheet.includes('again: Hold the card there again'), sheet.join(' | '));
    // after the card came back, the first thing carried was the applet's choosing
    const carried = s.host.cardCarried;
    assert.equal(s.host.cardAgainAt.length, 1, 'found again once');
    assert.equal(CardHost.read(Buffer.from(carried[s.host.cardAgainAt[0]], 'hex')), 'select');
  });
  assert.ok(s.host.asked.get('cardAgain') > 0, 'cardAgain asked of the P3 host');
});

test('the card session on a P2 host is a violation: Android serves it from P3 (ENG-667)', async () => {
  const s = await boot({ seed: true, tor: 'up', phase: 'P2' });
  try {
    await assert.rejects(askAsPage(s, 'cardBegin', { text: 'Tap' }), /Unknown action: cardBegin/);
    assert.ok(s.host.violations.some((v) => v.kind === 'unserved' && v.detail.includes('cardBegin')));
  } finally {
    s.close();
  }
});

test('P3: APPROVE on the first-install Bluetooth screen closes it, answered "unsupported" and pushed (ENG-656)', async () => {
  // The flow to run against the Kotlin bridge in P3 (ENG-656): bluetoothAsk is served by
  // a real handler that pushes __foxyBluetoothAnswer("unsupported") and then answers it.
  const s = await flow({ seed: true, tor: 'up', phase: 'P3' }, async (s) => {
    const d = s.window.document, w = s.window;
    const approve = await toBluetoothScreen(s);
    assert.equal(w.localStorage.getItem('foxy.bt.asked'), null, 'nothing written before a button is pressed');
    approve.click();
    await s.until(() => !d.getElementById('ftg-bt'), 10000, 'the Bluetooth screen to close after APPROVE');
    assert.equal(w.localStorage.getItem('foxy.bt.asked'), 'approved, iOS said unsupported', 'the page wrote down the answer it was pushed');
    assert.equal(w.__foxyBluetoothAnswer, null, 'the screen let go of its global as it left');
    // and the launch goes on to the wallet, with no gate screen left over it
    await s.until(() => s.W.connected === true && !w.FoxyGate.visible(), 30000, 'the wallet, with the gate gone');
  });
  assert.equal(s.host.asked.get('bluetoothAsk'), 1, 'APPROVE asked the phone once');
  assert.deepEqual(s.host.answered.filter((a) => a[0] === 'bluetoothAsk'), [['bluetoothAsk', 'unsupported', null]]);
  assert.deepEqual(s.host.pushes.filter((p) => p === '__foxyBluetoothAnswer'), ['__foxyBluetoothAnswer']);
});

test('the Bluetooth screen: a reply with no __foxyBluetoothAnswer push leaves it up for good (the ENG-656 bug, as a control)', async () => {
  // What main 570d049 did: "Unknown action" through _scanResult and no push. The page waits for
  // the push alone, so the screen stays and a second APPROVE asks nothing (`asking`). This is the
  // control that shows the flow above can fail.
  const s = await flow({ seed: true, tor: 'up', phase: 'P3', answers: { bluetoothAsk: () => [null, 'Unknown action: bluetoothAsk'] } }, async (s) => {
    const d = s.window.document, w = s.window;
    const approve = await toBluetoothScreen(s);
    approve.click();
    await s.until(() => s.host.answered.some((a) => a[0] === 'bluetoothAsk'), 10000, 'the reply');
    await s.settle(1000);
    assert.ok(d.getElementById('ftg-bt'), 'the screen is still up');
    approve.click();
    await s.settle(500);
    assert.ok(d.getElementById('ftg-bt'), 'and a second APPROVE does not close it');
    assert.equal(w.localStorage.getItem('foxy.bt.asked'), null, 'nothing was written down');
  });
  assert.equal(s.host.asked.get('bluetoothAsk'), 1, 'the second APPROVE returned early on `asking`');
  assert.equal(s.host.pushes.includes('__foxyBluetoothAnswer'), false);
});

test('the Bluetooth screen: REJECT closes it with no native call', async () => {
  const s = await flow({ seed: true, tor: 'up', phase: 'P3' }, async (s) => {
    const d = s.window.document;
    await toBluetoothScreen(s);
    d.getElementById('ftg-bt-no').click();
    await s.until(() => !d.getElementById('ftg-bt'), 10000, 'the Bluetooth screen to close after REJECT');
    assert.match(s.window.localStorage.getItem('foxy.bt.asked'), /^rejected/);
  });
  assert.equal(s.host.asked.get('bluetoothAsk') || 0, 0);
});

/* Tap to pay's receiving side over a link (ENG-670): P3, asked by the flows that
 * make one. */
const IN_A_TAP_LINK = ['tapChange', 'tapAsking', 'tapChangeDue', 'tapTerms', 'inboxAnswer', 'shakeStart', 'shakeStop'];

/* The receive invoice screen, as a person reaches it, on a P3 host: through the
 * first-install screens (the gate is up until then, and nothing arms under it),
 * an invoice made, the screen up, and TAP pressed, which raises the TAP HERE card
 * over the QR the screen armed for itself. The receive screen also asks the onion
 * inbox, LATER and "Unknown action" on Android (`unknown`), not part of what this
 * counts; shake to TAP is P3 since ENG-670 and answered. Returns the app. */
const RECEIVE_ALSO_ASKS = new Set(['inboxOpen', 'inboxClose']);
async function toReceiveScreenWithTapPressed(s) {
  const W = s.W, w = s.window;
  (await toBluetoothScreen(s)).click();
  await s.until(() => W.connected === true && !w.FoxyGate.visible(), 30000, 'the wallet, with the gate gone');
  const inv = await W.invoice(64, 'conformance tap');
  const app = s.logic();
  app.setState({ screen: 'confirm', flow: 'receive', invoice: inv.bolt11 });
  await s.until(() => s.host.asked.get('tapReceiveStart') > 0, 10000, 'the screen to arm itself and ask tapReceiveStart');
  app.tapArm();
  await s.until(() => w.document.body.textContent.includes('TAP HERE'), 5000, 'the TAP HERE card');
  return app;
}

/* tapReceiveStart asks over a window: long enough for the page's 1.2 s retry to
 * have asked three more times if the first answer was a refusal.
 *
 * With a current price the offer carries the price and the second it was read
 * (`rate`, `at`), so the page sends a new offer whenever the screen updates in a
 * new second, on iOS as here: an offer refresh, not the retry. These flows hold
 * the offer still by giving the page no price (`price` refused), so every ask
 * after the first is the retry loop and nothing else. */
const REARM_WINDOW_MS = 4000;
const NO_PRICE = { price: () => [null, 'Foxy is not connected to Tor.'] };

test('P3: the receive screen\'s TAP is answered "ok" then told "unsupported", with no UNKNOWN ACTION and no re-arm loop (ENG-656)', async () => {
  // The flow to run against the Kotlin bridge in P3 (ENG-656): tapReceiveStart is served by a
  // real handler that answers "ok" and, making the receiver, then pushes _tap receive/unsupported.
  let app;
  const s = await flow({ seed: true, tor: 'up', phase: 'P3', unknown: RECEIVE_ALSO_ASKS, answers: NO_PRICE }, async (s) => {
    app = await toReceiveScreenWithTapPressed(s);
    const before = s.host.asked.get('tapReceiveStart');
    await s.settle(REARM_WINDOW_MS);
    assert.equal(s.host.asked.get('tapReceiveStart'), before, 'nothing asked tapReceiveStart again: the 1.2 s re-arm loop is not running');
    const text = s.window.document.body.textContent;
    assert.equal(/UNKNOWN ACTION/i.test(text), false, 'no "Unknown action" anywhere on screen');
    assert.ok(text.includes('TAP HERE'), 'the TAP HERE card is up');
    assert.equal(app.state.tapNotYet || '', '', 'the page holds no refusal for the TAP card');
    assert.equal(app.state.tapArmed, true, 'the screen stays armed, as on an iPhone without Bluetooth');
    assert.ok(!app._tapRetryT, 'no retry was ever scheduled: only a refusal schedules one');
    // the pinned page takes only off and denied from a receive-side _tap: no false "turn Bluetooth on"
    assert.equal(['off', 'denied'].includes(app.state.tapRecvStage), false, 'the TAP button is not greyed for a radio that is off');
  });
  assert.equal(s.host.asked.get('tapReceiveStart'), 1, 'asked once for the whole screen');
  assert.deepEqual(s.host.answered.filter((a) => a[0] === 'tapReceiveStart'), [['tapReceiveStart', 'ok', null]]);
  assert.deepEqual(s.host.pushes.filter((p) => p === '_tap'), ['_tap'], 'the receiver said unsupported once');
});

test('the receive screen\'s TAP: an "Unknown action" reply shows UNKNOWN ACTION: TAPRECEIVESTART and re-arms every 1.2 s (the ENG-656 bug, as a control)', async () => {
  // What main 570d049 did. The control that shows the flow above can fail.
  const s = await flow({ seed: true, tor: 'up', phase: 'P3', unknown: RECEIVE_ALSO_ASKS,
    answers: { ...NO_PRICE, tapReceiveStart: () => [null, 'Unknown action: tapReceiveStart'] } }, async (s) => {
    await toReceiveScreenWithTapPressed(s);
    await s.until(() => s.window.document.body.textContent.includes('UNKNOWN ACTION: TAPRECEIVESTART'), 5000, 'the error in the TAP card');
    const before = s.host.asked.get('tapReceiveStart');
    await s.settle(REARM_WINDOW_MS);
    assert.ok(s.host.asked.get('tapReceiveStart') >= before + 2, 'asked again and again: ' + before + ' then ' + s.host.asked.get('tapReceiveStart'));
  });
  assert.equal(s.host.pushes.includes('_tap'), false);
});

/* ENG-670: a phone with Bluetooth. The first-install screen's APPROVE is answered with
 * the phone's state, pushed first; the receive screen goes on the air (`searching`
 * after the reply); a payer comes near, links (the four digits) and pays over the
 * link; the payment reaches the page as `_requestPaid(body, key, "tap")` and the
 * page's `inboxAnswer` for that key is what goes back as M6. Then the receiving
 * side's words over that link, a shake while armed, the phone turned over, and the
 * screen let go. */
for (const state of ['allowed', 'off', 'denied']) {
  test('P3 (ENG-670): APPROVE on the first-install Bluetooth screen on a phone with Bluetooth ' + state + ' closes it with that answer', async () => {
    const s = await flow({ seed: true, tor: 'up', phase: 'P3', bluetooth: state }, async (s) => {
      const d = s.window.document;
      (await toBluetoothScreen(s)).click();
      await s.until(() => !d.getElementById('ftg-bt'), 10000, 'the Bluetooth screen to close after APPROVE');
      assert.equal(s.window.localStorage.getItem('foxy.bt.asked'), 'approved, iOS said ' + state);
    });
    assert.deepEqual(s.host.answered.filter((a) => a[0] === 'bluetoothAsk'), [['bluetoothAsk', state, null]]);
    assert.deepEqual(s.host.pushes.filter((p) => p === '__foxyBluetoothAnswer'), ['__foxyBluetoothAnswer']);
  });
}

test('P3 (ENG-670): the receive screen on the air with Bluetooth: searching, a payer links and pays over the tap, and the page answers it with inboxAnswer', async () => {
  let app;
  const s = await flow({ seed: true, tor: 'up', phase: 'P3', bluetooth: 'allowed', unknown: RECEIVE_ALSO_ASKS, answers: NO_PRICE }, async (s) => {
    const W = s.W;
    const heard = [];
    W.onTap((ev) => { if (ev.side === 'receive') heard.push(ev.stage + (ev.code ? ' ' + ev.code : '') + (ev.edge ? ' ' + ev.edge : '')); });
    app = await toReceiveScreenWithTapPressed(s);
    await s.until(() => heard.includes('searching'), 5000, 'the receiver to say it is on the air');
    const text = () => s.window.document.body.textContent;
    assert.ok(text().includes('HOLD A PHONE HERE'), 'on the air: the TAP card says HOLD A PHONE HERE');
    assert.equal(['off', 'denied', 'unsupported'].includes(app.state.tapRecvStage), false, 'the TAP button is not greyed');
    assert.equal(/UNKNOWN ACTION/i.test(text()), false);
    // a payer near, then its first word and the four digits
    s.host.tap.near('right');
    s.host.tap.link('4821');
    await s.until(() => text().includes('4821'), 5000, 'the four digits on screen');
    // the receiving side's words over the link: M9, M11, change owed and sent back
    assert.equal(await W.tapAsking(), 'ok');
    assert.equal(await W.tapTerms(JSON.stringify({ v: 2, req: 'creqAabcdefghijklmnopqrstuv' })), 'ok');
    await W.tapChangeDue();
    assert.equal(await W.tapChange('cashuBchange'), 'ok');
    // a payment that crossed the link and answers no request open here: the page refuses it, through inboxAnswer
    const m6 = await s.host.tap.pay(JSON.stringify({ id: 'not-open-here', mint: W.mintUrl, unit: 'sat', proofs: [{ id: '00', amount: 1, secret: 's', C: '02' }] }));
    assert.equal(m6[0], 422, 'refused: ' + JSON.stringify(m6));
    assert.match(m6[1], /answers no request open here/);
    // something that is not a payment at all: still answered, clamped to a status the payer reads
    const bad = await s.host.tap.pay('not a payment');
    assert.equal(bad[0], 422);
    // the key is answered once: a second answer for it finds nothing
    await assert.rejects(askAsPage(s, 'inboxAnswer', { answer: 'f'.repeat(32), status: 200, text: '' }), /No payment is waiting on that answer\./);
    assert.deepEqual(heard.slice(0, 4), ['searching', 'nearby right', 'connecting', 'code 4821'], 'the receiver\'s stages, in order, with the edge and the digits');
    // shake to TAP: heard only while armed
    await W.shakeSense(true);
    s.host.tap.shake();
    await W.shakeSense(false);
    s.host.tap.shake();
    assert.deepEqual(heard.filter((h) => h === 'shake'), ['shake'], 'one shake, the one while armed');
    // the phone turned over: the invoice screen turns itself round
    s.host.pushAs('_turned', true);
    assert.equal(W.upsideDown(), true);
    s.host.pushAs('_turned', false);
    assert.equal(W.upsideDown(), false);
    s.host.tap.gone();
    await W.tapReceiveStop();
  });
  for (const a of IN_A_TAP_LINK) assert.ok(s.host.asked.get(a) > 0, a + ' asked of the P3 host');
  assert.ok(s.host.asked.get('tapReceiveStart') >= 1);
  assert.deepEqual(s.host.state.m6.map((m) => m[0]), [422, 422], 'each payment answered once, as M6');
  const said = s.host.pushes.filter((p) => p === '_tap').length;
  assert.ok(said >= 5, 'searching, nearby, connecting, code, a shake while armed and gone: ' + said);
});

test('P3 (ENG-670): a receive screen on a phone whose Bluetooth is off is told off, and the TAP button says so', async () => {
  let app;
  const s = await flow({ seed: true, tor: 'up', phase: 'P3', bluetooth: 'off', unknown: RECEIVE_ALSO_ASKS, answers: NO_PRICE }, async (s) => {
    app = await toReceiveScreenWithTapPressed(s);
    await s.until(() => app.state.tapRecvStage === 'off', 5000, 'the page to take off from the receiver');
  });
  assert.deepEqual(s.host.answered.filter((a) => a[0] === 'tapReceiveStart').slice(0, 1), [['tapReceiveStart', 'ok', null]]);
});

test('screens: everything a screen asks native for, through the wallet', async () => {
  await flow({ seed: true, tor: 'up' }, async (s) => {
    const W = s.W, w = s.window;
    await s.until(() => W.connected === true, 30000, 'connected');
    await W.rate({ fresh: true }).catch(() => {});
    await W.agreedRate().catch(() => {});
    await W.candles(3600, 86400);
    assert.equal(await W.scan('Scan an ecash token'), 'cashuBconformance');
    await W.clipboard();
    assert.equal(W.share('cashuBconformance'), true);
    assert.equal(W.notify('received'), true);
    assert.equal(await W.biometric('Unlock Foxy', true), 'yes');
    // a pane the page measured: jsdom lays nothing out, so the rectangle is given
    const pane = w.document.createElement('div');
    pane.getBoundingClientRect = () => ({ left: 20, top: 400, width: 300, height: 200 });
    assert.equal(W.startPreview(pane, () => {}), true);
    W.stopPreview();
    assert.equal(W.startPasteButton(pane, () => {}), true);
    W.stopPasteButton();
    await W.openSettings();
    await W.openCompany('strike');
    await W.fieldLog(true);
    await W.clearFieldLog();
    await W.keepAwake(true);
    await W.keepAwake(false);
    await W.captureCheck();
    // the seed screens, and the protection in front of the seed
    await W.showSeedNative({ verify: true });
    const candidate = await W.enterSeedNative();
    await W.forgetSeedCandidate(candidate);
    await W.seedProtection();
    await W.setSeedProtection('device').catch(() => {});
    // the app, as a person uses it
    const app = s.logic();
    assert.equal(app.copySecret('cashuBconformance-token'), true);
    app.haptic('success', true);
    app.haptic('error', true);
    app.haptic('triple', true);
  });
});

test('money: receive, send a token, take it back, pay an invoice, on a mint of cashu-ts', async () => {
  await flow({ seed: true, tor: 'up' }, async (s) => {
    const W = s.W;
    await s.until(() => W.connected === true, 30000, 'connected');
    const inv = await W.invoice(64, 'conformance');
    const claimed = await W.claim(inv.hash || inv.quote || inv.id);
    assert.ok(claimed, 'the invoice was claimed');
    assert.ok(s.host.state.counters.size > 0, 'the phone keeps the counters, the page asked it to reserve them');
    const tok = await W.sendToken(8);
    assert.match(String(tok.token), /^cashu/, 'a token');
    await W.receiveToken(tok.token);
    await W.pay('lnbc80n1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypq').catch(() => {});
    assert.ok(s.host.mint.requests.includes('POST /v1/melt/quote/bolt11'), 'the payment asked the mint for a melt quote, over mintRequest');
    assert.ok(s.host.mint.requests.includes('POST /v1/swap'), 'tokens were made and taken back with swaps');
    assert.ok(s.host.mint.requests.includes('POST /v1/melt/bolt11'), 'and the invoice was paid with a melt');
    // a scan of this wallet's own seed at its mint, with no words (FoxyWebView.runSeedSelfTest does the same)
    const rows = await W.scanSeed(null, [W.mintUrl]);
    assert.ok(Array.isArray(rows) && rows.length === 1, 'the restore scan answered a row');
    // RESTORE: words typed on the phone, scanned as a candidate, adopted (seedAdopt), then forgotten
    const candidate = await W.enterSeedNative();
    const found = await W.scanSeed({ candidate }, [W.mintUrl]);
    await W.adoptScan(found, { candidate }).catch(() => {});
    await W.forgetSeedCandidate(candidate);
    // DELETE WALLET: the phone asks, wipes, and makes a new seed
    await W.wipeDevice().catch(() => {});
    await s.settle(800);
  });
});

test('payment requests and lock keys: what Android serves later still has the contract\'s shape', async () => {
  await flow({ seed: true, tor: 'up' }, async (s) => {
    const W = s.W;
    await s.until(() => W.connected === true, 30000, 'connected');
    await W.openInbox().catch(() => {});
    await W.closeInbox(0);
    await W.primeLocks().catch(() => {});
    await Promise.resolve().then(() => W.paymentRequest(21, {})).catch(() => {});
    // a payment arriving at the request: answered with inboxAnswer
    s.host.push('_requestPaid', 'not a payment');
    await s.settle(300);
  });
});

test('tap to pay and the card: their doors, with no radio', async () => {
  await flow({ seed: true, tor: 'up' }, async (s) => {
    const W = s.W;
    await s.until(() => W.connected === true, 30000, 'connected');
    await W.shakeSense(true);
    await W.shakeSense(false);
    await W.tapReceive('lnbc80n1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypq', false).catch(() => {});
    await W.tapReceiveStop();
    await W.tapPayNow().catch(() => {});
    await W.tapPay().catch(() => {});
    await W.tapSend('cashuBx').catch(() => {});
    await W.tapChange('cashuBx').catch(() => {});
    await W.tapAsking().catch(() => {});
    await W.tapQuote('{}').catch(() => {});
    await W.tapTerms('{}').catch(() => {});
    await W.tapChangeDue().catch(() => {});
    await W.tapChangeKept(true).catch(() => {});
    await W.tapPayStop();
    await W.cardStop();
    await W.cardSession('Tap behind the phone.', () => Promise.resolve(), {}).catch(() => {});
  });
});

/* A card held to the phone (getfoxy/iOS 9832f7d): native says so with `_cardTapped`, a P3
 * push since ENG-668 (flashcard/CardTapDelivery.kt). Only when a card is held up: a page never
 * told must boot, connect and run as it did before the push existed, and a host of an earlier
 * phase may not send it. */
const cardTapLines = (s) => s.page.logs.map((l) => l.join(' ')).filter((l) => l.includes('[foxy] card tap:'));

test('the card tap (_cardTapped, P3): a page never told boots and runs, puts the call on its wallet, and opens no card', async () => {
  await flow({ seed: true, tor: 'up' }, async (s) => {
    const W = s.W, app = s.logic();
    await s.until(() => W.connected === true, 30000, 'the page to connect with no _cardTapped push');
    await s.until(() => s.host.asked.get('covered') > 0 && s.host.asked.get('price') > 0, 30000, 'covered and price, as any boot');
    // bootWallet puts it on the wallet once the lock is decided (06-wallet-boot-and-restore.js); native asks until it answers true
    await s.until(() => typeof W._cardTapped === 'function', 30000, 'the app to put _cardTapped on the wallet');
    await s.settle(1000);
    assert.equal(app.state.screen, 'home', 'booted to home');
    assert.ok(!s.host.pushes.includes('_cardTapped'), 'nothing pushed _cardTapped');
    assert.deepEqual(cardTapLines(s), [], 'the page heard of no card tap');
    assert.ok(!s.host.asked.has('cardBegin'), 'and opened no card session');
  });
});

test('the card tap (_cardTapped, P3): answered true, kept while the gate is up, acted on at home once it goes; a P2 host may not send it', async () => {
  // the intro film never ends in jsdom, and the custody notice would sit over home: both as already seen
  const storage = { 'foxy.intro.seen': '2026-10-12', 'foxy.custody.told': JSON.stringify({ Minibits: true }) };
  await flow({ seed: true, tor: 'up', storage }, async (s) => {
    const W = s.W, d = s.window.document;
    await s.until(() => W.connected === true && typeof W._cardTapped === 'function', 30000, 'connected, with _cardTapped on the wallet');
    await s.until(() => d.getElementById('ftg-warn-go'), 30000, 'the first-launch warning');
    // what native evaluates (CardAddress.pageCall), and its answer: true, the page has the request
    assert.equal(s.host.push('_cardTapped'), true, 'the page answers true once it has the request');
    assert.equal(s.host.push('_cardTapped'), true, 'a second tap while one waits is the same tap, still true');
    await s.until(() => cardTapLines(s).length > 0, 5000, 'a card tap log line');
    assert.match(cardTapLines(s)[0], /kept until Foxy is open, it is starting/);
    assert.ok(!s.host.asked.has('cardBegin'), 'no card session under the gate');
    // through the gate's first-install screens; then home, where nothing is in progress (cardTapRule): act
    d.getElementById('ftg-warn-go').click();
    await s.until(() => d.getElementById('ftg-bt-go'), 10000, 'the Bluetooth screen after PROCEED');
    d.getElementById('ftg-bt-go').click();
    await s.until(() => s.host.asked.get('cardBegin') > 0, 10000, 'the kept tap to open the card sheet once the gate has gone');
    const lines = cardTapLines(s);
    assert.ok(lines.some((l) => /ignored, one is already waiting/.test(l)), 'the second tap was the same one');
    assert.ok(lines.some((l) => /opening the card screen from home, after \d+ s kept/.test(l)), 'acted on from home: ' + lines.join(' | '));
  });
  // P3 sends it (ENG-668); a host of an earlier phase may not
  const p2 = await boot({ seed: true, tor: 'up', phase: 'P2' });
  try {
    await p2.settle(300);
    p2.host.violations.length = 0;
    p2.host.pushAs('_cardTapped');
    assert.deepEqual(p2.host.violations.map((v) => v.kind), ['unserved'], 'a P2 host refuses to push the P3 _cardTapped');
  } finally {
    p2.close();
  }
});

/* What Android's CardTapDelivery leans on (ENG-668): the page's answer to CardAddress.PAGE_CALL is
 * its only sign. Before the app has put `_cardTapped` on the wallet the call answers nothing that
 * is true (native asks again in 0.3 s); once it has, the answer is true, and a P3 host sends it
 * with no violation of the contract. */
test('the card tap (_cardTapped, P3): the page call answers not-true until the wallet has it, then true, from a P3 host', async () => {
  const s = await boot({ seed: true, tor: 'up', phase: 'P3' });
  try {
    const call = 'window.FoxyWallet && window.FoxyWallet._cardTapped && window.FoxyWallet._cardTapped()';
    const early = s.window.eval(call);
    if (typeof s.W._cardTapped !== 'function') assert.notEqual(early, true, 'no wallet member yet: not true, so native asks again');
    await s.until(() => typeof s.W._cardTapped === 'function', 30000, 'the app to put _cardTapped on the wallet');
    s.host.violations.length = 0;
    assert.equal(s.host.pushAs('_cardTapped'), true, 'a P3 host pushes it and the page answers true');
    assert.equal(s.window.eval(call), true, 'the very string native evaluates answers true');
    assert.deepEqual(s.host.violations, [], 'no contract violation from a P3 host');
  } finally {
    s.close();
  }
});

/* The send confirmation, read from what Android's host pushes (getfoxy/iOS 77d0908, d37df18).
 *
 * The page decides CHECKING… from `_privacy` alone (`routeComing`: tor 'connecting' or 'stuck',
 * a network under it, not unprotected; build/wallet/02-dleq-lock-holds-imports.js:692), and
 * NOT ENOUGH from the mint's fee quote against the balance (`payNeed`, build/app/01-ui-basics.js:341).
 * Android's `_privacy` push is what these screens are drawn from: a push missing a field or
 * saying a different word shows SEND while the route is still closed, or CHECKING… for good.
 * The invoice is a real BOLT11 (tools/fake-invoice.js) so the page classifies and decodes it as
 * it would one scanned; it is handed in through the `scan` action, as the camera hands it. */
const { fakeInvoice } = require('../../tools/fake-invoice');
const sendCta = (s) => { try { return s.logic().confirmSpec().cta; } catch (e) { return ''; } };
const feeRow = (s) => { try { return (s.logic().confirmSpec().rows.find((r) => r.label === 'FEE') || {}).value; } catch (e) { return ''; } };

async function fundedWallet(s, sats) {
  const W = s.W;
  await s.until(() => W.connected === true, 30000, 'connected');
  const inv = await W.invoice(sats, 'conformance send');
  assert.ok(await W.claim(inv.hash || inv.quote || inv.id), 'the invoice was claimed: minted');
  s.logic().refreshBalance();
  await s.until(() => s.logic().state.balSats === sats, 10000, 'the balance to read ' + sats);
}

/* What the camera hands the page, through native's `scan` (contract: scan), to the screen that takes it. */
async function scanned(s, text) {
  s.host.state.scanAnswer = text;
  const got = await s.W.scan('Scan an invoice');
  assert.equal(got, text, 'the scan answered the invoice');
  s.logic().acceptTarget(got);
}

test('the send confirmation after a return: an invoice scanned while Tor reconnects reads CHECKING… and asks nothing, then SEND once _privacy says up', async () => {
  const invoice = fakeInvoice(8);
  await flow({ seed: true, tor: 'up', answers: { scan: (m, host) => [host.state.scanAnswer || 'cashuBconformance', null] } }, async (s) => {
    const W = s.W, app = s.logic();
    await fundedWallet(s, 64);
    // back from the background: native tells the page (`_resumed`), and Tor is at work on a circuit again (`_privacy`)
    s.host.pushAs('_resumed', 30);
    s.host.state.tor = 'connecting';
    s.host.state.progress = 40;
    s.host.pushAs('_privacy', snapshot(s.host.state));
    await s.until(() => W.routeComing() === true, 5000, 'the page to read a connection on its way from _privacy');
    const before = routeActions(s), meltsBefore = s.host.mint.requests.filter((r) => r === 'POST /v1/melt/quote/bolt11').length;
    await scanned(s, invoice);
    await s.until(() => app.state.screen === 'sendConfirm', 10000, 'the invoice to go to the confirmation, not the offline card');
    assert.equal(sendCta(s), 'CHECKING…', 'the button holds while the route is on its way');
    assert.equal(feeRow(s), 'CHECKING…', 'and the FEE row says the same word');
    assert.ok(shows(s, 'CHECKING…'), 'CHECKING… is on screen');
    const end = Date.now() + 3000;
    while (Date.now() < end) {
      assert.equal(routeActions(s), before, 'nothing that needs a route was asked while Tor reconnected');
      assert.equal(sendCta(s), 'CHECKING…', 'and the button stayed CHECKING…');
      await wait(50);
    }
    assert.equal(s.host.mint.requests.filter((r) => r === 'POST /v1/melt/quote/bolt11').length, meltsBefore, 'no melt quote left');
    // Tor up, pushed as native pushes it: the fee is asked by itself, and the button becomes SEND
    s.torUp();
    await s.until(() => /^SEND /.test(sendCta(s)), 20000, 'SEND once the route is up and the fee quoted');
    assert.ok(s.host.mint.requests.filter((r) => r === 'POST /v1/melt/quote/bolt11').length > meltsBefore, 'the fee was quoted over mintRequest once the route opened');
    assert.match(String(feeRow(s)), /^UP TO /, 'the FEE row reads the quote');
    assert.ok(!s.host.mint.requests.includes('POST /v1/melt/bolt11'), 'and nothing was paid: SEND was not pressed');
  });
});

test('the send confirmation: a quote the balance cannot cover reads NOT ENOUGH with its figures, and pressing it pays nothing', async () => {
  // 64 sats held, an invoice for 64: the invoice fits, the fake mint's fee reserve (10, lib/mint.js) does not
  const invoice = fakeInvoice(64);
  await flow({ seed: true, tor: 'up', answers: { scan: (m, host) => [host.state.scanAnswer || 'cashuBconformance', null] } }, async (s) => {
    const app = s.logic();
    await fundedWallet(s, 64);
    await scanned(s, invoice);
    await s.until(() => app.state.screen === 'sendConfirm', 10000, 'the confirmation');
    await s.until(() => sendCta(s) === 'NOT ENOUGH', 20000, 'NOT ENOUGH once the fee is quoted');
    const spec = app.confirmSpec();
    assert.equal(spec.ctaTone, 'warn');
    assert.match(String(feeRow(s)), /^UP TO /, 'the quoted fee stays in its row');
    assert.match(String(spec.warn), /this needs 7\d sats and you hold 64\./, 'the wallet\'s sentence at its figures: ' + spec.warn);
    assert.ok(shows(s, 'NOT ENOUGH'), 'NOT ENOUGH is on screen');
    spec.go();
    await s.settle(500);
    assert.equal(app.state.screen, 'sendConfirm', 'pressing it goes nowhere');
    assert.ok(!s.host.mint.requests.includes('POST /v1/melt/bolt11'), 'and nothing was paid');
  });
});

test('native pushes: each one the contract lists, delivered with a contract-shaped payload', async () => {
  await flow({ seed: true, tor: 'up' }, async (s) => {
    const w = s.window, W = s.W;
    await s.until(() => W.connected === true && typeof W._putAway === 'function', 30000, 'connected');
    const pushes = {
      _privacy: { tor: 'up', progress: 100, everUp: true, unprotected: false, transport: 'direct', orbot: 'none', firstSetup: false, vpn: false, orbotKey: false, network: 'wifi' },
      _captured: true, _screenshot: undefined, _previewRefused: 'That pane is too small for a camera (1x1).', _liveScan: 'cashuBx', _pasted: '',
      _resumed: 0, _tapQuote: '{}', _tapChange: 'cashuBx', _tapTerms: '{}', _turned: false, _tapChangeKeptLate: undefined,
      _tap: { side: 'pay', stage: 'searching' }, _card: { stage: 'lost', text: 'The card was taken away.' },
    };
    for (const [name, arg] of Object.entries(pushes)) {
      assert.equal(typeof W[name], 'function', name + ' is a function on FoxyWallet');
      s.host.push(name, arg);
      await wait(20);
    }
    W._captured(false);
    // _putAway is the app's: set once the app has booted, called with true as Foxy goes away
    assert.equal(typeof W._putAway, 'function', '_putAway set by the app');
    s.host.push('_putAway', true);
    assert.equal(typeof W._tidying, 'boolean', '_tidying is read by native as a boolean');
    w.__foxyUptime = { s: 2000, p: w.performance.now() };
    pushesSeen.add('__foxyUptime');
  });
});

/* Actions no flow here posts, each with the reason. Nothing else may be missing. */
const NOT_EXERCISED = {
  isDebug: 'nothing in the page at the pinned commit asks it (it reads window.FOXY_DEBUG)',
  counterReserveAt: 'a recovery path (reserving a range the mint said was already signed); checked statically',
  p2pkPubkeys: 'scanning for the index a token in hand is locked to; checked statically',
  p2pkKey: 'opening ecash locked to this phone; checked statically',
  counterAdvance: 'a restore lifting a counter; checked statically',
  onionPost: 'paying a request that names an onion; checked statically',
  nostrSend: 'paying a request that names a Nostr key; checked statically',
  cardTime: 'a card of software 1.14 or before, mid-tap (the ENG-667 flows tap a card of 1.20, which takes block headers)',
};

test('coverage: every action in the contract was posted by a flow, or is accounted for', (t) => {
  const actions = CONTRACT.filter((e) => e.kind === 'action').map((e) => e.name);
  const missing = actions.filter((a) => !seen.has(a) && !(a in NOT_EXERCISED));
  assert.deepEqual(missing, [], 'posted by no flow and not in NOT_EXERCISED');
  const stale = Object.keys(NOT_EXERCISED).filter((a) => seen.has(a));
  assert.deepEqual(stale, [], 'in NOT_EXERCISED but a flow posts it now: take it off the list');
  for (const m of ['haptic', 'username']) assert.ok(seen.has(m), m + ' was posted');
  const exercised = actions.filter((a) => seen.has(a)).length;
  t.diagnostic('coverage: ' + exercised + ' of ' + actions.length + ' actions posted by the flows; ' + Object.keys(NOT_EXERCISED).length + ' accounted for; ' + pushesSeen.size + ' pushes delivered');
});
