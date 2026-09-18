package io.github.nastechresearch.nastech.data.agentconfig

import io.github.nastechresearch.nastech.data.datastore.Settings
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import kotlin.uuid.Uuid

class ConversationAgentRuntime {
    fun selectAgentForTurn(
        config: ConversationAgentConfig,
        hasVisualInput: Boolean,
    ): ConversationAgentDefinition? {
        if (!config.enabled || !config.autonomousTaskMode) return null
        val agents = config.enabledAgents
        if (agents.isEmpty()) return null
        if (hasVisualInput) {
            agents.firstOrNull { it.activation == AgentActivation.NEEDS_VISION || it.role == AgentRole.VISION }
                ?.let { return it }
        }
        agents.firstOrNull { it.activation == AgentActivation.TASK_START && it.role == AgentRole.PLANNER }
            ?.let { return it }
        agents.firstOrNull { it.role == AgentRole.PLANNER }?.let { return it }
        if (agents.size > 1) {
            agents.firstOrNull { it.activation == AgentActivation.MULTI_AGENT || it.role == AgentRole.SUPERVISOR }
                ?.let { return it }
        }
        agents.firstOrNull { it.role == AgentRole.EXECUTOR }?.let { return it }
        return agents.firstOrNull()
    }

    fun resolveModelId(
        agent: ConversationAgentDefinition?,
        fallbackModel: Model,
        settings: Settings,
    ): Uuid {
        val raw = agent?.modelId?.trim().orEmpty()
        val uuid = raw.takeIf { it.isNotBlank() }?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        if (uuid != null && settings.providers.any { provider -> provider.models.any { it.id == uuid } }) return uuid
        val byModelId = raw.takeIf { it.isNotBlank() }?.let { input ->
            settings.providers.flatMap { it.models }.firstOrNull { it.modelId.equals(input, ignoreCase = true) }
        }
        return byModelId?.id ?: fallbackModel.id
    }

    fun filterTools(agent: ConversationAgentDefinition?, tools: List<Tool>): List<Tool> {
        if (agent == null) return tools
        return tools.filter { tool ->
            (agent.allowedTools.isEmpty() || tool.name in agent.allowedTools) && tool.name !in agent.deniedTools
        }
    }

    fun systemAddendum(config: ConversationAgentConfig, agent: ConversationAgentDefinition?): String {
        if (!config.enabled || !config.autonomousTaskMode) return ""
        val agentLines = config.enabledAgents.joinToString("
") {
            "- ${it.name.ifBlank { it.role.name.lowercase() }} role=${it.role.name} activation=${it.activation.name} model=${it.modelId ?: "default"}"
        }
        val workflowLines = config.workflow.joinToString("
") {
            "- ${it.fromAgentId} -> ${it.toAgentId}${it.condition.takeIf(String::isNotBlank)?.let { condition -> " when $condition" } ?: ""}"
        }
        return buildString {
            appendLine("Autonomous Task Mode is enabled.")
            appendLine("Autonomy level: ${config.autonomyLevel.name}. Never assume unlimited autonomy.")
            appendLine("Lifecycle: Goal -> Planning -> Execution -> Verification -> Recovery -> Replanning -> Completion.")
            appendLine("Active agent: ${agent?.name ?: "default"}.")
            if (config.instructions.isNotBlank()) appendLine("Workspace instructions: ${config.instructions.take(2000)}")
            if (agent?.systemInstructions?.isNotBlank() == true) appendLine("Agent instructions: ${agent.systemInstructions.take(2000)}")
            if (agentLines.isNotBlank()) {
                appendLine("Configured agents:")
                appendLine(agentLines)
            }
            if (workflowLines.isNotBlank()) {
                appendLine("Workflow:")
                appendLine(workflowLines)
            }
            appendLine("Per-agent tool permissions are mandatory; do not bypass them.")
            appendLine("Loop guard: max depth ${config.maxAgentDepth}, turns ${config.maxTurns}, tool calls ${config.maxToolCalls}.")
            when (config.autonomyLevel) {
                AgentAutonomyLevel.PLAN_ONLY -> appendLine("Plan Only: present the plan and wait for user approval before acting.")
                AgentAutonomyLevel.SAFE_AUTO -> appendLine("Safe Auto: perform low-risk actions automatically; stop for sensitive actions.")
                AgentAutonomyLevel.AUTONOMOUS -> appendLine("Autonomous: manage the task end-to-end, but stop when safe continuation is not possible.")
            }
        }.trim()
    }

    fun autoApprove(config: ConversationAgentConfig, toolName: String, baseApproved: Boolean): Boolean {
        if (!config.enabled || !config.autonomousTaskMode) return baseApproved
        if (config.autonomyLevel == AgentAutonomyLevel.PLAN_ONLY) return false
        if (isSensitiveTool(toolName)) return false
        return baseApproved
    }

    fun isSensitiveTool(name: String): Boolean {
        val normalized = name.lowercase()
        return normalized.contains("sms") ||
            normalized.contains("call") ||
            normalized.contains("telegram_send") ||
            normalized.contains("send_") ||
            normalized.contains("delete") ||
            normalized.contains("remove") ||
            normalized.contains("ssh") ||
            normalized.contains("shizuku") ||
            normalized.contains("termux") ||
            normalized.contains("write") ||
            normalized.contains("set_") ||
            normalized.contains("payment") ||
            normalized.contains("purchase")
    }

    fun hasAgentLoop(config: ConversationAgentConfig): Boolean {
        val ids = config.enabledAgents.map { it.id }.toSet()
        val adjacency = config.workflow
            .filter { it.fromAgentId in ids && it.toAgentId in ids }
            .groupBy { it.fromAgentId }
            .mapValues { (_, edges) -> edges.map { it.toAgentId } }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(id: String): Boolean {
            if (id in visiting) return true
            if (id in visited) return false
            visiting += id
            adjacency[id].orEmpty().forEach { next -> if (visit(next)) return true }
            visiting -= id
            visited += id
            return false
        }
        return ids.any(::visit)
    }
}
