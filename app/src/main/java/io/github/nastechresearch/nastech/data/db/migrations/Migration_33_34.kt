package io.github.nastechresearch.nastech.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS \`conversation_agent_configs\` (
                \`conversationId\` TEXT NOT NULL,
                \`configJson\` TEXT NOT NULL,
                \`updatedAt\` INTEGER NOT NULL,
                PRIMARY KEY(\`conversationId\`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS \`agent_templates\` (
                \`templateId\` TEXT NOT NULL,
                \`name\` TEXT NOT NULL,
                \`description\` TEXT NOT NULL,
                \`configJson\` TEXT NOT NULL,
                \`createdAt\` INTEGER NOT NULL,
                \`updatedAt\` INTEGER NOT NULL,
                PRIMARY KEY(\`templateId\`)
            )
            """.trimIndent()
        )
    }
}
