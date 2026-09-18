package io.github.nastechresearch.nastech.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `task_execution_usage` (
                `taskId` TEXT NOT NULL,
                `plannerCalls` INTEGER NOT NULL,
                `recoveryCalls` INTEGER NOT NULL,
                `visionCalls` INTEGER NOT NULL,
                `verifierCalls` INTEGER NOT NULL,
                `plannedSteps` INTEGER NOT NULL,
                `toolCalls` INTEGER NOT NULL,
                `localToolExecutions` INTEGER NOT NULL,
                `localRecoveryAttempts` INTEGER NOT NULL,
                `partialReplans` INTEGER NOT NULL,
                `llmCallsAvoided` INTEGER NOT NULL,
                `toolContextChars` INTEGER NOT NULL,
                `plannerInputTokens` INTEGER NOT NULL,
                `plannerOutputTokens` INTEGER NOT NULL,
                `recoveryInputTokens` INTEGER NOT NULL,
                `recoveryOutputTokens` INTEGER NOT NULL,
                `visionInputTokens` INTEGER NOT NULL,
                `visionOutputTokens` INTEGER NOT NULL,
                `verifierInputTokens` INTEGER NOT NULL,
                `verifierOutputTokens` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`taskId`)
            )
            """.trimIndent(),
        )
    }
}
