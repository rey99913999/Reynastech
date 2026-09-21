package io.github.nastechresearch.nastech.workflow.execution

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.nastechresearch.nastech.data.execution.debug.ExecutionTrace
import io.github.nastechresearch.nastech.data.execution.debug.ExecutionTraceStatus
import io.github.nastechresearch.nastech.data.execution.debug.ExecutionTraceStep
import io.github.nastechresearch.nastech.data.execution.debug.safeJsonSummary
import me.rerere.ai.core.Tool
import io.github.nastechresearch.nastech.data.ai.tools.HardlineCommandGuard
import io.github.nastechresearch.nastech.data.ai.tools.LocalTools
import io.github.nastechresearch.nastech.data.datastore.SettingsStore
import io.github.nastechresearch.nastech.workflow.condition.ConditionEvaluator
import io.github.nastechresearch.nastech.workflow.condition.ContextProvider
import io.github.nastechresearch.nastech.workflow.model.WorkflowAction
import io.github.nastechresearch.nastech.workflow.model.WorkflowDefinition
import io.github.nastechresearch.nastech.workflow.model.WorkflowRunStatus
import io.github.nastechresearch.nastech.workflow.repository.WorkflowRepository
import io.github.nastechresearch.nastech.workflow.trigger.TriggerFireCallback
import java.time.LocalDate
import java.time.ZoneId

/**
 * Phase 12 — workflow execution engine. The single entry point for any workflow fire.
 *
 * Lifecycle of a fire (matches `headless = true` semantics from cron jobs):
 *  1. Lookup workflow + verify enabled.
 *  2. Cooldown check — `lastRunAtMs + cooldownSeconds` against now.
 *  3. Daily-cap check — counted fires (SUCCESS+FAILED) for today's local date.
 *  4. Build [WorkflowContext] — lazy on location for sunset/sunrise conditions.
 *  5. Evaluate conditions; AND-combined.
 *  6. Resolve assistant + tool list. Workflows are app-global, but actions still need a
 *     tool surface to execute against — we use the first assistant with the Workflows
 *     toggle on (the toggle gates *authoring*; runtime fallback is reasonable).
 *  7. Execute action sequence via [DirectModeActionRunner] — every action HARDLINE-checked.
 *  8. Persist run row, projected last-run state, daily counter, trim history.
 *
 * Concurrency: per-workflow mutex so two near-simultaneous fires (e.g. WiFi flicker) can't
 * race on the daily counter. Cross-workflow execution stays parallel.
 *
 * Approval semantics: HARDLINE applies in workflow context. Tool factories that set
 * `needsApproval = { true }` would normally pop a prompt — workflows are headless and the
 * pre-authorisation is the workflow_create approval the user already granted. So the
 * action runner just calls the tool's [Tool.execute] directly. This matches scheduled-jobs
 * direct-mode behavior.
 *
 * The `Workflows` per-assistant toggle gates the seven `workflow_*` LLM tools, NOT the
 * trigger pipeline. A workflow that's been authored stays armed regardless of which
 * assistant the user is currently chatting with. Trigger dispatch is gated by the
 * workflow's own `enabled` flag.
 */
class WorkflowEngine(
    private val repository: WorkflowRepository,
    private val settingsStore: SettingsStore,
    private val contextProvider: ContextProvider,
    private val actionRunner: WorkflowActionRunner,
) {

    /**
     * [LocalTools] is resolved lazily via Koin to break the construction cycle:
     *   - [LocalTools] constructor takes a [WorkflowEngine] (so workflow_run can fire)
     *   - [WorkflowEngine] needs [LocalTools] only at fire time (to build the action's tool surface)
     * Eager constructor injection would loop the DI graph at startup — observed as a
     * StackOverflowError on first install of Phase 12. Lazy lookup is safe because the
     * graph is fully resolved by the time `fire()` is called.
     */
    private val localTools: LocalTools by lazy {
        org.koin.java.KoinJavaComponent.getKoin().get<LocalTools>()
    }

    /**
     * Phase 24 — unified AgentRun ledger writer. Resolved lazily via Koin (same pattern as
     * [localTools] above) to keep the engine's constructor DI surface minimal — the engine
     * is shared across cron / sub-agent surfaces and a tiny lookup on the rare-fire path is
     * cheaper than threading another constructor arg through the factory. No cycle risk:
     * AgentRunRepository depends only on its DAO.
     */
    private val agentRunRepo: io.github.nastechresearch.nastech.data.agentrun.AgentRunRepository by lazy {
        org.koin.java.KoinJavaComponent.getKoin().get<io.github.nastechresearch.nastech.data.agentrun.AgentRunRepository>()
    }

    private val perWorkflowLocks = mutableMapOf<String, Mutex>()
    private val locksMutex = Mutex()

    private suspend fun lockFor(id: String): Mutex = locksMutex.withLock {
        perWorkflowLocks.getOrPut(id) { Mutex() }
    }

    /**
     * Drop the lock entry for a deleted workflow. Wired from
     * [io.github.nastechresearch.nastech.workflow.repository.WorkflowRepository.deleteCascading] so the
     * lock map can't grow unbounded across heavy LLM-driven create/delete churn.
     */
    suspend fun forgetWorkflow(id: String) {
        locksMutex.withLock { perWorkflowLocks.remove(id) }
    }

    /**
     * Trigger callback target. The registry hands every fire here. [matchSpec] is the
     * variant that fired — used for diagnostics; the workflow's own [WorkflowDefinition.trigger]
     * is the source of truth for its semantics.
     */
    val triggerCallback = TriggerFireCallback { workflowId, _ -> fire(workflowId) }

    /**
     * Fire a workflow. Resolves cooldown / daily cap / conditions, then runs the action
     * sequence. Returns the resulting status — useful for `workflow_run` synchronous tool
     * call, ignored by the trigger callback path.
     */
    suspend fun fire(workflowId: String): FireOutcome = withContext(Dispatchers.IO) {
        val lock = lockFor(workflowId)
        lock.withLock { fireLocked(workflowId) }
    }

    private suspend fun fireLocked(workflowId: String): FireOutcome {
        val firedAtMs = System.currentTimeMillis()
        val started = System.nanoTime()
        val loaded = repository.getById(workflowId)
            ?: return FireOutcome(WorkflowRunStatus.FAILED, "workflow_not_found", "")
        val def = loaded.definition
        val entity = loaded.entity

        // Phase 24 — open the cross-pillar ledger row for this fire. Opened after the
        // workflow loads so a `workflow_not_found` non-fire isn't recorded, but before the
        // gate checks so a SKIPPED_* outcome is still visible in the ledger. domain_id is
        // the workflow id; the ledger row is per-fire (a fresh row each time fire() runs).
        val ledgerId = agentRunRepo.open(
            kind = io.github.nastechresearch.nastech.data.agentrun.AgentRunKind.Workflow,
            domainId = workflowId,
            metadata = buildJsonObject {
                put("name", entity.name)
                put("trigger", def.trigger::class.simpleName ?: "unknown")
            },
        )

        if (!entity.enabled) {
            return persistAndReturn(workflowId, firedAtMs, started, WorkflowRunStatus.SKIPPED_DISABLED, null, "", ledgerId)
        }

        // Trigger runtime pre-flight — surface "this trigger needs setup" as an explicit
        // FAILED row in history so the user sees WHY the workflow doesn't fire instead of
        // just "Never run". The audit found these were silently dying:
        //  - geofence triggers without ACCESS_FINE_LOCATION + ACCESS_BACKGROUND_LOCATION
        //  - notification_received without notification listener bound
        //  - app_launched / app_closed without accessibility service running
        triggerRuntimeCheck(def.trigger)?.let { reason ->
            return persistAndReturn(workflowId, firedAtMs, started, WorkflowRunStatus.FAILED, reason, "", ledgerId)
        }

        // Cooldown gate. NOTE: must use `lastActualFireAtMs` (most-recent SUCCESS/FAILED
        // from history) — NOT `entity.lastRunAtMs`, which gets overwritten on every
        // attempt INCLUDING skips. Using the projected column would let SKIPPED_COOLDOWN
        // fires push the cooldown window forward indefinitely; the cooldown could never
        // be satisfied by waiting.
        val lastActualFireMs = if (def.cooldownSeconds > 0) repository.lastActualFireAtMs(workflowId) else null
        if (CooldownGate.isWithinCooldown(def.cooldownSeconds, lastActualFireMs, firedAtMs)) {
            return persistAndReturn(workflowId, firedAtMs, started, WorkflowRunStatus.SKIPPED_COOLDOWN, null, "", ledgerId)
        }

        // Daily-cap gate
        if (def.maxRunsPerDay != null) {
            val today = LocalDate.now(ZoneId.systemDefault()).toString()
            val countedToday = if (entity.runsTodayDate == today) entity.runsTodayCount else 0
            if (countedToday >= def.maxRunsPerDay) {
                return persistAndReturn(workflowId, firedAtMs, started, WorkflowRunStatus.SKIPPED_DAILY_CAP, null, "", ledgerId)
            }
        }

        // Conditions
        if (def.conditions.isNotEmpty()) {
            val ctx = contextProvider.snapshot(needsLocation = ConditionEvaluator.needsLocation(def.conditions))
            val cr = ConditionEvaluator.evaluateAll(def.conditions, ctx)
            if (cr is ConditionEvaluator.Result.FailedAt) {
                return persistAndReturn(
                    workflowId, firedAtMs, started, WorkflowRunStatus.SKIPPED_CONDITIONS,
                    "condition[${cr.index}] failed: ${cr.reason}", "", ledgerId,
                )
            }
        }

        // Resolve assistant + tools. Prefer the persisted authoring assistant id (added by
        // the audit-pass fix to remove "first matching assistant" non-determinism). If the
        // workflow predates that fix (legacy null) OR the authoring assistant was deleted,
        // fall back to "any assistant with Workflows toggle on" but log loudly — the user's
        // intent might not match what we run.
        val settings = settingsStore.settingsFlow.first()
        val authoringAssistant = run {
            val storedId = def.authoringAssistantId
            val byId = if (storedId != null) {
                settings.assistants.firstOrNull { it.id.toString() == storedId }
            } else null
            if (byId != null) {
                byId
            } else {
                if (storedId != null) {
                    Log.w(TAG, "fire: authoring assistant $storedId for workflow $workflowId no longer exists; falling back to first-with-Workflows")
                }
                settings.assistants.firstOrNull { asst ->
                    asst.localTools.any { it is io.github.nastechresearch.nastech.data.ai.tools.LocalToolOption.Workflows }
                }
            }
        }
        if (authoringAssistant == null) {
            return persistAndReturn(workflowId, firedAtMs, started, WorkflowRunStatus.FAILED,
                "no_workflows_assistant", "", ledgerId)
        }
        // Headless context — sub-agent recursion guard fires from workflow-action
        // dispatch so a workflow's actions can't spawn a sub-agent that re-fires another
        // workflow_run that re-spawns ad infinitum.
        val tools = localTools.getTools(
            authoringAssistant.localTools,
            io.github.nastechresearch.nastech.data.ai.tools.ToolInvocationContext(
                callerAssistantId = authoringAssistant.id.toString(),
                callerConversationId = null,  // headless workflow fire — no conv
                isHeadless = true,
            ),
        )

        // Persist a live execution trace in the existing workflow_runs history.
        var trace = ExecutionTrace(
            runId = "pending",
            workflowId = workflowId,
            startedAtMs = firedAtMs,
            status = ExecutionTraceStatus.RUNNING,
            steps = emptyList(),
        )
        val runId = repository.beginRun(
            workflowId = workflowId,
            firedAtMs = firedAtMs,
            trace = trace,
        )
        trace = trace.copy(runId = runId.toString())
        repository.updateRunTrace(runId, trace)
        val result = actionRunner.run(
            actions = def.actions,
            availableTools = tools,
            onProgress = { steps, currentStep, status, checkpointStep ->
                trace = trace.copy(
                    currentStep = currentStep,
                    status = status,
                    steps = steps,
                    checkpointStep = checkpointStep,
                )
                repository.updateRunTrace(runId, trace)
            },
        )
        trace = trace.copy(
            currentStep = result.currentStep,
            status = if (result.success) ExecutionTraceStatus.SUCCESS else ExecutionTraceStatus.FAILED,
            steps = result.traceSteps,
            checkpointStep = result.checkpointStep,
        )
        val status = if (result.success) WorkflowRunStatus.SUCCESS else WorkflowRunStatus.FAILED
        return persistAndReturn(
            workflowId = workflowId,
            firedAtMs = firedAtMs,
            startedNanos = started,
            status = status,
            error = result.error,
            summary = result.summary,
            ledgerId = ledgerId,
            runId = runId,
            trace = trace,
        )
    }

    suspend fun retryRun(runId: Long, skipFailedStep: Boolean = false): FireOutcome? =
        withContext(Dispatchers.IO) {
            val source = repository.getRun(runId) ?: return@withContext null
            val loaded = repository.getById(source.workflowId)
                ?: return@withContext FireOutcome(WorkflowRunStatus.FAILED, "workflow_not_found", "")
            val trace = source.trace ?: return@withContext FireOutcome(
                WorkflowRunStatus.FAILED,
                "execution_trace_unavailable",
                "",
            )
            val failedIndex = trace.currentStep ?: trace.steps.indexOfLast {
                it.status == ExecutionTraceStatus.FAILED || it.status == ExecutionTraceStatus.RETRIED
            }
            if (failedIndex < 0 || failedIndex >= loaded.definition.actions.size) {
                return@withContext FireOutcome(
                    WorkflowRunStatus.FAILED,
                    "no_failed_step_to_retry",
                    "",
                )
            }

            val startIndex = if (skipFailedStep) failedIndex + 1 else failedIndex
            val steps = trace.steps.toMutableList()
            if (skipFailedStep) {
                steps[failedIndex] = steps[failedIndex].copy(
                    status = ExecutionTraceStatus.SKIPPED,
                    endedAtMs = System.currentTimeMillis(),
                    checkpoint = false,
                    resultSummary = "Skipped during recovery.",
                )
            }

            val settings = settingsStore.settingsFlow.first()
            val authoringAssistant = settings.assistants.firstOrNull { assistant ->
                loaded.definition.authoringAssistantId?.let { it == assistant.id.toString() } == true
            } ?: settings.assistants.firstOrNull { assistant ->
                assistant.localTools.any {
                    it is io.github.nastechresearch.nastech.data.ai.tools.LocalToolOption.Workflows
                }
            }
            if (authoringAssistant == null) {
                return@withContext FireOutcome(
                    WorkflowRunStatus.FAILED,
                    "no_workflows_assistant",
                    "",
                )
            }

            val tools = localTools.getTools(
                authoringAssistant.localTools,
                io.github.nastechresearch.nastech.data.ai.tools.ToolInvocationContext(
                    callerAssistantId = authoringAssistant.id.toString(),
                    callerConversationId = null,
                    isHeadless = true,
                ),
            )

            val startedAtMs = System.currentTimeMillis()
            val newTraceBase = trace.copy(
                runId = "pending",
                startedAtMs = startedAtMs,
                endedAtMs = null,
                currentStep = if (startIndex < loaded.definition.actions.size) startIndex else null,
                status = ExecutionTraceStatus.RUNNING,
                steps = steps,
                parentRunId = source.rowId.toString(),
                retryCount = trace.retryCount + if (skipFailedStep) 0 else 1,
                diagnosis = null,
            )
            val ledgerId = agentRunRepo.open(
                kind = io.github.nastechresearch.nastech.data.agentrun.AgentRunKind.Workflow,
                domainId = loaded.entity.id,
                metadata = buildJsonObject {
                    put("name", loaded.entity.name)
                    put("recovery_of_run", source.rowId)
                    put("skip_failed_step", skipFailedStep)
                },
            )
            val newRunId = repository.beginRun(
                workflowId = loaded.entity.id,
                firedAtMs = startedAtMs,
                trace = newTraceBase,
                parentRunId = source.rowId,
            )
            var liveTrace = newTraceBase.copy(runId = newRunId.toString())
            repository.updateRunTrace(newRunId, liveTrace)

            if (startIndex >= loaded.definition.actions.size) {
                liveTrace = liveTrace.copy(
                    currentStep = null,
                    status = ExecutionTraceStatus.SUCCESS,
                    endedAtMs = System.currentTimeMillis(),
                )
                repository.finishRun(
                    runId = newRunId,
                    workflowId = loaded.entity.id,
                    firedAtMs = startedAtMs,
                    status = WorkflowRunStatus.SUCCESS,
                    durationMs = System.currentTimeMillis() - startedAtMs,
                    errorMessage = null,
                    trace = liveTrace,
                )
                agentRunRepo.markTerminal(
                    id = ledgerId,
                    status = io.github.nastechresearch.nastech.data.agentrun.AgentRunStatus.succeeded,
                    lastError = null,
                )
                return@withContext FireOutcome(WorkflowRunStatus.SUCCESS, null, "Recovery skipped the failed step.")
            }

            val result = actionRunner.run(
                actions = loaded.definition.actions,
                availableTools = tools,
                startIndex = startIndex,
                existingSteps = steps,
                onProgress = { nextSteps, currentStep, status, checkpointStep ->
                    liveTrace = liveTrace.copy(
                        currentStep = currentStep,
                        status = status,
                        steps = nextSteps,
                        checkpointStep = checkpointStep,
                    )
                    repository.updateRunTrace(newRunId, liveTrace)
                },
            )
            liveTrace = liveTrace.copy(
                currentStep = result.currentStep,
                status = if (result.success) ExecutionTraceStatus.SUCCESS else ExecutionTraceStatus.FAILED,
                steps = result.traceSteps,
                checkpointStep = result.checkpointStep,
                endedAtMs = System.currentTimeMillis(),
            )
            val terminalStatus = if (result.success) WorkflowRunStatus.SUCCESS else WorkflowRunStatus.FAILED
            repository.finishRun(
                runId = newRunId,
                workflowId = loaded.entity.id,
                firedAtMs = startedAtMs,
                status = terminalStatus,
                durationMs = System.currentTimeMillis() - startedAtMs,
                errorMessage = result.error,
                trace = liveTrace,
            )
            agentRunRepo.markTerminal(
                id = ledgerId,
                status = if (result.success) {
                    io.github.nastechresearch.nastech.data.agentrun.AgentRunStatus.succeeded
                } else {
                    io.github.nastechresearch.nastech.data.agentrun.AgentRunStatus.failed
                },
                lastError = result.error,
            )
            FireOutcome(terminalStatus, result.error, result.summary)
        }

    suspend fun pauseRun(runId: Long): Boolean {
        val source = repository.getRun(runId) ?: return false
        val trace = source.trace ?: return false
        repository.finishRun(
            runId = runId,
            workflowId = source.workflowId,
            firedAtMs = source.firedAtMs,
            status = WorkflowRunStatus.PAUSED,
            durationMs = source.durationMs,
            errorMessage = "Paused by user.",
            trace = trace.copy(
                status = ExecutionTraceStatus.WAITING,
                endedAtMs = System.currentTimeMillis(),
                diagnostics = trace.diagnostics + "Paused by user.",
            ),
        )
        return true
    }

    suspend fun cancelRun(runId: Long): Boolean {
        val source = repository.getRun(runId) ?: return false
        val trace = source.trace ?: return false
        repository.finishRun(
            runId = runId,
            workflowId = source.workflowId,
            firedAtMs = source.firedAtMs,
            status = WorkflowRunStatus.CANCELLED,
            durationMs = source.durationMs,
            errorMessage = "Stopped by user.",
            trace = trace.copy(
                status = ExecutionTraceStatus.CANCELLED,
                endedAtMs = System.currentTimeMillis(),
                diagnostics = trace.diagnostics + "Stopped by user.",
            ),
        )
        return true
    }

    private fun workflowRetryDraft(
        loaded: WorkflowRepository.Loaded,
        trace: ExecutionTrace,
    ): WorkflowDefinition {
        val failedIndex = trace.currentStep ?: trace.steps.indexOfLast {
            it.status == ExecutionTraceStatus.FAILED
        }
        if (failedIndex !in loaded.definition.actions.indices) return loaded.definition
        val failed = loaded.definition.actions[failedIndex]
        val shouldExtendTimeout = trace.steps.getOrNull(failedIndex)?.error
            ?.lowercase()
            ?.contains("exceeded") == true
        if (!shouldExtendTimeout) return loaded.definition
        val updated = failed.copy(timeoutSeconds = (failed.timeoutSeconds + 30).coerceAtMost(600))
        val actions = loaded.definition.actions.toMutableList()
        actions[failedIndex] = updated
        return loaded.definition.copy(
            actions = actions,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    suspend fun createRepairDraft(runId: Long, kind: String): String? {
        val run = repository.getRun(runId) ?: return null
        val loaded = repository.getById(run.workflowId) ?: return null
        val trace = run.trace ?: return null
        val proposal = workflowRetryDraft(loaded, trace)
        val reason = kind + " repair draft for run " + runId +
            ". AI suggestions never modify the live workflow automatically."
        return repository.createRevision(
            workflowId = run.workflowId,
            sourceRunId = runId,
            reason = reason,
            definition = proposal,
            status = "DRAFT",
        )
    }

    /**
     * Pre-flight check for trigger types that depend on runtime state (a permission, a
     * service binding, Play Services availability). Returns null if the trigger can fire,
     * or a stable error code otherwise — the engine then records the fire as FAILED with
     * that reason and the user sees a clear "missing setup" message in workflow_get history.
     */
    private fun triggerRuntimeCheck(trigger: io.github.nastechresearch.nastech.workflow.model.TriggerSpec): String? {
        val ctx = (this as Any).let {
            // Static context lookup via Koin so we don't need to take it as a constructor arg
            // (engine is shared across cron / sub-agent surfaces; minimising its DI surface
            // is worth a tiny lookup cost on the rare-fire path).
            org.koin.java.KoinJavaComponent.getKoin().get<android.content.Context>()
        }
        return when (trigger) {
            is io.github.nastechresearch.nastech.workflow.model.TriggerSpec.GeofenceEnter,
            is io.github.nastechresearch.nastech.workflow.model.TriggerSpec.GeofenceExit -> {
                val fineGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val bgGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        ctx, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                } else true
                when {
                    !fineGranted -> "geofence_unavailable: ACCESS_FINE_LOCATION not granted — open Settings → Apps → Nastech → Permissions → Location and pick Allow all the time"
                    !bgGranted -> "geofence_unavailable: ACCESS_BACKGROUND_LOCATION not granted — open Settings → Apps → Nastech → Permissions → Location and pick Allow all the time"
                    else -> null
                }
            }
            is io.github.nastechresearch.nastech.workflow.model.TriggerSpec.NotificationReceived -> {
                if (!io.github.nastechresearch.nastech.data.ai.tools.local.NotificationListenerHandle.isBound()) {
                    "notification_listener_not_enabled: enable the Nastech notification listener in Settings → Apps → Special access → Notification access"
                } else null
            }
            is io.github.nastechresearch.nastech.workflow.model.TriggerSpec.AppLaunched,
            is io.github.nastechresearch.nastech.workflow.model.TriggerSpec.AppClosed -> {
                if (!io.github.nastechresearch.nastech.data.ai.tools.local.AccessibilityServiceHandle.isRunning()) {
                    "accessibility_not_enabled: enable the Nastech accessibility service in Settings → Accessibility (required for app_launched / app_closed triggers)"
                } else null
            }
            is io.github.nastechresearch.nastech.workflow.model.TriggerSpec.BluetoothDeviceConnected,
            is io.github.nastechresearch.nastech.workflow.model.TriggerSpec.BluetoothDeviceDisconnected -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                        ctx, android.Manifest.permission.BLUETOOTH_CONNECT
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!granted) "bluetooth_connect_not_granted: BLUETOOTH_CONNECT runtime permission not granted — required on Android 12+ to read paired-device addresses"
                    else null
                } else null
            }
            else -> null
        }
    }

    private suspend fun persistAndReturn(
        workflowId: String,
        firedAtMs: Long,
        startedNanos: Long,
        status: WorkflowRunStatus,
        error: String?,
        summary: String,
        ledgerId: String,
        runId: Long? = null,
        trace: ExecutionTrace? = null,
    ): FireOutcome {
        val durationMs = (System.nanoTime() - startedNanos) / 1_000_000L
        runCatching {
            if (runId != null && trace != null) {
                repository.finishRun(
                    runId = runId,
                    workflowId = workflowId,
                    firedAtMs = firedAtMs,
                    status = status,
                    durationMs = durationMs,
                    errorMessage = error,
                    trace = trace,
                )
            } else {
                repository.recordFire(
                    workflowId = workflowId,
                    firedAtMs = firedAtMs,
                    status = status,
                    durationMs = durationMs,
                    errorMessage = error,
                )
            }
        }.onFailure { Log.w(TAG, "recordFire failed for " + workflowId, it) }
        val ledgerStatus = when (status) {
            WorkflowRunStatus.SUCCESS -> io.github.nastechresearch.nastech.data.agentrun.AgentRunStatus.succeeded
            WorkflowRunStatus.FAILED -> io.github.nastechresearch.nastech.data.agentrun.AgentRunStatus.failed
            WorkflowRunStatus.RUNNING -> io.github.nastechresearch.nastech.data.agentrun.AgentRunStatus.running
            else -> io.github.nastechresearch.nastech.data.agentrun.AgentRunStatus.cancelled
        }
        agentRunRepo.markTerminal(
            id = ledgerId,
            status = ledgerStatus,
            lastError = error ?: if (ledgerStatus == io.github.nastechresearch.nastech.data.agentrun.AgentRunStatus.cancelled) status.name else null,
        )
        return FireOutcome(status, error, summary)
    }

    companion object { private const val TAG = "WorkflowEngine" }

    data class FireOutcome(
        val status: WorkflowRunStatus,
        val error: String?,
        val summary: String,
    )
}

/**
 * Cooldown decision in isolation so the (load-bearing) gate logic can be unit-tested
 * without spinning up Room + the engine. The rule: use the most-recent SUCCESS/FAILED
 * fire time, not the workflow row's projected lastRunAtMs (the projected column is
 * bumped on every attempt — including skips — so it can't be the cooldown anchor).
 */
internal object CooldownGate {
    fun isWithinCooldown(cooldownSeconds: Int, lastActualFireMs: Long?, nowMs: Long): Boolean {
        if (cooldownSeconds <= 0) return false
        if (lastActualFireMs == null) return false
        return nowMs < lastActualFireMs + cooldownSeconds * 1000L
    }
}

/**
 * Sequential action runner — wraps [io.github.nastechresearch.nastech.service.DirectModeActionRunner]'s
 * core logic but on the workflow side, since direct-mode's own runner takes a slightly
 * different action shape. Same HARDLINE-then-execute semantics.
 *
 * Per-action timeout is the action's [WorkflowAction.timeoutSeconds] field; default 60s.
 */
class WorkflowActionRunner {

    data class RunResult(
        val success: Boolean,
        val error: String?,
        val summary: String,
        val traceSteps: List<ExecutionTraceStep> = emptyList(),
        val currentStep: Int? = null,
        val checkpointStep: Int? = null,
    )

    suspend fun run(
        actions: List<WorkflowAction>,
        availableTools: List<Tool>,
        startIndex: Int = 0,
        existingSteps: List<ExecutionTraceStep> = emptyList(),
        onProgress: suspend (
            steps: List<ExecutionTraceStep>,
            currentStep: Int?,
            status: ExecutionTraceStatus,
            checkpointStep: Int?,
        ) -> Unit = { _, _, _, _ -> },
    ): RunResult {
        val steps = actions.mapIndexed { index, action ->
            existingSteps.getOrNull(index) ?: ExecutionTraceStep(
                index = index,
                action = action.tool,
                method = "Local tool execution",
                tool = action.tool,
                inputsSummary = safeJsonSummary(action.args),
                expectedState = "Tool reports success",
            )
        }.toMutableList()
        val outputs = mutableListOf<String>()
        var lastCheckpoint: Int? = existingSteps.indexOfLast { it.checkpoint }.takeIf { it >= 0 }

        if (startIndex >= actions.size) {
            return RunResult(true, null, "No pending workflow actions.", steps, null, lastCheckpoint)
        }

        for (idx in startIndex until actions.size) {
            val action = actions[idx]
            val previous = steps[idx]
            val retryCount = previous.retries +
                if (previous.status == ExecutionTraceStatus.FAILED || previous.status == ExecutionTraceStatus.RETRIED) 1 else 0
            val startedAt = System.currentTimeMillis()
            val running = previous.copy(
                index = idx,
                action = action.tool,
                method = "Local tool execution",
                status = ExecutionTraceStatus.RUNNING,
                tool = action.tool,
                inputsSummary = safeJsonSummary(action.args),
                resultSummary = null,
                error = null,
                startedAtMs = startedAt,
                endedAtMs = null,
                retries = retryCount,
                checkpoint = false,
            )
            steps[idx] = running
            onProgress(steps.toList(), idx, ExecutionTraceStatus.RUNNING, lastCheckpoint)

            val argsJson = action.args.toString()
            val hardlineReason = HardlineCommandGuard.checkTool(action.tool, argsJson)
            if (hardlineReason != null) {
                val finished = running.copy(
                    status = ExecutionTraceStatus.FAILED,
                    endedAtMs = System.currentTimeMillis(),
                    error = "hardline:" + hardlineReason,
                    resultSummary = "Blocked by execution policy.",
                )
                steps[idx] = finished
                onProgress(steps.toList(), idx, ExecutionTraceStatus.FAILED, lastCheckpoint)
                return RunResult(
                    false,
                    "action " + idx + ": hardline:" + hardlineReason,
                    outputs.joinToString("\\n"),
                    steps,
                    idx,
                    lastCheckpoint,
                )
            }

            val tool = availableTools.find { it.name == action.tool }
            if (tool == null) {
                val finished = running.copy(
                    status = ExecutionTraceStatus.FAILED,
                    endedAtMs = System.currentTimeMillis(),
                    error = "unknown_tool:" + action.tool,
                    resultSummary = "Required tool is not available.",
                )
                steps[idx] = finished
                onProgress(steps.toList(), idx, ExecutionTraceStatus.FAILED, lastCheckpoint)
                return RunResult(
                    false,
                    "action " + idx + ": unknown_tool:" + action.tool,
                    outputs.joinToString("\\n"),
                    steps,
                    idx,
                    lastCheckpoint,
                )
            }

            val out = try {
                withTimeoutOrNull(action.timeoutSeconds * 1000L) { tool.execute(action.args) }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                val error = (t::class.simpleName.orEmpty() + ": " + t.message.orEmpty()).take(500)
                val finished = running.copy(
                    status = ExecutionTraceStatus.FAILED,
                    endedAtMs = System.currentTimeMillis(),
                    error = error,
                    resultSummary = "Tool threw an exception.",
                )
                steps[idx] = finished
                onProgress(steps.toList(), idx, ExecutionTraceStatus.FAILED, lastCheckpoint)
                return RunResult(
                    false,
                    "action " + idx + ": " + error,
                    outputs.joinToString("\\n"),
                    steps,
                    idx,
                    lastCheckpoint,
                )
            }

            if (out == null) {
                val error = action.tool + " exceeded " + action.timeoutSeconds + "s"
                val finished = running.copy(
                    status = ExecutionTraceStatus.FAILED,
                    endedAtMs = System.currentTimeMillis(),
                    error = error,
                    resultSummary = "Tool execution timed out.",
                )
                steps[idx] = finished
                onProgress(steps.toList(), idx, ExecutionTraceStatus.FAILED, lastCheckpoint)
                return RunResult(
                    false,
                    "action " + idx + ": " + error,
                    outputs.joinToString("\\n"),
                    steps,
                    idx,
                    lastCheckpoint,
                )
            }

            val text = out.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
                .joinToString("\\n") { it.text }
                .take(400)
            val lower = text.lowercase()
            val success = !lower.contains("\"success\":false") &&
                !lower.contains("\"error\"") &&
                !lower.startsWith("[error")
            val finished = running.copy(
                status = if (success) ExecutionTraceStatus.SUCCESS else ExecutionTraceStatus.FAILED,
                endedAtMs = System.currentTimeMillis(),
                resultSummary = text.ifBlank { "Tool returned " + out.size + " message part(s)." },
                error = if (success) null else text.take(500),
                checkpoint = success,
                actualState = if (success) "Tool reported success" else text.take(500),
            )
            steps[idx] = finished
            if (success) {
                lastCheckpoint = idx
                outputs += "[" + idx + "] " + action.tool + ": " + text.take(200)
                onProgress(steps.toList(), idx, ExecutionTraceStatus.SUCCESS, lastCheckpoint)
            } else {
                onProgress(steps.toList(), idx, ExecutionTraceStatus.FAILED, lastCheckpoint)
                return RunResult(
                    false,
                    "action " + idx + ": " + action.tool + " returned an error",
                    outputs.joinToString("\\n"),
                    steps,
                    idx,
                    lastCheckpoint,
                )
            }
        }
        return RunResult(true, null, outputs.joinToString("\\n").take(2_000), steps, null, lastCheckpoint)
    }

    companion object { private const val TAG = "WorkflowActionRunner" }
}

