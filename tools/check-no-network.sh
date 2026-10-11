#!/usr/bin/env bash
# check-no-network.sh — fail if any code under app/src can reach the network
# other than through network/Route.kt (docs/ROUTE.md §5, SECURITY-INVARIANTS.md S9).
#
# Route.kt is the one place a client may be built (OkHttp, P2). Nothing else may
# open a connection, a socket, a download or a DNS lookup, and no WebView may load
# anything but the asset URL. The one exception is the instrumented net-block
# test's loopback listener (in the test package's own process), which must
# record nothing (S8).
#
#   tools/check-no-network.sh            check app/src
#   tools/check-no-network.sh <dir>      check another tree (the self-test uses this)
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
src="${1:-$root/app/src}"
route='main/java/io/islandbitcoin/foxy/network/Route.kt'
listener='androidTest/java/io/islandbitcoin/foxy/ListenerService.java'

if [ ! -d "$src" ]; then
  echo "check-no-network: no such folder: $src" >&2
  exit 2
fi

fail=0
report() {
  echo "check-no-network: $1" >&2
  echo "$2" | sed 's/^/    /' >&2
  fail=1
}

# rule <name> <extended regex> [<path suffix allowed to match> ...]
rule() {
  local name="$1" pattern="$2"
  shift 2
  local hits
  hits="$(grep -rnE --include='*.kt' --include='*.java' --include='*.kts' -e "$pattern" "$src" || true)"
  local a
  for a in "$@"; do hits="$(printf '%s\n' "$hits" | grep -v -F "$a:" || true)"; done
  hits="$(printf '%s' "$hits" | sed '/^$/d')"
  if [ -n "$hits" ]; then report "$name" "$hits"; fi
}

rule "HttpURLConnection" 'Http(s)?URLConnection'
rule "URL.openStream / openConnection" '\.openStream\(|\.openConnection\(|URLConnection'
rule "java.net.URL" 'import java\.net\.URL($|[^A-Za-z])'
rule "DownloadManager" 'DownloadManager'
rule "Cronet" '[Cc]ronet|org\.chromium\.net'
rule "Volley" '[Vv]olley'
rule "Ktor" 'io\.ktor|[Kk]tor[A-Z]'
rule "OkHttp outside Route.kt" 'okhttp3|OkHttp' "$route"
rule "java.net.http" 'java\.net\.http'
rule "InetAddress" 'InetAddress' "$listener"
# a JavaScript `new WebSocket(` inside a test's page script is the page's attempt, not a socket of the app's
rule "sockets" '(^|[^A-Za-z])(Server|SSL)?Socket\(|DatagramSocket|SocketChannel|SocketFactory' "$listener"

# loadUrl with anything other than the asset page
loads="$(grep -rnE --include='*.kt' --include='*.java' 'loadUrl\(|loadDataWithBaseURL\(|postUrl\(' "$src" | grep -vE 'loadUrl\(PAGE_URL\)' || true)"
if [ -n "$loads" ]; then report "a WebView load other than loadUrl(PAGE_URL)" "$loads"; fi

if [ "$fail" -ne 0 ]; then
  echo "check-no-network: FAILED — the only network code allowed is in app/src/$route" >&2
  exit 1
fi
echo "check-no-network: ok ($src)"
