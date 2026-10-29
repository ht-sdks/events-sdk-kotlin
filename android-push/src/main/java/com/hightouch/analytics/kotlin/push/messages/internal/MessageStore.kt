package com.hightouch.analytics.kotlin.push.messages.internal

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import androidx.annotation.VisibleForTesting
import com.hightouch.analytics.kotlin.push.messages.HightouchMessage
import com.hightouch.analytics.kotlin.push.messages.HightouchMessageQuery
import com.hightouch.analytics.kotlin.push.messages.HightouchMessageSort
import com.hightouch.analytics.kotlin.push.messages.internal.MessageStoreSchema.Col
import java.io.File

/** What changed as the result of one [MessageStore.replaceAll] call. */
internal data class UpsertOutcome(
    val insertedIds: List<String>,
    val updatedIds: List<String>,
    val removedIds: List<String>,
    val unchanged: Int,
)

/**
 * The SQLite-backed local cache of messages, partitioned by [MessageOwner]. One store per app.
 * Mirrors iOS `MessageStore`.
 *
 * Every method is synchronous and blocks on disk I/O, so call it off the main thread. The store is
 * thread-safe: mutations are serialized and each one is a single transaction, so a concurrent
 * read sees the state either before or after it, never a partial write. File-backed stores use
 * write-ahead logging, so reads do not wait for a writer.
 *
 * @param modelVersion Names the database file and is its SQLite `user_version`.
 */
internal class MessageStore(
    context: Context,
    location: MessageStoreLocation = MessageStoreLocation.AppContainer,
    val modelVersion: Int = MessageStoreSchema.MODEL_VERSION,
) {
    private val appContext = context.applicationContext ?: context
    private val writeLock = Any()
    private val helper: Helper
    private val db: SQLiteDatabase

    init {
        val file = MessageStoreLocation.storeFile(location, appContext, modelVersion)
        val path = file?.absolutePath
        var opened = Helper(appContext, path, modelVersion)
        val database = try {
            opened.writableDatabase
        } catch (e: SQLiteException) {
            // On any open failure, delete the file and retry once from empty: every row is
            // re-fetchable from the server.
            if (path == null) throw e
            opened.close()
            SQLiteDatabase.deleteDatabase(File(path))
            opened = Helper(appContext, path, modelVersion)
            opened.writableDatabase
        }
        helper = opened
        db = database
        if (location is MessageStoreLocation.AppContainer) {
            MessageStoreLocation.pruneOldModelVersions(appContext, modelVersion)
        }
    }

    /** Closes the database. The store must not be used afterwards. */
    fun close() {
        helper.close()
    }

    // region Fetch

    /** Decodes from `payload`, the full message; the typed columns exist only for filtering and sorting. */
    fun messages(query: HightouchMessageQuery, owner: MessageOwner): List<HightouchMessage> {
        val (sql, args) = selectSql(SELECT_PAYLOAD, query, owner, orderAndLimit = true)
        val result = ArrayList<HightouchMessage>()
        db.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                decode(cursor.getBlob(0))?.let { result.add(it) }
            }
        }
        return result
    }

    /** Ignores expiry: an expired row is still returned until it is pruned or replaced. */
    fun message(messageId: String, owner: MessageOwner): HightouchMessage? =
        db.rawQuery(
            "$SELECT_PAYLOAD WHERE ${Col.OWNER_KEY} = ? AND ${Col.MESSAGE_ID} = ? LIMIT 1",
            arrayOf(owner.storageKey, messageId),
        ).use { cursor -> if (cursor.moveToFirst()) decode(cursor.getBlob(0)) else null }

    fun count(query: HightouchMessageQuery, owner: MessageOwner): Int {
        val (sql, args) = selectSql("SELECT COUNT(*) FROM ${MessageStoreSchema.TABLE}", query, owner, orderAndLimit = false)
        return db.rawQuery(sql, args).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /**
     * The owner predicate is unconditional: it is what keeps one user from reading another's rows.
     * Expiry is `expiresAt IS NULL OR expiresAt > now`, the complement of [pruneExpired]'s `<=`.
     * [limit][HightouchMessageQuery.limit] of `null`, `0` or negative means unlimited.
     */
    private fun selectSql(
        select: String,
        query: HightouchMessageQuery,
        owner: MessageOwner,
        orderAndLimit: Boolean,
    ): Pair<String, Array<String>> {
        val clauses = mutableListOf("${Col.OWNER_KEY} = ?")
        val args = mutableListOf(owner.storageKey)
        query.locationId?.let {
            clauses.add("${Col.LOCATION_ID} = ?")
            args.add(it)
        }
        query.variantSlug?.let {
            clauses.add("${Col.VARIANT_SLUG} = ?")
            args.add(it)
        }
        if (!query.includeExpired) {
            clauses.add("(${Col.EXPIRES_AT} IS NULL OR ${Col.EXPIRES_AT} > ?)")
            args.add((query.nowMillis ?: System.currentTimeMillis()).toString())
        }
        val sql = StringBuilder(select).append(" WHERE ").append(clauses.joinToString(" AND "))
        if (orderAndLimit) {
            sql.append(" ORDER BY ").append(orderBy(query.sort))
            val limit = query.limit
            if (limit != null && limit > 0) sql.append(" LIMIT ").append(limit)
        }
        return sql.toString() to args.toTypedArray()
    }

    /** `messageId` is the final tiebreaker so the order is stable across calls. */
    private fun orderBy(sort: HightouchMessageSort): String = when (sort) {
        HightouchMessageSort.PRIORITY_THEN_NEWEST ->
            "${Col.PRIORITY_RANK} ASC, ${Col.CREATED_AT} DESC, ${Col.MESSAGE_ID} ASC"
        HightouchMessageSort.PRIORITY_THEN_OLDEST ->
            "${Col.PRIORITY_RANK} ASC, ${Col.CREATED_AT} ASC, ${Col.MESSAGE_ID} ASC"
        HightouchMessageSort.NEWEST_FIRST -> "${Col.CREATED_AT} DESC, ${Col.MESSAGE_ID} ASC"
        HightouchMessageSort.OLDEST_FIRST -> "${Col.CREATED_AT} ASC, ${Col.MESSAGE_ID} ASC"
    }

    // endregion

    // region Upsert

    /**
     * Replaces everything stored for [owner] with [messages]: rows not repeated are deleted. One
     * transaction, so readers never see a half-applied sync.
     *
     * @param suppressing Ids to drop from [messages] (dismissed messages). Evaluated inside the
     * write lock: a dismissal records its id before queueing its delete, so any id recorded after
     * this read is deleted after this call.
     * @param limit Keeps only the owner's first [limit] messages in `PRIORITY_THEN_NEWEST` order,
     * so an owner whose server set exceeds the cap converges instead of being evicted and
     * re-inserted on every sync. `null` or negative is unlimited; `0` keeps nothing.
     */
    fun replaceAll(
        messages: List<HightouchMessage>,
        owner: MessageOwner,
        nowMillis: Long = System.currentTimeMillis(),
        suppressing: () -> Set<String> = { emptySet() },
        limit: Int? = null,
    ): UpsertOutcome = synchronized(writeLock) {
        val suppressed = suppressing()
        // A response may repeat an id; last one wins, at the position of its first occurrence.
        val byId = LinkedHashMap<String, HightouchMessage>()
        for (message in messages) {
            if (message.messageId in suppressed) continue
            byId[message.messageId] = message
        }
        var unique: List<HightouchMessage> = byId.values.toList()
        if (limit != null && limit >= 0 && unique.size > limit) {
            unique = unique.sortedWith(PRIORITY_THEN_NEWEST).take(limit)
        }

        val insertedIds = ArrayList<String>()
        val updatedIds = ArrayList<String>()
        val removedIds = ArrayList<String>()
        var unchanged = 0

        db.inTransaction {
            for (chunk in unique.chunked(CHUNK_SIZE)) {
                val existing = storedPayloads(owner, chunk.map { it.messageId })
                for (message in chunk) {
                    val newPayload = message.payloadData()
                    val storedPayload = existing[message.messageId]
                    if (storedPayload == null) {
                        db.insertOrThrow(MessageStoreSchema.TABLE, null, values(message, newPayload, owner, nowMillis, isNew = true))
                        insertedIds.add(message.messageId)
                        continue
                    }
                    // Rows written by an older encoding can differ in bytes alone, so fall back
                    // to a structural compare.
                    val same = storedPayload.contentEquals(newPayload) || decode(storedPayload) == message
                    if (same) {
                        unchanged++
                        continue
                    }
                    db.update(
                        MessageStoreSchema.TABLE,
                        values(message, newPayload, owner, nowMillis, isNew = false),
                        "${Col.OWNER_KEY} = ? AND ${Col.MESSAGE_ID} = ?",
                        arrayOf(owner.storageKey, message.messageId),
                    )
                    updatedIds.add(message.messageId)
                }
            }

            val keepIds = unique.mapTo(HashSet()) { it.messageId }
            db.rawQuery(
                "SELECT ${Col.MESSAGE_ID} FROM ${MessageStoreSchema.TABLE} WHERE ${Col.OWNER_KEY} = ?",
                arrayOf(owner.storageKey),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    if (id !in keepIds) removedIds.add(id)
                }
            }
            deleteIds(owner, removedIds)
        }
        UpsertOutcome(insertedIds, updatedIds, removedIds, unchanged)
    }

    /**
     * Evicts the oldest rows (by first insertion, across owners) until at most
     * [maxStoredMessages] remain. Rows of [preserving] are never evicted, so the syncing owner
     * never loses rows that are not reported to the host app; the store can then stay above the
     * cap. Returns the number of rows evicted.
     */
    fun enforceLimit(maxStoredMessages: Int, preserving: MessageOwner? = null): Int {
        if (maxStoredMessages < 0) return 0
        return synchronized(writeLock) {
            db.inTransaction {
                val total = scalarInt("SELECT COUNT(*) FROM ${MessageStoreSchema.TABLE}", emptyArray())
                val overage = total - maxStoredMessages
                if (overage <= 0) return@inTransaction 0

                val where = if (preserving != null) "WHERE ${Col.OWNER_KEY} != ? " else ""
                val args = if (preserving != null) arrayOf(preserving.storageKey) else emptyArray()
                val victims = ArrayList<Pair<String, String>>()
                db.rawQuery(
                    "SELECT ${Col.OWNER_KEY}, ${Col.MESSAGE_ID} FROM ${MessageStoreSchema.TABLE} $where" +
                        "ORDER BY ${Col.INSERTED_AT} ASC, ${Col.OWNER_KEY} ASC, ${Col.MESSAGE_ID} ASC LIMIT $overage",
                    args,
                ).use { cursor ->
                    while (cursor.moveToNext()) victims.add(cursor.getString(0) to cursor.getString(1))
                }
                for ((ownerKey, messageId) in victims) {
                    db.delete(
                        MessageStoreSchema.TABLE,
                        "${Col.OWNER_KEY} = ? AND ${Col.MESSAGE_ID} = ?",
                        arrayOf(ownerKey, messageId),
                    )
                }
                victims.size
            }
        }
    }

    // endregion

    // region Prune and delete

    /**
     * Reclaims disk space used by expired rows, across owners. Space only: reads already hide
     * expired rows via the query-time predicate, whose boundary this matches (`expiresAt <= now`
     * is expired). Returns the number of rows removed.
     */
    fun pruneExpired(asOfMillis: Long = System.currentTimeMillis()): Int = synchronized(writeLock) {
        db.delete(
            MessageStoreSchema.TABLE,
            "${Col.EXPIRES_AT} IS NOT NULL AND ${Col.EXPIRES_AT} <= ?",
            arrayOf(asOfMillis.toString()),
        )
    }

    /** Returns the number of rows removed. */
    fun delete(messageId: String, owner: MessageOwner): Int = delete(listOf(messageId), owner)

    /** Returns the number of rows removed. */
    fun delete(messageIds: List<String>, owner: MessageOwner): Int {
        if (messageIds.isEmpty()) return 0
        return synchronized(writeLock) { db.inTransaction { deleteIds(owner, messageIds) } }
    }

    /** Returns the ids of the removed rows. */
    fun deleteAll(owner: MessageOwner): List<String> = synchronized(writeLock) {
        db.inTransaction {
            val ids = ArrayList<String>()
            db.rawQuery(
                "SELECT ${Col.MESSAGE_ID} FROM ${MessageStoreSchema.TABLE} WHERE ${Col.OWNER_KEY} = ?",
                arrayOf(owner.storageKey),
            ).use { while (it.moveToNext()) ids.add(it.getString(0)) }
            db.delete(MessageStoreSchema.TABLE, "${Col.OWNER_KEY} = ?", arrayOf(owner.storageKey))
            ids
        }
    }

    /** Removes rows for every owner on the device and returns their ids. Debug tooling only. */
    fun deleteAllOwners(): List<String> = synchronized(writeLock) {
        db.inTransaction {
            val ids = ArrayList<String>()
            db.rawQuery("SELECT ${Col.MESSAGE_ID} FROM ${MessageStoreSchema.TABLE}", null)
                .use { while (it.moveToNext()) ids.add(it.getString(0)) }
            db.delete(MessageStoreSchema.TABLE, null, null)
            ids
        }
    }

    // endregion

    // region Test hooks

    /** Rows across every owner. */
    @VisibleForTesting
    internal fun totalCount(): Int = scalarInt("SELECT COUNT(*) FROM ${MessageStoreSchema.TABLE}", emptyArray())

    /** `insertedAt`, `updatedAt` and `payload` of one row, or null if absent. */
    @VisibleForTesting
    internal fun rowSnapshot(messageId: String, owner: MessageOwner): RowSnapshot? =
        db.rawQuery(
            "SELECT ${Col.INSERTED_AT}, ${Col.UPDATED_AT}, ${Col.PAYLOAD} FROM ${MessageStoreSchema.TABLE} " +
                "WHERE ${Col.OWNER_KEY} = ? AND ${Col.MESSAGE_ID} = ?",
            arrayOf(owner.storageKey, messageId),
        ).use { c -> if (c.moveToFirst()) RowSnapshot(c.getLong(0), c.getLong(1), c.getBlob(2)) else null }

    /** The underlying database, for tests that assert on schema or write raw rows. */
    @get:VisibleForTesting
    internal val database: SQLiteDatabase get() = db

    internal class RowSnapshot(val insertedAtMillis: Long, val updatedAtMillis: Long, val payload: ByteArray)

    // endregion

    // region Helpers

    private fun scalarInt(sql: String, args: Array<String>): Int =
        db.rawQuery(sql, args).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Stored payloads for [ids] under [owner], keyed by message id. */
    private fun storedPayloads(owner: MessageOwner, ids: List<String>): Map<String, ByteArray> {
        val result = HashMap<String, ByteArray>()
        val placeholders = ids.joinToString(",") { "?" }
        db.rawQuery(
            "SELECT ${Col.MESSAGE_ID}, ${Col.PAYLOAD} FROM ${MessageStoreSchema.TABLE} " +
                "WHERE ${Col.OWNER_KEY} = ? AND ${Col.MESSAGE_ID} IN ($placeholders)",
            arrayOf(owner.storageKey) + ids.toTypedArray(),
        ).use { while (it.moveToNext()) result[it.getString(0)] = it.getBlob(1) }
        return result
    }

    private fun deleteIds(owner: MessageOwner, ids: List<String>): Int {
        var removed = 0
        for (chunk in ids.chunked(CHUNK_SIZE)) {
            removed += db.delete(
                MessageStoreSchema.TABLE,
                "${Col.OWNER_KEY} = ? AND ${Col.MESSAGE_ID} IN (${chunk.joinToString(",") { "?" }})",
                arrayOf(owner.storageKey) + chunk.toTypedArray(),
            )
        }
        return removed
    }

    private fun values(
        message: HightouchMessage,
        payload: ByteArray,
        owner: MessageOwner,
        nowMillis: Long,
        isNew: Boolean,
    ) = ContentValues().apply {
        put(Col.MESSAGE_ID, message.messageId)
        put(Col.OWNER_KEY, owner.storageKey)
        put(Col.OWNER_USER_ID, if (owner.kind == MessageOwner.Kind.USER_ID) owner.id else null)
        put(Col.OWNER_ANONYMOUS_ID, if (owner.kind == MessageOwner.Kind.ANONYMOUS_ID) owner.id else null)
        put(Col.MESSAGE_TYPE, message.messageType)
        put(Col.CAMPAIGN_ID, message.campaignId)
        put(Col.CREATED_AT, message.createdAtMillis)
        put(Col.EXPIRES_AT, message.expiresAtMillis)
        put(Col.PRIORITY, message.priority)
        put(Col.PRIORITY_RANK, message.priorityRank)
        put(Col.LOCATION_ID, message.locationId)
        put(Col.VARIANT_SLUG, message.variantSlug)
        put(Col.TRIGGER_TYPE, message.triggerType)
        put(Col.MESSAGE_VERSION, message.version)
        put(Col.PAYLOAD, payload)
        put(Col.UPDATED_AT, nowMillis)
        if (isNew) put(Col.INSERTED_AT, nowMillis)
    }

    private fun decode(payload: ByteArray): HightouchMessage? =
        try {
            HightouchMessage.decode(payload)
        } catch (e: Exception) {
            null
        }

    private inline fun <T> SQLiteDatabase.inTransaction(block: () -> T): T {
        beginTransaction()
        try {
            val result = block()
            setTransactionSuccessful()
            return result
        } finally {
            endTransaction()
        }
    }

    private class Helper(context: Context, name: String?, version: Int) :
        SQLiteOpenHelper(context, name, null, version) {
        override fun onConfigure(db: SQLiteDatabase) {
            // WAL lets reads proceed during a write. Not applicable to the in-memory database.
            if (databaseName != null) db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            for (statement in MessageStoreSchema.CREATE_STATEMENTS) db.execSQL(statement)
        }

        // The version is in the filename, so these only run for a hand-edited file. Every row is
        // re-fetchable from the server, so start over rather than migrate.
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = recreate(db)

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = recreate(db)

        private fun recreate(db: SQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS ${MessageStoreSchema.TABLE}")
            onCreate(db)
        }
    }

    // endregion

    private companion object {
        /** Keeps each `IN` clause under SQLite's bound-variable limit (as low as 999 on older builds). */
        const val CHUNK_SIZE = 200

        const val SELECT_PAYLOAD = "SELECT ${Col.PAYLOAD} FROM ${MessageStoreSchema.TABLE}"

        /** Same order as the SQL `PRIORITY_THEN_NEWEST` sort: nulls sort as the smallest date. */
        val PRIORITY_THEN_NEWEST: Comparator<HightouchMessage> =
            compareBy<HightouchMessage> { it.priorityRank }
                .thenByDescending { it.createdAtMillis ?: Long.MIN_VALUE }
                .thenBy { it.messageId }
    }
}
