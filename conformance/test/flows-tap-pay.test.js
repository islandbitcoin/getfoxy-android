'use strict';
/* flows-tap-pay.test.js — tap to pay's paying side (ENG-671), and ENG-658.
 *
 * A funded wallet's home screen listens for a receiver with no one acting
 * (`tapPayStart`). Android answered that listen "Stopped." at once until
 * ENG-671, and the page, reading a search that ended at once, asked again,
 * backing off from one second to a minute: a re-ask every few seconds for as
 * long as Foxy was open (ENG-658). The listen is now answered only by an offer:
 * a phone whose Bluetooth cannot listen says why as a stage, once, and the call
 * stays open. Then a listen that hears a receiver: TAP TO PAY, the link, the
 * offer and the four digits on the confirmation, the payer's words over the
 * link (M10, the payment and its M6), M11 and change arriving, and the listen
 * let go. The listen with Bluetooth allowed and with none, and the ENG-658 control,
 * are flows-tap-listen.test.js's.
 *
 * The checks after every flow (no violation, nothing on the network, every
 * answer in the contract), and the record of what the flows posted for the
 * coverage check (test/coverage/flows-coverage.test.js), are lib/flows.js's.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const { wait } = require('../lib/session');
const { record, flow, toFundedHome, REARM_WINDOW_MS, RECEIVE_ALSO_ASKS } = require('../lib/flows');
const { fakeInvoice } = require('../../tools/fake-invoice');

record(__filename);

for (const bluetooth of ['off', 'denied']) {
  test('P3 (ENG-658, ENG-671): a funded home screen listens once and is not made to ask again (Bluetooth ' + bluetooth + ')', async () => {
    const s = await flow({ seed: true, tor: 'up', phase: 'P3', bluetooth, unknown: RECEIVE_ALSO_ASKS }, async (s) => {
      const heard = [];
      s.W.onTap((ev) => { if (ev.side === 'pay') heard.push(ev.stage); });
      await toFundedHome(s, 2000);
      const first = s.host.asked.get('tapPayStart');
      // the window the page's re-listen would have asked in three more times after a "Stopped."
      await wait(REARM_WINDOW_MS);
      assert.equal(s.host.asked.get('tapPayStart'), first, 'no second listen: the first is still open');
      assert.deepEqual(s.host.answered.filter((a) => a[0] === 'tapPayStart'), [], 'and it was never answered');
      // a radio that cannot listen is said, once
      assert.deepEqual(heard, bluetooth === 'allowed' ? [] : [bluetooth]);
      await s.W.tapPayStop();
    });
    assert.ok(s.host.asked.get('tapPayStop') >= 1);
  });
}

test('P3 (ENG-671): the home screen hears a receiver: TAP TO PAY, the link and the four digits, the payer\'s words over it, and the listen let go', async () => {
  let app;
  const s = await flow({ seed: true, tor: 'up', phase: 'P3', bluetooth: 'allowed', unknown: RECEIVE_ALSO_ASKS }, async (s) => {
    const W = s.W;
    const heard = [];
    W.onTap((ev) => { if (ev.side === 'pay') heard.push(ev.stage + (ev.edge ? ' ' + ev.edge : '')); });
    await toFundedHome(s, 2000);
    app = s.logic();
    // a receiver near enough to be told about: TAP TO PAY on this screen, with the edge
    s.host.payer.nearby('left');
    await s.settle(100);
    assert.deepEqual(heard, ['nearby left']);
    // TAP TO PAY pressed: answered at once, the listen still open
    assert.equal(await W.tapPayNow(), 'ok');
    // the receiver's offer and the four digits: the listen is answered and the confirmation opens with them
    const offer = JSON.stringify({ v: 2, inv: fakeInvoice(21) });
    s.host.payer.link(offer, '4821');
    await s.until(() => app.state.tapCode === '4821', 5000, 'the four digits on the payer\'s confirmation');
    assert.deepEqual(s.host.answered.filter((a) => a[0] === 'tapPayStart' && a[1] !== 'ok').map((a) => JSON.parse(a[1])), [{ payload: offer, code: '4821' }]);
    assert.deepEqual(heard.slice(0, 3), ['nearby left', 'connecting', 'linked']);
    // M10, this phone's price
    assert.equal(await W.tapQuote(JSON.stringify({ rate: 1 })), 'ok');
    // M11, their terms, and change arriving: both reach the page as pushes
    s.host.payer.terms(JSON.stringify({ intent: 'nobody-asked', go: true }));
    s.host.payer.change('cashuBchange');
    // the page answers the change itself, once it has dealt with it: that word is what goes back as M8
    await s.until(() => s.host.asked.get('tapChangeKept') > 0, 5000, 'the page\'s word on the change');
    assert.deepEqual(s.host.answered.filter((a) => a[0] === 'tapChangeKept'), [['tapChangeKept', 'ok', null]]);
    // and only once: a second word has no change waiting
    await assert.rejects(W.tapChangeKept(true), /No change is waiting to be kept\./);
    // the payment over the held link, and the receiver's M6
    assert.equal(await W.tapSend(JSON.stringify({ proofs: [] })), 'settled');
    assert.deepEqual(s.host.state.paySent, [JSON.stringify({ proofs: [] })]);
    // the link goes: the payer's words have nowhere to go
    s.host.payer.gone();
    await assert.rejects(W.tapQuote('{}'), /That phone is no longer connected\./);
    await assert.rejects(W.tapSend('cashuBx'), /That phone is no longer connected\./);
    await W.tapPayStop();
  });
  for (const a of ['tapPayStart', 'tapPayStop', 'tapSend', 'tapQuote', 'tapChangeKept']) assert.ok(s.host.asked.get(a) > 0, a + ' asked of the P3 host');
  for (const p of ['_tapChange', '_tapTerms']) assert.ok(s.host.pushes.includes(p), p + ' delivered');
});

test('P3 (ENG-671): a second listen answers the first "Started again.", and a stop answers a waiting one "Stopped."', async () => {
  const s = await flow({ seed: true, tor: 'up', phase: 'P3', bluetooth: 'allowed', unknown: RECEIVE_ALSO_ASKS }, async (s) => {
    const W = s.W;
    await s.until(() => W.connected === true, 30000, 'connected');
    const first = W.tapPay();
    await s.settle(50);
    const second = W.tapPay();
    await assert.rejects(first, /Started again\./);
    await W.tapPayStop();
    await assert.rejects(second, /Stopped\./);
    // nothing to hand over is refused before anything else
    await assert.rejects(W.tapSend(''), /There is nothing to hand over\./);
  });
  assert.ok(s.host.answered.some((a) => a[0] === 'tapPayStart' && a[2] === 'Started again.'));
});
