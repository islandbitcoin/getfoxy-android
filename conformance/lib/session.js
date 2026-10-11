'use strict';
/* session.js — the page and its fake native side, together, as a phone runs them.
 *
 *   const s = await boot({ seed: true, tor: 'up' });
 *   await s.settle();               // let the page finish what it started
 *   s.host.asked                    // what it asked for
 *   s.violations()                  // the network sentinel's and the host's, together
 *   s.close();
 *
 * Native's first push is the route: iOS pushes _privacy from startTor()
 * (FoxyBridge.swift) before the page has loaded, and the page also asks
 * `privacy` itself. Here the push comes as soon as the wallet exists, which is
 * the earliest a page can hear it.
 */
const { loadPage } = require('./page');
const { makeHost, snapshot } = require('./host');
const { fakeMint } = require('./mint');

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

async function boot(o) {
  const opts = o || {};
  const host = makeHost(opts);
  const page = loadPage({
    storage: opts.storage,
    postMessage: (m, w) => host.postMessage(m, w),
    beforeScripts: (w) => {
      host.window = w;
      // the uptime native pushes on didFinish (FoxyWebView.pushUptime)
      w.__foxyUptime = { s: 1000, p: 0 };
      w.__foxyClock24 = true;
      w.__foxyPageHash = opts.pageHash || '';
      if (opts.beforeScripts) opts.beforeScripts(w);
    },
  });
  host.window = page.window;
  await page.ready(opts.bootMs || 20000);
  const w = page.window;
  // the test hooks the iOS harness sets (tests/harness.js load): nothing fetched or swept unasked
  const W = w.FoxyWallet;
  if (opts.quiet !== false) {
    W._spareOff = true;
    W._headersOff = true;
    W._sweepPause = [0, 0];
    W._routeWaitMs = 0;
    W._keysetCheckDelay = [0, 0];
  }
  if (w.CashuTS && w.CashuTS.createNewMintKeys) host.mint = fakeMint(w);
  host.push('_privacy', snapshot(host.state));
  const s = {
    page, host, window: w, W,
    logic: () => page.logic(),
    settle: (ms) => wait(ms === undefined ? 400 : ms),
    torUp() { host.state.tor = 'up'; host.state.everUp = true; host.push('_privacy', snapshot(host.state)); },
    torFailed() { host.state.tor = 'failed'; host.push('_privacy', snapshot(host.state)); },
    violations() { page.domUrls(); return [...page.violations, ...host.violations]; },
    close() { page.close(); },
  };
  return s;
}

module.exports = { boot, wait };
