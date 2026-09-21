package io.github.nastechresearch.nastech.workflow.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.nastechresearch.nastech.workflow.execution.WorkflowEngine
import io.github.nastechresearch.nastech.workflow.execution.WorkflowAiDiagnosisHook
import io.github.nastechresearch.nastech.workflow.db.WorkflowRevisionEntity
import io.github.nastechresearch.nastech.workflow.model.WorkflowJson
import io.github.nastechresearch.nastech.workflow.model.WorkflowRun
import io.github.nastechresearch.nastech.workflow.recording.RawRecording
import io.github.nastechresearch.nastech.workflow.recording.WorkflowRecordingController
import io.github.nastechresearch.nastech.workflow.recording.WorkflowRecordingConverter
import io.github.nastechresearch.nastech.workflow.recording.WorkflowRecordingStore
import io.github.nastechresearch.nastech.workflow.repository.WorkflowRepository
import io.github.nastechresearch.nastech.workflow.repository.WorkflowRepository.Loaded

class WorkflowsViewModel(
    private val repository: WorkflowRepository,
    private val engine: WorkflowEngine,
    private val diagnosisHook: WorkflowAiDiagnosisHook,
    private val context: Context,
    private val recordingStore: WorkflowRecordingStore,
    private val recordingConverter: WorkflowRecordingConverter,
) : ViewModel() {

    val workflows: StateFlow<List<Loaded>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _recordings = MutableStateFlow<List<RawRecording>>(emptyList())
    val recordings: StateFlow<List<RawRecording>> = _recordings

    val recordingState = WorkflowRecordingController.state

    init {
        refreshRecordings()
    }

    fun refreshRecordings() {
        viewModelScope.launch(Dispatchers.IO) {
            _recordings.value = recordingStore.listRecordings()
        }
    }

    fun startRecording(title: String) {
        runCatching { WorkflowRecordingController.start(context, title) }
            .onSuccess { refreshRecordings() }
    }

    fun stopRecording() {
        WorkflowRecordingController.stop(context)
        refreshRecordings()
    }

    fun deleteRecording(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            recordingStore.delete(id)
            _recordings.value = recordingStore.listRecordings()
        }
    }

    suspend fun convertRecording(id: String): String? = withContext(Dispatchers.IO) {
        val result = recordingConverter.convert(id)
        _recordings.value = recordingStore.listRecordings()
        result
    }

    suspend fun draftJson(id: String): String? = withContext(Dispatchers.IO) {
        recordingStore.getDraft(id)
    }

    suspend fun saveLearnedDraft(recordingId: String, rawJson: String): Result<String> =
        withContext(Dispatchers.IO) {
            when (val parsed = WorkflowJson.parse(rawJson, setOf("learned_action"))) {
                is WorkflowJson.ParseResult.Err ->
                    Result.failure(IllegalArgumentException(parsed.error + ": " + parsed.detail))
                is WorkflowJson.ParseResult.Ok -> {
                    if (parsed.definition.sourceRecordingId != recordingId) {
                        return@withContext Result.failure(
                            IllegalArgumentException("source_recording_id must match the recording")
                        )
                    }
                    val current = repository.getById(parsed.definition.id)
                    val definition = parsed.definition.copy(
                        enabled = false,
                        approvedAtMs = null,
                        sourceRecordingId = recordingId,
                        createdAtMs = current?.definition?.createdAtMs ?: parsed.definition.createdAtMs,
                        updatedAtMs = System.currentTimeMillis(),
                        authoringAssistantId = current?.definition?.authoringAssistantId
                            ?: parsed.definition.authoringAssistantId,
                    )
                    repository.upsert(definition)
                    repository.createRevision(
                        workflowId = definition.id,
                        sourceRunId = null,
                        reason = "User edited learned draft from recording " + recordingId,
                        definition = definition,
                        status = "DRAFT_EDITED",
                    )
                    val path = recordingStore.saveDraft(recordingId, WorkflowJson.encode(definition))
                    recordingStore.markConverted(recordingId, path)
                    Result.success(definition.id)
                }
            }
        }

    suspend fun approveLearnedDraft(recordingId: String, rawJson: String): Result<String> =
        withContext(Dispatchers.IO) {
            when (val parsed = WorkflowJson.parse(rawJson, setOf("learned_action"))) {
                is WorkflowJson.ParseResult.Err ->
                    Result.failure(IllegalArgumentException(parsed.error + ": " + parsed.detail))
                is WorkflowJson.ParseResult.Ok -> {
                    if (parsed.definition.sourceRecordingId != recordingId) {
                        return@withContext Result.failure(
                            IllegalArgumentException("source_recording_id must match the recording")
                        )
                    }
                    val current = repository.getById(parsed.definition.id)
                    val now = System.currentTimeMillis()
                    val definition = parsed.definition.copy(
                        enabled = true,
                        approvedAtMs = now,
                        sourceRecordingId = recordingId,
                        createdAtMs = current?.definition?.createdAtMs ?: parsed.definition.createdAtMs,
                        updatedAtMs = now,
                        authoringAssistantId = current?.definition?.authoringAssistantId
                            ?: parsed.definition.authoringAssistantId,
                    )
                    repository.upsert(definition)
                    repository.createRevision(
                        workflowId = definition.id,
                        sourceRunId = null,
                        reason = "User approved learned workflow from recording " + recordingId,
                        definition = definition,
                        status = "APPROVED",
                    )
                    val path = recordingStore.saveDraft(recordingId, WorkflowJson.encode(definition))
                    recordingStore.markConverted(recordingId, path)
                    Result.success(definition.id)
                }
            }
        }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = repository.getById(id) ?: return@launch
            if (enabled && loaded.definition.sourceRecordingId != null &&
                loaded.definition.approvedAtMs == null
            ) return@launch
            repository.setEnabled(id, enabled)
        }
    }

    fun delete(id: String, onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteCascading(id)
            onDone()
        }
    }

    suspend fun runNow(id: String): WorkflowEngine.FireOutcome = engine.fire(id)

    suspend fun history(id: String, limit: Int = 20): List<WorkflowRun> =
        repository.lastRuns(id, limit)

    suspend fun get(id: String): Loaded? = repository.getById(id)

    suspend fun getRun(runId: Long): WorkflowRun? = repository.getRun(runId)

    fun revisions(id: String): Flow<List<WorkflowRevisionEntity>> =
        repository.observeRevisions(id)

    suspend fun retry(runId: Long): WorkflowEngine.FireOutcome? =
        engine.retryRun(runId, skipFailedStep = false)

    suspend fun skipFailedStep(runId: Long): WorkflowEngine.FireOutcome? =
        engine.retryRun(runId, skipFailedStep = true)

    suspend fun pause(runId: Long): Boolean = engine.pauseRun(runId)

    suspend fun stop(runId: Long): Boolean = engine.cancelRun(runId)

    suspend fun createRepairDraft(runId: Long, kind: String): String? =
        engine.createRepairDraft(runId, kind)

    suspend fun applyRevision(revisionId: String): Boolean =
        repository.applyRevision(revisionId)

    suspend fun diagnose(runId: Long): String? {
        val run = repository.getRun(runId) ?: return null
        val trace = run.trace ?: return null
        val workflow = repository.getById(run.workflowId)?.definition ?: return null
        return diagnosisHook.diagnose(workflow, trace)
    }
}
