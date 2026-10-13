'use strict';
/* card-host.js — the fake host's card session, as Android answers it (ENG-667).
 *
 * The five actions (cardBegin, cardSend, cardSay, cardAgain, cardEnd) and the
 * `_card` push, by the rules of bridge/CardBridge.kt and flashcard/CardGate.kt
 * (FoxyBridge+Flashcard.swift and CardGate.swift at the pin): one session at a
 * time; the page's line cut to one printable line of 120; every command read
 * by the gate before it is carried (an applet chosen by its whole name first,
 * 2.0's CASHUCARD or, until the v0.1.3 re-pin, Foxy 1.x's FOXYCARD; then only
 * the 32 instructions under class B0, SET_TAG not among them); the
 * applet chosen again after a card comes back; progress pushed only from the
 * session in hand, before the reply it goes with; and the link's words for
 * each way a session ends.
 *
 * Behind it is a card: `host.state.card`, a model of iOS's tests/flashcard-card.js
 * (lib/flashcard-card.js, `makeCard`), or null for a phone that cannot read
 * one, or 'away' for a sheet nobody holds a card to (it times out).
 * `host.state.cardPull` set to N lets N more commands through and then takes
 * the card away, as getfoxy/card's simulator does (`ctl pull N`).
 */

/* flashcard/CardGate.kt: CASHU_APPLET (card protocol 2.0) and FOXY_APPLET (1.x, the pinned page's CARD_AID, out at the v0.1.3 re-pin) */
const CASHU_APPLET = 'f043415348554341524401';
const FOXY_APPLET = 'f0464f58594341524401';
const APPLETS = [CASHU_APPLET, FOXY_APPLET];
const APPLET_CLASS = 0xb0;
const INSTRUCTIONS = new Set([0x01, 0x10, 0x11, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x20, 0x22, 0x23, 0x24, 0x25, 0x26,
  0x30, 0x31, 0x32, 0x34, 0x35, 0x36, 0x37, 0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x51, 0x53]);
const LONGEST = 4 + 1 + 255 + 1;
const SCANNING = 'Reading the card';
const STAGES = new Set(['connected', 'say', 'end', 'lost']);
/* flashcard/CardLink.kt `CardLinkError` (CardLink.swift's words) */
const WORDS = { unavailable: 'NFC is not available on this phone', cancelled: 'the session was cancelled', timedOut: 'the session timed out', lost: 'the tag was lost' };
/* bridge/CardBridge.kt */
const NOT_A_COMMAND = 'That is not a command for a card.';
const NOT_FOXYS = 'That is not a command Foxy sends to a card.';
const BEGIN_LINE = 'Hold the card to the back of the phone';
const AGAIN_LINE = 'Hold the card here again';

function bytes(hex) {
  if (typeof hex !== 'string' || !hex.length || hex.length % 2 || hex.length > LONGEST * 2 || !/^[0-9a-fA-F]*$/.test(hex)) return null;
  return Buffer.from(hex, 'hex');
}

/* 'select', the instruction byte, or null: CardGate.read */
function read(b) {
  if (!b || b.length < 4 || b.length > LONGEST) return null;
  const after = b.length - 4;
  let data = Buffer.alloc(0);
  if (after > 1) {
    const lc = b[4];
    if (lc <= 0 || !(after === 1 + lc || after === 2 + lc)) return null;
    data = b.subarray(5, 5 + lc);
  }
  if (b[0] === 0x00 && b[1] === 0xa4) return (b[2] === 0x04 && b[3] === 0x00 && APPLETS.includes(data.toString('hex'))) ? 'select' : null;
  return (b[0] === APPLET_CLASS && INSTRUCTIONS.has(b[1])) ? b[1] : null;
}

const allows = (b, selected) => { const c = read(b); return c === 'select' || (c !== null && selected); };
const succeeded = (answer) => /9000$/i.test(answer);

/* CardGate.line: control and format characters and line breaks out, then 120 characters as a person counts them */
function line(text) {
  const kept = Array.from(String(text == null ? '' : text)).filter((c) => !/[\p{Cc}\p{Cf}\u2028\u2029]/u.test(c)).join('');
  const seg = new Intl.Segmenter(undefined, { granularity: 'grapheme' });
  return Array.from(seg.segment(kept)).slice(0, 120).map((s) => s.segment).join('');
}

function cardAnswers(host) {
  const state = host.state;
  let session = null;      // { card, selected, open }
  const progress = (s, stage, text) => {
    if (session !== s || !STAGES.has(stage)) return;
    host.cardSheet.push(stage + ': ' + line(text));
    // before the reply it goes with, as the link tells the page before it answers
    setTimeout(() => host.pushAs('_card', { stage, text: line(text) }), 0);
  };
  host.cardSheet = [];
  host.cardCarried = [];
  host.cardAgainAt = [];     // where in cardCarried each card found again by cardAgain begins

  const cardBegin = (m) => {
    const text = line(m.text);
    if (session) { const old = session; session = null; old.open = false; }
    const card = state.card;
    if (!card) return [null, WORDS.unavailable];
    if (card === 'away') return [null, WORDS.timedOut];
    const s = { card, selected: false, open: true };
    session = s;
    host.cardSheet.push('begin: ' + (text || BEGIN_LINE));
    card.tap();
    progress(s, 'connected', SCANNING);
    return ['ok', null];
  };

  const cardSend = (m) => {
    const b = bytes(m.apdu);
    if (!b) return [null, NOT_A_COMMAND];
    const s = session;
    if (!s || !s.card || s.gone) return [null, WORDS.lost];
    if (!allows(b, s.selected)) return [null, NOT_FOXYS];
    const choosing = read(b) === 'select';
    if (typeof state.cardPull === 'number') {
      if (state.cardPull <= 0) {
        state.cardPull = null;
        s.gone = true;
        progress(s, 'lost', WORDS.lost);
        return [null, WORDS.lost];
      }
      state.cardPull -= 1;
    }
    host.cardCarried.push(b.toString('hex'));
    return Promise.resolve(s.card.send(b.toString('hex'))).then((answer) => {
      const a = String(answer).toLowerCase();
      if (choosing && session === s) s.selected = succeeded(a);
      return [a, null];
    }, () => {
      s.gone = true;
      progress(s, 'lost', WORDS.lost);
      return [null, WORDS.lost];
    });
  };

  const cardSay = (m) => {
    const text = line(m.text);
    if (session && text) { host.cardSheet.push('say: ' + text); progress(session, 'say', text); }
    return ['ok', null];
  };

  const cardAgain = (m) => {
    const s = session;
    if (!s) return [null, WORDS.cancelled];
    s.selected = false;
    host.cardSheet.push('again: ' + (line(m.text) || AGAIN_LINE));
    if (!state.card || state.card === 'away') { session = null; return [null, WORDS.timedOut]; }
    s.card = state.card;
    s.gone = false;
    host.cardAgainAt.push(host.cardCarried.length);
    s.card.tap();
    progress(s, 'connected', SCANNING);
    return ['ok', null];
  };

  const cardEnd = (m) => {
    const s = session;
    if (s) {
      const error = typeof m.error === 'string' ? line(m.error) : null;
      progress(s, 'end', error || line(m.text));
      host.cardSheet.push(error ? 'error: ' + error : 'done: ' + line(m.text));
    }
    session = null;
    return ['ok', null];
  };

  return { cardBegin, cardSend, cardSay, cardAgain, cardEnd };
}

module.exports = { cardAnswers, read, allows, line, bytes, INSTRUCTIONS, APPLETS, CASHU_APPLET, FOXY_APPLET, WORDS, NOT_A_COMMAND, NOT_FOXYS, SCANNING };
