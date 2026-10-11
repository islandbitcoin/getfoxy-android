package io.islandbitcoin.foxy.keychain

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * A file written whole or not at all: to a temporary file beside it, flushed to
 * the disk (`FileDescriptor.sync()`), renamed over it, and the folder flushed so
 * the rename is on the disk too. A crash or a power cut leaves the old file or
 * the new one, never half of one and never an older one.
 *
 * android.util.AtomicFile does the same, minus the folder flush, and is not in
 * the JVM's android.jar; this one runs unchanged in the unit tests.
 */
object AtomicWrite {
    /** For CounterCrashTest and the store tests: runs after the rename, before the caller answers. */
    @Volatile internal var afterRename: ((File) -> Unit)? = null

    @Throws(IOException::class)
    fun write(target: File, bytes: ByteArray) {
        val dir = target.absoluteFile.parentFile ?: throw IOException("no folder for ${target.name}")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("the folder could not be made")
        val temporary = File(dir, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            temporary.delete()
            throw e
        } catch (e: RuntimeException) {
            temporary.delete()
            throw IOException(e)
        }
        syncFolder(dir)
        afterRename?.invoke(target)
    }

    /** The rename is the folder's change; losing it would bring the old file back. Best effort. */
    private fun syncFolder(dir: File) {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {
            // some file systems refuse to open a folder; the file itself is on the disk
        }
    }
}
