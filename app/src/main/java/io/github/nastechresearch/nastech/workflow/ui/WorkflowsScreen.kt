package io.github.nastechresearch.nastech.workflow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nastechresearch.nastech.Screen
import io.github.nastechresearch.nastech.ui.components.nav.BackButton
import io.github.nastechresearch.nastech.ui.context.LocalNavController
import io.github.nastechresearch.nastech.ui.theme.CustomColors
import io.github.nastechresearch.nastech.utils.RelativeTimeStrings
import io.github.nastechresearch.nastech.utils.formatRelativeAgo
import io.github.nastechresearch.nastech.utils.plus
import io.github.nastechresearch.nastech.workflow.model.TriggerSpec
import io.github.nastechresearch.nastech.workflow.model.WorkflowDefinition
import io.github.nastechresearch.nastech.workflow.model.WorkflowRunStatus
import io.github.nastechresearch.nastech.workflow.recording.RawRecording
import io.github.nastechresearch.nastech.workflow.repository.WorkflowRepository.Loaded
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@Composable
fun WorkflowsScreen(vm: WorkflowsViewModel = koinViewModel()) {
    val nav = LocalNavController.current
    val scope = rememberCoroutineScope()
    val workflows by vm.workflows.collectAsStateWithLifecycle()
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    val recordingState by vm.recordingState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var showHowItWorks by remember { mutableStateOf(false) }
    var showRecordDialog by remember { mutableStateOf(false) }
    var recordTitle by remember { mutableStateOf("") }
    var conversionError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(recordingState.active) {
        vm.refreshRecordings()
    }

    if (showHowItWorks) {
        AlertDialog(
            onDismissRequest = { showHowItWorks = false },
            title = { Text("Learned workflows") },
            text = {
                Text(
                    "Record a task once, preserve the raw recording, convert it to an editable " +
                        "draft, then explicitly approve it. Drafts stay disabled."
                )
            },
            confirmButton = {
                TextButton(onClick = { showHowItWorks = false }) { Text("Close") }
            },
        )
    }

    if (showRecordDialog) {
        AlertDialog(
            onDismissRequest = { showRecordDialog = false },
            title = { Text("Record Macro") },
            text = {
                OutlinedTextField(
                    value = recordTitle,
                    onValueChange = { recordTitle = it },
                    singleLine = true,
                    label = { Text("Recording name") },
                )
            },
            confirmButton = {
                Button(onClick = {
                    vm.startRecording(recordTitle)
                    recordTitle = ""
                    showRecordDialog = false
                }) { Text("Start Recording") }
            },
            dismissButton = { TextButton(onClick = { showRecordDialog = false }) { Text("Cancel") } },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Workflows") },
                navigationIcon = { BackButton() },
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
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (recordingState.active) {
                        Button(onClick = { vm.stopRecording() }) {
                            Text("Stop Recording (" + recordingState.actionCount + ")")
                        }
                    } else {
                        Button(onClick = { showRecordDialog = true }) {
                            Text("Start Recording / Record Macro")
                        }
                    }
                    TextButton(onClick = { showHowItWorks = true }) { Text("How it works") }
                }
            }

            if (recordingState.active) {
                item {
                    Text(
                        "Recording: " + recordingState.title,
                        modifier = Modifier.padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }

            conversionError?.let { error ->
                item {
                    Text(
                        error,
                        modifier = Modifier.padding(8.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (recordings.isNotEmpty()) {
                item {
                    Text(
                        "Raw recordings",
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(recordings, key = { it.id }) { recording ->
                    RecordingRow(
                        recording = recording,
                        onConvert = {
                            conversionError = null
                            scope.launch {
                                runCatching { vm.convertRecording(recording.id) }
                                    .onSuccess { workflowId ->
                                        if (workflowId != null) {
                                            nav.navigate(Screen.WorkflowLearningDraft(recording.id))
                                        } else {
                                            conversionError = "AI conversion failed. Configure a chat model/provider and try again."
                                        }
                                    }
                                    .onFailure {
                                        conversionError = it.message ?: "AI conversion failed."
                                    }
                            }
                        },
                        onDelete = { vm.deleteRecording(recording.id) },
                    )
                }
                item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            }

            if (workflows.isNotEmpty()) {
                item {
                    Text(
                        "Workflows",
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(workflows, key = { it.entity.id }) { loaded ->
                    WorkflowRow(
                        loaded = loaded,
                        onToggle = { enabled -> vm.setEnabled(loaded.entity.id, enabled) },
                        onTap = { nav.navigate(Screen.WorkflowDetail(loaded.entity.id)) },
                    )
                }
            } else if (recordings.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No workflows yet.",
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingRow(
    recording: RawRecording,
    onConvert: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        headlineContent = { Text(recording.title) },
        supportingContent = {
            Text(recording.status + " · actions=" + recording.actions.size)
        },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onConvert) { Text("Convert") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        },
    )
    HorizontalDivider()
}

@Composable
private fun WorkflowRow(
    loaded: Loaded,
    onToggle: (Boolean) -> Unit,
    onTap: () -> Unit,
) {
    val rel = relativeStrings()
    val nowMs by rememberTickingNowMs()
    val triggerSummary = remember(loaded.definition) { oneLineTriggerSummary(loaded.definition) }
    val statusLine: String = when {
        loaded.entity.lastRunAtMs == null -> "never run"
        else -> {
            val ago = formatRelativeAgo(loaded.entity.lastRunAtMs, nowMs, rel)
            when (loaded.entity.lastRunStatus) {
                WorkflowRunStatus.SUCCESS.name -> "last run succeeded " + ago
                WorkflowRunStatus.FAILED.name -> "last run failed " + ago
                else -> "last run skipped " + ago
            }
        }
    }
    val learnedDraft = loaded.definition.sourceRecordingId != null &&
        loaded.definition.approvedAtMs == null

    ListItem(
        modifier = Modifier.fillMaxWidth().clickable { onTap() }.padding(horizontal = 8.dp),
        headlineContent = { Text(loaded.entity.name) },
        supportingContent = {
            Text(
                (if (learnedDraft) "DRAFT — review required\n" else "") +
                    "When: " + triggerSummary + "\n" + statusLine,
                maxLines = 3,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = {
            Switch(
                checked = loaded.entity.enabled,
                onCheckedChange = onToggle,
                enabled = !learnedDraft,
            )
        },
    )
    HorizontalDivider()
}

internal fun oneLineTriggerSummary(def: WorkflowDefinition): String = when (val t = def.trigger) {
    is TriggerSpec.TimeCron -> if (!t.timeOfDay.isNullOrBlank()) "every " + t.timeOfDay else "schedule"
    is TriggerSpec.WifiConnected -> "WiFi connects" + (t.ssid?.let { " to " + it }.orEmpty())
    is TriggerSpec.WifiDisconnected -> "WiFi disconnects" + (t.ssid?.let { " from " + it }.orEmpty())
    is TriggerSpec.BluetoothDeviceConnected -> "Bluetooth connects"
    is TriggerSpec.BluetoothDeviceDisconnected -> "Bluetooth disconnects"
    is TriggerSpec.HeadphonesPlugged -> "headphones plugged"
    is TriggerSpec.HeadphonesUnplugged -> "headphones unplugged"
    is TriggerSpec.PowerConnected -> "power connected"
    is TriggerSpec.PowerDisconnected -> "power disconnected"
    is TriggerSpec.BatteryBelow -> "battery < " + t.thresholdPercent + "%"
    is TriggerSpec.BatteryAbove -> "battery > " + t.thresholdPercent + "%"
    is TriggerSpec.GeofenceEnter -> "you arrive at " + (t.label ?: "a place")
    is TriggerSpec.GeofenceExit -> "you leave " + (t.label ?: "a place")
    is TriggerSpec.AppLaunched -> t.packageName + " launches"
    is TriggerSpec.AppClosed -> t.packageName + " closes"
    is TriggerSpec.NotificationReceived -> "notification" + (t.packageName?.let { " from " + it } ?: "")
    is TriggerSpec.BootCompleted -> "device boots"
    is TriggerSpec.ScreenOn -> "screen on"
    is TriggerSpec.ScreenOff -> "screen off"
    is TriggerSpec.Manual -> "manual run only"
}

@Composable
internal fun relativeStrings(): RelativeTimeStrings = RelativeTimeStrings(
    justNow = "just now",
    secondsAgo = "%ds ago",
    minutesAgo = "%dm ago",
    hoursAgo = "%dh ago",
    daysAgo = "%dd ago",
)

@Composable
internal fun rememberTickingNowMs(): androidx.compose.runtime.State<Long> {
    val state = androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis())
    LaunchedEffect(Unit) {
        while (true) {
            state.longValue = System.currentTimeMillis()
            kotlinx.coroutines.delay(30_000L)
        }
    }
    return state
}
