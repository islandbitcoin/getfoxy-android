'use strict';
/* nprofile.js — NIP-19's `nprofile` (bech32 over TLVs), for the fake host's
 * Nostr doors (ENG-677): the key a request is paid to, written as
 * nostr/NostrEvent.kt `Bech32.nprofile` writes it, and read back the way
 * NostrDelivery reads a request's. Checked against NIP-19's own worked example
 * and BIP-173's strings (test/harness.test.js), not against the Kotlin. */

const ALPHABET = 'qpzry9x8gf2tvdw0s3jn54khce6mua7l';
const GENERATOR = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3];

function polymod(values) {
  let check = 1;
  for (const v of values) {
    const top = check >>> 25;
    check = (((check & 0x1ffffff) << 5) ^ v) >>> 0;
    for (let i = 0; i < 5; i++) if ((top >>> i) & 1) check = (check ^ GENERATOR[i]) >>> 0;
  }
  return check;
}

function expand(prefix) {
  const out = [];
  for (const c of prefix) out.push(c.charCodeAt(0) >> 5);
  out.push(0);
  for (const c of prefix) out.push(c.charCodeAt(0) & 31);
  return out;
}

function convert(data, from, to, pad) {
  let acc = 0, bits = 0;
  const out = [];
  for (const v of data) {
    acc = (acc << from) | v;
    bits += from;
    while (bits >= to) { bits -= to; out.push((acc >> bits) & ((1 << to) - 1)); }
    acc &= (1 << bits) - 1;
  }
  if (pad && bits > 0) out.push((acc << (to - bits)) & ((1 << to) - 1));
  return out;
}

function encode(prefix, bytes) {
  const values = convert(bytes, 8, 5, true);
  const p = polymod(expand(prefix).concat(values, [0, 0, 0, 0, 0, 0])) ^ 1;
  const sum = [0, 1, 2, 3, 4, 5].map((i) => (p >>> (5 * (5 - i))) & 31);
  return prefix + '1' + values.concat(sum).map((v) => ALPHABET[v]).join('');
}

/* { prefix, bytes } or null: the checksum, the characters and the case must hold. */
function decode(text) {
  const s = String(text);
  const lower = s.toLowerCase();
  if (lower !== s && s.toUpperCase() !== s) return null;
  if (lower.length < 8 || lower.length > 5000) return null;
  const split = lower.lastIndexOf('1');
  if (split < 1) return null;
  const prefix = lower.slice(0, split);
  const values = [];
  for (const c of lower.slice(split + 1)) {
    const at = ALPHABET.indexOf(c);
    if (at < 0) return null;
    values.push(at);
  }
  if (values.length <= 6 || polymod(expand(prefix).concat(values)) !== 1) return null;
  return { prefix, bytes: convert(values.slice(0, -6), 5, 8, false) };
}

/* An nprofile naming a 32-byte key (hex) and relays. */
function nprofile(pubkeyHex, relays) {
  const tlv = [0, 32, ...Buffer.from(pubkeyHex, 'hex')];
  for (const r of relays) {
    const v = [...Buffer.from(r, 'utf8')];
    if (v.length <= 255) tlv.push(1, v.length, ...v);
  }
  return encode('nprofile', tlv);
}

/* { pubkey: hex, relays } of an nprofile, or null. */
function readNprofile(text) {
  const d = decode(text);
  if (!d || d.prefix !== 'nprofile') return null;
  let pubkey = null;
  const relays = [];
  let at = 0;
  const b = d.bytes;
  while (at + 2 <= b.length) {
    const type = b[at], len = b[at + 1];
    if (at + 2 + len > b.length) return null;
    const value = b.slice(at + 2, at + 2 + len);
    if (type === 0 && len === 32) pubkey = Buffer.from(value).toString('hex');
    if (type === 1) relays.push(Buffer.from(value).toString('utf8'));
    at += 2 + len;
  }
  return pubkey ? { pubkey, relays } : null;
}

module.exports = { encode, decode, nprofile, readNprofile };
