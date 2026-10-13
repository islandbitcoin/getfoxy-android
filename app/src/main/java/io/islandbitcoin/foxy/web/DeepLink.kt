package io.islandbitcoin.foxy.web

import java.net.URLDecoder

/**
 * DEBUG BUILDS ONLY: `foxy://receive?amt=50&unit=sat&rail=cashu`, rebuilt from
 * a whitelist into the page's own hash (FoxyWebView.swift:492-531; BRIDGE.md
 * `deepLink`). The scheme is registered only in the debug manifest
 * (`app/src/debug/AndroidManifest.xml`), MainActivity reads a link only in a
 * debug build, and the page reads the parameters only where `FOXY_DEBUG` is
 * set: a release Foxy has no deep-link scheme to be reached through (ManifestTest;
 * its one https address is the card's, CardTapIntake, which only tells the page a card was tapped).
 *
 * Nothing from the URL is put into the page. The screen is matched against a
 * list and each parameter against what it may be; the hash is rebuilt from
 * what matched, so what reaches JavaScript is drawn from a fixed set plus
 * digits. Anything else is dropped without a word to the page.
 *
 * `foxy://card` is not a screen: it is a card held to the phone, said by hand
 * (FoxyWebView.swift:497-508). It sets no hash and takes no parameters; iOS
 * tells the page `FoxyWallet._cardTapped()` through CardTapDelivery. [read]
 * names it [Link.CardTap], and MainActivity hands it to its CardTapDelivery,
 * as for a real card's address (ENG-668); nothing of the link reaches the page.
 */
object DeepLink {
    /** The screens whose link becomes a hash. */
    val SCREENS = listOf("home", "receive", "send", "token", "nfc", "split")

    /** The one link that is not a screen: a card tap (`foxy://card`). */
    const val CARD = "card"

    /** What a link asks for: a screen, by the page's hash, or a card tap. */
    sealed interface Link {
        data class Screen(val hash: String) : Link
        data object CardTap : Link
    }
    private val AMOUNT = Regex("^[0-9]{1,9}([.][0-9]{1,2})?$")
    private val UNITS = setOf("sat", "sats", "usd")
    private val RAILS = setOf("lightning", "cashu", "onchain", "on-chain")

    /** The hash for `link`, or null when it names no screen Foxy has (a card tap included). `ignored` hears each parameter dropped. */
    fun hash(link: String, ignored: (String) -> Unit = {}): String? = (read(link, ignored) as? Link.Screen)?.hash

    /**
     * What `link` asks for, or null when it is no link of Foxy's. A card tap reads
     * no parameters, so `ignored` hears none for it, as on iOS.
     */
    fun read(link: String, ignored: (String) -> Unit = {}): Link? {
        val colon = link.indexOf(':')
        if (colon < 0 || link.substring(0, colon).lowercase(java.util.Locale.ROOT) != "foxy") return null
        var rest = link.substring(colon + 1)
        val hashAt = rest.indexOf('#')
        if (hashAt >= 0) rest = rest.substring(0, hashAt)
        val queryAt = rest.indexOf('?')
        val query = if (queryAt >= 0) rest.substring(queryAt + 1) else ""
        val place = if (queryAt >= 0) rest.substring(0, queryAt) else rest
        // foxy://receive, foxy:///receive and foxy:receive all name the same screen
        val named = when {
            place.startsWith("//") -> {
                val authority = place.substring(2).substringBefore('/')
                authority.ifEmpty { place.substring(2).trimStart('/').substringBefore('/') }
            }
            else -> place.trimStart('/').substringBefore('/')
        }
        val target = named.lowercase(java.util.Locale.ROOT)
        if (target == CARD) return Link.CardTap
        if (target !in SCREENS) return null
        val parts = arrayListOf("foxy=$target")
        if (query.isNotEmpty()) {
            for (item in query.split('&')) {
                if (item.isEmpty()) continue
                val name = decode(item.substringBefore('=')) ?: continue
                val value = (if ('=' in item) decode(item.substringAfter('=')) else "")?.lowercase(java.util.Locale.ROOT) ?: continue
                when (name.lowercase(java.util.Locale.ROOT)) {
                    "amt" -> if (AMOUNT.matches(value)) parts.add("amt=$value")
                    "unit" -> if (value in UNITS) parts.add("unit=$value")
                    "rail" -> if (value in RAILS) parts.add("rail=$value")
                    else -> ignored(name)
                }
            }
        }
        return Link.Screen(parts.joinToString("&"))
    }

    private fun decode(s: String): String? = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: IllegalArgumentException) {
        null
    }

    /** The script that hands the hash to the page; `hash` is only ever one [hash] built. */
    fun script(hash: String): String = "location.hash = '$hash'"
}
