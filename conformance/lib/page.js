'use strict';
/* page.js — core/web/index.html, as it ships, in jsdom, served the way the
 * Android app will serve it, with every road to the network watched.
 *
 * The page is loaded at https://appassets.androidplatform.net/assets/web/index.html,
 * the origin androidx.webkit's WebViewAssetLoader gives it on the phone
 * (docs/ANDROID-MAPPING.md). Its own loader then runs exactly as it does on a
 * phone: it unpacks the gzip'd assets in its manifest into blob: URLs, rebuilds
 * the document from its template, and loads qrcode.js, cashu-ts.js, the
 * progress overlays, foxy-tor-gate.js and foxy-wallet.js as relative scripts.
 * Nothing here edits a byte of core/web; what jsdom lacks (blob URLs,
 * DecompressionStream, layout observers) is supplied around the page.
 *
 * The network sentinel. The invariant (docs/ROUTE.md) is that the page makes
 * no request of its own: every byte it wants from outside goes over the bridge
 * as a `mintRequest`, `price`, `candles`, ... and native decides the route.
 * Here, as with Android's shouldInterceptRequest, only two kinds of URL are
 * served — the asset origin's /assets/web/ files and blob: URLs the page itself
 * minted — and everything else is a violation, recorded and refused:
 *
 *   - every subresource jsdom would fetch (scripts, iframes, stylesheets, XHR),
 *     through a request interceptor, with a dispatcher behind it that refuses
 *     anything the interceptor let past;
 *   - fetch, XMLHttpRequest, WebSocket, EventSource, navigator.sendBeacon and
 *     RTCPeerConnection, replaced before the page's first script;
 *   - <img>, <link>, <source>, <video>, <audio>, <iframe> and the rest, whose
 *     URLs jsdom does not fetch, and url(...) in <style> and in style
 *     attributes: every such URL in the document, resolved against the page
 *     (so `//host/x` counts), is checked when a run ends (`domUrls`), and as it
 *     is set, by a MutationObserver.
 */
const fs = require('fs');
const path = require('path');
const { JSDOM, VirtualConsole, requestInterceptor } = require('jsdom');

const ROOT = path.join(__dirname, '..', '..');
const WEB = path.join(ROOT, 'core', 'web');
const ORIGIN = 'https://appassets.androidplatform.net';
const BASE = ORIGIN + '/assets/web/';
const INDEX = BASE + 'index.html';

const TYPES = {
  '.html': 'text/html', '.js': 'text/javascript', '.json': 'application/json', '.md': 'text/markdown',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.webp': 'image/webp', '.mp4': 'video/mp4',
  '.woff2': 'font/woff2', '.svg': 'image/svg+xml',
};

/* Is this a URL the asset loader serves? Only files under core/web, by a path
 * that stays inside it. */
function assetFile(url) {
  if (!url.startsWith(BASE)) return null;
  const rel = decodeURIComponent(url.slice(BASE.length).split(/[?#]/)[0]);
  const file = path.resolve(WEB, rel);
  if (!file.startsWith(WEB + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) return null;
  return file;
}

/* A URL in an attribute that would leave the phone. It is resolved against the
 * page first, as the WebView resolves it, so a protocol-relative
 * `//cdn.example/x` (https://cdn.example at this origin) is caught along with
 * one that names its scheme. */
const REMOTE = new Set(['http:', 'https:', 'ws:', 'wss:', 'ftp:']);
function leaves(u) {
  const s = String(u || '').trim();
  if (!s) return false;
  let url;
  try { url = new URL(s, BASE); } catch (e) { return false; }
  return REMOTE.has(url.protocol) && url.origin !== ORIGIN;
}

/* Every url(...) in a stylesheet or a style attribute that would leave the phone. */
const CSS_URL = /url\(\s*(['"]?)([^'")]*)\1\s*\)/gi;
function cssLeaves(text) {
  return [...String(text || '').matchAll(CSS_URL)].map((m) => m[2].trim()).filter(leaves);
}

const URL_ATTRS = ['src', 'href', 'srcset', 'poster', 'action', 'data', 'background', 'xlink:href', 'formaction', 'ping'];
const WATCHED = [...URL_ATTRS, 'style'];

/**
 * Load the page.
 *   o.postMessage(message)  the native side, called with every message the
 *                           page posts to window.webkit.messageHandlers.foxy
 *   o.storage               localStorage entries to start with
 *   o.beforeScripts(w)      runs before the page's first script
 * Returns { window, violations, logs, close, ready(), logic() }.
 */
function loadPage(o) {
  const opts = o || {};
  const violations = [];
  const logs = [];
  const blobs = new Map();
  let blobN = 0;
  const violate = (kind, detail) => violations.push({ kind, detail: String(detail).slice(0, 300) });

  const vc = new VirtualConsole();
  vc.on('jsdomError', (e) => {
    const msg = String((e && e.message) || e);
    // what jsdom does not implement is not the page's fault (media playback, layout)
    if (/Not implemented/.test(msg)) return;
    logs.push(['jsdomError', msg.slice(0, 500)]);
  });
  for (const k of ['log', 'warn', 'error', 'info']) vc.on(k, (...a) => logs.push([k, a.map(String).join(' ').slice(0, 500)]));

  const interceptor = requestInterceptor(async (request) => {
    const url = request.url;
    if (url.startsWith('blob:')) {
      const b = blobs.get(url);
      if (b) return new Response(await b.arrayBuffer(), { headers: { 'Content-Type': b.type || 'text/javascript' } });
      violate('blob', 'an unknown blob URL: ' + url);
      return new Response('', { status: 404 });
    }
    const file = assetFile(url);
    if (file) {
      return new Response(fs.readFileSync(file), { headers: { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' } });
    }
    if (url.startsWith(ORIGIN + '/')) {
      // the asset origin itself, but no such file: the loader answers 404 and nothing leaves
      return new Response('', { status: 404 });
    }
    violate('subresource', request.method + ' ' + url);
    return new Response('', { status: 599, statusText: 'refused by the conformance network sentinel' });
  });

  /* Behind the interceptor: anything it did not answer would reach the real
   * network through undici's default dispatcher. It never should; this makes
   * sure it cannot. */
  const refuseAll = {
    dispatch(o2, handler) {
      violate('dispatcher', (o2.method || 'GET') + ' ' + (o2.origin || '') + (o2.path || ''));
      handler.onError(new Error('refused by the conformance network sentinel'));
      return false;
    },
    close() { return Promise.resolve(); },
    destroy() { return Promise.resolve(); },
    on() { return this; }, once() { return this; }, off() { return this; }, emit() { return false; },
  };

  const html = fs.readFileSync(path.join(WEB, 'index.html'), 'utf8');
  let logic = null;

  const dom = new JSDOM(html, {
    url: INDEX,
    runScripts: 'dangerously',
    pretendToBeVisual: true,
    virtualConsole: vc,
    resources: { interceptors: [interceptor], dispatcher: refuseAll },
    beforeParse(w) {
      // what jsdom does not have, and a phone's WebView does
      w.URL.createObjectURL = (blob) => { const u = 'blob:' + ORIGIN + '/' + (++blobN); blobs.set(u, blob); return u; };
      w.URL.revokeObjectURL = (u) => { blobs.delete(u); };
      w.DecompressionStream = globalThis.DecompressionStream;
      w.CompressionStream = globalThis.CompressionStream;
      if (!w.TextEncoder) w.TextEncoder = globalThis.TextEncoder;
      if (!w.TextDecoder) w.TextDecoder = globalThis.TextDecoder;
      if (!w.crypto || !w.crypto.subtle) Object.defineProperty(w, 'crypto', { value: globalThis.crypto, configurable: true });
      w.matchMedia = () => ({ matches: false, media: '', addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} });
      w.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
      w.IntersectionObserver = class { observe() {} unobserve() {} disconnect() {} };
      w.HTMLCanvasElement.prototype.getContext = () => null;
      w.HTMLMediaElement.prototype.play = function () { return Promise.resolve(); };
      w.HTMLMediaElement.prototype.pause = function () {};
      w.scrollTo = () => {};
      w.Element.prototype.scrollIntoView = function () {};

      // ---- the network sentinel: nothing in the page reaches out by itself ----
      w.fetch = (input) => {
        violate('fetch', typeof input === 'string' ? input : (input && input.url) || String(input));
        return Promise.reject(new TypeError('Failed to fetch (the page has no network)'));
      };
      const XO = w.XMLHttpRequest.prototype.open;
      w.XMLHttpRequest.prototype.open = function (method, url) {
        const abs = new w.URL(String(url), w.location.href).href;
        if (!abs.startsWith(BASE) && !abs.startsWith('blob:')) violate('xhr', method + ' ' + abs);
        return XO.apply(this, arguments);
      };
      w.WebSocket = function (url) { violate('websocket', url); throw new w.DOMException('refused', 'SecurityError'); };
      w.EventSource = function (url) { violate('eventsource', url); throw new w.DOMException('refused', 'SecurityError'); };
      Object.defineProperty(w.navigator, 'sendBeacon', { value: (url) => { violate('beacon', url); return false; }, configurable: true });
      // the Android host removes WebRTC before the page runs, as FoxyWebView.noWebRTC does on iOS
      w.RTCPeerConnection = function () { violate('webrtc', 'RTCPeerConnection'); throw new Error('no WebRTC'); };

      // the native side
      if (opts.postMessage) {
        w.webkit = { messageHandlers: { foxy: { postMessage: (m) => opts.postMessage(m, w) } } };
      }
      if (opts.storage) for (const [k, v] of Object.entries(opts.storage)) w.localStorage.setItem(k, v);

      /* The app's component instance, for flows that act as a person would
       * (copySecret, haptic). The page defines __foxyComponentFactory from a
       * blob script; wrapping it as it is assigned captures the instance the
       * way the iOS suite's mount() does, without touching the page. */
      let factory;
      Object.defineProperty(w, '__foxyComponentFactory', {
        configurable: true,
        get() { return factory; },
        set(f) {
          factory = typeof f !== 'function' ? f : function (a, b, R) {
            const C = f(a, b, R);
            return C ? class extends C { constructor(p) { super(p); if (!logic) logic = this; } } : C;
          };
        },
      });

      if (opts.beforeScripts) opts.beforeScripts(w);
    },
  });

  const w = dom.window;
  const scanUrls = (root) => {
    const all = root.querySelectorAll ? root.querySelectorAll('*') : [];
    for (const el of [root, ...all]) {
      if (!el.getAttribute) continue;
      for (const a of URL_ATTRS) {
        const v = el.getAttribute(a);
        if (!v) continue;
        for (const part of a === 'srcset' ? v.split(',').map((x) => x.trim().split(/\s+/)[0]) : [v]) {
          if (leaves(part)) violate('dom', '<' + el.localName + ' ' + a + '="' + part + '">');
        }
      }
      for (const u of cssLeaves(el.getAttribute('style'))) violate('dom', '<' + el.localName + ' style="url(' + u + ')">');
      if (el.localName === 'style') for (const u of cssLeaves(el.textContent)) violate('dom', '<style> with a remote url(' + u + ')');
    }
  };
  const watcher = new w.MutationObserver((records) => {
    for (const r of records) {
      if (r.type === 'attributes' && r.target.getAttribute) {
        const v = r.target.getAttribute(r.attributeName);
        if (r.attributeName === 'style') {
          for (const u of cssLeaves(v)) violate('dom', '<' + r.target.localName + ' style="url(' + u + ')">');
        } else if (URL_ATTRS.includes(r.attributeName) && leaves(v)) {
          violate('dom', '<' + r.target.localName + ' ' + r.attributeName + '="' + v + '">');
        }
      }
      for (const n of r.addedNodes || []) if (n.nodeType === 1) scanUrls(n);
    }
  });
  watcher.observe(w.document, { subtree: true, childList: true, attributes: true, attributeFilter: WATCHED });

  /* The page has booted when the wallet and the gate are both defined. */
  function ready(ms) {
    const until = Date.now() + (ms || 15000);
    return new Promise((ok, no) => {
      const look = () => {
        if (w.FoxyWallet && w.FoxyGate && logic) return ok();
        if (Date.now() > until) return no(new Error('the page did not boot: FoxyWallet ' + typeof w.FoxyWallet + ', FoxyGate ' + typeof w.FoxyGate + ', app ' + !!logic + '\n' + logs.slice(-15).map((l) => l.join(' ')).join('\n')));
        setTimeout(look, 25);
      };
      look();
    });
  }

  return {
    window: w,
    violations,
    logs,
    ready,
    logic: () => logic,
    /* Everything still in the document that would leave the phone, and the run over. */
    domUrls: () => scanUrls(w.document.documentElement),
    close() { try { watcher.disconnect(); } catch (e) {} try { w.close(); } catch (e) {} },
  };
}

module.exports = { loadPage, ORIGIN, BASE, INDEX, WEB, ROOT, leaves, cssLeaves };
