package io.github.nastechresearch.nastech.workflow.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import io.github.nastechresearch.nastech.workflow.execution.WorkflowEngine
import io.github.nastechresearch.nastech.workflow.execution.WorkflowAiDiagnosisHook
import io.github.nastechresearch.nastech.workflow.db.WorkflowRevisionEntity
import io.github.nastechresearch.nastech.workflow.model.WorkflowRun
import io.github.nastechresearch.nastech.workflow.repository.WorkflowRepository
import io.github.nastechresearch.nastech.workflow.repository.WorkflowRepository.Loaded

class WorkflowsViewModel(
    private val repository: WorkflowRepository,
    private val engine: WorkflowEngine,
    private val diagnosisHook: WorkflowAiDiagnosisHook,
) : ViewModel() {

    val workflows: StateFlow<List<Loaded>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { repository.setEnabled(id, enabled) }
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
