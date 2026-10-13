'use strict';
/* flows-nostr.test.js — the Nostr transport (ENG-677).
 *
 * The receive screen's request naming both of the phone's addresses, Nostr
 * first and the onion second, as an iPhone's does; a second Foxy reading the
 * same request still pays the onion (deliveryFor prefers it); two phones on P3
 * hosts with a request that names only a Nostr key (what a browser wallet's
 * request looks like, and an iPhone's when its onion is down), paid with
 * `nostrSend`, the receiver handed it as `_requestPaid(body, "")` and redeeming
 * it, its `inboxAnswer("")` told nothing waits; a key closed with its request
 * that hears nothing after; and, as the control, the answer without a Nostr
 * key that the page takes as an iPhone whose relays would not listen.
 *
 * The checks after every flow (no violation, nothing on the network, every
 * answer in the contract), and the record of what the flows posted for the
 * coverage check (test/coverage/flows-coverage.test.js), are lib/flows.js's.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const { record, flow, NO_PRICE, fundedWallet } = require('../lib/flows');
const { readNprofile } = require('../lib/nprofile');
const { INBOX_RELAYS } = require('../lib/host');

record(__filename);

// an object out of the page's window, as plain JSON (its prototype is the page's, not this realm's)
const plain = (x) => JSON.parse(JSON.stringify(x));

test('P3: inboxOpen names a Nostr key made for the request beside the onion, and the request lists Nostr first (ENG-677)', async () => {
  await flow({ seed: true, tor: 'up', phase: 'P3', answers: NO_PRICE }, async (s) => {
    await s.until(() => s.W.connected === true, 30000, 'connected');
    const got = plain(await s.W.openInbox());
    assert.deepEqual(Object.keys(got).sort(), ['nostr', 'onion']);
    assert.equal(got.nostr, s.host.state.nostr.nprofile);
    const profile = readNprofile(got.nostr);
    assert.equal(profile.pubkey, s.host.state.nostr.key, 'the nprofile names the key the phone listens with');
    assert.deepEqual(profile.relays, INBOX_RELAYS, 'and the four relays it listens on');
    // asked again while it listens: the same key
    assert.equal(plain(await s.W.openInbox()).nostr, got.nostr);
    const req = s.W.decodeRequest(s.W.paymentRequest(21, { deliverTo: got, lock: false }));
    assert.deepEqual(plain(req.delivery), { kind: 'onion', target: got.onion }, 'a Foxy reading it still goes phone to phone');
    // the request's transports: Nostr first, then the onion, so a browser wallet takes the one it can reach
    assert.deepEqual(plain(req.transports), ['nostr', 'post']);
    // and the Nostr one, read the way another wallet reads it, names this key with NIP-17's tag
    const raw = s.window.CashuTS.decodePaymentRequest(s.W.paymentRequest(21, { deliverTo: got, lock: false }));
    assert.equal(raw.transport[0].target, got.nostr);
    assert.deepEqual(plain(raw.transport[0].tags), [['n', '17']]);
    // closed with the request: a fresh key next time
    await s.W.closeInbox(0);
    assert.equal(s.host.state.nostr, null);
    const again = plain(await s.W.openInbox());
    assert.notEqual(again.nostr, got.nostr, 'a key is made for one request and thrown away with it');
  });
});

test('P3: with no relay listening the request goes with its onion alone, as on an iPhone (the control)', async () => {
  await flow({ seed: true, tor: 'up', phase: 'P3', nostrDeaf: true, answers: NO_PRICE }, async (s) => {
    await s.until(() => s.W.connected === true, 30000, 'connected');
    const got = plain(await s.W.openInbox());
    assert.deepEqual(Object.keys(got), ['onion']);
  });
});

/* Two phones on P3 hosts, as Android ships them, on one mint: the receiver's
 * request names only its Nostr key; the payer pays it with `nostrSend`, which
 * the fake relays carry to the receiver's inbox (`o.nostrPeer`): the receiver's
 * page is handed the payment (`_requestPaid(body, "")`), redeems it at the mint
 * and answers as it answers every payment, and nobody is waiting on that answer. */
test('P3 two phones: a payment request paid over Nostr, payer to receiver (ENG-677)', async () => {
  let creq, nostr, before;
  await flow({ seed: true, tor: 'up', phase: 'P3', seedLabel: 'conformance nostr receiver' }, async (r) => {
    await r.until(() => r.W.connected === true, 30000, 'the receiver connected');
    nostr = (await r.W.openInbox()).nostr;
    assert.match(String(nostr), /^nprofile1[02-9ac-hj-np-z]+$/);
    creq = r.W.paymentRequest(8, { deliverTo: { nostr }, lock: false });
    assert.match(String(creq), /^creq/, 'a request was made');
    before = r.host.pushes.filter((p) => p === '_requestPaid').length;
    await flow({ seed: true, tor: 'up', phase: 'P3', nostrPeer: r.host }, async (p) => {
      await fundedWallet(p, 64);
      r.host.mint = p.host.mint;
      const req = p.W.decodeRequest(creq);
      assert.deepEqual(plain(req.delivery), { kind: 'nostr', target: nostr }, 'the payer reads the Nostr key out of the request');
      const sent = await p.W.payRequest(req, () => {}, { overpayOk: true });
      assert.ok(sent && sent.sats >= 8, 'the payment was published: ' + JSON.stringify(sent && sent.sats));
      const sends = p.host.answered.filter((a) => a[0] === 'nostrSend');
      assert.deepEqual(sends, [['nostrSend', String(INBOX_RELAYS.length), null]], 'every relay the request names took it');
      const posted = p.host.posted.filter((m) => m.action === 'nostrSend');
      assert.equal(posted.length, 1);
      assert.equal(posted[0].target, nostr, 'sent to the key the request named');
      assert.equal(p.host.asked.get('onionPost') || 0, 0, 'no onion was named, none was tried');
    });
    assert.equal(r.host.pushes.filter((x) => x === '_requestPaid').length, before + 1, 'the receiver was handed the payment');
    // the page redeems it and answers it, with the empty key, as it answers every payment: nobody waits on it
    await r.until(() => r.host.answersAsked.some((a) => a[0] === ''), 20000, 'the receiver to answer the payment');
    assert.deepEqual(r.host.answersAsked.filter((a) => a[0] === ''), [['', 200]], 'redeemed: the receiver took it');
    assert.deepEqual(r.host.answered.filter((a) => a[0] === 'inboxAnswer'), [['inboxAnswer', null, 'No payment is waiting on that answer.']]);
  });
});

test('P3 two phones: a key closed with its request hears nothing after, though the relays took the payment (ENG-677)', async () => {
  await flow({ seed: true, tor: 'up', phase: 'P3', seedLabel: 'conformance nostr closed' }, async (r) => {
    await r.until(() => r.W.connected === true, 30000, 'the receiver connected');
    const nostr = (await r.W.openInbox()).nostr;
    const creq = r.W.paymentRequest(8, { deliverTo: { nostr }, lock: false });
    await r.W.closeInbox(0);
    await flow({ seed: true, tor: 'up', phase: 'P3', nostrPeer: r.host }, async (p) => {
      await fundedWallet(p, 64);
      r.host.mint = p.host.mint;
      await p.W.payRequest(p.W.decodeRequest(creq), () => {}, { overpayOk: true });
      assert.deepEqual(p.host.answered.filter((a) => a[0] === 'nostrSend'), [['nostrSend', String(INBOX_RELAYS.length), null]]);
    });
    await r.settle(500);
    assert.equal(r.host.pushes.includes('_requestPaid'), false, 'nothing reached a key that was thrown away');
  });
});
