/* foxy-receive-progress.js — the "verifying ecash" overlay.
 *
 * The receiving counterpart of foxy-send-progress.js, and built the same way:
 * it owns its own DOM, made with document.createElement and styled by writing
 * to element.style. Nothing here goes through the design runtime's templates
 * or re-render cycle, so nothing it shows can lag behind what is happening.
 *
 * A token pasted or scanned is checked with the mint and then swapped for
 * proofs this wallet holds — two round trips over Tor, which on a slow circuit
 * is several seconds with nothing to look at. This is what fills them: the
 * other phone at the top, and their ecash drifting down past you, faster and
 * faster (modelled on anim-receive-ecash.html).
 *
 * The fonts are the app's own. The original names Outfit and DM Mono from
 * Google's servers; this page cannot reach them — every http(s) request is
 * blocked by the content rules, and reaching out would be the one request
 * Foxy makes that is not over Tor.
 *
 * Public surface:
 *   FoxyReceiving.begin(text)   show it; `text` replaces the default title
 *   FoxyReceiving.end()         take it down
 *   FoxyReceiving.visible()     is it up
 */
(function () {
  'use strict';

  var INK = '#F6FAFC';
  var BACK = '#0D1013';
  var EDGE = '#3A434B';
  var PALE = '#CFE0EC';

  /* The note's own mark: the sat symbol drawn as strokes, so it needs no font.
   * The same one the peer phone shows, at the size the note wants. */
  function mark(size, colour, width) {
    return '<svg viewBox="0 0 24 24" aria-hidden="true" style="width:' + size + 'px;height:' + size
      + 'px;display:block"><g stroke="' + colour + '" stroke-width="' + width + '" stroke-linecap="round">'
      + '<line x1="12" y1="2" x2="12" y2="22"/><line x1="4" y1="7" x2="20" y2="7"/>'
      + '<line x1="4" y1="12" x2="20" y2="12"/><line x1="4" y1="17" x2="20" y2="17"/></g></svg>';
  }

  /* When each note leaves, and how long it takes.
   *
   * One schedule for the whole stream: full speed until two seconds in, then
   * ramping to five times that by five seconds, so every note in flight moves
   * at the same rate as every other and the stream accelerates as a body
   * rather than as a set of independent drifts. */
  function stream(gap, base) {
    var out = [], t = 0;
    while (t <= 4.7) {
      var speed = 1 + 4 * Math.min(1, Math.max(0, (t - 2) / 3));
      out.push({ delay: t.toFixed(2) + 's', dur: (base / speed).toFixed(2) + 's' });
      t += gap / speed;
    }
    return out;
  }

  var XS = ['-46px', '28px', '-12px', '44px', '4px', '-34px', '38px', '-22px', '18px', '-52px'];
  var ROTS = ['-7deg', '5deg', '-3deg', '8deg', '-5deg'];

  var root = null;
  var styleTag = null;

  function css() {
    return [
      '@keyframes foxyRecvGlow{0%,100%{opacity:.2}50%{opacity:.55}}',
      '@keyframes foxyRecvDrift{',
      '0%{transform:translate(var(--x),0) rotate(var(--r)) scale(.5);opacity:0}',
      '12%{transform:translate(var(--x),34px) rotate(var(--r)) scale(1);opacity:1}',
      '88%{transform:translate(calc(var(--x) * 1.4),380px) rotate(var(--r)) scale(1);opacity:1}',
      '100%{transform:translate(calc(var(--x) * 1.5),430px) rotate(var(--r)) scale(1);opacity:0}}',
      '@keyframes foxyRecvIn{from{opacity:0}to{opacity:1}}',
      '@media (prefers-reduced-motion: reduce){',
      '[data-foxy-receiving] *{animation-duration:.001s !important}}'
    ].join('');
  }

  function el(style, html) {
    var d = document.createElement('div');
    d.style.cssText = style;
    if (html) d.innerHTML = html;
    return d;
  }

  function build(title) {
    if (!styleTag) {
      styleTag = document.createElement('style');
      styleTag.textContent = css();
      document.head.appendChild(styleTag);
    }
    root = el('position:fixed;inset:0;z-index:2147483500;background:' + BACK + ';'
      + 'display:flex;flex-direction:column;align-items:center;overflow:hidden;'
      + 'font-family:Sora,system-ui,-apple-system,sans-serif;-webkit-font-smoothing:antialiased;'
      + 'animation:foxyRecvIn .18s ease');
    root.setAttribute('data-foxy-receiving', '1');

    /* The stage fills the screen and the notes fall through it; the words sit
     * over the top of it rather than beside it, so the fall is the full height
     * of the phone whatever the phone is. */
    var stage = el('position:absolute;inset:0;overflow:hidden');
    root.appendChild(stage);

    stage.appendChild(el('position:absolute;left:50%;top:5%;width:170px;height:150px;'
      + 'transform:translate(-50%,0);border-radius:50%;filter:blur(30px);'
      + 'background:radial-gradient(circle,' + PALE + ' 0%,rgba(207,224,236,0) 68%);'
      + 'animation:foxyRecvGlow 2.4s ease-in-out infinite'));

    var peer = el('position:absolute;left:50%;top:9%;width:58px;height:114px;'
      + 'transform:translate(-50%,0);z-index:3;border-radius:14px;'
      + 'background:linear-gradient(170deg,#1C2126,' + BACK + ');border:2px solid ' + EDGE + ';'
      + 'box-shadow:0 14px 34px rgba(0,0,0,.75);display:flex;align-items:center;justify-content:center',
      mark(24, PALE, 2.2));
    peer.appendChild(el('position:absolute;left:50%;top:6px;transform:translateX(-50%);'
      + 'width:18px;height:3px;border-radius:2px;background:' + EDGE));
    peer.appendChild(el('position:absolute;left:50%;bottom:6px;transform:translateX(-50%);'
      + 'width:22px;height:3px;border-radius:2px;background:' + EDGE));
    stage.appendChild(peer);

    var notes = document.createDocumentFragment();
    stream(0.34, 2.8).forEach(function (n, i) {
      var note = el('position:absolute;left:50%;top:150px;margin-left:-44px;width:88px;height:52px;'
        + 'z-index:1;border-radius:8px;opacity:0;'
        + 'background:linear-gradient(150deg,#D8E4EC,#A8B8C4);border:1.5px solid #EDF4F8;'
        + 'box-shadow:0 8px 18px rgba(0,0,0,.6);display:flex;align-items:center;justify-content:center;'
        + 'animation:foxyRecvDrift linear 1 both;'
        + '--x:' + XS[i % XS.length] + ';--r:' + ROTS[i % ROTS.length] + ';'
        + 'animation-duration:' + n.dur + ';animation-delay:' + n.delay);
      note.appendChild(el('position:absolute;inset:4px;border:1px solid rgba(28,38,46,.5);border-radius:5px'));
      note.appendChild(el('position:absolute;inset:7px;border:1px dashed rgba(28,38,46,.28);border-radius:3px'));
      var tag = 'position:absolute;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;'
        + 'font-size:8px;letter-spacing:.1em;color:#2B3942';
      var tl = el(tag + ';top:5px;left:8px'); tl.textContent = 'ECASH';
      var br = el(tag + ';bottom:5px;right:8px'); br.textContent = 'ECASH';
      note.appendChild(tl);
      note.appendChild(br);
      note.appendChild(el('position:relative;width:30px;height:30px;border-radius:50%;'
        + 'border:1.5px solid rgba(40,54,64,.6);background:rgba(28,38,46,.1);'
        + 'display:flex;align-items:center;justify-content:center', mark(17, '#22303A', 2.2)));
      notes.appendChild(note);
    });
    stage.appendChild(notes);

    // the words, under the fall and over everything the fall passes
    var words = el('position:relative;z-index:4;margin-top:auto;'
      + 'padding:0 28px calc(64px + env(safe-area-inset-bottom));text-align:center');
    var head = el('font-size:26px;font-weight:800;letter-spacing:-0.01em;color:' + INK);
    head.textContent = title || 'VERIFYING ECASH';
    words.appendChild(head);
    var sub = el('margin-top:8px;font-size:17px;font-weight:500;line-height:1.4;'
      + 'color:rgba(246,250,252,.6)');
    sub.textContent = 'Checking it with the mint over Tor.';
    words.appendChild(sub);
    root.appendChild(words);

    /* Never the last screen. Eight seconds with no answer and it says so and
     * offers a way off: a scan whose mint check hung behind a stalled Tor sat
     * here for twenty-six seconds with nothing to press. The
     * check goes on behind; leaving only stops the waiting. */
    var mine = root;
    clearTimeout(slowT);
    slowT = setTimeout(function () {
      if (root !== mine) return;
      sub.textContent = 'Still waiting for the mint to answer.';
      var stop = el('margin:18px auto 0;height:50px;max-width:260px;border-radius:25px;'
        + 'border:1.5px solid rgba(246,250,252,.7);display:flex;align-items:center;justify-content:center;'
        + 'font-size:15px;font-weight:800;letter-spacing:.06em;cursor:pointer;color:' + INK);
      stop.textContent = 'STOP WAITING';
      stop.addEventListener('click', function () {
        var go = window.__foxyStopWaiting;
        FoxyReceiving.end();
        if (typeof go === 'function') { try { go(); } catch (e) {} }
      });
      words.appendChild(stop);
    }, 13000);

    document.body.appendChild(root);
  }

  var slowT = null;

  var FoxyReceiving = {
    begin: function (title) {
      if (root) { this.end(); }
      try { build(title); } catch (e) { root = null; }
      return !!root;
    },
    end: function () {
      clearTimeout(slowT);
      if (!root) return;
      var going = root;
      root = null;
      going.style.transition = 'opacity .2s ease-out';
      going.style.opacity = '0';
      setTimeout(function () {
        /* Emptied, not just removed: the notes are animating when this runs,
         * and a detached tree that is still being animated keeps the work
         * going on some builds. */
        going.innerHTML = '';
        if (going.parentNode) going.parentNode.removeChild(going);
      }, 220);
    },
    visible: function () { return !!root; }
  };

  window.FoxyReceiving = FoxyReceiving;
})();
