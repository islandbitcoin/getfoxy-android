'use strict';
/* host.js — a fake native side, built from contract.json.
 *
 * It is the Android app's half of the bridge as the contract writes it, and
 * nothing looser: every message the page posts is
 *
 *   1. carried the way the Android shim must carry it — serialised to JSON and
 *      parsed again (androidx.webkit's WebMessageListener takes strings), so a
 *      message that does not survive that is a failure here, not a mystery on
 *      a phone;
 *   2. classified as the iOS dispatch does it (FoxyBridge.dispatch): a
 *      `haptic`, an action with an id, or a message that is neither, which must
 *      be one the contract lists as ignored;
 *   3. checked against its payloadSchema;
 *   4. answered through FoxyWallet._scanResult, as native answers, with an
 *      answer that is itself checked against the contract's reply.
 *
 * Anything else — an action the contract does not name, a field it does not
 * list, a missing one — is a violation, and the run fails.
 *
 * The answers are a phone's, made up: Tor up, a seed saved, counters kept in
 * memory, secrets that are HMACs of a fixed seed (no stub turns them into
 * spendable ecash anywhere but the fake mint), a mint made of cashu-ts.
 */
const crypto = require('crypto');
const { check } = require('./schema');
const CONTRACT = require('../contract.json');

const BY_NAME = new Map(CONTRACT.map((e) => [e.name, e]));
const ACTIONS = new Map(CONTRACT.filter((e) => e.kind === 'action').map((e) => [e.name, e]));
const MESSAGES = CONTRACT.filter((e) => e.kind === 'message');

function snapshot(s) {
  return { tor: s.tor, progress: s.tor === 'up' ? 100 : s.progress, everUp: s.everUp, unprotected: s.unprotected,
    transport: 'direct', orbot: 'none', firstSetup: false, vpn: false, orbotKey: false, network: 'wifi' };
}

/* Is a value plain JSON all the way down (what a string bridge can carry)? */
function plainJson(v, at, out) {
  if (v === null || typeof v === 'string' || typeof v === 'boolean') return;
  if (typeof v === 'number') { if (!Number.isFinite(v)) out.push(at + ' is ' + v); return; }
  if (Array.isArray(v)) { v.forEach((x, i) => plainJson(x, at + '[' + i + ']', out)); return; }
  if (typeof v === 'object') {
    const proto = Object.getPrototypeOf(v);
    if (proto !== null && proto.constructor && proto.constructor.name !== 'Object') out.push(at + ' is a ' + proto.constructor.name);
    for (const [k, x] of Object.entries(v)) if (x !== undefined) plainJson(x, at + '.' + k, out);
    return;
  }
  out.push(at + ' is a ' + typeof v);
}

/* Does an answer fit the contract's reply for that action? */
function replyProblems(entry, text, error) {
  const r = entry.reply || {};
  if (error !== null && error !== undefined) return typeof error === 'string' ? [] : ['error is not a string'];
  if (typeof text !== 'string') return ['text is not a string'];
  const parse = () => { try { return JSON.parse(text); } catch (e) { return undefined; } };
  switch (r.format) {
    case 'text': return r.values && !r.values.includes(text) ? ['"' + text + '" is not one of ' + JSON.stringify(r.values)] : [];
    case 'json': { const j = parse(); return j === undefined ? ['not JSON'] : check(r.json, j); }
    case 'json-array': {
      const j = parse();
      if (!Array.isArray(j)) return ['not a JSON array'];
      if (r.items && typeof r.items === 'object') return j.flatMap((x, i) => check(r.items, x).map((p) => '[' + i + '] ' + p));
      return [];
    }
    case 'json-or-text': {
      if (r.values && r.values.includes(text)) return [];
      const j = parse();
      return j === undefined ? ['neither one of ' + JSON.stringify(r.values) + ' nor JSON'] : check(r.json, j);
    }
    case 'status-body': return /^\d{3}\n/.test(text) ? [] : ['not "status\\nbody"'];
    case 'none': return ['an answer to a message that takes none'];
    default: return ['the contract gives no reply format'];
  }
}

/**
 * makeHost(o)
 *   o.seed      true: a seed is saved (default); false: a fresh install
 *   o.tor       'up' (default) | 'connecting' | 'failed'
 *   o.answers   { action: (msg, host) => [text, error] | undefined } overrides
 *   o.silent    a Set of actions to leave unanswered
 */
function makeHost(o) {
  const opts = o || {};
  const state = {
    tor: opts.tor || 'up', progress: 0, everUp: (opts.tor || 'up') === 'up', unprotected: false,
    seed: opts.seed !== false, counters: new Map(), p2pkNext: 0, candidates: new Set(), migrated: false,
    protection: 'none', imported: false, notifiedAt: 0,
  };
  const SEED = crypto.createHash('sha256').update('conformance seed').digest();
  const derive = (keysetId, counter, what) =>
    crypto.createHmac('sha256', SEED).update(keysetId + '|' + counter + '|' + what).digest('hex');
  const pubOf = (i) => '02' + crypto.createHmac('sha256', SEED).update('p2pk|' + i).digest('hex');

  const host = {
    state,
    window: null,
    mint: null,
    posted: [],          // every message, as carried
    asked: new Map(),    // action/message name -> count
    violations: [],
    answered: [],        // [name, text, error]
    replyProblems: [],
    pushes: [],
  };
  const violate = (kind, detail) => host.violations.push({ kind, detail: String(detail).slice(0, 400) });
  const count = (name) => host.asked.set(name, (host.asked.get(name) || 0) + 1);

  function secrets(keysetId, start, n) {
    const out = { keysetId, start, secrets: [], blindingFactors: [] };
    for (let i = 0; i < n; i++) {
      out.secrets.push(derive(keysetId, start + i, 'secret'));
      out.blindingFactors.push(derive(keysetId, start + i, 'r'));
    }
    return out;
  }
  const J = (x) => [JSON.stringify(x), null];
  const NO = (why) => [null, why];
  const routeOk = () => state.tor === 'up' || state.unprotected;

  const answer = {
    covered: () => ['ok', null],
    log: () => ['ok', null],
    fieldLog: () => J([{ at: Date.now(), text: '[foxy] conformance', bad: false }]),
    fieldLogClear: () => ['ok', null],
    isDebug: () => ['no', null],
    awake: () => ['ok', null],
    captured: () => ['no', null],
    privacy: () => J(snapshot(state)),
    torRetry: () => J(snapshot(state)),
    unprotected: (m) => { state.unprotected = !!m.on && state.tor !== 'up'; host.push('_privacy', snapshot(state)); return J(snapshot(state)); },
    orbotRefresh: () => ['none', null],
    orbotAccess: () => ['no', null],
    hostsKnown: () => ['ok', null],
    price: () => (routeOk() ? ['65000.5', null] : NO('Foxy is not connected to Tor.')),
    candles: () => (routeOk() ? J([[1790000000, 64000.1], [1790003600, 65000.5]]) : NO('Foxy is not connected to Tor.')),
    mintRequest: (m) => {
      if (!routeOk()) return NO('Foxy is not connected to Tor.');
      const u = new URL(m.url);
      if (/\.onion$/.test(u.hostname) || /^(mempool\.space|blockstream\.info)$/.test(u.hostname)) return ['404\n{}', null];
      if (!host.mint) return ['503\n{"detail":"no mint yet"}', null];
      return [host.mint.handle(m), null];
    },
    seedStatus: () => J({ exists: state.seed }),
    seedCreate: () => { if (state.seed) return NO('a seed already exists'); state.seed = true; return J({ created: true }); },
    seedMigrate: () => { if (state.seed) return J({ migrated: false, same: true }); state.seed = true; state.migrated = true; return J({ migrated: true }); },
    countersImport: (m) => {
      if (state.imported) return NO('counters were already imported');
      state.imported = true;
      for (const [k, v] of Object.entries(m.counters || {})) state.counters.set(k.toLowerCase(), Math.max(state.counters.get(k.toLowerCase()) || 0, v));
      return J({ counters: Object.fromEntries(state.counters) });
    },
    counterReserve: (m) => {
      const k = String(m.keysetId).toLowerCase();
      const start = state.counters.get(k) || 0;
      if (m.count > 0 && !state.seed) return NO('no seed');
      state.counters.set(k, start + m.count);
      return J(secrets(k, start, m.count));
    },
    counterReserveAt: (m) => {
      const k = String(m.keysetId).toLowerCase();
      const next = state.counters.get(k) || 0;
      if (m.start < next) return NO('range already issued');
      if (m.start > next + 100) return NO('too far ahead');
      state.counters.set(k, m.start + m.count);
      return J(secrets(k, m.start, m.count));
    },
    counterAdvance: (m) => {
      const k = String(m.keysetId).toLowerCase();
      const next = Math.max(state.counters.get(k) || 0, m.next);
      state.counters.set(k, next);
      return J({ keysetId: k, next });
    },
    counterSnapshot: () => J({ counters: Object.fromEntries(state.counters) }),
    restoreSecrets: (m) => J(secrets(String(m.keysetId).toLowerCase(), m.start, m.count)),
    p2pkReserve: (m) => {
      const start = state.p2pkNext;
      state.p2pkNext += m.count;
      return J({ start, next: state.p2pkNext, pubkeys: Array.from({ length: m.count }, (_, i) => pubOf(start + i)) });
    },
    p2pkPubkeys: (m) => J({ start: m.start, next: state.p2pkNext, pubkeys: Array.from({ length: m.count }, (_, i) => pubOf(m.start + i)) }),
    p2pkKey: (m) => J({ index: m.index, privkey: crypto.createHmac('sha256', SEED).update('p2pk-priv|' + m.index).digest('hex'), pubkey: pubOf(m.index) }),
    seedShow: () => J({ verified: true, delete: false, revealed: true }),
    seedEnter: () => { const c = crypto.randomBytes(16).toString('hex'); state.candidates.add(c); return J({ candidate: c }); },
    seedAdopt: (m) => (state.candidates.has(m.candidate) ? J({ adopted: true }) : NO('unknown candidate')),
    seedCandidateForget: (m) => { state.candidates.delete(m.candidate); return J({ forgotten: true }); },
    seedWipe: () => { state.counters.clear(); state.seed = true; return J({ wiped: true, created: true }); },
    seedProtection: () => J({ mode: state.protection, passcode: true }),
    seedProtect: (m) => { if (m.mode !== 'device' && m.mode !== 'none') return NO('bad request'); state.protection = m.mode; return J({ mode: m.mode }); },
    scan: () => ['cashuBconformance', null],
    previewStart: () => ['ok', null],
    previewStop: () => ['', null],
    pasteStart: () => ['ok', null],
    pasteStop: () => ['', null],
    clipboard: () => ['', null],
    copy: (m) => (m.text ? ['ok', null] : NO('nothing to copy')),
    share: (m) => (m.text ? ['ok', null] : NO('Nothing to share.')),
    notify: (m) => {
      if (m.kind !== 'sent' && m.kind !== 'received') return NO('unknown notification kind');
      if (Date.now() - state.notifiedAt <= 3000) return ['throttled', null];
      state.notifiedAt = Date.now();
      return ['ok', null];
    },
    biometric: () => ['yes', null],
    openSettings: () => ['ok', null],
    openCompany: (m) => (['cashapp', 'strike', 'river', 'flash'].includes(m.which) ? ['ok', null] : NO('Not one of the listed sites.')),
    inboxOpen: () => NO('No address: Tor is not ready.'),
    inboxClose: () => ['ok', null],
    inboxAnswer: () => NO('No payment is waiting on that answer.'),
    onionPost: () => NO('That is not an onion address Foxy will post to.'),
    nostrSend: () => NO('The payment could not be sent over Nostr.'),
    tapReceiveStart: () => ['ok', null],
    tapReceiveStop: () => ['ok', null],
    tapPayStart: (m) => (m.now ? ['ok', null] : NO('Stopped.')),
    tapSend: () => NO('That phone is no longer connected.'),
    tapChange: () => NO('That phone is no longer connected.'),
    tapChangeKept: () => { if (!state.changeWaiting) return NO('No change is waiting to be kept.'); state.changeWaiting = false; return ['ok', null]; },
    tapAsking: () => ['no link', null],
    tapChangeDue: () => ['ok', null],
    tapQuote: () => NO('That phone is no longer connected.'),
    tapTerms: () => NO('That phone is no longer connected.'),
    tapPayStop: () => ['ok', null],
    bluetoothAsk: () => ['unsupported', null],
    shakeStart: () => ['ok', null],
    shakeStop: () => ['ok', null],
    cardBegin: () => NO('This phone cannot read cards.'),
    cardSend: () => NO('The card was taken away.'),
    cardSay: () => ['ok', null],
    cardAgain: () => NO('The card was taken away.'),
    cardEnd: () => ['ok', null],
    cardOwnerKey: () => J({ pub: '04' + '11'.repeat(64) }),
    cardOwnerSign: () => J({ sig: '3006020101020101' }),
    cardTime: () => J({ time: Math.floor(Date.now() / 1000), sig: '3006020101020101' }),
  };
  host.answerers = answer;

  /* Push into the page, as native does with evaluateJavaScript. */
  host.push = (name, arg) => {
    const w = host.window;
    host.pushes.push(name);
    // change handed over on a tap: native holds the answer until the page says it kept it (FoxyBridge+Tap.swift)
    if (name === '_tapChange') state.changeWaiting = true;
    const W = w && w.FoxyWallet;
    if (!W || typeof W[name] !== 'function') return undefined;
    return W[name](arg);
  };

  function reply(entry, id, text, error) {
    const problems = replyProblems(entry, text, error);
    if (problems.length) host.replyProblems.push(entry.name + ': ' + problems.join('; '));
    host.answered.push([entry.name, text, error]);
    setTimeout(() => {
      const W = host.window && host.window.FoxyWallet;
      if (W && W._scanResult) W._scanResult(id, text === null ? null : text, error === null ? null : error);
    }, 0);
  }

  /* window.webkit.messageHandlers.foxy.postMessage */
  host.postMessage = (raw, w) => {
    if (w) host.window = w;
    // 1. the string bridge
    const notJson = [];
    plainJson(raw, 'message', notJson);
    if (notJson.length) violate('not-json', notJson.join('; '));
    let m;
    try { m = JSON.parse(JSON.stringify(raw)); } catch (e) { violate('not-json', String(e)); return; }
    host.posted.push(m);
    // 2. dispatch, as FoxyBridge.dispatch
    if (m === null || typeof m !== 'object' || Array.isArray(m)) { violate('unknown', 'a message that is not an object: ' + JSON.stringify(m)); return; }
    if (typeof m.haptic === 'string') {
      count('haptic');
      const p = check(BY_NAME.get('haptic').payloadSchema, m);
      if (p.length) violate('payload', 'haptic: ' + p.join('; '));
      return;
    }
    if (typeof m.action !== 'string' || typeof m.id !== 'string') {
      const fits = MESSAGES.filter((e) => e.name !== 'haptic' && !check(e.payloadSchema, m).length);
      if (!fits.length) violate('unknown', 'a message with no action/id the contract does not list: ' + JSON.stringify(m).slice(0, 200));
      else count(fits[0].name);
      return;
    }
    const entry = ACTIONS.get(m.action);
    if (!entry) {
      violate('unknown', 'action "' + m.action + '" is not in contract.json');
      // as iOS answers it: FoxyBridge.userContentController, `.unknown`
      reply({ name: m.action, reply: { format: 'text' } }, m.id, null, 'Unknown action: ' + m.action);
      return;
    }
    count(m.action);
    // 3. the payload
    const p = check(entry.payloadSchema, m);
    if (p.length) violate('payload', m.action + ': ' + p.join('; ') + ' — ' + JSON.stringify(m).slice(0, 200));
    // 4. the answer
    if (opts.silent && opts.silent.has(m.action)) return;
    const over = opts.answers && opts.answers[m.action];
    const got = (over && over(m, host)) || answer[m.action](m, host);
    reply(entry, m.id, got[0], got[1]);
  };

  return host;
}

module.exports = { makeHost, snapshot, replyProblems, CONTRACT };
