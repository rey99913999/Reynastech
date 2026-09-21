package io.github.nastechresearch.nastech.data.agent

import androidx.room.withTransaction
import io.github.nastechresearch.nastech.data.db.AppDatabase
import io.github.nastechresearch.nastech.data.db.entity.AgentTemplateEntity
import io.github.nastechresearch.nastech.data.db.entity.ConversationAgentConfigEntity
import io.github.nastechresearch.nastech.utils.JsonInstant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

class AgentConfigRepository(
    private val db: AppDatabase,
) {
    suspend fun get(conversationId: String): ConversationAgentConfig {
        val stored = db.conversationAgentConfigDao().get(conversationId)
        if (stored != null) return decodeConfig(stored.configJson, conversationId)
        val created = ConversationAgentConfig.defaultFor(conversationId).copy(workflow = defaultWorkflow(defaultAgentDefinitions()))
        save(created)
        ensureBuiltInTemplates()
        return created
    }

    fun observe(conversationId: String): Flow<ConversationAgentConfig> =
        db.conversationAgentConfigDao().observe(conversationId).map { entity ->
            entity?.let { decodeConfig(it.configJson, conversationId) }
                ?: ConversationAgentConfig.defaultFor(conversationId)
        }

    suspend fun save(config: ConversationAgentConfig) {
        db.conversationAgentConfigDao().upsert(
            ConversationAgentConfigEntity(
                conversationId = config.conversationId,
                configJson = JsonInstant.encodeToString(config.copy(updatedAt = System.currentTimeMillis())),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    fun observeTemplates(): Flow<List<AgentTemplate>> =
        db.agentTemplateDao().observeAll().map { rows -> rows.mapNotNull(::decodeTemplate) }

    suspend fun getTemplates(): List<AgentTemplate> =
        db.agentTemplateDao().getAll().mapNotNull(::decodeTemplate)

    suspend fun saveTemplate(name: String, config: ConversationAgentConfig, description: String = "") {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val now = System.currentTimeMillis()
        db.agentTemplateDao().upsert(
            AgentTemplateEntity(
                templateId = Uuid.random().toString(),
                name = clean,
                description = description.trim(),
                configJson = JsonInstant.encodeToString(config.copy(conversationId = "")),
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    suspend fun applyTemplate(templateId: String, conversationId: String): ConversationAgentConfig? {
        val row = db.agentTemplateDao().getById(templateId) ?: return null
        val config = decodeConfig(row.configJson, conversationId)
        val applied = config.copy(conversationId = conversationId, updatedAt = System.currentTimeMillis())
        save(applied)
        return applied
    }

    suspend fun ensureBuiltInTemplates() {
        db.withTransaction {
            val existing = db.agentTemplateDao().getAll().map { it.name.lowercase() }.toSet()
            val now = System.currentTimeMillis()
            fun add(name: String, description: String, config: ConversationAgentConfig) {
                if (name.lowercase() in existing) return
                db.agentTemplateDao().upsert(
                    AgentTemplateEntity(
                        templateId = "builtin-" + name.lowercase().replace(Regex("[^a-z0-9]+"), "-"),
                        name = name,
                        description = description,
                        configJson = JsonInstant.encodeToString(config.copy(conversationId = "")),
                        createdAt = now,
                        updatedAt = now,
                    )
                )
            }
            add(
                "Android Automation",
                "Planner → Executor → Verifier with safety gates.",
                ConversationAgentConfig.defaultFor("").copy(
                    enabled = true,
                    autonomyLevel = AgentAutonomyLevel.SAFE_AUTO,
                    workflow = defaultWorkflow(defaultAgentDefinitions()),
                ),
            )
            add(
                "Programming",
                "Planner → Executor → Verifier for coding/workspace tasks.",
                ConversationAgentConfig.defaultFor("").copy(
                    enabled = true,
                    autonomyLevel = AgentAutonomyLevel.SAFE_AUTO,
                    instructions = "Use workspace tools carefully and verify changes.",
                    workflow = defaultWorkflow(defaultAgentDefinitions()),
                ),
            )
            val researchAgents = listOf(
                AgentDefinition(name = "Planner", role = AgentRole.PLANNER, systemInstructions = "Plan the research question and evidence needed."),
                AgentDefinition(name = "Researcher", role = AgentRole.RESEARCHER, systemInstructions = "Gather evidence using only granted research tools.", allowedTools = listOf("*")),
                AgentDefinition(name = "Reviewer", role = AgentRole.REVIEWER, systemInstructions = "Review the research output for factual gaps."),
            )
            add(
                "Research",
                "Planner → Researcher → Reviewer.",
                ConversationAgentConfig(
                    conversationId = "",
                    enabled = true,
                    autonomyLevel = AgentAutonomyLevel.SAFE_AUTO,
                    agents = researchAgents,
                    workflow = listOf(
                        AgentWorkflowEdge(researchAgents[0].id, researchAgents[1].id),
                        AgentWorkflowEdge(researchAgents[1].id, researchAgents[2].id),
                    ),
                ),
            )
        }
    }

    private fun decodeConfig(json: String, conversationId: String): ConversationAgentConfig =
        runCatching {
            JsonInstant.decodeFromString<ConversationAgentConfig>(json).copy(conversationId = conversationId)
        }.getOrElse {
            ConversationAgentConfig.defaultFor(conversationId)
        }

    private fun decodeTemplate(row: AgentTemplateEntity): AgentTemplate? =
        runCatching {
            AgentTemplate(
                id = row.templateId,
                name = row.name,
                description = row.description,
                config = JsonInstant.decodeFromString(row.configJson),
            )
        }.getOrNull()
}

data class AgentTemplate(
    val id: String,
    val name: String,
    val description: String,
    val config: ConversationAgentConfig,
)
