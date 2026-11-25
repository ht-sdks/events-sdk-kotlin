package com.hightouch.analytics.kotlin.push.internal

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Stores this install's device ID: a UUID minted on first use and kept until the app is
 * uninstalled or its data is cleared.
 *
 * The file lives in [Context.getNoBackupFilesDir] so Auto Backup and device-to-device transfer do
 * not copy the ID onto a reinstall or onto another device. It is separate from [PushPreferences]
 * so that nothing which clears user state can remove it.
 */
internal class DeviceIdStore(private val dir: File?) {

    constructor(context: Context) : this(context.noBackupFilesDir)

    private var cached: String? = null

    /**
     * Returns the stored ID, minting and storing one if none exists.
     *
     * Returns `null` when a stored file exists but cannot be read, so callers omit `device_id`
     * instead of reporting this install under a second ID.
     */
    @Synchronized
    fun getOrCreate(): String? {
        cached?.let { return it }
        val resolved = loadOrMint()
        if (resolved != null) cached = resolved
        return resolved
    }

    private fun loadOrMint(): String? {
        val directory = dir
        if (directory == null) {
            Log.w(TAG, "No-backup files dir unavailable; device ID will change on the next launch")
            return UUID.randomUUID().toString()
        }

        val file = File(directory, FILE_NAME)
        if (file.exists()) {
            val stored = try {
                file.readText(Charsets.UTF_8).trim()
            } catch (e: IOException) {
                Log.w(TAG, "Could not read the device ID; events will omit it until readable", e)
                return null
            }
            if (stored.isNotEmpty()) return stored
        }

        val minted = UUID.randomUUID().toString()
        val temp = File(directory, "$FILE_NAME.tmp")
        try {
            if (!directory.exists()) directory.mkdirs()
            FileOutputStream(temp).use { out ->
                out.write(minted.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!temp.renameTo(file)) {
                throw IOException("Failed to rename ${temp.name} to ${file.name}")
            }
        } catch (e: IOException) {
            temp.delete()
            Log.w(TAG, "Could not store the device ID; it will change on the next launch", e)
        }
        return minted
    }

    internal companion object {
        const val FILE_NAME = "hightouch_push_device_id"
        private const val TAG = "HightouchPush"
    }
}
