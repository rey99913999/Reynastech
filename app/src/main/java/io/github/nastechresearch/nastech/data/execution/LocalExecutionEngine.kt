package io.github.nastechresearch.nastech.data.execution

import io.github.nastechresearch.nastech.data.task.TaskManager
import io.github.nastechresearch.nastech.data.task.TaskRecoveryAction
import io.github.nastechresearch.nastech.data.ai.tools.AssistantToolPermissionResolver
import io.github.nastechresearch.nastech.data.ai.tools.ToolPermissionDecision
import io.github.nastechresearch.nastech.data.model.Assistant
import kotlinx.coroutines.delay
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

class LocalExecutionEngine(
    private val taskManager: TaskManager,
    private val telemetry: ExecutionTelemetry,
) {
    suspend fun execute(
        plan: StructuredExecutionPlan,
        tools: List<Tool>,
        isToolAutoApproved: suspend (String) -> Boolean = { false },
    ): ExecutionPlanResult =
        execute(
            plan = plan,
            registry = ExecutionToolRegistry(tools).also { registry ->
                registry.loadTools(tools.map { it.name })
            },
            isToolAutoApproved = isToolAutoApproved,
        )

    suspend fun execute(
        plan: StructuredExecutionPlan,
        registry: ExecutionToolRegistry,
        isToolAutoApproved: suspend (String) -> Boolean = { false },
        toolPermissionResolver: AssistantToolPermissionResolver? = null,
        assistant: Assistant? = null,
    ): ExecutionPlanResult {
        require(plan.goal.isNotBlank()) { "Execution plan goal cannot be blank" }
        require(plan.steps.isNotEmpty()) { "Execution plan requires at least one step" }
        require(plan.steps.size <= 32) { "Execution plan is limited to 32 steps per local run" }


        val results = mutableListOf<ExecutionStepResult>()
        val policy = plan.taskId?.let { taskManager.getTask(it) }
            ?.let { taskManager.getPolicy(it.conversationId) }

        telemetry.recordPlan(
            taskId = plan.taskId,
            stepCount = plan.steps.size,
        )

        for (step in plan.steps) {
            if (step.kind != ExecutionStepKind.TOOL) {
                results += ExecutionStepResult(
                    stepId = step.id,
                    taskStepId = step.taskStepId,
                    status = if (step.kind == ExecutionStepKind.RETURN) {
                        ExecutionStepStatus.SUCCESS
                    } else {
                        ExecutionStepStatus.SKIPPED
                    },
                )
                continue
            }

            val toolName = step.toolName
            if (toolName.isNullOrBlank()) {
                results += failure(plan, step, "toolName is required")
                break
            }

            if (step.level >= ExecutionLevel.VISION) {
                results += ExecutionStepResult(
                    stepId = step.id,
                    taskStepId = step.taskStepId,
                    toolName = toolName,
                    status = ExecutionStepStatus.REPLAN_REQUIRED,
                    error = "Step requires a higher execution level: " + step.level.name,
                )
                break
            }

            val resolvedName = sequenceOf(toolName)
                .plus(step.alternativeToolNames.asSequence())
                .mapNotNull { candidate -> registry.findLoaded(candidate)?.let { candidate to it } }
                .firstOrNull()
            if (resolvedName == null) {
                results += failure(plan, step, "Tool " + toolName + " is not loaded or unavailable")
                break
            }
            val resolvedToolName = resolvedName.first
            val tool = resolvedName.second

            if (toolPermissionResolver != null && assistant != null) {
                when (
                    toolPermissionResolver.decide(
                        assistant = assistant,
                        registry = registry,
                        toolName = resolvedToolName,
                        args = step.args,
                        taskId = plan.taskId,
                    ).action
                ) {
                    ToolPermissionDecision.Action.ALLOW -> Unit
                    ToolPermissionDecision.Action.ASK -> {
                        results += ExecutionStepResult(
                            stepId = step.id,
                            taskStepId = step.taskStepId,
                            toolName = toolName,
                            status = ExecutionStepStatus.APPROVAL_REQUIRED,
                            error = "Approval is required for " + resolvedToolName + " before local execution.",
                        )
                        break
                    }
                    ToolPermissionDecision.Action.DENY -> {
                        results += ExecutionStepResult(
                            stepId = step.id,
                            taskStepId = step.taskStepId,
                            toolName = toolName,
                            status = ExecutionStepStatus.REPLAN_REQUIRED,
                            error = "Tool " + resolvedToolName + " was denied by the Assistant tool policy. Re-plan without it.",
                        )
                        break
                    }
                }
            } else if (registry.requiresApproval(resolvedToolName, step.args) &&
                !isToolAutoApproved(registry.approvalKey(resolvedToolName))
            ) {
                results += ExecutionStepResult(
                    stepId = step.id,
                    taskStepId = step.taskStepId,
                    toolName = toolName,
                    status = ExecutionStepStatus.APPROVAL_REQUIRED,
                    error = "Approval is required for " + resolvedToolName + " before local execution.",
                )
                break
            }

            val maxRetries = (policy?.retryCount ?: 1).coerceIn(0, 3)
            val delayMs = (policy?.retryDelayMs ?: 750L).coerceIn(0L, 5_000L)
            var attempt = 0
            var successResult: ExecutionStepResult? = null
            var lastError = "local execution failed"

            while (attempt <= maxRetries) {
                attempt++
                try {
                    if (plan.taskId != null && step.taskStepId != null) {
                        taskManager.beginStep(plan.taskId, step.taskStepId)
                    }

                    val parts = tool.execute(step.args)
                    val rawOutput = parts.extractText()
                    val processed = ToolOutputPreprocessor.preprocess(rawOutput)
                    val expected = step.expectedResult?.trim().orEmpty()
                    val verified = expected.isBlank() || processed.contains(expected, ignoreCase = true)

                    if (step.requiresVerification && !verified) {
                        throw IllegalStateException(
                            "Verification failed: expected '" + expected.take(160) + "'",
                        )
                    }

                    var checkpointId: String? = null
                    if (plan.taskId != null && step.taskStepId != null) {
                        taskManager.completeStep(
                            taskId = plan.taskId,
                            stepId = step.taskStepId,
                            actualResult = processed.take(4_000),
                            checkpoint = false,
                        )
                        checkpointId = taskManager.saveCheckpoint(
                            plan.taskId,
                            "Completed " + step.id,
                        )
                    }

                    telemetry.recordToolExecution(
                        taskId = plan.taskId,
                        outputChars = processed.length,
                        local = true,
                        recoveryAttempt = attempt > 1,
                    )

                    successResult = ExecutionStepResult(
                        stepId = step.id,
                        taskStepId = step.taskStepId,
                        toolName = resolvedToolName,
                        status = ExecutionStepStatus.SUCCESS,
                        attempts = attempt,
                        output = processed.take(4_000),
                        checkpointId = checkpointId,
                    )
                    break
                } catch (t: Throwable) {
                    lastError = (t.message ?: t.javaClass.simpleName).take(500)
                    if (attempt <= maxRetries) {
                        telemetry.recordToolExecution(
                            taskId = plan.taskId,
                            outputChars = 0,
                            local = true,
                            recoveryAttempt = true,
                        )
                        delay(delayMs)
                    }
                }
            }

            if (successResult != null) {
                results += successResult!!
                continue
            }

            results += failure(plan, step, lastError)
            break
        }

        val done = results.count { it.status == ExecutionStepStatus.SUCCESS }
        val status = when {
            results.any { it.status == ExecutionStepStatus.REPLAN_REQUIRED } -> "REPLAN_REQUIRED"
            results.any { it.status == ExecutionStepStatus.APPROVAL_REQUIRED } -> "WAITING_FOR_APPROVAL"
            results.any { it.status == ExecutionStepStatus.FAILED } -> "FAILED"
            done == plan.steps.size -> "SUCCESS"
            else -> "PARTIAL"
        }

        val failed = results.firstOrNull {
            it.status == ExecutionStepStatus.REPLAN_REQUIRED || it.status == ExecutionStepStatus.FAILED
        }

        val partialReplan = failed?.let {
            telemetry.recordPartialReplan(plan.taskId)
            val failedIndex = plan.steps.indexOfFirst { step -> step.id == it.stepId }
            PartialReplanRequest(
                goal = plan.goal,
                completedSteps = results.takeWhile { result -> result.stepId != it.stepId },
                failedStepId = it.stepId,
                failureReason = it.error.orEmpty(),
                remainingSteps = if (failedIndex >= 0) {
                    plan.steps.drop(failedIndex + 1)
                } else {
                    emptyList()
                },
            )
        }

        val llmCallsAvoided = (done - 1).coerceAtLeast(0)
        telemetry.recordLlmCallsAvoided(plan.taskId, llmCallsAvoided)

        return ExecutionPlanResult(
            goal = plan.goal,
            status = status,
            completedCount = done,
            attemptedCount = results.size,
            results = results,
            partialReplan = partialReplan,
            llmCallsAvoided = llmCallsAvoided,
        )
    }

    private suspend fun failure(
        plan: StructuredExecutionPlan,
        step: StructuredExecutionStep,
        reason: String,
    ): ExecutionStepResult {
        if (plan.taskId != null && step.taskStepId != null) {
            taskManager.recordStepFailure(
                taskId = plan.taskId,
                stepId = step.taskStepId,
                error = reason,
                recovery = TaskRecoveryAction.REPLAN,
            )
        }
        return ExecutionStepResult(
            stepId = step.id,
            taskStepId = step.taskStepId,
            toolName = step.toolName,
            status = ExecutionStepStatus.REPLAN_REQUIRED,
            error = reason,
        )
    }

    private fun List<UIMessagePart>.extractText(): String =
        filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
            .trim()
}
