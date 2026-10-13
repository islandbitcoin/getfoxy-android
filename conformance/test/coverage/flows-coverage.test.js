'use strict';
/* flows-coverage.test.js — every action in the contract was posted by some flow,
 * or is named in NOT_EXERCISED with the reason.
 *
 * The flows run in their own files (test/flows-*.test.js), each in its own
 * process; each writes what its flows posted and the pushes they delivered to
 * lib/flows.js's SEEN_DIR as it ends. npm test clears that directory first
 * (pretest), runs every test/*.test.js, and only then runs this file, so what
 * it reads is this run's. A flow file that wrote nothing fails it: a file that
 * did not run cannot count as having covered anything.
 */
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const assert = require('node:assert/strict');
const { CONTRACT } = require('../../lib/host');
const { SEEN_DIR } = require('../../lib/flows');

const FLOW_FILES = fs.readdirSync(path.join(__dirname, '..')).filter((f) => /^flows-.*\.test\.js$/.test(f)).sort();
const seen = new Map();
const pushesSeen = new Set();

/* Actions no flow posts, each with the reason. Nothing else may be missing. */
const NOT_EXERCISED = {
  isDebug: 'nothing in the page at the pinned commit asks it (it reads window.FOXY_DEBUG)',
  counterReserveAt: 'a recovery path (reserving a range the mint said was already signed); checked statically',
  p2pkPubkeys: 'scanning for the index a token in hand is locked to; checked statically',
  p2pkKey: 'opening ecash locked to this phone; checked statically',
  counterAdvance: 'a restore lifting a counter; checked statically',
  cardTime: 'a card of software 1.14 or before, mid-tap (the ENG-667 flows tap a card of 1.20, which takes block headers)',
};

test('coverage: every action in the contract was posted by a flow, or is accounted for', (t) => {
  assert.ok(FLOW_FILES.length > 0, 'flow files found');
  const missingFiles = FLOW_FILES.filter((f) => !fs.existsSync(path.join(SEEN_DIR, f + '.json')));
  assert.deepEqual(missingFiles, [], 'every flow file recorded what it posted (run through npm test, which runs them first)');
  for (const f of FLOW_FILES) {
    const rec = JSON.parse(fs.readFileSync(path.join(SEEN_DIR, f + '.json'), 'utf8'));
    for (const [k, v] of Object.entries(rec.seen)) seen.set(k, (seen.get(k) || 0) + v);
    for (const p of rec.pushes) pushesSeen.add(p);
  }
  const actions = CONTRACT.filter((e) => e.kind === 'action').map((e) => e.name);
  const missing = actions.filter((a) => !seen.has(a) && !(a in NOT_EXERCISED));
  assert.deepEqual(missing, [], 'posted by no flow and not in NOT_EXERCISED');
  const stale = Object.keys(NOT_EXERCISED).filter((a) => seen.has(a));
  assert.deepEqual(stale, [], 'in NOT_EXERCISED but a flow posts it now: take it off the list');
  for (const m of ['haptic', 'username']) assert.ok(seen.has(m), m + ' was posted');
  const exercised = actions.filter((a) => seen.has(a)).length;
  t.diagnostic('coverage: ' + exercised + ' of ' + actions.length + ' actions posted by the flows in ' + FLOW_FILES.length + ' files; ' + Object.keys(NOT_EXERCISED).length + ' accounted for; ' + pushesSeen.size + ' pushes delivered');
});
