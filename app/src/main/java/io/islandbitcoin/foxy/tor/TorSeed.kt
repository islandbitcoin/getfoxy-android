package io.islandbitcoin.foxy.tor

import io.islandbitcoin.foxy.debug.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * The relay directory Foxy ships with, laid down on a first run
 * (Foxy/Tor/TorSeed.swift). It is iOS's Foxy/TorSeed/tor-microdescs.xz, byte
 * for byte, unpacked when the APK is built (gradle/tor-seed.gradle.kts: a Kotlin
 * decoder on the phone took over half a minute in a debug build) and checked
 * there against app/tor-seed/PIN; the APK carries the plain file at
 * [ASSET], and its SHA-256 is checked again here, as it is copied.
 *
 * Tor cannot build a circuit until it holds the microdescriptors of the relays
 * it might use; on a first run it downloads all of them, which was 37 of the 45
 * seconds to a first circuit on an iPhone. A copy in the APK leaves Tor only the
 * changes since.
 *
 * Nothing here is trusted: Tor checks every microdescriptor against the
 * digest the signed consensus gives for it, and drops what does not match.
 * Nor is any of it private: the directory is public, the same in every copy.
 *
 * The marker [MARKER_NAME] is written beside the seed and removed the first
 * time Tor reaches a circuit ([proven]). It turns to "used" once Tor reaches
 * [USED_AT]% ([reached]), the stage that reads the seed; a marker still "used"
 * on a later launch means a run read the seed and never got through, and that
 * seed is taken out rather than laid again.
 *
 * On Android Tor's cache is tor-android's `--CacheDirectory`
 * (`cacheDir/TorService`), which the command line fixes and a torrc cannot move.
 */
object TorSeed {
    /** The name Tor reads it under. */
    const val CACHE_NAME = "cached-microdescs"
    const val MARKER_NAME = "microdescs.unproven"
    const val USED_AT = 40
    const val ASSET = "tor/cached-microdescs"

    /** Tor built a circuit, so whatever is in the cache works. */
    fun proven(dir: File) {
        val marker = File(dir, MARKER_NAME)
        if (!marker.exists()) return
        marker.delete()
        Log.line("[foxy] tor: the directory seed got through")
    }

    /** Tor reached the stage that reads the seed: a run that fails from here counts against it. */
    fun reached(percent: Int, dir: File) {
        if (percent < USED_AT) return
        val marker = File(dir, MARKER_NAME)
        if (!marker.exists() || marker.length() != 0L) return
        try {
            marker.writeText("used")
        } catch (_: Exception) {
        }
    }

    /**
     * Lay the seed down in `dir` if Tor has no directory of its own there.
     * `open`: the asset, or null when there is none. `plainSha256`: its pin;
     * bytes that do not match are not used. True when a seed was laid down.
     */
    fun plant(dir: File, open: () -> InputStream?, plainSha256: String): Boolean {
        val cache = File(dir, CACHE_NAME)
        val marker = File(dir, MARKER_NAME)
        if (marker.exists()) {
            if (marker.length() != 0L) {
                cache.delete()
                marker.delete()
                Log.line("[foxy] tor: the last directory seed was read and never got through; fetching the directory instead")
                return false
            }
            Log.line("[foxy] tor: the last launch never reached the directory; keeping the seed")
        }
        if (cache.exists()) return false
        val began = System.nanoTime()
        // beside the cache, then moved onto it: a run that ends half way leaves no half a file for Tor
        val part = File(dir, "$CACHE_NAME.seed")
        var bytes = 0L
        try {
            val input = try {
                open()
            } catch (e: Exception) {
                null
            }
            if (input == null) {
                Log.line("[foxy] tor: no directory seed in the APK; Tor will fetch its own")
                return false
            }
            if (!dir.isDirectory) {
                dir.mkdirs()
                ownerOnly(dir)
            }
            part.delete()
            val digest = MessageDigest.getInstance("SHA-256")
            input.use { inp ->
                FileOutputStream(part).use { out ->
                    bytes = DigestInputStream(inp, digest).copyTo(out, 1 shl 16)
                    out.fd.sync()
                }
            }
            if (bytes == 0L || digest.digest().joinToString("") { "%02x".format(it) } != plainSha256) {
                part.delete()
                Log.line("[foxy] tor: the directory seed is not the pinned one; Tor will fetch its own")
                return false
            }
            if (!part.renameTo(cache)) throw java.io.IOException("the rename failed")
        } catch (e: Exception) {
            part.delete()
            Log.line("[foxy] tor: the directory seed could not be written (${e.javaClass.simpleName})")
            return false
        }
        try {
            marker.writeBytes(ByteArray(0))
        } catch (_: Exception) {
        }
        Log.line("[foxy] tor: directory seed laid down, %.1f MB in %.1fs".format(bytes / 1e6, (System.nanoTime() - began) / 1e9))
        return true
    }

    /** rwx for the owner only: Tor refuses a directory others can read. */
    fun ownerOnly(dir: File) {
        dir.setReadable(false, false)
        dir.setWritable(false, false)
        dir.setExecutable(false, false)
        dir.setReadable(true, true)
        dir.setWritable(true, true)
        dir.setExecutable(true, true)
    }

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
