package io.github.nastechresearch.nastech.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nastechresearch.nastech.data.agent.AgentActivationCondition
import io.github.nastechresearch.nastech.data.agent.AgentAutonomyLevel
import io.github.nastechresearch.nastech.data.agent.AgentConfigRepository
import io.github.nastechresearch.nastech.data.agent.AgentDefinition
import io.github.nastechresearch.nastech.data.agent.AgentRole
import io.github.nastechresearch.nastech.data.agent.ConversationAgentConfig
import io.github.nastechresearch.nastech.data.agent.defaultWorkflow
import io.github.nastechresearch.nastech.data.datastore.SettingsStore
import io.github.nastechresearch.nastech.ui.components.ai.ModelSelector
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid
import org.koin.compose.koinInject

@Composable
fun ConversationAgentCustomizationSheet(
    conversationId: Uuid,
    onDismiss: () -> Unit,
    repository: AgentConfigRepository = koinInject(),
    settingsStore: SettingsStore = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val config by repository.observe(conversationId.toString()).collectAsState(initial = ConversationAgentConfig.defaultFor(conversationId.toString()))
    val templates by repository.observeTemplates().collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle(initialValue = io.github.nastechresearch.nastech.data.datastore.Settings.dummy())

    var draft by remember(config.updatedAt) { mutableStateOf(config) }
    var workflowText by remember(config.updatedAt) { mutableStateOf(workflowAsText(config)) }
    var templateName by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        repository.get(conversationId.toString())
        repository.ensureBuiltInTemplates()
    }

    fun saveConfig() {
        val lookup = draft.agents.associateBy { it.name.trim().lowercase() }
        val edges = workflowText.lines().mapNotNull { line ->
            val parts = line.split("->").map(String::trim).filter(String::isNotBlank)
            if (parts.size != 2) return@mapNotNull null
            val from = lookup[parts[0].lowercase()]?.id ?: return@mapNotNull null
            val to = lookup[parts[1].lowercase()]?.id ?: return@mapNotNull null
            io.github.nastechresearch.nastech.data.agent.AgentWorkflowEdge(from, to)
        }
        scope.launch {
            repository.save(draft.copy(workflow = edges.ifEmpty { defaultWorkflow(draft.agents) }))
        }
    }

    ModalBottomSheet(onDismissRequest = {
        saveConfig()
        onDismiss()
    }) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Conversation Customization", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Each conversation is an independent Agent Workspace. Changes here affect only this conversation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Agent Runtime")
                        Text("Expose the bounded run_autonomous_task tool to this conversation.")
                    }
                    Switch(
                        checked = draft.enabled,
                        onCheckedChange = { draft = draft.copy(enabled = it) },
                    )
                }
            }

            item {
                TextButton(onClick = {
                    val next = AgentAutonomyLevel.entries
                    val idx = next.indexOf(draft.autonomyLevel)
                    draft = draft.copy(autonomyLevel = next[(idx + 1) % next.size])
                }) {
                    Text("Autonomy: " + draft.autonomyLevel.name)
                }
                Text(
                    when (draft.autonomyLevel) {
                        AgentAutonomyLevel.PLAN_ONLY -> "Plan Only — planning Agents run; execution stops."
                        AgentAutonomyLevel.SAFE_AUTO -> "Safe Auto — low-risk execution is allowed; sensitive tools are withheld."
                        AgentAutonomyLevel.AUTONOMOUS -> "Autonomous — the workflow runs within explicit limits and still blocks sensitive actions."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            item {
                OutlinedTextField(
                    value = draft.instructions,
                    onValueChange = { draft = draft.copy(instructions = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Conversation Agent instructions") },
                    minLines = 3,
                    maxLines = 6,
                )
            }

            item {
                Text("Templates", style = MaterialTheme.typography.titleMedium)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    templates.forEach { template ->
                        TextButton(onClick = {
                            scope.launch {
                                repository.applyTemplate(template.id, conversationId.toString())?.let {
                                    draft = it
                                    workflowText = workflowAsText(it)
                                }
                            }
                        }) {
                            Text(template.name + " — " + template.description)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = templateName,
                        onValueChange = { templateName = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Save current config as template") },
                    )
                    Button(
                        onClick = {
                            val name = templateName.trim()
                            if (name.isNotEmpty()) {
                                scope.launch {
                                    saveConfig()
                                    repository.saveTemplate(name, draft)
                                    templateName = ""
                                }
                            }
                        },
                        enabled = templateName.isNotBlank(),
                    ) { Text("Save") }
                }
            }

            item {
                HorizontalDivider()
                Text("Workflow", style = MaterialTheme.typography.titleMedium)
                Text(
                    "One edge per line, using Agent names. Example: Planner -> Executor",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = workflowText,
                    onValueChange = { workflowText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )
            }

            item {
                Text("Loop limits", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft.maxTurns.toString(),
                        onValueChange = { draft = draft.copy(maxTurns = it.toIntOrNull()?.coerceIn(1, 32) ?: draft.maxTurns) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Max turns") },
                    )
                    OutlinedTextField(
                        value = draft.maxDepth.toString(),
                        onValueChange = { draft = draft.copy(maxDepth = it.toIntOrNull()?.coerceIn(1, 32) ?: draft.maxDepth) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Max depth") },
                    )
                }
            }

            item { Text("Agents", style = MaterialTheme.typography.titleMedium) }

            items(draft.agents, key = { it.id }) { agent ->
                AgentDefinitionEditor(
                    agent = agent,
                    providers = settings.providers,
                    onChange = { changed ->
                        draft = draft.copy(agents = draft.agents.map { if (it.id == changed.id) changed else it })
                        workflowText = workflowAsText(draft)
                    },
                    onDelete = {
                        draft = draft.copy(agents = draft.agents.filterNot { it.id == agent.id })
                        workflowText = workflowAsText(draft)
                    },
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val agent = AgentDefinition(
                            name = "Agent " + (draft.agents.size + 1),
                            role = AgentRole.CUSTOM,
                        )
                        draft = draft.copy(agents = draft.agents + agent)
                        workflowText = workflowAsText(draft)
                    }) { Text("Add Agent") }

                    TextButton(onClick = {
                        draft = draft.copy(workflow = defaultWorkflow(draft.agents))
                        workflowText = workflowAsText(draft)
                    }) { Text("Reset workflow") }

                    TextButton(onClick = {
                        saveConfig()
                        onDismiss()
                    }) { Text("Save and close") }
                }
            }
        }
    }
}

@Composable
private fun AgentDefinitionEditor(
    agent: AgentDefinition,
    providers: List<me.rerere.ai.provider.ProviderSetting>,
    onChange: (AgentDefinition) -> Unit,
    onDelete: () -> Unit,
) {
    var roleOpen by rememberSaveable(agent.id) { mutableStateOf(false) }
    var activationOpen by rememberSaveable(agent.id) { mutableStateOf(false) }
    var autonomyOpen by rememberSaveable(agent.id) { mutableStateOf(false) }
    var outputOpen by rememberSaveable(agent.id) { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(agent.name.ifBlank { "Unnamed Agent" }, style = MaterialTheme.typography.titleSmall)
                Switch(checked = agent.enabled, onCheckedChange = { onChange(agent.copy(enabled = it)) })
            }

            OutlinedTextField(
                value = agent.name,
                onValueChange = { onChange(agent.copy(name = it)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Name") },
            )

            TextButton(onClick = { roleOpen = true }) {
                Text("Role: " + agent.role.name)
            }
            DropdownMenu(expanded = roleOpen, onDismissRequest = { roleOpen = false }) {
                AgentRole.entries.forEach { role ->
                    TextButton(onClick = {
                        roleOpen = false
                        onChange(agent.copy(role = role))
                    }) { Text(role.name) }
                }
            }

            TextButton(onClick = { activationOpen = true }) {
                Text("Activation: " + agent.activationCondition.name)
            }
            TextButton(onClick = { autonomyOpen = true }) {
                Text("Agent autonomy: " + agent.autonomyLevel.name)
            }
            DropdownMenu(expanded = autonomyOpen, onDismissRequest = { autonomyOpen = false }) {
                AgentAutonomyLevel.entries.forEach { level ->
                    TextButton(onClick = {
                        autonomyOpen = false
                        onChange(agent.copy(autonomyLevel = level))
                    }) { Text(level.name) }
                }
            }

            TextButton(onClick = { outputOpen = true }) {
                Text("Output: " + agent.outputType.name)
            }
            DropdownMenu(expanded = outputOpen, onDismissRequest = { outputOpen = false }) {
                io.github.nastechresearch.nastech.data.agent.AgentOutputType.entries.forEach { type ->
                    TextButton(onClick = {
                        outputOpen = false
                        onChange(agent.copy(outputType = type))
                    }) { Text(type.name) }
                }
            }

            DropdownMenu(expanded = activationOpen, onDismissRequest = { activationOpen = false }) {
                AgentActivationCondition.entries.forEach { condition ->
                    TextButton(onClick = {
                        activationOpen = false
                        onChange(agent.copy(activationCondition = condition))
                    }) { Text(condition.name) }
                }
            }

            ModelSelector(
                modelId = agent.modelId?.let { runCatching { Uuid.parse(it) }.getOrNull() },
                providers = providers,
                type = me.rerere.ai.provider.ModelType.CHAT,
                allowClear = true,
                onSelect = { model ->
                    val selected = model.modelId.takeIf { it.isNotBlank() }?.let { model.id.toString() }
                    onChange(agent.copy(modelId = selected))
                },
            )

            OutlinedTextField(
                value = agent.allowedTools.joinToString(", "),
                onValueChange = { onChange(agent.copy(allowedTools = it.split(",").map(String::trim).filter(String::isNotBlank))) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Allowed tools") },
                supportingText = { Text("Use * to grant every currently available tool.") },
                minLines = 2,
            )

            OutlinedTextField(
                value = agent.deniedTools.joinToString(", "),
                onValueChange = { onChange(agent.copy(deniedTools = it.split(",").map(String::trim).filter(String::isNotBlank))) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Denied tools") },
                minLines = 2,
            )

            OutlinedTextField(
                value = agent.systemInstructions,
                onValueChange = { onChange(agent.copy(systemInstructions = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("System instructions") },
                minLines = 3,
                maxLines = 8,
            )

            TextButton(onClick = onDelete) { Text("Delete Agent") }
        }
    }
}

private fun workflowAsText(config: ConversationAgentConfig): String {
    val names = config.agents.associateBy({ it.id }, { it.name })
    val edges = config.workflow.ifEmpty { defaultWorkflow(config.agents) }
    return edges.joinToString("\n") {
        (names[it.fromAgentId] ?: it.fromAgentId) + " -> " + (names[it.toAgentId] ?: it.toAgentId)
    }
}
