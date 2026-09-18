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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import io.github.nastechresearch.nastech.Screen
import io.github.nastechresearch.nastech.data.task.TaskManager
import io.github.nastechresearch.nastech.data.task.TaskStatus
import io.github.nastechresearch.nastech.data.task.TaskStepSpec
import io.github.nastechresearch.nastech.ui.components.nav.BackButton
import io.github.nastechresearch.nastech.ui.context.LocalNavController
import io.github.nastechresearch.nastech.ui.theme.CustomColors
import io.github.nastechresearch.nastech.utils.plus
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun TasksPage(taskManager: TaskManager = koinInject()) {
    val nav = LocalNavController.current
    val scope = rememberCoroutineScope()
    val tasks by taskManager.observeTasks().collectAsStateWithLifecycle(initialValue = emptyList())
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showCreate by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        taskManager.recoverStaleRunningTasks()
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Tasks") },
                navigationIcon = { BackButton() },
                actions = {
                    TextButton(onClick = { showCreate = true }) { Text("New") }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val statuses = listOf(
                TaskStatus.RUNNING,
                TaskStatus.PAUSED,
                TaskStatus.WAITING_FOR_USER,
                TaskStatus.COMPLETED,
                TaskStatus.FAILED,
                TaskStatus.PENDING,
                TaskStatus.CANCELLED,
            )
            val grouped = tasks.groupBy { it.status }
            statuses.forEach { status ->
                val entries = grouped[status.name].orEmpty()
                if (entries.isNotEmpty()) {
                    item("header-" + status.name) {
                        Text(
                            statusLabel(status),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                        )
                    }
                    items(entries, key = { it.taskId }) { task ->
                        ListItem(
                            headlineContent = { Text(task.goal) },
                            supportingContent = {
                                val stepLabel = task.currentStepId?.take(8) ?: "no step"
                                Text(
                                    task.progress.toString() + "% • " + stepLabel +
                                        (task.pausedReason?.let { " • " + it } ?: ""),
                                    maxLines = 2,
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(onClick = { nav.navigate(Screen.TaskDetail(task.taskId)) }) {
                                Text("Details")
                            }
                            when (status) {
                                TaskStatus.PAUSED,
                                TaskStatus.PENDING,
                                TaskStatus.FAILED,
                                TaskStatus.WAITING_FOR_USER -> {
                                    TextButton(onClick = {
                                        scope.launch { taskManager.resumeTask(task.taskId) }
                                    }) { Text("Resume") }
                                }
                                TaskStatus.RUNNING -> {
                                    TextButton(onClick = {
                                        scope.launch { taskManager.pauseTask(task.taskId) }
                                    }) { Text("Pause") }
                                }
                                else -> Unit
                            }
                            if (status != TaskStatus.COMPLETED && status != TaskStatus.CANCELLED) {
                                TextButton(onClick = {
                                    scope.launch { taskManager.cancelTask(task.taskId) }
                                }) { Text("Cancel") }
                            }
                        }
                    }
                }
            }
            if (tasks.isEmpty()) {
                item {
                    Text(
                        "No tasks yet. Create a task to persist its steps, checkpoints, and recovery state.",
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
    }

    if (showCreate) {
        CreateTaskDialog(
            taskManager = taskManager,
            onDismiss = { showCreate = false },
            onCreated = { id ->
                showCreate = false
                nav.navigate(Screen.TaskDetail(id))
            },
        )
    }
}

@Composable
private fun CreateTaskDialog(
    taskManager: TaskManager,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var goal by remember { mutableStateOf("") }
    var steps by remember { mutableStateOf("") }
    val conversationId = ""

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = goal,
                    onValueChange = { goal = it },
                    label = { Text("Goal") },
                    supportingText = { Text("One sentence describing the desired outcome.") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = steps,
                    onValueChange = { steps = it },
                    label = { Text("Steps") },
                    supportingText = { Text("One durable execution step per line.") },
                    minLines = 4,
                    maxLines = 8,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val specs = steps.lineSequence()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .map { text -> TaskStepSpec(text, text) }
                    .toList()
                if (goal.isNotBlank() && specs.isNotEmpty()) {
                    scope.launch {
                        val id = taskManager.createTask(
                            conversationId = conversationId,
                            goal = goal,
                            steps = specs,
                        )
                        onCreated(id)
                    }
                }
            }) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun statusLabel(status: TaskStatus): String = when (status) {
    TaskStatus.RUNNING -> "Running"
    TaskStatus.PAUSED -> "Paused"
    TaskStatus.WAITING_FOR_USER -> "Waiting for User"
    TaskStatus.COMPLETED -> "Completed"
    TaskStatus.FAILED -> "Failed"
    TaskStatus.CANCELLED -> "Cancelled"
    TaskStatus.PENDING -> "Pending"
}
