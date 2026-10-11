/* foxy-send-progress.js — the send progress overlay.
 *
 * This deliberately owns its own DOM. It builds its elements with
 * document.createElement, appends them to the page, and updates them by
 * writing to element.style on a requestAnimationFrame loop.
 *
 * Nothing here goes through the design runtime's templates, bindings or
 * re-render cycle. That is the whole point: earlier versions set state and
 * waited for the screen to reflect it, and the screen fell seconds behind —
 * the payment finished, the state read 100, and the display still showed 78.
 * Here the state IS the element's style, so it cannot lag.
 *
 * Public surface:
 *   FoxyProgress.begin()          show the overlay, reset to zero
 *   FoxyProgress.stage(name)      report a milestone: resolving | quote |
 *                                  melting | inflight | done
 *   FoxyProgress.whenFull(fn)     called once the finish animation starts
 *   FoxyProgress.end()            hide and tear down
 *   FoxyProgress.visible()        is it currently up
 */
(function () {
  'use strict';

  var LADDER = { start: 0, resolving: 10, quote: 25, melting: 50, inflight: 75, done: 100 };

  var COINS = [
    [8, '#5CD46C', '150px', '-130px', '0s'],
    [6, '#FED100', '-160px', '-90px', '.3125s'],
    [7, '#5CD46C', '130px', '150px', '.625s'],
    [6, '#9FF0B4', '-140px', '140px', '.9375s'],
    [5, '#FED100', '0px', '-190px', '1.25s'],
    [7, '#5CD46C', '0px', '200px', '1.5625s'],
    [6, '#9FF0B4', '190px', '20px', '1.875s'],
    [6, '#FED100', '-195px', '30px', '2.1875s']
  ];

  var BOLT =
    '<svg style="height:104px;width:auto;display:block" xmlns="http://www.w3.org/2000/svg" ' +
    'viewBox="0 0 236.5 366" role="img" aria-label="Flash bolt">' +
    '<path d="M81.1,364.6c-11.8-3.1-21.4-11.1-26.8-22-5.1-10.1-6-22.6-1.5-33.1l15.3-33,17.8-37.6c.3-.7-.1-1.6-.4-2.1' +
    's-1-.9-1.9-.9h-40.8c-9,0-17.4-2.6-24.7-7.9-5-3.6-9.1-8.1-12.3-13.4-5.6-9.4-7.3-21.3-4.3-31.8l4.3-14.8L37.9,56.1' +
    'c4.3-20.8,21.7-34.8,42.9-34.2,14.7.4,28.3,8.2,35.6,21,5.7,10,6.7,21.9,3.5,32.9l-6.6,22.7-15.4,51.9' +
    'c-.2.8-.3,1.5.2,2.1s1,1.1,1.9,1.1h53.6c5.7,0,11,1.7,16,4,13.5,6.2,22.4,19.3,23.8,34,.8,8.4-.9,16.3-4.6,23.7' +
    'l-8.2,17.8-50.5,108.9c-8.4,18.2-29.3,27.9-48.8,22.7Z" fill="#050505"></path>' +
    '<path d="M103,355c-11.8-3.1-21.4-11.1-26.8-22-5.1-10.1-6-22.6-1.5-33.1l15.3-33,17.8-37.6c.3-.7-.1-1.6-.4-2.1' +
    's-1-.9-1.9-.9h-40.8c-9,0-17.4-2.6-24.7-7.9-5-3.6-9.1-8.1-12.3-13.4-5.6-9.4-7.3-21.3-4.3-31.8l4.3-14.8L59.7,46.4' +
    'c4.3-20.8,21.7-34.8,42.9-34.2,14.7.4,28.3,8.2,35.6,21,5.7,10,6.7,21.9,3.5,32.9l-6.6,22.7-15.4,51.9' +
    'c-.2.8-.3,1.5.2,2.1s1,1.1,1.9,1.1h53.6c5.7,0,11,1.7,16,4,13.5,6.2,22.4,19.3,23.8,34,.8,8.4-.9,16.3-4.6,23.7' +
    'l-8.2,17.8-50.5,108.9c-8.4,18.2-29.3,27.9-48.8,22.7Z" fill="#3AB54A"></path>' +
    '<path d="M124.1,342.8c-11.8-3.1-21.4-11.1-26.8-22-5.1-10.1-6-22.6-1.5-33.1l15.3-33,17.8-37.6c.3-.7-.1-1.6-.4-2.1' +
    's-1-.9-1.9-.9h-40.8c-9,0-17.4-2.6-24.7-7.9-5-3.6-9.1-8.1-12.3-13.4-5.6-9.4-7.3-21.3-4.3-31.8l4.3-14.8,32.1-112' +
    'C85.2,13.5,102.6-.5,123.8,0c14.7.4,28.3,8.2,35.6,21,5.7,10,6.7,21.9,3.5,32.9l-6.6,22.7-15.4,51.9' +
    'c-.2.8-.3,1.5.2,2.1s1,1.1,1.9,1.1h53.6c5.7,0,11,1.7,16,4,13.5,6.2,22.4,19.3,23.8,34,.8,8.4-.9,16.3-4.6,23.7' +
    'l-8.2,17.8-50.5,108.9c-8.4,18.2-29.3,27.9-48.8,22.7Z" fill="#FFF204"></path></svg>';

  // No keyframes. Ten infinite CSS animations, each on its own promoted layer,
  // saturated WebKit's accelerated-animation queue ("Too many messages (129)
  // in the queue ... DrawingArea_AcceleratedAnimationDidStart"), so the
  // overlay's pixels arrived seconds late while the rest of the page painted
  // on time — the confirmation screen overtook a burst that had already run.
  // Everything now moves in the single rAF loop below instead.
  var CSS = '';

  var root = null, nodes = null, sheet = null, coinNodes = [];
  var sweepNode = null, dashNode = null, startedAt = 0, burstAt = 0;
  var value = 0, target = 0, ceiling = 0;
  var running = false, fired = false, last = 0;
  var fullHandlers = [];

  function el(style, html) {
    var d = document.createElement('div');
    d.style.cssText = style;
    if (html) d.innerHTML = html;
    return d;
  }

  function build() {
    if (root && root.firstChild) return;    // already built and populated

    if (!root) {
      root = document.createElement('div');
      root.id = 'flp-root';
    }
    root.innerHTML = '';
    root.style.cssText =
      'position:absolute;inset:0;z-index:2147483000;pointer-events:none;' +
      'display:none;overflow:hidden';

    // No backdrop at all. The radial wash behind the fox read as a dark halo
    // around it — it was a green-era glow that never suited the new accent.
    // The app's own send screen shows through underneath instead.

    // the rotating sweep and its counter-ring, driven by the loop below
    var sweep = el('position:absolute;left:50%;top:44%;width:340px;height:340px;' +
      'margin:-170px 0 0 -170px;border-radius:50%;background:conic-gradient(from 0deg,' +
      'rgba(92,212,108,.5) 0 8deg,rgba(92,212,108,0) 8deg 45deg,rgba(254,209,0,.4) 45deg 53deg,' +
      'rgba(92,212,108,0) 53deg 90deg,rgba(92,212,108,.5) 90deg 98deg,rgba(92,212,108,0) 98deg 135deg,' +
      'rgba(254,209,0,.4) 135deg 143deg,rgba(92,212,108,0) 143deg 180deg,rgba(92,212,108,.5) 180deg 188deg,' +
      'rgba(92,212,108,0) 188deg 225deg,rgba(254,209,0,.4) 225deg 233deg,rgba(92,212,108,0) 233deg 270deg,' +
      'rgba(92,212,108,.5) 270deg 278deg,rgba(92,212,108,0) 278deg 315deg,rgba(254,209,0,.4) 315deg 323deg,' +
      'rgba(92,212,108,0) 323deg 360deg);' +
      '-webkit-mask:radial-gradient(circle,transparent 0 47%,#000 48% 50%,transparent 51%);' +
      'mask:radial-gradient(circle,transparent 0 47%,#000 48% 50%,transparent 51%)');
    // Back to the smaller size, but drawn from a 288px sheet so it is sharp
    // rather than upscaled. Costs about 58MB decoded against 35MB before.
    var foxPx = 224;
    buildFox(root, foxPx);

    // A bar under the fox, so the number is visible rather than implied.
    // Orange, matching the balance pill rather than the app's green.
    var barW = Math.min(foxPx - 40, 300);
    var barWrap = el('position:absolute;left:50%;top:50%;width:' + barW + 'px;height:10px;' +
      'margin-left:' + (-barW / 2) + 'px;margin-top:' + (foxPx / 2 - 40 + 18) + 'px;' +
      'border-radius:5px;background:rgba(255,255,255,.18);overflow:hidden');
    var barFill = el('position:absolute;left:0;top:0;bottom:0;width:100%;transform-origin:0 50%;' +
      'transform:scaleX(0);border-radius:5px;background:#FFFFFF;' +
      'box-shadow:0 0 18px 2px rgba(255,255,255,.5)');
    barWrap.appendChild(barFill);
    root.appendChild(barWrap);

    var barPct = el('position:absolute;left:50%;top:50%;width:' + barW + 'px;' +
      'margin-left:' + (-barW / 2) + 'px;margin-top:' + (foxPx / 2 - 40 + 34) + 'px;' +
      'text-align:center;font-family:Sora,system-ui,-apple-system,sans-serif;' +
      'font-size:15px;font-weight:800;letter-spacing:0.04em;color:#FFFFFF');
    barPct.textContent = '0%';
    root.appendChild(barPct);

    if (DECOR_ON) root.appendChild(sweep);

    var dashRing = el('position:absolute;left:50%;top:44%;width:268px;height:268px;' +
      'margin:-134px 0 0 -134px;border-radius:50%;border:2px dashed rgba(92,212,108,.22)');
    if (DECOR_ON) root.appendChild(dashRing);

    // coins falling into the bolt, also driven by the loop
    coinNodes = [];
    for (var i = 0; i < COINS.length; i++) {
      var c = COINS[i], h = c[0] / 2;
      var coin = el('position:absolute;left:50%;top:44%;width:' + c[0] + 'px;height:' + c[0] + 'px;' +
        'margin:-' + h + 'px 0 0 -' + h + 'px;border-radius:50%;background:' + c[1] + ';opacity:0');
      coin._from = [parseFloat(c[2]), parseFloat(c[3])];
      coin._delay = parseFloat(c[4]);
      coinNodes.push(coin);
      if (DECOR_ON) root.appendChild(coin);
    }

    // the core: charge level, bolt, and the completion flash
    var core = el('position:absolute;left:50%;top:44%;width:184px;height:184px;margin:-92px 0 0 -92px;' +
      'border-radius:50%;background:linear-gradient(170deg,rgba(11,32,19,.92),rgba(4,10,6,.92));' +
      'border:2px solid rgba(92,212,108,.34);box-sizing:border-box;overflow:hidden;' +
      'box-shadow:inset 0 0 40px rgba(0,0,0,.7)');
    var fill = el('position:absolute;left:0;right:0;bottom:0;height:100%;transform-origin:50% 100%;' +
      'transform:scaleY(0);background:linear-gradient(180deg,' +
      'rgba(92,212,108,.55),rgba(92,212,108,.9) 40%,#3FBF54)');
    var flash = el('position:absolute;inset:0;background:#FFFFFF;opacity:0;pointer-events:none');
    core.appendChild(fill);
    core.appendChild(el('position:absolute;inset:0;display:flex;align-items:center;justify-content:center;' +
      'filter:drop-shadow(0 0 14px rgba(0,0,0,.5))', BOLT));
    // the percentage lives inside the circle, under the bolt — above the fill
    // so it stays readable as the charge rises past it
    var pct = el('position:absolute;left:0;right:0;bottom:10px;text-align:center;' +
      'font-family:Sora,system-ui,sans-serif;font-size:14px;font-weight:700;letter-spacing:.1em;' +
      'color:#FFFFFF;text-shadow:0 1px 3px rgba(0,0,0,.75);font-variant-numeric:tabular-nums');
    pct.textContent = '0%';
    core.appendChild(pct);
    core.appendChild(flash);
    if (DECOR_ON) root.appendChild(core);

    // completion rings and spokes, idle until the payment lands
    var burst1 = el('position:absolute;left:50%;top:44%;width:420px;height:420px;margin:-210px 0 0 -210px;' +
      'border-radius:50%;border:3px solid rgba(255,255,255,.7);opacity:0');
    var burst2 = el('position:absolute;left:50%;top:44%;width:320px;height:320px;margin:-160px 0 0 -160px;' +
      'border-radius:50%;border:2px solid rgba(92,212,108,.8);opacity:0');
    var cross = el('position:absolute;left:50%;top:44%;width:0;height:0;opacity:0',
      '<div style="position:absolute;left:-4px;top:-190px;width:8px;height:120px;' +
      'background:linear-gradient(180deg,rgba(255,255,255,0),#FFFFFF);border-radius:4px"></div>' +
      '<div style="position:absolute;left:-4px;top:70px;width:8px;height:120px;' +
      'background:linear-gradient(0deg,rgba(255,255,255,0),#FFFFFF);border-radius:4px"></div>' +
      '<div style="position:absolute;left:70px;top:-4px;width:120px;height:8px;' +
      'background:linear-gradient(90deg,rgba(255,255,255,0),#FFFFFF);border-radius:4px"></div>' +
      '<div style="position:absolute;left:-190px;top:-4px;width:120px;height:8px;' +
      'background:linear-gradient(270deg,rgba(255,255,255,0),#FFFFFF);border-radius:4px"></div>');
    if (BURST_ON) {
      root.appendChild(burst1);
      root.appendChild(burst2);
    }
    if (DECOR_ON) root.appendChild(cross);

    document.body.appendChild(root);
    sweepNode = sweep; dashNode = dashRing;
    nodes = { fill: fill, flash: flash, burst1: burst1, burst2: burst2, cross: cross, pct: pct,
              barFill: barFill, barPct: barPct };
  }

  var lastLogged = -1;

  function paint() {
    if (!nodes) return;
    var v = Math.max(0, Math.min(100, value));
    nodes.fill.style.transform = 'scaleY(' + (v / 100).toFixed(4) + ')';
    nodes.pct.textContent = Math.round(v) + '%';
    if (nodes.barFill) nodes.barFill.style.transform = 'scaleX(' + (v / 100).toFixed(4) + ')';
    if (nodes.barPct) nodes.barPct.textContent = Math.round(v) + '%';

    // log each 25% the DISPLAY actually reaches, so painted progress can be
    // compared against when the stages arrived
    var band = Math.floor(v / 25) * 25;
    if (band > lastLogged) { lastLogged = band; console.log('[foxy] painted', band + '%'); }
  }

  // the decoration: rings turning, coins falling. Driven here rather than by
  // CSS so it shares one animation frame with everything else.
  function paintDecor(t) {
    if (!root) return;
    if (sweepNode) sweepNode.style.transform = 'rotate(' + ((t / 10000) * 360 % 360) + 'deg)';
    if (dashNode) dashNode.style.transform = 'rotate(' + (-(t / 10000) * 360 % 360) + 'deg)';

    for (var i = 0; i < coinNodes.length; i++) {
      var c = coinNodes[i];
      var p = ((t / 1000) - c._delay) / 2.5;         // 2.5s cycle, staggered
      if (p < 0) { c.style.opacity = '0'; continue; }
      p = p - Math.floor(p);
      var x = c._from[0] * (1 - p), y = c._from[1] * (1 - p);
      var scale = 0.4 + (0.2 - 0.4) * p;
      var o = p < 0.14 ? (p / 0.14) : (p > 0.88 ? (1 - (p - 0.88) / 0.12) * 0.9 : 0.95);
      c.style.transform = 'translate(' + x.toFixed(1) + 'px,' + y.toFixed(1) + 'px) scale(' + scale.toFixed(3) + ')';
      c.style.opacity = o.toFixed(3);
    }
  }

  // the completion burst, also frame-driven so it cannot queue behind anything
  var FADE_MS = 500;

  /* The rings, cross and white flash are off while the fox carries the
   * arrival — they sat on top of it. The fade below stays: it is what forces
   * this layer to repaint itself away instead of being abandoned by the
   * compositor, which is the bug that left the bolt hanging over the
   * confirmation. Flip BURST_ON to bring them back. */
  var BURST_ON = false;

  /* Everything the overlay drew before the fox — the sweep, the dashed ring,
   * the orbiting coins, the filling core with its percentage — is off. The
   * elements are still built so the painters have something to write to, they
   * simply are not added to the document. Flip DECOR_ON to bring them back. */
  var DECOR_ON = false;

  function paintBurst(t) {
    if (!burstAt || !nodes || !root) return;
    var e = (t - burstAt) / 1000;

    if (!BURST_ON) {
      var f0 = 1 - (t - burstAt) / FADE_MS;
      root.style.opacity = (f0 < 0 ? 0 : f0).toFixed(3);
      return;
    }

    // The overlay fades from full to nothing across the burst. Written every
    // frame rather than left to CSS, so the layer actually repaints — hiding
    // it outright left the bolt on screen for about a second afterwards.
    var fade = 1 - (t - burstAt) / FADE_MS;
    root.style.opacity = (fade < 0 ? 0 : fade).toFixed(3);
    var ring = function (node, delay, dur) {
      var p = (e - delay) / dur;
      if (p < 0) { node.style.opacity = '0'; return; }
      if (p > 1) { node.style.opacity = '0'; return; }
      node.style.opacity = (1 - p).toFixed(3);
      node.style.transform = 'scale(' + (1 + 0.7 * p).toFixed(3) + ')';
    };
    ring(nodes.burst1, 0, FADE_MS / 1000);
    ring(nodes.burst2, 0.05, FADE_MS / 1000);
    ring(nodes.cross, 0.03, FADE_MS / 1000);
    var f = e / (FADE_MS / 1000);
    nodes.flash.style.opacity = f >= 1 ? '0' : (0.95 * (1 - f)).toFixed(3);
  }

  function finish() {
    if (fired || !nodes) return;
    fired = true;
    console.log('[foxy] progress reached 100');
    burstAt = performance.now();
    var handlers = fullHandlers.slice();
    fullHandlers = [];
    for (var i = 0; i < handlers.length; i++) {
      try { handlers[i](); } catch (e) {}
    }
  }

  /* ---- the fox --------------------------------------------------------
   *
   * A 174-frame run scrubbed by progress rather than played: frames 1-150 map
   * to 0-100% at 1.5 frames a percent, and 151-174 run out afterwards as the
   * finish. A GIF cannot do this — it plays at its own rate and ignores us —
   * so the frames ship as a sprite sheet and this moves the window over it.
   */
  var FOX_COLS = 14, FOX_ROWS = 13, FOX_FRAMES = 174;
  var FOX_AT_FULL = 150;               // the frame that means 100%
  var FOX_TAIL_MS = 20;                // per frame once it is running out
  var fox = null, foxTailFrom = 0, foxTailing = false;

  function buildFox(parent, px) {
    fox = document.createElement('div');
    fox.style.cssText =
      'position:absolute;left:50%;top:50%;width:' + px + 'px;height:' + px + 'px;' +
      'margin-left:' + (-px / 2) + 'px;margin-top:' + (-px / 2 - 40) + 'px;' +
      'border-radius:26px;overflow:hidden;background-image:url("foxy-sheet.webp");' +
      'background-repeat:no-repeat;background-size:' + (px * FOX_COLS) + 'px ' +
      (px * FOX_ROWS) + 'px;image-rendering:auto;will-change:background-position';
    parent.appendChild(fox);
    foxTo(0);
    return fox;
  }

  /* Put the window on frame i (0-based). */
  function foxTo(i) {
    if (!fox) return;
    var n = Math.max(0, Math.min(FOX_FRAMES - 1, Math.round(i)));
    var px = fox.offsetWidth || parseFloat(fox.style.width);
    fox.style.backgroundPosition =
      (-(n % FOX_COLS) * px) + 'px ' + (-Math.floor(n / FOX_COLS) * px) + 'px';
  }

  /* Scrub from the percentage actually on screen, so the fox and the bar
   * always agree. */
  function foxFromValue(v) {
    if (foxTailing) return;
    foxTo(Math.min(FOX_AT_FULL - 2, Math.floor(v * 1.5)));
  }

  /* The run-out: everything past frame 150, once we have arrived. */
  function foxTail(now) {
    if (!foxTailing) return true;
    var i = FOX_AT_FULL - 1 + Math.floor((now - foxTailFrom) / FOX_TAIL_MS);
    foxTo(i);
    return i >= FOX_FRAMES - 1;
  }

  function frame(now) {
    var dt = Math.min(100, now - last) / 1000;
    last = now;

    if (value < target) {
      // completion sprints home; the stages in between amble
      var rate = target >= 100
        ? Math.max(45, (target - value) * 6)
        : Math.max(7, (target - value) * 1.8);
      value = Math.min(target, value + rate * dt);
    } else if (value < ceiling) {
      value = Math.min(ceiling, value + 1 * dt);   // 1% a second while waiting
    }

    paint();
    foxFromValue(value);
    foxTail(now);
    paintDecor(now - startedAt);
    paintBurst(now);

    if (target >= 100 && value >= 99.6 && !fired) {
      value = 100;
      paint();
      foxTo(FOX_AT_FULL - 1);
      foxTailing = true;
      foxTailFrom = now;
      finish();
    }
    // keep going through the burst and the hold; end() stops us
    if (running) requestAnimationFrame(frame);
  }

  var FoxyProgress = {
    begin: function () {
      foxTailing = false;
      foxTailFrom = 0;
      build();   // rebuilds if the last run tore it down
      value = 0; target = 0; ceiling = 0; running = false; fired = false;
      lastLogged = -1;
      fullHandlers = [];
      console.log('[foxy] overlay begin');
      nodes.flash.style.opacity = '0';
      nodes.burst1.style.opacity = '0';
      nodes.burst2.style.opacity = '0';
      nodes.cross.style.opacity = '0';
      startedAt = performance.now();
      burstAt = 0;
      paint();
      root.style.zIndex = '2147483000';
      root.style.opacity = '1';
      root.style.visibility = 'visible';
      root.style.display = 'block';
      if (!running) { running = true; last = performance.now(); requestAnimationFrame(frame); }
      this.stage('start');
    },

    stage: function (name) {
      if (!root) this.begin();
      var pct = LADDER[name];
      if (pct == null) return;
      console.log('[foxy] stage', name, '-> target', pct, '(showing', Math.round(value) + '%)');
      target = pct;
      var steps = [];
      for (var k in LADDER) if (Object.prototype.hasOwnProperty.call(LADDER, k)) steps.push(LADDER[k]);
      steps.sort(function (a, b) { return a - b; });
      var next = null;
      for (var i = 0; i < steps.length; i++) if (steps[i] > pct) { next = steps[i]; break; }
      // stop a little short of the next milestone so it can only go forwards
      ceiling = Math.max(pct, (next == null ? 100 : next) - 3);
      if (!running) { running = true; last = performance.now(); requestAnimationFrame(frame); }
    },

    // fires once the finish animation starts; if it has already fired, runs now
    whenFull: function (fn) {
      if (typeof fn !== 'function') return;
      if (fired) { try { fn(); } catch (e) {} return; }
      fullHandlers.push(fn);
    },

    // One call that owns the whole ending: drive to 100, fire the burst, hold,
    // fade out, and only then hand back. The app supplies a callback rather
    // than running its own timer, so the confirmation cannot arrive early.
    complete: function (done, holdMs) {
      var self = this;
      var hold = typeof holdMs === 'number' ? holdMs : FADE_MS;
      self.stage('done');
      self.whenFull(function () {
        console.log('[foxy] burst fired, holding', hold, 'ms');
        setTimeout(function () {
          // by now the burst has faded to nothing on screen
          console.log('[foxy] burst faded out, showing confirmation');
          if (typeof done === 'function') done();
          self.end();
        }, hold);
      });
    },

    end: function () {
      console.log('[foxy] overlay end (removed)');
      running = false;
      if (!root) return;
      // Taken out of the document, not just hidden. display:none left a frame
      // where the burst was still composited over the confirmation.
      // Drop BEHIND the app first. WebKit flushes this overlay's layer about a
      // second late, so hiding it alone leaves the bolt visible over the
      // confirmation. Sent to the back, those stale pixels sit under the
      // confirmation's opaque background and are never seen.
      root.style.zIndex = '-1';
      root.style.opacity = '0';
      var kids = root.querySelectorAll ? root.querySelectorAll('*') : [];
      for (var k = 0; k < kids.length; k++) {
        kids[k].style.opacity = '0';
        kids[k].style.transform = 'scale(0)';
      }
      root.innerHTML = '';            // repaints the layer as empty
      root.style.display = 'none';
      void root.offsetHeight;         // flush it
      nodes = null; coinNodes = [];
      sweepNode = null; dashNode = null; burstAt = 0;
      console.log('[foxy] overlay emptied');
    },

    visible: function () {
      return !!(root && root.style.display !== 'none');
    },

    // for the console: watch it run without a payment
    demo: function () {
      var self = this;
      self.begin();
      var script = [[700, 'resolving'], [1900, 'quote'], [3200, 'melting'], [3400, 'inflight'], [6200, 'done']];
      script.forEach(function (s) { setTimeout(function () { self.stage(s[1]); }, s[0]); });
      self.whenFull(function () { setTimeout(function () { self.end(); }, 1200); });
    }
  };

  window.FoxyProgress = FoxyProgress;
})();
