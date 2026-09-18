package io.github.nastechresearch.nastech.data.agentconfig

import io.github.nastechresearch.nastech.data.db.AppDatabase
import io.github.nastechresearch.nastech.data.memory.ConversationMemoryEngine
import io.github.nastechresearch.nastech.data.task.TaskManager
import io.github.nastechresearch.nastech.data.task.TaskPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

class ConversationAgentConfigRepository(
    private val database: AppDatabase,
    private val memoryEngine: ConversationMemoryEngine,
    private val taskManager: TaskManager,
) {
    private val dao get() = database.conversationAgentConfigDao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun get(conversationId: String): ConversationAgentConfig {
        val stored = dao.getConfig(conversationId)
            ?: return ConversationAgentConfig(conversationId = conversationId)
        return runCatching {
            json.decodeFromString<ConversationAgentConfig>(stored.config_json).forConversation(conversationId)
        }.getOrDefault(ConversationAgentConfig(conversationId = conversationId))
    }

    suspend fun save(config: ConversationAgentConfig) {
        val normalized = config.forConversation(config.conversationId)
        dao.upsertConfig(
            ConversationAgentConfigEntity(
                conversation_id = normalized.conversationId,
                config_json = json.encodeToString(normalized),
                updated_at = System.currentTimeMillis(),
            )
        )

        memoryEngine.setMode(normalized.conversationId, normalized.memoryMode)
        memoryEngine.setUseInContext(normalized.conversationId, normalized.memoryUseInContext)
        memoryEngine.setTokenBudget(normalized.conversationId, normalized.memoryTokenBudget)

        val recovery = normalized.taskRecoveryPolicy
        taskManager.savePolicy(
            normalized.conversationId,
            TaskPolicy(
                saveCheckpoints = recovery.saveCheckpoints,
                resumeAfterAppClose = recovery.resumeAfterAppClose,
                retryCount = recovery.retryCount,
                retryDelayMs = recovery.retryDelayMs,
                sensitiveActionsRequireApproval = recovery.sensitiveActionsRequireApproval,
            ),
        )
    }

    suspend fun delete(conversationId: String) = dao.deleteConfig(conversationId)

    fun observeTemplates(): Flow<List<ConversationAgentTemplate>> =
        dao.observeTemplates().map { entities -> entities.mapNotNull(::decodeTemplate) }

    suspend fun getTemplates(): List<ConversationAgentTemplate> =
        dao.getTemplates().mapNotNull(::decodeTemplate)

    suspend fun saveTemplate(name: String, sourceConfig: ConversationAgentConfig): ConversationAgentTemplate? {
        val cleanName = name.trim().take(80)
        if (cleanName.isBlank()) return null
        val template = ConversationAgentTemplate(
            id = Uuid.random().toString(),
            name = cleanName,
            config = sourceConfig.copy(conversationId = "").normalized(),
        )
        dao.upsertTemplate(
            ConversationAgentTemplateEntity(
                id = template.id,
                name = template.name,
                config_json = json.encodeToString(template.config),
                updated_at = template.updatedAt,
            )
        )
        return template
    }

    suspend fun applyTemplate(conversationId: String, templateId: String): ConversationAgentConfig? {
        val template = dao.getTemplate(templateId)?.let(::decodeTemplate) ?: return null
        val config = template.config.forConversation(conversationId)
        save(config)
        return config
    }

    suspend fun deleteTemplate(templateId: String) = dao.deleteTemplate(templateId)

    private fun decodeTemplate(entity: ConversationAgentTemplateEntity): ConversationAgentTemplate? =
        runCatching {
            ConversationAgentTemplate(
                id = entity.id,
                name = entity.name,
                config = json.decodeFromString<ConversationAgentConfig>(entity.config_json),
                updatedAt = entity.updated_at,
            )
        }.getOrNull()
}
