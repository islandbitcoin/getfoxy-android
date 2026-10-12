#!/usr/bin/env bash
# check-no-network.test.sh — the network check catches what it is for.
#
# Plants each kind of network call in a scratch tree and expects
# tools/check-no-network.sh to fail on it; then a clean tree, and the allowed
# exceptions (OkHttp in network/, the SOCKS factory's socket and placeholder
# address, the network/ JVM tests' loopback servers, the net-block test's
# listener, a page script's `new WebSocket(`), must pass.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
check="$here/check-no-network.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

src="$tmp/src"
plant() { # plant <file under src> <line>
  rm -rf "$src"
  mkdir -p "$(dirname "$src/$1")"
  printf '%s\n' "$2" > "$src/$1"
}

failures=0
expect() { # expect pass|fail <what>
  local want="$1" what="$2" got
  if "$check" "$src" > /dev/null 2>&1; then got=pass; else got=fail; fi
  if [ "$got" != "$want" ]; then
    echo "check-no-network.test: expected $want, got $got: $what" >&2
    failures=$((failures + 1))
  fi
}

app='main/java/io/islandbitcoin/foxy/x/X.kt'
for line in \
  'import java.net.HttpURLConnection' \
  'val c = url.openConnection()' \
  'val s = url.openStream()' \
  'import java.net.URL' \
  'import java.net.URL as Link' \
  'val t = java.net.URL(u).readText()' \
  'val b = java.net.URL(u).readBytes()' \
  'import java.net.*' \
  'val t = URL(u).readText()' \
  'val b = URL(u).readBytes()' \
  'val t = uri.toURL().readText()' \
  'val d = context.getSystemService(DownloadManager::class.java)' \
  'import org.chromium.net.CronetEngine' \
  'import com.android.volley.toolbox.Volley' \
  'import io.ktor.client.HttpClient' \
  'import okhttp3.OkHttpClient' \
  'import java.net.http.HttpClient' \
  'val a = InetAddress.getByName("example.com")' \
  'val s = Socket("example.com", 443)' \
  'val s = ServerSocket(0)' \
  'val f = SSLSocketFactory.getDefault()' \
  'val u = DatagramSocket()' \
  'webView.loadUrl("https://example.com")' \
  'webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)'; do
  plant "$app" "$line"
  expect fail "$line"
done

plant "$app" 'val x = 1'
expect pass "a tree with no network code"
plant "$app" 'webView?.loadUrl(PAGE_URL)'
expect pass "the asset page"
plant 'main/java/io/islandbitcoin/foxy/network/Route.kt' 'import okhttp3.OkHttpClient'
expect pass "OkHttp in Route.kt"
plant "$app" 'import java.net.URLEncoder'
expect pass "URLEncoder is not java.net.URL"
plant "$app" 'val u = Uri.parse(PAGE_URL)'
expect pass "a Uri is not a URL"
plant 'androidTest/java/io/islandbitcoin/foxy/ListenerService.java' 'ServerSocket s = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());'
expect pass "the net-block test's listener"
plant 'androidTest/java/io/islandbitcoin/foxy/NetBlockTest.kt' '"try { ws = new WebSocket(host); } catch (e) {}"'
expect pass "a page script's WebSocket"

# P2: OkHttp in network/ only; a socket and an InetAddress in the SOCKS factory only; no lookup anywhere
net='main/java/io/islandbitcoin/foxy/network'
socks="$net/Socks5SocketFactory.kt"
plant "$net/RouteHttp.kt" 'import okhttp3.OkHttpClient'
expect pass "OkHttp in network/RouteHttp.kt"
plant "$net/RouteHttp.kt" 'fun factoryFor(label: String): Socks5SocketFactory = make(label)'
expect pass "naming Foxy's SOCKS factory in network/"
plant 'main/java/io/islandbitcoin/foxy/bridge/X.kt' 'import okhttp3.HttpUrl'
expect fail "OkHttp in bridge/"
plant 'main/java/io/islandbitcoin/foxy/tor/X.kt' 'val c = OkHttpClient()'
expect fail "OkHttp in tor/"
plant "$socks" 'class S : Socket() { fun f() = InetAddress.getByAddress(h, b) }'
expect pass "the SOCKS factory's socket and placeholder address"
plant "$socks" 'val a = InetAddress.getByName(host)'
expect fail "a lookup in the SOCKS factory"
plant "$net/Route.kt" 'val a = InetAddress.getAllByName(host)'
expect fail "a lookup in Route.kt"
plant "$net/Other.kt" 'val s = Socket("127.0.0.1", 9050)'
expect fail "a socket elsewhere in network/"
plant "$net/Other.kt" 'val a = InetAddress.getLoopbackAddress()'
expect fail "an InetAddress elsewhere in network/"
plant 'main/java/io/islandbitcoin/foxy/x/X.kt' '// main/java/io/islandbitcoin/foxy/network/Socks5SocketFactory.kt: val s = Socket(h, 1)'
expect fail "a line that only names the factory's path is not excused"
plant 'main/java/io/islandbitcoin/foxy/x/X.kt' 'val f = javax.net.SocketFactory.getDefault()'
expect fail "the platform SocketFactory"
plant 'test/java/io/islandbitcoin/foxy/network/FakeSocks.kt' 'val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())'
expect pass "a network/ JVM test's loopback server"
plant 'test/java/io/islandbitcoin/foxy/network/RouteClientTest.kt' 'import okhttp3.mockwebserver.MockWebServer'
expect pass "MockWebServer in a network/ JVM test"
plant 'test/java/io/islandbitcoin/foxy/bridge/X.kt' 'val server = ServerSocket(0)'
expect fail "a server in a JVM test outside network/"
plant 'test/java/io/islandbitcoin/foxy/network/X.kt' 'val a = InetAddress.getByName("example.com")'
expect fail "a lookup in a network/ JVM test"

if [ "$failures" -ne 0 ]; then
  echo "check-no-network.test: $failures case(s) wrong" >&2
  exit 1
fi
echo "check-no-network.test: ok"
