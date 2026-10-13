'use strict';
/* flows-tap-listen.test.js — the payer's listen on a funded home screen (ENG-671, ENG-658).
 *
 * A funded wallet's home screen listens for a receiver with no one acting
 * (`tapPayStart`). Android answered that listen "Stopped." at once until
 * ENG-671, and the page, reading a search that ended at once, asked again,
 * backing off from one second to a minute: a re-ask every few seconds for as
 * long as Foxy was open (ENG-658). The listen is now answered only by an offer:
 * a phone whose Bluetooth cannot listen says why as a stage, once, and the call
 * stays open. Here: Bluetooth allowed, a phone with none, and, as the control,
 * the "Stopped." that made the page ask again. Bluetooth off and refused, and a
 * listen that hears a receiver, are flows-tap-pay.test.js's.
 *
 * The checks after every flow (no violation, nothing on the network, every
 * answer in the contract), and the record of what the flows posted for the
 * coverage check (test/coverage/flows-coverage.test.js), are lib/flows.js's.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const { wait } = require('../lib/session');
const { record, flow, toFundedHome, REARM_WINDOW_MS, RECEIVE_ALSO_ASKS } = require('../lib/flows');

record(__filename);

for (const bluetooth of ['allowed', 'unsupported']) {
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

test('a listen answered "Stopped." at once is asked again and again (the ENG-658 bug, as a control)', async () => {
  // What main a7e7497 answered (the p1 stub on a P3 host). The control that shows the flows above can fail.
  const s = await flow({ seed: true, tor: 'up', phase: 'P3', bluetooth: 'allowed', unknown: RECEIVE_ALSO_ASKS,
    answers: { tapPayStart: (m) => (m.now ? ['ok', null] : [null, 'Stopped.']) } }, async (s) => {
    await toFundedHome(s, 2000);
    const before = s.host.asked.get('tapPayStart');
    await wait(REARM_WINDOW_MS);
    // backing off from a second to a minute: asked again inside the window the flows above hold quiet, and again before it
    assert.ok(s.host.asked.get('tapPayStart') > before, 'asked again: ' + before + ' then ' + s.host.asked.get('tapPayStart'));
    assert.ok(s.host.asked.get('tapPayStart') >= 3, 'and again and again: ' + s.host.asked.get('tapPayStart'));
  });
  assert.ok(s.host.answered.filter((a) => a[0] === 'tapPayStart').every((a) => a[2] === 'Stopped.'));
});
