/* foxy-tor-gate.js — the screens between launch and a private connection.
 *
 * Foxy reaches the network through its own Tor. The native side owns that
 * state and routes every request by it: a request it cannot send through Tor
 * it refuses, unless the person using the app chose to continue unprotected
 * this session. So nothing here is enforcement. These screens say what the
 * state is and offer the two ways forward.
 *
 * Replaces foxy-vpn-gate.js, which decided privacy in nine places that had to
 * agree — Orbot, a system VPN, an exposed mode, embedded Tor — and repeatedly
 * did not. There is now one question, asked of one place: is Tor up?
 *
 * What the person can see:
 *   CONNECTING TO TOR       — launch, and every return from the background,
 *                             while a private connection is set up; RESTART
 *                             TOR while the native side says Tor is stuck
 *   OPENING YOUR WALLET     — then the mint and the balance, on the same screen
 *   CANNOT CONNECT TO TOR   — RETRY, or CONTINUE UNPROTECTED
 *   TOR STOPPED             — Tor's thread has exited and cannot start again
 *                             inside Foxy: reopen Foxy, or CONTINUE UNPROTECTED
 *   the app                 — Tor is up and the balance loaded, or they chose
 *                             to continue
 *
 * Once Tor has been up since launch or the last return, losing it shows no
 * screen: requests are refused until it is back, and the place a request was
 * refused says so.
 * Continuing unprotected lasts until Tor connects — Tor keeps trying, and the
 * app moves onto it when it does — or until the app is closed.
 *
 * Self-managed DOM, like foxy-send-progress.js: built with createElement and
 * updated by writing styles directly, never through the design runtime.
 */
(function () {
  'use strict';

  var root = null, nodes = null;
  var screen = null;        // null | 'connecting' | 'failed' | 'vpn'
  var onRetry = null;       // the app's boot, run when a failed screen clears
  var hold = false;         // keep CONNECTING up across the wallet search
  var launchStep = '';                             // launch, past Tor: the mint, the balance
  var retrying = false, letGo = null;
  /* The number is a pace, not a measurement: 0 to 99 over paceMs on every
   * arrival, waiting at 99 until the launch is genuinely through, then 100.
   * See startCount. */
  /* RESTART TOR. Nobody should ever have to kill Foxy to get Tor moving; before
   * it the connecting screen had no button at all. It is offered while the
   * native side reports `stuck` (TorStuck, in TorTransport.swift): no control
   * link for 10 seconds, or nothing arriving for 30 (longer over a bridge,
   * where only Tor's events can be seen: 60, and 120 on Snowflake). Not on a percentage that
   * stands still: on a slow network it stands still while Tor downloads, and a
   * restart then undoes the download and counts against Tor's relays. */
  var waiters = [];         // functions of a state; true when they are done

  function W() { return window.FoxyWallet; }

  function bridged() {
    return !!(window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.foxy);
  }

  function state() {
    var w = W();
    return (w && w.privacy) ? w.privacy() : null;
  }

  /* May the app run? Tor, or the person's own choice. With no native side —
   * a desktop browser — there is nothing to wait for. */
  function through(p) {
    if (!bridged()) return true;
    /* `offline` belongs here and nowhere near `routeOpen`.
     *
     * This asks "may the app run", and with offline chosen it may: ecash already
     * held can be handed over, and ecash locked to this phone can be taken. Left
     * out of here, every push from Swift would raise this screen back over a
     * person who had already said carry on, and every FoxyGate.quiet() caller
     * would put it up again. The wallet's `routeOpen` is the other question —
     * may anything be sent — and offline is deliberately absent from it. */
    /* With no interface, neither Tor's `up` nor a choice to go unprotected is a
     * route: one is a circuit that outlives the network and the other sends in
     * the clear over nothing at all. The person's own offline choice still
     * counts, because that is a choice about this app rather than a claim about
     * the wire. */
    if (p && p.network === 'none' && !p.offline) return false;
    return !!p && (p.tor === 'up' || p.unprotected || p.offline);
  }

  /* Has the person said carry on with no connection?
   *
   * Once they have, this file has nothing left to say. It was still saying it:
   * `proceedOffline` took the gate down, the launch's next step put it straight
   * back up (`launchStage` raises the screen when none is showing), and the
   * ending then played SECURING YOUR CONNECTION over a phone with its radios
   * off — eleven milliseconds after they tapped, and for a second and a half.
   * There is no connection to secure and
   * nothing to celebrate; the gate stays down. */
  /* The wait between the path monitor saying there is nothing and this file
   * saying so (see `_changed`). */
  var netGone = null;
  /* And whether the screen showing is one this file raised because the network
   * went, rather than one a launch put up. It has to come down again by itself:
   * `check`'s own takedown belongs to a promise that resolved long ago, and
   * nothing else watches a `connecting` screen for a route arriving. */
  var netRaised = false;
  /* Whether the try that follows a network appearing has already been made for
   * this stretch of having one. Cleared when the network goes again. */
  var triedOnNetwork = false;
  /* Whether the connecting screen showing is the one the automatic try from
   * offline put up, and the clock that takes it down again if that try gets
   * nowhere. Raising a screen over somebody working offline is how a phone
   * was once locked out, so this one is always on a timer. */
  var offRaised = false;
  var offGiveUp = null;
  /* And the screen the person asked for, by tapping the banner. It is not put
   * away by `goHomeFirst`, and it comes down by itself once Tor is through. */
  var tapRaised = false;

  function chosenOffline() {
    var p = state();
    return !!(p && p.offline);
  }

  /* ---- home first ---------------------------------------------------------
   *
   * Foxy opens on the home screen. This file's connecting screen made every
   * launch and every return a wait: three to fifteen seconds of SECURING YOUR
   * CONNECTION before a balance could be read or a payment started, and a
   * second and a half of its ending after Tor was already up. A wallet that
   * has connected once has all it needs to be useful before the connection:
   * its mint's keysets and a price are on file (`offlineReady`), which is
   * what working offline runs on, and working offline gives itself up the
   * moment Tor is really up (`_privacy`).
   *
   * So wherever this file would have put the connecting screen up by itself
   * (a launch, a return, a request, the network going), it puts the wallet
   * into working offline instead and stays down. The banner at the foot of
   * the home screen says the rest: SECURING YOUR CONNECTION while Tor is at
   * work, OFFLINE - NO CONNECTION with no network. A step that moves money
   * waits a few seconds for a connection that is on its way rather than take
   * the offline branch (`routeSoon`, in the wallet).
   *
   * Not on a first launch: with nothing on file there is no wallet to show,
   * and that launch keeps this screen and the fox. And never over a screen
   * the person opened by tapping the banner (`tapRaised`): that one is theirs,
   * and comes down when Tor is through or when they say.
   *
   * Nothing here decides whether anything is sent. The native side refuses
   * every request without Tor, as it always has. */
  function homeFirst() {
    if (FoxyGate.homeFirst === false || !bridged()) return false;
    var w = W();
    if (!w || typeof w.offlineReady !== 'function' || typeof w.setOffline !== 'function') return false;
    try { return !!w.offlineReady(); } catch (e) { return false; }
  }

  /* Instead of the screen: working offline, until the connection is up. True
   * when the screen is not needed, whether or not anything had to change. */
  function goHomeFirst(why) {
    if (tapRaised || !homeFirst()) return false;
    var p = state();
    if (through(p)) return true;
    console.log('[foxy] tor gate: home first, ' + why + ' \u2014 working offline until the connection is up');
    /* Tor is already setting itself up, so the try this file makes when a
     * network appears is spent for this stretch: it is a restart, and a
     * restart a few seconds into a launch only sets Tor back (seen in the
     * simulator: ten seconds in, "restarting (the person asked)"). Spent
     * unless the phone is known to have no network, which is the one case
     * that try is for; before the phone has said what network it has, Tor is
     * at work all the same. */
    if (!(p && p.network === 'none')) triedOnNetwork = true;
    W().setOffline(true);
    return chosenOffline();
  }

  function el(style, html) {
    var d = document.createElement('div');
    d.style.cssText = style;
    if (html) d.innerHTML = html;
    return d;
  }

  /* Tell the native side the page has the screen to itself now, so the splash
   * — and, on a return, the app-switcher cover — can come off without the home
   * screen showing in between (FoxyWebView).
   *
   * Said every time something of this file's goes up, not once per load: a
   * return from the background puts the launch screen up again and the cover
   * over it is waiting to hear exactly that. */
  function sayCovered() {
    var mh = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.foxy;
    if (!mh) return;
    /* After it has painted, not after it has been added. Saying it on the same
     * turn took the splash off a frame before the page had drawn anything, and
     * what showed in that frame was the home screen. Two
     * frames is the guarantee: one to lay out, one to paint. */
    var post = function () {
      try { mh.postMessage({ id: 'covered-' + Date.now(), action: 'covered' }); } catch (e) {}
    };
    /* And on a clock as well. At launch, under the phone's splash, the two
     * frames did not come, and the splash sat over the PIN pad for the
     * phone's own eight seconds. Said once, by whichever comes first; the
     * phone takes a second word as no news. */
    var said = false;
    var once = function () { if (said) return; said = true; post(); };
    if (typeof requestAnimationFrame !== 'function') { once(); return; }
    requestAnimationFrame(function () { requestAnimationFrame(once); });
    setTimeout(once, 150);
  }

  function gateChanged() {
    if (typeof window.__foxyGateChanged === 'function') {
      try { window.__foxyGateChanged(); } catch (e) {}
    }
  }

  /* Continuing unprotected is confirmed by iOS, not by this page.
   *
   * The tap asks the native side, which shows its own alert, and only a tap on
   * that alert turns Tor off. A script in this page can ask; it cannot answer
   * an alert iOS draws. That replaces the second tap that used to live here,
   * which any script could have simulated. */
  var UNPROTECTED = 'CONTINUE UNPROTECTED';

  /* What the launch screen is called, start to finish. It used to say
   * CONNECTING TO TOR and then OPENING YOUR WALLET, which made one wait look
   * like two. The screens that need a person to do
   * something — Orbot, a failure, Tor stopped — keep their own titles. */
  var SECURING = 'SECURING YOUR CONNECTION';



  function armUnprotected(node) {
    node.textContent = UNPROTECTED;
    node.style.color = 'rgba(255,255,255,.55)';
    node.addEventListener('click', function () { FoxyGate.continueUnprotected(); });
  }

  /* ---- the circuit ---------------------------------------------------------
   *
   * Three relays, drawn as a triangle that fills, with the phone tucked inside
   * concentric shells once it is. The numbers
   * are load-bearing and belong together: the geometry lives in a 300 x 195 view
   * box with the apex at (150,12) and the feet at (294,183) and (6,183); the
   * rings and the phone sit on the triangle's incenter, 59.5% down; the dashed
   * ring is its exact incircle, 44.7% of the width. Move the triangle and all
   * four numbers move with it.
   *
   * What the mockup did and this does not: it ran the percentage off the same
   * six-second loop as the drawing, counting to 100 every time round. Here the
   * drawing loops and the number does not — it is Tor's own bootstrap, and a
   * launch screen that invents progress is a launch screen that lies about
   * whether anything is connecting.
   */
  var CSS = [
    // the snow, which is weather rather than progress, and the ring's slow turn
    '@keyframes ftg-ring-spin{from{transform:translate(-50%,-50%) rotate(0)}to{transform:translate(-50%,-50%) rotate(360deg)}}',
    '@keyframes ftg-fall{0%{transform:translate3d(0,-40px,0)}100%{transform:translate3d(var(--drift),var(--fallTo,900px),0)}}',
    '@keyframes ftg-sway{0%,100%{transform:translateX(calc(var(--sway) * -1))}50%{transform:translateX(var(--sway))}}',
    '@keyframes ftg-flake{0%{opacity:0}8%{opacity:var(--op)}88%{opacity:var(--op)}100%{opacity:0}}',
    '#ftg-root .ftg-flake{position:absolute;top:0;will-change:transform;animation:ftg-fall linear infinite}',
    '#ftg-root .ftg-flake>div{width:100%;height:100%;animation:ftg-sway ease-in-out infinite}',
    '#ftg-root .ftg-flake>div>div{width:100%;height:100%;border-radius:50%;opacity:0;' +
      'background:radial-gradient(circle at 34% 30%,#FFF 0%,#E4EEF6 45%,rgba(190,212,230,.55) 100%);' +
      'animation:ftg-flake linear infinite}',
    '#ftg-root .ftg-ring{animation:ftg-ring-spin 18s linear infinite}',
    '#ftg-root .ftg-seg,#ftg-root .ftg-node{transition:none}',
    // the closing half moves on its own, over the second the screen holds at 100
    '#ftg-root .ftg-fill{transition:clip-path .55s ease-out}',
    '#ftg-root .ftg-shell{transition:transform .5s cubic-bezier(.34,1.4,.5,1),opacity .4s ease-out}',
    '#ftg-root .ftg-ring{transition:opacity .45s ease-out}',
    '#ftg-root .ftg-eye-open,#ftg-root .ftg-eye-shut{transition:opacity .35s ease-out}',
    '#ftg-root.ftg-still .ftg-flake,#ftg-root.ftg-still .ftg-flake>div,' +
      '#ftg-root.ftg-still .ftg-flake>div>div,#ftg-root.ftg-still .ftg-ring{animation-play-state:paused}',
    '@media (prefers-reduced-motion: reduce){#ftg-root .ftg-snow{display:none}' +
      '#ftg-root .ftg-ring{animation:none}}',
  ].join('\n');

  var FROST = '226,240,252';

  /* The circuit is the percentage, drawn.
   *
   * The design this came from ran it on a six-second loop and counted 0-100
   * alongside, which made the drawing decoration: it said the same thing at 5%
   * as at 95%. It tracks the real number now — the three
   * relays light and the legs draw between them as the number climbs, the
   * triangle floods from 58% on, the shells close around the phone after 76%,
   * and the eye shuts at 78%. Nothing loops, so a screen that is stuck looks
   * stuck, which is the truth.
   *
   * The thresholds are the design's own timeline, read as percentages. */
  function circuit() {
    var wrap = el('position:relative;flex:none;width:min(300px,84%);aspect-ratio:300 / 195;margin-bottom:4px');
    wrap.innerHTML =
      '<svg viewBox="0 0 300 195" aria-hidden="true" style="position:absolute;inset:0;width:100%;' +
        'height:100%;overflow:visible">' +
        '<polygon class="ftg-fill" points="150,12 294,183 6,183" fill="rgba(' + FROST + ',.28)" ' +
          'style="clip-path:inset(100% 0 0 0)"></polygon>' +
        /* The shape is always there, faintly, with the lit legs drawn over it
         * as the number climbs — the same idea as the bar and its track. At 4%
         * the screen was otherwise a phone floating in the snow. */
        '<polygon points="150,12 294,183 6,183" fill="none" stroke="rgba(' + FROST + ',.17)" ' +
          'stroke-width="2" stroke-linejoin="round"></polygon>' +
        '<g fill="none" stroke="#EDF4FA" stroke-width="3" stroke-linecap="round">' +
          '<line class="ftg-seg" x1="150" y1="12" x2="294" y2="183" ' +
            'style="stroke-dasharray:233;stroke-dashoffset:233"></line>' +
          '<line class="ftg-seg" x1="294" y1="183" x2="6" y2="183" ' +
            'style="stroke-dasharray:288;stroke-dashoffset:288"></line>' +
          '<line class="ftg-seg" x1="6" y1="183" x2="150" y2="12" ' +
            'style="stroke-dasharray:233;stroke-dashoffset:233"></line>' +
        '</g>' +
        '<circle class="ftg-node" cx="150" cy="12" r="8" fill="#fff" ' +
          'style="transform-origin:150px 12px;opacity:0;transform:scale(.3)"></circle>' +
        '<circle class="ftg-node" cx="294" cy="183" r="7" fill="#fff" ' +
          'style="transform-origin:294px 183px;opacity:0;transform:scale(.3)"></circle>' +
        '<circle class="ftg-node" cx="6" cy="183" r="7" fill="#fff" ' +
          'style="transform-origin:6px 183px;opacity:0;transform:scale(.3)"></circle>' +
      '</svg>' +
      // the incircle, and the shells that close once the triangle is full
      '<div class="ftg-ring" style="position:absolute;left:50%;top:59.5%;width:44.7%;aspect-ratio:1;' +
        'border-radius:50%;border:2px dashed rgba(' + FROST + ',.4);opacity:0"></div>' +
      shell(36.4, .06, .34, 30) + shell(28.7, .09, .44, 22) + shell(21, .13, .56, 18) +
      // the phone on the incenter, eye open until the circuit closes
      '<div class="ftg-phone" style="position:absolute;left:50%;top:59.5%;width:50px;height:98px;' +
        'border-radius:13px;transform:translate(-50%,-50%);' +
        'background:linear-gradient(170deg,#1E1E1E,#0A0A0A);border:2px solid #3E4348;' +
        'box-shadow:0 14px 34px rgba(0,0,0,.8);display:flex;align-items:center;justify-content:center">' +
        '<span style="position:absolute;left:50%;transform:translateX(-50%);top:6px;width:16px;' +
          'height:3px;border-radius:2px;background:#3E4348"></span>' +
        '<span style="position:relative;width:24px;height:24px;display:flex;align-items:center;' +
          'justify-content:center">' +
          '<svg class="ftg-eye-open" viewBox="0 0 24 24" style="position:absolute;width:24px;height:24px;' +
            'fill:none;stroke:#5E6B75;stroke-width:1.9;stroke-linecap:round;stroke-linejoin:round">' +
            '<path d="M2.4 12s3.7-6 9.6-6 9.6 6 9.6 6-3.7 6-9.6 6-9.6-6-9.6-6Z"></path>' +
            '<circle cx="12" cy="12" r="2.7"></circle></svg>' +
          '<svg class="ftg-eye-shut" viewBox="0 0 24 24" style="position:absolute;width:24px;height:24px;' +
            'fill:none;stroke:#fff;stroke-width:1.9;stroke-linecap:round;stroke-linejoin:round;opacity:0">' +
            '<path d="M2.4 10.8s3.7 5.4 9.6 5.4 9.6-5.4 9.6-5.4"></path>' +
            '<path d="M5 14.6 3.4 17.2"></path><path d="M9.2 16.6 8.5 19.4"></path>' +
            '<path d="M14.8 16.6l.7 2.8"></path><path d="M19 14.6l1.6 2.6"></path></svg>' +
        '</span>' +
        '<span style="position:absolute;left:50%;transform:translateX(-50%);bottom:6px;width:20px;' +
          'height:3px;border-radius:2px;background:#3E4348"></span>' +
        // the screen it ends on: green, with a tick, once everything is through
        '<div class="ftg-done" style="position:absolute;left:3px;right:3px;top:3px;bottom:3px;' +
          'border-radius:10px;background:#1FA85A;display:flex;align-items:center;justify-content:center;' +
          'opacity:0;transition:opacity .28s ease-out">' +
          '<svg viewBox="0 0 24 24" style="width:30px;height:30px;fill:none;stroke:#fff;' +
            'stroke-width:2.8;stroke-linecap:round;stroke-linejoin:round">' +
            '<path d="M4.5 12.5 9.5 17.5 19.5 6.5"></path></svg>' +
        '</div>' +
      '</div>';
    return wrap;
  }

  // 0 below `from`, 1 above `to`, and the way between
  function ramp(p, from, to) {
    if (p <= from) return 0;
    if (p >= to) return 1;
    return (p - from) / (to - from);
  }

  /* The triangle is the count: its three legs draw from nothing to closed
   * across 0 to 99, a third each, with a relay lighting as each one lands. The
   * rest of it — the flood, the shells, the phone's eye — belongs to 100, when
   * the connection is actually there. */
  var SEG = [[0, 33, 233], [33, 66, 288], [66, 99, 233]];  // start%, done%, length
  var NODE = [[0, 6], [33, 39], [66, 72]];

  function paintCircuit(pct) {
    if (!nodes || !nodes.circuit) return;
    var c = nodes.circuit, p = Math.max(0, Math.min(100, pct));
    for (var i = 0; i < 3; i++) {
      c.seg[i].style.strokeDashoffset = (SEG[i][2] * (1 - ramp(p, SEG[i][0], SEG[i][1]))).toFixed(1);
      var lit = ramp(p, NODE[i][0], NODE[i][1]);
      // a node pops past its size as it lands, then settles
      var pop = lit < 1 ? 0.3 + lit * 1.05 : 1;
      c.node[i].style.opacity = lit.toFixed(3);
      c.node[i].style.transform = 'scale(' + pop.toFixed(3) + ')';
    }
    /* At 100 and not before. These carry their own transitions (see CSS), so
     * the flood rises and the shells close over the second the screen holds at
     * 100 rather than snapping into place. */
    var done = p >= 100 ? 1 : 0;
    c.fill.style.clipPath = 'inset(' + ((1 - done) * 100).toFixed(1) + '% 0 0 0)';
    for (var k = 0; k < c.shell.length; k++) {
      c.shell[k].style.opacity = String(done);
      c.shell[k].style.transform = 'translate(-50%,-50%) scale(' + (0.42 + done * 0.58).toFixed(3) + ')';
      c.shell[k].style.transitionDelay = (done ? k * 0.09 : 0) + 's';
    }
    c.ring.style.opacity = String(done);
    c.eyeOpen.style.opacity = String(1 - done);
    c.eyeShut.style.opacity = String(done);
  }

  function shell(w, fill, edge, glow) {
    return '<div class="ftg-shell" style="position:absolute;left:50%;top:59.5%;width:' + w + '%;aspect-ratio:1;' +
      'border-radius:50%;background:rgba(' + FROST + ',' + fill + ');' +
      'border:1.5px solid rgba(' + FROST + ',' + edge + ');' +
      'box-shadow:inset 0 0 ' + glow + 'px rgba(' + FROST + ',' + (edge / 2).toFixed(2) + ');' +
      'opacity:0;transform:translate(-50%,-50%) scale(.42)"></div>';
  }

  /* Snow, in the depth the design asks for — far flakes small, slow and dim —
   * but counted against the screen rather than fixed at 120. This runs while
   * Tor bootstraps on whatever phone the person has, and frames spent here are
   * frames not spent there. */
  function snow(into) {
    var wide = Math.max(320, window.innerWidth || 390);
    var count = Math.min(70, Math.round(wide / 7));
    var fallTo = Math.max(900, (window.innerHeight || 800) + 80);
    var layer = el('position:absolute;inset:0;overflow:hidden;pointer-events:none;z-index:1');
    layer.className = 'ftg-snow';
    var frag = document.createDocumentFragment();
    for (var i = 0; i < count; i++) {
      var d = Math.pow(Math.random(), 1.7);           // 0 far … 1 near
      var size = 1.6 + d * 5.4;
      var fall = (22 - d * 12 + Math.random() * 6).toFixed(2) + 's';
      var delay = (-Math.random() * 26).toFixed(2) + 's';
      var outer = document.createElement('div');
      outer.className = 'ftg-flake';
      outer.style.cssText = 'left:' + (Math.random() * 102 - 1).toFixed(2) + '%;' +
        'width:' + size.toFixed(1) + 'px;height:' + size.toFixed(1) + 'px;' +
        '--drift:' + ((Math.random() * 2 - 1) * (20 + d * 70)).toFixed(0) + 'px;' +
        '--fallTo:' + fallTo + 'px;animation-duration:' + fall + ';animation-delay:' + delay + ';';
      var mid = document.createElement('div');
      mid.style.cssText = '--sway:' + (4 + d * 14).toFixed(0) + 'px;' +
        'animation-duration:' + (3.4 + Math.random() * 4.6).toFixed(2) + 's;animation-delay:' + delay + ';';
      var dot = document.createElement('div');
      dot.style.cssText = '--op:' + (0.3 + d * 0.65).toFixed(2) + ';' +
        'box-shadow:0 0 ' + (2 + d * 6).toFixed(0) + 'px rgba(' + FROST + ',.5);' +
        'animation-duration:' + fall + ';animation-delay:' + delay + ';';
      mid.appendChild(dot); outer.appendChild(mid); frag.appendChild(outer);
    }
    layer.appendChild(frag);
    into.appendChild(layer);
  }

  function build() {
    if (root) return;

    var sheet = document.createElement('style');
    sheet.textContent = CSS;
    document.head.appendChild(sheet);

    root = document.createElement('div');
    root.id = 'ftg-root';
    root.style.cssText =
      'position:absolute;inset:0;z-index:2147483602;display:none;' +
      'background:#000;overflow:hidden;' +
      'font-family:Sora,system-ui,-apple-system,sans-serif';

    // a glow where the circuit sits, and the snow in front of it
    root.appendChild(el('position:absolute;left:0;right:0;top:0;height:44%;pointer-events:none;' +
      'background:radial-gradient(90% 70% at 50% 34%,rgba(' + FROST + ',.09) 0%,rgba(' + FROST + ',0) 70%)'));
    snow(root);
    if (warningDue()) showWarning();

    var box = el('position:absolute;inset:0;z-index:2;display:flex;flex-direction:column;' +
      'align-items:center;justify-content:center;gap:26px;padding:0 32px;text-align:center');

    var ring = circuit();
    box.appendChild(ring);

    var title = el('font-size:30px;font-weight:800;line-height:1.14;letter-spacing:.005em;color:#fff');
    var body = el('font-size:17px;line-height:1.45;color:#fff;max-width:300px');
    box.appendChild(title);
    box.appendChild(body);

    // the real figure, and a bar that is the same figure
    var progress = el('width:100%;max-width:265px;display:flex;flex-direction:column;' +
      'align-items:center;gap:12px;flex:none');
    var pct = el('font-size:19px;font-weight:600;letter-spacing:.14em;' +
      'font-variant-numeric:tabular-nums;color:rgba(255,255,255,.72)');
    var track = el('width:100%;height:3px;border-radius:2px;background:rgba(255,255,255,.14);overflow:hidden');
    /* No transition on the width. The count steps every paceMs/99 — about
     * every 20ms — and a 450ms transition restarted on each step covers only a
     * twentieth of its distance before the next one interrupts it, so the bar
     * crawled far behind the number: 26% of the track under a figure reading
     * 68%. The number is the animation; the bar is it. */
    var bar = el('height:100%;width:0%;border-radius:2px;background:#fff');
    track.appendChild(bar);
    progress.appendChild(pct);
    progress.appendChild(track);
    box.appendChild(progress);

    var hint = el('font-size:14px;line-height:1.45;color:rgba(255,255,255,.62);max-width:300px');
    box.appendChild(hint);

    var below = el('display:none;flex-direction:column;align-items:center;gap:12px;margin-top:10px');

    var retry = el('padding:15px 38px;border-radius:26px;background:#F2A342;color:#2A1405;' +
      'font-size:22px;font-weight:800;letter-spacing:.02em;cursor:pointer');
    retry.addEventListener('click', function () {
      /* On the VPN screen the action button is Orbot's allow, because that is
       * the one thing that makes Foxy's own Tor work without turning Orbot
       * off. For any other tunnel it is an ordinary retry. */
      var pv = state();
      if (screen === 'vpn' && pv && pv.orbot && pv.orbot !== 'none') FoxyGate.allowOrbot();
      /* Orbot issued a key and has gone quiet. Retrying Tor alone is the one
       * thing that cannot help — Tor is not what is wrong — and Orbot's state
       * here is up to fifteen seconds old, since that is the poll. Ask Orbot
       * again first, then retry Tor with whatever it now says. */
      else if (screen === 'vpn' && pv && pv.orbotKey) FoxyGate.recheckOrbot();
      else FoxyGate.retry();
    });
    below.appendChild(retry);

    /* Its own colour, for the reason spelled out on the offline button below: the
     * gate paints black and sets no colour, so anything that does not set one
     * inherits the page's dark ink and disappears. This one has been that way
     * since it was written; nobody noticed because the screens that offer it are
     * rare and the tests check the DOM rather than the pixels. */
    var go = el('padding:10px 16px;font-size:14px;font-weight:700;cursor:pointer;' +
      'letter-spacing:.02em;text-align:center;color:rgba(255,255,255,.72)');
    armUnprotected(go);
    below.appendChild(go);

    /* Its own button, never `go`.
     *
     * `go` is CONTINUE UNPROTECTED, which puts this person's traffic on the open
     * internet. This one sends nothing at all. Two choices that differ that much
     * must not share a control, a label or a place in the column — and the
     * connecting screen is pinned never to offer unprotected
     * (tests/gate-scenarios.js), which this must not quietly undo. */
    /* Its colour is its own, and that is not a detail.
     *
     * `#ftg-root` paints a black background and sets no colour, so a child that
     * does not set one inherits from the page body — which in Foxy is dark ink for
     * a dark theme. The title and the body each set `color:#fff` for exactly this
     * reason. This button did not, and on a phone it rendered as black text
     * on black: present in the DOM, asserted as shown by the scenarios, and
     * invisible to the person who needed it.
     *
     * Underlined as well, because a small line of text with no box around it has
     * to say it is a control. */
    var offline = el('padding:12px 18px;font-size:15px;font-weight:700;cursor:pointer;' +
      'letter-spacing:.02em;text-align:center;display:none;color:#fff;' +
      'text-decoration:underline;text-underline-offset:4px');
    offline.textContent = 'PROCEED OFFLINE';
    offline.addEventListener('click', function () { FoxyGate.proceedOffline(); });
    below.appendChild(offline);

    box.appendChild(below);
    root.appendChild(box);


    document.body.appendChild(root);

    nodes = { title: title, body: body, hint: hint, pct: pct, bar: bar,
              progress: progress, below: below, retry: retry, go: go, offline: offline,
              circuit: {
                seg: ring.querySelectorAll('.ftg-seg'),
                node: ring.querySelectorAll('.ftg-node'),
                shell: ring.querySelectorAll('.ftg-shell'),
                fill: ring.querySelector('.ftg-fill'),
                ring: ring.querySelector('.ftg-ring'),
                eyeOpen: ring.querySelector('.ftg-eye-open'),
                eyeShut: ring.querySelector('.ftg-eye-shut'),
                done: ring.querySelector('.ftg-done'),
              } };
    paintCircuit(0);
  }

  /* The circuit runs while something is being waited on, and is held still on
   * a failure nobody is retrying — the same rule the old spinner had, applied
   * to every animation in the screen at once (CSS, one class). */
  function spin(on) {
    if (!root) return;
    if (on) root.classList.remove('ftg-still');
    else root.classList.add('ftg-still');
  }

  /* The figure and the bar, from the same number — Tor's own bootstrap, scaled
   * from the paced count, which is the only number this screen has. Showing
   * nothing hides the pair rather than drawing a bar at zero. */
  /* Arriving at 89% and vanishing a second later told the person nothing: the
   * screen was gone before they could read it. So the
   * number is swept from nothing up to wherever the launch really is, over
   * SWEEP_MS, and the circuit draws itself along with it. What is swept is only
   * the telling of it — the real figure is what it arrives at, and the sweep
   * never runs past it. */
  var counting = null, paced = 0;
  // when the count reached 99 and stopped, and the redraw that follows it
  var parkedAt = 0, parkedT = null;
  function parkedLong() {
    return !!parkedAt && paced >= 99 && Date.now() - parkedAt >= FoxyGate.parkedMs;
  }
  // the count was stopped for want of a network, and starts over when one returns
  var countLost = false;

  /* Everything the launch was waiting for was through before the fox had
   * finished waking up.
   *
   * finish() used to run there and then: it sealed and faded the screen while
   * the intro was still over it, so by the time the film ended the gate was
   * already gone and home arrived straight after the fox. On a first install,
   * with Tor now reaching a circuit in four and a half seconds against a ten
   * second film, that is every time — the one launch where somebody has never
   * seen Foxy make a private connection is the one launch that never shows it.
   *
   * So a finish that lands under the intro is remembered instead of spent.
   * introEnd starts the count, it runs all the way to 100 at the same pace as
   * any other launch, and the seal follows it. */
  var torDone = false;

  /* The number on the launch screen is paced, not measured: every arrival counts from nothing to 90 over paceMs, and then
   * a point a second as far as 99 while the launch works. Only when everything
   * is genuinely through — Tor up, the mint answered, the balance loaded —
   * does it finish at 100.
   *
   * So the pace is uniform and the ending is honest: 100 is never shown before
   * it is true, and 99 is where a launch that is still working sits. What it no
   * longer does is dramatise Tor's own bootstrap, which arrived in bursts —
   * 5, 10, 14, 15, 75, 90, 100 in about three seconds — and then left the
   * screen parked while the mint took another ten.
   */
  function startCount() {
    stopCount();
    paced = 0;
    /* Not while the fox is over it. The launch screen is built and shown
     * behind the intro, and the count used to run there — so by the time the
     * film ended and the screen was uncovered it was already at 98. introEnd starts it when there is someone to watch it. */
    if (intro) return;
    parkedAt = 0;
    clearTimeout(parkedT);
    if (!FoxyGate.paceMs) { paced = 90; return; }
    // the quick part: nothing to 90 over paceMs
    /* A first set-up has the relay list to fetch, which is half a minute on
     * cellular and not two seconds. At the ordinary pace the number was at 99
     * almost at once and stood there for the rest of it.
     * Ten times slower, so it is still climbing while the work is being done. */
    var first = state();
    var over = (first && first.firstSetup && FoxyGate.paceMs) ? FoxyGate.paceMs * FoxyGate.firstSlower : FoxyGate.paceMs;
    var quick = Math.max(8, over / 90);
    counting = setInterval(function () {
      if (!nodes || screen !== 'connecting') { stopCount(); return; }
      /* 90 while the launch is still working — the last ten points are the
       * slow part, and 100 is only ever true. With everything already through
       * it runs the whole way here and then seals, which is the launch that
       * was going by unseen. */
      /* torDone was one launch's "Tor was through before the fox had finished
       * waking up", not a standing fact — and it was never cleared. Left set,
       * every later gate counted to 100 and finished itself whether or not Tor
       * was up, which is the wake flicker. So it is spent the
       * moment it is read, and it only reaches 100 if Tor really is through. */
      var ending = torDone && through(state());
      if (torDone && !ending) torDone = false;
      /* A tunnel is slower, so the quick part stops lower and leaves more of
       * the bar for the part that creeps. Orbot takes minutes where a direct
       * connect takes seconds; running to 90 and then sitting reads as stuck,
       * where 80 with points still arriving reads as working.
       * The slow tail is a point a second either way. */
      var pv = state();
      var top = ending ? 100 : ((pv && pv.vpn) ? 80 : 90);
      if (paced >= top) {
        if (ending) { torDone = false; stopCount(); finish(); return; }
        waitOut();
        return;
      }
      paced += 1;
      render(state());
    }, quick);
  }

  /* And then the slow part: a point a second, as far as 99, for as long as the
   * launch takes. A number that stops dead reads as a
   * wedge; one that keeps ticking reads as work. It still cannot reach 100 —
   * only finish() writes that, and only once everything is through. */
  function waitOut() {
    if (counting) { clearInterval(counting); counting = null; }
    counting = setInterval(function () {
      if (!nodes || screen !== 'connecting') { stopCount(); return; }
      if (paced >= 99) { stopCount(); return; }
      paced += 1;
      if (paced >= 99 && !parkedAt) {
        /* Standing at 99. A launch that stays here is one somebody should be
         * able to do something about, so the screen is drawn again once it has
         * been long enough to offer it (`parkedLong`). */
        parkedAt = Date.now();
        clearTimeout(parkedT);
        parkedT = setTimeout(function () { if (screen === 'connecting') render(state()); }, FoxyGate.parkedMs + 50);
      }
      render(state());
    }, FoxyGate.waitMs);
  }

  function stopCount() {
    if (counting) { clearInterval(counting); counting = null; }
  }

  function showPercent(show) {
    if (!nodes) return;
    nodes.pct.textContent = show ? paced + '%' : '';
    nodes.bar.style.width = (show ? paced : 0) + '%';
    nodes.progress.style.display = show ? 'flex' : 'none';
    paintCircuit(show ? paced : 0);     // the drawing is the same number
  }

  function percent(p) {
    return (p && p.progress > 0 && p.progress < 100) ? ' ' + p.progress + '%' : '';
  }

  function restartOffered(p) {
    return screen === 'connecting' && !launchStep && !!p && p.tor === 'stuck';
  }

  /* PROCEED OFFLINE is offered from the first frame, on the launch screen and the
   * connecting screen alike.
   *
   * It was gated on Tor saying `stuck`, which is the native side's verdict after
   * thirty seconds of nothing moving — and suppressed entirely while the launch
   * animation was up. In airplane mode that meant sitting through the fox, then
   * the percentage climbing to 99, and only then a button; and the button that
   * appeared first was RESTART TOR, which is the one thing that cannot help when
   * there is no network at all.
   *
   * Somebody who knows they have no signal should not have to prove it to the app
   * for half a minute. The cost of showing it early is that it is there on every
   * launch, which is a fair trade for the one case where it is the only way in —
   * and it stays the quiet text button beneath, never the loud one. */
  /* PROCEED OFFLINE is offered only once there is something to work offline
   * WITH: this phone's mint keysets on file, and a bitcoin price fetched at
   * least once. Without them the choice is a door into an empty room — no
   * wallet, no balance, no mint to select, every dollar figure blank — which is
   * what a phone that had never connected walked into.
   *
   * Fail closed. No wallet yet, or a build without `offlineReady`, offers
   * nothing: a person who cannot work offline is better off waiting for Tor
   * than being shown a way in that leads nowhere. */
  function offlineOffered() {
    if (screen !== 'connecting') return false;
    var w = W();
    if (!w || typeof w.offlineReady !== 'function') return false;
    try { return !!w.offlineReady(); } catch (e) { return false; }
  }

  /* No way onto a network at all: wifi off, cellular off, nothing plugged in.
   *
   * Different from Tor being slow, and it deserves a different screen. Foxy used
   * to spend half a minute finding this out — the fox ran, the number climbed to
   * 99, and the first button offered was RESTART TOR, which cannot help when
   * there is nothing to restart through. The system knows the moment it is asked.
   *
   * 'unknown' is not 'none': before the first answer from the path monitor the
   * screen says nothing rather than accusing the phone of being offline. */
  function noNetwork(p) {
    /* `!p.unprotected` used to be here too. Continuing unprotected with no
     * interface sends exactly as much as Tor does — nothing — so the honest
     * screen is still the one that says there is no network. */
    /* `tor !== 'up'` used to be part of this, and it was the stale half.
     *
     * Tor's `up` is Foxy's view of a circuit it built, and it survives the
     * interface going away for as long as its deadline takes to notice — up to
     * forty-five seconds of a wallet that looks like it works. The path monitor
     * is the system's answer to the same question and it is current. Where the
     * two disagree about whether this phone can reach anything, the monitor is
     * right, so the flicker risk is handled by waiting a moment (`netGone`)
     * rather than by believing the older answer. */
    return !!p && p.network === 'none';
  }

  /* CANNOT CONNECT, when there is nothing to connect through, is the wrong screen.
   *
   * It offers CONTINUE UNPROTECTED, and continuing unprotected with no interface
   * sends exactly as much as Tor does: nothing. Somebody tapped it, then tapped
   * PROCEED OFFLINE a second later, and carried both choices — so the home screen
   * said IP ADDRESS EXPOSED with the radio off. The honest screen for
   * a phone with no network is the one that says so, whatever Tor has decided. */
  function noNetworkInstead(p) {
    return (screen === 'connecting' || screen === 'failed') && noNetwork(p);
  }

  /* The connecting screen's number: Tor's, or the creep, whichever is higher.
   * At launch Tor's own number fills the first 60% and the mint and the
   * balance the rest, so one number runs from launch to the home screen. */
  function render(p) {
    /* CONTINUE UNPROTECTED is this button everywhere except the VPN screen,
     * which renames it. Reset it here so the name cannot leak from one screen
     * to the next — only `vpn` sets it, and nothing else used to set it back. */
    if (nodes && nodes.go && screen !== 'vpn') nodes.go.textContent = UNPROTECTED;
    if (!screen) return;
    build();
    nodes.retry.style.display = '';
    nodes.below.style.visibility = '';   // `finish` hides it without moving anything
    /* Hidden here for every screen, and shown again only by the connecting
     * branch. The same reason the line above exists: a node set in one branch
     * and nowhere else leaks into the next screen. */
    nodes.offline.style.display = 'none';
    /* Decided once, here, and read by every branch below. It used to be worked
     * out inside the connecting branch alone, so NO CONNECTION — the screen most
     * likely to be looking at a phone that has never connected — offered the
     * choice unconditionally. */
    var offOffer = offlineOffered();
    /* The count belongs to a launch that can get somewhere. It went on running
     * under NO CONNECTION, so a first launch in airplane mode came back to
     * SECURING YOUR CONNECTION already at 99% and sat there for the whole of
     * Tor's first set-up, seventy-eight seconds. Stopped while there is no network, and started from nothing
     * when there is one again. */
    if (noNetworkInstead(p)) {
      if (!countLost) { countLost = true; stopCount(); paced = 0; }
    } else if (countLost && screen === 'connecting') {
      countLost = false;
      startCount();
    }
    if (screen === 'connecting' && launchStep && !noNetworkInstead(p)) {
      /* One title from the first frame to the last, whatever step this is and
       * whatever the number says — and no line naming the
       * mint, which was the one fact this screen knew that nobody looking over
       * a shoulder should be told. */
      nodes.title.textContent = SECURING;
      nodes.body.textContent = '';
      showPercent(true);
      nodes.hint.textContent = '';
      /* The column is shown for the offline choice alone: no hint above it, and
       * neither of the loud buttons, which have nothing to offer during a launch
       * that is still going normally. */
      nodes.below.style.display = 'flex';
      nodes.retry.style.display = 'none';
      /* And a way to try again, once the number has stood at 99 for a while or
       * Tor has said it is stuck. A launch had neither button nor word: the
       * person waited at 99 with nothing to press. */
      /* Only while Tor is what is being waited for. Once it has been up, the
       * wait is the mint's, and restarting Tor cannot hurry a mint. */
      if (p && p.tor !== 'up' && !p.everUp && !chosenOffline()
          && (parkedLong() || p.tor === 'stuck')) {
        nodes.hint.textContent = 'This is taking longer than it should.';
        nodes.retry.style.display = '';
        nodes.retry.textContent = 'RESTART TOR';
        nodes.retry.style.opacity = '1';
      }
      nodes.go.style.display = 'none';
      nodes.offline.style.display = offOffer ? '' : 'none';
      spin(true);
    } else if (noNetworkInstead(p)) {
      /* Say what is wrong and what to do about it, and stop the spinner: a
       * percentage creeping up while there is no interface is the app telling
       * somebody it is working on it when it cannot be. */
      nodes.title.textContent = 'NO CONNECTION';
      /* What to do about it, and only what this phone can actually do. Working
       * offline needs the mint's keysets and a price, both of which come from a
       * connection that worked; a phone that has never had one is told to get a
       * network, full stop, rather than offered a door into an empty room. */
      nodes.body.textContent = offOffer
        ? 'Connect to Wi-Fi or cellular data, or work offline.'
        : 'Connect to Wi-Fi or cellular data.';
      showPercent(false);
      nodes.hint.textContent = '';
      nodes.below.style.display = 'flex';
      nodes.retry.style.display = '';
      nodes.retry.textContent = 'TRY AGAIN';
      nodes.retry.style.opacity = '1';
      nodes.go.style.display = 'none';
      nodes.offline.style.display = offOffer ? '' : 'none';
      spin(false);
    } else if (screen === 'connecting') {
      /* The title, the number and the circuit, and nothing else. The lines that used to sit here — setting up a private
       * connection, trying a bridge, this initial setup may take 30 seconds —
       * were read once and then stared at. What a person can act on still
       * appears: the hint under the number, with RESTART TOR beneath it. */
      nodes.title.textContent = SECURING;
      nodes.body.textContent = '';
      var offer = restartOffered(p);
      showPercent(true);
      /* The hint belongs to RESTART TOR, not to the offline choice: "taking longer
       * than it should" is only true once Tor says so, and saying it from the
       * first second would be the screen worrying out loud on every launch. */
      nodes.hint.textContent = offer ? 'This is taking longer than it should.' : '';
      nodes.below.style.display = (offer || offOffer) ? 'flex' : 'none';
      /* RESTART TOR only while Tor is actually stuck; PROCEED OFFLINE for the
       * rest of the visit once it has been. So the pair can appear together, and
       * the retry can go away on its own while the offline choice stays. */
      nodes.retry.style.display = offer ? '' : 'none';
      if (offer) {
        nodes.retry.textContent = 'RESTART TOR';
        nodes.retry.style.opacity = '1';
      }
      nodes.go.style.display = 'none';
      nodes.offline.style.display = offOffer ? '' : 'none';
      spin(true);
    } else if (screen === 'vpn') {
      /* A tunnel is up and Foxy's Tor could not connect through it.
       *
       * NOT shown merely because a VPN is on: an ordinary VPN carries Tor
       * perfectly well, and somebody on Mullvad whose Foxy works must never be
       * stopped to be told about a problem they do not have. It waits for the failure.
       *
       * Orbot gets its own words and its own single button, because it is the
       * one tunnel with a way out that keeps everything: it can let Foxy's own
       * Tor past it, and then Foxy keeps its own circuits, its per-job
       * isolation and its onion inbox, with Orbot still on. */
      /* Orbot counts if its API says so OR if Foxy holds a key it issued.
       *
       * The poll is the live answer, and it goes quiet at exactly the wrong
       * moment — coming back from Orbot. On a phone the key was
       * stored and the state fell to 'none' in the same second, so this turned
       * into the generic VPN screen whose only way on is exposing an IP.
       * A key is proof enough that Orbot is the tunnel here —
       * but only while there is a tunnel at all.
       *
       * The key lives in the keychain for good. Without the `vpn` half of this
       * it went on standing in for Orbot after Orbot was closed, so a screen
       * raised for some other reason came up saying ORBOT DETECTED about an
       * Orbot that was not running, and its one button retried a Tor that had
       * nothing wrong with it. The person was left with no way off it but the
       * app switcher. No tunnel means nothing of Orbot's is
       * carrying anything, whatever Foxy still holds from last time. */
      var isOrbot = !!(p && ((p.orbot && p.orbot !== 'none') || (p.orbotKey && p.vpn)));
      var allowed = p && p.orbot === 'bypass';
      showPercent(false);
      spin(!!allowed);
      nodes.below.style.display = allowed ? 'none' : 'flex';
      nodes.retry.style.opacity = '1';

      if (isOrbot) {
        nodes.title.textContent = 'ORBOT DETECTED';
        /* Already approved once, and Orbot has gone quiet since. Saying "let
         * Foxy past" again would be wrong — they did — so say what is actually
         * true and point at the thing they can check. */
        var quiet = !!(p && p.orbotKey && p.orbot !== 'needs-access' && !allowed);
        nodes.body.textContent = allowed
          ? 'Allowed. Connecting past Orbot.' + percent(p)
          : quiet
            ? 'Orbot is on but is not answering Foxy, so Foxy\u2019s Tor cannot '
              + 'get out through it. Turn Orbot off and Foxy connects on its own.'
            : 'Foxy runs its own Tor, and it cannot run inside Orbot\u2019s. '
              + 'Let Foxy past, and Orbot stays on.';
        /* Turning Orbot off is the advice because it is the one thing that
         * works. Asking somebody to "check Orbot is still connected" was the
         * opposite of the truth: Orbot WAS connected, and its tunnel with no
         * bypass is exactly what Foxy's Tor cannot get through — it sat at 10%
         * for thirty-five seconds, was restarted, and sat again. The retry stays
         * for the case where Orbot starts answering, but it is no longer the only
         * thing offered. */
        nodes.hint.textContent = allowed ? ''
          : quiet ? 'Or wait for Orbot to answer and try again.'
          : 'Orbot will ask you to approve Foxy. Approve it, then come back here.';
        nodes.retry.textContent = quiet ? 'TRY AGAIN' : 'ALLOW FOXY IN ORBOT';
        /* One button. Proceeding on Orbot's own Tor is deliberately not
         * offered here: it would mean Foxy stops running its own Tor, and
         * `Route.unprotected` then lets requests out through the ordinary
         * session — fine while Orbot's tunnel holds, and in the clear the
         * moment it drops. Letting Foxy past fails closed instead; if Orbot
         * goes away the bypass port closes and requests are refused. Turning
         * Orbot off clears this screen on its own. */
        nodes.go.style.display = 'none';
      } else {
        nodes.title.textContent = 'VPN DETECTED';
        nodes.body.textContent = 'Turn off your VPN to use Foxy\u2019s Tor service. '
          + 'Or proceed with your VPN if you trust it.';
        nodes.hint.textContent = '';
        nodes.retry.textContent = 'RETRY TOR';
        nodes.go.textContent = 'PROCEED WITH VPN';
        nodes.go.style.display = 'block';
      }
    } else if (p && p.tor === 'stopped') {
      /* Tor runs once per app. Its thread has exited and nothing in this
       * process can start it again, so no button pretends to. */
      showPercent(false);
      nodes.title.textContent = 'TOR STOPPED';
      nodes.body.textContent = 'Close Foxy from the app switcher and open it again.';
      nodes.hint.textContent = 'Tor can only start again when Foxy does. Your wallet is kept as it is.';
      nodes.below.style.display = 'flex';
      nodes.retry.style.display = 'none';
      nodes.go.style.display = 'block';
      spin(false);
    } else {
      showPercent(false);
      nodes.title.textContent = 'CANNOT CONNECT TO TOR';
      nodes.body.textContent = retrying
        ? 'Trying again…' + percent(p)
        : 'Tor could not connect from this network, directly, through Snowflake or through a bridge.';
      // Orbot has its own screen; this one is about the network
      nodes.hint.textContent = 'Without Tor, the mint sees your IP address. ' +
        'Another network, or turning off a VPN, may let Tor through.';
      nodes.below.style.display = 'flex';
      nodes.retry.textContent = retrying ? 'CONNECTING…' : 'RETRY';
      nodes.go.style.display = 'block';
      nodes.retry.style.opacity = retrying ? '.7' : '1';
      spin(retrying);
    }
    root.style.display = 'block';
    sayCovered();
  }

  /* What the state was when a screen went up, for the log.
   *
   * A screen that should not have been there leaves nothing behind otherwise:
   * a person was stuck on ORBOT DETECTED with no way off it, and the only log
   * was the relaunch afterwards, which was healthy and said
   * nothing about why. These four fields decide every screen
   * here, so printing them with the screen name is enough to tell which
   * branch raised it. */
  function reason(p) {
    if (!p) return 'no state';
    return 'tor ' + p.tor + (p.everUp ? ' (was up)' : '')
      + ', tunnel ' + (p.vpn ? 'yes' : 'no')
      + ', orbot ' + (p.orbot || 'none') + (p.orbotKey ? ' (key held)' : '')
      + (p.unprotected ? ', unprotected' : '');
  }

  /* While a screen that blocks the app is up, look at the state on a clock as
   * well as waiting to be told.
   *
   * Every take-down here hangs off `_changed`, which only runs when the native
   * side pushes. If the push that would clear a screen is missed, or never
   * comes because nothing native changed, the screen stays with no way off it
   * but the app switcher — which is what had to be done twice, on two
   * different builds, while Tor itself was healthy within seconds of the
   * relaunch. Asking costs a function call every
   * two seconds and only while somebody is stuck looking at one of these. */
  /* Ask the phone to stay lit, once per change. The app asks for its own
   * screens (26d-tap.js); this is for the ones that are up before the app is. */
  var awakeNow = false;
  function awake(on) {
    var w = W();
    if (!w || !w.keepAwake || on === awakeNow) return;
    awakeNow = on;
    w.keepAwake(on);
  }

  var watch = null;
  function watchState(on) {
    if (watch) { clearInterval(watch); watch = null; }
    if (!on) return;
    watch = setInterval(function () {
      /* Ask the native side, do not read the copy. `state()` is the wallet's
       * cached privacy, and that cache is only written when a push arrives —
       * so polling it would re-read the very value a missed push left stale.
       * `refreshPrivacy` fetches and applies, which runs `_changed`, and the
       * ordinary take-down there does the rest. */
      var w = W();
      if (w && w.refreshPrivacy) { w.refreshPrivacy().catch(function () {}); return; }
      var p = state();
      if (!through(p)) return;
      console.log('[foxy] tor gate: through while', screen, 'was up —', reason(p));
      takeDown(true, 'through, found by asking');
      if (typeof onRetry === 'function') onRetry();
    }, FoxyGate.watchMs);
  }

  function show(which) {
    if (sealing) { clearTimeout(sealing); sealing = null; }
    if (root) { root.style.transition = ''; root.style.opacity = '1'; }
    var was = screen;
    screen = which;
    if (which === 'connecting') {
      // a new visit waits for `stuck` on its own merits before offering anything
      if (was !== 'connecting') startCount();
    } else {
      stopCount();
    }
    render(state());
    /* The gate is watched and never touched — the count creeps, the fox runs —
     * so the phone dimmed and locked part way through connecting. Given back in takeDown. */
    awake(true);
    watchState(which === 'vpn' || which === 'failed');
    if (was !== which) console.log('[foxy] tor gate:', which, '—', reason(state()));
    if (was !== which) { try { console.log('[foxy] tor gate: raised by ' + String((new Error().stack || '').split('\n').slice(1, 4).join(' < ')).replace(/https?:\/\/[^ )]*\//g, '')); } catch (e) {} }
    /* Said, as going down is: the camera is a native view above the page and
     * has to be taken off a scan screen this covers (syncPreview). */
    if (!was) { try { window.dispatchEvent(new Event('foxy-gate-up')); } catch (e) {} }
  }

  /* Take the screen down. CONNECTING is left up while the app holds it across
   * its wallet search, unless forced; a failure is never held. */
  function takeDown(force, why) {
    if (hold && !force && screen === 'connecting') return;
    /* However this screen goes — through, a tap on PROCEED OFFLINE, the clock
     * — the try from offline is over and its clock is spent. */
    offRaised = false;
    if (offGiveUp) { clearTimeout(offGiveUp); offGiveUp = null; }
    /* Logged, because until it was, the gate's own log could not explain a
     * flicker: `show` only prints on a change, so a screen that came up,
     * went, and came back printed the same word twice with nothing between
     * (connecting, home for a second, connecting,
     * home). What took it down, and what Foxy thought of Tor at that moment,
     * is the half that was missing. */
    if (screen) {
      var p = state();
      console.log('[foxy] tor gate: down from', screen, '—', why || 'asked to',
                  '| tor', (p && p.tor) || 'not said',
                  (p && p.unprotected) ? '| unprotected' : '');
    }
    screen = null;
    retrying = false;
    netRaised = false;
    tapRaised = false;
    awake(false);
    watchState(false);
    stopCount();
    spin(false);
    if (root) root.style.display = 'none';
    /* Said, for whatever was waiting for this screen to go: the tap's listen
     * is what asks for Bluetooth, and it waits for the launch to be over. */
    try { window.dispatchEvent(new Event('foxy-gate-down')); } catch (e) {}
  }

  /* Everything is through, and the screen says so before it goes:
   *
   *   the number finishes at 100 and the circuit closes — the triangle full,
   *   the shells around the phone, its eye shut — and that is held for a
   *   second, so it is seen rather than glimpsed;
   *   then the phone's screen turns green with a tick, held for another;
   *   then home.
   *
   * Before this it simply vanished at whatever the creep had reached, usually
   * about 85%, which read as giving up rather than finishing. */
  var SEAL_MS = 500;                   // 100%, eye shut
  var DONE_MS = 600;                   // the closed circuit, held
  var sealing = null;

  /* Every launch ends here, whether or not the connecting screen was ever
   * seen. Tor is sometimes up before the person has looked at the phone, and
   * the whole thing went by without a word — a new person would have no idea
   * any of it had happened. So: if the screen was up, it
   * finishes at 100 and then says it; if it never showed, it says it anyway. */
  function finish() {
    if (sealing) return;
    /* Nothing to finish with no connection. Guarded here as well as at the
     * callers, because this is what actually draws it and a fourth caller added
     * later would otherwise put the animation back. */
    if (chosenOffline()) return;
    /* 100 is only ever true (startCount). An ending that arrives while Tor is
     * still connecting is not an ending, and playing it puts the person on the
     * home screen with no connection — then the next thing Tor says raises the
     * gate again, which is the flicker, as the log shows:
     *
     *   tor gate: connecting
     *   down from connecting — the ending played through | tor connecting
     *   tor gate: connecting
     *   down from connecting — the ending played through | tor up
     *
     * Nothing is taken down here; the gate stays up and the count goes on
     * ticking, which is what a launch that is still working looks like. */
    if (!through(state())) {
      torDone = false;
      console.log('[foxy] tor gate: not an ending yet — tor',
                  (state() || {}).tor || 'not said');
      return;
    }
    build();
    /* Not while the fox is over it: the screen would seal and fade behind him
     * and be gone before anyone saw it. Remembered, and run for real the
     * moment he goes (startCount, torDone). */
    if (intro) { torDone = true; return; }
    /* A launch that never showed anything has nothing to finish: it goes
     * straight to the home screen, where the banner says the connection is
     * secure whether or not this screen was ever seen. */
    if (screen !== 'connecting') { takeDown(true, 'finished, with no connecting screen to seal'); return; }
    stopCount();                        // the pace stops where it is
    paced = 100;                        // and finishes, which is the honest part
    showPercent(true);                  // which draws the circuit closed
    /* Out of sight, and still taking up its room. Taken out of the layout, the
     * buttons under the number let everything above them drop down the screen
     * at the very moment it reached 100. */
    if (nodes) { nodes.hint.textContent = ''; nodes.below.style.visibility = 'hidden'; }
    sealing = setTimeout(seal, SEAL_MS);
  }

  /* The finished circuit, held for a beat, and then the whole screen fades to
   * the home screen behind it. What it used to end on — a full iceberg-blue
   * screen saying so — is a banner at the foot of the home screen now: the same words, permanently, where they can be checked rather
   * than seen once and gone. */
  var FADE_MS = 350;

  function seal() {
    if (nodes && nodes.circuit.done) nodes.circuit.done.style.opacity = '1';
    sealing = setTimeout(function () {
      if (root) {
        root.style.transition = 'opacity ' + (FADE_MS / 1000) + 's ease-out';
        root.style.opacity = '0';
      }
      sealing = setTimeout(function () {
        sealing = null;
        if (nodes && nodes.circuit.done) nodes.circuit.done.style.opacity = '0';
        if (root) {
          takeDown(true, 'the ending played through to home');
          // back to its own self for whatever shows next
          root.style.transition = '';
          root.style.opacity = '1';
        }
      }, FADE_MS);
    }, DONE_MS);
  }

  /* Wait for Tor, or the person's choice, without showing anything. */
  function waitFor(ms) {
    return new Promise(function (ok) {
      var settled = false;
      var t = setTimeout(function () { if (!settled) { settled = true; ok(false); } }, ms);
      waiters.push(function (p) {
        if (settled) return true;
        if (!through(p)) return false;
        settled = true;
        clearTimeout(t);
        ok(true);
        return true;
      });
    });
  }

  var FoxyGate = {
    /* Launch: resolves true once Tor is up or the person chose to continue,
     * false once CANNOT CONNECT is on screen. When that screen later clears —
     * Tor connected, or CONTINUE UNPROTECTED — retry runs, which is the
     * app's boot, which calls this again and gets true. */
    check: function (retry) {
      if (retry) onRetry = retry;
      var w = W();
      if (!bridged() || !w || !w.refreshPrivacy) return Promise.resolve(true);
      /* Already decided, so there is nothing to check.
       *
       * `check` is the app's boot, and `proceedOffline` runs the app's boot —
       * so it comes straight back here, finds the launch still held, and raises
       * the screen again (`hold && !screen`). A phone could not get in
       * at all: tap, gate down, gate up, over and over about twice a second.
       * The three other ways in were guarded and this,
       * the one that matters most, was not.
       *
       * Answered true, because the person said carry on and that is exactly
       * what this promise means. The gate is taken down rather than left to
       * whatever put it up. */
      // home first: the wallet goes offline in place of this screen, and the answer below is the same
      if (!screen || screen === 'connecting') goHomeFirst('a launch or a return');
      /* And through already: nothing to wait for and nothing to show. Tor came
       * up while the person was at the Face ID prompt, working offline was
       * given up by itself, and the launch's hold then put this screen up over
       * a connected wallet and played its ending (seen on a phone). */
      if (homeFirst() && !tapRaised && !screen && through(state())) {
        if (!offRaised) takeDown(true, 'home first: through already');
        return Promise.resolve(true);
      }
      if (chosenOffline()) {
        /* Unless the screen up is the try this file is making itself.
         *
         * A network that appears while somebody works offline is tried at
         * once, on this screen, so that the wait has a face (`_changed`). A
         * phone whose wifi came on while Foxy was put away came back to that
         * screen and to the app's set-up, which asks here — and this took the
         * screen down with the try two seconds from getting through, under
         * the old reason. It comes down by its own rule: Tor up, or twenty
         * seconds. */
        if (!offRaised && !tapRaised) takeDown(true, 'working offline');
        return Promise.resolve(true);
      }

      return w.refreshPrivacy().catch(function () { return state(); }).then(function () {
        return new Promise(function (ok) {
          var done = false, reveal = null, vpnWait = null;

          function decide(p) {
            if (done) return true;
            if (through(p)) {
              done = true;
              clearTimeout(reveal);
              takeDown(false, 'the launch check found Tor through');
              ok(true);
              return true;
            }
            /* Orbot is on and has not let Foxy's Tor past it. Waiting will
             * not help, so say so now rather than after every fallback times
             * out. An ordinary VPN is NOT treated this way: it carries Tor
             * perfectly well, so it is left to connect and only reaches the
             * VPN screen below if it genuinely cannot. */
            if (p && p.orbot === 'needs-access') {
              done = true;
              clearTimeout(reveal);
              show('vpn');
              ok(false);
              return true;
            }
            /* Tor gave up and a tunnel is up: name the tunnel rather than
             * saying CANNOT CONNECT. It comes before the failure screen
             * because it is the more useful of the two — one of them tells
             * somebody what to do about it, and the other does not. */
            if (p && p.vpn && !through(p)
                && (p.tor === 'stopped' || (!p.everUp && (p.tor === 'failed' || p.tor === 'none')))) {
              done = true;
              clearTimeout(reveal);
              show('vpn');
              ok(false);
              return true;
            }
            // Never connected this session and Tor has given up: say so. A
            // build with no Tor at all ('none') lands here too.
            // A stopped Tor is past waiting whether or not it was ever up.
            if (p && (p.tor === 'stopped' || (!p.everUp && (p.tor === 'failed' || p.tor === 'none')))) {
              done = true;
              clearTimeout(reveal);
              show('failed');
              ok(false);
              return true;
            }
            /* Connecting, with a tunnel up, and getting nowhere.
             *
             * Tor inside Orbot does not fail — it sits. It stays 'connecting'
             * at 10%, tries the next transport, sits again, and never reaches
             * 'failed' or 'stopped', so a screen that waits for those never
             * comes: the count creeps to 99 and stops there with no way on.
             * A tunnel that Tor has not got
             * through in vpnPatienceMs is the case this screen exists for, so
             * it is reached on the clock rather than on a verdict Tor will
             * never give. Nothing is decided early — a connection that comes
             * good in the meantime clears it the usual way. */
            if (p && p.vpn && !p.everUp && !vpnWait) {
              vpnWait = setTimeout(function () {
                vpnWait = null;
                if (done || through(state())) return;
                done = true;
                clearTimeout(reveal);
                show('vpn');
                ok(false);
              }, FoxyGate.vpnPatienceMs);
            }

            // Still connecting. Silent for half a second, so a warm start
            // that is up at once leaves no trace.
            if (screen) render(p);
            else if (!reveal) reveal = setTimeout(function () {
              reveal = null;
              if (!done) show('connecting');
            }, 500);
            return false;
          }

          // At launch the screen is up even when Tor already is: the mint and
          // the balance come next, on the same screen.
          if (hold && !screen) show('connecting');
          // The wallet's copy, not the answer: a push that landed after the
          // answer left is newer, and deciding on the answer would wait on a
          // state that has already changed.
          if (!decide(state())) waiters.push(decide);
        });
      });
    },

    /* Before a request the person asked for: receive, split, and on resume.
     * Tor up, or unprotected: go. Tor up earlier and now reconnecting: wait a
     * moment, silently, and report false if it does not come back. Not up
     * since launch or the last return: that is the launch situation, so it
     * gets the launch screen. */
    quiet: function (retry) {
      if (retry) onRetry = retry;
      var p = state();
      /* Home first: a connection that is on its way is waited for, a few
       * seconds, and then the request goes on by whatever there is. No screen
       * comes up for it; the step that asked shows its own waiting. */
      if (homeFirst() && !tapRaised) {
        var w = W();
        var soon = (w && w.routeSoon) ? w.routeSoon() : Promise.resolve(false);
        return soon.then(function () { goHomeFirst('a request'); return true; });
      }
      if (through(p)) return Promise.resolve(true);
      if (p && p.everUp) return waitFor(15000);
      return FoxyGate.check(retry);
    },

    /* Is there a connection right now, with nothing here to wait for?
     *
     * Tor up, or the person's choice to go without it, over a network that is
     * there. Not `through`: somebody working offline is through and is not
     * connected. Asked on a return to the app (`setUpAfterReturn`), where a
     * yes means the connection Foxy left with is the one it came back to and
     * no screen is owed. */
    connected: function () {
      if (!bridged()) return true;
      var p = state();
      if (!p || p.network === 'none' || chosenOffline()) return false;
      return p.tor === 'up' || !!p.unprotected;
    },

    /* Whether this launch or return goes straight to the home screen
     * (`homeFirst`): the app boots from what is on file then, and does not
     * wait for the mint or a price before showing it. */
    isHomeFirst: function () { return homeFirst(); },

    /* Nothing of this file's is going up, and what the page is showing is
     * what should be seen: the phone's cover over a return can come off.
     *
     * The cover waits to hear that the page has the screen (`sayCovered`), and
     * a return that raised no connection screen never said so: the splash sat
     * over a connected wallet for the four seconds of the phone's own
     * backstop, which is the wait this was meant to remove. */
    uncover: function () { sayCovered(); },

    /* Before spending. The same question; kept as its own name because the
     * send path calls it. */
    recheck: function () {
      return FoxyGate.quiet();
    },

    /* How often the connecting screen's percentage creeps one point. */

    retry: function () {
      var w = W();
      if (retrying || !w || !w.torRetry) return;
      if (screen === 'connecting') {
        // RESTART TOR: the screen stays; the native side reports connecting again,
        // which takes the button away, and stuck again only if nothing moves
        console.log('[foxy] tor gate: restart tor');
        w.torRetry().catch(function (e) {
          console.error('[foxy] tor restart failed:', e && e.message);
        });
        return;
      }
      retrying = true;
      /* Let go of the latch on a clock as well as on a state change.
       *
       * `retrying` is cleared in `_changed` once Tor stops saying 'connecting'
       * — and under Orbot it says 'connecting' for ever, so the latch never
       * released and every tap after the first was swallowed in silence. A
       * phone recorded eight taps in two seconds against a single
       * `tor gate: retry`. A button that has stopped
       * working while still looking like a button is worse than no button. */
      if (letGo) clearTimeout(letGo);
      letGo = setTimeout(function () {
        letGo = null;
        if (!retrying) return;
        retrying = false;
        console.log('[foxy] tor gate: the retry is offered again');
        render(state());
      }, FoxyGate.retryLatchMs);
      render(state());
      console.log('[foxy] tor gate: retry');
      w.torRetry().catch(function (e) {
        console.error('[foxy] tor retry failed:', e && e.message);
        retrying = false;
        render(state());
      });
    },

    allowOrbot: function () {
      var w = W();
      if (!w || !w.orbotAccess) return;
      console.log('[foxy] tor gate: asking Orbot to let Foxy past');
      w.orbotAccess().catch(function (e) {
        console.error('[foxy] could not open Orbot:', e && e.message);
      });
    },

    /* TRY AGAIN on a quiet Orbot. Asks the native side to poll Orbot now
     * rather than waiting out its fifteen-second timer, then retries Tor with
     * whatever came back. Falls through to a plain retry on an older build
     * that has no such action, so the button is never inert. */
    recheckOrbot: function () {
      var w = W();
      console.log('[foxy] tor gate: asking Orbot again');
      if (!w || !w.orbotRefresh) { FoxyGate.retry(); return; }
      w.orbotRefresh().catch(function (e) {
        console.error('[foxy] could not re-ask Orbot:', e && e.message);
        return null;
      }).then(function (said) {
        /* What Orbot answered, not what the page holds: the push carrying the
         * new state can land after this resolves, so reading it here would
         * log the old one. */
        console.log('[foxy] tor gate: Orbot now', said || 'no answer');
        FoxyGate.retry();
      });
    },

    /* Carry on with no network at all.
     *
     * Nothing is asked of the native side: `Route.start` already refuses without
     * Tor and goes on refusing, so there is nothing to turn on and no alert to
     * draw. The wallet is told so the screens can say so, and then the gate comes
     * down the same way a successful connection takes it down — including
     * `onRetry`, without which the app sits with no wallet booted at all. */
    proceedOffline: function () {
      var w = W();
      if (!w || !w.setOffline) return;
      console.log('[foxy] tor gate: the person chose to work offline');
      w.setOffline(true);
      takeDown(true, 'the person chose to work offline');
      gateChanged();
      if (onRetry) { try { onRetry(); } catch (e) { console.error('[foxy] offline boot:', e && e.message); } }
    },

    /* Back to wanting a connection: the banner's TAP TO RETRY.
     *
     * The choice is surrendered and the launch screen comes back up, because a
     * tap that says "try again" should visibly try. Which face that screen wears
     * is not decided here and never should be: it is the same screen either way,
     * and `render` reads the phone. With no interface it says NO CONNECTION and
     * offers the way back to offline; with one it says SECURING YOUR CONNECTION
     * and counts.
     *
     * The phone is asked first. `network` comes from the path monitor and is the
     * whole basis of that choice, so acting on a copy from before the tap would
     * show NO CONNECTION to somebody who had just turned their wifi on. */
    leaveOffline: function () {
      var w = W();
      if (!w) return;
      /* Theirs, so home first leaves it alone; and it comes down by itself
       * once Tor is through (`_changed`). It used to stay up at whatever the
       * count had reached, with a connection behind it. */
      tapRaised = true;
      if (w.setOffline) w.setOffline(false);
      /* Up at once, on what is known now, so the tap is not silent while the
       * phone is asked. The answer re-renders it a moment later. */
      show('connecting');
      gateChanged();
      var settle = function () {
        var p = state();
        if (through(p)) { takeDown(false, 'a route came back'); return; }
        render(p);
      };
      var asked = w.refreshPrivacy ? w.refreshPrivacy() : Promise.resolve(null);
      asked.catch(function () { return null; }).then(function () {
        render(state());
        /* Nothing to restart through: Tor cannot be asked to try harder at a
         * phone with its radios off, and asking only spends half a minute
         * arriving at what the path monitor already said. */
        if (noNetwork(state())) return null;
        return w.torRetry ? w.torRetry().catch(function (e) {
          console.error('[foxy] tor retry from offline failed:', e && e.message);
        }) : null;
      }).then(settle, settle);
    },

    continueUnprotected: function () {
      var w = W();
      if (!w || !w.setUnprotected) return;
      console.warn('[foxy] continuing without Tor — the mint sees this device');
      w.setUnprotected(true).catch(function (e) {
        console.error('[foxy] could not continue unprotected:', e && e.message);
      });
    },

    /* Every change of state, from the wallet, which hears it from Swift. */
    _changed: function (p) {
      waiters = waiters.filter(function (fn) { return !fn(p); });
      if (retrying && p && p.tor !== 'connecting') {
        retrying = false;
        if (letGo) { clearTimeout(letGo); letGo = null; }
      }

      /* The interface went away, and the phone knows at once.
       *
       * Tor does not: it has a circuit it believes in, and it takes forty-five
       * seconds of a deadline to find out otherwise. That is forty-five seconds
       * of a home screen that looks like a working wallet on a phone that
       * cannot reach its mint. The path monitor said so immediately, so this
       * says so immediately.
       *
       * `connecting` is the right screen because that is the one that wears the
       * NO CONNECTION face (noNetworkInstead); it offers TRY AGAIN and, where
       * there is something to work offline with, the way in.
       *
       * Not over a person who already chose to work offline: they know, and
       * putting this over them is the loop that locked a person out. Not over
       * the screens that are asking for something either — Orbot and the
       * failure screen have their own instructions. */
      /* Working offline, and a network has appeared: try it, without being asked.
       *
       * "CONNECTION MAY BE AVAILABLE" put the work on the person — they had to
       * notice the banner and tap it. The phone knows the moment an interface
       * comes back, and trying costs nothing: Tor either gets through, in which
       * case the choice to work offline is surrendered by itself and the banner
       * turns, or it does not and nothing has changed.
       *
       * Once per return of the network, not once per push: `torRetry` is a
       * restart and a push arrives for every state Tor moves through. */
      if (p && chosenOffline() && p.network && p.network !== 'none'
          && p.network !== 'unknown' && !triedOnNetwork) {
        triedOnNetwork = true;
        console.log('[foxy] tor gate: working offline and a network appeared; trying it');
        /* And say so on the screen, rather than trying in silence.
         *
         * The try was already being made; nothing showed it. Foxy came back
         * from the background on a phone whose wifi had just been turned on,
         * put the home screen up with the banner, and rebuilt Tor behind it —
         * so a redeem tapped in that gap was refused with no way to tell how
         * long to wait, and the person killed the app to get past it.
         *
         * On a clock, because the person did not ask for this screen: twenty
         * seconds is long enough for Tor to come back over a network that
         * works, and if it does not they are put back exactly where they were.
         * The screen offers PROCEED OFFLINE throughout, so it is never a wall.
         */
        if (!screen && !homeFirst()) {
          offRaised = true;
          show('connecting');
          gateChanged();
          if (offGiveUp) clearTimeout(offGiveUp);
          offGiveUp = setTimeout(function () {
            offGiveUp = null;
            if (!offRaised || screen !== 'connecting') return;
            offRaised = false;
            console.log('[foxy] tor gate: the try from offline got nowhere; back to offline');
            takeDown(true, 'the try from offline got nowhere');
            gateChanged();
          }, 20000);
        }
        var w0 = W();
        if (w0 && w0.torRetry) {
          w0.torRetry().catch(function (e) {
            console.warn('[foxy] the try from offline failed:', e && e.message);
          });
        }
      }
      if (p && (!p.network || p.network === 'none')) triedOnNetwork = false;

      if (p && !chosenOffline()) {
        if (noNetwork(p)) {
          /* After a moment, not on the instant. iOS reports a brief unsatisfied
           * path while it hands a phone from wifi to cellular and back, and a
           * screen that flashes NO CONNECTION over a working wallet on every
           * handoff is worse than the wait it replaces. A second and a half is
           * far below noticing and far above a handoff. */
          /* Asked again for as long as another screen is in the way.
           *
           * This was armed only with nothing on screen, and gave up if
           * something was when it fired. The network is reported once, when it
           * changes — so a phone put in airplane mode while the launch screen
           * was playing its ending was never asked about again, and sat on a
           * home screen that looked online until it was sent to the background
           * and brought back, twenty-five seconds later. */
          if (!netGone) {
            var netLook = function () {
              netGone = null;
              if (chosenOffline() || !noNetwork(state())) return;
              if (screen) { netGone = setTimeout(netLook, 700); return; }
              console.log('[foxy] tor gate: the network went away');
              // home first: the banner says OFFLINE - NO CONNECTION, and nothing goes up
              if (goHomeFirst('the network went away')) { gateChanged(); return; }
              netRaised = true;
              show('connecting');
              gateChanged();
            };
            netGone = setTimeout(netLook, screen ? 700 : 1500);
          }
        } else if (netGone) {
          clearTimeout(netGone);
          netGone = null;
        }
      }

      /* Tor's thread has exited: nothing will connect, so say so now, over
       * whatever is showing, unless the person already chose to go without Tor.
       *
       * 'stuck' is deliberately not here. It means only that nothing moved for
       * ten seconds, and an ordinary cold start does that: on a phone
       * it was raised at 10s, withdrawn three seconds later, and
       * Tor went on to connect — but the screen had already jumped up telling
       * somebody with a working connection to turn their VPN off. A tunnel that
       * is merely slow is what vpnPatienceMs is for, and the clock in decide()
       * covers the Orbot case this trigger was added for, since Orbot's Tor
       * sits at 10% and never reaches any of these states either. */
      /* Home first, a connection lost or still being made is working offline,
       * with the banner saying which. Tor dropping its circuit in the middle
       * of a session put nothing on screen and left the app neither online
       * nor offline: every request refused, and no mode that says why. */
      if (p && !screen && !through(p)) goHomeFirst('the connection is not up');

      /* And the screens below, which ask the person for something, do not
       * come up over the home screen by themselves: the banner says CANNOT
       * CONNECT, and a tap on it brings them (`leaveOffline`). Once one of
       * this file's screens is up, they follow the state as before. */
      var mayRaise = !!screen || !homeFirst();

      if (mayRaise && p && p.vpn && !through(p) && screen !== 'vpn'
          && (p.tor === 'failed' || p.tor === 'stopped')) {
        show('vpn');
        gateChanged();
        return;
      }

      if (mayRaise && p && p.tor === 'stopped' && !through(p) && screen !== 'failed') {
        show('failed');
        gateChanged();
        return;
      }

      // Orbot switched on mid-session: a silent reconnect cannot work under it,
      // so the screen that can fix it comes up.
      if (mayRaise && p && p.orbot === 'needs-access' && !through(p) && screen !== 'vpn' && (screen || p.everUp)) {
        show('vpn');
      }

      /* Any other tunnel, once Tor has actually given up on it. A VPN alone is
       * never a reason to stop somebody: Tor rides most of them without
       * complaint, and a person whose Foxy works must not be shown a warning
       * about a problem they do not have. So this waits for the failure. */
      if (mayRaise && p && p.vpn && (p.tor === 'failed' || p.tor === 'stopped') && !through(p)
          && screen !== 'vpn') {
        show('vpn');
      }

      /* The tunnel this screen is about has gone. Whatever Tor is doing, the
       * reason for the screen went with it, so it goes too and the ordinary
       * connecting screen comes back.
       *
       * Without this, turning Orbot off while the screen was up did not clear
       * it: `isOrbot` correctly stopped being true, and the same screen simply
       * re-drew as the generic VPN one — telling somebody to turn off a VPN
       * they had just turned off. */
      if (screen === 'vpn' && p && !p.vpn && (!p.orbot || p.orbot === 'none') && !through(p)) {
        takeDown(true, 'the tunnel went away');
        show('connecting');
        gateChanged();
        return;
      }

      /* Raised because the network went, and the network is back: down again,
       * with no tap. The launch's own screens are not touched — `netRaised` is
       * only ever set by the branch at the top of this function. */
      if (netRaised && screen === 'connecting' && through(p) && !noNetwork(p)) {
        netRaised = false;
        takeDown(true, 'the network came back');
        gateChanged();
        return;
      }

      /* And the one the person raised by tapping the banner: through, so it
       * goes. A failure or a tunnel takes it to its own screen above, and
       * those come down below. */
      if (tapRaised && screen === 'connecting' && through(p) && !noNetwork(p) && !chosenOffline()) {
        takeDown(true, 'through, after the banner was tapped');
        gateChanged();
        return;
      }

      /* The same, for the screen the automatic try from offline raised: Tor is
       * up, so it goes, and the clock that would have taken it down is spent.
       *
       * Tor being *up*, not `through`: somebody working offline is already
       * through by definition — that is what the choice means — so asking
       * `through` here took this screen down in the same pass that raised it. */
      if (offRaised && screen === 'connecting' && p && p.tor === 'up') {
        offRaised = false;
        if (offGiveUp) { clearTimeout(offGiveUp); offGiveUp = null; }
        takeDown(true, 'the try from offline got through');
        gateChanged();
        return;
      }
      if ((screen === 'failed' || screen === 'vpn') && through(p)) {
        takeDown(true, 'through at last');
        console.log('[foxy] tor gate: through —',
          p.tor === 'up' ? 'Tor connected' : 'continuing unprotected');
        if (typeof onRetry === 'function') onRetry();
      } else if (screen) {
        render(p);
      }
      gateChanged();
    },

    /* The inline row, or null for nothing to say. Shown only while the person
     * is running without Tor; tapping it asks Tor to try again. */
    warning: function () {
      var p = state();
      if (!bridged() || !p || !p.unprotected || p.tor === 'up') return null;
      if (p.tor === 'stopped') return { text: 'IP ADDRESS EXPOSED · TOR STOPPED, REOPEN FOXY', colour: '#8C1D1D' };
      return p.tor === 'connecting'
        ? { text: 'IP ADDRESS EXPOSED · CONNECTING TO TOR…', colour: '#7A5A12' }
        : { text: 'IP ADDRESS EXPOSED · TAP TO RETRY TOR', colour: '#8C1D1D' };
    },

    rearm: function () {
      var w = W();
      if (w && w.torRetry) w.torRetry().catch(function () {});
      return 'retrying tor';
    },

    /* One line for the log: where traffic is going right now. */
    stamp: function () {
      var p = state();
      if (!bridged()) return 'no native side';
      if (!p) return 'no state';
      if (p.tor === 'up') return 'via tor';
      return (p.unprotected ? 'UNPROTECTED, ' : '') + 'tor ' + p.tor;
    },

    holdVerify: function (on) {
      hold = !!on;
      if (!hold) {
        launchStep = '';
      }
      /* Through, so the ending plays — whether or not the launch screen was
       * ever on. A screen that is asking for something (Orbot, a failure) is
       * not through, so it keeps asking. */
      if (chosenOffline()) return;
      if (!hold && through(state()) && (!screen || screen === 'connecting')) finish();
    },

    /* The launch screen, before Tor is asked: the seed is read first, and the
     * home screen behind it is not ready. */
    showLaunch: function () {
      if (chosenOffline()) return;
      if (goHomeFirst('a launch or a return')) return;
      if (bridged() && !screen) show('connecting');
    },

    /* Launch, past Tor: connecting to the mint, then loading the balance, on
     * the screen that said CONNECTING TO TOR. The home screen shows once the
     * balance has loaded (holdVerify(false)), not before. The number creeps
     * toward the end of each step and never reaches it. A failure or Orbot
     * screen is never covered. */
    /* The launch's steps still move the screen on — the title, and the fact
     * that a step is running — but they no longer carry a number of their own:
     * the paced count is the only number on the screen. */
    launchStage: function (label, from, to) {
      if (chosenOffline()) return;
      /* Home first, there is no launch screen to move along: this put it up
       * after the PIN unlock, over a wallet whose Tor had come up meanwhile,
       * and played its ending (seen on a phone, with the raiser logged). */
      if (homeFirst() && !tapRaised) return;
      if (!hold || !bridged()) return;
      if (screen && screen !== 'connecting') return;
      launchStep = label;
      if (!screen) show('connecting'); else render(state());
    },

    /* The launch says when the phone is about to ask for Face ID, and the fox
     * runs through the asking; then it says when that step is done, and the
     * fox leaves. Both are safe to call at any time — before the intro exists,
     * after it has gone, or on a day with no intro at all. */
    introStart: function () { if (introStart) introStart(); },
    introGo: function () { if (introGo) introGo(); },

    /* How long a tunnel gets before Foxy says so. Long enough that an
     * ordinary VPN carrying a slow-but-working Tor is never interrupted — a
     * direct connect is usually 4 to 13 seconds — and short enough that a
     * tunnel Tor cannot get through does not leave somebody watching a number
     * climb and stop. Raised from 25s: this screen was seen
     * for two seconds before Tor got through on its own, more than once, and a
     * screen that is right for two seconds is worse than no screen. */
    vpnPatienceMs: 30000,
    /* Home first (see `homeFirst`). Off, every launch and return waits behind
     * the connecting screen, as before: the suites that are about that screen
     * turn it off, and it is the one switch back. */
    homeFirst: true,

    /* How often a blocking screen looks at the state itself, rather than
     * waiting to be told. See watchState. */
    watchMs: 2000,

    /* How long a tap holds the retry closed before it is offered again. */
    retryLatchMs: 15000,

    // how long 0 to 90 takes, and then how long each point after it
    paceMs: 2000,
    waitMs: 1000,
    /* How much slower the quick part runs on a first set-up, and how long the
     * number stands at 99 before RESTART TOR is offered during a launch. */
    firstSlower: 10,
    parkedMs: 8000,

    visible: function () { return !!screen || !!intro || !!warning || !!bluetooth; },
    hide: function () { takeDown(true, 'the app asked'); },
  };

  /* ---- the warning: once, on the first launch after install ---------------
   *
   * After the fox and before SECURING YOUR CONNECTION: Foxy is risky, in plain
   * words, with one button. It sits over the connecting screen, whose count
   * runs on underneath, so reading it is also time for the first circuit to
   * be built. Written down when PROCEED is pressed, not when shown, so a
   * launch killed on it shows it again. */
  var warning = null;
  var WARNED = 'foxy.warned';
  function warningDue() {
    if (window.FOXY_SHOW_WARNING) return true;     // a debug launch asked to see it again
    try { return !localStorage.getItem(WARNED); } catch (e) { return false; }
  }
  var WARNING_CSS =
    '@keyframes ftg-triDraw{from{stroke-dashoffset:101}to{stroke-dashoffset:0}}' +
    '@keyframes ftg-triFill{from{opacity:0}to{opacity:1}}' +
    '@keyframes ftg-bangDrop{0%{transform:translateY(-46px);opacity:0}60%{transform:translateY(5px);opacity:1}80%{transform:translateY(-2px)}100%{transform:translateY(0);opacity:1}}' +
    '@keyframes ftg-dotPop{0%{transform:scale(0)}60%{transform:scale(1.5)}100%{transform:scale(1)}}' +
    '@keyframes ftg-symbolIn{0%{transform:scale(.6) rotate(-8deg);opacity:0}60%{transform:scale(1.08) rotate(2deg);opacity:1}100%{transform:scale(1) rotate(0);opacity:1}}' +
    '@keyframes ftg-symbolBeat{0%,62%,100%{transform:scale(1)}70%{transform:scale(1.07)}78%{transform:scale(.98)}86%{transform:scale(1.04)}}' +
    '@keyframes ftg-bangBlink{0%,60%,100%{opacity:1}68%{opacity:.35}76%{opacity:1}}' +
    '@keyframes ftg-ring{0%{transform:translate(-50%,-50%) scale(.6);opacity:0}20%{opacity:.5}100%{transform:translate(-50%,-50%) scale(1.9);opacity:0}}' +
    '@keyframes ftg-flash{0%{opacity:0;transform:translate(-50%,-50%) scale(.3)}30%{opacity:.9}100%{opacity:0;transform:translate(-50%,-50%) scale(1.6)}}' +
    '@keyframes ftg-rise{from{transform:translateY(14px);opacity:0}to{transform:translateY(0);opacity:1}}' +
    '@keyframes ftg-glowDrift{0%,100%{transform:translate(-50%,-50%) scale(1);opacity:.5}50%{transform:translate(-50%,-50%) scale(1.12);opacity:.75}}' +
    '@keyframes ftg-grain{0%,100%{transform:translate(0,0)}50%{transform:translate(-2%,1.5%)}}' +
    '#ftg-warn p{margin:0}' +
    '#ftg-warn-go:active{transform:scale(.98)}';
  /* The design (risk-warning.html), with the texture as a
   * bundled file rather than a megabyte of base64 in this script. */
  function showWarning() {
    if (warning) return;
    var sheet = document.createElement('style');
    sheet.textContent = WARNING_CSS;
    document.head.appendChild(sheet);
    var INK = '#1B0C04', MARK = '#F7B46A';
    warning = el('position:fixed;inset:0;z-index:2147483609;overflow:hidden;display:flex;flex-direction:column;' +
      'background:linear-gradient(165deg,#F08A3A 0%,#E9622B 42%,#C9481C 78%,#A93A16 100%);color:' + INK + ';' +
      'font-family:Sora,system-ui,-apple-system,sans-serif;-webkit-font-smoothing:antialiased');
    warning.id = 'ftg-warn';
    warning.appendChild(el('position:absolute;left:50%;top:22%;width:440px;height:440px;border-radius:50%;' +
      'background:radial-gradient(circle,rgba(255,214,150,.55) 0%,rgba(255,214,150,0) 62%);' +
      'animation:ftg-glowDrift 5s ease-in-out infinite;pointer-events:none'));
    warning.appendChild(el('position:absolute;inset:-10%;background:url(risk-grain.jpg) center/cover no-repeat;' +
      'mix-blend-mode:soft-light;opacity:.45;animation:ftg-grain 14s ease-in-out infinite;pointer-events:none'));

    var body = el('position:relative;z-index:1;flex:1 1 auto;display:flex;flex-direction:column;' +
      'padding:calc(40px + env(safe-area-inset-top)) 28px 8px;min-height:0;overflow-y:auto;' +
      '-webkit-mask-image:linear-gradient(to bottom,#000 calc(100% - 28px),transparent)');
    var sign = el('position:relative;align-self:center;width:132px;height:120px;margin:4px 0 0;flex:none',
      '<div style="position:absolute;left:50%;top:58%;width:150px;height:150px;border-radius:50%;' +
        'background:radial-gradient(circle,rgba(255,240,215,.95) 0%,rgba(255,240,215,0) 65%);opacity:0;animation:ftg-flash .9s ease-out 1.15s 1 both"></div>' +
      '<div style="position:absolute;left:50%;top:64%;width:104px;height:104px;border-radius:50%;border:2.5px solid ' + INK + ';opacity:0;animation:ftg-ring 2.6s ease-out 2s infinite"></div>' +
      '<div style="position:absolute;left:50%;top:64%;width:104px;height:104px;border-radius:50%;border:2.5px solid ' + INK + ';opacity:0;animation:ftg-ring 2.6s ease-out 3.3s infinite"></div>' +
      '<div style="position:absolute;inset:0;animation:ftg-symbolIn .7s cubic-bezier(.3,1.4,.5,1) 0s 1 both">' +
        '<div style="position:absolute;inset:0;animation:ftg-symbolBeat 2.6s ease-in-out 2s infinite">' +
          '<svg viewBox="0 0 132 120" width="132" height="120" fill="none" style="position:absolute;inset:0;overflow:visible" aria-hidden="true">' +
            '<path d="M66 8 L124 110 L8 110 Z" fill="' + INK + '" stroke-linejoin="round" style="opacity:0;animation:ftg-triFill .35s ease-out .85s 1 both"></path>' +
            '<path d="M8 110 L66 8 L124 110 Z" pathLength="100" stroke="' + INK + '" stroke-width="10" stroke-linejoin="round" stroke-linecap="round" stroke-dasharray="101" style="animation:ftg-triDraw .9s cubic-bezier(.6,0,.3,1) .1s 1 both"></path>' +
          '</svg>' +
          '<div style="position:absolute;left:50%;top:38px;width:12px;margin-left:-6px;animation:ftg-bangDrop .55s cubic-bezier(.3,1.3,.5,1) 1.1s 1 both">' +
            '<div style="animation:ftg-bangBlink 2.6s ease-in-out 2s infinite;display:flex;flex-direction:column;align-items:center;gap:7px">' +
              '<div style="width:12px;height:38px;border-radius:6px;background:' + MARK + '"></div>' +
              '<div style="width:12px;height:12px;border-radius:50%;background:' + MARK + ';animation:ftg-dotPop .4s ease-out 1.45s 1 both"></div>' +
            '</div></div></div></div>');
    body.appendChild(sign);
    body.appendChild(el('position:relative;z-index:1;text-align:center;margin:26px 0 0;animation:ftg-rise .5s ease-out 1.5s 1 both',
      '<div style="font-weight:800;font-size:34px;line-height:1.05;letter-spacing:-.01em;text-transform:uppercase">Foxy is risky.</div>'));
    body.appendChild(el('display:flex;flex-direction:column;gap:16px;margin:20px 0 0;font-family:Figtree,system-ui,sans-serif;' +
      'font-weight:500;font-size:20px;line-height:1.3;animation:ftg-rise .5s ease-out 1.7s 1 both',
      '<p>Cashu is an experimental protocol.</p>' +
      '<p>Foxy is an experimental wallet.</p>' +
      '<p>Both are doing things that have not been done before at scale.</p>' +
      '<p>You may lose your money due to a bad Cashu mint or a flaw in the Foxy code or Cashu protocol.</p>' +
      '<p style="font-weight:700">Never keep more in Foxy than you are willing to lose.</p>' +
      '<p style="font-weight:700">Never keep money in Foxy for a long time.</p>'));
    warning.appendChild(body);

    /* The button is the whole bottom band and answers to a touch as well as a
     * click, so it works from the first tap wherever the finger lands on it;
     * nothing of the page sits over it. */
    var foot = el('position:relative;z-index:2;flex:none;padding:20px 24px calc(28px + env(safe-area-inset-bottom));' +
      'animation:ftg-rise .5s ease-out 2.1s 1 both');
    var go = el('width:100%;height:60px;border-radius:18px;background:' + INK + ';color:#FFE9CF;' +
      'display:flex;align-items:center;justify-content:center;font-weight:800;font-size:17px;letter-spacing:.04em;' +
      'text-transform:uppercase;cursor:pointer;box-shadow:0 10px 24px rgba(27,12,4,.35);-webkit-tap-highlight-color:transparent;' +
      'touch-action:manipulation;user-select:none;-webkit-user-select:none', 'Proceed');
    go.id = 'ftg-warn-go';
    go.setAttribute('role', 'button');
    var pressed = false;
    var proceed = function (e) {
      if (e && e.preventDefault) e.preventDefault();
      if (pressed) return;
      pressed = true;
      try { localStorage.setItem(WARNED, String(Date.now())); } catch (x) {}
      var going = warning;
      warning = null;
      going.style.transition = 'opacity .35s linear';
      going.style.opacity = '0';
      setTimeout(function () { if (going.parentNode) going.parentNode.removeChild(going); }, 400);
      console.log('[foxy] warning: read, proceeding');
      if (bluetoothDue()) showBluetooth(); else gateChanged();
    };
    go.addEventListener('touchend', proceed, { passive: false });
    go.addEventListener('click', proceed);
    foot.appendChild(go);
    warning.appendChild(foot);
    (document.body || document.documentElement).appendChild(warning);
    console.log('[foxy] warning: shown, first launch since install');
  }

  /* ---- the Bluetooth question, once, after the warning ---------------------
   *
   * Tap to pay needs Bluetooth, and iOS asks for it the first time Foxy
   * makes a Bluetooth manager — which used to be whenever a receive screen
   * first armed, with no word of why. Now the question comes right after the
   * warning, with the reason on screen.
   * APPROVE asks the phone (`bluetoothAsk`), which puts iOS's own dialog up
   * and says how it was answered — and whatever it was, Foxy moves on. It
   * kept the screen on a refusal at first, but iOS asks its question once
   * per install and never again, so a second APPROVE did nothing and the
   * person was stuck. REJECT moves on without
   * asking, and iOS asks its question later, when Bluetooth is first needed.
   * Written down when either button is pressed. */
  var bluetooth = null;
  var BT_ASKED = 'foxy.bt.asked';
  function bluetoothDue() {
    if (window.FOXY_SHOW_WARNING) return true;
    try { return !!localStorage.getItem(WARNED) && !localStorage.getItem(BT_ASKED); } catch (e) { return false; }
  }
  var BLUETOOTH_CSS =
    '@keyframes ftg-discIn{0%{transform:scale(.4);opacity:0}60%{transform:scale(1.1);opacity:1}100%{transform:scale(1);opacity:1}}' +
    '@keyframes ftg-runeDraw{from{stroke-dashoffset:101}to{stroke-dashoffset:0}}' +
    '@keyframes ftg-waveIn{from{opacity:0;transform:scale(.6)}to{opacity:1;transform:scale(1)}}' +
    '@keyframes ftg-wavePulse{0%,100%{opacity:.25}30%{opacity:1}60%{opacity:.25}}' +
    '#ftg-bt p{margin:0}#ftg-bt-go:active,#ftg-bt-no:active{transform:scale(.98)}';
  function showBluetooth() {
    if (bluetooth) return;
    var sheet = document.createElement('style');
    sheet.textContent = BLUETOOTH_CSS;
    document.head.appendChild(sheet);
    var INK = '#13333C';
    bluetooth = el('position:fixed;inset:0;z-index:2147483609;overflow:hidden;display:flex;flex-direction:column;' +
      'background:linear-gradient(170deg,#D3EDF4 0%,#C5E6EF 45%,#B5DDE9 80%,#A9D6E4 100%);color:' + INK + ';' +
      'font-family:Sora,system-ui,-apple-system,sans-serif;-webkit-font-smoothing:antialiased');
    bluetooth.id = 'ftg-bt';
    bluetooth.appendChild(el('position:absolute;left:50%;top:34%;width:440px;height:440px;border-radius:50%;' +
      'background:radial-gradient(circle,rgba(255,255,255,.75) 0%,rgba(255,255,255,0) 65%);' +
      'animation:ftg-glowDrift 5s ease-in-out infinite;pointer-events:none'));
    bluetooth.appendChild(el('position:absolute;inset:-10%;background:url(risk-grain.jpg) center/cover no-repeat;' +
      'mix-blend-mode:soft-light;opacity:.18;animation:ftg-grain 14s ease-in-out infinite;pointer-events:none'));
    var body = el('position:relative;z-index:1;flex:1 1 auto;display:flex;flex-direction:column;align-items:center;' +
      'justify-content:flex-start;padding:calc(40px + env(safe-area-inset-top)) 28px 8px;min-height:0;text-align:center');
    var waves = function (d, delay) {
      return '<path d="' + d + '" style="transform-origin:100px 75px;animation:ftg-waveIn .4s ease-out ' + delay +
        's 1 both,ftg-wavePulse 1.8s ease-in-out ' + (delay + 0.8) + 's infinite"></path>';
    };
    body.appendChild(el('position:relative;width:200px;height:150px;margin:-11px 0 0;flex:none',
      '<div style="position:absolute;left:50%;top:50%;width:160px;height:160px;border-radius:50%;' +
        'background:radial-gradient(circle,rgba(255,255,255,.95) 0%,rgba(255,255,255,0) 65%);opacity:0;animation:ftg-flash .9s ease-out 1s 1 both"></div>' +
      '<div style="position:absolute;left:50%;top:50%;width:112px;height:112px;border-radius:50%;border:2.5px solid ' + INK + ';opacity:0;animation:ftg-ring 2.6s ease-out 1.9s infinite"></div>' +
      '<div style="position:absolute;left:50%;top:50%;width:112px;height:112px;border-radius:50%;border:2.5px solid ' + INK + ';opacity:0;animation:ftg-ring 2.6s ease-out 3.2s infinite"></div>' +
      '<svg viewBox="0 0 200 150" width="200" height="150" fill="none" style="position:absolute;inset:0;overflow:visible" aria-hidden="true">' +
        '<g stroke="' + INK + '" stroke-width="6" stroke-linecap="round">' +
          waves('M38 50 Q26 75 38 100', 1.2) + waves('M22 36 Q4 75 22 114', 1.35) +
          waves('M162 50 Q174 75 162 100', 1.2) + waves('M178 36 Q196 75 178 114', 1.35) +
        '</g>' +
        '<g style="transform-origin:100px 75px;animation:ftg-discIn .6s cubic-bezier(.3,1.4,.5,1) 0s 1 both">' +
          '<circle cx="100" cy="75" r="52" fill="' + INK + '"></circle>' +
          '<path d="M86 56 L116 86 L100 102 L100 48 L116 64 L86 94" pathLength="100" stroke="#BFE3EC" stroke-width="7" ' +
            'stroke-linecap="round" stroke-linejoin="round" stroke-dasharray="101" style="animation:ftg-runeDraw .8s cubic-bezier(.6,0,.3,1) .45s 1 both"></path>' +
        '</g></svg>'));
    body.appendChild(el('margin:15px 0 0;font-weight:800;font-size:34px;line-height:1.05;letter-spacing:-.01em;' +
      'text-transform:uppercase;animation:ftg-rise .5s ease-out 1.4s 1 both', 'Enable tap to pay'));
    body.appendChild(el('display:flex;flex-direction:column;gap:16px;margin:20px 0 0;max-width:320px;' +
      'font-family:Figtree,system-ui,sans-serif;font-weight:500;font-size:20px;line-height:1.35;animation:ftg-rise .5s ease-out 1.6s 1 both',
      '<p>Foxy uses Bluetooth for tap to pay.</p><p>Please allow Foxy to access your Bluetooth.</p>'));
    bluetooth.appendChild(body);

    var foot = el('position:relative;z-index:2;flex:none;display:flex;flex-direction:column;gap:12px;' +
      'padding:20px 24px calc(28px + env(safe-area-inset-bottom));animation:ftg-rise .5s ease-out 1.9s 1 both');
    var button = function (id, words, style) {
      var b = el('width:100%;height:60px;border-radius:18px;display:flex;align-items:center;justify-content:center;' +
        'font-weight:800;font-size:17px;letter-spacing:.04em;text-transform:uppercase;cursor:pointer;' +
        '-webkit-tap-highlight-color:transparent;touch-action:manipulation;user-select:none;-webkit-user-select:none;' + style, words);
      b.id = id;
      b.setAttribute('role', 'button');
      return b;
    };
    var no = button('ftg-bt-no', 'Reject', 'border:2px solid ' + INK + ';background:transparent;color:' + INK);
    var go = button('ftg-bt-go', 'Approve', 'background:' + INK + ';color:#fff;box-shadow:0 10px 24px rgba(19,51,60,.3)');
    var asking = false;
    var leave = function (why) {
      try { localStorage.setItem(BT_ASKED, why); } catch (x) {}
      var going = bluetooth;
      bluetooth = null;
      window.__foxyBluetoothAnswer = null;
      going.style.transition = 'opacity .35s linear';
      going.style.opacity = '0';
      setTimeout(function () { if (going.parentNode) going.parentNode.removeChild(going); }, 400);
      console.log('[foxy] bluetooth screen: ' + why);
      gateChanged();
    };
    var once = function (fn) {
      return function (e) {
        if (e && e.preventDefault) e.preventDefault();
        if (!bluetooth) return;
        fn();
      };
    };
    window.__foxyBluetoothAnswer = function (state) {
      if (!bluetooth) return;
      asking = false;
      // a refusal moves on too: iOS will not ask again, and Settings is the way back
      leave('approved, iOS said ' + state);
    };
    var approve = once(function () {
      if (asking) return;
      var mh = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.foxy;
      if (!mh) { leave('approved, no phone to ask'); return; }
      asking = true;
      try { mh.postMessage({ id: 'bt-' + Date.now(), action: 'bluetoothAsk' }); }
      catch (x) { asking = false; leave('approved, the phone could not be asked'); }
    });
    var reject = once(function () { leave('rejected; iOS asks when Bluetooth is first needed'); });
    go.addEventListener('touchend', approve, { passive: false });
    go.addEventListener('click', approve);
    no.addEventListener('touchend', reject, { passive: false });
    no.addEventListener('click', reject);
    foot.appendChild(no);
    foot.appendChild(go);
    bluetooth.appendChild(foot);
    (document.body || document.documentElement).appendChild(bluetooth);
    console.log('[foxy] bluetooth screen: shown');
  }

  /* ---- the intro: the splash fox waking up --------------------------------
   *
   * The first launch after install opens with the drawing the splash is a still
   * of: the fox sleeping, stretching, standing. It sits
   * over everything this file draws, so nothing else in the launch has to know
   * about it — Tor connects underneath, and the CONNECTING screen is simply
   * not seen until the fox is done.
   *
   * The first frame of the video is the splash art, at the same size and in
   * the same place, so the hand-over from the native splash is invisible: what
   * was a still starts moving.
   *
   * Placement is arithmetic, not taste. The drawing is 800 x 526 with the
   * sleeping fox 66.75% of it wide and its middle 43% down; the splash puts
   * that fox across 61% of the screen, centred. So the frame is drawn 91.4% of
   * the screen wide (61 / 66.75), and lifted so that point sits at the middle:
   * 50% - 0.43 x 60.1vw. Change the artwork and all four numbers change.
   */
  var intro = null, introDone = null, introName = null, introGo = null, introStart = null, introWait = null;
  var INTRO_NAME_BEFORE_MS = 1000;     // the name is up this long before the film ends
  var INTRO_NAME_FADE_MS = 700;        // and takes this long to get there
  var INTRO_FILM_MS = 9600;            // the film's own length, if it will not say

  /* The animation and the still are the same picture on the same canvas — the
   * splash's shape, with the fox at the splash's size and place, composited by
   * tools/make-intro.swift — so both are laid out by one rule and cannot drift
   * apart. Sizing the video against the splash in CSS instead put it thirty
   * points low on a phone. */
  var FRAME = 'position:absolute;inset:0;width:100%;height:100%;object-fit:cover;display:block';

  function introDay() {
    var d = new Date();
    return d.getFullYear() + '-' + (d.getMonth() + 1) + '-' + d.getDate();
  }

  /* The first launch after install, and no other. It was once a day, and
   * every launch of a debug build: a film in front of a wallet somebody
   * wants to pay with. The first launch is the one with something to wait
   * for, since the relay list has to be fetched before anything can happen.
   *
   * The stamp is written when it starts, not when it finishes, so a launch
   * killed halfway through does not replay it on the next one. A phone that
   * holds the old daily stamp has seen it. */
  function introDue() {
    try {
      if (localStorage.getItem('foxy.intro.seen') || localStorage.getItem('foxy.intro.day')) return false;
      localStorage.setItem('foxy.intro.seen', introDay());
      return true;
    } catch (e) { return false; }      // no storage: no intro rather than every launch
  }

  function introEnd() {
    if (!intro) return;
    clearTimeout(introDone);
    clearTimeout(introName);
    clearTimeout(introWait);
    introGo = null;
    introStart = null;
    var going = intro;
    intro = null;
    going.style.transition = 'opacity .45s linear';
    going.style.opacity = '0';
    // the screen underneath is seen now, so its count starts now
    if (screen === 'connecting') { startCount(); render(state()); }
    setTimeout(function () {
      /* Emptied, not just removed. On a first install the fox's last frame
       * flashed behind the backup card a moment after he had gone: the node was still in the page, transparent, and the card
       * drawing over it made the page repaint what was underneath. Nothing to
       * repaint now. */
      var film = going.querySelector('video');
      if (film) { try { film.pause(); } catch (e) {} film.removeAttribute('src'); film.load(); }
      var shot = going.querySelector('img');
      if (shot) shot.removeAttribute('src');
      going.style.display = 'none';
      if (going.parentNode) going.parentNode.removeChild(going);
    }, 500);
    gateChanged();
  }

  /* The intro: the splash fox waking up.
   *
   * The first launch after install opens with the drawing the splash is a still
   * of — sleeping, stretching, standing. It sits over
   * everything else this file draws, so nothing in the launch has to know about
   * it: Tor connects underneath the whole time — which is the point on a first
   * install, where the peers have to be fetched before anything can happen —
   * and the CONNECTING screen is simply not seen until the fox is done.
   *
   * The splash still is the layer's own background and the film is the same
   * canvas over it, so the first frame is the still.
   */
  function introPlay() {
    if (intro || !bridged() || !introDue()) return;
    var still = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);

    intro = el('position:fixed;inset:0;z-index:2147483610;background:#000 url(foxy-splash-still.png) ' +
      'center/cover no-repeat;overflow:hidden;' +
      'font-family:Sora,system-ui,-apple-system,sans-serif');

    var film = document.createElement('video');
    film.src = 'foxy-intro.mp4';
    film.muted = true;
    film.defaultMuted = true;
    /* Not autoplay. The element used to carry it, so WebKit started the film
     * the moment it could — which is while the phone is still asking for Face
     * ID, and the fox was mid-stretch by the time the person looked back. It waits for introGo now. */
    film.autoplay = false;
    film.setAttribute('playsinline', '');
    film.setAttribute('webkit-playsinline', '');
    film.setAttribute('disablepictureinpicture', '');
    film.preload = 'auto';
    /* The last frame goes in first and stays visible, with the film over it.
     * The film's own frames are opaque, so it covers the standing fox for the
     * whole ride — and when it is hidden at the end there is nothing to see
     * through, where before the splash still flashed for a moment between the
     * two. */
    var last = document.createElement('img');
    last.src = 'foxy-intro-last.png';
    last.style.cssText = FRAME;
    intro.appendChild(last);

    film.style.cssText = FRAME + (still ? ';visibility:hidden' : '');
    intro.appendChild(film);

    /* Under his feet, centred: the standing fox reaches 63.6% down the screen. Sora, like everything else this file draws — the
     * layer sets the family and the page carries the font. */
    var word = el('position:absolute;left:0;right:0;top:calc(63.6% + 4px);' +
      'text-align:center;font-size:34px;line-height:38px;font-weight:800;letter-spacing:.16em;' +
      'color:#fff;opacity:0;transition:opacity ' + (INTRO_NAME_FADE_MS / 1000) + 's ease-out');
    word.textContent = 'FOXY';
    intro.appendChild(word);

    document.body.appendChild(intro);
    gateChanged();
    sayCovered();

    var settled = false, cleared = false, held = false;

    /* The name comes up while he is still finishing, so that it is fully there
     * a second before the film ends, and the screen moves on the moment it
     * does. It used to appear at the end and hold for
     * three seconds afterwards, which read as a pause rather than a finish. */
    function nameIn() {
      word.style.opacity = '1';
    }

    function schedule() {
      var runs = (film.duration && isFinite(film.duration)) ? film.duration * 1000 : INTRO_FILM_MS;
      var at = Math.max(0, runs - INTRO_NAME_BEFORE_MS - INTRO_NAME_FADE_MS);
      clearTimeout(introName);
      introName = setTimeout(nameIn, at);
    }

    function done() {                      // the film has run, however it ended
      if (settled) return;
      settled = true;
      clearTimeout(introName);
      film.style.visibility = 'hidden';      // the last frame is already under it
      nameIn();                              // it is already up, unless the film was short
      maybeEnd();
    }

    /* Away once both are true: the film has finished, and the launch is past
     * the step this was covering. No hold on the last frame — when the film
     * ends the screen moves on. A step still running is the one thing that
     * keeps him there, standing, until it is done. */
    function maybeEnd() {
      if (!settled || !cleared || held) return;
      held = true;
      introEnd();
    }

    function start() {
      if (still) { done(); return; }
      clearTimeout(introDone);
      schedule();
      film.addEventListener('loadedmetadata', schedule);
      // the clock is the backstop: a decoder that gives up must not hang it
      introDone = setTimeout(done, 12000);
      var going = film.play();
      if (going && going.catch) going.catch(done);
    }

    film.addEventListener('ended', done);
    film.addEventListener('error', done);

    /* It does not start here.
     *
     * The seed is read a moment after this, and reading it is Face ID — so the
     * fox used to be halfway through his stretch by the time the prompt was
     * answered and the phone was being looked at again.
     * The layer goes up now, showing the still, which is the splash; the app
     * says when the person is through (FoxyGate.introGo), and the film starts
     * then. The clock is the backstop for a launch that never says so. */
    /* Two signals, not one.
     *
     * The film starts as the phone asks for Face ID, so it plays while the
     * person is being looked at rather than after; and it
     * will not leave until the launch says that step is done, so a slow or
     * retried unlock finds the fox standing there waiting rather than an empty
     * screen. The clocks are backstops for a launch that says neither. */
    introStart = function () {
      introStart = null;
      clearTimeout(introWait);
      introWait = setTimeout(function () { cleared = true; maybeEnd(); }, 30000);
      start();
    };
    introGo = function () {
      if (introStart) introStart();     // it was never told to start: start now
      introGo = null;
      cleared = true;
      maybeEnd();
    };
    introWait = setTimeout(function () { if (introStart) introStart(); }, 12000);
  }

  introPlay();

  window.FoxyGate = FoxyGate;
})();
