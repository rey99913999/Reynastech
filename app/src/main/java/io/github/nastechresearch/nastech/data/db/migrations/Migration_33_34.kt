package io.github.nastechresearch.nastech.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversation_agent_configs (
                conversation_id TEXT NOT NULL PRIMARY KEY,
                config_json TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversation_agent_templates (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL UNIQUE,
                config_json TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_conversation_agent_configs_updated_at ON conversation_agent_configs(updated_at)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_conversation_agent_templates_name ON conversation_agent_templates(name)"
        )
    }
}
