package io.github.nastechresearch.nastech.ui.pages.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nastechresearch.nastech.data.task.TaskManager
import io.github.nastechresearch.nastech.data.task.TaskPolicy
import io.github.nastechresearch.nastech.data.task.TaskStatus
import io.github.nastechresearch.nastech.data.task.TaskStepEntity
import io.github.nastechresearch.nastech.data.task.TaskStepStatus
import io.github.nastechresearch.nastech.ui.components.nav.BackButton
import io.github.nastechresearch.nastech.ui.context.LocalNavController
import io.github.nastechresearch.nastech.ui.theme.CustomColors
import io.github.nastechresearch.nastech.utils.plus
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun TaskDetailPage(
    taskId: String,
    taskManager: TaskManager = koinInject(),
) {
    val nav = LocalNavController.current
    val scope = rememberCoroutineScope()
    val task by taskManager.observeTask(taskId).collectAsStateWithLifecycle(initialValue = null)
    val steps by taskManager.observeSteps(taskId).collectAsStateWithLifecycle(initialValue = emptyList())
    val checkpoints by taskManager.observeCheckpoints(taskId).collectAsStateWithLifecycle(initialValue = emptyList())
    val audit by taskManager.observeAudit(taskId).collectAsStateWithLifecycle(initialValue = emptyList())
    var policy by remember(task?.conversationId) { mutableStateOf<TaskPolicy?>(null) }

    LaunchedEffect(task?.conversationId) {
        val conversationId = task?.conversationId ?: return@LaunchedEffect
        policy = taskManager.getPolicy(conversationId)
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(task?.goal ?: "Task") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (task != null && task?.status !in setOf(TaskStatus.COMPLETED.name, TaskStatus.CANCELLED.name)) {
                    TextButton(onClick = {
                        scope.launch { taskManager.saveCheckpoint(taskId, "Manual checkpoint") }
                    }) {
                        Text("Checkpoint")
                    }
                }
                when (task?.status) {
                    TaskStatus.RUNNING.name -> {
                        TextButton(onClick = { scope.launch { taskManager.pauseTask(taskId) } }) {
                            Text("Pause")
                        }
                    }
                    TaskStatus.PAUSED.name,
                    TaskStatus.PENDING.name,
                    TaskStatus.FAILED.name,
                    TaskStatus.WAITING_FOR_USER.name -> {
                        Button(onClick = { scope.launch { taskManager.resumeTask(taskId) } }) {
                            Text("Resume")
                        }
                    }
                    else -> Unit
                }
                if (task != null &&
                    task?.status !in setOf(TaskStatus.COMPLETED.name, TaskStatus.CANCELLED.name)
                ) {
                    TextButton(onClick = {
                        scope.launch {
                            taskManager.cancelTask(taskId)
                            nav.popBackStack()
                        }
                    }) {
                        Text("Cancel")
                    }
                }
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            task?.let { current ->
                item {
                    Text(
                        current.status + " • " + current.progress + "%",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    current.pausedReason?.let { reason ->
                        Text(
                            reason,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                item { Text("Steps", style = MaterialTheme.typography.titleMedium) }
                items(steps, key = { it.id }) { step ->
                    StepRow(step)
                }

                item { Text("Checkpoints", style = MaterialTheme.typography.titleMedium) }
                if (checkpoints.isEmpty()) {
                    item { Text("No checkpoints saved yet.") }
                } else {
                    items(checkpoints.take(10), key = { it.checkpointId }) { checkpoint ->
                        ListItem(
                            headlineContent = { Text(checkpoint.note ?: "Checkpoint") },
                            supportingContent = { Text(checkpoint.stateSummary) },
                        )
                    }
                }

                item { Text("Audit log", style = MaterialTheme.typography.titleMedium) }
                if (audit.isEmpty()) {
                    item { Text("No audit entries yet.") }
                } else {
                    items(audit.take(50), key = { it.eventId }) { entry ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    entry.eventType +
                                        (entry.status?.let { " • " + it } ?: ""),
                                )
                            },
                            supportingContent = {
                                Text(
                                    entry.message ?: entry.error ?: entry.outputSummary ?: "",
                                    maxLines = 3,
                                )
                            },
                        )
                    }
                }

                item { Text("Recovery policy", style = MaterialTheme.typography.titleMedium) }
                policy?.let { currentPolicy ->
                    PolicyEditor(
                        initial = currentPolicy,
                        onSave = { newPolicy ->
                            scope.launch {
                                taskManager.savePolicy(current.conversationId, newPolicy)
                                policy = newPolicy
                            }
                        },
                    )
                }
            } ?: item {
                Text("Task not found.", modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun StepRow(step: TaskStepEntity) {
    val status = runCatching { TaskStepStatus.valueOf(step.status) }
        .getOrDefault(TaskStepStatus.PENDING)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            (step.orderIndex + 1).toString() + ". " + step.logicalGoal,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            status.name,
            style = MaterialTheme.typography.labelMedium,
            color = when (status) {
                TaskStepStatus.SUCCESS -> MaterialTheme.colorScheme.primary
                TaskStepStatus.FAILED -> MaterialTheme.colorScheme.error
                TaskStepStatus.RUNNING -> MaterialTheme.colorScheme.secondary
                TaskStepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (!step.actualResult.isNullOrBlank()) {
            Text("Result: " + step.actualResult, style = MaterialTheme.typography.bodySmall)
        }
        if (!step.lastError.isNullOrBlank()) {
            Text(
                "Error: " + step.lastError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (step.requiresVerification) {
            Text(
                "Verification required" +
                    (step.verificationHint?.let { ": " + it } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PolicyEditor(
    initial: TaskPolicy,
    onSave: (TaskPolicy) -> Unit,
) {
    var saveCheckpoints by remember(initial) { mutableStateOf(initial.saveCheckpoints) }
    var resumeAfterClose by remember(initial) { mutableStateOf(initial.resumeAfterAppClose) }
    var sensitiveApproval by remember(initial) { mutableStateOf(initial.sensitiveActionsRequireApproval) }
    var retryCount by remember(initial) { mutableStateOf(initial.retryCount.toString()) }
    var retryDelay by remember(initial) { mutableStateOf(initial.retryDelayMs.toString()) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Save important checkpoints")
            Switch(
                checked = saveCheckpoints,
                onCheckedChange = { saveCheckpoints = it },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Resume after app close")
            Switch(
                checked = resumeAfterClose,
                onCheckedChange = { resumeAfterClose = it },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Sensitive actions require approval")
            Switch(
                checked = sensitiveApproval,
                onCheckedChange = { sensitiveApproval = it },
            )
        }
        OutlinedTextField(
            value = retryCount,
            onValueChange = { retryCount = it.filter(Char::isDigit).take(2) },
            label = { Text("Retry count") },
            singleLine = true,
        )
        OutlinedTextField(
            value = retryDelay,
            onValueChange = { retryDelay = it.filter(Char::isDigit).take(6) },
            label = { Text("Retry delay (ms)") },
            singleLine = true,
        )
        Text(
            "On tool failure: " + initial.onToolFailure.name
        )
        TextButton(onClick = {
            val next = when (initial.onToolFailure) {
                io.github.nastechresearch.nastech.data.task.TaskToolFailurePolicy.RETRY ->
                    io.github.nastechresearch.nastech.data.task.TaskToolFailurePolicy.ALTERNATIVE_TOOL
                io.github.nastechresearch.nastech.data.task.TaskToolFailurePolicy.ALTERNATIVE_TOOL ->
                    io.github.nastechresearch.nastech.data.task.TaskToolFailurePolicy.AGENT_DECIDES
                io.github.nastechresearch.nastech.data.task.TaskToolFailurePolicy.AGENT_DECIDES ->
                    io.github.nastechresearch.nastech.data.task.TaskToolFailurePolicy.ASK_USER
                io.github.nastechresearch.nastech.data.task.TaskToolFailurePolicy.ASK_USER ->
                    io.github.nastechresearch.nastech.data.task.TaskToolFailurePolicy.RETRY
            }
            onSave(
                initial.copy(
                    saveCheckpoints = saveCheckpoints,
                    resumeAfterAppClose = resumeAfterClose,
                    sensitiveActionsRequireApproval = sensitiveApproval,
                    retryCount = retryCount.toIntOrNull() ?: initial.retryCount,
                    retryDelayMs = retryDelay.toLongOrNull() ?: initial.retryDelayMs,
                    onToolFailure = next,
                )
            )
        }) {
            Text("Change tool-failure policy")
        }
        Text("Unrecoverable error: " + initial.onUnrecoverableError.name)
        TextButton(onClick = {
            val next = when (initial.onUnrecoverableError) {
                io.github.nastechresearch.nastech.data.task.TaskUnrecoverablePolicy.PAUSE ->
                    io.github.nastechresearch.nastech.data.task.TaskUnrecoverablePolicy.FAIL
                io.github.nastechresearch.nastech.data.task.TaskUnrecoverablePolicy.FAIL ->
                    io.github.nastechresearch.nastech.data.task.TaskUnrecoverablePolicy.PAUSE
            }
            onSave(
                initial.copy(
                    saveCheckpoints = saveCheckpoints,
                    resumeAfterAppClose = resumeAfterClose,
                    sensitiveActionsRequireApproval = sensitiveApproval,
                    retryCount = retryCount.toIntOrNull() ?: initial.retryCount,
                    retryDelayMs = retryDelay.toLongOrNull() ?: initial.retryDelayMs,
                    onUnrecoverableError = next,
                )
            )
        }) {
            Text("Change unrecoverable-error policy")
        }
        TextButton(
            onClick = {
                onSave(
                    initial.copy(
                        saveCheckpoints = saveCheckpoints,
                        resumeAfterAppClose = resumeAfterClose,
                        sensitiveActionsRequireApproval = sensitiveApproval,
                        retryCount = retryCount.toIntOrNull() ?: initial.retryCount,
                        retryDelayMs = retryDelay.toLongOrNull() ?: initial.retryDelayMs,
                    )
                )
            },
        ) {
            Text("Save policy")
        }
    }
}
