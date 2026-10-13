'use strict';
/* lib/flows.js — what the flow files (test/flows-*.test.js) share.
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
 * The flows are split across files by area so that each file stays well under
 * node:test's per-file timeout on a slow runner; node --test runs each file in
 * its own process. The coverage check (test/coverage/flows-coverage.test.js)
 * still needs what every flow posted, so each flow file writes what its flows
 * posted and the pushes they delivered to SEEN_DIR as it ends (record()), and
 * the coverage check, run after all of them (package.json), reads them all and
 * fails if any flow file wrote nothing.
 */
const fs = require('node:fs');
const path = require('node:path');
const { after } = require('node:test');
const assert = require('node:assert/strict');
const { boot, wait } = require('./session');

/* Where each flow file leaves what its flows posted (git-ignored; cleared by
 * package.json's pretest before every npm test). */
const SEEN_DIR = path.join(__dirname, '..', '.flows-seen');

const seen = new Map();
const pushesSeen = new Set();

async function flow(opts, body) {
  const s = await boot(opts);
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

/* Called once at the top of each flow file with its __filename: when the file's
 * tests are done, what they posted is written for the coverage check. Written
 * whether or not a flow failed; a failed flow fails its own file. */
function record(file) {
  after(() => {
    fs.mkdirSync(SEEN_DIR, { recursive: true });
    const out = { file: path.basename(file), seen: Object.fromEntries(seen), pushes: [...pushesSeen] };
    fs.writeFileSync(path.join(SEEN_DIR, path.basename(file) + '.json'), JSON.stringify(out, null, 1));
  });
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

/* Ask native as the page's card code does (08a-flashcard.js `cardOwnerPub`, `cardProof`,
 * `cardTold`: nativeJson over bridgeSeed): its own message shape, through the page's
 * window.webkit.messageHandlers.foxy, answered through FoxyWallet._scanResult to a
 * resolver kept in FoxyWallet._scans, as bridgeSeed keeps one. Not a flow of the page's,
 * so the coverage list (NOT_EXERCISED) still says the page posts these only in a card session. */
function askAsPage(s, action, extra) {
  const w = s.window;
  return new Promise((ok, no) => {
    const id = 's' + Date.now() + Math.random().toString(36).slice(2, 7);
    s.W._scans[id] = { ok: (t) => ok(String(t)), no: (e) => no(new Error(String((e && e.message) || e))) };
    w.webkit.messageHandlers.foxy.postMessage(Object.assign({ action, id }, extra));
  });
}

/* Tap to pay's receiving side over a link (ENG-670): P3, asked by the flows that
 * make one. (`inboxAnswer` is the onion inbox's too since ENG-672, asked by the
 * two-phone flow of flows-onion.test.js.) */
const IN_A_TAP_LINK = ['tapChange', 'tapAsking', 'tapChangeDue', 'tapTerms', 'inboxAnswer', 'shakeStart', 'shakeStop'];

/* Tap to pay's paying side (ENG-671): P3, asked by the flows that listen on a funded
 * wallet and pay over the link (flows-tap-pay.test.js). */
const IN_A_PAY_LINK = ['tapPayStart', 'tapPayStop', 'tapSend', 'tapChangeKept', 'tapQuote'];

/* The receive invoice screen, as a person reaches it, on a P3 host: through the
 * first-install screens (the gate is up until then, and nothing arms under it),
 * an invoice made, the screen up, and TAP pressed, which raises the TAP HERE card
 * over the QR the screen armed for itself. Everything else the receive screen asks
 * is answered on a P3 host now: shake to TAP since ENG-670 and the onion inbox
 * since ENG-672, so nothing is left for `unknown`. Returns the app. */
const RECEIVE_ALSO_ASKS = new Set();
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

/* A wallet holding `sats`, minted at the conformance mint over mintRequest. */
async function fundedWallet(s, sats) {
  const W = s.W;
  await s.until(() => W.connected === true, 30000, 'connected');
  const inv = await W.invoice(sats, 'conformance send');
  assert.ok(await W.claim(inv.hash || inv.quote || inv.id), 'the invoice was claimed: minted');
  s.logic().refreshBalance();
  await s.until(() => s.logic().state.balSats === sats, 10000, 'the balance to read ' + sats);
}

/* Through the first-install screens to a wallet holding sats, on its home screen, which
 * listens for a tap by itself (ENG-671: flows-tap-listen.test.js, flows-tap-pay.test.js). */
async function toFundedHome(s, sats) {
  const W = s.W, w = s.window;
  (await toBluetoothScreen(s)).click();
  await s.until(() => W.connected === true && !w.FoxyGate.visible(), 30000, 'the wallet, with the gate gone');
  await fundedWallet(s, sats);
  await s.until(() => s.host.asked.get('tapPayStart') > 0, 10000, 'the home screen to listen for a tap');
}

module.exports = {
  SEEN_DIR, seen, pushesSeen, flow, record,
  routeActions, shows, QUIET_MS, staysQuiet,
  toBluetoothScreen, askAsPage,
  IN_A_TAP_LINK, IN_A_PAY_LINK, RECEIVE_ALSO_ASKS, toReceiveScreenWithTapPressed, REARM_WINDOW_MS, NO_PRICE,
  fundedWallet, toFundedHome,
};
