/* foxy-move-progress.js — the "moving ecash between mints" overlay.
 *
 * The third of the overlays that own their own DOM (foxy-send-progress.js,
 * foxy-receive-progress.js). Built with document.createElement and styled by
 * writing to element.style: nothing here goes through the design runtime, so
 * nothing it shows can lag behind the work it is covering.
 *
 * A token from a mint this wallet does not use takes two steps, and both are
 * slow over Tor: the fee to move it has to be quoted at two mints, and then the
 * sats have to be claimed at theirs and paid home to yours. This covers both,
 * with the same animation and a different title:
 *
 *   VERIFYING ECASH             while the move is being priced
 *   MOVING ECASH TO YOUR MINT   once they have said to go ahead
 *
 * The two banks are named after the real mints, so the picture says which way
 * the money is going rather than standing in for it.
 *
 * The fonts are the app's own: the original names Outfit and DM Mono from
 * Google's servers, which this page cannot reach and must not try to.
 *
 * Public surface:
 *   FoxyMoving.begin(title, {from, to})   show it
 *   FoxyMoving.retitle(title)             change the words, keep the animation
 *   FoxyMoving.end()                      take it down
 *   FoxyMoving.visible()                  is it up
 */
(function () {
  'use strict';

  var INK = '#F6FAFC';
  var BACK = '#0D1013';
  var ACC = '#F2802E';

  /* The stage is laid out at this height and scaled to whatever the phone
   * gives it. Scale the container, not the internals: every position
   * inside is in these coordinates. */
  var DESIGN_H = 660;
  var DESIGN_W = 320;

  function mark(size, colour, width) {
    return '<svg viewBox="0 0 24 24" aria-hidden="true" style="width:' + size + 'px;height:' + size
      + 'px;display:block"><g stroke="' + colour + '" stroke-width="' + width + '" stroke-linecap="round">'
      + '<line x1="12" y1="2" x2="12" y2="22"/><line x1="4" y1="7" x2="20" y2="7"/>'
      + '<line x1="4" y1="12" x2="20" y2="12"/><line x1="4" y1="17" x2="20" y2="17"/></g></svg>';
  }

  var NOTE_IN = [['-52px', '-6deg', '1.5s', '0s'], ['30px', '5deg', '1.4s', '0.4s'],
                 ['-10px', '-2deg', '1.5s', '0.8s'], ['48px', '7deg', '1.35s', '1.2s'],
                 ['-38px', '-4deg', '1.45s', '1.6s']];
  var COINS = [['40px', '-46px', '2.6s'], ['32px', '26px', '2.95s'], ['44px', '-8px', '3.3s'],
               ['28px', '50px', '3.65s'], ['36px', '-34px', '4s']];
  var NOTE_OUT = [['-50px', '-5deg', '5.2s'], ['28px', '4deg', '5.6s'],
                  ['-8px', '-2deg', '6s'], ['46px', '6deg', '6.4s']];

  var root = null, styleTag = null, headEl = null, scaler = null;

  /* Every animated part, with the animation it was given.
   *
   * The stage is one run of about eight seconds — notes in, the sats across,
   * notes out — and a move takes as long as two mints and a Lightning payment
   * take, which is usually longer than that. It loops for as long as it is up.
   *
   * Not with `infinite`: a CSS animation-delay applies to the first iteration
   * only, so an infinite one would run the whole stage once as drawn and then
   * repeat with every note and coin leaving at the same moment — the stagger
   * that makes it a stream is in the delays. Instead the run is started again:
   * every animation is taken off, the page is made to notice, and they are put
   * back, which begins all of them from nothing with their delays intact. */
  var parts = [];
  var looping = null;
  var CYCLE_MS = 8200;      // the last note out leaves at 6.4s and takes 1.6s

  function animate(node, animation) {
    node.style.animation = animation;
    parts.push({ node: node, animation: animation });
    return node;
  }

  function runAgain() {
    if (!root || !parts.length) return;
    for (var i = 0; i < parts.length; i++) parts[i].node.style.animation = 'none';
    void root.offsetWidth;                 // one reflow, so the change is seen
    for (var j = 0; j < parts.length; j++) parts[j].node.style.animation = parts[j].animation;
  }

  /* Still, for somebody who has asked for less movement: the stylesheet takes
   * every duration down to nothing, and running that again on a loop would be
   * a flicker rather than an animation. */
  function motionWanted() {
    try {
      return !(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
    } catch (e) { return true; }
  }

  function css() {
    return [
      '@keyframes foxyMmWork{0%,100%{opacity:.16}50%{opacity:.5}}',
      '@keyframes foxyMmSettle{0%{opacity:.5}100%{opacity:.22}}',
      /* The top bank sits 60px lower than the original drawing, so the notes fall 60px further to land on it and the
       * coins start 60px lower and run 60px less — they leave the same base
       * and arrive at the same roof. */
      '@keyframes foxyMmFallIn{',
      '0%{transform:translate(var(--x),-90px) rotate(var(--r)) scale(.9);opacity:0}',
      '12%{transform:translate(var(--x),-40px) rotate(var(--r)) scale(1);opacity:1}',
      '100%{transform:translate(calc(var(--x) * .2),178px) rotate(var(--r)) scale(1);opacity:1}}',
      '@keyframes foxyMmHandoff{',
      '0%{transform:translate(calc(-50% + var(--x) * .2),-8px) scale(.4);opacity:0}',
      '18%{transform:translate(calc(-50% + var(--x)),18px) scale(1);opacity:1}',
      '82%{transform:translate(calc(-50% + var(--x)),90px) scale(1);opacity:1}',
      '100%{transform:translate(calc(-50% + var(--x) * .25),122px) scale(.7);opacity:0}}',
      '@keyframes foxyMmFallOut{',
      '0%{transform:translate(calc(-50% + var(--x) * .15),-14px) rotate(0deg) scale(.38);opacity:0}',
      '30%{transform:translate(calc(-50% + var(--x)),34px) rotate(var(--r)) scale(1);opacity:1}',
      '100%{transform:translate(calc(-50% + var(--x) * 1.3),148px) rotate(var(--r)) scale(1);opacity:0}}',
      '@keyframes foxyMmIn{from{opacity:0}to{opacity:1}}',
      '@media (prefers-reduced-motion: reduce){',
      '[data-foxy-moving] *{animation-duration:.001s !important}}'
    ].join('');
  }

  function el(style, html) {
    var d = document.createElement('div');
    d.style.cssText = style;
    if (html) d.innerHTML = html;
    return d;
  }

  function note(big) {
    var n = el('position:absolute;border-radius:6px;opacity:0;'
      + 'background:linear-gradient(150deg,#D8E4EC,#A8B8C4);border:1.5px solid #EDF4F8;'
      + 'box-shadow:0 8px 18px rgba(0,0,0,.6);display:flex;align-items:center;justify-content:center');
    n.appendChild(el('position:absolute;inset:3px;border:1px solid rgba(28,38,46,.5);border-radius:4px'));
    n.appendChild(el('position:absolute;inset:5px;border:1px dashed rgba(28,38,46,.28);border-radius:3px'));
    var tag = 'position:absolute;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;'
      + 'font-size:6px;letter-spacing:.1em;color:#2B3942';
    var tl = el(tag + ';top:3px;left:5px'); tl.textContent = 'ECASH';
    var br = el(tag + ';bottom:3px;right:5px'); br.textContent = 'ECASH';
    n.appendChild(tl); n.appendChild(br);
    n.appendChild(el('position:relative;width:21px;height:21px;border-radius:50%;'
      + 'border:1.5px solid rgba(40,54,64,.6);background:rgba(28,38,46,.1);'
      + 'display:flex;align-items:center;justify-content:center', mark(12, '#22303A', 2.2)));
    return n;
  }

  /* A mint, drawn as the kind of building the word brings to mind. */
  function bank(top, name) {
    var b = el('position:absolute;left:50%;top:' + top + 'px;width:150px;'
      + 'transform:translate(-50%,0);z-index:3;display:flex;flex-direction:column;align-items:center');
    var label = String(name || '').toUpperCase().slice(0, 12);
    b.innerHTML = '<svg viewBox="0 0 160 34" style="width:150px;height:32px;display:block">'
      + '<polygon points="80,1 158,33 2,33" fill="#1C2126" stroke="#3A434B" stroke-width="1.5" stroke-linejoin="round"/>'
      + '<text x="80" y="27" text-anchor="middle" font-family="Sora, system-ui, sans-serif" '
      + 'font-size="11" font-weight="800" letter-spacing="2.4" fill="' + ACC + '">'
      + label.replace(/&/g, '&amp;').replace(/</g, '&lt;') + '</text></svg>';
    b.appendChild(el('width:142px;height:8px;background:linear-gradient(180deg,#232A30,#141719);'
      + 'border:1.5px solid #3A434B;border-radius:3px'));
    var body = el('width:130px;padding:6px 9px 0;background:linear-gradient(180deg,#191E22,' + BACK + ');'
      + 'border-left:1.5px solid #333C44;border-right:1.5px solid #333C44;'
      + 'display:flex;justify-content:space-between;align-items:flex-end;gap:5px');
    var col = 'width:12px;height:64px;border-radius:2px 2px 0 0;'
      + 'background:linear-gradient(90deg,#3E4952,#1A1F23 42%,#3E4952)';
    body.appendChild(el(col));
    body.appendChild(el(col));
    body.appendChild(el('flex:1 1 auto;margin:0 3px;height:64px;border-radius:18px 18px 0 0;'
      + 'background:linear-gradient(180deg,' + BACK + ',#070809);'
      + 'border:1.5px solid #333C44;border-bottom:none'));
    body.appendChild(el(col));
    body.appendChild(el(col));
    b.appendChild(body);
    b.appendChild(el('width:142px;height:10px;background:linear-gradient(180deg,#232A30,#101315);'
      + 'border:1.5px solid #3A434B;border-radius:0 0 4px 4px'));
    return b;
  }

  function glow(top) {
    return animate(el('position:absolute;left:50%;top:' + top + 'px;width:200px;height:170px;'
      + 'transform:translateX(-50%);border-radius:50%;filter:blur(32px);'
      + 'background:radial-gradient(circle,' + ACC + ' 0%,rgba(242,128,46,0) 68%)'),
      'foxyMmWork 2s ease-in-out 0s 4 both, foxyMmSettle 1.4s ease-out 8s 1 forwards');
  }

  function build(title, opts) {
    var o = opts || {};
    if (!styleTag) {
      styleTag = document.createElement('style');
      styleTag.textContent = css();
      document.head.appendChild(styleTag);
    }
    root = el('position:fixed;inset:0;z-index:2147483500;background:' + BACK + ';'
      + 'display:flex;flex-direction:column;align-items:center;overflow:hidden;'
      + 'font-family:Sora,system-ui,-apple-system,sans-serif;-webkit-font-smoothing:antialiased;'
      + 'animation:foxyMmIn .18s ease');
    root.setAttribute('data-foxy-moving', '1');

    /* The stage keeps its design size and is scaled to the room there is, so
     * every coordinate inside stays the one the animation was drawn against. */
    var fit = el('position:absolute;left:0;right:0;top:0;bottom:0;'
      + 'display:flex;align-items:flex-start;justify-content:center;overflow:hidden');
    scaler = el('position:relative;width:' + DESIGN_W + 'px;height:' + DESIGN_H + 'px;'
      + 'transform-origin:top center;flex:none');
    fit.appendChild(scaler);
    root.appendChild(fit);

    scaler.appendChild(glow(122));
    scaler.appendChild(glow(372));
    scaler.appendChild(bank(132, o.from || 'THEIR MINT'));
    scaler.appendChild(bank(382, o.to || 'YOUR MINT'));

    var frag = document.createDocumentFragment();
    // 1 · their ecash falls into their mint
    NOTE_IN.forEach(function (n) {
      var e = note();
      e.style.cssText += ';left:50%;top:0;margin-left:-31px;width:62px;height:37px;z-index:1;'
        + '--x:' + n[0] + ';--r:' + n[1];
      frag.appendChild(animate(e, 'foxyMmFallIn ' + n[2] + ' linear ' + n[3] + ' 1 both'));
    });
    // 2 · the sats hand off, one mint to the other, over Lightning
    COINS.forEach(function (c) {
      var coin = el('position:absolute;left:50%;top:264px;z-index:2;border-radius:50%;opacity:0;'
        + 'width:' + c[0] + ';height:' + c[0] + ';'
        + 'background:conic-gradient(from 210deg,#8A4E0D 0deg,#F7C34A 40deg,#FFF3CE 78deg,'
        + '#E0A733 130deg,#9C5A11 190deg,#F0BC49 250deg,#FFEBAF 296deg,#B87317 350deg,#8A4E0D 360deg);'
        + 'box-shadow:0 5px 0 -2px #7C440B;--x:' + c[1]);
      coin.appendChild(el('position:absolute;inset:5px;border-radius:50%;'
        + 'background:radial-gradient(circle at 38% 30%,#FFEEBB 0%,#EDB73F 46%,#C07C1E 100%);'
        + 'display:flex;align-items:center;justify-content:center',
        mark(Math.round(parseInt(c[0], 10) * 0.36), '#B8741A', 2.4)));
      frag.appendChild(animate(coin, 'foxyMmHandoff 1.7s cubic-bezier(.4,.02,.5,1) ' + c[2] + ' 1 both'));
    });
    // 3 · your mint issues the ecash that is now yours
    NOTE_OUT.forEach(function (n) {
      var e = note();
      e.style.cssText += ';left:50%;top:510px;width:62px;height:37px;z-index:2;'
        + '--x:' + n[0] + ';--r:' + n[1];
      frag.appendChild(animate(e, 'foxyMmFallOut 1.6s cubic-bezier(.3,.9,.5,1) ' + n[2] + ' 1 both'));
    });
    scaler.appendChild(frag);

    var words = el('position:relative;z-index:4;margin-top:auto;'
      + 'padding:0 28px calc(40px + env(safe-area-inset-bottom));text-align:center');
    headEl = el('font-size:24px;font-weight:800;letter-spacing:-0.01em;color:' + INK);
    headEl.textContent = title || 'MOVING ECASH TO YOUR MINT';
    words.appendChild(headEl);
    root.appendChild(words);

    document.body.appendChild(root);
    scale();
    // and round again, for as long as the move takes
    if (motionWanted()) looping = setInterval(runAgain, CYCLE_MS);
  }

  /* The stage is 660 tall and the words want the foot of the screen; whatever
   * is left over after them is what the stage is scaled into. */
  function scale() {
    if (!root || !scaler) return;
    var room = root.clientHeight - 96;
    var wide = root.clientWidth;
    var by = Math.min(1, room / DESIGN_H, wide / DESIGN_W);
    scaler.style.transform = 'scale(' + (by > 0 ? by.toFixed(3) : 1) + ')';
  }

  var FoxyMoving = {
    begin: function (title, opts) {
      if (root) this.end(true);
      parts = [];
      try { build(title, opts); } catch (e) { root = null; }
      return !!root;
    },
    /* The same animation, different words: the move is priced and then made,
     * and the picture is the same picture. */
    retitle: function (title) {
      if (headEl && title) headEl.textContent = title;
      return !!headEl;
    },
    end: function (now) {
      if (!root) return;
      var going = root;
      root = null;
      headEl = null;
      scaler = null;
      // the loop goes with it: a stage nobody can see must not keep drawing
      if (looping) { clearInterval(looping); looping = null; }
      parts = [];
      if (now) {
        going.innerHTML = '';
        if (going.parentNode) going.parentNode.removeChild(going);
        return;
      }
      going.style.transition = 'opacity .2s ease-out';
      going.style.opacity = '0';
      setTimeout(function () {
        // emptied, not only detached: the notes and coins are still animating
        going.innerHTML = '';
        if (going.parentNode) going.parentNode.removeChild(going);
      }, 220);
    },
    visible: function () { return !!root; }
  };

  window.addEventListener('resize', scale);
  window.FoxyMoving = FoxyMoving;
})();
