package io.islandbitcoin.foxy.keychain

import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.keychain.SeedMigrationWindow.Outcome
import io.islandbitcoin.foxy.keychain.SeedMigrationWindow.Saved
import io.islandbitcoin.foxy.keychain.SeedMigrationWindow.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** seedMigrate's one-time window. Ported from SeedMigrationWindowTests.swift, with the WebView's storage for WebKit's. */
class SeedMigrationWindowTest {
    @Test
    fun openOnlyOnAnInstallWithPageStorageThatHasNotClosed() {
        assertTrue(SeedMigrationWindow.open(closed = false, hadPageStorage = true))
        assertFalse(SeedMigrationWindow.open(closed = true, hadPageStorage = true))
        assertFalse(SeedMigrationWindow.open(closed = false, hadPageStorage = false))
        assertFalse(SeedMigrationWindow.open(closed = true, hadPageStorage = false))
    }

    @Test
    fun aWriteNeedsTheWindowOpenAndASavedSeedIsComparedEitherWay() {
        for (open in listOf(true, false)) {
            assertEquals(Step.Answer(Outcome.Same), SeedMigrationWindow.step(open, Saved.Found(true)))
            assertEquals(Step.Answer(Outcome.Different), SeedMigrationWindow.step(open, Saved.Found(false)))
            assertEquals(Step.Answer(Outcome.Refused("the seed could not be read")), SeedMigrationWindow.step(open, Saved.Failed))
        }
        assertEquals(Step.Write, SeedMigrationWindow.step(true, Saved.Absent))
        assertEquals(Step.Answer(Outcome.Refused("no migration here")), SeedMigrationWindow.step(false, Saved.Absent))
    }

    @Test
    fun onlyAnAnswerAboutTheWordsClosesTheWindow() {
        assertTrue(SeedMigrationWindow.closes(Outcome.Migrated))
        assertTrue(SeedMigrationWindow.closes(Outcome.Same))
        assertTrue(SeedMigrationWindow.closes(Outcome.Different))
        for (why in listOf("the seed could not be read", "the keystore refused the write", "no migration here", "the words could not be read")) {
            assertFalse(why, SeedMigrationWindow.closes(Outcome.Refused(why)))
        }
    }

    @Test
    fun pageStorageIsFoundOnlyWhereTheWebViewKeepsLocalStorage() {
        val root = TestSupport.tempDir("webview")
        try {
            assertFalse("no folder at all", SeedMigrationWindow.pageStorageExists(File(root, "missing")))
            File(root, "Default/Cache").mkdirs()
            File(root, "Default/Cache/data_0").writeText("cache")
            assertFalse("other WebView data is not page storage", SeedMigrationWindow.pageStorageExists(root))
            val storage = File(root, "Default/Local Storage/leveldb").apply { mkdirs() }
            val file = File(storage, "000003.log")
            file.writeBytes(ByteArray(0))
            assertFalse("an empty file holds nothing", SeedMigrationWindow.pageStorageExists(root))
            file.writeText("foxy.seed.v1")
            assertTrue(SeedMigrationWindow.pageStorageExists(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aFreshInstallClosesAtItsFirstLaunchAndClosingIsForGood() {
        val dir = TestSupport.tempDir("window")
        try {
            val flags = Flags(dir)
            val fresh = SeedMigrationWindow(flags)
            fresh.noteLaunch(File(dir, "no webview yet"))
            assertFalse(fresh.isOpen)
            assertTrue(flags.isSet(SeedMigrationWindow.CLOSED_FLAG))

            // an install whose page held storage before this launch, not yet closed
            val dir2 = TestSupport.tempDir("window2")
            val flags2 = Flags(dir2)
            val webview = File(dir2, "app_webview/Default/Local Storage/leveldb").apply { mkdirs() }
            File(webview, "000003.log").writeText("x")
            val old = SeedMigrationWindow(flags2)
            old.noteLaunch(File(dir2, "app_webview"))
            assertTrue(old.isOpen)
            old.noteLaunch(File(dir2, "elsewhere"))
            assertTrue("noted once per process", old.isOpen)
            old.close("a test")
            assertFalse(old.isOpen)
            old.close("again")
            assertFalse(old.isOpen)
            val relaunched = SeedMigrationWindow(flags2)
            relaunched.noteLaunch(File(dir2, "app_webview"))
            assertFalse("closed stays closed", relaunched.isOpen)
            dir2.deleteRecursively()
        } finally {
            dir.deleteRecursively()
        }
    }
}
