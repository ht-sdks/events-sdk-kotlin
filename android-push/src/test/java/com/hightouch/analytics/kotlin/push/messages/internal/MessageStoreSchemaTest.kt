package com.hightouch.analytics.kotlin.push.messages.internal

import android.database.sqlite.SQLiteConstraintException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the schema (iOS pins the Core Data `versionHash`). Any change to the SQL or version fails
 * here on purpose: bump `MODEL_VERSION` so the store starts a fresh `messages-v<n>.db`, then update
 * the expectations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MessageStoreSchemaTest {
    private lateinit var store: MessageStore

    @Before
    fun setUp() {
        store = ephemeralStore()
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun `model version is pinned`() {
        assertEquals(1, MessageStoreSchema.MODEL_VERSION)
        assertEquals("messages-v1.db", MessageStoreLocation.fileName(MessageStoreSchema.MODEL_VERSION))
    }

    @Test
    fun `create table sql is pinned`() {
        assertEquals(
            "CREATE TABLE messages (" +
                "messageId TEXT NOT NULL, " +
                "ownerKey TEXT NOT NULL, " +
                "messageType TEXT, " +
                "campaignId TEXT, " +
                "createdAt INTEGER, " +
                "expiresAt INTEGER, " +
                "priorityRank INTEGER NOT NULL DEFAULT 2147483647, " +
                "locationId TEXT, " +
                "variantSlug TEXT, " +
                "messageVersion TEXT, " +
                "payload BLOB NOT NULL, " +
                "insertedAt INTEGER NOT NULL, " +
                "PRIMARY KEY (ownerKey, messageId))",
            MessageStoreSchema.CREATE_TABLE,
        )
    }

    @Test
    fun `create index sql is pinned`() {
        assertEquals(
            listOf(
                "CREATE INDEX idx_owner_location_variant ON messages (ownerKey, locationId, variantSlug)",
                "CREATE INDEX idx_owner_variant ON messages (ownerKey, variantSlug)",
                "CREATE INDEX idx_owner_expires ON messages (ownerKey, expiresAt)",
                "CREATE INDEX idx_expires ON messages (expiresAt)",
                "CREATE INDEX idx_owner_sort ON messages (ownerKey, priorityRank, createdAt)",
            ),
            MessageStoreSchema.CREATE_INDEXES,
        )
    }

    @Test
    fun `opened database matches the pinned sql and version`() {
        val db = store.database
        val sql = HashMap<String, String>()
        db.rawQuery("SELECT name, sql FROM sqlite_master WHERE sql IS NOT NULL AND name != 'android_metadata'", null).use {
            while (it.moveToNext()) sql[it.getString(0)] = it.getString(1)
        }
        assertEquals(MessageStoreSchema.CREATE_TABLE, sql["messages"])
        val indexNames = setOf(
            "idx_owner_location_variant", "idx_owner_variant", "idx_owner_expires", "idx_expires", "idx_owner_sort",
        )
        assertEquals(indexNames, sql.keys - "messages")
        assertEquals(MessageStoreSchema.CREATE_INDEXES.toSet(), (sql - "messages").values.toSet())
        assertEquals(MessageStoreSchema.MODEL_VERSION, db.version)
    }

    @Test
    fun `composite primary key rejects a duplicate message id for one owner`() {
        fun insert(ownerKey: String) = store.database.execSQL(
            "INSERT INTO messages (messageId, ownerKey, payload, insertedAt) VALUES ('dup', ?, x'01', 0)",
            arrayOf(ownerKey),
        )
        insert("u:alice")
        try {
            insert("u:alice")
            fail("expected a constraint violation")
        } catch (expected: SQLiteConstraintException) {
            // The same id for a different owner is allowed.
        }
        insert("u:bob")
        assertEquals(2, store.totalCount())
    }

    @Test
    fun `defaults apply when optional projection columns are omitted`() {
        store.database.execSQL(
            "INSERT INTO messages (messageId, ownerKey, payload, insertedAt) VALUES ('m', 'u:alice', x'01', 0)",
        )
        store.database.rawQuery("SELECT messageType, priorityRank, expiresAt FROM messages", null).use {
            assertTrue(it.moveToFirst())
            assertTrue(it.isNull(0))
            assertEquals(Int.MAX_VALUE, it.getInt(1))
            assertTrue(it.isNull(2))
        }
    }
}
