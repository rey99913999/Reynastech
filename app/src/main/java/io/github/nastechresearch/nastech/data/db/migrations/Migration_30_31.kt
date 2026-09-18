package io.github.nastechresearch.nastech.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v30 -> v31: conversation-scoped memory persistence.
 *
 * This is the explicit counterpart of the former Room auto-migration. Keeping it explicit
 * avoids requiring a checked-in v31 schema during KSP processing while preserving the same
 * additive database shape used by Update 01.
 */
val Migration_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `conversation_memory_settings` (
                `conversation_id` TEXT NOT NULL,
                `mode` TEXT NOT NULL DEFAULT 'OFF',
                `use_in_context` INTEGER NOT NULL DEFAULT 1,
                `token_budget` INTEGER NOT NULL DEFAULT 2000,
                PRIMARY KEY(`conversation_id`)
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `conversation_memory_state` (
                `conversation_id` TEXT NOT NULL,
                `goal` TEXT NOT NULL DEFAULT '',
                `completed` TEXT NOT NULL DEFAULT '',
                `current_problem` TEXT NOT NULL DEFAULT '',
                `last_action` TEXT NOT NULL DEFAULT '',
                `next_expected_action` TEXT NOT NULL DEFAULT '',
                `constraints` TEXT NOT NULL DEFAULT '',
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`conversation_id`)
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `conversation_memory_summaries` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `level` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `source_revision` INTEGER NOT NULL DEFAULT 0,
                `updated_at` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_conversation_memory_summaries_conversation_id_level` " +
                "ON `conversation_memory_summaries` (`conversation_id`, `level`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `conversation_memory_candidates` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `importance` TEXT NOT NULL,
                `confidence` REAL NOT NULL,
                `scope` TEXT NOT NULL,
                `source_message_id` TEXT,
                `created_at` INTEGER NOT NULL,
                `status` TEXT NOT NULL DEFAULT 'PENDING'
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_conversation_memory_candidates_conversation_id_status` " +
                "ON `conversation_memory_candidates` (`conversation_id`, `status`)",
        )
    }
}
