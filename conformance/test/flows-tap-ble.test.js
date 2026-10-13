'use strict';
/* flows-tap-ble.test.js — tap to pay's receiving side on a phone with Bluetooth (ENG-670).
 *
 * On the air, a payer linked and paid over the tap, answered with inboxAnswer;
 * and a phone whose Bluetooth is off.
 *
 * The checks after every flow (no violation, nothing on the network, every
 * answer in the contract), and the record of what the flows posted for the
 * coverage check (test/coverage/flows-coverage.test.js), are lib/flows.js's.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const { record, flow, askAsPage, IN_A_TAP_LINK, RECEIVE_ALSO_ASKS, toReceiveScreenWithTapPressed, NO_PRICE } = require('../lib/flows');

record(__filename);

/* ENG-670: a phone with Bluetooth (its first-install APPROVE is in
 * flows-p3.test.js). The receive screen goes on the air (`searching` after the
 * reply); a payer comes near, links (the four digits) and pays over the
 * link; the payment reaches the page as `_requestPaid(body, key, "tap")` and the
 * page's `inboxAnswer` for that key is what goes back as M6. Then the receiving
 * side's words over that link, a shake while armed, the phone turned over, and the
 * screen let go. */

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
