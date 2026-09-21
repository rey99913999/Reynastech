package io.github.nastechresearch.nastech.data.agent

import io.github.nastechresearch.nastech.data.task.TaskFailureCategory
import io.github.nastechresearch.nastech.data.task.TaskManager
import io.github.nastechresearch.nastech.data.task.TaskRecoveryAction
import io.github.nastechresearch.nastech.data.task.TaskStatus
import io.github.nastechresearch.nastech.data.task.TaskStepEntity
import io.github.nastechresearch.nastech.data.task.TaskStepSpec
import io.github.nastechresearch.nastech.data.task.TaskStepStatus
import io.github.nastechresearch.nastech.subagent.SubAgentEngine
import io.github.nastechresearch.nastech.subagent.SubAgentRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class ConversationAgentRuntime(
    private val configRepository: AgentConfigRepository,
    private val taskManager: TaskManager,
    private val subAgentEngine: SubAgentEngine,
) {
    suspend fun getConfig(conversationId: String): ConversationAgentConfig =
        configRepository.get(conversationId)

    suspend fun isEnabled(conversationId: String): Boolean =
        configRepository.get(conversationId).enabled

    suspend fun run(
        conversationId: String,
        parentAssistantId: String,
        goal: String,
        availableTools: List<String>,
    ): AgentRuntimeResult {
        val config = configRepository.get(conversationId)
        if (!config.enabled) {
            return AgentRuntimeResult(status = "DISABLED", summary = "Agent Runtime is disabled for this conversation.")
        }

        val selected = activeWorkflow(config, goal)
        if (selected.isEmpty()) {
            return AgentRuntimeResult(status = "NO_AGENTS", summary = "No enabled Agents matched the current workflow.")
        }

        val effective = if (config.autonomyLevel == AgentAutonomyLevel.PLAN_ONLY) {
            selected.filter { it.role == AgentRole.PLANNER || it.role == AgentRole.SUPERVISOR }
        } else {
            selected
        }.take(config.maxTurns.coerceIn(1, 32))

        if (effective.isEmpty()) {
            return AgentRuntimeResult(status = "NO_PLANNER", summary = "Plan-only mode requires an enabled Planner Agent.")
        }

        val taskId = taskManager.createTask(
            conversationId = conversationId,
            goal = goal,
            steps = effective.map { agent ->
                TaskStepSpec(
                    logicalGoal = "Agent: " + agent.name,
                    executionInstruction = encodeAgentInstruction(agent),
                    expectedResult = "Agent returns a usable " + agent.outputType.name.lowercase() + " result.",
                    requiresVerification = false,
                    verificationHint = "Review " + agent.name + "'s result before retrying it.",
                )
            },
        )
        taskManager.startTask(taskId)

        return executeTask(
            taskId = taskId,
            config = config,
            parentAssistantId = parentAssistantId,
            conversationId = conversationId,
            goal = goal,
            availableTools = availableTools,
        )
    }

    suspend fun executeExistingTask(
        taskId: String,
        parentAssistantId: String,
        availableTools: List<String>,
    ): AgentRuntimeResult {
        val task = taskManager.getTask(taskId)
            ?: return AgentRuntimeResult(status = "NOT_FOUND", summary = "Task not found.")
        val config = configRepository.get(task.conversationId)
        return executeTask(
            taskId = taskId,
            config = config,
            parentAssistantId = parentAssistantId,
            conversationId = task.conversationId,
            goal = task.goal,
            availableTools = availableTools,
        )
    }

    private suspend fun executeTask(
        taskId: String,
        config: ConversationAgentConfig,
        parentAssistantId: String,
        conversationId: String,
        goal: String,
        availableTools: List<String>,
    ): AgentRuntimeResult {
        val steps = taskManager.snapshotSteps(taskId)
        if (steps.isEmpty()) return AgentRuntimeResult(status = "EMPTY", taskId = taskId)

        val previousResults = mutableListOf<String>()
        val completedAgents = mutableListOf<String>()

        for (step in steps.sortedBy { it.orderIndex }) {
            currentCoroutineContext().ensureActive()
            val currentTask = taskManager.getTask(taskId) ?: break

            if (currentTask.status == TaskStatus.PAUSED.name ||
                currentTask.status == TaskStatus.WAITING_FOR_USER.name ||
                currentTask.status == TaskStatus.CANCELLED.name
            ) {
                return AgentRuntimeResult(
                    status = currentTask.status,
                    taskId = taskId,
                    summary = currentTask.pausedReason.orEmpty(),
                    completedAgents = completedAgents,
                    waitingForApproval = currentTask.status == TaskStatus.WAITING_FOR_USER.name,
                )
            }

            if (step.status == TaskStepStatus.SUCCESS.name) {
                decodeAgentId(step)?.let(completedAgents::add)
                step.actualResult?.takeIf(String::isNotBlank)?.let(previousResults::add)
                continue
            }

            val agentId = decodeAgentId(step)
            val agent = config.agents.firstOrNull { it.id == agentId && it.enabled }
                ?: run {
                    taskManager.recordStepFailure(
                        taskId = taskId,
                        stepId = step.id,
                        error = "Agent is no longer enabled.",
                        category = TaskFailureCategory.PERMISSION,
                        recovery = TaskRecoveryAction.ASK_USER,
                    )
                    return AgentRuntimeResult(
                        status = "WAITING_FOR_USER",
                        taskId = taskId,
                        summary = "Agent permissions changed. Review Conversation Customization and resume.",
                        waitingForApproval = true,
                        completedAgents = completedAgents,
                    )
                }

            val permittedTools = resolveTools(agent, availableTools)
            if (agent.role == AgentRole.EXECUTOR && permittedTools.any(::isSensitiveTool)) {
                taskManager.recordStepFailure(
                    taskId = taskId,
                    stepId = step.id,
                    error = "Sensitive tools were withheld by the Agent Runtime policy.",
                    category = TaskFailureCategory.PERMISSION,
                    recovery = TaskRecoveryAction.ASK_USER,
                )
                return AgentRuntimeResult(
                    status = "WAITING_FOR_USER",
                    taskId = taskId,
                    summary = "Sensitive tools are blocked. Review the Executor permissions, then Resume from Task State.",
                    completedAgents = completedAgents,
                    waitingForApproval = true,
                )
            }

            taskManager.beginStep(taskId, step.id)

            val prompt = buildString {
                append("Original user goal:\n")
                append(goal.trim())
                append("\n\n")
                if (config.instructions.isNotBlank()) {
                    append("Conversation Agent instructions:\n")
                    append(config.instructions.trim())
                    append("\n\n")
                }
                if (previousResults.isNotEmpty()) {
                    append("Previous Agent results:\n")
                    append(previousResults.takeLast(3).joinToString("\n\n"))
                    append("\n\n")
                }
                append("Your role: ")
                append(agent.role.name)
                append("\nComplete only this role's responsibility and return a concise result for the next Agent.")
            }

            val request = SubAgentRequest(
                task = prompt,
                modelId = agent.modelId,
                systemPrompt = agent.systemInstructions.ifBlank {
                    "You are the " + agent.role.name.lowercase() + " Agent in a bounded workflow."
                },
                tools = permittedTools,
                runInBackground = false,
                timeoutSeconds = 300,
                maxTrips = (3 + config.maxDepth.coerceIn(1, 8)).coerceAtMost(12),
                label = agent.name.take(60),
            )

            when (val result = subAgentEngine.dispatch(parentAssistantId, conversationId, request)) {
                is SubAgentEngine.DispatchResult.Reject -> {
                    taskManager.recordStepFailure(
                        taskId = taskId,
                        stepId = step.id,
                        error = result.error + ": " + result.detail,
                        category = TaskFailureCategory.TOOL,
                        recovery = TaskRecoveryAction.RETRY,
                    )
                    return AgentRuntimeResult(
                        status = "FAILED",
                        taskId = taskId,
                        summary = result.detail,
                        completedAgents = completedAgents,
                        failure = result.detail,
                    )
                }

                is SubAgentEngine.DispatchResult.Ok -> {
                    val output = result.run.result?.trim().orEmpty()
                    if (output.isBlank()) {
                        taskManager.recordStepFailure(
                            taskId = taskId,
                            stepId = step.id,
                            error = "Agent returned no usable output.",
                            category = TaskFailureCategory.INVALID_RESULT,
                            recovery = TaskRecoveryAction.REPLAN,
                        )
                        return AgentRuntimeResult(
                            status = "FAILED",
                            taskId = taskId,
                            summary = agent.name + " returned no usable output.",
                            completedAgents = completedAgents,
                            failure = "empty_agent_output",
                        )
                    }
                    taskManager.completeStep(
                        taskId = taskId,
                        stepId = step.id,
                        actualResult = output.take(4_000),
                        checkpoint = true,
                        checkpointNote = agent.name + " completed",
                    )
                    previousResults += output.take(4_000)
                    completedAgents += agent.id
                }
            }
        }

        val task = taskManager.getTask(taskId)
        return AgentRuntimeResult(
            status = task?.status ?: TaskStatus.COMPLETED.name,
            taskId = taskId,
            summary = previousResults.lastOrNull() ?: "Agent workflow completed.",
            completedAgents = completedAgents,
        )
    }

    private fun activeWorkflow(config: ConversationAgentConfig, goal: String): List<AgentDefinition> {
        val enabled = config.agents.filter { it.enabled }
        if (enabled.isEmpty()) return emptyList()

        val edges = config.normalizedWorkflow()
        val incoming = edges.map { it.toAgentId }.toSet()
        val starts = enabled.filter { it.id !in incoming }.ifEmpty { enabled.take(1) }
        val nextById = edges.groupBy { it.fromAgentId }
        val queue = java.util.ArrayDeque<AgentDefinition>()
        starts.forEach(queue::add)
        val visited = mutableSetOf<String>()
        val ordered = mutableListOf<AgentDefinition>()
        var depth = 0

        while (
            queue.isNotEmpty() &&
            ordered.size < config.maxTurns.coerceIn(1, 32) &&
            depth++ <= config.maxDepth.coerceIn(1, 32)
        ) {
            val agent = queue.removeFirst()
            if (!visited.add(agent.id)) continue
            if (!shouldActivate(agent, goal, enabled.size)) continue
            ordered += agent
            nextById[agent.id].orEmpty().forEach { edge ->
                enabled.firstOrNull { it.id == edge.toAgentId }?.let(queue::add)
            }
        }
        return ordered
    }

    private fun shouldActivate(agent: AgentDefinition, goal: String, agentCount: Int): Boolean {
        val g = goal.lowercase()
        return when (agent.activationCondition) {
            AgentActivationCondition.ALWAYS -> true
            AgentActivationCondition.HAS_VISUAL_SIGNAL ->
                listOf("image", "screenshot", "screen", "visual", "ocr").any(g::contains)
            AgentActivationCondition.ON_FAILURE -> listOf("recover", "failed", "error").any(g::contains)
            AgentActivationCondition.ON_REVIEW_REQUEST -> listOf("review", "check", "verify").any(g::contains)
            AgentActivationCondition.MULTI_AGENT -> agentCount > 1
        }
    }

    private fun resolveTools(agent: AgentDefinition, availableTools: List<String>): List<String> {
        val allowed = agent.allowedTools.map(String::trim).filter(String::isNotBlank)
        val denied = agent.deniedTools.map(String::trim).filter(String::isNotBlank)

        val candidate = if (allowed.any { it == "*" }) {
            availableTools
        } else {
            availableTools.filter { tool -> allowed.any { it.equals(tool, ignoreCase = true) } }
        }

        return candidate.filterNot { tool ->
            denied.any { deniedName ->
                deniedName == "*" || deniedName.equals(tool, ignoreCase = true)
            }
        }
    }

    private fun encodeAgentInstruction(agent: AgentDefinition): String =
        "AGENT_ID=" + agent.id + "\nAGENT_ROLE=" + agent.role.name + "\nAGENT_NAME=" + agent.name

    private fun decodeAgentId(step: TaskStepEntity): String? =
        Regex("AGENT_ID=([^\\n]+)").find(step.executionInstruction)?.groupValues?.getOrNull(1)

    companion object {
        fun isSensitiveTool(toolName: String): Boolean {
            val value = toolName.lowercase()
            return listOf(
                "delete", "send_message", "send_sms", "call", "payment", "purchase",
                "transfer", "shell", "adb", "termux", "shizuku", "write_file",
                "edit_file", "create_file", "move_file", "rename_file",
            ).any(value::contains)
        }
    }
}
