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
const { nprofile, readNprofile } = require('./nprofile');
const CONTRACT = require('../contract.json');

const BY_NAME = new Map(CONTRACT.map((e) => [e.name, e]));

/* The fake phone's onion service id: 56 characters of base32, the last a "d". */
const ONION_ID = 'abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwd';

/* The relays a Nostr inbox listens on and its nprofile names (NostrInbox.RELAYS, the first four
 * of NostrDelivery.FALLBACK_RELAYS), and the fallbacks a payment goes to when a request names none. */
const FALLBACK_RELAYS = ['wss://relay.damus.io', 'wss://nos.lol', 'wss://relay.primal.net', 'wss://relay.minibits.cash'];
const INBOX_RELAYS = FALLBACK_RELAYS.slice(0, 4);

/* What the payer hears for the page's word, as InboxAnswers.answerBody writes it:
 * {"ok":true}, or {"error":…} escaped as Apple's JSONSerialization escapes (the slash too). */
function appleJson(text) {
  let out = '"';
  for (const ch of String(text)) {
    const c = ch.codePointAt(0);
    if (ch === '"') out += '\\"';
    else if (ch === '\\') out += '\\\\';
    else if (ch === '/') out += '\\/';
    else if (ch === '\b') out += '\\b';
    else if (ch === '\f') out += '\\f';
    else if (ch === '\n') out += '\\n';
    else if (ch === '\r') out += '\\r';
    else if (ch === '\t') out += '\\t';
    else if (c < 0x20) out += '\\u' + c.toString(16).padStart(4, '0');
    else out += ch;
  }
  return out + '"';
}
function answerBody(status, text) { return status === 200 ? '{"ok":true}' : '{"error":' + appleJson(text) + '}'; }
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
 *   o.seedLabel what this phone's made-up seed is derived from (default
 *               'conformance seed'): a second phone in the same flow gets its
 *               own, so the two never make the same secrets at a shared mint
 *   o.onionPeer another session's host (makeHost) that `onionPost` delivers to,
 *               as Tor carries a post to the receiving phone's onion inbox: its
 *               `deliver(url, body)` pushes `_requestPaid` into that page and
 *               answers with what the page told `inboxAnswer`. Without one, a
 *               post is answered as an address nobody publishes.
 *   o.nostrPeer another session's host whose Nostr inbox the relays carry this
 *               phone's `nostrSend` to (ENG-677): a gift wrap for the key its
 *               request named reaches its page as `_requestPaid(body, "")`. The
 *               relays take any event, so without one (or for a key nobody
 *               listens on) a send is still taken by every relay it names.
 *   o.nostrDeaf true: no relay will listen, so `inboxOpen` answers the onion
 *               alone, as Android and iOS do then
 *   o.bluetooth the phone's Bluetooth, as Android's TapBridge reads it (ENG-670):
 *               'unsupported' (default: no Bluetooth LE peripheral, the ENG-656
 *               answers), or a phone with one whose state is 'allowed', 'off' or
 *               'denied'. With one, bluetoothAsk answers that state, and a new
 *               receiver says `searching` ('allowed') or the state after its
 *               reply; host.tap drives a payer over its link (below).
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
    bluetooth: opts.bluetooth || 'unsupported', linked: false, offered: '', early: false,
    shakeArmed: false, answers: new Map(), m6: [],
    pay: null, payLinked: false, paySent: [], payM6: JSON.stringify({ ok: true, code: 200 }),
    card: opts.card || null, cardPull: null,
    inbox: null, nostr: null,
  };
  if (!['unsupported', 'allowed', 'off', 'denied'].includes(state.bluetooth)) throw new Error('unknown o.bluetooth: ' + state.bluetooth);
  const SEED = crypto.createHash('sha256').update(opts.seedLabel || 'conformance seed').digest();
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
    inboxAnswers: [],    // [status, body] each payer was answered, as Android writes it
    answersAsked: [],    // [answer, status] of every inboxAnswer the page asked, a Nostr payment's "" included
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
    /* As Android answers them since ENG-672 and ENG-677 (bridge/DeliveryBridge.kt): with Tor
     * up, an onion address and a Nostr key made for the request (an nprofile naming it and
     * the four relays it listens on), the same ones until they are closed, {"onion","nostr"}
     * in that order, or {"onion"} alone when no relay would listen (o.nostrDeaf); a post only
     * through Tor, only to a v3 onion, delivered to `o.onionPeer`. */
    inboxOpen: () => {
      if (state.tor !== 'up') return NO('No onion address: Tor is not ready.');
      if (!state.inbox) state.inbox = 'http://' + ONION_ID + '.onion/' + crypto.randomBytes(16).toString('hex');
      if (!state.nostr && !opts.nostrDeaf) {
        const key = crypto.randomBytes(32).toString('hex');
        state.nostr = { key, nprofile: nprofile(key, INBOX_RELAYS) };
      }
      return state.nostr ? J({ onion: state.inbox, nostr: state.nostr.nprofile }) : J({ onion: state.inbox });
    },
    // the onion address and the Nostr key alike end with the request (handleInboxClose)
    inboxClose: () => { state.inbox = null; state.nostr = null; return ['ok', null]; },
    /* The page's word on a payment it was handed, from the one table the onion's and the
     * tap's payments wait in (FoxyBridge+Delivery.swift `handleInboxAnswer`, InboxAnswers.kt):
     * taken out by its key, so it is answered once; the status clamped to 200, 409 or 422,
     * the text to 300 characters as a person counts them, and the payer's JSON written as
     * InboxAnswers.answerBody writes it. A tap's answer reads the reason back out of it. */
    inboxAnswer: (m) => {
      host.answersAsked.push([m.answer, m.status]);
      const tell = state.answers.get(m.answer);
      if (!tell) return NO('No payment is waiting on that answer.');
      state.answers.delete(m.answer);
      const status = [200, 409, 422].includes(m.status) ? m.status : 422;
      const text = [...new Intl.Segmenter('en', { granularity: 'grapheme' }).segment(String(m.text || ''))].slice(0, 300).map((x) => x.segment).join('');
      tell(status, answerBody(status, text));
      return ['ok', null];
    },
    onionPost: (m) => {
      if (state.tor !== 'up') return NO('Foxy is not connected to Tor.');
      if (!/^http:\/\/[a-z2-7]{55}d\.onion(\/[!-~]*)?$/.test(String(m.url))) return NO('That is not an onion address.');
      if (!m.body) return NO('That payment is too large to send.');
      if (!opts.onionPeer) return NO('Their phone could not be reached over Tor.');
      return opts.onionPeer.deliver(m.url, m.body).then((text) => [text, null]);
    },
    /* As DeliveryBridge.nostrSend and NostrDelivery answer (ENG-677): a target, a body of at
     * most 32767 UTF-8 bytes, an nprofile that reads, and relays that are wss (each once, at
     * most five, the fallbacks when it names none); Tor only. Every relay takes the wrap, as
     * relays do, so the answer is how many there were; the receiver named by `o.nostrPeer`
     * gets it if its key is the one the request named and is still listening. */
    nostrSend: (m) => {
      if (typeof m.target !== 'string' || !m.target) return NO('That request names no Nostr address.');
      const body = typeof m.body === 'string' ? m.body : '';
      if (!body || Buffer.byteLength(body, 'utf8') > 32767) return NO('That payment is too large to send over Nostr.');
      const profile = readNprofile(m.target);
      if (!profile) return NO('The payment could not be sent over Nostr.');
      const named = profile.relays.length ? profile.relays : FALLBACK_RELAYS;
      const relays = [...new Set(named.map((r) => r.trim()).filter((r) => /^wss:\/\/[!-~]+$/.test(r)))].slice(0, 5);
      if (!relays.length) return NO('The payment could not be sent over Nostr.');
      if (state.tor !== 'up') return NO('No relay took the payment: Foxy is not connected to Tor.');
      if (opts.nostrPeer) opts.nostrPeer.deliverNostr(profile.pubkey, body);
      return [String(relays.length), null];
    },
    /* As Android's TapBridge answers (ENG-670; FoxyBridge+Tap.swift `handleTapReceiveStart`,
     * TapLink.swift `cbState`): a non-empty payload TapRules.kt (TapProtocol.isPayload)
     * would refuse is refused with its words, making no receiver; otherwise "ok", and
     * the receiver this makes says its radio after the reply: `unsupported` on a phone
     * without a Bluetooth LE peripheral (ENG-656), `searching` once on the air, or `off`
     * or `denied`. A receiver already held takes the payload and says nothing more. */
    tapReceiveStart: (m) => {
      const payload = typeof m.payload === 'string' ? m.payload : '';
      if (payload !== '' && !TapRules.isPayload(payload)) return NO(TapRules.NOT_PAYABLE);
      state.offered = payload;
      state.early = !!m.early;
      if (!state.receiverHeld) {
        state.receiverHeld = true;
        const stage = state.bluetooth === 'allowed' ? 'searching' : state.bluetooth;
        host.afterReply(() => host.pushAs('_tap', { side: 'receive', stage }));
      }
      return ['ok', null];
    },
    tapReceiveStop: () => { state.receiverHeld = false; state.linked = false; return ['ok', null]; },
    /* The payer's listen as Android's TapBridge serves it (ENG-671; FoxyBridge+Tap.swift
     * `handleTapPayStart`): `now` answered at once; otherwise the listen before is ended
     * ("Started again.") and this one is answered only with an offer (host.payer.link), or
     * by tapPayStop and the page going ("Stopped."). A phone whose Bluetooth cannot listen
     * says so as a stage, once, after the call, and the call stays open: nothing for the
     * page to ask again (ENG-658). */
    tapPayStart: (m) => {
      if (m.now) { if (state.pay) state.pay.nudged = true; return ['ok', null]; }
      endPay('Started again.');
      state.payLinked = false;
      return new Promise((resolve) => {
        state.pay = { resolve, nudged: false };
        if (state.bluetooth !== 'allowed') setTimeout(() => host.pushAs('_tap', { side: 'pay', stage: state.bluetooth }), 0);
      });
    },
    /* The payment over the held link (TapBridge.kt `tapSend`): the receiver's M6 as it
     * sealed it, which host.payer sets (state.payM6). */
    tapSend: (m) => {
      if (!m.body) return NO('There is nothing to hand over.');
      if (!state.payLinked) return NO('That phone is no longer connected.');
      state.paySent.push(m.body);
      return [state.payM6, null];
    },
    /* The receiving side's words over a link (TapBridge.kt): only a linked receiver
     * can send M7, M9 or M11; the payer of host.tap keeps any change at once. */
    tapChange: (m) => {
      if (!m.body) return NO('There is no change to send.');
      if (!state.linked) return NO('That phone is no longer connected.');
      return ['ok', null];
    },
    tapChangeKept: () => { if (!state.changeWaiting) return NO('No change is waiting to be kept.'); state.changeWaiting = false; return ['ok', null]; },
    tapAsking: () => [state.linked ? 'ok' : 'no link', null],
    tapChangeDue: () => ['ok', null],
    tapQuote: () => (state.payLinked ? ['ok', null] : NO('That phone is no longer connected.')),
    tapTerms: () => (state.linked ? ['ok', null] : NO('That phone is no longer connected.')),
    tapPayStop: () => { endPay('Stopped.'); state.payLinked = false; return ['ok', null]; },
    /* The gate's own global first, then the reply, in iOS's order (FoxyBridge+Tap.swift
     * `handleBluetoothAsk`): the first-install screen leaves only on the push. The
     * state is the phone's (o.bluetooth), "unsupported" without Bluetooth (ENG-656). */
    bluetoothAsk: () => { host.global('__foxyBluetoothAnswer', state.bluetooth); return [state.bluetooth, null]; },
    shakeStart: () => { state.shakeArmed = true; return ['ok', null]; },
    shakeStop: () => { state.shakeArmed = false; return ['ok', null]; },
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
  /* Every `_tap` pushed through pushAs, its payload as said ({side, stage, …}). */
  host.taps = [];

  /* A listen waiting for an offer is answered with [why] and let go (TapBridge.kt `endTapPay`). */
  function endPay(why) {
    const p = state.pay;
    state.pay = null;
    if (p) p.resolve([null, why]);
  }
  host.endPay = endPay;

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
  host.pushAs = (name, arg, ...more) => {
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
    // each `_tap` as it was said, so a flow can tell the receiving side's from the paying side's (ENG-671)
    if (name === '_tap' && arg && typeof arg === 'object') host.taps.push(Object.assign({}, arg));
    if (!more.length) return host.push(name, arg);
    // positional arguments (`_requestPaid(body, answerId, "tap")`), each the type its field gives
    const fields = Object.entries(entry.payloadSchema.fields);
    const args = [arg, ...more];
    if (args.length > fields.length) violate('payload', name + ': ' + args.length + ' arguments for ' + fields.length + ' fields');
    const p = check(entry.payloadSchema, Object.fromEntries(fields.slice(0, args.length).map(([k], i) => [k, args[i]])));
    if (p.length) violate('payload', name + ': ' + p.join('; '));
    host.pushes.push(name);
    const W = host.window && host.window.FoxyWallet;
    if (!W || typeof W[name] !== 'function') return undefined;
    return W[name](...args);
  };

  /* A payer over a receiver's link, as Android's TapReceiver tells the page of one
   * (TapBridge.kt, TapGattServer.kt): `near` (the edge it chose), `link` (its first
   * word, then the four digits), `pay` (a payment that crossed the link, pushed as
   * `_requestPaid(body, key, "tap")`; resolves with the page's answer, as M6 carries
   * it: [status, why]), `shake` (pressed only while shake to TAP is armed), `gone`.
   * Needs a receiver on the air (o.bluetooth 'allowed' and a tapReceiveStart). */
  host.tap = {
    near: (edge) => host.pushAs('_tap', { side: 'receive', stage: 'nearby', edge }),
    link: (code) => {
      if (state.bluetooth !== 'allowed' || !state.receiverHeld) { violate('tap', 'a payer linked with no receiver on the air'); return; }
      host.pushAs('_tap', { side: 'receive', stage: 'connecting' });
      host.pushAs('_tap', { side: 'receive', stage: 'code', code });
      state.linked = true;
    },
    pay: (body) => new Promise((resolve) => {
      if (!state.linked) { violate('tap', 'a payment over no link'); resolve(null); return; }
      const key = crypto.randomBytes(16).toString('hex');
      state.answers.set(key, (status, said) => {
        // the reason alone, read back out of the payer's JSON (FoxyBridge+Tap.swift 107-110)
        const why = status === 200 ? '' : (JSON.parse(said).error || '');
        state.m6.push([status, why]);
        resolve([status, why]);
      });
      host.pushAs('_requestPaid', body, key, 'tap');
    }),
    shake: () => { if (state.shakeArmed) host.pushAs('_tap', { side: 'receive', stage: 'shake' }); },
    gone: () => { state.linked = false; host.pushAs('_tap', { side: 'receive', stage: 'gone' }); },
  };

  /* A receiver the payer's listen hears, as Android's TapPayer tells the page of one
   * (TapBridge.kt, TapPayer.kt; ENG-671): `nearby` (TAP TO PAY, with this screen's edge),
   * `far`, `link` (connecting, linked, and the listen answered {payload, code}), `change`
   * (`_tapChange`, answered with tapChangeKept), `terms` (`_tapTerms`), `asking`, and
   * `gone` (the held link went). Needs a listen (a tapPayStart on o.bluetooth 'allowed'). */
  host.payer = {
    nearby: (edge) => host.pushAs('_tap', { side: 'pay', stage: 'nearby', edge }),
    far: () => host.pushAs('_tap', { side: 'pay', stage: 'far' }),
    link: (payload, code) => {
      const p = state.pay;
      if (state.bluetooth !== 'allowed' || !p) { violate('tap', 'a receiver linked with no listen'); return; }
      host.pushAs('_tap', { side: 'pay', stage: 'connecting' });
      host.pushAs('_tap', { side: 'pay', stage: 'linked' });
      state.pay = null;
      state.payLinked = true;
      p.resolve([JSON.stringify({ payload, code }), null]);
    },
    change: (token) => { if (!state.payLinked) { violate('tap', 'change over no link'); return; } host.pushAs('_tapChange', token); },
    terms: (text) => { if (!state.payLinked) { violate('tap', 'terms over no link'); return; } host.pushAs('_tapTerms', text); },
    asking: () => host.pushAs('_tap', { side: 'pay', stage: 'asking' }),
    gone: () => { state.payLinked = false; },
  };

  /* A payment posted to this phone's onion inbox (OnionInbox → DeliveryBridge.paymentArrived):
   * `_requestPaid(body, key)` pushed into the page with a fresh 32-hex key, and the
   * payer answered "status\nbody" once the page says how it went (`inboxAnswer`), or
   * 404 for an address this phone is not answering. Refused, as a push, on a host
   * whose phase is earlier than the push's. */
  /* A gift wrap for `key` that a relay hands this phone's Nostr inbox (NostrInbox → DeliveryBridge.
   * nostrPaymentArrived): `_requestPaid(body, "")` pushed into the page when the key is the one
   * its request named and is still open, once per wrap. Nobody waits on the answer: the payer
   * published and walked away, so no key goes in the answers table. A key that was closed (or
   * never this phone's) hears nothing: the relay keeps the wrap, unread. Refused, as a push, on
   * a host whose phase is earlier than the push's. */
  host.deliverNostr = (key, body) => {
    if (!state.nostr || state.nostr.key !== key) return false;
    const entry = BY_NAME.get('_requestPaid');
    if (opts.phase && laterThan(entry.phase, opts.phase)) {
      violate('unserved', opts.phase + ' pushes ' + entry.phase + ' push "_requestPaid"');
      return false;
    }
    host.pushes.push('_requestPaid');
    setTimeout(() => {
      const W = host.window && host.window.FoxyWallet;
      if (W && typeof W._requestPaid === 'function') W._requestPaid(body, '');
    }, 0);
    return true;
  };

  host.deliver = (url, body) => new Promise((resolve) => {
    if (!state.inbox || url !== state.inbox) { resolve('404\n'); return; }
    const entry = BY_NAME.get('_requestPaid');
    if (opts.phase && laterThan(entry.phase, opts.phase)) {
      violate('unserved', opts.phase + ' pushes ' + entry.phase + ' push "_requestPaid"');
      resolve('404\n');
      return;
    }
    const key = crypto.randomBytes(16).toString('hex');
    // the same table a tap's payment waits in (InboxAnswers.onion)
    state.answers.set(key, (status, said) => {
      host.inboxAnswers.push([status, said]);
      resolve(status + '\n' + said);
    });
    host.pushes.push('_requestPaid');
    setTimeout(() => {
      const W = host.window && host.window.FoxyWallet;
      if (W && typeof W._requestPaid === 'function') W._requestPaid(body, key);
    }, 0);
  });

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
    // an answer that takes a while (a card's, ENG-667; a post to another phone, ENG-672): replied when it comes, as native's
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

module.exports = { makeHost, snapshot, replyProblems, laterThan, answerBody, appleJson, ONION_ID, FALLBACK_RELAYS, INBOX_RELAYS, PHASE_ORDER, CONTRACT };
