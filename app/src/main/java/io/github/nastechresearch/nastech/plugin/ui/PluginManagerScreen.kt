package io.github.nastechresearch.nastech.plugin.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nastechresearch.nastech.ui.components.nav.BackButton
import io.github.nastechresearch.nastech.ui.context.LocalNavController
import io.github.nastechresearch.nastech.ui.theme.CustomColors
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Settings03
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginManagerScreen(vm: PluginManagerViewModel = koinViewModel()) {
    val plugins by vm.plugins.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val nav = LocalNavController.current
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var bindingPluginId by remember { mutableStateOf<String?>(null) }
    var agentPluginId by remember { mutableStateOf<String?>(null) }
    var agentConversationId by remember { mutableStateOf<String?>(null) }
    var configPluginId by remember { mutableStateOf<String?>(null) }
    var configText by remember { mutableStateOf("{}") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        vm.installUri(uri) { message = it }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Plugin Manager") },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
                scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Install plugin package", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("URL / GitHub release URL") },
                            singleLine = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    vm.installUrl(url) { message = it }
                                    url = ""
                                },
                                enabled = url.isNotBlank(),
                            ) { Text("Install URL") }
                            TextButton(
                                onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) },
                            ) { Text("Local package") }
                        }
                        Text(
                            "Plugins are installed disabled. Skills, MCP connections, tools, and permissions activate only through their explicit bindings.",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (plugins.isEmpty()) {
                item { Text("No plugins installed.") }
            }

            items(plugins, key = { it.manifest.normalizedId() }) { record ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(HugeIcons.Package, null)
                            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(record.manifest.name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                                Text(
                                    record.manifest.id + " • v" + record.manifest.version,
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                )
                            }
                            Checkbox(
                                checked = record.enabled,
                                onCheckedChange = { enabled ->
                                    vm.setEnabled(record.manifest.normalizedId(), enabled) { message = it }
                                },
                            )
                        }
                        Text("Developer: " + record.manifest.developer.ifBlank { "Unknown" })
                        Text("Source: " + record.sourceLabel)
                        Text("Trust: " + record.manifest.trust.name)
                        Text("Status: " + record.status.name)
                        record.manifest.mcpServers
                            .filter { it.auth.type.equals("mcp_oauth", ignoreCase = true) }
                            .forEach { server ->
                                Text(
                                    "Authentication: " +
                                        server.auth.provider.ifBlank { "MCP OAuth" } +
                                        if (server.auth.account.isBlank()) "" else " • " + server.auth.account,
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(
                                        onClick = {
                                            vm.authorize(record.manifest.normalizedId(), context) { message = it }
                                        },
                                        enabled = record.enabled,
                                    ) { Text("Authorize") }
                                    TextButton(
                                        onClick = {
                                            vm.cancelAuthorization(record.manifest.normalizedId()) { message = it }
                                        },
                                    ) { Text("Cancel") }
                                }
                            }
                        if (record.manifest.description.isNotBlank()) {
                            Text(record.manifest.description)
                        }
                        Text(
                            "Used by: " + record.conversationIds.size + " conversation(s) • " +
                                record.manifest.tools.size + " tool(s) • " +
                                record.manifest.skills.size + " skill(s)",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                        Divider()
                        Text("Permissions", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                        record.manifest.permissions.sorted().forEach { permission ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = permission in record.grantedPermissions,
                                    onCheckedChange = { granted ->
                                        vm.setPermission(
                                            record.manifest.normalizedId(),
                                            permission,
                                            granted,
                                        ) { message = it }
                                    },
                                )
                                Text(permission)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { bindingPluginId = record.manifest.normalizedId() },
                            ) { Text("Conversations") }
                            TextButton(
                                onClick = {
                                    configPluginId = record.manifest.normalizedId()
                                    configText = vm.configText(record.manifest.normalizedId())
                                },
                            ) { Icon(HugeIcons.Settings03, null); Text("Configure") }
                            if (record.previousVersions.isNotEmpty()) {
                                TextButton(
                                    onClick = {
                                        vm.rollback(record.manifest.normalizedId()) { message = it }
                                    },
                                ) { Text("Rollback") }
                            }
                            TextButton(
                                onClick = {
                                    vm.uninstall(record.manifest.normalizedId()) { message = it }
                                },
                            ) { Icon(HugeIcons.Delete02, null); Text("Uninstall") }
                        }
                    }
                }
            }
        }
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            text = { Text(text) },
        )
    }

    val bindingPlugin = bindingPluginId
    if (bindingPlugin != null) {
        val record = plugins.firstOrNull { it.manifest.normalizedId() == bindingPlugin }
        AlertDialog(
            onDismissRequest = { bindingPluginId = null },
            confirmButton = { TextButton(onClick = { bindingPluginId = null }) { Text("Done") } },
            title = { Text("Conversation bindings") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(conversations, key = { it.id }) { conversation ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = record?.conversationIds?.contains(conversation.id) == true,
                                onCheckedChange = { checked ->
                                    vm.bindConversation(bindingPlugin, conversation.id, checked) { message = it }
                                    if (checked) {
                                        agentConversationId = conversation.id
                                        agentPluginId = bindingPlugin
                                    }
                                },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(conversation.title.ifBlank { "Untitled conversation" })
                                Text(conversation.id, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                            }
                            if (record?.conversationIds?.contains(conversation.id) == true) {
                                TextButton(
                                    onClick = {
                                        agentConversationId = conversation.id
                                        agentPluginId = bindingPlugin
                                    },
                                ) { Text("Agents") }
                            }
                        }
                    }
                }
            },
        )
    }

    val agentPlugin = agentPluginId
    val agentConversation = agentConversationId
    if (agentPlugin != null && agentConversation != null) {
        var agents by remember(agentPlugin, agentConversation) { mutableStateOf(emptyList<Pair<String, String>>()) }
        vm.agentsForConversation(agentConversation) { agents = it }
        val record = plugins.firstOrNull { it.manifest.normalizedId() == agentPlugin }
        AlertDialog(
            onDismissRequest = { agentPluginId = null; agentConversationId = null },
            confirmButton = {
                TextButton(onClick = { agentPluginId = null; agentConversationId = null }) { Text("Done") }
            },
            title = { Text("Agent permissions") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        Text(
                            "A plugin can be bound to a conversation while remaining blocked for selected Agents.",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                    }
                    items(agents, key = { it.first }) { agent ->
                        val allowed = record?.agentPluginBindings[agentConversation + "/" + agent.first]
                            .orEmpty()
                            .contains(record?.manifest?.id)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = allowed,
                                onCheckedChange = { checked ->
                                    vm.bindAgent(
                                        agentPlugin,
                                        agentConversation,
                                        agent.first,
                                        checked,
                                    ) { message = it }
                                },
                            )
                            Text(agent.second)
                        }
                    }
                }
            },
        )
    }

    val configId = configPluginId
    if (configId != null) {
        AlertDialog(
            onDismissRequest = { configPluginId = null },
            title = { Text("Plugin configuration") },
            text = {
                OutlinedTextField(
                    value = configText,
                    onValueChange = { configText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 8,
                    maxLines = 14,
                    label = { Text("JSON") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.saveConfig(configId, configText) { message = it }
                        configPluginId = null
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { configPluginId = null }) { Text("Cancel") }
            },
        )
    }
}
