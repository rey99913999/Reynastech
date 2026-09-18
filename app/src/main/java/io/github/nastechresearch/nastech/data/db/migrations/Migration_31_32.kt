package io.github.nastechresearch.nastech.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v31 -> v32: durable Task State / Checkpoints / Recovery persistence.
 *
 * All changes are additive: five new tables and their indexes. Existing data and
 * existing workflow/scheduler tables are untouched.
 */
val Migration_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tasks` (
                `taskId` TEXT NOT NULL,
                `conversationId` TEXT NOT NULL,
                `goal` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `currentStepId` TEXT,
                `progress` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `pausedReason` TEXT,
                `retryPolicy` TEXT NOT NULL,
                `checkpointPolicy` TEXT NOT NULL,
                `autonomyPolicy` TEXT NOT NULL,
                `parentTaskId` TEXT,
                PRIMARY KEY(`taskId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tasks_conversationId_updatedAt` " +
                "ON `tasks` (`conversationId`, `updatedAt`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tasks_status_updatedAt` " +
                "ON `tasks` (`status`, `updatedAt`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `task_steps` (
                `id` TEXT NOT NULL,
                `taskId` TEXT NOT NULL,
                `orderIndex` INTEGER NOT NULL,
                `logicalGoal` TEXT NOT NULL,
                `executionInstruction` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `startedAt` INTEGER,
                `completedAt` INTEGER,
                `expectedResult` TEXT,
                `actualResult` TEXT,
                `retryCount` INTEGER NOT NULL,
                `lastError` TEXT,
                `checkpointId` TEXT,
                `requiresVerification` INTEGER NOT NULL,
                `verificationHint` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_task_steps_taskId_orderIndex` " +
                "ON `task_steps` (`taskId`, `orderIndex`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_task_steps_taskId_status` " +
                "ON `task_steps` (`taskId`, `status`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `task_checkpoints` (
                `checkpointId` TEXT NOT NULL,
                `taskId` TEXT NOT NULL,
                `stepId` TEXT,
                `createdAt` INTEGER NOT NULL,
                `note` TEXT,
                `stateSummary` TEXT NOT NULL,
                PRIMARY KEY(`checkpointId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_task_checkpoints_taskId_createdAt` " +
                "ON `task_checkpoints` (`taskId`, `createdAt`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `task_audit_logs` (
                `eventId` TEXT NOT NULL,
                `taskId` TEXT NOT NULL,
                `stepId` TEXT,
                `eventType` TEXT NOT NULL,
                `status` TEXT,
                `message` TEXT,
                `error` TEXT,
                `inputFingerprint` TEXT,
                `outputSummary` TEXT,
                `diagnosticRef` TEXT,
                `timestamp` INTEGER NOT NULL,
                PRIMARY KEY(`eventId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_task_audit_logs_taskId_timestamp` " +
                "ON `task_audit_logs` (`taskId`, `timestamp`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `task_settings` (
                `conversationId` TEXT NOT NULL,
                `saveCheckpoints` INTEGER NOT NULL,
                `resumeAfterAppClose` INTEGER NOT NULL,
                `retryCount` INTEGER NOT NULL,
                `retryDelayMs` INTEGER NOT NULL,
                `onToolFailure` TEXT NOT NULL,
                `sensitiveActionsRequireApproval` INTEGER NOT NULL,
                `onUnrecoverableError` TEXT NOT NULL,
                PRIMARY KEY(`conversationId`)
            )
            """.trimIndent(),
        )
    }
}
