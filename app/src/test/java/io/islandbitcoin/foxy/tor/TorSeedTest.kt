package io.islandbitcoin.foxy.tor

import io.islandbitcoin.foxy.TestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The relay directory seed: iOS's file, pinned; what the build unpacked it to,
 * pinned; and on the phone, a seed laid down once, whole, only when its bytes
 * are the pinned ones, and taken out when it was read and never got through
 * (TorSeed.swift, TorSeedTests.swift).
 */
class TorSeedTest {
    private val pin = File("tor-seed/PIN").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        .associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun theSeedIsIOSsFileAndTheBuildUnpackedItToThePinnedBytes() {
        assertEquals(pin["xz-sha256"], TorSeed.sha256(File("tor-seed/tor-microdescs.xz").readBytes()))
        val plain = File(System.getProperty("foxy.torSeed") ?: error("the unpacked seed's path is not set")).readBytes()
        assertEquals(pin["plain-bytes"]!!.toInt(), plain.size)
        assertEquals(pin["plain-sha256"], TorSeed.sha256(plain))
        assertTrue(String(plain, 0, 100).startsWith("@last-listed "))
        assertEquals("the APK carries it where TorSeed reads it", "tor/cached-microdescs", TorSeed.ASSET)
    }

    private val seed = "@last-listed 2026-09-20 07:28:30\nonion-key\n".repeat(1000).toByteArray()
    private val seedSha = TorSeed.sha256(seed)

    @Test
    fun aSeedIsLaidDownOnceWholeWithItsMarker() {
        val dir = TestSupport.tempDir("seed")
        assertTrue(TorSeed.plant(dir, { seed.inputStream() }, seedSha))
        val cache = File(dir, TorSeed.CACHE_NAME)
        assertEquals(seedSha, TorSeed.sha256(cache.readBytes()))
        assertEquals("the marker, empty: not yet read", 0L, File(dir, TorSeed.MARKER_NAME).length())
        assertFalse("no part file left", File(dir, TorSeed.CACHE_NAME + ".seed").exists())
        // never over a cache Tor keeps
        cache.writeText("Tor's own")
        File(dir, TorSeed.MARKER_NAME).delete()
        assertFalse(TorSeed.plant(dir, { seed.inputStream() }, seedSha))
        assertEquals("Tor's own", cache.readText())
    }

    @Test
    fun aSeedThatWasReadAndNeverGotThroughIsTakenOut() {
        val dir = TestSupport.tempDir("seed")
        File(dir, TorSeed.CACHE_NAME).writeText("seed")
        File(dir, TorSeed.MARKER_NAME).writeText("used")
        assertFalse(TorSeed.plant(dir, { seed.inputStream() }, seedSha))
        assertFalse(File(dir, TorSeed.CACHE_NAME).exists())
        assertFalse(File(dir, TorSeed.MARKER_NAME).exists())
    }

    @Test
    fun aMarkerNeverReachedKeepsTheSeed() {
        val dir = TestSupport.tempDir("seed")
        File(dir, TorSeed.CACHE_NAME).writeText("seed")
        File(dir, TorSeed.MARKER_NAME).writeBytes(ByteArray(0))
        assertFalse(TorSeed.plant(dir, { seed.inputStream() }, seedSha))
        assertEquals("seed", File(dir, TorSeed.CACHE_NAME).readText())
    }

    @Test
    fun bytesThatAreNotThePinnedOnesAreNotLaidDown() {
        val dir = TestSupport.tempDir("seed")
        assertFalse(TorSeed.plant(dir, { "other".byteInputStream() }, seedSha))
        assertFalse(TorSeed.plant(dir, { null }, seedSha))
        assertFalse(TorSeed.plant(dir, { throw java.io.IOException("no asset") }, seedSha))
        assertFalse(TorSeed.plant(dir, { ByteArray(0).inputStream() }, TorSeed.sha256(ByteArray(0))))
        assertFalse(File(dir, TorSeed.CACHE_NAME).exists())
        assertFalse(File(dir, TorSeed.CACHE_NAME + ".seed").exists())
        assertFalse(File(dir, TorSeed.MARKER_NAME).exists())
    }

    @Test
    fun aDirectoryItMakesIsTheOwnersOnly() {
        val dir = File(TestSupport.tempDir("seed"), "TorService")
        assertTrue(TorSeed.plant(dir, { seed.inputStream() }, seedSha))
        val mode = java.nio.file.Files.getPosixFilePermissions(dir.toPath())
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"), mode)
    }
}
