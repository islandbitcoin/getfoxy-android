'use strict';
/* flows-wallet.test.js — the wallet's own calls, money, and native's pushes.
 *
 * Everything a screen asks native for; a receive, a send and a token round trip
 * on a mint made of cashu-ts; the later-phase payment requests; the send
 * confirmation drawn from `_privacy`; and every push the contract lists.
 *
 * The checks after every flow (no violation, nothing on the network, every
 * answer in the contract), and the record of what the flows posted for the
 * coverage check (test/coverage/flows-coverage.test.js), are lib/flows.js's.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const { wait } = require('../lib/session');
const { snapshot } = require('../lib/host');
const { record, pushesSeen, flow, routeActions, shows, fundedWallet } = require('../lib/flows');

record(__filename);

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
