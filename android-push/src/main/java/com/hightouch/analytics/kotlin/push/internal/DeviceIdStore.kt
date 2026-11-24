package com.hightouch.analytics.kotlin.push.internal

import android.content.Context
import android.util.Log
import java.io.File
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
internal class DeviceIdStore(context: Context) {

    private val file = File(context.noBackupFilesDir, FILE_NAME)

    /** Returns the stored ID, minting and storing one if none exists. */
    fun getOrCreate(): String {
        read()?.let { return it }
        val minted = UUID.randomUUID().toString()
        try {
            file.writeText(minted)
        } catch (e: IOException) {
            Log.w(TAG, "Could not store the device ID; it will change on the next launch", e)
        }
        return minted
    }

    private fun read(): String? = try {
        file.readText().trim().takeIf { it.isNotEmpty() }
    } catch (e: IOException) {
        null
    }

    internal companion object {
        const val FILE_NAME = "hightouch_push_device_id"
        private const val TAG = "HightouchPush"
    }
}
