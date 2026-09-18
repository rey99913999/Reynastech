package io.github.nastechresearch.nastech.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.nastechresearch.nastech.data.agentconfig.AgentActivation
import io.github.nastechresearch.nastech.data.agentconfig.AgentAutonomyLevel
import io.github.nastechresearch.nastech.data.agentconfig.AgentOutputType
import io.github.nastechresearch.nastech.data.agentconfig.AgentRole
import io.github.nastechresearch.nastech.data.agentconfig.ConversationAgentConfig
import io.github.nastechresearch.nastech.data.agentconfig.ConversationAgentConfigRepository
import io.github.nastechresearch.nastech.data.agentconfig.ConversationAgentDefinition
import io.github.nastechresearch.nastech.data.agentconfig.ConversationAgentWorkflowEdge
import kotlinx.coroutines.launch
import io.github.nastechresearch.nastech.data.datastore.SettingsStore
import io.github.nastechresearch.nastech.data.memory.ConversationMemoryMode
import org.koin.compose.koinInject
import java.util.UUID

@Composable
fun ConversationCustomizationPage(conversationId: String) {
    val repository: ConversationAgentConfigRepository = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var config by remember(conversationId) {
        mutableStateOf(ConversationAgentConfig(conversationId = conversationId))
    }
    var loaded by remember(conversationId) { mutableStateOf(false) }
    var templateName by rememberSaveable { mutableStateOf("") }
    var selectedTemplateId by rememberSaveable { mutableStateOf("") }
    var edgeDraft by rememberSaveable { mutableStateOf("") }
    var showTemplates by remember { mutableStateOf(false) }
    var showAutonomy by remember { mutableStateOf(false) }
    var showMemory by remember { mutableStateOf(false) }
    var showAgentEditor by remember { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var agentName by rememberSaveable { mutableStateOf("") }
    var agentInstructions by rememberSaveable { mutableStateOf("") }
    var agentModelId by rememberSaveable { mutableStateOf("") }
    var agentAllowed by rememberSaveable { mutableStateOf("") }
    var agentDenied by rememberSaveable { mutableStateOf("") }
    var agentRole by rememberSaveable { mutableStateOf(AgentRole.CUSTOM.name) }
    var agentActivation by rememberSaveable { mutableStateOf(AgentActivation.ALWAYS.name) }
    var agentEnabled by rememberSaveable { mutableStateOf(true) }
    var showRole by remember { mutableStateOf(false) }
    var showActivation by remember { mutableStateOf(false) }
    var showModel by remember { mutableStateOf(false) }

    LaunchedEffect(conversationId) {
        config = repository.get(conversationId)
        loaded = true
    }

    val templates by repository.observeTemplates().collectAsStateWithLifecycle(initialValue = emptyList())

    fun persist(next: ConversationAgentConfig) {
        val normalized = next.normalized()
        config = normalized
        scope.launch { repository.save(normalized) }
    }

    if (!loaded) {
        Text("Loading…", modifier = Modifier.padding(24.dp))
        return
    }

    Column(
        modifier = Modifier.padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Conversation Customization", style = MaterialTheme.typography.headlineSmall)
        Text(
            "This configuration belongs only to this conversation. Agent Mode is optional.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Autonomous Task Mode", style = MaterialTheme.typography.titleMedium)
                    Switch(
                        checked = config.enabled && config.autonomousTaskMode,
                        onCheckedChange = { persist(config.copy(enabled = it, autonomousTaskMode = it)) },
                    )
                }
                MenuField(
                    value = config.autonomyLevel.name,
                    expanded = showAutonomy,
                    onOpen = { showAutonomy = true },
                    onDismiss = { showAutonomy = false },
                ) {
                    AgentAutonomyLevel.entries.forEach { level ->
                        DropdownMenuItem(
                            text = { Text(level.name) },
                            onClick = {
                                showAutonomy = false
                                persist(config.copy(autonomyLevel = level))
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = config.instructions,
                    onValueChange = { persist(config.copy(instructions = it)) },
                    label = { Text("Conversation instructions") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Agents", style = MaterialTheme.typography.titleMedium)
                config.agents.forEach { agent ->
                    AgentCard(
                        agent = agent,
                        onEdit = {
                            editingId = agent.id
                            agentName = agent.name
                            agentInstructions = agent.systemInstructions
                            agentModelId = agent.modelId.orEmpty()
                            agentAllowed = agent.allowedTools.joinToString(", ")
                            agentDenied = agent.deniedTools.joinToString(", ")
                            agentRole = agent.role.name
                            agentActivation = agent.activation.name
                            agentEnabled = agent.enabled
                            showAgentEditor = true
                        },
                        onDelete = {
                            persist(
                                config.copy(
                                    agents = config.agents.filterNot { it.id == agent.id },
                                    workflow = config.workflow.filter {
                                        it.fromAgentId != agent.id && it.toAgentId != agent.id
                                    },
                                )
                            )
                        },
                        onEnabledChange = { enabled ->
                            persist(config.copy(agents = config.agents.map {
                                if (it.id == agent.id) agent.copy(enabled = enabled) else it
                            }))
                        },
                    )
                }
                if (config.agents.isEmpty()) {
                    OutlinedButton(onClick = {
                        persist(
                            config.copy(
                                enabled = true,
                                autonomousTaskMode = true,
                                agents = listOf(
                                    ConversationAgentDefinition(
                                        id = UUID.randomUUID().toString(),
                                        name = "Planner",
                                        role = AgentRole.PLANNER,
                                        activation = AgentActivation.TASK_START,
                                        outputType = AgentOutputType.PLAN,
                                    ),
                                    ConversationAgentDefinition(
                                        id = UUID.randomUUID().toString(),
                                        name = "Executor",
                                        role = AgentRole.EXECUTOR,
                                        activation = AgentActivation.ALWAYS,
                                    ),
                                    ConversationAgentDefinition(
                                        id = UUID.randomUUID().toString(),
                                        name = "Verifier",
                                        role = AgentRole.VERIFIER,
                                        activation = AgentActivation.ON_REVIEW,
                                        outputType = AgentOutputType.VERIFICATION,
                                    ),
                                ),
                                workflow = emptyList(),
                            )
                        )
                    }) {
                        Text("Create Planner + Executor + Verifier")
                    }
                }
                OutlinedButton(onClick = {
                    editingId = null
                    agentName = ""
                    agentInstructions = ""
                    agentModelId = ""
                    agentAllowed = ""
                    agentDenied = ""
                    agentRole = AgentRole.CUSTOM.name
                    agentActivation = AgentActivation.ALWAYS.name
                    agentEnabled = true
                    showAgentEditor = true
                }) {
                    Text("Add Agent")
                }
            }
        }

        if (showAgentEditor) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Agent Editor", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(agentName, { agentName = it }, Modifier.fillMaxWidth(), label = { Text("Name") })
                    MenuField(agentRole, showRole, { showRole = true }, { showRole = false }) {
                        AgentRole.entries.forEach { role ->
                            DropdownMenuItem(
                                text = { Text(role.name) },
                                onClick = { agentRole = role.name; showRole = false },
                            )
                        }
                    }
                    MenuField(agentActivation, showActivation, { showActivation = true }, { showActivation = false }) {
                        AgentActivation.entries.forEach { activation ->
                            DropdownMenuItem(
                                text = { Text(activation.name) },
                                onClick = { agentActivation = activation.name; showActivation = false },
                            )
                        }
                    }
                    MenuField(
                        value = settings.providers.flatMap { it.models }
                            .firstOrNull { it.id.toString() == agentModelId }
                            ?.displayName ?: "Inherit conversation model",
                        expanded = showModel,
                        onOpen = { showModel = true },
                        onDismiss = { showModel = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Inherit conversation model") },
                            onClick = { agentModelId = ""; showModel = false },
                        )
                        settings.providers.flatMap { provider -> provider.models }.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.displayName) },
                                onClick = {
                                    agentModelId = model.id.toString()
                                    showModel = false
                                },
                            )
                        }
                    }
                    OutlinedTextField(
                        agentInstructions,
                        { agentInstructions = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("System instructions") },
                        minLines = 2,
                    )
                    OutlinedTextField(
                        agentAllowed,
                        { agentAllowed = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("Allowed tools (blank = all)") },
                    )
                    OutlinedTextField(
                        agentDenied,
                        { agentDenied = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("Denied tools") },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Enabled")
                        Switch(agentEnabled, { agentEnabled = it })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val id = editingId ?: UUID.randomUUID().toString()
                            val agent = ConversationAgentDefinition(
                                id = id,
                                name = agentName.ifBlank { "Agent" },
                                role = runCatching { AgentRole.valueOf(agentRole) }.getOrDefault(AgentRole.CUSTOM),
                                systemInstructions = agentInstructions,
                                modelId = agentModelId.takeIf(String::isNotBlank),
                                allowedTools = agentAllowed.split(",").map(String::trim).filter(String::isNotBlank).toSet(),
                                deniedTools = agentDenied.split(",").map(String::trim).filter(String::isNotBlank).toSet(),
                                activation = runCatching { AgentActivation.valueOf(agentActivation) }.getOrDefault(AgentActivation.ALWAYS),
                                outputType = AgentOutputType.TEXT,
                                enabled = agentEnabled,
                            )
                            persist(config.copy(agents = config.agents.filterNot { it.id == id } + agent))
                            showAgentEditor = false
                        }) { Text("Save Agent") }
                        OutlinedButton(onClick = { showAgentEditor = false }) { Text("Cancel") }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Workflow", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Format: agentId -> agentId | condition. Cycles are rejected by the runtime.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    edgeDraft,
                    { edgeDraft = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("New workflow edge") },
                )
                Button(onClick = {
                    val pieces = edgeDraft.split("|", limit = 2)
                    val ids = pieces.firstOrNull()?.split("->")?.map(String::trim).orEmpty()
                    if (ids.size == 2 && ids[0].isNotBlank() && ids[1].isNotBlank()) {
                        persist(
                            config.copy(
                                workflow = config.workflow + ConversationAgentWorkflowEdge(
                                    fromAgentId = ids[0],
                                    toAgentId = ids[1],
                                    condition = pieces.getOrNull(1)?.trim().orEmpty(),
                                )
                            )
                        )
                        edgeDraft = ""
                    }
                }) { Text("Add Edge") }
                config.workflow.forEach { edge ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(edge.fromAgentId + " -> " + edge.toAgentId)
                        OutlinedButton(onClick = {
                            persist(config.copy(workflow = config.workflow.filterNot { it == edge }))
                        }) { Text("Remove") }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Memory", style = MaterialTheme.typography.titleMedium)
                MenuField(config.memoryMode.name, showMemory, { showMemory = true }, { showMemory = false }) {
                    ConversationMemoryMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.name) },
                            onClick = {
                                showMemory = false
                                persist(config.copy(memoryMode = mode))
                            },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Use memory in context")
                    Switch(config.memoryUseInContext, { persist(config.copy(memoryUseInContext = it)) })
                }
                OutlinedTextField(
                    config.memoryTokenBudget.toString(),
                    { value -> value.toIntOrNull()?.let { persist(config.copy(memoryTokenBudget = it)) } },
                    Modifier.fillMaxWidth(),
                    label = { Text("Memory token budget") },
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Task Recovery", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Save checkpoints")
                    Switch(config.taskRecoveryPolicy.saveCheckpoints, {
                        persist(config.copy(taskRecoveryPolicy = config.taskRecoveryPolicy.copy(saveCheckpoints = it)))
                    })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Resume after app close")
                    Switch(config.taskRecoveryPolicy.resumeAfterAppClose, {
                        persist(config.copy(taskRecoveryPolicy = config.taskRecoveryPolicy.copy(resumeAfterAppClose = it)))
                    })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Sensitive actions require approval")
                    Switch(config.taskRecoveryPolicy.sensitiveActionsRequireApproval, {
                        persist(config.copy(taskRecoveryPolicy = config.taskRecoveryPolicy.copy(sensitiveActionsRequireApproval = it)))
                    })
                }
                OutlinedTextField(
                    config.taskRecoveryPolicy.retryCount.toString(),
                    { value -> value.toIntOrNull()?.let { persist(config.copy(taskRecoveryPolicy = config.taskRecoveryPolicy.copy(retryCount = it))) } },
                    Modifier.fillMaxWidth(),
                    label = { Text("Retry count") },
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Conversation Templates", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(templateName, { templateName = it }, Modifier.weight(1f), label = { Text("Template name") })
                    Button(onClick = {
                        scope.launch {
                            repository.saveTemplate(templateName, config)
                            templateName = ""
                        }
                    }) { Text("Save") }
                }
                MenuField(
                    templates.firstOrNull { it.id == selectedTemplateId }?.name ?: "Select template",
                    showTemplates,
                    { showTemplates = true },
                    { showTemplates = false },
                ) {
                    templates.forEach { template ->
                        DropdownMenuItem(
                            text = { Text(template.name) },
                            onClick = {
                                selectedTemplateId = template.id
                                showTemplates = false
                                scope.launch {
                                    repository.applyTemplate(conversationId, template.id)?.let { config = it }
                                }
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.padding(bottom = 24.dp))
    }
}

@Composable
private fun AgentCard(
    agent: ConversationAgentDefinition,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(agent.name, style = MaterialTheme.typography.titleSmall)
                Switch(agent.enabled, onEnabledChange)
            }
            Text(
                agent.role.name + " • " + agent.activation.name,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Allowed tools: " + if (agent.allowedTools.isEmpty()) "all" else agent.allowedTools.joinToString(),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit) { Text("Edit") }
                OutlinedButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun MenuField(
    value: String,
    expanded: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    menu: @Composable () -> Unit,
) {
    Column {
        OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text(value) }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) { menu() }
    }
}
