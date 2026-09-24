package io.github.nastechresearch.nastech.data.execution

import android.content.Context
import io.github.nastechresearch.nastech.data.agent.AgentConfigRepository
import io.github.nastechresearch.nastech.data.agent.AgentRole
import io.github.nastechresearch.nastech.data.ai.tools.AssistantToolPermissionResolver
import io.github.nastechresearch.nastech.data.ai.tools.ToolPermissionDecision
import io.github.nastechresearch.nastech.data.model.Assistant
import io.github.nastechresearch.nastech.data.task.TaskManager
import io.github.nastechresearch.nastech.data.task.TaskRecoveryAction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

class DeviceAgentCore(
    private val context: Context,
    private val telemetry: ExecutionTelemetry,
    private val taskManager: TaskManager,
    private val stateCache: DeviceStateCache,
    private val observer: DeviceObserver,
    private val targetResolver: DeviceTargetResolver,
    private val verifier: DevicePostconditionVerifier,
    private val waiter: DeviceStateWaiter,
    private val recoveryEngine: DeviceRecoveryEngine,
    private val agentConfigRepository: AgentConfigRepository,
) {
    suspend fun executePlan(
        plan: StructuredExecutionPlan,
        registry: ExecutionToolRegistry,
        isToolAutoApproved: suspend (String) -> Boolean = { false },
        toolPermissionResolver: AssistantToolPermissionResolver? = null,
        assistant: Assistant? = null,
    ): ExecutionPlanResult {
        require(plan.steps.size <= 32) { "Device Core plan is limited to 32 steps" }

        val contract = DeviceCapabilityContractBuilder.build(
            context = context,
            registry = registry,
            requiredCapabilities = plan.requiredCapabilities,
            requiredConstraints = plan.requiredConstraints,
        )
        contract.requiredCapabilityFailure()?.let { capability ->
            return preflightFailure(plan, capability)
        }

        val results = mutableListOf<ExecutionStepResult>()
        var before = stateCache.getFresh(1_500L)

        for (step in plan.steps) {
            currentCoroutineContext().ensureActive()

            validateConstraints(plan, step, registry)?.let { reason ->
                results += failure(plan, step, reason)
                break
            }

            when (step.kind) {
                ExecutionStepKind.RETURN -> {
                    results += ExecutionStepResult(
                        stepId = step.id,
                        taskStepId = step.taskStepId,
                        status = ExecutionStepStatus.SUCCESS,
                        lifecycle = DeviceActionLifecycle.VERIFIED,
                        output = step.expectedResult ?: "return_result",
                    )
                    continue
                }

                ExecutionStepKind.VERIFY -> {
                    val predicate = step.postconditions.firstOrNull()
                    if (predicate == null) {
                        results += failure(plan, step, "verify_step_missing_postcondition")
                        break
                    }
                    val verification = verifier.verify(predicate, before)
                    val result = if (verification.verified) {
                        ExecutionStepResult(
                            stepId = step.id,
                            taskStepId = step.taskStepId,
                            status = ExecutionStepStatus.SUCCESS,
                            lifecycle = DeviceActionLifecycle.VERIFIED,
                            output = verification.reason,
                            observationSource = verification.observationSource,
                        )
                    } else {
                        failure(
                            plan = plan,
                            step = step,
                            reason = verification.reason,
                            observationSource = verification.observationSource,
                        )
                    }
                    results += result
                    if (result.status != ExecutionStepStatus.SUCCESS) break
                    before = observer.observe()
                    continue
                }

                ExecutionStepKind.DECIDE -> {
                    results += failure(
                        plan = plan,
                        step = step,
                        reason = "decision_boundary_requires_general_reasoning",
                    )
                    break
                }

                ExecutionStepKind.TOOL,
                ExecutionStepKind.RECOVER -> Unit
            }

            if (plan.taskId != null && step.taskStepId != null) {
                runCatching { taskManager.beginStep(plan.taskId, step.taskStepId) }
            }

            val current = before ?: observer.observe()
            var preconditionFailure: DeviceVerificationResult? = null
            for (precondition in step.preconditions) {
                val verification = verifier.verify(precondition, current)
                if (!verification.verified) {
                    preconditionFailure = verification
                    break
                }
            }

            if (preconditionFailure != null) {
                results += failure(
                    plan = plan,
                    step = step,
                    reason = preconditionFailure.reason,
                    observationSource = preconditionFailure.observationSource,
                )
                break
            }

            val initial = executeAction(
                step = step,
                registry = registry,
                isToolAutoApproved = isToolAutoApproved,
                permissionResolver = toolPermissionResolver,
                assistant = assistant,
                taskId = plan.taskId,
            )

            val action = if (!initial.executed && !initial.requiresApproval && isRecoverable(step)) {
                recoverAction(
                    step = step,
                    firstFailure = initial,
                    registry = registry,
                    isToolAutoApproved = isToolAutoApproved,
                    permissionResolver = toolPermissionResolver,
                    assistant = assistant,
                    taskId = plan.taskId,
                )
            } else {
                initial
            }

            if (action.requiresApproval) {
                results += ExecutionStepResult(
                    stepId = step.id,
                    taskStepId = step.taskStepId,
                    toolName = step.toolName,
                    status = ExecutionStepStatus.APPROVAL_REQUIRED,
                    lifecycle = action.lifecycle,
                    error = action.error,
                )
                break
            }

            if (!action.executed) {
                results += failure(plan, step, action.error ?: "device_action_failed")
                break
            }

            stateCache.invalidate()
            val after = observer.observe(
                captureScreenshot = step.deviceAction == DeviceAction.CAPTURE_SCREENSHOT,
                captureOcr = normalizedPostconditions(step).any {
                    it.type == DevicePostconditionType.NODE_PRESENT ||
                        it.type == DevicePostconditionType.TEXT_PRESENT ||
                        it.type == DevicePostconditionType.RESPONSE_TEXT_NON_EMPTY
                },
            )

            val postconditions = normalizedPostconditions(step)
            if (
                step.deviceAction == null &&
                step.toolName in VERIFICATION_REQUIRED_CORE_TOOLS &&
                postconditions.isEmpty()
            ) {
                results += failure(
                    plan,
                    step,
                    "device_postcondition_required:" + step.toolName,
                )
                break
            }

            var verified = postconditions.isEmpty()
            var verificationAttempts = 0
            var verificationSource: String? = null
            var localWaitMs = action.localWaitMs

            for (predicate in postconditions) {
                verificationAttempts++
                val started = System.currentTimeMillis()
                val result = when (predicate.type) {
                    DevicePostconditionType.SCREEN_CHANGED,
                    DevicePostconditionType.SCREEN_FINGERPRINT_CHANGED ->
                        verifier.verify(predicate, before)
                    else ->
                        waiter.waitFor(predicate, before)
                }
                localWaitMs += System.currentTimeMillis() - started
                verificationSource = result.observationSource
                if (!result.verified) {
                    verified = false
                    break
                }
                verified = true
            }

            if (!verified) {
                // Adapter success is not task success. Because the action may already have
                // changed the device, the core does not blindly repeat it after verification
                // failure. It returns a precise replan boundary for the higher-level layer.
                results += failure(
                    plan = plan,
                    step = step,
                    reason = "postcondition_not_verified",
                    attempts = action.attempts,
                    verificationAttempts = verificationAttempts,
                    observationSource = verificationSource,
                )
                telemetry.recordDeviceEvent(
                    DeviceTelemetryEvent(
                        taskId = plan.taskId,
                        executionPath = "CORE",
                        actionId = step.id,
                        actionType = step.deviceAction?.name ?: step.toolName.orEmpty(),
                        targetSource = action.target?.source?.name,
                        targetConfidence = action.target?.confidence,
                        fallbackPath = action.fallbackPath,
                        recoveryAttempts = action.recoveryAttempts,
                        verificationAttempts = verificationAttempts,
                        verificationOutcome = "FAILED",
                        localWaitMs = localWaitMs,
                        visionRequests = if (action.target?.source == DeviceTargetSource.VISION) 1 else 0,
                        finalStatus = "REPLAN_REQUIRED",
                        replanBoundary = true,
                        artifactState = action.artifactState?.name,
                    ),
                )
                break
            }

            var checkpointId: String? = null
            if (plan.taskId != null && step.taskStepId != null) {
                runCatching {
                    taskManager.completeStep(
                        taskId = plan.taskId,
                        stepId = step.taskStepId,
                        actualResult = action.output?.take(4_000) ?: "Device step verified",
                        checkpoint = false,
                    )
                    checkpointId = taskManager.saveCheckpoint(
                        plan.taskId,
                        "Device Core completed " + step.id,
                    )
                }
            }

            val lifecycle = when {
                action.recoveryAttempts > 0 -> DeviceActionLifecycle.RECOVERED
                postconditions.isNotEmpty() -> DeviceActionLifecycle.VERIFIED
                after.screenshotState != DeviceArtifactState.NONE ->
                    DeviceActionLifecycle.EFFECT_OBSERVED
                else -> DeviceActionLifecycle.EXECUTED
            }

            results += ExecutionStepResult(
                stepId = step.id,
                taskStepId = step.taskStepId,
                toolName = step.toolName,
                status = ExecutionStepStatus.SUCCESS,
                lifecycle = lifecycle,
                attempts = action.attempts,
                output = action.output,
                checkpointId = checkpointId,
                observationSource = verificationSource ?: action.target?.source?.name,
                recoveryAttempts = action.recoveryAttempts,
                artifactState = action.artifactState,
            )

            telemetry.recordDeviceEvent(
                DeviceTelemetryEvent(
                    taskId = plan.taskId,
                    executionPath = "CORE",
                    actionId = step.id,
                    actionType = step.deviceAction?.name ?: step.toolName.orEmpty(),
                    targetSource = action.target?.source?.name,
                    targetConfidence = action.target?.confidence,
                    fallbackPath = action.fallbackPath,
                    recoveryAttempts = action.recoveryAttempts,
                    verificationAttempts = verificationAttempts,
                    verificationOutcome = if (postconditions.isEmpty()) "NOT_REQUIRED" else "VERIFIED",
                    localWaitMs = localWaitMs,
                    visionRequests = if (action.target?.source == DeviceTargetSource.VISION) 1 else 0,
                    finalStatus = "SUCCESS",
                    artifactState = action.artifactState?.name,
                ),
            )

            before = after
        }

        val completed = results.count { it.status == ExecutionStepStatus.SUCCESS }
        val failed = results.firstOrNull {
            it.status == ExecutionStepStatus.REPLAN_REQUIRED ||
                it.status == ExecutionStepStatus.FAILED
        }
        val partialReplan = failed?.let { failedResult ->
            val index = plan.steps.indexOfFirst { it.id == failedResult.stepId }
            PartialReplanRequest(
                goal = plan.goal,
                completedSteps = results.takeWhile { it.stepId != failedResult.stepId },
                failedStepId = failedResult.stepId,
                failureReason = failedResult.error.orEmpty(),
                remainingSteps = if (index >= 0) plan.steps.drop(index + 1) else emptyList(),
            )
        }

        val avoided = (completed - 1).coerceAtLeast(0)
        if (plan.taskId != null && failed != null) {
            telemetry.recordPartialReplan(plan.taskId)
        }
        telemetry.recordLlmCallsAvoided(plan.taskId, avoided)

        return ExecutionPlanResult(
            goal = plan.goal,
            status = when {
                results.any { it.status == ExecutionStepStatus.APPROVAL_REQUIRED } -> "WAITING_FOR_APPROVAL"
                failed != null -> "REPLAN_REQUIRED"
                completed == plan.steps.size -> "SUCCESS"
                else -> "PARTIAL"
            },
            completedCount = completed,
            attemptedCount = results.size,
            results = results,
            partialReplan = partialReplan,
            llmCallsAvoided = avoided,
        )
    }

    private fun validateConstraints(
        plan: StructuredExecutionPlan,
        step: StructuredExecutionStep,
        registry: ExecutionToolRegistry,
    ): String? {
        val constraints = plan.requiredConstraints + step.requiredConstraints
        if ("use_keyboard" in constraints &&
            step.deviceAction != DeviceAction.TYPE_AND_SUBMIT &&
            step.toolName != "keyboard_type"
        ) {
            return "required_constraint_not_satisfied:use_keyboard"
        }
        if ("use_screenshot_tool" in constraints &&
            step.deviceAction != DeviceAction.CAPTURE_SCREENSHOT &&
            step.toolName != "take_screenshot"
        ) {
            return "required_constraint_not_satisfied:use_screenshot_tool"
        }
        if ("use_keyboard" in constraints && registry.find("keyboard_type") == null) {
            return "required_capability_missing:keyboard"
        }
        return null
    }

    private fun normalizedPostconditions(step: StructuredExecutionStep): List<DevicePostcondition> {
        if (step.postconditions.isNotEmpty()) return step.postconditions
        if (step.requiresVerification) {
            return step.expectedResult?.let {
                listOf(DevicePostcondition(DevicePostconditionType.TEXT_PRESENT, it))
            }.orEmpty()
        }
        return when (step.deviceAction) {
            DeviceAction.OPEN_APP ->
                step.args["name"]?.jsonPrimitive?.contentOrNull?.let {
                    listOf(DevicePostcondition(DevicePostconditionType.APP_FOREGROUND, it, timeoutMs = 3_000L))
                }.orEmpty()
            DeviceAction.TAP_TARGET ->
                listOf(DevicePostcondition(DevicePostconditionType.SCREEN_CHANGED, timeoutMs = 3_000L))
            DeviceAction.TYPE_AND_SUBMIT ->
                listOf(DevicePostcondition(DevicePostconditionType.SCREEN_CHANGED, timeoutMs = 15_000L))
            DeviceAction.EXTRACT_RESPONSE ->
                listOf(DevicePostcondition(DevicePostconditionType.RESPONSE_TEXT_NON_EMPTY))
            else -> emptyList()
        }
    }

    private suspend fun executeAction(
        step: StructuredExecutionStep,
        registry: ExecutionToolRegistry,
        isToolAutoApproved: suspend (String) -> Boolean,
        permissionResolver: AssistantToolPermissionResolver?,
        assistant: Assistant?,
        taskId: String?,
    ): ActionExecution {
        step.deviceAction?.let {
            return executeHighLevelAction(
                step, it, registry, isToolAutoApproved, permissionResolver, assistant, taskId,
            )
        }

        var last = ActionExecution(
            executed = false,
            lifecycle = DeviceActionLifecycle.DISPATCHED,
            error = "tool_unavailable",
        )
        (listOfNotNull(step.toolName) + step.alternativeToolNames)
            .distinct()
            .take(4)
            .forEach { name ->
                if (!last.executed && !last.requiresApproval) {
                    registry.findLoaded(name)?.let { tool ->
                        last = executeTool(
                            name, tool, step.args, registry, isToolAutoApproved,
                            permissionResolver, assistant, taskId,
                        )
                    }
                }
            }
        return last
    }

    private suspend fun executeHighLevelAction(
        step: StructuredExecutionStep,
        action: DeviceAction,
        registry: ExecutionToolRegistry,
        isToolAutoApproved: suspend (String) -> Boolean,
        permissionResolver: AssistantToolPermissionResolver?,
        assistant: Assistant?,
        taskId: String?,
    ): ActionExecution {
        return when (action) {
            DeviceAction.OPEN_APP -> {
                val name = step.args["name"]?.jsonPrimitive?.contentOrNull
                    ?: return ActionExecution(false, DeviceActionLifecycle.DISPATCHED, error = "app_name_required")
                val tool = registry.findLoaded("open_app")
                    ?: return ActionExecution(false, DeviceActionLifecycle.DISPATCHED, error = "app_launch_capability_unavailable")
                executeTool(
                    "open_app",
                    tool,
                    buildJsonObject { put("name", name) },
                    registry, isToolAutoApproved, permissionResolver, assistant, taskId,
                )
            }

            DeviceAction.FIND_TARGET -> {
                val targetName = step.args["target"]?.jsonPrimitive?.contentOrNull
                    ?: return ActionExecution(false, DeviceActionLifecycle.DISPATCHED, error = "target_required")
                val visionModelId = resolveVisionModelId(taskId)
                when (val resolution = targetResolver.resolve(targetName, 0.70f, visionModelId)) {
                    is DeviceTargetResolution.Resolved -> ActionExecution(
                        executed = true,
                        lifecycle = DeviceActionLifecycle.EFFECT_OBSERVED,
                        output = buildJsonObject {
                            put("target", resolution.target.semanticName)
                            put("x", resolution.target.clickX.toDouble())
                            put("y", resolution.target.clickY.toDouble())
                            put("confidence", resolution.target.confidence.toDouble())
                            put("source", resolution.target.source.name)
                        }.toString(),
                        target = resolution.target,
                        attempts = 1,
                    )
                    is DeviceTargetResolution.Ambiguous ->
                        ActionExecution(false, DeviceActionLifecycle.DISPATCHED, error = "target_ambiguous")
                    is DeviceTargetResolution.NotFound ->
                        ActionExecution(false, DeviceActionLifecycle.DISPATCHED, error = resolution.reason)
                }
            }

            DeviceAction.TAP_TARGET -> {
                val targetName = step.args["target"]?.jsonPrimitive?.contentOrNull
                    ?: return ActionExecution(false, DeviceActionLifecycle.DISPATCHED, error = "target_required")
                val visionModelId = resolveVisionModelId(taskId)
                when (val resolution = targetResolver.resolve(targetName, 0.70f, visionModelId)) {
                    is DeviceTargetResolution.Resolved -> {
                        if (resolution.target.source == DeviceTargetSource.VISION &&
                            resolution.target.confidence < 0.90f
                        ) {
                            ActionExecution(
                                false,
                                DeviceActionLifecycle.DISPATCHED,
                                error = "vision_target_confidence_too_low",
                                target = resolution.target,
                            )
                        } else {
                            val tap = registry.findLoaded("tap")
                                ?: return ActionExecution(false, DeviceActionLifecycle.DISPATCHED, error = "tap_unavailable")
                            executeTool(
                                "tap",
                                tap,
                                buildJsonObject {
                                    put("x", resolution.target.clickX.toDouble())
                                    put("y", resolution.target.clickY.toDouble())
                                },
                                registry, isToolAutoApproved, permissionResolver, assistant, taskId,
                            ).copy(target = resolution.target)
                        }
                    }
                    is DeviceTargetResolution.Ambiguous ->
                        ActionExecution(false, DeviceActionLifecycle.DISPATCHED, error = "target_ambiguous")
                    is DeviceTargetResolution.NotFound ->
                        ActionExecution(false, DeviceActionLifecycle.DISPATCHED, error = resolution.reason)
                }
            }

            DeviceAction.TYPE_AND_SUBMIT -> {
                val text = step.args["text"]?.jsonPrimitive?.contentOrNull
                    ?: return ActionExecution(false, DeviceActionLifecycle.DISPATCHED, error = "text_required")
                val keyboard = registry.findLoaded("keyboard_type")
                    ?: return ActionExecution(false, DeviceActionLifecycle.DISPATCHED, error = "keyboard_capability_unavailable")
                executeTool(
                    "keyboard_type",
                    keyboard,
                    buildJsonObject {
                        put("text", text)
                        put("submit", true)
                    },
                    registry, isToolAutoApproved, permissionResolver, assistant, taskId,
                )
            }

            DeviceAction.WAIT_FOR_RESPONSE -> {
                val expected = step.args["expected_text"]?.jsonPrimitive?.contentOrNull
                val predicate = if (!expected.isNullOrBlank()) {
                    DevicePostcondition(
                        DevicePostconditionType.TEXT_PRESENT,
                        expected,
                        timeoutMs = step.args["timeout_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                            ?: 15_000L,
                    )
                } else {
                    DevicePostcondition(
                        DevicePostconditionType.SCREEN_CHANGED,
                        timeoutMs = step.args["timeout_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                            ?: 15_000L,
                    )
                }
                val started = System.currentTimeMillis()
                val result = waiter.waitFor(predicate)
                ActionExecution(
                    executed = result.verified,
                    lifecycle = if (result.verified) DeviceActionLifecycle.VERIFIED else DeviceActionLifecycle.EXECUTED,
                    output = result.reason,
                    error = result.takeIf { !it.verified }?.reason,
                    attempts = 1,
                    localWaitMs = System.currentTimeMillis() - started,
                )
            }

            DeviceAction.CAPTURE_SCREENSHOT -> {
                val tool = registry.findLoaded("take_screenshot")
                    ?: return ActionExecution(false, DeviceActionLifecycle.DISPATCHED, error = "screenshot_unavailable")
                executeTool(
                    "take_screenshot",
                    tool,
                    step.args,
                    registry, isToolAutoApproved, permissionResolver, assistant, taskId,
                )
            }

            DeviceAction.EXTRACT_RESPONSE -> {
                val observation = observer.observe(captureScreenshot = true, captureOcr = true)
                val responseText = observation.visibleText
                    .asSequence()
                    .map(String::trim)
                    .filter { it.length >= 2 }
                    .distinct()
                    .toList()
                    .takeLast(100)
                    .joinToString("\n")
                    .trim()
                ActionExecution(
                    executed = responseText.isNotBlank(),
                    lifecycle = if (responseText.isNotBlank()) {
                        DeviceActionLifecycle.EFFECT_OBSERVED
                    } else {
                        DeviceActionLifecycle.EXECUTED
                    },
                    output = buildJsonObject {
                        put("response_text", responseText)
                        put("non_empty", responseText.isNotBlank())
                        if (observation.screenshotPath != null) {
                            put("screenshot_state", observation.screenshotState.name)
                            put("screenshot_path", observation.screenshotPath)
                        }
                    }.toString(),
                    error = if (responseText.isBlank()) "response_text_empty" else null,
                    artifactState = observation.screenshotState,
                    attempts = 1,
                )
            }

            DeviceAction.RETURN_RESULT ->
                ActionExecution(true, DeviceActionLifecycle.VERIFIED, output = "return_result", attempts = 1)
        }
    }

    private suspend fun recoverAction(
        step: StructuredExecutionStep,
        firstFailure: ActionExecution,
        registry: ExecutionToolRegistry,
        isToolAutoApproved: suspend (String) -> Boolean,
        permissionResolver: AssistantToolPermissionResolver?,
        assistant: Assistant?,
        taskId: String?,
    ): ActionExecution {
        val fallbacks = mutableListOf<suspend () -> ActionExecution>()

        fallbacks += suspend {
            executeAction(
                step, registry, isToolAutoApproved, permissionResolver, assistant, taskId,
            ).copy(fallbackPath = listOf("retry"))
        }

        if (step.deviceAction == DeviceAction.TAP_TARGET) {
            val targetName = step.args["target"]?.jsonPrimitive?.contentOrNull
            if (!targetName.isNullOrBlank()) {
                val visionModelId = resolveVisionModelId(taskId)

                fallbacks += suspend {
                    when (val resolution = targetResolver.resolve(targetName, 0.90f, visionModelId)) {
                        is DeviceTargetResolution.Resolved -> {
                            if (resolution.target.source == DeviceTargetSource.VISION &&
                                resolution.target.confidence < 0.90f
                            ) {
                                ActionExecution(false, DeviceActionLifecycle.DISPATCHED, error = "low_confidence_vision_target")
                            } else {
                                val tap = registry.findLoaded("tap")
                                if (tap == null) {
                                    ActionExecution(
                                        false,
                                        DeviceActionLifecycle.DISPATCHED,
                                        error = "tap_unavailable",
                                    )
                                } else executeTool(
                                    "tap",
                                    tap,
                                    buildJsonObject {
                                        put("x", resolution.target.clickX.toDouble())
                                        put("y", resolution.target.clickY.toDouble())
                                    },
                                    registry, isToolAutoApproved, permissionResolver, assistant, taskId,
                                ).copy(
                                    target = resolution.target,
                                    fallbackPath = listOf(resolution.target.source.name),
                                )
                            }
                        }
                        is DeviceTargetResolution.Ambiguous ->
                            ActionExecution(false, DeviceActionLifecycle.DISPATCHED, error = "fallback_target_ambiguous")
                        is DeviceTargetResolution.NotFound ->
                            ActionExecution(false, DeviceActionLifecycle.DISPATCHED, error = resolution.reason)
                    }
                }

                if (registry.findLoaded("shizuku_exec") != null) {
                    fallbacks += suspend {
                        when (val resolution = targetResolver.resolve(targetName, 0.90f, visionModelId)) {
                            is DeviceTargetResolution.Resolved -> {
                                val command = "input tap " +
                                    resolution.target.clickX.toInt() + " " +
                                    resolution.target.clickY.toInt()
                                val shizuku = registry.findLoaded("shizuku_exec")
                                if (shizuku == null) {
                                    ActionExecution(
                                        false,
                                        DeviceActionLifecycle.DISPATCHED,
                                        error = "shizuku_unavailable",
                                    )
                                } else executeTool(
                                    "shizuku_exec",
                                    shizuku,
                                    buildJsonObject { put("command", command) },
                                    registry, isToolAutoApproved, permissionResolver, assistant, taskId,
                                ).copy(
                                    target = resolution.target,
                                    fallbackPath = listOf("shizuku"),
                                )
                            }
                            else ->
                                ActionExecution(false, DeviceActionLifecycle.DISPATCHED, error = "shizuku_target_unavailable")
                        }
                    }
                }
            }
        }

        val recovered = recoveryEngine.run(
            primary = { firstFailure },
            fallbacks = fallbacks,
            isSuccess = { it.executed || it.requiresApproval },
        )
        val result = recovered.result ?: firstFailure
        return result.copy(
            recoveryAttempts = recovered.attempts,
            fallbackPath = firstFailure.fallbackPath + result.fallbackPath,
        )
    }

    private suspend fun executeTool(
        toolName: String,
        tool: Tool,
        args: JsonObject,
        registry: ExecutionToolRegistry,
        isToolAutoApproved: suspend (String) -> Boolean,
        permissionResolver: AssistantToolPermissionResolver?,
        assistant: Assistant?,
        taskId: String?,
    ): ActionExecution {
        val decision = if (permissionResolver != null && assistant != null) {
            permissionResolver.decide(
                assistant = assistant,
                registry = registry,
                toolName = toolName,
                args = args,
                taskId = taskId,
            )
        } else {
            null
        }

        when (decision?.action) {
            ToolPermissionDecision.Action.ALLOW,
            null -> Unit
            ToolPermissionDecision.Action.ASK ->
                return ActionExecution(
                    executed = false,
                    lifecycle = DeviceActionLifecycle.DISPATCHED,
                    requiresApproval = true,
                    error = "authorization_required:" + toolName,
                )
            ToolPermissionDecision.Action.DENY ->
                return ActionExecution(
                    executed = false,
                    lifecycle = DeviceActionLifecycle.DISPATCHED,
                    error = "authorization_denied:" + toolName,
                )
        }

        if (decision == null &&
            registry.requiresApproval(toolName, args) &&
            !isToolAutoApproved(toolName)
        ) {
            return ActionExecution(
                executed = false,
                lifecycle = DeviceActionLifecycle.DISPATCHED,
                requiresApproval = true,
                error = "authorization_required:" + toolName,
            )
        }

        return try {
            val output = tool.execute(args)
            val text = output.filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
                .trim()
            val error = runCatching {
                Json.parseToJsonElement(text).jsonObject["error"]
                    ?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            if (error != null) {
                ActionExecution(
                    executed = false,
                    lifecycle = DeviceActionLifecycle.EXECUTED,
                    output = text,
                    error = error,
                )
            } else {
                ActionExecution(
                    executed = true,
                    lifecycle = DeviceActionLifecycle.EXECUTED,
                    output = text,
                    attempts = 1,
                    artifactState = if (
                        text.contains("\"screenshot_captured\":true") ||
                        text.contains("\"screenshot_state\":\"CAPTURED_NOT_DELIVERED\"")
                    ) {
                        DeviceArtifactState.CAPTURED_NOT_DELIVERED
                    } else {
                        null
                    },
                )
            }
        } catch (t: Throwable) {
            ActionExecution(
                executed = false,
                lifecycle = DeviceActionLifecycle.EXECUTED,
                error = (t.message ?: t::class.java.simpleName).take(500),
            )
        }
    }

    private suspend fun resolveVisionModelId(taskId: String?): String? {
        if (taskId.isNullOrBlank()) return null
        val task = taskManager.getTask(taskId) ?: return null
        return agentConfigRepository.get(task.conversationId)
            .agents
            .firstOrNull { it.enabled && it.role == AgentRole.VISION }
            ?.modelId
    }

    private fun isRecoverable(step: StructuredExecutionStep): Boolean =
        step.deviceAction == DeviceAction.TAP_TARGET ||
            step.toolName in setOf("tap", "click_node", "find_node")

    private companion object {
        val VERIFICATION_REQUIRED_CORE_TOOLS: Set<String> = setOf(
            "open_app",
            "launch_app",
            "keyboard_type",
            "tap",
            "click_node",
            "set_text",
            "global_action",
            "scroll",
            "swipe",
            "long_press",
        )
    }

    private suspend fun failure(
        plan: StructuredExecutionPlan,
        step: StructuredExecutionStep,
        reason: String,
        attempts: Int = 0,
        verificationAttempts: Int = 0,
        observationSource: String? = null,
    ): ExecutionStepResult {
        if (plan.taskId != null && step.taskStepId != null) {
            runCatching {
                taskManager.recordStepFailure(
                    taskId = plan.taskId,
                    stepId = step.taskStepId,
                    error = reason,
                    recovery = TaskRecoveryAction.REPLAN,
                )
            }
        }
        return ExecutionStepResult(
            stepId = step.id,
            taskStepId = step.taskStepId,
            toolName = step.toolName,
            status = ExecutionStepStatus.REPLAN_REQUIRED,
            lifecycle = DeviceActionLifecycle.EXECUTED,
            attempts = attempts,
            error = reason,
            observationSource = observationSource,
            recoveryAttempts = verificationAttempts,
            artifactState = null,
        )
    }

    private fun preflightFailure(
        plan: StructuredExecutionPlan,
        capability: DeviceCapability,
    ): ExecutionPlanResult =
        ExecutionPlanResult(
            goal = plan.goal,
            status = "REPLAN_REQUIRED",
            completedCount = 0,
            attemptedCount = 0,
            results = listOf(
                ExecutionStepResult(
                    stepId = "preflight",
                    status = ExecutionStepStatus.REPLAN_REQUIRED,
                    lifecycle = DeviceActionLifecycle.DISPATCHED,
                    error = "capability_unavailable:" +
                        capability.name + ":" + capability.reason.orEmpty(),
                ),
            ),
        )

    data class ActionExecution(
        val executed: Boolean,
        val lifecycle: DeviceActionLifecycle,
        val output: String? = null,
        val error: String? = null,
        val requiresApproval: Boolean = false,
        val attempts: Int = 0,
        val target: DeviceTarget? = null,
        val fallbackPath: List<String> = emptyList(),
        val recoveryAttempts: Int = 0,
        val localWaitMs: Long = 0L,
        val artifactState: DeviceArtifactState? = null,
    )
}
