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
const TapRules = require('./tap-rules');
const CardOwner = require('./card-owner');
const CardHost = require('./card-host');
const CONTRACT = require('../contract.json');

const BY_NAME = new Map(CONTRACT.map((e) => [e.name, e]));
const ACTIONS = new Map(CONTRACT.filter((e) => e.kind === 'action').map((e) => [e.name, e]));
const MESSAGES = CONTRACT.filter((e) => e.kind === 'message');

/* The phases in the order Android ships them. */
const PHASE_ORDER = ['P1', 'P2', 'P3', 'LATER'];
function laterThan(phase, host) {
  const a = PHASE_ORDER.indexOf(phase), b = PHASE_ORDER.indexOf(host);
  if (a < 0 || b < 0) throw new Error('unknown phase: ' + (a < 0 ? phase : host));
  return a > b;
}

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
 *   o.phase     'P1' | 'P2' | 'P3': the host Android ships in that phase. Every
 *               action of that phase or an earlier one is served as above; an
 *               action of a later phase answers its contract `p1` stub, and one
 *               with no stub answers as iOS answers an action it does not serve
 *               ("Unknown action: …") and is a violation ('unserved'), so a boot
 *               that leans on one fails here. Unset: everything is served (the
 *               iOS app's table).
 *   o.ownerWords the BIP-39 phrase a card's owner key is derived from while a
 *               seed is saved (default: abandon … about, the first case of the
 *               iOS card-owner vectors); o.nowMs the phone's clock for cardTime
 *               (default: Date.now)
 *   o.card      the card a card session finds (ENG-667): a model of lib/flashcard-card.js
 *               (`makeCard`), or 'away' for a sheet nobody holds a card to; unset, a phone
 *               that cannot read one. Also `host.state.card`, set by a flow once the page
 *               has loaded (the model needs the page's CashuTS).
 *   o.unknown   a Set of later-phase actions with no stub that this host answers
 *               "Unknown action: …" without a violation, as the Kotlin bridge
 *               answers them today: for a flow about something else that has to
 *               stand on a screen which also asks them. Naming an action the
 *               host's phase serves, or one with a stub, is itself a violation.
 */
function makeHost(o) {
  const opts = o || {};
  const state = {
    tor: opts.tor || 'up', progress: 0, everUp: (opts.tor || 'up') === 'up', unprotected: false,
    seed: opts.seed !== false, counters: new Map(), p2pkNext: 0, candidates: new Set(), migrated: false,
    protection: 'none', imported: false, notifiedAt: 0, receiverHeld: false,
    card: opts.card || null, cardPull: null,
  };
  const SEED = crypto.createHash('sha256').update('conformance seed').digest();
  const derive = (keysetId, counter, what) =>
    crypto.createHmac('sha256', SEED).update(keysetId + '|' + counter + '|' + what).digest('hex');
  const OWNER_SEED = CardOwner.seedOf(opts.ownerWords || 'abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about');
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
  for (const name of (opts.unknown || [])) {
    const e = ACTIONS.get(name);
    if (!e || e.p1 || !opts.phase || !laterThan(e.phase, opts.phase)) {
      violate('unknown', 'o.unknown names ' + name + ', which this host serves or stubs (or is no action)');
    }
  }
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
    /* As an iPhone without Bluetooth answers, which is Android until BLE tap to pay
     * (ENG-650, ENG-656; FoxyBridge+Tap.swift `handleTapReceiveStart`, TapLink.swift
     * `cbState`): a non-empty payload TapRules.kt (TapProtocol.isPayload) would
     * refuse is refused with its words, making no receiver; otherwise "ok", and
     * the receiver this makes says `unsupported` after the reply; a receiver
     * already held says nothing more. */
    tapReceiveStart: (m) => {
      const payload = typeof m.payload === 'string' ? m.payload : '';
      if (payload !== '' && !TapRules.isPayload(payload)) return NO(TapRules.NOT_PAYABLE);
      if (!state.receiverHeld) {
        state.receiverHeld = true;
        host.afterReply(() => host.pushAs('_tap', { side: 'receive', stage: 'unsupported' }));
      }
      return ['ok', null];
    },
    tapReceiveStop: () => { state.receiverHeld = false; return ['ok', null]; },
    tapPayStart: (m) => (m.now ? ['ok', null] : NO('Stopped.')),
    tapSend: () => NO('That phone is no longer connected.'),
    tapChange: () => NO('That phone is no longer connected.'),
    tapChangeKept: () => { if (!state.changeWaiting) return NO('No change is waiting to be kept.'); state.changeWaiting = false; return ['ok', null]; },
    tapAsking: () => ['no link', null],
    tapChangeDue: () => ['ok', null],
    tapQuote: () => NO('That phone is no longer connected.'),
    tapTerms: () => NO('That phone is no longer connected.'),
    tapPayStop: () => ['ok', null],
    /* The gate's own global first, then the reply, in iOS's order (FoxyBridge+Tap.swift
     * `handleBluetoothAsk`): the first-install screen leaves only on the push. Android
     * answers "unsupported" until BLE tap to pay (ENG-650, ENG-656). */
    bluetoothAsk: () => { host.global('__foxyBluetoothAnswer', 'unsupported'); return ['unsupported', null]; },
    shakeStart: () => ['ok', null],
    shakeStop: () => ['ok', null],
    /* The card session as Android answers it since ENG-667 (lib/card-host.js: CardBridge.kt,
     * CardGate.kt): a card behind it when a flow puts one there (`o.card`, `state.card`), and
     * otherwise a phone that cannot read one ("NFC is not available on this phone"). */
    ...CardHost.cardAnswers(host),
    /* As Android answers them since ENG-666 (lib/card-owner.js, CardOwner.kt, CardTime.kt):
     * the owner key and signatures from the seed's words, "bad request" for anything but
     * the six labels and their shapes, and the clock signed by the interim time key. */
    cardOwnerKey: (m) => CardOwner.ownerKey(m, state.seed ? OWNER_SEED : null),
    cardOwnerSign: (m) => CardOwner.ownerSign(m, state.seed ? OWNER_SEED : null),
    cardTime: () => CardOwner.cardTime(opts.nowMs ? opts.nowMs() : Date.now()),
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

  /* A push native makes after the reply it is answering (`afterReply`), as the
   * contract's push: refused, like `global`, when its phase is later than the host's. */
  let afterReplies = [];
  host.afterReply = (fn) => { afterReplies.push(fn); };
  host.pushAs = (name, arg) => {
    const entry = BY_NAME.get(name);
    if (!entry || entry.kind !== 'push') { violate('unknown', 'native pushes ' + name + ', which is not a push in contract.json'); return undefined; }
    if (opts.phase && laterThan(entry.phase, opts.phase)) {
      violate('unserved', opts.phase + ' pushes ' + entry.phase + ' push "' + name + '"');
      return undefined;
    }
    // an object argument is the payload itself (`_tap({side, stage})`), and has the contract's shape
    if (arg && typeof arg === 'object') {
      const p = check(entry.payloadSchema, arg);
      if (p.length) violate('payload', name + ': ' + p.join('; '));
    }
    return host.push(name, arg);
  };

  /* Call a window global native defines no member for (`__foxyBluetoothAnswer`), as
   * native evaluates `window.X && window.X(arg)`: on a later turn, never inside the
   * page's own postMessage call, and before a reply scheduled after it. */
  host.global = (name, arg) => {
    const entry = BY_NAME.get(name);
    if (!entry || entry.kind !== 'global') { violate('unknown', 'native calls ' + name + ', which is not a global in contract.json'); return; }
    if (opts.phase && laterThan(entry.phase, opts.phase)) {
      violate('unserved', opts.phase + ' pushes ' + entry.phase + ' global "' + name + '"');
      return;
    }
    host.pushes.push(name);
    setTimeout(() => {
      const w = host.window;
      if (w && typeof w[name] === 'function') w[name](arg);
    }, 0);
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
    if (opts.phase && laterThan(entry.phase, opts.phase)) {
      if (!entry.p1 && opts.unknown && opts.unknown.has(m.action)) {
        reply({ name: m.action, reply: { format: 'text' } }, m.id, null, 'Unknown action: ' + m.action);
        return;
      }
      if (!entry.p1) {
        violate('unserved', opts.phase + ' has no answer for ' + entry.phase + ' action "' + m.action + '" and the contract gives it no p1 stub');
        reply({ name: m.action, reply: { format: 'text' } }, m.id, null, 'Unknown action: ' + m.action);
        return;
      }
      reply(entry, m.id, entry.p1.text, entry.p1.error);
      return;
    }
    const over = opts.answers && opts.answers[m.action];
    afterReplies = [];
    const got = (over && over(m, host)) || answer[m.action](m, host);
    // an answer that takes a while (a card's, ENG-667): replied when it comes, as native's
    if (got && typeof got.then === 'function') {
      const after = afterReplies.splice(0);
      got.then((g) => {
        reply(entry, m.id, g[0], g[1]);
        for (const fn of after) setTimeout(fn, 0);
      });
      return;
    }
    reply(entry, m.id, got[0], got[1]);
    // scheduled after the reply's own turn, so the page hears the answer first
    for (const fn of afterReplies.splice(0)) setTimeout(fn, 0);
  };

  return host;
}

module.exports = { makeHost, snapshot, replyProblems, laterThan, PHASE_ORDER, CONTRACT };
