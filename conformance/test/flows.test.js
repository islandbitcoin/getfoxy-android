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
    await s.settle(1500);
    assert.equal(routeActions(s), 0, 'nothing that needs a route was asked while Tor was connecting');
    s.torFailed();
    await s.settle(1000);
    assert.equal(routeActions(s), 0, 'nor after Tor failed');
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
  bluetoothAsk: 'posted only by the first-install Bluetooth screen\'s Approve button, after the warning; checked statically (contract.test.js)',
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
