#!/usr/bin/env node
'use strict';
/* fake-invoice.js — a BOLT11 invoice with nothing behind it. It was the melt's
 * invoice on testnut.cashu.space (fake money) until Android moved to testing on
 * real mints with real ecash (ENG-662): it is NEVER paid on a real mint, and no
 * device leg or leak test melts to it any more (docs/DEVICE-TESTS.md "Real
 * money"; melts pay only an invoice the person supplies). What is left: an
 * invoice to show the scanner (scanned, never paid), and the vectors of
 * tools/real-money.test.js. It is signed with a fresh key and pays no one.
 *
 *   node tools/fake-invoice.js 8      # an 8-sat invoice, mainnet prefix (lnbc)
 *
 * Fields: a timestamp, a random payment hash (p) and secret (s), a description
 * (d), an hour's expiry (x), min_final_cltv 18 (c), and the features a decoder
 * requires (the variable-length onion and the payment secret, both required). The signature is
 * secp256k1 over sha256(hrp || data), low-s, recovery id 0: with no payee field
 * a decoder recovers the key from it, which is the fresh key's or another that
 * verifies the same signature; either way it is valid and pays no one.
 */
const crypto = require('crypto');

const CHARSET = 'qpzry9x8gf2tvdw0s3jn54khce6mua7l';
const N = BigInt('0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141');

function polymod(values) {
  const G = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3];
  let chk = 1;
  for (const v of values) {
    const b = chk >> 25;
    chk = ((chk & 0x1ffffff) << 5) ^ v;
    for (let i = 0; i < 5; i++) if ((b >> i) & 1) chk ^= G[i];
  }
  return chk;
}
const hrpExpand = (h) => [...[...h].map((c) => c.charCodeAt(0) >> 5), 0, ...[...h].map((c) => c.charCodeAt(0) & 31)];
function checksum(hrp, data) {
  const p = polymod([...hrpExpand(hrp), ...data, 0, 0, 0, 0, 0, 0]) ^ 1;
  return [0, 1, 2, 3, 4, 5].map((i) => (p >> (5 * (5 - i))) & 31);
}
function to5(bytes) {
  let acc = 0, bits = 0;
  const out = [];
  for (const b of bytes) {
    acc = ((acc << 8) | b) & 0xffff;
    bits += 8;
    while (bits >= 5) { bits -= 5; out.push((acc >> bits) & 31); }
  }
  if (bits) out.push((acc << (5 - bits)) & 31);
  return out;
}
function to8(words) {
  let acc = 0, bits = 0;
  const out = [];
  for (const w of words) {
    acc = ((acc << 5) | w) & 0xffff;
    bits += 5;
    while (bits >= 8) { bits -= 8; out.push((acc >> bits) & 255); }
  }
  if (bits) out.push((acc << (8 - bits)) & 255);
  return Buffer.from(out);
}
const num = (n, len) => Array.from({ length: len }, (_, i) => Math.floor(n / 2 ** (5 * (len - 1 - i))) % 32);
const tag = (c, words) => [CHARSET.indexOf(c), ...num(words.length, 2), ...words];

function fakeInvoice(sats, now = Date.now()) {
  if (!(Number.isInteger(sats) && sats > 0)) throw new Error('sats: a whole number above zero');
  const hrp = 'lnbc' + sats * 10 + 'n';
  const data = [
    ...num(Math.floor(now / 1000), 7),
    ...tag('p', to5(crypto.randomBytes(32))),
    ...tag('s', to5(crypto.randomBytes(32))),
    ...tag('d', to5(Buffer.from('foxy test melt'))),
    ...tag('x', num(3600, 3)),
    ...tag('c', num(18, 1)),
    // features: the variable-length onion (bit 8) and the payment secret (bit 14), both required
    ...tag('9', [16, 8, 0]),
  ];
  const msg = Buffer.concat([Buffer.from(hrp), to8(data)]);
  const { privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'secp256k1' });
  const sig = crypto.createSign('SHA256').update(msg).sign({ key: privateKey, dsaEncoding: 'ieee-p1363' });
  let s = BigInt('0x' + sig.subarray(32).toString('hex'));
  if (s > N / 2n) s = N - s;
  const words = [...data, ...to5(Buffer.concat([sig.subarray(0, 32), Buffer.from(s.toString(16).padStart(64, '0'), 'hex'), Buffer.from([0])]))];
  return hrp + '1' + [...words, ...checksum(hrp, words)].map((w) => CHARSET[w]).join('');
}

module.exports = { fakeInvoice, polymod, hrpExpand, CHARSET, to8 };

if (require.main === module) process.stdout.write(fakeInvoice(Number(process.argv[2] || 8)) + '\n');
