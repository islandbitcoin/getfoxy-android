'use strict';
/* flows-onion.test.js — the onion inbox and post (ENG-672).
 *
 * The receive screen's onion inbox answered with an address once, and the
 * request on screen naming it; as the control, the "Unknown action" reply that
 * had the screen ask again every 4 s; and two phones on P3 hosts, a payment
 * request paid over the onion from payer to receiver (`onionPost`, the
 * receiver's `_requestPaid` and `inboxAnswer`).
 *
 * The checks after every flow (no violation, nothing on the network, every
 * answer in the contract), and the record of what the flows posted for the
 * coverage check (test/coverage/flows-coverage.test.js), are lib/flows.js's.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const { wait } = require('../lib/session');
const { record, flow, toBluetoothScreen, NO_PRICE, fundedWallet } = require('../lib/flows');

record(__filename);

/* The receive screen's onion inbox (ENG-672). The page asks `inboxOpen` on every
 * render of a receive screen until it has an address, waiting 4 s after a refusal
 * (syncInbox, build/app/12-receive.js), so an "Unknown action" reply had the
 * screen ask again and again for as long as it was up. Renders are forced here
 * every 300 ms over a window longer than two of those waits. */
const INBOX_WINDOW_MS = 9000;
// an object out of the page's window, as plain JSON (its prototype is the page's, not this realm's)
const plain = (x) => JSON.parse(JSON.stringify(x));
async function receiveScreenRendering(s) {
  const W = s.W, w = s.window;
  (await toBluetoothScreen(s)).click();
  await s.until(() => W.connected === true && !w.FoxyGate.visible(), 30000, 'the wallet, with the gate gone');
  const inv = await W.invoice(64, 'conformance inbox');
  const app = s.logic();
  app.setState({ screen: 'confirm', flow: 'receive', invoice: inv.bolt11 });
  await s.until(() => s.host.asked.get('inboxOpen') > 0, 10000, 'the receive screen to ask inboxOpen');
  const end = Date.now() + INBOX_WINDOW_MS;
  while (Date.now() < end) {
    app.setState({ inboxTick: Date.now() });
    await wait(300);
  }
  return app;
}

test('P3: the receive screen\'s onion inbox is answered with an address once, and the request on screen names it (ENG-672)', async () => {
  let app;
  const s = await flow({ seed: true, tor: 'up', phase: 'P3', answers: NO_PRICE }, async (s) => {
    app = await receiveScreenRendering(s);
    assert.equal(/UNKNOWN ACTION/i.test(s.window.document.body.textContent), false, 'no "Unknown action" anywhere on screen');
  });
  assert.equal(s.host.asked.get('inboxOpen'), 1, 'asked once for the whole screen, not re-asked');
  const answers = s.host.answered.filter((a) => a[0] === 'inboxOpen');
  assert.equal(answers.length, 1);
  assert.equal(answers[0][2], null);
  const onion = JSON.parse(answers[0][1]).onion;
  assert.equal(onion, s.host.state.inbox, 'the address the phone opened');
  assert.deepEqual(plain(app._inboxUrl), { onion }, 'the page holds it, and only it: no Nostr address on Android yet');
  // a request made for the screen names it as where the payer posts
  const req = s.W.decodeRequest(s.W.paymentRequest(21, { deliverTo: app._inboxUrl, lock: false }));
  assert.deepEqual(plain(req.delivery), { kind: 'onion', target: onion });
});

test('the receive screen\'s onion inbox: an "Unknown action" reply is asked again every 4 s (today\'s bug, as a control)', async () => {
  // What main did before ENG-672. The control that shows the flow above can fail.
  const s = await flow({ seed: true, tor: 'up', phase: 'P3',
    answers: { ...NO_PRICE, inboxOpen: () => [null, 'Unknown action: inboxOpen'] } }, async (s) => {
    await receiveScreenRendering(s);
  });
  assert.ok(s.host.asked.get('inboxOpen') >= 3, 'asked again and again: ' + s.host.asked.get('inboxOpen'));
});

/* Two phones on P3 hosts, as Android ships them, on one mint: the receiver's
 * request names its onion; the payer decodes it and pays it with `onionPost`,
 * which the fake Tor carries to the receiver's inbox (`o.onionPeer`): the
 * receiver's page is handed the payment (`_requestPaid`), redeems it at the
 * mint, and answers (`inboxAnswer`), and the payer hears that answer. */
test('P3 two phones: a payment request paid over the onion, payer to receiver (ENG-672)', async () => {
  let receiver, onion;
  // the receiver is a phone of its own: its own seed, so the two never make the same secrets at the one mint
  await flow({ seed: true, tor: 'up', phase: 'P3', seedLabel: 'conformance receiver' }, async (r) => {
    receiver = r;
    await r.until(() => r.W.connected === true, 30000, 'the receiver connected');
    onion = (await r.W.openInbox()).onion;
    const creq = r.W.paymentRequest(8, { deliverTo: { onion }, lock: false });
    assert.match(String(creq), /^creq/, 'a request was made');
    await flow({ seed: true, tor: 'up', phase: 'P3', onionPeer: r.host }, async (p) => {
      // one mint for both phones: the same keys, one record of what is spent
      await fundedWallet(p, 64);
      r.host.mint = p.host.mint;
      const req = p.W.decodeRequest(creq);
      assert.deepEqual(plain(req.delivery), { kind: 'onion', target: onion }, 'the payer reads the onion out of the request');
      const sent = await p.W.payRequest(req, () => {}, { overpayOk: true });
      assert.ok(sent && sent.sats >= 8, 'the payment was delivered: ' + JSON.stringify(sent && sent.sats));
      const posts = p.host.answered.filter((a) => a[0] === 'onionPost');
      assert.deepEqual(posts, [['onionPost', '200\n{"ok":true}', null]], 'the payer heard the receiver\'s 200');
      assert.ok(p.host.posted.some((m) => m.action === 'onionPost' && m.url === onion), 'posted to the receiver\'s onion');
    });
    await r.until(() => r.host.inboxAnswers.length > 0, 20000, 'the receiver to answer');
    assert.deepEqual(r.host.inboxAnswers, [[200, '{"ok":true}']], 'the receiver took it and said so');
    assert.ok(r.host.pushes.includes('_requestPaid'), 'the receiver was handed the payment');
  });
  assert.ok(receiver.host.asked.get('inboxAnswer') >= 1);
});
