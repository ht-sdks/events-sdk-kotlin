package com.hightouch.analytics.kotlin.push.messages.internal

/**
 * The SQLite schema of the message store, defined in code. `payload` holds the full message JSON;
 * every other column is a projection of it for filtering and sorting. Mirrors iOS
 * `MessageStoreSchema`; `MessageStoreSchemaTest` pins the SQL below, so any schema change fails
 * CI on purpose and forces a [MODEL_VERSION] bump.
 */
internal object MessageStoreSchema {
    /** Part of the database filename (`messages-v<n>.db`), so bumping it starts a fresh store. */
    const val MODEL_VERSION = 1

    const val TABLE = "messages"

    /** Column names; `messageVersion` rather than `version` to avoid confusion with [MODEL_VERSION]. */
    object Col {
        const val MESSAGE_ID = "messageId"
        const val OWNER_KEY = "ownerKey"
        const val MESSAGE_TYPE = "messageType"
        const val CAMPAIGN_ID = "campaignId"
        const val CREATED_AT = "createdAt"
        const val EXPIRES_AT = "expiresAt"
        const val PRIORITY_RANK = "priorityRank"
        const val LOCATION_ID = "locationId"
        const val VARIANT_SLUG = "variantSlug"
        const val MESSAGE_VERSION = "messageVersion"
        const val PAYLOAD = "payload"
        const val INSERTED_AT = "insertedAt"
    }

    /**
     * Dates are epoch milliseconds. A null `expiresAt` means "never expires". The composite primary
     * key is the uniqueness constraint: the same message id can be cached for two owners on one
     * device. It also serves as the (ownerKey, messageId) index iOS declares separately.
     */
    const val CREATE_TABLE =
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
            "PRIMARY KEY (ownerKey, messageId))"

    val CREATE_INDEXES: List<String> = listOf(
        "CREATE INDEX idx_owner_location_variant ON messages (ownerKey, locationId, variantSlug)",
        "CREATE INDEX idx_owner_variant ON messages (ownerKey, variantSlug)",
        "CREATE INDEX idx_owner_expires ON messages (ownerKey, expiresAt)",
        // For the cross-owner prune.
        "CREATE INDEX idx_expires ON messages (expiresAt)",
        "CREATE INDEX idx_owner_sort ON messages (ownerKey, priorityRank, createdAt)",
    )

    val CREATE_STATEMENTS: List<String> = listOf(CREATE_TABLE) + CREATE_INDEXES
}
