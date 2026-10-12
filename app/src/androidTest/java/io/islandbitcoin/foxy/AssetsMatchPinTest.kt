package io.islandbitcoin.foxy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * S26 on the phone: every line of core/SHA256SUMS matches the bytes the APK
 * serves from assets/web/, no file there is missing or extra (AAPT dropped or
 * added nothing), and sha256(SHA256SUMS) is core/PIN's page-hash and the
 * BuildConfig value the page is shown.
 */
@RunWith(AndroidJUnit4::class)
class AssetsMatchPinTest {
    private val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun files(dir: String): List<String> {
        val names = assets.list(dir) ?: return emptyList()
        if (names.isEmpty()) return listOf(dir)
        return names.flatMap { files("$dir/$it") }
    }

    @Test
    fun everyServedFileIsThePinnedFileAndNothingElseIsThere() {
        val sumsBytes = assets.open("SHA256SUMS").readBytes()
        val sums = String(sumsBytes).lines().filter { it.isNotBlank() }.associate { line ->
            val (hash, path) = line.split("  ", limit = 2)
            path to hash
        }
        assertTrue("SHA256SUMS lists the page", sums.containsKey("index.html"))
        for ((path, hash) in sums) {
            assertEquals("assets/web/$path", hash, sha256(assets.open("web/$path").readBytes()))
        }
        val served = files("web").map { it.removePrefix("web/") }.toSet()
        assertEquals("the files under assets/web/ are exactly SHA256SUMS's", sums.keys, served)

        val pin = String(assets.open("PIN").readBytes()).lines().first { it.startsWith("page-hash=") }.removePrefix("page-hash=")
        assertEquals(pin, sha256(sumsBytes))
        assertEquals(pin, BuildConfig.PAGE_HASH)
    }
}
