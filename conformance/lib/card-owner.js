'use strict';
/* card-owner.js — a card's owner key and its signed time, as Android answers them
 * (app/src/main/java/io/islandbitcoin/foxy/keychain/CardOwner.kt and
 * flashcard/CardTime.kt, ports of iOS's CardOwner in Foxy/Keychain/NUT13.swift and
 * Foxy/Flashcard/CardTime.swift at the pin), in Node's own crypto, so the fake
 * host answers cardOwnerKey, cardOwnerSign and cardTime with real keys and
 * signatures and refuses what the phone refuses, with the same words.
 *
 * The owner key: d = HMAC-SHA256(key = the 64-byte BIP-39 seed,
 * "FoxyCard/owner" || 0x00 || the card's compressed key [|| counter]) mod n, P-256.
 * The proof: ECDSA P-256 / SHA-256, DER, over "FoxyCard/" + label || nonce || value,
 * for six labels only, each with its value shape.
 */
const crypto = require('crypto');

const N = BigInt('0xffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551');
const NONCE_LENGTH = 16;
const MOST_VALUE_BYTES = 180;
const LABELS = ['change-pin', 'set-limit', 'set-owner', 'set-card', 'load', 'reset'];

const TIME_PRIVATE = '5d3aa8864437b69bc699905ded587014b3d1f7e0de402684e5eec162d540b2ca';
const TIME_PUBLIC = '04403d6dc56a7e17ce887410e510f6214ff8699d5e09bf43dbbbb4159a9856cd0fe0737a186bbc8cbdaa924c49c3e5bc390968348d30c1c5406ba530a040fb17f8';
const UNTELLABLE = 'The clock on this phone is not a time a card can be told.';

/* Hex of either case, every character a hex digit, an even count; else null. */
function hexBytes(text) {
  if (typeof text !== 'string' || text.length % 2 || !/^[0-9a-fA-F]*$/.test(text)) return null;
  return Buffer.from(text, 'hex');
}
const utf8 = (s) => Buffer.byteLength(s, 'utf8');

function isCardKey(key) { return key.length === 33 && (key[0] === 2 || key[0] === 3); }

/* cardOwnerKey's {key}: 66 hex characters for 33 bytes starting 02 or 03. */
function cardKey(text) {
  if (typeof text !== 'string' || utf8(text) !== 66) return null;
  const key = hexBytes(text);
  return key && isCardKey(key) ? key : null;
}

function accepts(label, v) {
  switch (label) {
    case 'change-pin': return v.length >= 4 && v.length <= 8 && [...v].every((b) => b >= 0x30 && b <= 0x39);
    case 'set-limit': return v.length === 4 || v.length === 8 || v.length === 12;
    case 'set-owner': return v.length === 65 && v[0] === 4;
    case 'set-card': {
      if (v.length < 100) return false;
      if (v[34] !== 4 && [...v.subarray(34, 99)].some((b) => b !== 0)) return false;
      const mint = v[99];
      return mint >= 1 && mint <= 80 && v.length === 100 + mint;
    }
    case 'load': case 'reset': return v.length === 0;
    default: return false;
  }
}

/* cardOwnerSign's {key, label, nonce, value}, or null for anything else. */
function signRequest(m) {
  const key = cardKey(m.key);
  if (!key || typeof m.label !== 'string' || !LABELS.includes(m.label)) return null;
  if (typeof m.nonce !== 'string' || utf8(m.nonce) !== 2 * NONCE_LENGTH) return null;
  const nonce = hexBytes(m.nonce);
  if (!nonce || nonce.length !== NONCE_LENGTH) return null;
  if (typeof m.value !== 'string' || utf8(m.value) > 2 * MOST_VALUE_BYTES) return null;
  const value = hexBytes(m.value);
  if (!value || !accepts(m.label, value)) return null;
  return { key, label: m.label, nonce, value };
}

/* The BIP-39 seed of a phrase (no passphrase). */
function seedOf(words) {
  return crypto.pbkdf2Sync(Buffer.from(words.normalize('NFKD'), 'utf8'), Buffer.from('mnemonic', 'utf8'), 2048, 64, 'sha512');
}

/* d for a seed and a card's key, 32 bytes. */
function scalar(seed, key) {
  for (let counter = 0; counter < 256; counter++) {
    const parts = [Buffer.from('FoxyCard/owner'), Buffer.from([0]), key];
    if (counter > 0) parts.push(Buffer.from([counter]));
    const h = BigInt('0x' + crypto.createHmac('sha256', seed).update(Buffer.concat(parts)).digest('hex'));
    const d = h % N;
    if (d !== 0n) return Buffer.from(d.toString(16).padStart(64, '0'), 'hex');
  }
  return null;
}

function publicKey(d) {
  const ecdh = crypto.createECDH('prime256v1');
  ecdh.setPrivateKey(d);
  return ecdh.getPublicKey(null, 'uncompressed');
}

const b64u = (b) => Buffer.from(b).toString('base64url');
function sign(d, message) {
  const pub = publicKey(d);
  const key = crypto.createPrivateKey({ format: 'jwk', key: { kty: 'EC', crv: 'P-256', d: b64u(d), x: b64u(pub.subarray(1, 33)), y: b64u(pub.subarray(33)) } });
  return crypto.sign('sha256', message, { key, dsaEncoding: 'der' });
}
function verify(pubHex, message, sigHex) {
  try {
    const pub = Buffer.from(pubHex, 'hex');
    if (pub.length !== 65 || pub[0] !== 4) return false;
    const key = crypto.createPublicKey({ format: 'jwk', key: { kty: 'EC', crv: 'P-256', x: b64u(pub.subarray(1, 33)), y: b64u(pub.subarray(33)) } });
    return crypto.verify('sha256', message, { key, dsaEncoding: 'der' }, Buffer.from(sigHex, 'hex'));
  } catch (e) {
    return false;
  }
}

function message(label, nonce, value) { return Buffer.concat([Buffer.from('FoxyCard/' + label), nonce, value]); }

/* The bridge's answers, as [text, error]; `seed` null: no seed is saved. */
function ownerKey(m, seed) {
  const key = cardKey(m.key);
  if (!key) return [null, 'bad request'];
  if (!seed) return [null, 'no seed'];
  return [JSON.stringify({ pub: publicKey(scalar(seed, key)).toString('hex') }), null];
}
function ownerSign(m, seed) {
  const r = signRequest(m);
  if (!r) return [null, 'bad request'];
  if (!seed) return [null, 'no seed'];
  return [JSON.stringify({ sig: sign(scalar(seed, r.key), message(r.label, r.nonce, r.value)).toString('hex') }), null];
}

function timeMessage(time) {
  const four = Buffer.alloc(4);
  four.writeUInt32BE(time);
  return Buffer.concat([Buffer.from('FoxyCard/time'), four]);
}
/* cardTime for a clock in ms since 1970: {"sig","time"}, or the refusal. */
function cardTime(nowMs) {
  const time = Math.floor(nowMs / 1000);
  if (!Number.isFinite(time) || time < 0 || time > 0xffffffff) return [null, UNTELLABLE];
  const sig = sign(Buffer.from(TIME_PRIVATE, 'hex'), timeMessage(time)).toString('hex');
  return ['{"sig":"' + sig + '","time":' + time + '}', null];
}

module.exports = {
  N, LABELS, TIME_PUBLIC, UNTELLABLE, cardKey, signRequest, seedOf, scalar, publicKey, sign, verify, message,
  ownerKey, ownerSign, timeMessage, cardTime,
};
