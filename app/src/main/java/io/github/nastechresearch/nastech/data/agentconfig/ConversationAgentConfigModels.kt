package io.github.nastechresearch.nastech.data.agentconfig

import io.github.nastechresearch.nastech.data.memory.ConversationMemoryMode
import kotlinx.serialization.Serializable

@Serializable
enum class AgentAutonomyLevel { PLAN_ONLY, SAFE_AUTO, AUTONOMOUS }

@Serializable
enum class AgentRole {
    SUPERVISOR, PLANNER, VISION, EXECUTOR, VERIFIER, RESEARCHER, REVIEWER, MEMORY_MANAGER, CUSTOM,
}

@Serializable
enum class AgentActivation {
    ALWAYS, TASK_START, NEEDS_VISION, ON_FAILURE, ON_REVIEW, MULTI_AGENT, MANUAL,
}

@Serializable
enum class AgentOutputType { TEXT, PLAN, JSON, VERIFICATION, TOOL_RESULT }

@Serializable
data class ConversationAgentDefinition(
    val id: String,
    val name: String,
    val role: AgentRole = AgentRole.CUSTOM,
    val systemInstructions: String = "",
    val modelId: String? = null,
    val allowedTools: Set<String> = emptySet(),
    val deniedTools: Set<String> = emptySet(),
    val autonomyLevel: AgentAutonomyLevel? = null,
    val activation: AgentActivation = AgentActivation.ALWAYS,
    val outputType: AgentOutputType = AgentOutputType.TEXT,
    val enabled: Boolean = true,
)

@Serializable
data class ConversationAgentWorkflowEdge(
    val fromAgentId: String,
    val toAgentId: String,
    val condition: String = "",
)

@Serializable
data class ConversationAgentTaskRecoveryPolicy(
    val saveCheckpoints: Boolean = true,
    val resumeAfterAppClose: Boolean = true,
    val retryCount: Int = 2,
    val retryDelayMs: Long = 2_000L,
    val sensitiveActionsRequireApproval: Boolean = true,
)

@Serializable
data class ConversationAgentConfig(
    val conversationId: String,
    val enabled: Boolean = false,
    val autonomousTaskMode: Boolean = false,
    val autonomyLevel: AgentAutonomyLevel = AgentAutonomyLevel.SAFE_AUTO,
    val instructions: String = "",
    val agents: List<ConversationAgentDefinition> = emptyList(),
    val workflow: List<ConversationAgentWorkflowEdge> = emptyList(),
    val memoryMode: ConversationMemoryMode = ConversationMemoryMode.OFF,
    val memoryUseInContext: Boolean = true,
    val memoryTokenBudget: Int = 2_000,
    val taskRecoveryPolicy: ConversationAgentTaskRecoveryPolicy = ConversationAgentTaskRecoveryPolicy(),
    val knowledgeSkillsBindings: Set<String> = emptySet(),
    val maxAgentDepth: Int = 4,
    val maxTurns: Int = 12,
    val maxToolCalls: Int = 32,
) {
    val enabledAgents: List<ConversationAgentDefinition>
        get() = agents.filter { it.enabled }

    fun normalized(): ConversationAgentConfig {
        val validIds = agents.map { it.id }.toSet()
        return copy(
            agents = agents.map {
                it.copy(
                    name = it.name.trim().take(80),
                    systemInstructions = it.systemInstructions.trim(),
                    allowedTools = it.allowedTools.map { name -> name.trim() }.filter(String::isNotBlank).toSet(),
                    deniedTools = it.deniedTools.map { name -> name.trim() }.filter(String::isNotBlank).toSet(),
                )
            },
            workflow = workflow.filter {
                it.fromAgentId in validIds && it.toAgentId in validIds && it.fromAgentId != it.toAgentId
            },
            memoryTokenBudget = memoryTokenBudget.coerceIn(256, 16_000),
            maxAgentDepth = maxAgentDepth.coerceIn(1, 8),
            maxTurns = maxTurns.coerceIn(1, 32),
            maxToolCalls = maxToolCalls.coerceIn(1, 128),
            taskRecoveryPolicy = taskRecoveryPolicy.copy(
                retryCount = taskRecoveryPolicy.retryCount.coerceIn(0, 3),
                retryDelayMs = taskRecoveryPolicy.retryDelayMs.coerceIn(0L, 5_000L),
            ),
        )
    }
}

@Serializable
data class ConversationAgentTemplate(
    val id: String,
    val name: String,
    val config: ConversationAgentConfig,
    val updatedAt: Long = System.currentTimeMillis(),
)

fun ConversationAgentConfig.forConversation(conversationId: String): ConversationAgentConfig =
    copy(conversationId = conversationId).normalized()
