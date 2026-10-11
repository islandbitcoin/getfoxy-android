'use strict';
/* mint.js — a mint made of cashu-ts's own crypto, answering `mintRequest`.
 *
 * Ported, trimmed, from the iOS repository's tests/harness.js `fakeMint`
 * (getfoxy/iOS at core/PIN's commit, MIT): the same approach, so a flow that
 * passes there passes here. Its keys come from a fixed seed; it signs what it
 * is sent with a DLEQ proof, refuses outputs it already signed and inputs
 * already spent, and remembers what it signed for /v1/restore. Lightning is
 * pretend: a mint quote is PAID when made; a melt pays at once for 2 sat and
 * returns the rest of its 10-sat reserve as change. It is not a mint — it
 * checks no fee and no lock — and this suite does not test money: it gives
 * the page real answers, so the page goes on to post the messages a real
 * session posts, and the contract is checked on those.
 *
 *   const mint = fakeMint(window)        // after cashu-ts.js has loaded in it
 *   mint.handle({ url, method, body })   // -> "status\nbody", as native answers
 */
function fakeMint(w, opts) {
  const o = opts || {};
  const C = w.CashuTS;
  if (!C || !C.createNewMintKeys) throw new Error('fakeMint: the page has no CashuTS yet');
  const pair = C.createNewMintKeys(16, new w.Uint8Array(64).fill(o.fill || 7), { versionByte: 0 });
  const id = pair.keysetId;
  const hex = (b) => Buffer.from(Array.from(b)).toString('hex');
  const pubs = {};
  for (const [a, k] of Object.entries(pair.pubKeys)) pubs[a] = typeof k === 'string' ? k : hex(k);
  const quotes = {}, melts = {}, spent = new Set(), signed = new Map();
  let n = 0;
  const quoteId = () => (++n).toString(16).padStart(32, '0');
  const now = () => Math.floor(Date.now() / 1000);
  const ok = (j) => '200\n' + JSON.stringify(j);
  const no = (code, detail) => '400\n' + JSON.stringify({ code, detail });
  const sum = (list) => (list || []).reduce((a, x) => a + Number(x.amount), 0);
  const yOf = (secret) => C.hashToCurve(new w.Uint8Array(Buffer.from(String(secret), 'utf8'))).toHex(true);
  const unsigned = (outs) => (outs || []).every((x) => !signed.has(x.B_));
  function sign(outs, amounts) {
    return outs.map((out, i) => {
      const amount = amounts ? amounts[i] : Number(out.amount);
      const priv = pair.privKeys[String(amount)];
      if (!priv) throw new Error('fake mint: no key for ' + amount);
      const sig = { id, amount, C_: C.createBlindSignature(C.pointFromHex(out.B_), priv, id).C_.toHex(true) };
      const d = C.createDLEQProof(C.pointFromHex(out.B_), priv);
      sig.dleq = { e: hex(d.e), s: hex(d.s) };
      signed.set(out.B_, { out: { id, amount, B_: out.B_ }, sig });
      return sig;
    });
  }
  function spend(inputs) {
    const ys = (inputs || []).map((p) => yOf(p.secret));
    if (ys.some((y) => spent.has(y))) return false;
    ys.forEach((y) => spent.add(y));
    return true;
  }
  function changeFor(outs, back) {
    const amounts = [];
    for (let bit = 1; back > 0; bit *= 2) if (back & bit) { amounts.push(bit); back -= bit; }
    const use = (outs || []).slice(0, amounts.length);
    return sign(use, amounts.slice(0, use.length));
  }
  function sats(bolt11) {
    const m = /^lnbc(\d+)([munp]?)1/.exec(String(bolt11));
    if (!m) return 0;
    const msat = { '': 1e11, m: 1e8, u: 1e5, n: 100, p: 0.1 }[m[2]];
    return Math.floor((Number(m[1]) * msat) / 1000);
  }
  const requests = [];
  function handle(m) {
    const p = new URL(m.url).pathname.replace(/^\/Bitcoin(?=\/)/, '');
    const body = m.body ? JSON.parse(m.body) : {};
    requests.push((m.method || 'GET') + ' ' + p);
    const last = p.split('/').pop();
    if (p === '/v1/info') {
      return ok({ name: 'conformance mint', pubkey: pubs['1'], version: 'fake/1',
        nuts: { 4: { methods: [{ method: 'bolt11', unit: 'sat', description: true }], disabled: false },
                5: { methods: [{ method: 'bolt11', unit: 'sat' }], disabled: false },
                7: { supported: true }, 8: { supported: true }, 9: { supported: true }, 12: { supported: true } } });
    }
    if (p === '/v1/keysets') return ok({ keysets: [{ id, unit: 'sat', active: true, input_fee_ppk: 0 }] });
    if (p === '/v1/keys' || p === '/v1/keys/' + id) return ok({ keysets: [{ id, unit: 'sat', active: true, keys: pubs }] });
    if (p === '/v1/mint/quote/bolt11') {
      const q = { quote: quoteId(), request: 'lnbc' + (Number(body.amount) * 10) + 'n1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypq',
        amount: Number(body.amount), unit: 'sat', state: 'PAID', expiry: now() + 3600 };
      if (typeof body.pubkey === 'string' && body.pubkey) q.pubkey = body.pubkey;
      quotes[q.quote] = q;
      return ok(q);
    }
    if (p.startsWith('/v1/mint/quote/bolt11/')) return quotes[last] ? ok(quotes[last]) : no(20007, 'quote not found');
    if (p === '/v1/mint/bolt11') {
      const q = quotes[body.quote];
      if (!q) return no(20007, 'quote not found');
      if (q.state === 'ISSUED') return no(20002, 'quote already issued');
      if (sum(body.outputs) !== q.amount) return no(11002, 'outputs do not add up to the quote');
      if (!unsigned(body.outputs)) return no(10002, 'Blinded Message is already signed.');
      q.state = 'ISSUED';
      return ok({ signatures: sign(body.outputs) });
    }
    if (p === '/v1/swap') {
      if (sum(body.inputs) !== sum(body.outputs)) return no(11002, 'inputs and outputs do not balance');
      if (!unsigned(body.outputs)) return no(10002, 'Blinded Message is already signed.');
      if (!spend(body.inputs)) return no(11001, 'Token already spent.');
      return ok({ signatures: sign(body.outputs) });
    }
    if (p === '/v1/checkstate') {
      return ok({ states: (body.Ys || []).map((Y) => ({ Y, state: spent.has(Y) ? 'SPENT' : 'UNSPENT', witness: null })) });
    }
    if (p === '/v1/melt/quote/bolt11') {
      const q = { quote: quoteId(), amount: sats(body.request), fee_reserve: 10, state: 'UNPAID',
        expiry: now() + 3600, request: body.request, unit: 'sat', payment_preimage: null };
      melts[q.quote] = q;
      return ok(q);
    }
    if (p.startsWith('/v1/melt/quote/bolt11/')) return melts[last] ? ok(melts[last]) : no(20007, 'quote not found');
    if (p === '/v1/melt/bolt11') {
      const q = melts[body.quote];
      if (!q) return no(20007, 'quote not found');
      if (q.state !== 'UNPAID') return no(20005, 'quote already ' + q.state);
      const paidIn = sum(body.inputs);
      if (paidIn < q.amount + q.fee_reserve) return no(11002, 'not enough inputs for the quote');
      if (!spend(body.inputs)) return no(11001, 'Token already spent.');
      const sigs = changeFor(body.outputs, paidIn - q.amount - 2);
      Object.assign(q, { state: 'PAID', payment_preimage: '00'.repeat(32), change: sigs });
      return ok(q);
    }
    if (p === '/v1/restore') {
      const outputs = [], signatures = [];
      for (const x of body.outputs || []) {
        const s = signed.get(x.B_);
        if (s) { outputs.push(s.out); signatures.push(s.sig); }
      }
      return ok({ outputs, signatures });
    }
    return '404\n' + JSON.stringify({ detail: 'the conformance mint has no ' + (m.method || 'GET') + ' ' + p });
  }
  return { id, handle, requests };
}

module.exports = { fakeMint };
