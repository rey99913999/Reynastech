package io.github.nastechresearch.nastech.ui.components.execution

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.nastechresearch.nastech.data.execution.debug.ExecutionTraceStatus
import io.github.nastechresearch.nastech.data.execution.debug.ExecutionTraceStep
import io.github.nastechresearch.nastech.data.execution.debug.humanReadableExecutionError
import io.github.nastechresearch.nastech.data.task.TaskAuditLogEntity
import io.github.nastechresearch.nastech.data.task.TaskCheckpointEntity
import io.github.nastechresearch.nastech.data.task.TaskStepEntity
import io.github.nastechresearch.nastech.workflow.db.WorkflowRevisionEntity
import io.github.nastechresearch.nastech.workflow.model.WorkflowRun

@Composable
fun WorkflowRunInspectorDialog(
    run: WorkflowRun,
    revisions: List<WorkflowRevisionEntity>,
    diagnosis: String?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onSkip: () -> Unit,
    onCreateDraft: (String) -> Unit,
    onDiagnose: () -> Unit,
    onApplyRevision: (String) -> Unit,
) {
    val trace = run.trace
    val scroll = rememberScrollState()
    var expandedStep by remember { mutableIntStateOf(trace?.currentStep ?: 0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Execution Inspector") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Run ID: " + run.rowId)
                Text("Workflow: " + run.workflowId)
                Text("Status: " + run.status.name)
                Text("Retries: " + (trace?.retryCount ?: 0))
                trace?.checkpointStep?.let { Text("Last checkpoint: step " + (it + 1)) }
                trace?.diagnostics?.forEach { Text("Diagnostic: " + it) }

                HorizontalDivider()
                Text("Timeline", style = MaterialTheme.typography.titleMedium)
                trace?.steps.orEmpty().forEach { step ->
                    val selected = expandedStep == step.index
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (selected) MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.surface,
                            )
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            (step.index + 1).toString() + ". " + step.action +
                                " • " + step.status.name +
                                (if (step.retries > 0) " • retries=" + step.retries else ""),
                            color = statusColor(step.status),
                        )
                        Text(
                            step.tool?.let { "Tool: " + it } ?: "Tool: unknown",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            step.resultSummary ?: step.error ?: "No result recorded.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { expandedStep = step.index }) {
                            Text(if (selected) "Selected step" else "Inspect step")
                        }
                        if (selected) {
                            Text("Action: " + step.action)
                            Text("Method: " + (step.method ?: "not recorded"))
                            Text("Attempts: " + (step.retries + 1))
                            Text("Previous state: " + (step.actualState ?: "not recorded"))
                            Text("Expected state: " + (step.expectedState ?: "not recorded"))
                            step.visionConfidence?.let { Text("Vision confidence: " + (it * 100).toInt() + "%") }
                            step.diagnosticRef?.let { Text("Evidence: " + it) }
                            step.error?.let { Text("Reason: " + humanReadableExecutionError(it)) }
                            Text("Checkpoint: " + if (step.checkpoint) "yes" else "no")
                        }
                    }
                }

                diagnosis?.let {
                    HorizontalDivider()
                    Text("AI diagnosis", style = MaterialTheme.typography.titleMedium)
                    Text(it)
                }

                if (revisions.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Repair revisions", style = MaterialTheme.typography.titleMedium)
                    revisions.take(5).forEach { revision ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(revision.status + " • " + revision.reason)
                            if (revision.status == "DRAFT") {
                                TextButton(onClick = { onApplyRevision(revision.revisionId) }) {
                                    Text("Apply revision")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onRetry) { Text("Retry") }
                TextButton(onClick = onPause) { Text("Pause") }
                TextButton(onClick = onStop) { Text("Stop") }
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = onSkip) { Text("Skip step") }
                TextButton(onClick = onDiagnose) { Text("AI diagnosis") }
                TextButton(onClick = { onCreateDraft("Vision-assisted") }) { Text("Vision repair draft") }
                TextButton(onClick = { onCreateDraft("Alternative-tool") }) { Text("Alternative-tool draft") }
                TextButton(onClick = { onCreateDraft("Workflow") }) { Text("Repair workflow draft") }
            }
        },
    )
}

@Composable
fun TaskStepInspectorDialog(
    step: TaskStepEntity,
    audit: List<TaskAuditLogEntity>,
    checkpoints: List<TaskCheckpointEntity>,
    onDismiss: () -> Unit,
    onVerifyExisting: () -> Unit,
    onRetry: () -> Unit,
    onPause: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Step Inspector") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Action: " + step.logicalGoal)
                Text("Method: " + step.executionInstruction)
                Text("Result: " + (step.actualResult ?: "not recorded"))
                Text("Attempts: " + (step.retryCount + 1))
                step.lastError?.let { Text("Reason: " + humanReadableExecutionError(it)) }
                Text("Expected: " + (step.expectedResult ?: step.verificationHint ?: "not specified"))
                Text("Actual: " + (step.actualResult ?: "not recorded"))
                Text("Tool/checkpoint: " + (step.checkpointId ?: "none"))

                HorizontalDivider()
                Text("Evidence / audit", style = MaterialTheme.typography.titleMedium)
                audit.filter { it.stepId == step.id }.takeLast(10).reversed().forEach {
                    Text(
                        it.eventType +
                            (it.status?.let { status -> " • " + status } ?: "") +
                            " — " + (it.message ?: it.error ?: it.outputSummary.orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val checkpoint = checkpoints.firstOrNull { it.checkpointId == step.checkpointId }
                checkpoint?.let {
                    Text("Checkpoint before/after: " + it.stateSummary, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (step.status == "FAILED") {
                    TextButton(onClick = onVerifyExisting) { Text("Result exists") }
                    Button(onClick = onRetry) { Text("Retry") }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onPause) { Text("Pause task") }
        },
    )
}

private fun statusColor(status: ExecutionTraceStatus): Color = when (status) {
    ExecutionTraceStatus.SUCCESS -> Color.Unspecified
    ExecutionTraceStatus.FAILED -> Color.Unspecified
    ExecutionTraceStatus.RUNNING -> Color.Unspecified
    else -> Color.Unspecified
}
