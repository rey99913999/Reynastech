package io.github.nastechresearch.nastech.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `workflow_runs` ADD COLUMN `traceJson` TEXT NOT NULL DEFAULT '{}'"
        )
        db.execSQL(
            "ALTER TABLE `workflow_runs` ADD COLUMN `parentRunId` INTEGER"
        )
        db.execSQL(
            "ALTER TABLE `workflow_runs` ADD COLUMN `endedAtMs` INTEGER"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `workflow_revisions` (
                `revisionId` TEXT NOT NULL,
                `workflowId` TEXT NOT NULL,
                `sourceRunId` INTEGER,
                `createdAtMs` INTEGER NOT NULL,
                `reason` TEXT NOT NULL,
                `definitionJson` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                PRIMARY KEY(`revisionId`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_workflow_revisions_workflowId_createdAtMs` ON `workflow_revisions` (`workflowId`, `createdAtMs`)"
        )
    }
}
