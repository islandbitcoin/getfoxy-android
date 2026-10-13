'use strict';
/* flows-p1-p2.test.js — boot, the closed route, and the P1 and P2 hosts.
 *
 * A wallet booting with a seed, on a fresh install, and from words left in page
 * storage; Tor connecting then failing with nothing that needs a route asked; and
 * a host serving only P1, then P1 and P2, each leaning on nothing later without a
 * stub.
 *
 * The checks after every flow (no violation, nothing on the network, every
 * answer in the contract), and the record of what the flows posted for the
 * coverage check (test/coverage/flows-coverage.test.js), are lib/flows.js's.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const { boot } = require('../lib/session');
const { snapshot } = require('../lib/host');
const { record, flow, routeActions, shows, staysQuiet } = require('../lib/flows');

record(__filename);

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
    // settled into Tor connecting: the page asked privacy and shows the connecting screen
    await s.until(() => s.host.asked.get('privacy') > 0 && shows(s, 'SECURING YOUR CONNECTION'), 30000, 'the connecting screen');
    await staysQuiet(s, 'while Tor was connecting');
    s.torFailed();
    // settled into Tor failed: the failure screen is up and the gate is polling privacy on it
    const before = s.host.asked.get('privacy');
    await s.until(() => shows(s, 'CANNOT CONNECT TO TOR') && s.host.asked.get('privacy') >= before + 2, 30000, 'the failed screen, re-checking');
    await staysQuiet(s, 'after Tor failed');
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

test('P1: a host serving only P1 actions and the p1 stubs boots to a defined state, and nothing leaves', async () => {
  // The flow to run against the Kotlin bridge in P1 (docs/BRIDGE.md §2, P1 answers):
  // a later-phase action with no p1 stub is an 'unserved' violation and fails it.
  const s = await flow({ seed: true, phase: 'P1' }, async (s) => {
    const W = s.W, G = s.window.FoxyGate;
    // no Tor in P1: the gate settles on CANNOT CONNECT TO TOR, re-checking privacy, offering PROCEED OFFLINE
    await s.until(() => shows(s, 'CANNOT CONNECT TO TOR') && shows(s, 'PROCEED OFFLINE') && s.host.asked.get('privacy') >= 2,
      30000, 'the no-Tor screen, re-checking');
    assert.equal(W.connected, false, 'not connected to a mint');
    await staysQuiet(s, 'on the no-Tor screen');
    // the gate's buttons, answered by their p1 stubs
    await W.torRetry();
    await W.setUnprotected(true);
    const p = W.privacy();
    assert.equal(p.tor, 'none', 'the page holds the no-Tor snapshot');
    assert.equal(p.unprotected, false, 'no unprotected path until P2');
    assert.equal(await W.orbotRefresh(), 'none');
    await W.orbotAccess();
    assert.equal(await W.captureCheck(), false);
    // PROCEED OFFLINE: the wallet itself refuses what needs a route
    G.proceedOffline();
    await assert.rejects(async () => W.rate({ fresh: true }), /offline/);
    await assert.rejects(async () => W.candles(3600, 86400), /offline/);
    await staysQuiet(s, 'working offline');
  });
  for (const a of ['hostsKnown', 'captured', 'privacy', 'seedStatus', 'torRetry', 'unprotected', 'orbotRefresh', 'orbotAccess']) {
    assert.ok(s.host.asked.get(a) > 0, 'P1 boot asked ' + a);
  }
  assert.equal(s.host.pushes.includes('_privacy'), false, 'P1 pushes no _privacy');
});

test('P2: a host serving P1 and P2 actions goes from connecting to up, mints and melts, and leans on nothing later without a stub', async () => {
  // The flow to run against the Kotlin bridge in P2: P1 and P2 served, the P3 and
  // LATER actions answered by their p1 stubs, and any without a stub an 'unserved'
  // violation that fails it.
  const s = await flow({ seed: true, tor: 'connecting', phase: 'P2' }, async (s) => {
    const W = s.W;
    // Tor connecting: the route is closed, and the page holds to it on a P2 host too
    await s.until(() => s.host.asked.get('privacy') > 0 && shows(s, 'SECURING YOUR CONNECTION'), 30000, 'the connecting screen');
    await staysQuiet(s, 'while Tor was connecting, on a P2 host');
    // Tor up, pushed as native pushes it
    s.torUp();
    await s.until(() => W.connected === true, 30000, 'the page to connect to its default mint once Tor is up');
    // mint
    const inv = await W.invoice(64, 'conformance p2');
    assert.ok(await W.claim(inv.hash || inv.quote || inv.id), 'the invoice was claimed: minted');
    assert.ok(s.host.mint.requests.includes('POST /v1/mint/bolt11'), 'minted over mintRequest');
    // melt
    await W.pay('lnbc80n1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypq').catch(() => {});
    assert.ok(s.host.mint.requests.includes('POST /v1/melt/quote/bolt11'), 'a melt quote, over mintRequest');
    assert.ok(s.host.mint.requests.includes('POST /v1/melt/bolt11'), 'and the melt');
    // a funded wallet's home listens for a tap (LATER): the stub ends each search at once
    await assert.rejects(W.tapPay(), /Stopped\./);
    // a PASTE button on screen asks for the native control (P3), without waiting on the answer
    const pane = s.window.document.createElement('div');
    pane.getBoundingClientRect = () => ({ left: 20, top: 400, width: 300, height: 60 });
    assert.equal(W.startPasteButton(pane, () => {}), true);
    W.stopPasteButton();
    // the gate's buttons a P2 host serves itself, and the two Orbot ones it stubs (LATER on Android)
    await W.torRetry();
    assert.equal(await W.orbotRefresh(), 'none');
    await W.orbotAccess();
  });
  assert.ok(s.host.pushes.includes('_privacy'), 'a P2 host pushes _privacy');
  for (const a of ['privacy', 'hostsKnown', 'mintRequest', 'price', 'captured', 'seedStatus']) {
    assert.ok(s.host.asked.get(a) > 0, 'the P2 flow asked ' + a);
  }
});
