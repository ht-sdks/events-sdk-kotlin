package com.hightouch.analytics.kotlin.push.internal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
class DeviceIdStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `same device_id is returned across restarts and leaves no temp file behind`() {
        val dir = tmp.newFolder("no-backup")

        val first = DeviceIdStore(dir).getOrCreate()
        val afterRestart = DeviceIdStore(dir).getOrCreate()

        assertNotNull(first)
        assertEquals(first, afterRestart)
        assertTrue(File(dir, DeviceIdStore.FILE_NAME).isFile)
        assertFalse(File(dir, "${DeviceIdStore.FILE_NAME}.tmp").exists())
    }

    @Test
    fun `a new device_id is minted once the stored file is removed or empty`() {
        val dir = tmp.newFolder("no-backup")
        val first = DeviceIdStore(dir).getOrCreate()!!

        val file = File(dir, DeviceIdStore.FILE_NAME)
        assertTrue(file.delete())
        val afterDelete = DeviceIdStore(dir).getOrCreate()!!
        assertNotEquals(first, afterDelete)

        file.writeText("   \n")
        val afterEmpty = DeviceIdStore(dir).getOrCreate()!!
        assertNotEquals(afterDelete, afterEmpty)
    }

    @Test
    fun `no device_id is minted while the stored file cannot be read`() {
        val dir = tmp.newFolder("no-backup")
        val stored = DeviceIdStore(dir).getOrCreate()!!
        val file = File(dir, DeviceIdStore.FILE_NAME)
        val backup = File(dir, "${DeviceIdStore.FILE_NAME}.bak")
        assertTrue(file.renameTo(backup))
        assertTrue(file.mkdir())

        val store = DeviceIdStore(dir)
        assertNull(store.getOrCreate())

        assertTrue(file.delete())
        assertTrue(backup.renameTo(file))
        assertEquals(stored, store.getOrCreate())
    }

    @Test
    fun `device_id stays stable for the process when it cannot be stored`() {
        val blockedParent = tmp.newFile("not-a-directory")
        val unwritableDir = File(blockedParent, "child")

        val store = DeviceIdStore(unwritableDir)
        val first = store.getOrCreate()

        assertNotNull(first)
        assertEquals(first, store.getOrCreate())
    }

    @Test
    fun `device_id stays stable for the process when no-backup directory is null`() {
        val store = DeviceIdStore(dir = null)
        val first = store.getOrCreate()

        assertNotNull(first)
        assertEquals(first, store.getOrCreate())
    }
}
