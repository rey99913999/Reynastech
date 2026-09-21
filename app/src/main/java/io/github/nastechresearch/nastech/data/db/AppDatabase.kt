package io.github.nastechresearch.nastech.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import me.rerere.ai.core.TokenUsage
import io.github.nastechresearch.nastech.data.agentrun.AgentRun
import io.github.nastechresearch.nastech.data.agentrun.AgentRunDao
import io.github.nastechresearch.nastech.data.db.dao.ConversationDAO
import io.github.nastechresearch.nastech.data.db.dao.ConversationCompactionDAO
import io.github.nastechresearch.nastech.data.db.dao.ConversationMemorySettingsDao
import io.github.nastechresearch.nastech.data.db.dao.ConversationMemoryStateDao
import io.github.nastechresearch.nastech.data.db.dao.ConversationMemorySummaryDao
import io.github.nastechresearch.nastech.data.db.dao.FavoriteDAO
import io.github.nastechresearch.nastech.data.db.dao.FolderDAO
import io.github.nastechresearch.nastech.data.db.dao.GenMediaDAO
import io.github.nastechresearch.nastech.data.db.dao.ManagedFileDAO
import io.github.nastechresearch.nastech.data.db.dao.MemoryCandidateDao
import io.github.nastechresearch.nastech.data.db.dao.ConversationAgentConfigDao
import io.github.nastechresearch.nastech.data.db.dao.AgentTemplateDao
import io.github.nastechresearch.nastech.data.task.TaskAuditLogDao
import io.github.nastechresearch.nastech.data.task.TaskCheckpointDao
import io.github.nastechresearch.nastech.data.task.TaskDao
import io.github.nastechresearch.nastech.data.task.TaskSettingsDao
import io.github.nastechresearch.nastech.data.task.TaskStepDao
import io.github.nastechresearch.nastech.data.db.dao.MemoryDAO
import io.github.nastechresearch.nastech.data.db.dao.MessageNodeDAO
import io.github.nastechresearch.nastech.data.db.dao.ScheduledJobDao
import io.github.nastechresearch.nastech.data.db.dao.ScheduledJobRunDao
import io.github.nastechresearch.nastech.data.db.dao.SshHostDao
import io.github.nastechresearch.nastech.data.db.dao.TelegramChatDao
import io.github.nastechresearch.nastech.data.db.dao.WorkspaceDAO
import io.github.nastechresearch.nastech.data.db.entity.ConversationEntity
import io.github.nastechresearch.nastech.data.db.entity.ConversationCompactionEntity
import io.github.nastechresearch.nastech.data.db.entity.ConversationMemorySettingsEntity
import io.github.nastechresearch.nastech.data.db.entity.ConversationAgentConfigEntity
import io.github.nastechresearch.nastech.data.db.entity.AgentTemplateEntity
import io.github.nastechresearch.nastech.data.db.entity.ConversationMemoryStateEntity
import io.github.nastechresearch.nastech.data.db.entity.ConversationMemorySummaryEntity
import io.github.nastechresearch.nastech.data.db.entity.FavoriteEntity
import io.github.nastechresearch.nastech.data.db.entity.FolderEntity
import io.github.nastechresearch.nastech.data.db.entity.GenMediaEntity
import io.github.nastechresearch.nastech.data.db.entity.ManagedFileEntity
import io.github.nastechresearch.nastech.data.db.entity.MemoryCandidateEntity
import io.github.nastechresearch.nastech.data.db.entity.MemoryEntity
import io.github.nastechresearch.nastech.data.db.entity.MessageNodeEntity
import io.github.nastechresearch.nastech.data.db.entity.ScheduledJobEntity
import io.github.nastechresearch.nastech.data.db.entity.ScheduledJobRunEntity
import io.github.nastechresearch.nastech.data.db.entity.SshHostEntity
import io.github.nastechresearch.nastech.data.db.entity.TelegramChatEntity
import io.github.nastechresearch.nastech.data.db.entity.WorkspaceEntity
import io.github.nastechresearch.nastech.data.task.TaskAuditLogEntity
import io.github.nastechresearch.nastech.data.task.TaskCheckpointEntity
import io.github.nastechresearch.nastech.data.task.TaskEntity
import io.github.nastechresearch.nastech.data.task.TaskSettingsEntity
import io.github.nastechresearch.nastech.data.task.TaskStepEntity
import io.github.nastechresearch.nastech.data.execution.TaskExecutionUsageEntity
import io.github.nastechresearch.nastech.data.execution.TaskExecutionUsageDao
import io.github.nastechresearch.nastech.data.db.migrations.Migration_16_17
import io.github.nastechresearch.nastech.data.db.migrations.Migration_20_21
import io.github.nastechresearch.nastech.data.db.migrations.Migration_21_22
import io.github.nastechresearch.nastech.data.db.migrations.Migration_22_23
import io.github.nastechresearch.nastech.data.db.migrations.Migration_32_33
import io.github.nastechresearch.nastech.data.db.migrations.Migration_33_34
import io.github.nastechresearch.nastech.data.db.migrations.Migration_8_9
import io.github.nastechresearch.nastech.utils.JsonInstant
import io.github.nastechresearch.nastech.workflow.db.WorkflowDao
import io.github.nastechresearch.nastech.workflow.db.WorkflowEntity
import io.github.nastechresearch.nastech.workflow.db.WorkflowRunDao
import io.github.nastechresearch.nastech.workflow.db.WorkflowRunEntity

@Database(
    entities = [
        ConversationEntity::class,
        ConversationCompactionEntity::class,
        MemoryEntity::class,
        ConversationMemorySettingsEntity::class,
        ConversationMemoryStateEntity::class,
        ConversationMemorySummaryEntity::class,
        MemoryCandidateEntity::class,
        GenMediaEntity::class,
        MessageNodeEntity::class,
        ManagedFileEntity::class,
        FavoriteEntity::class,
        ScheduledJobEntity::class,
        ScheduledJobRunEntity::class,
        SshHostEntity::class,
        TelegramChatEntity::class,
        WorkflowEntity::class,
        WorkflowRunEntity::class,
        AgentRun::class,
        WorkspaceEntity::class,
        FolderEntity::class,
        TaskEntity::class,
        TaskStepEntity::class,
        TaskCheckpointEntity::class,
        TaskAuditLogEntity::class,
        TaskSettingsEntity::class,
        TaskExecutionUsageEntity::class,
        ConversationAgentConfigEntity::class,
        AgentTemplateEntity::class,
    ],
    version = 34,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9, spec = Migration_8_9::class),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 16, to = 17, spec = Migration_16_17::class),
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19),
        AutoMigration(from = 19, to = 20),
        AutoMigration(from = 20, to = 21, spec = Migration_20_21::class),
        AutoMigration(from = 21, to = 22, spec = Migration_21_22::class),
        AutoMigration(from = 22, to = 23, spec = Migration_22_23::class),
        AutoMigration(from = 24, to = 25),
        AutoMigration(from = 25, to = 26),
        AutoMigration(from = 26, to = 27),
        AutoMigration(from = 27, to = 28),
        AutoMigration(from = 28, to = 29),
        AutoMigration(from = 29, to = 30),
        // v31 is handled by explicit Migration_30_31 in DataSourceModule to avoid requiring
        // a generated v31 schema during KSP processing.
    ]
)
@TypeConverters(TokenUsageConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDAO

    abstract fun conversationCompactionDao(): ConversationCompactionDAO

    abstract fun memoryDao(): MemoryDAO

    abstract fun conversationMemorySettingsDao(): ConversationMemorySettingsDao

    abstract fun conversationMemoryStateDao(): ConversationMemoryStateDao

    abstract fun conversationMemorySummaryDao(): ConversationMemorySummaryDao

    abstract fun memoryCandidateDao(): MemoryCandidateDao

    abstract fun genMediaDao(): GenMediaDAO

    abstract fun messageNodeDao(): MessageNodeDAO

    abstract fun managedFileDao(): ManagedFileDAO

    abstract fun favoriteDao(): FavoriteDAO

    abstract fun scheduledJobDao(): ScheduledJobDao

    abstract fun scheduledJobRunDao(): ScheduledJobRunDao

    abstract fun sshHostDao(): SshHostDao

    abstract fun telegramChatDao(): TelegramChatDao

    abstract fun workflowDao(): WorkflowDao

    abstract fun workflowRunDao(): WorkflowRunDao

    abstract fun agentRunDao(): AgentRunDao

    abstract fun workspaceDao(): WorkspaceDAO

    abstract fun folderDao(): FolderDAO

    abstract fun taskDao(): TaskDao

    abstract fun taskStepDao(): TaskStepDao

    abstract fun taskCheckpointDao(): TaskCheckpointDao

    abstract fun taskAuditLogDao(): TaskAuditLogDao

    abstract fun taskSettingsDao(): TaskSettingsDao

    abstract fun taskExecutionUsageDao(): TaskExecutionUsageDao

    abstract fun conversationAgentConfigDao(): ConversationAgentConfigDao

    abstract fun agentTemplateDao(): AgentTemplateDao
}

object TokenUsageConverter {
    @TypeConverter
    fun fromTokenUsage(usage: TokenUsage?): String {
        return JsonInstant.encodeToString(usage)
    }

    @TypeConverter
    fun toTokenUsage(usage: String): TokenUsage? {
        return JsonInstant.decodeFromString(usage)
    }
}
