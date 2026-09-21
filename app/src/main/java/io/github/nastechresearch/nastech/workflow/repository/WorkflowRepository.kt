package io.github.nastechresearch.nastech.workflow.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid
import io.github.nastechresearch.nastech.data.execution.debug.ExecutionTrace
import io.github.nastechresearch.nastech.data.execution.debug.ExecutionTraceCodec
import io.github.nastechresearch.nastech.workflow.db.WorkflowDao
import io.github.nastechresearch.nastech.workflow.db.WorkflowEntity
import io.github.nastechresearch.nastech.workflow.db.WorkflowRunDao
import io.github.nastechresearch.nastech.workflow.db.WorkflowRunEntity
import io.github.nastechresearch.nastech.workflow.db.WorkflowRevisionDao
import io.github.nastechresearch.nastech.workflow.db.WorkflowRevisionEntity
import io.github.nastechresearch.nastech.workflow.model.WorkflowConstants
import io.github.nastechresearch.nastech.workflow.model.WorkflowDefinition
import io.github.nastechresearch.nastech.workflow.model.WorkflowJson
import io.github.nastechresearch.nastech.workflow.model.WorkflowRun
import io.github.nastechresearch.nastech.workflow.model.WorkflowRunStatus
import java.time.LocalDate
import java.time.ZoneId

/**
 * Phase 12 — single repository covering workflows + run history. The JSON blob in
 * [WorkflowEntity.definitionJson] is the source of truth — projected columns are derived.
 *
 * Phase-17 stability — the JSON parse is hot. Every Room emission re-fetches all rows; with
 * heavy fire rates and projection-only updates (lastRunAtMs / runsTodayCount) the parser
 * was running thousands of times an hour for ~16KB blobs that hadn't actually changed.
 * The audit measured 100 parses/min for one `@every 60s` workflow with two subscribers.
 *
 * Cache strategy: keyed on `(id, updatedAtMs)`. Projection-only writes don't bump
 * updatedAtMs (Phase 12 deliberately decoupled them from the JSON), so the cache hits.
 * Bounded to 200 entries — far above realistic workflow count and survives normal churn.
 */
class WorkflowRepository(
    private val workflowDao: WorkflowDao,
    private val workflowRunDao: WorkflowRunDao,
    private val workflowRevisionDao: WorkflowRevisionDao,
) {

    data class Loaded(val entity: WorkflowEntity, val definition: WorkflowDefinition)

    /** (id, updatedAtMs) → parsed definition. */
    private val parseCache = androidx.collection.LruCache<String, Pair<Long, WorkflowDefinition>>(200)

    private fun parseCached(row: WorkflowEntity): WorkflowDefinition? {
        val cached = parseCache.get(row.id)
        if (cached != null && cached.first == row.updatedAtMs) return cached.second
        val parsed = WorkflowJson.parseStored(row.definitionJson) ?: return null
        parseCache.put(row.id, row.updatedAtMs to parsed)
        return parsed
    }

    fun observeAll(): Flow<List<Loaded>> = workflowDao.observeAll().map { rows ->
        rows.mapNotNull { row -> parseCached(row)?.let { Loaded(row, it) } }
    }

    fun observeById(id: String): Flow<Loaded?> = workflowDao.observeById(id).map { row ->
        row?.let { parseCached(it)?.let { def -> Loaded(it, def) } }
    }

    suspend fun listAll(): List<Loaded> =
        workflowDao.listAll().mapNotNull { row ->
            parseCached(row)?.let { Loaded(row, it) }
        }

    suspend fun listEnabled(): List<Loaded> =
        workflowDao.listEnabled().mapNotNull { row ->
            parseCached(row)?.let { Loaded(row, it) }
        }

    suspend fun getById(id: String): Loaded? = workflowDao.getById(id)?.let { row ->
        parseCached(row)?.let { Loaded(row, it) }
    }

    /** Insert or replace a workflow. Updates [definitionJson] from the canonical encoder. */
    suspend fun upsert(definition: WorkflowDefinition) {
        val entity = WorkflowEntity(
            id = definition.id,
            name = definition.name,
            description = definition.description,
            enabled = definition.enabled,
            definitionJson = WorkflowJson.encode(definition),
            createdAtMs = definition.createdAtMs,
            updatedAtMs = definition.updatedAtMs,
            // Preserve last-run state across upsert by reading the current row first; a fresh
            // create will simply find null and use defaults below.
        )
        val existing = workflowDao.getById(definition.id)
        val merged = if (existing != null) entity.copy(
            createdAtMs = existing.createdAtMs,    // creation time is immutable
            lastRunAtMs = existing.lastRunAtMs,
            lastRunStatus = existing.lastRunStatus,
            lastRunError = existing.lastRunError,
            runsTodayCount = existing.runsTodayCount,
            runsTodayDate = existing.runsTodayDate,
        ) else entity
        workflowDao.upsert(merged)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        workflowDao.setEnabled(id, enabled, System.currentTimeMillis())
    }

    /** Delete the workflow row and its run history. */
    suspend fun deleteCascading(id: String): Boolean {
        workflowRunDao.deleteAllFor(id)
        val deleted = workflowDao.deleteById(id) > 0
        // Drop the per-workflow lock from the engine so its in-memory map can't grow
        // unbounded across LLM-driven create/delete churn. Resolved lazily because the
        // engine and repository have a circular DI relationship.
        if (deleted) {
            parseCache.remove(id)
            runCatching { engineRef?.forgetWorkflow(id) }
        }
        return deleted
    }

    @Volatile private var engineRef: io.github.nastechresearch.nastech.workflow.execution.WorkflowEngine? = null
    /**
     * Set by the DI module after both singletons exist — workaround for the circular
     * Engine-needs-Repo / Repo-needs-Engine dependency. The repository owns delete; it has
     * to notify the engine to drop in-memory caches.
     */
    fun bindEngine(engine: io.github.nastechresearch.nastech.workflow.execution.WorkflowEngine) {
        engineRef = engine
    }

    /**
     * Record a fire — write a [WorkflowRunEntity] history row, update the projected
     * last-run columns + daily counter on the workflow, and trim history to
     * [WorkflowConstants.MAX_RUNS_HISTORY] rows.
     */
    suspend fun beginRun(
        workflowId: String,
        firedAtMs: Long,
        trace: ExecutionTrace,
        parentRunId: Long? = null,
    ): Long {
        return workflowRunDao.insert(
            WorkflowRunEntity(
                workflowId = workflowId,
                firedAtMs = firedAtMs,
                status = WorkflowRunStatus.RUNNING.name,
                durationMs = 0L,
                errorMessage = null,
                traceJson = ExecutionTraceCodec.encode(trace),
                parentRunId = parentRunId,
                endedAtMs = null,
            )
        )
    }

    suspend fun updateRunTrace(runId: Long, trace: ExecutionTrace) {
        workflowRunDao.updateTrace(runId, ExecutionTraceCodec.encode(trace))
    }

    suspend fun finishRun(
        runId: Long,
        workflowId: String,
        firedAtMs: Long,
        status: WorkflowRunStatus,
        durationMs: Long,
        errorMessage: String?,
        trace: ExecutionTrace,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ) {
        val truncatedErr = errorMessage?.take(WorkflowConstants.MAX_ERROR_LENGTH)
        val endedAtMs = System.currentTimeMillis()
        workflowRunDao.markTerminal(
            rowId = runId,
            status = status.name,
            durationMs = durationMs,
            errorMessage = truncatedErr,
            traceJson = ExecutionTraceCodec.encode(trace.copy(
                status = when (status) {
                    WorkflowRunStatus.SUCCESS -> io.github.nastechresearch.nastech.data.execution.debug.ExecutionTraceStatus.SUCCESS
                    WorkflowRunStatus.FAILED -> io.github.nastechresearch.nastech.data.execution.debug.ExecutionTraceStatus.FAILED
                    WorkflowRunStatus.RUNNING -> io.github.nastechresearch.nastech.data.execution.debug.ExecutionTraceStatus.RUNNING
                    WorkflowRunStatus.PAUSED -> io.github.nastechresearch.nastech.data.execution.debug.ExecutionTraceStatus.WAITING
                    WorkflowRunStatus.CANCELLED -> io.github.nastechresearch.nastech.data.execution.debug.ExecutionTraceStatus.CANCELLED
                    else -> io.github.nastechresearch.nastech.data.execution.debug.ExecutionTraceStatus.SKIPPED
                },
                endedAtMs = endedAtMs,
            )),
            endedAtMs = endedAtMs,
        )
        val countsTowardCap = status == WorkflowRunStatus.SUCCESS || status == WorkflowRunStatus.FAILED
        val today = LocalDate.now(zoneId).toString()
        val current = workflowDao.getById(workflowId)
        val newCount = when {
            current == null -> if (countsTowardCap) 1 else 0
            current.runsTodayDate != today -> if (countsTowardCap) 1 else 0
            else -> current.runsTodayCount + (if (countsTowardCap) 1 else 0)
        }
        workflowDao.recordFire(
            id = workflowId,
            firedAtMs = firedAtMs,
            status = status.name,
            errorMessage = truncatedErr,
            runsTodayCount = newCount,
            runsTodayDate = today,
        )
        workflowRunDao.trim(workflowId, WorkflowConstants.MAX_RUNS_HISTORY)
    }

    suspend fun recordFire(
        workflowId: String,
        firedAtMs: Long,
        status: WorkflowRunStatus,
        durationMs: Long,
        errorMessage: String?,
        zoneId: ZoneId = ZoneId.systemDefault(),
        trace: ExecutionTrace? = null,
        parentRunId: Long? = null,
    ) {
        val traceValue = trace ?: ExecutionTrace(
            runId = "unpersisted",
            workflowId = workflowId,
            startedAtMs = firedAtMs,
            status = io.github.nastechresearch.nastech.data.execution.debug.ExecutionTraceStatus.PENDING,
        )
        val runId = beginRun(workflowId, firedAtMs, traceValue, parentRunId)
        finishRun(runId, workflowId, firedAtMs, status, durationMs, errorMessage, traceValue, zoneId)
    }

    /**
     * Most-recent SUCCESS/FAILED fire — used by the cooldown gate. The projected
     * `lastRunAtMs` column is bumped on every attempt (including skips) so it can't be
     * used here without breaking cooldown semantics.
     */
    suspend fun lastActualFireAtMs(workflowId: String): Long? =
        workflowRunDao.lastActualFireAtMs(workflowId)

    suspend fun getRun(runId: Long): WorkflowRun? =
        workflowRunDao.getById(runId)?.let { row ->
            WorkflowRun(
                rowId = row.rowId,
                workflowId = row.workflowId,
                firedAtMs = row.firedAtMs,
                status = runCatching { WorkflowRunStatus.valueOf(row.status) }
                    .getOrDefault(WorkflowRunStatus.FAILED),
                durationMs = row.durationMs,
                errorMessage = row.errorMessage,
                trace = ExecutionTraceCodec.decode(row.traceJson),
                parentRunId = row.parentRunId,
            )
        }

    fun observeRevisions(workflowId: String): kotlinx.coroutines.flow.Flow<List<WorkflowRevisionEntity>> =
        workflowRevisionDao.observeForWorkflow(workflowId)

    suspend fun createRevision(
        workflowId: String,
        sourceRunId: Long?,
        reason: String,
        definition: WorkflowDefinition,
        status: String = "DRAFT",
    ): String {
        val id = Uuid.random().toString()
        workflowRevisionDao.insert(
            WorkflowRevisionEntity(
                revisionId = id,
                workflowId = workflowId,
                sourceRunId = sourceRunId,
                createdAtMs = System.currentTimeMillis(),
                reason = reason.take(1_000),
                definitionJson = WorkflowJson.encode(definition),
                status = status,
            )
        )
        return id
    }

    suspend fun applyRevision(revisionId: String): Boolean {
        val revision = workflowRevisionDao.getById(revisionId) ?: return false
        val current = getById(revision.workflowId) ?: return false
        val candidate = WorkflowJson.parseStored(revision.definitionJson) ?: return false
        createRevision(
            workflowId = revision.workflowId,
            sourceRunId = revision.sourceRunId,
            reason = "Automatic rollback snapshot before applying revision ${revision.revisionId}",
            definition = current.definition,
            status = "ROLLBACK_SNAPSHOT",
        )
        upsert(candidate.copy(updatedAtMs = System.currentTimeMillis(), enabled = current.entity.enabled))
        workflowRevisionDao.setStatus(revision.revisionId, "APPLIED")
        return true
    }

    suspend fun rollbackToRevision(revisionId: String): Boolean = applyRevision(revisionId)

    suspend fun lastRuns(workflowId: String, limit: Int = 20): List<WorkflowRun> =
        workflowRunDao.lastN(workflowId, limit).map { row ->
            WorkflowRun(
                rowId = row.rowId,
                workflowId = row.workflowId,
                firedAtMs = row.firedAtMs,
                status = runCatching { WorkflowRunStatus.valueOf(row.status) }
                    .getOrDefault(WorkflowRunStatus.FAILED),
                durationMs = row.durationMs,
                errorMessage = row.errorMessage,
                trace = ExecutionTraceCodec.decode(row.traceJson),
                parentRunId = row.parentRunId,
            )
        }
}
