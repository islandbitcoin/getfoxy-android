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
const { CONTRACT } = require('../lib/host');

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
  assert.equal(p3.length, 23);
  assert.deepEqual(p3.filter((a) => !(s.host.asked.get(a) > 0)), [], 'every P3 action was asked of the P3 host');
  assert.ok(s.host.asked.get('haptic') > 0, 'and a haptic');
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

/* The receive invoice screen, as a person reaches it, on a P3 host: through the
 * first-install screens (the gate is up until then, and nothing arms under it),
 * an invoice made, the screen up, and TAP pressed, which raises the TAP HERE card
 * over the QR the screen armed for itself. The receive screen also asks the onion
 * inbox and shake to TAP, both LATER and "Unknown action" on Android (`unknown`),
 * neither part of what this counts. Returns the app. */
const RECEIVE_ALSO_ASKS = new Set(['inboxOpen', 'inboxClose', 'shakeStart', 'shakeStop']);
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
  cardSend: 'a card session needs the NFC sheet to open, which the fake phone refuses (cardBegin is answered with an error)',
  cardSay: 'as cardSend', cardAgain: 'as cardSend', cardOwnerKey: 'card set-up, after a card answered', cardOwnerSign: 'as cardOwnerKey',
  cardTime: 'a card of software 1.14 or before, mid-tap',
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
