#!/usr/bin/env bash
# check-no-network.sh — fail if any code under app/src can reach the network
# other than through network/ (docs/ROUTE.md §5, SECURITY-INVARIANTS.md S9).
#
# network/ is the one place the HTTP client lives (OkHttp: Route.kt and
# RouteHttp.kt build it; RouteUrl lets the bridge carry a URL without it), and
# network/Socks5SocketFactory.kt the one place a socket is opened or an
# InetAddress made (to Tor's SOCKS port on 127.0.0.1, and the name-keeping
# placeholder OkHttp is handed; it never looks a name up), and
# network/OnionInbox.kt the one place a socket is listened on (the onion
# inbox's, bound to 127.0.0.1, which only Tor's onion service forwards to from
# outside the phone; it dials nothing and names no InetAddress; checked below
# line by line: every ServerSocket( there binds Socks5SocketFactory.TOR_HOST). Nothing else may
# open a connection, a socket, a download or a DNS lookup, and no WebView may
# load anything but the asset URL. getByName and getAllByName are refused
# everywhere, network/ included. The exceptions: the instrumented net-block
# test's loopback listener (in the test package's own process), which must
# record nothing (S8), and the JVM tests of network/ (a fake SOCKS server and
# MockWebServer on loopback), which never ship. And, for the card (ENG-667):
# the debug build's card simulator link, debug/.../flashcard/SimCardLink.kt,
# which reaches the applet in jCardSim on the loopback address and nowhere
# else (checked below line by line: one unconnected Socket(), connected only to
# InetAddress.getLoopbackAddress()), never in main/ or release/, so a release
# build has no socket outside network/; and the debug JVM tests of flashcard/ (src/testDebug) (a fake
# card server, and the real one's control port, on loopback), which never ship.
#
#   tools/check-no-network.sh            check app/src
#   tools/check-no-network.sh <dir>      check another tree (the self-test uses this)
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
src="${1:-$root/app/src}"
route='main/java/io/islandbitcoin/foxy/network/Route.kt'
network='main/java/io/islandbitcoin/foxy/network/'
socks='main/java/io/islandbitcoin/foxy/network/Socks5SocketFactory.kt'
inbox='main/java/io/islandbitcoin/foxy/network/OnionInbox.kt'
networkTests='test/java/io/islandbitcoin/foxy/network/'
listener='androidTest/java/io/islandbitcoin/foxy/ListenerService.java'
cardsim='debug/java/io/islandbitcoin/foxy/flashcard/SimCardLink.kt'
cardTests='testDebug/java/io/islandbitcoin/foxy/flashcard/'

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

# rule <name> <extended regex> [<allowed> ...]
# An allowed entry is a file (a path suffix under app/src) or, ending in '/', a
# folder; it is matched against the file's path only, never the line's text.
rule() {
  local name="$1" pattern="$2"
  shift 2
  local hits
  hits="$(grep -rnE --include='*.kt' --include='*.java' --include='*.kts' -e "$pattern" "$src" || true)"
  local a
  for a in "$@"; do
    hits="$(printf '%s\n' "$hits" | awk -v a="$a" '{
      path = $0; sub(/:[0-9]+:.*$/, "", path)
      if (substr(a, length(a)) == "/") { if (index(path, "/" a) > 0) next }
      else if (length(path) >= length(a) + 1 && substr(path, length(path) - length(a)) == "/" a) next
      print }' || true)"
  done
  hits="$(printf '%s' "$hits" | sed '/^$/d')"
  if [ -n "$hits" ]; then report "$name" "$hits"; fi
}

rule "HttpURLConnection" 'Http(s)?URLConnection'
rule "URL.openStream / openConnection" '\.openStream\(|\.openConnection\(|URLConnection'
# java.net.URL anywhere, imported or fully qualified: URL(u).readText() and
# .readBytes() are Kotlin extensions over openStream, so the openStream rule never
# sees them; a URL may not be built or reached outside Route.kt at all
rule "java.net.URL" 'java\.net\.URL($|[^A-Za-z])' "$route"
rule "java.net.* (a wildcard hides URL, Socket and the rest)" 'import java\.net\.\*'
rule "a URL built outside Route.kt" '(^|[^A-Za-z0-9_.])URL\(|\.toURL\(' "$route"
rule "DownloadManager" 'DownloadManager'
rule "Cronet" '[Cc]ronet|org\.chromium\.net'
rule "Volley" '[Vv]olley'
rule "Ktor" 'io\.ktor|[Kk]tor[A-Z]'
rule "OkHttp outside network/" 'okhttp3|OkHttp' "$network" "$networkTests"
rule "java.net.http" 'java\.net\.http'
rule "a DNS lookup (getByName, getAllByName), anywhere" 'getByName|getAllByName'
rule "InetAddress" 'InetAddress' "$socks" "$listener" "$networkTests" "$cardsim" "$cardTests"
# a JavaScript `new WebSocket(` inside a test's page script is the page's attempt, not a socket of the app's;
# Socks5SocketFactory is Foxy's own SOCKS-to-Tor factory, named (not opened) in network/
rule "sockets" '(^|[^A-Za-z])(Server|SSL)?Socket\(|DatagramSocket|SocketChannel|(^|[^A-Za-z0-9])(SSL)?SocketFactory' "$socks" "$listener" "$networkTests" "$cardsim" "$cardTests" "$inbox"
# the onion inbox only listens: no client socket, datagram, channel, factory or TLS listener there either
rule "a socket other than a listener in the onion inbox" '(^|[^A-Za-z])(SSL)?Socket\(|DatagramSocket|SocketChannel|(^|[^A-Za-z0-9])(SSL)?SocketFactory|SSLServerSocket' "$socks" "$listener" "$networkTests" "$cardsim" "$cardTests"

# the onion inbox's listener: 127.0.0.1 only. Every line of it that makes a
# server socket names Socks5SocketFactory.TOR_HOST as the bind address in that
# same call (ServerSocket(0) or ServerSocket(0, 8) would listen on every
# interface, the LAN included), and nothing in it rebinds one with .bind(.
ib="$src/$inbox"
if [ -f "$ib" ]; then
  wild="$(grep -nE 'ServerSocket\(|\.bind\(' "$ib" \
    | grep -vE '^[0-9]+:.*ServerSocket\([[:space:]]*[^,()]+,[[:space:]]*[^,()]+,[[:space:]]*(Socks5SocketFactory\.)?TOR_HOST[[:space:]]*\)' || true)"
  if [ -n "$wild" ]; then report "the onion inbox listening on anything but 127.0.0.1 ($inbox)" "$wild"; fi
fi

# the card simulator link: loopback only. Every line of it that names an address
# or makes a socket is one of these three, and nothing else: the import, an
# unconnected Socket(), and a connect to InetAddress.getLoopbackAddress().
sim="$src/$cardsim"
if [ -f "$sim" ]; then
  wild="$(grep -nE 'InetAddress|Socket\(|InetSocketAddress\(|\.connect\(|\.bind\(|"[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+"|localhost' "$sim" \
    | grep -vE '^[0-9]+:import java\.net\.(InetAddress|InetSocketAddress|Socket)$' \
    | grep -vE '^[0-9]+:[[:space:]]*val [a-z]+ = Socket\(\)$' \
    | grep -vE '^[0-9]+:[[:space:]]*[a-z]+\.connect\(InetSocketAddress\(InetAddress\.getLoopbackAddress\(\), port\), CONNECT_MS\)$' || true)"
  if [ -n "$wild" ]; then report "the card simulator link reaching anything but loopback ($cardsim)" "$wild"; fi
fi

# loadUrl with anything other than the asset page
loads="$(grep -rnE --include='*.kt' --include='*.java' 'loadUrl\(|loadDataWithBaseURL\(|postUrl\(' "$src" | grep -vE 'loadUrl\(PAGE_URL\)' || true)"
if [ -n "$loads" ]; then report "a WebView load other than loadUrl(PAGE_URL)" "$loads"; fi

if [ "$fail" -ne 0 ]; then
  echo "check-no-network: FAILED — the only network code allowed is in app/src/$network" >&2
  exit 1
fi
echo "check-no-network: ok ($src)"
