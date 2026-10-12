'use strict';
/* real-money.test.js — the leak test's money rules (tools/real-money.js), the
 * same as RealMoneyTest checks for DeviceExitTest's (RealMoney.kt). */
const test = require('node:test');
const assert = require('node:assert/strict');
const { execFileSync, spawnSync } = require('node:child_process');
const path = require('node:path');
const { fakeInvoice, polymod, hrpExpand, CHARSET } = require('./fake-invoice');
const { leg, invoiceSats, mintOf, Refused, DEFAULT_MINT } = require('./real-money');

/* one fakeInvoice(8, 1_790_000_000_000), kept (its keys are random each time it is made): RealMoneyTest uses the same */
const base = 'lnbc80n1p4tzwuqpp58q55ukf4eqqvmvgkn8v8mm46a6zjrzys2cn9nnemw6wvhz5d8y5ssp5xc50fqf9tzys007ymgq0zmvum7u3p2zfqywet7e9whask4ry6f4sdqhvehhs7fqw3jhxapqd4jkcaqxqrrsscqpj9qrsgqp7s3yl8umdm2z6q7ud5tqz5f9lhp5aq3s65ryllmp2cyagp7lpz9j6u78qg52afgm02sxfv6acnxpzlc9hz78smasm2y5pvnde4jksqqhl7tff';
/* the same invoice under another human-readable part, its checksum computed again by fake-invoice.js's polymod */
function inv(hrp) {
  const at = base.lastIndexOf('1');
  const words = [...base.slice(at + 1, -6)].map((c) => CHARSET.indexOf(c));
  const p = polymod([...hrpExpand(hrp), ...words, 0, 0, 0, 0, 0, 0]) ^ 1;
  const cs = [0, 1, 2, 3, 4, 5].map((i) => (p >> (5 * (5 - i))) & 31);
  return hrp + '1' + [...words, ...cs].map((w) => CHARSET[w]).join('');
}
const args = (o) => (name) => o[name];
const good = { realMoney: 'yes', maxSats: '64', invoice: inv('lnbc80n') };
function refused(o, re) { assert.throws(() => leg(args(o)), (e) => e instanceof Refused && re.test(e.message)); }

test('no money moves without REAL_MONEY=yes', () => {
  refused({ ...good, realMoney: undefined }, /REAL_MONEY=yes/);
  for (const w of ['true', 'YES', 'y', '1', '', 'yes ']) refused({ ...good, realMoney: w }, /explicit go/);
});

test('a leg with every rule met is allowed, on minibits by default', () => {
  assert.equal(DEFAULT_MINT, 'https://mint.minibits.cash/Bitcoin');
  assert.deepEqual(leg(args(good)), { mint: DEFAULT_MINT, maxSats: 64, mintSats: 64, invoice: inv('lnbc80n'), invoiceSats: 8, payTimeoutSeconds: 600 });
  assert.deepEqual(leg(args({ ...good, mint: 'https://mint.example.com', mintSats: '32', payTimeoutSeconds: '120' })),
    { mint: 'https://mint.example.com', maxSats: 64, mintSats: 32, invoice: inv('lnbc80n'), invoiceSats: 8, payTimeoutSeconds: 120 });
  assert.equal(leg(args({ ...good, invoice: inv('lnbc80n').toUpperCase() })).invoice, inv('lnbc80n'));
});

test('MAX_SATS is required and capped at 100 unless the person raises it', () => {
  refused({ ...good, maxSats: undefined }, /MAX_SATS=/);
  for (const bad of ['0', '-5', 'ten', '1.5', '', '0100']) refused({ ...good, maxSats: bad }, /maxSats: a whole number/);
  assert.equal(leg(args({ ...good, maxSats: '100' })).maxSats, 100);
  refused({ ...good, maxSats: '101' }, /OVER_DEFAULT_CAP=yes/);
  refused({ ...good, maxSats: '500', overDefaultCap: 'true' }, /default cap/);
  assert.equal(leg(args({ ...good, maxSats: '500', overDefaultCap: 'yes' })).mintSats, 500);
});

test('an invoice above MAX_SATS is refused, and one that leaves no fee reserve', () => {
  refused({ ...good, invoice: inv('lnbc1u') }, /100 sats, above maxSats 64/);
  refused({ ...good, maxSats: '100', invoice: inv('lnbc1010n') }, /101 sats, above maxSats 100/);
  refused({ ...good, maxSats: '100', invoice: inv('lnbc1m') }, /100000 sats, above maxSats 100/);
  refused({ ...good, maxSats: '8' }, /mint more than the invoice/);
  refused({ ...good, mintSats: '100' }, /mintSats 100 is above maxSats 64/);
});

test('the melt pays only an invoice the person gave: mainnet, with an amount', () => {
  refused({ ...good, invoice: undefined }, /INVOICE=/);
  refused({ ...good, invoice: inv('lnbc') }, /no amount/);
  refused({ ...good, invoice: inv('lntb80n') }, /not a mainnet invoice/);
  refused({ ...good, invoice: inv('lnbcrt80n') }, /not a mainnet invoice/);
});

test('the invoice is read, not trusted', () => {
  assert.equal(invoiceSats(inv('lnbc80n')), 8);
  assert.equal(invoiceSats(inv('lnbc100n')), 10);
  assert.equal(invoiceSats(inv('lnbc1u')), 100);
  assert.equal(invoiceSats(inv('lnbc1010n')), 101);
  assert.equal(invoiceSats(inv('lnbc1m')), 100000);
  const r = (s, re) => assert.throws(() => invoiceSats(s), (e) => e instanceof Refused && re.test(e.message));
  r(inv('lnbc80p'), /whole number of sats/);
  r(inv('lnbc1001n'), /whole number of sats/);
  r(inv('lnbc85p'), /not one BOLT11 allows/);
  r('lnbc10n' + base.slice(base.lastIndexOf('1')), /checksum/);
  r(base.slice(0, -1) + (base.endsWith('q') ? 'p' : 'q'), /checksum/);
  r('L' + base.slice(1), /mixes upper and lower case/);
  for (const junk of ['', 'lnbc', 'lnbc80n1', 'hello', 'lightning:' + base, base + "'"]) r(junk, /./);
  // RealMoneyTest's checksums are this invoice's, computed here by fake-invoice.js
  const kotlin = { lnbc80n: 'hl7tff', lnbc100n: 'mlwuz9', lnbc1u: 've8n0w', lnbc1010n: '8ff3rh', lnbc1m: '9x5r4m', lnbc: 'fxax96',
    lntb80n: 'u3jfkf', lnbcrt80n: '0xexnm', lnbc80p: 'kehlpn', lnbc85p: 'xshwv5', lnbc1001n: 'um7cdn' };
  for (const [hrp, cs] of Object.entries(kotlin)) assert.equal(inv(hrp).slice(-6), cs, hrp);
  // and a fresh fake invoice reads as what it was made for
  assert.equal(invoiceSats(fakeInvoice(42)), 42);
});

test('the mint is an https URL', () => {
  for (const bad of ['http://mint.minibits.cash/Bitcoin', 'https://', 'mint.minibits.cash', "https://mint.minibits.cash/Bitcoin'); alert(1); ('", 'https://a b.com', 'javascript:alert(1)']) {
    refused({ ...good, mint: bad }, /an https URL of a mint/);
  }
  assert.equal(mintOf(args({})), DEFAULT_MINT);
  for (const bad of ['0', '29', '3601', 'x']) assert.throws(() => leg(args({ ...good, payTimeoutSeconds: bad })), Refused);
});

test('the command line refuses with exit 3 and reads the environment', () => {
  const tool = path.join(__dirname, 'real-money.js');
  const clean = { PATH: process.env.PATH };
  const no = spawnSync(process.execPath, [tool, 'leg'], { env: { ...clean, MAX_SATS: '64', INVOICE: inv('lnbc80n') }, encoding: 'utf8' });
  assert.equal(no.status, 3);
  assert.match(no.stderr, /refused: .*REAL_MONEY=yes/);
  const over = spawnSync(process.execPath, [tool, 'leg'], { env: { ...clean, REAL_MONEY: 'yes', MAX_SATS: '64', INVOICE: inv('lnbc1u') }, encoding: 'utf8' });
  assert.equal(over.status, 3);
  assert.match(over.stderr, /above maxSats 64/);
  const ok = JSON.parse(execFileSync(process.execPath, [tool, 'leg'], { env: { ...clean, REAL_MONEY: 'yes', MAX_SATS: '64', INVOICE: inv('lnbc80n') }, encoding: 'utf8' }));
  assert.equal(ok.invoiceSats, 8);
  assert.equal(execFileSync(process.execPath, [tool, 'mint'], { env: clean, encoding: 'utf8' }).trim(), DEFAULT_MINT);
});
