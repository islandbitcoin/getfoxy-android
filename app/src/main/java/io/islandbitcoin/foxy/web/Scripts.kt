package io.islandbitcoin.foxy.web

/**
 * The scripts installed at document start, before any page script runs
 * (Foxy/FoxyWebView.swift:137-203, :378; docs/BRIDGE.md §1). The page itself is
 * never edited: these are the only native words in the page.
 */
object Scripts {
    /** The name of the object addWebMessageListener puts in the page. */
    const val NATIVE_OBJECT = "foxyNative"

    /**
     * The shim: `window.webkit.messageHandlers.foxy.postMessage(m)` posts
     * `JSON.stringify(m)` to native, so core/web runs unchanged. Locked, so a
     * later script cannot swap it.
     */
    val shim = """
        (function () {
          if (window.top !== window) return;
          var n = window.$NATIVE_OBJECT;
          if (!n || (window.webkit && window.webkit.messageHandlers)) return;
          var foxy = Object.freeze({ postMessage: function (m) {
            var s = JSON.stringify(m);
            if (typeof s === 'string') n.postMessage(s);
          } });
          Object.defineProperty(window, 'webkit', {
            value: Object.freeze({ messageHandlers: Object.freeze({ foxy: foxy }) }),
            writable: false, configurable: false
          });
        }());
    """.trimIndent()

    /**
     * No WebRTC in any frame: its STUN traffic is UDP no request block sees
     * (FoxyWebView.swift `noWebRTC`). Every RTC* constructor is defined as
     * undefined, non-writable and non-configurable before any page script runs.
     *
     * Android's document-start scripts do not reach a same-origin `about:blank`
     * or `srcdoc` frame (checked on the Pixel, PageBootTest), where iOS's do. So
     * the same removal is carried into every child frame from its parent: when
     * its window or document is read through `contentWindow`/`contentDocument`,
     * and when it fires `load`, which an `about:blank` frame does inside the
     * appendChild that adds it, before the script that added it can reach
     * `frames[0]`. Each child gets the same guard for its own children.
     */
    val noWebRTC = """
        (function () {
          var names = ['RTCPeerConnection', 'webkitRTCPeerConnection', 'RTCDataChannel', 'RTCIceCandidate',
            'RTCSessionDescription', 'RTCRtpSender', 'RTCRtpReceiver', 'RTCRtpTransceiver',
            'RTCIceTransport', 'RTCDtlsTransport', 'RTCSctpTransport', 'RTCCertificate',
            'RTCPeerConnectionIceEvent', 'RTCDataChannelEvent', 'RTCTrackEvent'];
          var guarded = new WeakSet();
          function strip(w) {
            if (!w) return;
            try {
              names.forEach(function (name) {
                try { Object.defineProperty(w, name, { value: undefined, writable: false, configurable: false }); } catch (e) {}
              });
              guard(w);
            } catch (e) {}
          }
          function guard(w) {
            var doc = w.document;
            if (!doc || guarded.has(doc)) return;
            guarded.add(doc);
            ['HTMLIFrameElement', 'HTMLFrameElement', 'HTMLObjectElement'].forEach(function (c) {
              var proto = w[c] && w[c].prototype;
              if (!proto) return;
              var win = Object.getOwnPropertyDescriptor(proto, 'contentWindow');
              if (win && win.get && win.configurable) {
                Object.defineProperty(proto, 'contentWindow', { configurable: false, enumerable: win.enumerable,
                  get: function () { var x = win.get.call(this); strip(x); return x; } });
              }
              var d = Object.getOwnPropertyDescriptor(proto, 'contentDocument');
              if (d && d.get && d.configurable) {
                Object.defineProperty(proto, 'contentDocument', { configurable: false, enumerable: d.enumerable,
                  get: function () { var x = d.get.call(this); if (x) strip(x.defaultView); return x; } });
              }
            });
            doc.addEventListener('load', function (e) {
              var t = e.target;
              if (t && (t.tagName === 'IFRAME' || t.tagName === 'FRAME' || t.tagName === 'OBJECT')) {
                try { strip(t.contentWindow); } catch (x) {}
              }
            }, true);
          }
          strip(window);
        }());
    """.trimIndent()

    /**
     * No long-press callout or text selection outside text fields: Translate,
     * Search and the like hand selected text to services on the open network.
     * Document end on iOS; here it waits for DOMContentLoaded so there is a head.
     */
    val noCallout = """
        (function () {
          if (window.top !== window) return;
          function add() {
            var s = document.createElement('style');
            s.textContent = 'html{-webkit-touch-callout:none;-webkit-user-select:none;user-select:none}'
              + 'input,textarea,[contenteditable]{-webkit-user-select:text;user-select:text}';
            (document.head || document.documentElement).appendChild(s);
          }
          if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', add, { once: true });
          else add();
        }());
    """.trimIndent()

    /** The phone's 12/24-hour setting. */
    fun clock24(is24: Boolean) = "if (window.top === window) window.__foxyClock24 = $is24;"

    /** Debug builds: the page's synchronous debug flag. */
    const val foxyDebug = "if (window.top === window) window.FOXY_DEBUG = true;"

    /** Debug builds launched with the FOXY_SHOW_WARNING extra: the first-launch warning again. */
    const val showWarning = "if (window.top === window) window.FOXY_SHOW_WARNING = true;"

    /** Release builds: the page's console says nothing but errors. */
    val consoleSilence = """
        (function () {
          if (window.top !== window) return;
          var noop = function () {};
          console.log = noop;
          console.warn = noop;
          console.info = noop;
          console.debug = noop;
        }());
    """.trimIndent()

    /** sha256(core/SHA256SUMS), hex only, for the drawer (S26). */
    fun pageHash(hash: String) = "if (window.top === window) window.__foxyPageHash = '${hash.filter { it in '0'..'9' || it in 'a'..'f' }}';"
}
