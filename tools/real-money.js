#!/usr/bin/env node
'use strict';
/* real-money.js — the money rules of a leak-test or device leg that moves real
 * sats (docs/DEVICE-TESTS.md, "Real money"); the same rules, word for word, as
 * app/src/sharedTest/java/io/islandbitcoin/foxy/RealMoney.kt, which
 * DeviceExitTest applies. Android is tested on real mints with real ecash, so a
 * mint or a melt runs only when every rule holds:
 *
 *   - the person said go: REAL_MONEY=yes, exactly;
 *   - MAX_SATS is given, and is 100 or less unless OVER_DEFAULT_CAP=yes too;
 *   - the mint quote (MINT_SATS, default MAX_SATS) is at most MAX_SATS, and the
 *     person pays it from their own wallet;
 *   - the melt pays only INVOICE, an invoice the person made in their own
 *     wallet: a mainnet BOLT11 with an amount, a whole number of sats, at most
 *     MAX_SATS, and leaving room under MINT_SATS for the mint's Lightning fee
 *     reserve: INVOICE + max(2, 1% of MINT_SATS, rounded up) <= MINT_SATS. No
 *     fake invoice is ever paid on a real mint;
 *   - the mint is MINT (default https://mint.minibits.cash/Bitcoin), over https;
 *   - PAY_TIMEOUT_SECONDS (default 600, 30 to 3600) to wait for the quote's payment.
 *
 *   node tools/real-money.js leg     the leg as JSON, or "refused: …" and exit 3
 *   node tools/real-money.js leg-if-asked
 *                                    nothing when no money variable is given at
 *                                    all; otherwise the same as `leg`, so asking
 *                                    for any part of a money leg without the go
 *                                    is refused, never quietly skipped
 *   node tools/real-money.js mint    the mint (MINT or the default), checked
 */

const DEFAULT_MINT = 'https://mint.minibits.cash/Bitcoin';
const DEFAULT_CAP_SATS = 100;
const DEFAULT_PAY_TIMEOUT_SECONDS = 600;
/* Room the melt leaves under the amount minted for the mint's Lightning fee
 * reserve (NUT-05's fee_reserve; Foxy's pay owes amount + fee_reserve): 2 sats,
 * or 1% of the amount minted rounded up, whichever is more. */
const feeMarginSats = (mintSats) => Math.max(2, Math.ceil(mintSats / 100));
const CHARSET = 'qpzry9x8gf2tvdw0s3jn54khce6mua7l';
const GEN = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3];
const WHOLE = /^[1-9][0-9]{0,8}$/;
const MINT_URL = /^https:\/\/[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+(:[0-9]{1,5})?(\/[A-Za-z0-9._~-]+)*\/?$/;

class Refused extends Error {}
const refuse = (m) => { throw new Refused(m); };

function polymod(values) {
  let chk = 1;
  for (const v of values) {
    const b = chk >>> 25;
    chk = ((chk & 0x1ffffff) << 5) ^ v;
    for (let i = 0; i < 5; i++) if ((b >>> i) & 1) chk ^= GEN[i];
  }
  return chk;
}

function normalised(invoice) {
  const s = String(invoice).trim();
  if (s !== s.toLowerCase() && s !== s.toUpperCase()) refuse('the invoice mixes upper and lower case: not a BOLT11 invoice');
  return s.toLowerCase();
}

/* The amount of a mainnet BOLT11 invoice in whole sats, or Refused. The
 * signature is not checked (the mint checks it); the checksum, the prefix and
 * the amount are. */
function invoiceSats(invoice) {
  const s = normalised(invoice);
  const at = s.lastIndexOf('1');
  if (at < 4 || s.length - at - 1 < 7 + 104 + 6) refuse('not a BOLT11 invoice');
  const hrp = s.slice(0, at);
  const words = [...s.slice(at + 1)].map((c) => CHARSET.indexOf(c));
  if (words.some((w) => w < 0) || [...hrp].some((c) => c.charCodeAt(0) < 33 || c.charCodeAt(0) > 126)) refuse('not a BOLT11 invoice');
  const expanded = [...[...hrp].map((c) => c.charCodeAt(0) >> 5), 0, ...[...hrp].map((c) => c.charCodeAt(0) & 31)];
  if (polymod([...expanded, ...words]) !== 1) refuse("the invoice's checksum does not hold: not a BOLT11 invoice");
  if (!hrp.startsWith('lnbc')) refuse(`not a mainnet invoice (lnbc…): "${hrp}"`);
  const amount = hrp.slice(4);
  if (!amount) refuse("an invoice with no amount is refused: its amount is what is checked against maxSats");
  const m = /^([0-9]+)([munp]?)$/.exec(amount);
  if (!m) refuse(`not a mainnet invoice with an amount: "${hrp}"`);
  if (m[1].length > 15 || m[1].startsWith('0')) refuse(`the invoice's amount is not one BOLT11 allows: "${amount}"`);
  const n = BigInt(m[1]);
  let msat;
  switch (m[2]) {
    case '': msat = n * 100000000000n; break;
    case 'm': msat = n * 100000000n; break;
    case 'u': msat = n * 100000n; break;
    case 'n': msat = n * 100n; break;
    default:
      if (n % 10n !== 0n) refuse(`the invoice's amount is not one BOLT11 allows: "${amount}"`);
      msat = n / 10n;
  }
  if (msat % 1000n !== 0n) refuse(`the invoice is not for a whole number of sats: "${amount}"`);
  const sats = msat / 1000n;
  if (sats > BigInt(Number.MAX_SAFE_INTEGER)) refuse(`the invoice's amount is far above any cap: "${amount}"`);
  return Number(sats);
}

function number(value, name) {
  if (value === undefined || value === null) return null;
  if (!WHOLE.test(value)) refuse(`${name}: a whole number above zero (got "${value}")`);
  return Number(value);
}

function mintOf(get) {
  const mint = get('mint') ?? DEFAULT_MINT;
  if (!MINT_URL.test(mint)) refuse(`mint: an https URL of a mint, like ${DEFAULT_MINT} (got "${mint}")`);
  return mint;
}

/* The leg the arguments allow, or Refused. `get(name)` answers RealMoney.kt's
 * argument names (realMoney, maxSats, overDefaultCap, mintSats, mint, invoice,
 * payTimeoutSeconds), undefined for one not given. */
function leg(get) {
  if (get('realMoney') !== 'yes') refuse("this leg moves real money on a real mint: it runs only with the person's explicit go, REAL_MONEY=yes");
  const maxSats = number(get('maxSats'), 'maxSats');
  if (maxSats === null) refuse(`say how much this leg may move: MAX_SATS=<sats> (at most ${DEFAULT_CAP_SATS} by default)`);
  if (maxSats > DEFAULT_CAP_SATS && get('overDefaultCap') !== 'yes') {
    refuse(`maxSats ${maxSats} is above the default cap of ${DEFAULT_CAP_SATS} sats a leg; only the person raises it, with OVER_DEFAULT_CAP=yes`);
  }
  const mintSats = number(get('mintSats'), 'mintSats') ?? maxSats;
  if (mintSats > maxSats) refuse(`mintSats ${mintSats} is above maxSats ${maxSats}`);
  const mint = mintOf(get);
  const given = get('invoice');
  if (given === undefined || given === null) refuse("the melt pays only an invoice the person made in their own wallet: INVOICE=<bolt11>");
  const invoice = normalised(given);
  const sats = invoiceSats(invoice);
  if (sats > maxSats) refuse(`the invoice is for ${sats} sats, above maxSats ${maxSats}: nothing was sent`);
  const margin = feeMarginSats(mintSats);
  if (sats + margin > mintSats) {
    refuse(`the invoice is for ${sats} sats and the leg mints ${mintSats}: mint more than the invoice and the mint's fee reserve (at least ${margin} sats over it: an invoice of at most ${mintSats - margin} sats), or ask for a smaller invoice`);
  }
  const timeout = number(get('payTimeoutSeconds'), 'payTimeoutSeconds') ?? DEFAULT_PAY_TIMEOUT_SECONDS;
  if (timeout < 30 || timeout > 3600) refuse(`payTimeoutSeconds: 30 to 3600 (got ${timeout})`);
  return { mint, maxSats, mintSats, invoice, invoiceSats: sats, payTimeoutSeconds: timeout };
}

const ENV = {
  realMoney: 'REAL_MONEY', maxSats: 'MAX_SATS', overDefaultCap: 'OVER_DEFAULT_CAP', mintSats: 'MINT_SATS',
  mint: 'MINT', invoice: 'INVOICE', payTimeoutSeconds: 'PAY_TIMEOUT_SECONDS',
};
const fromEnv = (env) => (name) => env[ENV[name]];
/* Was any part of a money leg asked for? Every money variable but MINT (a
 * connect moves nothing), given at all, even empty. */
const asked = (get) => Object.keys(ENV).some((name) => name !== 'mint' && get(name) !== undefined);

module.exports = { leg, asked, feeMarginSats, invoiceSats, mintOf, fromEnv, ENV, Refused, DEFAULT_MINT, DEFAULT_CAP_SATS, DEFAULT_PAY_TIMEOUT_SECONDS };

if (require.main === module) {
  const what = process.argv[2];
  try {
    if (what === 'leg') process.stdout.write(JSON.stringify(leg(fromEnv(process.env))) + '\n');
    else if (what === 'leg-if-asked') {
      if (asked(fromEnv(process.env))) process.stdout.write(JSON.stringify(leg(fromEnv(process.env))) + '\n');
    }
    else if (what === 'mint') process.stdout.write(mintOf(fromEnv(process.env)) + '\n');
    else { process.stderr.write('usage: real-money.js leg|leg-if-asked|mint (reads REAL_MONEY, MAX_SATS, … from the environment)\n'); process.exit(2); }
  } catch (e) {
    if (!(e instanceof Refused)) throw e;
    process.stderr.write('refused: ' + e.message + '\n');
    process.exit(3);
  }
}
