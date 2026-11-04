package com.hightouch.analytics.kotlin.push.messages.internal

import android.content.Context
import java.io.File

/**
 * Where a [MessageStore] keeps its database. Mirrors iOS `MessageStoreLocation`.
 */
internal sealed class MessageStoreLocation {
    /** `<noBackupFilesDir>/hightouch/messages/messages-v<n>.db`. */
    object AppContainer : MessageStoreLocation()

    data class Explicit(val file: File) : MessageStoreLocation()

    /** An in-memory database: private to its store and gone when the store closes. */
    object Ephemeral : MessageStoreLocation()

    companion object {
        private const val FILE_PREFIX = "messages-v"

        /**
         * The model version is in the filename, so a breaking schema change is a rename rather
         * than a migration.
         */
        fun fileName(modelVersion: Int): String = "$FILE_PREFIX$modelVersion.db"

        /**
         * The no-backup directory is never included in Auto Backup or device transfer, which
         * mirrors iOS excluding the directory from backups: every row is re-fetchable from the
         * server. One store per app: the app sandbox already isolates each app.
         */
        fun messagesDirectory(context: Context): File {
            val directory = File(File(context.applicationContext.noBackupFilesDir, "hightouch"), "messages")
            directory.mkdirs()
            return directory
        }

        /** `null` for [Ephemeral]. An explicit location gets its parent directory created. */
        fun storeFile(location: MessageStoreLocation, context: Context, modelVersion: Int): File? =
            when (location) {
                Ephemeral -> null
                is Explicit -> location.file.also { it.absoluteFile.parentFile?.mkdirs() }
                AppContainer -> File(messagesDirectory(context), fileName(modelVersion))
            }

        /** Deletes database files (and `-wal`/`-shm`/`-journal` sidecars) left by older model versions. */
        fun pruneOldModelVersions(context: Context, currentVersion: Int) {
            val current = fileName(currentVersion)
            val files = messagesDirectory(context).listFiles() ?: return
            for (file in files) {
                val name = file.name
                if (!name.startsWith(FILE_PREFIX)) continue
                if (name == current || name.startsWith("$current-")) continue
                file.delete()
            }
        }
    }
}
