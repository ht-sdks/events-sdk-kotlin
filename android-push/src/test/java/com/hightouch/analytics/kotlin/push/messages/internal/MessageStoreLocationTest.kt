package com.hightouch.analytics.kotlin.push.messages.internal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MessageStoreLocationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `bumping model version changes the filename`() {
        assertNotEquals(MessageStoreLocation.fileName(1), MessageStoreLocation.fileName(2))
        assertEquals("messages-v1.db", MessageStoreLocation.fileName(1))
        assertEquals("messages-v2.db", MessageStoreLocation.fileName(2))
    }

    @Test
    fun `app container lives in the no-backup dir and ends with the versioned filename`() {
        val context = appContext()
        val file1 = MessageStoreLocation.storeFile(MessageStoreLocation.AppContainer, context, 1)!!
        val file2 = MessageStoreLocation.storeFile(MessageStoreLocation.AppContainer, context, 2)!!
        assertEquals("messages-v1.db", file1.name)
        assertEquals("messages-v2.db", file2.name)
        assertEquals(File(File(context.noBackupFilesDir, "hightouch"), "messages"), file1.parentFile)
        assertTrue(file1.parentFile!!.isDirectory)
    }

    @Test
    fun `same location resolves to the same file every time`() {
        val context = appContext()
        assertEquals(
            MessageStoreLocation.storeFile(MessageStoreLocation.AppContainer, context, 1),
            MessageStoreLocation.storeFile(MessageStoreLocation.AppContainer, context, 1),
        )
    }

    @Test
    fun `ephemeral resolves to no file`() {
        assertNull(MessageStoreLocation.storeFile(MessageStoreLocation.Ephemeral, appContext(), 1))
    }

    @Test
    fun `explicit location creates its parent directory`() {
        val file = File(File(tmp.root, "a/b"), "custom.db")
        val resolved = MessageStoreLocation.storeFile(MessageStoreLocation.Explicit(file), appContext(), 1)
        assertEquals(file, resolved)
        assertTrue(file.parentFile!!.isDirectory)
    }

    @Test
    fun `opening an app container store prunes other model versions and their sidecars`() {
        val context = appContext()
        val dir = MessageStoreLocation.messagesDirectory(context)
        val current = MessageStoreLocation.fileName(MessageStoreSchema.MODEL_VERSION)
        val stale = listOf(
            "messages-v0.db", "messages-v0.db-wal", "messages-v0.db-shm", "messages-v0.db-journal",
            // Shares the "messages-v1" prefix with the current file but is a different version.
            "messages-v10.db",
        )
        stale.forEach { File(dir, it).writeText("x") }

        MessageStore(context, MessageStoreLocation.AppContainer).close()

        stale.forEach { assertFalse(it, File(dir, it).exists()) }
        assertTrue(File(dir, current).exists())
    }
}
