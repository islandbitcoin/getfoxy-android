'use strict';
/* tap-rules.js — what the receive screen's TAP may put on the air, as Android
 * refuses it (app/src/main/java/io/islandbitcoin/foxy/bridge/TapRules.kt, a port
 * of iOS's Foxy/Bluetooth/TapProtocol.swift `isPayload`, `isInvoice`,
 * `isRequest`). The same patterns and byte limits, so a tapReceiveStart the
 * phone refuses is refused here too.
 *
 * `$` in a Java (Kotlin) or NSRegularExpression pattern also matches before one
 * final line terminator; JS's does not, so END spells that out.
 */
const MOST_PAYLOAD_BYTES = 4096;
const MOST_INVOICE_BYTES = 2048;
const NOT_PAYABLE = 'Not something a payer could pay.';

const END = '(?:\\r\\n|[\\n\\r\\u0085\\u2028\\u2029])?$';
const INVOICE_SHAPE = new RegExp('^ln[a-z0-9]+1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+' + END);
const REQUEST_SHAPE = new RegExp('^creq[A-Za-z0-9_=+/-]{16,}' + END);

const bytes = (s) => Buffer.byteLength(s, 'utf8');

/* A Lightning invoice by shape: one case throughout, `ln…1…` in bech32's alphabet. */
function isInvoice(s) {
  if (bytes(s) < 20 || bytes(s) > MOST_INVOICE_BYTES) return false;
  const t = s.toLowerCase();
  if (s !== t && s !== s.toUpperCase()) return false;
  return INVOICE_SHAPE.test(t);
}

/* A Cashu payment request by shape alone. */
function isRequest(s) {
  if (bytes(s) < 20 || bytes(s) > MOST_PAYLOAD_BYTES) return false;
  return REQUEST_SHAPE.test(s);
}

/* `{"v":2,"inv"?,"req"?}` with at least one of the two, each of its shape, at most 4096 bytes. */
function isPayload(s) {
  if (bytes(s) > MOST_PAYLOAD_BYTES) return false;
  let o;
  try { o = JSON.parse(s); } catch (e) { return false; }
  if (o === null || typeof o !== 'object' || Array.isArray(o)) return false;
  // the number two, however it was written, and not `true`
  if (typeof o.v !== 'number' || o.v !== 2) return false;
  // a half that is not a string counts as missing, as iOS's `as? String` reads it
  const inv = typeof o.inv === 'string' ? o.inv : null;
  const req = typeof o.req === 'string' ? o.req : null;
  if (inv !== null && !isInvoice(inv)) return false;
  if (req !== null && !isRequest(req)) return false;
  return inv !== null || req !== null;
}

module.exports = { MOST_PAYLOAD_BYTES, MOST_INVOICE_BYTES, NOT_PAYABLE, isInvoice, isRequest, isPayload };
