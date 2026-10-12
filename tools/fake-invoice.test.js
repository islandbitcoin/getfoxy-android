'use strict';
/* fake-invoice.test.js — the test melts' invoice is a well-formed BOLT11 one. */
const test = require('node:test');
const assert = require('node:assert/strict');
const { fakeInvoice, polymod, hrpExpand, CHARSET, to8 } = require('./fake-invoice');

function parse(inv) {
  const at = inv.lastIndexOf('1');
  const hrp = inv.slice(0, at);
  const words = [...inv.slice(at + 1)].map((c) => CHARSET.indexOf(c));
  return { hrp, words };
}

test('an 8-sat invoice: lnbc80n, a valid bech32 checksum, the fields a decoder needs, a 65-byte signature', () => {
  const inv = fakeInvoice(8, 1_790_000_000_000);
  const { hrp, words } = parse(inv);
  assert.equal(hrp, 'lnbc80n');
  assert.ok(words.every((w) => w >= 0), 'only bech32 characters');
  assert.equal(polymod([...hrpExpand(hrp), ...words]), 1, 'the checksum holds');
  const data = words.slice(0, -6);
  const ts = data.slice(0, 7).reduce((a, w) => a * 32 + w, 0);
  assert.equal(ts, 1_790_000_000);
  const fields = {};
  let i = 7;
  const end = data.length - 104;
  while (i < end) {
    const c = CHARSET[data[i]], len = data[i + 1] * 32 + data[i + 2];
    fields[c] = data.slice(i + 3, i + 3 + len);
    i += 3 + len;
  }
  assert.equal(i, end, 'the tagged fields end where the signature starts');
  assert.equal(to8(fields.p).length, 33, 'p: a 32-byte payment hash (52 words)');
  assert.equal(fields.p.length, 52);
  assert.equal(fields.s.length, 52, 's: a payment secret');
  assert.equal(to8(fields.d).toString().replace(/\0+$/, ''), 'foxy test melt');
  assert.deepEqual(fields['9'], [16, 8, 0]);
  const sig = to8(data.slice(end));
  assert.equal(sig.length, 65);
  assert.equal(sig[64], 0, 'recovery id 0');
  assert.ok(BigInt('0x' + sig.subarray(32, 64).toString('hex')) <= BigInt('0x7FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF5D576E7357A4501DDFE92F46681B20A0'), 'low s');
  assert.notEqual(fakeInvoice(8), fakeInvoice(8), 'a fresh hash and key each time');
});

test('the amount is the hrp\'s, and nonsense is refused', () => {
  assert.ok(fakeInvoice(1).startsWith('lnbc10n1'));
  assert.ok(fakeInvoice(64).startsWith('lnbc640n1'));
  assert.throws(() => fakeInvoice(0), /above zero/);
  assert.throws(() => fakeInvoice(1.5), /whole number/);
});
