package io.github.nastechresearch.nastech.data.agent

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
enum class AgentAutonomyLevel { PLAN_ONLY, SAFE_AUTO, AUTONOMOUS }

@Serializable
enum class AgentRole {
    SUPERVISOR, PLANNER, VISION, EXECUTOR, VERIFIER, RESEARCHER, REVIEWER, MEMORY_MANAGER, CUSTOM
}

@Serializable
enum class AgentActivationCondition { ALWAYS, HAS_VISUAL_SIGNAL, ON_FAILURE, ON_REVIEW_REQUEST, MULTI_AGENT }

@Serializable
enum class AgentOutputType { TEXT, STRUCTURED }

@Serializable
data class AgentDefinition(
    val id: String = Uuid.random().toString(),
    val name: String = "",
    val role: AgentRole = AgentRole.CUSTOM,
    val systemInstructions: String = "",
    val modelId: String? = null,
    val allowedTools: List<String> = emptyList(),
    val deniedTools: List<String> = emptyList(),
    val autonomyLevel: AgentAutonomyLevel = AgentAutonomyLevel.SAFE_AUTO,
    val activationCondition: AgentActivationCondition = AgentActivationCondition.ALWAYS,
    val outputType: AgentOutputType = AgentOutputType.TEXT,
    val enabled: Boolean = true,
)

@Serializable
data class AgentWorkflowEdge(
    val fromAgentId: String,
    val toAgentId: String,
    val condition: String = "",
)

@Serializable
data class ConversationAgentConfig(
    val conversationId: String,
    val enabled: Boolean = false,
    val autonomyLevel: AgentAutonomyLevel = AgentAutonomyLevel.PLAN_ONLY,
    val agents: List<AgentDefinition> = defaultAgentDefinitions(),
    val workflow: List<AgentWorkflowEdge> = emptyList(),
    val instructions: String = "",
    val memoryMode: String = "UNCHANGED",
    val taskRecoveryPolicy: String = "USE_CONVERSATION_TASK_POLICY",
    val maxTurns: Int = 8,
    val maxDepth: Int = 4,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        fun defaultFor(conversationId: String) = ConversationAgentConfig(conversationId = conversationId)
    }
}

@Serializable
data class AgentRuntimeResult(
    val status: String,
    val taskId: String? = null,
    val summary: String = "",
    val completedAgents: List<String> = emptyList(),
    val waitingForApproval: Boolean = false,
    val failure: String? = null,
)

fun defaultAgentDefinitions(): List<AgentDefinition> = listOf(
    AgentDefinition(
        name = "Planner",
        role = AgentRole.PLANNER,
        systemInstructions = "Break the user's goal into a concise executable plan. Do not perform side effects.",
    ),
    AgentDefinition(
        name = "Executor",
        role = AgentRole.EXECUTOR,
        systemInstructions = "Execute the approved plan using only granted tools and verify important actions.",
        allowedTools = listOf("*"),
    ),
    AgentDefinition(
        name = "Verifier",
        role = AgentRole.VERIFIER,
        systemInstructions = "Check whether the goal was actually achieved and report concrete evidence.",
    ),
)

fun defaultWorkflow(configAgents: List<AgentDefinition>): List<AgentWorkflowEdge> {
    val planner = configAgents.firstOrNull { it.role == AgentRole.PLANNER }?.id
    val executor = configAgents.firstOrNull { it.role == AgentRole.EXECUTOR }?.id
    val verifier = configAgents.firstOrNull { it.role == AgentRole.VERIFIER }?.id
    return buildList {
        if (planner != null && executor != null) add(AgentWorkflowEdge(planner, executor))
        if (executor != null && verifier != null) add(AgentWorkflowEdge(executor, verifier))
    }
}

fun ConversationAgentConfig.normalizedWorkflow(): List<AgentWorkflowEdge> {
    val valid = workflow.filter { edge ->
        agents.any { it.id == edge.fromAgentId } && agents.any { it.id == edge.toAgentId }
    }
    return valid.ifEmpty { defaultWorkflow(agents) }
}
