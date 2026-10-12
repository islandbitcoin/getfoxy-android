#!/usr/bin/env node
'use strict';
/* cdp-eval.js — evaluate a script in Foxy's page on a phone or an emulator,
 * through the debug build's WebView remote debugging (docs/DEVICE-TESTS.md).
 *
 *   adb forward tcp:9333 localabstract:webview_devtools_remote_$(adb shell pidof io.islandbitcoin.foxy)
 *   node tools/cdp-eval.js 9333 'await FoxyWallet.connect("https://mint.minibits.cash/Bitcoin"); return FoxyWallet.privacy()'
 *
 * The mints are real: a connect moves nothing, but a mint or a melt moves real
 * sats and follows docs/DEVICE-TESTS.md "Real money".
 *
 * The script is an async function body; what it returns is printed as JSON.
 * A debug build only: release builds turn WebView debugging off.
 */
async function main() {
  const [port, body, timeoutArg] = process.argv.slice(2);
  if (!port || !body) {
    process.stderr.write('usage: cdp-eval.js <port> <async function body> [timeout ms]\n');
    return 2;
  }
  const pages = await (await fetch('http://127.0.0.1:' + port + '/json')).json();
  const page = pages.find((p) => p.type === 'page' && /appassets\.androidplatform\.net/.test(p.url));
  if (!page) {
    process.stderr.write('cdp-eval: no Foxy page on that port\n');
    return 2;
  }
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((ok, fail) => { ws.onopen = ok; ws.onerror = () => fail(new Error('no connection')); });
  const expression = '(async function () { ' + body + ' })().then(function (r) { return JSON.stringify(r === undefined ? null : r); }, function (e) { return JSON.stringify({ threw: String(e && e.message || e) }); })';
  const answer = await new Promise((ok, fail) => {
    const t = setTimeout(() => fail(new Error('no answer in time')), Number(timeoutArg) || 180000);
    ws.onmessage = (m) => {
      const msg = JSON.parse(m.data);
      if (msg.id !== 1) return;
      clearTimeout(t);
      ok(msg);
    };
    ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression, awaitPromise: true, returnByValue: true } }));
  });
  ws.close();
  const v = answer.result && answer.result.result && answer.result.result.value;
  process.stdout.write((v === undefined ? JSON.stringify(answer) : v) + '\n');
  return v && /^\{"threw"/.test(v) ? 1 : 0;
}

main().then((c) => { process.exitCode = c; }, (e) => { process.stderr.write('cdp-eval: ' + e.message + '\n'); process.exitCode = 2; });
