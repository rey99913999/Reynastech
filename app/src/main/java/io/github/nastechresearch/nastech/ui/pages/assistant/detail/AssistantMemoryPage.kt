package io.github.nastechresearch.nastech.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nastechresearch.nastech.R
import io.github.nastechresearch.nastech.data.memory.ConversationMemory
import io.github.nastechresearch.nastech.data.memory.ConversationMemoryCandidate
import io.github.nastechresearch.nastech.data.memory.ConversationMemoryEngine
import io.github.nastechresearch.nastech.data.memory.ConversationMemoryMode
import io.github.nastechresearch.nastech.data.memory.MemoryImportance
import io.github.nastechresearch.nastech.data.memory.MemoryStatus
import io.github.nastechresearch.nastech.data.memory.MemoryType
import io.github.nastechresearch.nastech.data.model.Assistant
import io.github.nastechresearch.nastech.data.model.AssistantMemory
import io.github.nastechresearch.nastech.ui.components.nav.BackButton
import io.github.nastechresearch.nastech.ui.components.ui.CardGroup
import io.github.nastechresearch.nastech.ui.components.ui.RikkaConfirmDialog
import io.github.nastechresearch.nastech.ui.hooks.EditStateContent
import io.github.nastechresearch.nastech.ui.hooks.rememberSharedPreferenceString
import io.github.nastechresearch.nastech.ui.hooks.useEditState
import io.github.nastechresearch.nastech.ui.theme.CustomColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantMemoryPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val memories by vm.memories.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_memory))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantMemoryContent(
            innerPadding = innerPadding,
            assistant = assistant,
            memories = memories,
            onUpdateAssistant = { vm.update(it) },
            onDeleteMemory = { vm.deleteMemory(it) },
            onAddMemory = { vm.addMemory(it) },
            onUpdateMemory = { vm.updateMemory(it) }
        )
    }
}

@Composable
private fun AssistantMemoryContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    memories: List<AssistantMemory>,
    onUpdateAssistant: (Assistant) -> Unit,
    onAddMemory: (AssistantMemory) -> Unit,
    onUpdateMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
) {
    val memoryDialogState = useEditState<AssistantMemory> {
        if (it.id == 0) {
            onAddMemory(it)
        } else {
            onUpdateMemory(it)
        }
    }
    var pendingDeleteMemory by remember { mutableStateOf<AssistantMemory?>(null) }

    // 记忆对话框
    memoryDialogState.EditStateContent { memory, update ->
        AlertDialog(
            onDismissRequest = {
                memoryDialogState.dismiss()
            },
            title = {
                Text(stringResource(R.string.assistant_page_manage_memory_title))
            },
            text = {
                TextField(
                    value = memory.content,
                    onValueChange = {
                        update(memory.copy(content = it))
                    },
                    label = {
                        Text(stringResource(R.string.assistant_page_manage_memory_title))
                    },
                    minLines = 2,
                    maxLines = 8
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        memoryDialogState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        memoryDialogState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CardGroup {
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_memory)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.assistant_page_memory_desc),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = assistant.enableMemory,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    enableMemory = it
                                )
                            )
                        }
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_global_memory)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.assistant_page_global_memory_desc),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = assistant.useGlobalMemory,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    useGlobalMemory = it
                                )
                            )
                        },
                        enabled = assistant.enableMemory
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_recent_chats)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.assistant_page_recent_chats_desc),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = assistant.enableRecentChatsReference,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    enableRecentChatsReference = it
                                )
                            )
                        }
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_time_reminder)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.assistant_page_time_reminder_desc),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = assistant.enableTimeReminder,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    enableTimeReminder = it
                                )
                            )
                        }
                    )
                }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.assistant_page_manage_memory_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .align(Alignment.CenterStart)
            )

            IconButton(
                onClick = {
                    memoryDialogState.open(AssistantMemory(0, ""))
                },
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Text(
                    text = "+",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }

        memories.forEach { memory ->
            key(memory.id) {
                MemoryItem(
                    memory = memory,
                    onEditMemory = {
                        memoryDialogState.open(it)
                    },
                    onDeleteMemory = {
                        pendingDeleteMemory = it
                    }
                )
            }
        }

        ConversationMemoryPanel()
    }

    RikkaConfirmDialog(
        show = pendingDeleteMemory != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            pendingDeleteMemory?.let(onDeleteMemory)
            pendingDeleteMemory = null
        },
        onDismiss = { pendingDeleteMemory = null },
        text = {
            Text(
                text = pendingDeleteMemory?.content.orEmpty(),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}

@Composable
private fun ConversationMemoryPanel() {
    val engine: ConversationMemoryEngine = koinInject()
    val lastConversationId by rememberSharedPreferenceString("lastConversationId")
    val conversationId = lastConversationId?.takeIf { it.isNotBlank() } ?: return

    var settings by remember(conversationId) { mutableStateOf<io.github.nastechresearch.nastech.data.memory.ConversationMemorySettings?>(null) }
    var memories by remember(conversationId) { mutableStateOf<List<ConversationMemory>>(emptyList()) }
    var candidates by remember(conversationId) { mutableStateOf<List<ConversationMemoryCandidate>>(emptyList()) }
    var showExport by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var editingId by remember { mutableStateOf<Int?>(null) }
    var editingContent by remember { mutableStateOf("") }

    suspend fun refresh() {
        settings = engine.getSettings(conversationId)
        memories = engine.memorySearch(conversationId, "", tokenBudget = 16_000)
        candidates = engine.observePendingCandidates(conversationId).first()
    }

    LaunchedEffect(conversationId) {
        refresh()
    }

    val currentSettings = settings ?: return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Conversation Memory", style = MaterialTheme.typography.titleMedium)
            Text(
                "آخر محادثة نشطة: ${conversationId.take(8)}…",
                style = MaterialTheme.typography.bodySmall,
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val modes = ConversationMemoryMode.entries
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = currentSettings.mode == mode,
                        onClick = {
                            scope.launch {
                                engine.setMode(conversationId, mode)
                                settings = engine.getSettings(conversationId)
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    ) { Text(mode.name) }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Use Memory in Context")
                    Text("يضيف الاسترجاع الانتقائي إلى طلب النموذج فقط", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = currentSettings.useInContext,
                    onCheckedChange = { enabled ->
                        kotlinx.coroutines.GlobalScope.launch {
                            engine.setUseInContext(conversationId, enabled)
                            settings = engine.getSettings(conversationId)
                        }
                    },
                )
            }

            Text("Memories: ${memories.size}")
            memories.forEach { memory ->
                ConversationMemoryItem(
                    memory = memory,
                    onEdit = {
                        editingId = it.id
                        editingContent = it.content
                    },
                    onToggleFreeze = {
                        kotlinx.coroutines.GlobalScope.launch {
                            engine.freezeMemory(conversationId, it.id, !it.frozen)
                            refresh()
                        }
                    },
                    onDelete = {
                        kotlinx.coroutines.GlobalScope.launch {
                            engine.memoryDelete(conversationId, it.id)
                            refresh()
                        }
                    },
                )
            }

            if (candidates.isNotEmpty()) {
                Text("Pending approval", style = MaterialTheme.typography.titleSmall)
                candidates.forEach { candidate ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(candidate.content, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    kotlinx.coroutines.GlobalScope.launch {
                                        engine.approveCandidate(conversationId, candidate.id)
                                        refresh()
                                    }
                                }) { Text("Approve") }
                                TextButton(onClick = {
                                    kotlinx.coroutines.GlobalScope.launch {
                                        engine.rejectCandidate(conversationId, candidate.id)
                                        refresh()
                                    }
                                }) { Text("Reject") }
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    kotlinx.coroutines.GlobalScope.launch {
                        engine.clearMemory(conversationId)
                        refresh()
                    }
                }) { Text("Clear Memory") }
                TextButton(onClick = {
                    kotlinx.coroutines.GlobalScope.launch {
                        exportText = engine.exportMemory(conversationId)
                        showExport = true
                    }
                }) { Text("Export Memory") }
            }
        }
    }

    if (editingId != null) {
        AlertDialog(
            onDismissRequest = { editingId = null },
            title = { Text("Edit Memory") },
            text = {
                TextField(
                    value = editingContent,
                    onValueChange = { editingContent = it },
                    minLines = 3,
                    maxLines = 8,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    kotlinx.coroutines.GlobalScope.launch {
                        engine.memoryUpdate(conversationId, editingId!!, editingContent)
                        editingId = null
                        refresh()
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingId = null }) { Text("Cancel") } },
        )
    }

    if (showExport) {
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text("Exported Memory") },
            text = { Text(exportText, maxLines = 20, overflow = TextOverflow.Ellipsis) },
            confirmButton = { TextButton(onClick = { showExport = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun ConversationMemoryItem(
    memory: ConversationMemory,
    onEdit: (ConversationMemory) -> Unit,
    onToggleFreeze: (ConversationMemory) -> Unit,
    onDelete: (ConversationMemory) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("${memory.type.name} • ${memory.importance.name}", style = MaterialTheme.typography.labelMedium)
                Text("#${memory.id}", style = MaterialTheme.typography.labelSmall)
            }
            Text(memory.content, maxLines = 5, overflow = TextOverflow.Ellipsis)
            Text(
                "confidence=${"%.2f".format(memory.confidence)} • ${memory.status.name}" +
                    if (memory.frozen) " • FROZEN" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onEdit(memory) }) { Text("Edit") }
                TextButton(onClick = { onToggleFreeze(memory) }) { Text(if (memory.frozen) "Unfreeze" else "Freeze") }
                TextButton(onClick = { onDelete(memory) }) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun MemoryItem(
    memory: AssistantMemory,
    onEditMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "#${memory.id}",
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                Text(
                    text = memory.content,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(
                onClick = { onEditMemory(memory) }
            ) {
                Icon(me.rerere.hugeicons.HugeIcons.PencilEdit01, null)
            }
            IconButton(
                onClick = { onDeleteMemory(memory) }
            ) {
                Icon(
                    me.rerere.hugeicons.HugeIcons.Delete01,
                    stringResource(R.string.assistant_page_delete)
                )
            }
        }
    }
}
