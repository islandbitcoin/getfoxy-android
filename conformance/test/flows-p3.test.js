'use strict';
/* flows-p3.test.js — the P3 host's platform doors and the first-install Bluetooth screen.
 *
 * A P3 host answering every platform door the screens ask; the Bluetooth
 * screen's APPROVE answered "unsupported" (ENG-656), its control, REJECT, and
 * APPROVE on a phone with Bluetooth (ENG-670).
 *
 * The checks after every flow (no violation, nothing on the network, every
 * answer in the contract), and the record of what the flows posted for the
 * coverage check (test/coverage/flows-coverage.test.js), are lib/flows.js's.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const { CONTRACT } = require('../lib/host');
const { record, flow, shows, toBluetoothScreen, IN_A_TAP_LINK, IN_A_PAY_LINK } = require('../lib/flows');

record(__filename);

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
    // the onion inbox (ENG-672): an address for a request, and done with it
    assert.match(String((await W.openInbox()).onion), /^http:\/\/[a-z2-7]{55}d\.onion\//);
    await W.closeInbox(0);
    await W.wipeDevice().catch(() => {});
    await s.settle(800);
  });
  const p3 = CONTRACT.filter((e) => e.kind === 'action' && e.phase === 'P3').map((e) => e.name);
  assert.equal(p3.length, 47);
  // The page asks a card's owner key and its signed time only inside a card session, which
  // opens with cardBegin: they are asked of a P3 host, as the page's card code asks them, by
  // the ENG-666 flows, and the session itself by the ENG-667 flows (both flows-card.test.js).
  const inACardSession = ['cardOwnerKey', 'cardOwnerSign', 'cardTime', 'cardBegin', 'cardSend', 'cardSay', 'cardAgain', 'cardEnd'];
  // And tap to pay's receiving side only over a link a payer has made, on a phone with
  // Bluetooth: asked of a P3 host by the ENG-670 flows (IN_A_TAP_LINK, flows-tap-ble.test.js).
  // And tap to pay's paying side only from a listen on a funded wallet, and over the link it makes:
  // asked of a P3 host by the ENG-671 flows (IN_A_PAY_LINK, flows-tap-pay.test.js).
  // A payment arriving and a payment sent need a second phone: asked by the flows *P3 two phones*
  // (ENG-672, flows-onion.test.js; ENG-677, nostrSend, flows-nostr.test.js).
  const betweenTwoPhones = ['inboxAnswer', 'onionPost', 'nostrSend'];
  assert.deepEqual(p3.filter((a) => !inACardSession.includes(a) && !IN_A_TAP_LINK.includes(a) && !IN_A_PAY_LINK.includes(a) && !betweenTwoPhones.includes(a) && !(s.host.asked.get(a) > 0)), [],
    'every other P3 action was asked of the P3 host');
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

/* ENG-670: a phone with Bluetooth. The first-install screen's APPROVE is answered with
 * the phone's state, pushed first. */
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
