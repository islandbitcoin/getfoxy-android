package io.islandbitcoin.foxy.keychain

import io.islandbitcoin.foxy.TestSupport
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

/** Whole or not at all; no temporary file left; one-way flags. */
class AtomicWriteTest {
    private val dir = TestSupport.tempDir("atomic")

    @After
    fun clean() {
        AtomicWrite.afterRename = null
        dir.setWritable(true)
        dir.deleteRecursively()
    }

    @Test
    fun aWriteReplacesTheFileWholeAndLeavesNothingBeside() {
        val f = File(dir, "x.json")
        AtomicWrite.write(f, "one".toByteArray())
        AtomicWrite.write(f, "two".toByteArray())
        assertEquals("two", f.readText())
        assertEquals(listOf("x.json"), dir.list()!!.toList())
        // a folder not there yet is made
        val nested = File(dir, "a/b/c.json")
        AtomicWrite.write(nested, byteArrayOf(1, 2))
        assertArrayEquals(byteArrayOf(1, 2), nested.readBytes())
    }

    @Test
    fun aFailedWriteLeavesTheOldFile() {
        val f = File(dir, "x.json")
        AtomicWrite.write(f, "old".toByteArray())
        TestSupport.makeUnwritable(dir)
        try {
            AtomicWrite.write(f, "new".toByteArray())
            fail("written into a folder that cannot be written")
        } catch (e: IOException) {
        } finally {
            dir.setWritable(true)
        }
        assertEquals("old", f.readText())
        assertEquals(listOf("x.json"), dir.list()!!.toList())
    }

    @Test
    fun theHookRunsAfterTheRename() {
        val f = File(dir, "x.json")
        var seen: String? = null
        AtomicWrite.afterRename = { seen = it.readText() }
        AtomicWrite.write(f, "done".toByteArray())
        assertEquals("the file was in place when the hook ran", "done", seen)
    }

    @Test
    fun flagsAreOneWay() {
        val flags = Flags(dir)
        assertFalse(flags.isSet("foxy.test"))
        flags.set("foxy.test")
        assertTrue(flags.isSet("foxy.test"))
        flags.set("foxy.test")
        assertTrue(Flags(dir).isSet("foxy.test"))
        try {
            flags.set("../escape")
            fail("a flag name that is a path")
        } catch (e: IllegalArgumentException) {
        }
    }
}
