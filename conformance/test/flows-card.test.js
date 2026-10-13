'use strict';
/* flows-card.test.js — the Flashcard: owner key, signed time, session, and tap.
 *
 * A card's owner key, proofs and signed time (ENG-666); the card session through
 * the gate (ENG-667); the tap and card doors with no radio; and a card held to the
 * phone, `_cardTapped` (ENG-668). A P2 host may serve none of it.
 *
 * The checks after every flow (no violation, nothing on the network, every
 * answer in the contract), and the record of what the flows posted for the
 * coverage check (test/coverage/flows-coverage.test.js), are lib/flows.js's.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const { boot } = require('../lib/session');
const { record, seen, flow, askAsPage, fundedWallet } = require('../lib/flows');

record(__filename);

test('P3 (ENG-666): a card\'s owner key, its proofs and the signed time are answered as the page\'s card code takes them', async () => {
  // The fake phone holds abandon … about, the first case of the iOS vectors; its card key is secp256k1's G.
  const V = require('../../app/src/test/resources/fixtures/card-owner-vectors.json');
  const CardOwner = require('../lib/card-owner');
  const c = V.cases[0];
  const nonce = '000102030405060708090a0b0c0d0e0f';
  const s = await boot({ seed: true, tor: 'up', phase: 'P3', nowMs: () => 1800000000250 });
  try {
    await s.until(() => s.W.connected === true, 30000, 'the page to connect to its default mint');
    // cardOwnerPub: { key } → { pub }, which the page takes only as 04 and 128 hex
    const pub = JSON.parse(await askAsPage(s, 'cardOwnerKey', { key: c.cardKey })).pub;
    assert.match(pub, /^04[0-9a-f]{128}$/);
    assert.equal(pub, c.pub, 'the iPhone\'s owner key for the same words and card');
    // cardProof: { key, label, nonce, value } → { sig }, which the page takes as 8 to 72 bytes of hex
    for (const [label, value] of [['change-pin', '31323334'], ['set-limit', '000186a0'], ['set-owner', '04' + 'ab'.repeat(64)], ['load', ''], ['reset', '']]) {
      const sig = JSON.parse(await askAsPage(s, 'cardOwnerSign', { key: c.cardKey, label, nonce, value: String(value || '') })).sig;
      assert.match(sig, /^(?:[0-9a-f]{2}){8,72}$/);
      assert.ok(CardOwner.verify(pub, CardOwner.message(label, Buffer.from(nonce, 'hex'), Buffer.from(value, 'hex')), sig), label + ' verifies against the owner key');
    }
    await assert.rejects(askAsPage(s, 'cardOwnerSign', { key: c.cardKey, label: 'lock', nonce, value: '' }), /^Error: bad request$/, 'LOCK_CARD is never signed');
    // cardTold: {} → { time, sig }, a whole number of seconds the card checks against the page's own time key
    const told = JSON.parse(await askAsPage(s, 'cardTime', {}));
    assert.equal(told.time, 1800000000);
    assert.match(told.sig, /^(?:[0-9a-f]{2}){8,72}$/);
    assert.ok(CardOwner.verify(s.W.cardTimeKey, CardOwner.timeMessage(told.time), told.sig), 'the page\'s CARD_TIME_KEY takes it');
    for (const a of ['cardOwnerKey', 'cardOwnerSign', 'cardTime']) assert.ok(s.host.asked.get(a) > 0, a + ' asked of the P3 host');
    await s.settle(300);
  } finally {
    const violations = s.violations();
    s.close();
    assert.deepEqual(violations, [], 'violations');
    assert.deepEqual(s.host.replyProblems, [], 'the fake host answered outside the contract');
  }
});

test('the card actions on a P2 host are a violation: Android serves them from P3 (ENG-666)', async () => {
  const s = await boot({ seed: true, tor: 'up', phase: 'P2' });
  try {
    await assert.rejects(askAsPage(s, 'cardTime', {}), /Unknown action: cardTime/);
    assert.ok(s.host.violations.some((v) => v.kind === 'unserved' && v.detail.includes('cardTime')));
  } finally {
    s.close();
  }
});

/* ENG-667: a card session, as the page runs one, against Android's half of it (lib/card-host.js:
 * CardBridge.kt and CardGate.kt's rules) with iOS's own model of the card behind it
 * (lib/flashcard-card.js, getfoxy/iOS tests/flashcard-card.js at the pin). The page's own card code
 * opens the sheet (cardBegin), chooses the applet and talks to it (cardSend) through the gate, says
 * how it goes (cardSay), and closes it (cardEnd): a new card set up with an owner and a PIN, money
 * put on it and its balance read, a payment taken from it at the conformance mint, a card taken away
 * part way, and a phone that cannot read one. Every message is held to the contract as any flow's. */
const { makeCard } = require('../lib/flashcard-card');
const CardHost = require('../lib/card-host');

test('P3 (ENG-667): a card is set up, loaded, read and paid from through the card session, every command through the gate', async () => {
  // the intro film never ends in jsdom, and the custody notice would sit over home: both as already seen
  const storage = { 'foxy.intro.seen': '2026-10-12', 'foxy.custody.told': JSON.stringify({ Minibits: true }) };
  const s = await flow({ seed: true, tor: 'up', phase: 'P3', storage, p2pk: true }, async (s) => {
    const W = s.W;
    await fundedWallet(s, 3000);
    const card = makeCard({ window: s.window, format: 4 });
    s.host.state.card = card;
    const tap = (fn) => W.cardSession('Tap behind the phone.', fn);
    // set up: an owner (this phone's key for it), a PIN, the record of this mint
    await tap((link) => W.cardSetUp(link, { pin: '1234', recoverable: true }));
    assert.ok(s.host.asked.get('cardOwnerKey') > 0 && s.host.asked.get('cardOwnerSign') > 0, 'set up under the owner\'s key, from this phone\'s words');
    // money onto it, and read back
    const added = await tap((link) => W.cardAdd(link, { sats: 1000, pin: '1234' }));
    assert.equal(added.sats, 1000);
    assert.equal(card.balance(), 1000, 'the card holds what was put on it');
    // the line on the sheet changed as the card screens change it (`link.say`, cardSession's own)
    const seen = await tap((link) => link.say('Keep the card there').then(() => W.cardLook(link)));
    assert.ok(s.host.cardSheet.includes('say: Keep the card there'));
    assert.equal(seen.balance, 1000, 'and the page reads it back through the gate');
    // a payment from it, taken by this phone at the mint
    const paid = await tap((link) => W.cardPay(link, { sats: 300, pin: '1234', hold: true }));
    assert.equal(paid.sats, 300);
    assert.equal(card.balance(), 700, 'what left the card is what was paid');
    // what native saw: every command carried was the applet's choosing or one of its 31, after the choosing
    const carried = s.host.cardCarried;
    assert.ok(carried.length > 20, carried.length + ' commands carried');
    let chosen = false;
    for (const hex of carried) {
      const c = CardHost.read(Buffer.from(hex, 'hex'));
      assert.ok(c === 'select' || (chosen && CardHost.INSTRUCTIONS.has(c)), hex.slice(0, 12) + ' went through the gate');
      if (c === 'select') chosen = true;
    }
    assert.ok(!carried.some((h) => /^b052/.test(h) || /^b050/.test(h) || /^b033/.test(h)), 'no SET_TAG, lock or PIN-form limit reached the card');
    const sheet = s.host.cardSheet;
    assert.equal(sheet.filter((l) => l.startsWith('begin: ')).length, 4, 'four sheets: ' + sheet.join(' | '));
    assert.equal(sheet.filter((l) => l.startsWith('connected: Reading the card')).length, 4, 'each told connected');
    assert.equal(sheet.filter((l) => l.startsWith('end: ')).length, 4, 'and each closed');
    assert.ok(s.host.asked.get('cardSay') > 0, 'the page said how the tap went on the sheet');
    assert.ok(s.host.pushes.includes('_card'), 'progress pushed');

    // the card taken away after two commands: the page is told it was lost, and the sheet ends in red
    s.host.state.cardPull = 2;
    const lost = await tap((link) => W.cardLook(link)).then(() => 'read', (e) => e);
    assert.notEqual(lost, 'read', 'a card taken away is not a card read');
    assert.ok(sheet.includes('lost: the tag was lost'), 'lost pushed: ' + sheet.slice(-4).join(' | '));
    assert.match(sheet[sheet.length - 1], /^error: /, 'the sheet closes with the reason in red');

    // a phone that cannot read a card, and a sheet nobody holds a card to
    s.host.state.card = null;
    const noNfc = await tap((link) => W.cardLook(link)).then(() => null, (e) => e);
    assert.equal(noNfc && noNfc.card, 'no-nfc', 'NFC is not available on this phone');
    s.host.state.card = 'away';
    const none = await tap((link) => W.cardLook(link)).then(() => null, (e) => e);
    assert.equal(none && none.card, 'cancelled', 'the sheet timed out');
    assert.equal(none.message, 'No card was tapped.');
  });
  for (const a of ['cardBegin', 'cardSend', 'cardSay', 'cardEnd']) assert.ok(s.host.asked.get(a) > 0, a + ' asked of the P3 host');
});

test('P3 (ENG-667): the card that leaves part way is looked for again by the same sheet (cardAgain), and the applet chosen again', async () => {
  const storage = { 'foxy.intro.seen': '2026-10-12', 'foxy.custody.told': JSON.stringify({ Minibits: true }) };
  const s = await flow({ seed: true, tor: 'up', phase: 'P3', storage, p2pk: true }, async (s) => {
    const W = s.W;
    await fundedWallet(s, 1000);
    const card = makeCard({ window: s.window, format: 4 });
    s.host.state.card = card;
    await W.cardSession('Tap behind the phone.', (link) => W.cardSetUp(link, { pin: '1234', recoverable: true }));
    // the page's own retry: its fn fails with the card gone, `opts.again` gives the line, and the sheet looks again
    let tries = 0;
    const read = await W.cardSession('Tap behind the phone.', (link) => {
      tries += 1;
      if (tries === 1) s.host.state.cardPull = 1;
      return W.cardLook(link);
    }, { again: (e) => (e && e.card === 'gone' ? 'Hold the card there again' : '') });
    assert.equal(tries, 2, 'the read ran again in the same sheet');
    assert.equal(read.balance, 0);
    const sheet = s.host.cardSheet;
    assert.ok(sheet.includes('again: Hold the card there again'), sheet.join(' | '));
    // after the card came back, the first thing carried was the applet's choosing
    const carried = s.host.cardCarried;
    assert.equal(s.host.cardAgainAt.length, 1, 'found again once');
    assert.equal(CardHost.read(Buffer.from(carried[s.host.cardAgainAt[0]], 'hex')), 'select');
  });
  assert.ok(s.host.asked.get('cardAgain') > 0, 'cardAgain asked of the P3 host');
});

test('the card session on a P2 host is a violation: Android serves it from P3 (ENG-667)', async () => {
  const s = await boot({ seed: true, tor: 'up', phase: 'P2' });
  try {
    await assert.rejects(askAsPage(s, 'cardBegin', { text: 'Tap' }), /Unknown action: cardBegin/);
    assert.ok(s.host.violations.some((v) => v.kind === 'unserved' && v.detail.includes('cardBegin')));
  } finally {
    s.close();
  }
});

test('tap to pay and the card: their doors, with no radio', async () => {
  await flow({ seed: true, tor: 'up' }, async (s) => {
    const W = s.W;
    await s.until(() => W.connected === true, 30000, 'connected');
    await W.shakeSense(true);
    await W.shakeSense(false);
    await W.tapReceive('lnbc80n1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypq', false).catch(() => {});
    await W.tapReceiveStop();
    await W.tapPayNow().catch(() => {});
    await W.tapPay().catch(() => {});
    await W.tapSend('cashuBx').catch(() => {});
    await W.tapChange('cashuBx').catch(() => {});
    await W.tapAsking().catch(() => {});
    await W.tapQuote('{}').catch(() => {});
    await W.tapTerms('{}').catch(() => {});
    await W.tapChangeDue().catch(() => {});
    await W.tapChangeKept(true).catch(() => {});
    await W.tapPayStop();
    await W.cardStop();
    await W.cardSession('Tap behind the phone.', () => Promise.resolve(), {}).catch(() => {});
  });
});

/* A card held to the phone (getfoxy/iOS 9832f7d): native says so with `_cardTapped`, a P3
 * push since ENG-668 (flashcard/CardTapDelivery.kt). Only when a card is held up: a page never
 * told must boot, connect and run as it did before the push existed, and a host of an earlier
 * phase may not send it. */
const cardTapLines = (s) => s.page.logs.map((l) => l.join(' ')).filter((l) => l.includes('[foxy] card tap:'));

test('the card tap (_cardTapped, P3): a page never told boots and runs, puts the call on its wallet, and opens no card', async () => {
  await flow({ seed: true, tor: 'up' }, async (s) => {
    const W = s.W, app = s.logic();
    await s.until(() => W.connected === true, 30000, 'the page to connect with no _cardTapped push');
    await s.until(() => s.host.asked.get('covered') > 0 && s.host.asked.get('price') > 0, 30000, 'covered and price, as any boot');
    // bootWallet puts it on the wallet once the lock is decided (06-wallet-boot-and-restore.js); native asks until it answers true
    await s.until(() => typeof W._cardTapped === 'function', 30000, 'the app to put _cardTapped on the wallet');
    await s.settle(1000);
    assert.equal(app.state.screen, 'home', 'booted to home');
    assert.ok(!s.host.pushes.includes('_cardTapped'), 'nothing pushed _cardTapped');
    assert.deepEqual(cardTapLines(s), [], 'the page heard of no card tap');
    assert.ok(!s.host.asked.has('cardBegin'), 'and opened no card session');
  });
});

test('the card tap (_cardTapped, P3): answered true, kept while the gate is up, acted on at home once it goes; a P2 host may not send it', async () => {
  // the intro film never ends in jsdom, and the custody notice would sit over home: both as already seen
  const storage = { 'foxy.intro.seen': '2026-10-12', 'foxy.custody.told': JSON.stringify({ Minibits: true }) };
  await flow({ seed: true, tor: 'up', storage }, async (s) => {
    const W = s.W, d = s.window.document;
    await s.until(() => W.connected === true && typeof W._cardTapped === 'function', 30000, 'connected, with _cardTapped on the wallet');
    await s.until(() => d.getElementById('ftg-warn-go'), 30000, 'the first-launch warning');
    // what native evaluates (CardAddress.pageCall), and its answer: true, the page has the request
    assert.equal(s.host.push('_cardTapped'), true, 'the page answers true once it has the request');
    assert.equal(s.host.push('_cardTapped'), true, 'a second tap while one waits is the same tap, still true');
    await s.until(() => cardTapLines(s).length > 0, 5000, 'a card tap log line');
    assert.match(cardTapLines(s)[0], /kept until Foxy is open, it is starting/);
    assert.ok(!s.host.asked.has('cardBegin'), 'no card session under the gate');
    // through the gate's first-install screens; then home, where nothing is in progress (cardTapRule): act
    d.getElementById('ftg-warn-go').click();
    await s.until(() => d.getElementById('ftg-bt-go'), 10000, 'the Bluetooth screen after PROCEED');
    d.getElementById('ftg-bt-go').click();
    await s.until(() => s.host.asked.get('cardBegin') > 0, 10000, 'the kept tap to open the card sheet once the gate has gone');
    const lines = cardTapLines(s);
    assert.ok(lines.some((l) => /ignored, one is already waiting/.test(l)), 'the second tap was the same one');
    assert.ok(lines.some((l) => /opening the card screen from home, after \d+ s kept/.test(l)), 'acted on from home: ' + lines.join(' | '));
  });
  // P3 sends it (ENG-668); a host of an earlier phase may not
  const p2 = await boot({ seed: true, tor: 'up', phase: 'P2' });
  try {
    await p2.settle(300);
    p2.host.violations.length = 0;
    p2.host.pushAs('_cardTapped');
    assert.deepEqual(p2.host.violations.map((v) => v.kind), ['unserved'], 'a P2 host refuses to push the P3 _cardTapped');
  } finally {
    p2.close();
  }
});

/* What Android's CardTapDelivery leans on (ENG-668): the page's answer to CardAddress.PAGE_CALL is
 * its only sign. Before the app has put `_cardTapped` on the wallet the call answers nothing that
 * is true (native asks again in 0.3 s); once it has, the answer is true, and a P3 host sends it
 * with no violation of the contract. */
test('the card tap (_cardTapped, P3): the page call answers not-true until the wallet has it, then true, from a P3 host', async () => {
  const s = await boot({ seed: true, tor: 'up', phase: 'P3' });
  try {
    const call = 'window.FoxyWallet && window.FoxyWallet._cardTapped && window.FoxyWallet._cardTapped()';
    const early = s.window.eval(call);
    if (typeof s.W._cardTapped !== 'function') assert.notEqual(early, true, 'no wallet member yet: not true, so native asks again');
    await s.until(() => typeof s.W._cardTapped === 'function', 30000, 'the app to put _cardTapped on the wallet');
    s.host.violations.length = 0;
    assert.equal(s.host.pushAs('_cardTapped'), true, 'a P3 host pushes it and the page answers true');
    assert.equal(s.window.eval(call), true, 'the very string native evaluates answers true');
    assert.deepEqual(s.host.violations, [], 'no contract violation from a P3 host');
  } finally {
    s.close();
  }
});
