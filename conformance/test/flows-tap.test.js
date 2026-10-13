'use strict';
/* flows-tap.test.js — the receive screen's TAP with no Bluetooth (ENG-656).
 *
 * Answered "ok" then told "unsupported", with no re-arm loop; and, as the
 * control, the "Unknown action" reply that re-armed every 1.2 s.
 *
 * The checks after every flow (no violation, nothing on the network, every
 * answer in the contract), and the record of what the flows posted for the
 * coverage check (test/coverage/flows-coverage.test.js), are lib/flows.js's.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const { record, flow, shows, RECEIVE_ALSO_ASKS, toReceiveScreenWithTapPressed, REARM_WINDOW_MS, NO_PRICE } = require('../lib/flows');

record(__filename);

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
