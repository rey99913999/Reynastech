package io.github.nastechresearch.nastech.ui.pages.assistant.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nastechresearch.nastech.R
import io.github.nastechresearch.nastech.data.ai.tools.AssistantToolDefaultPolicy
import io.github.nastechresearch.nastech.data.ai.tools.AssistantToolPermissionPolicy
import io.github.nastechresearch.nastech.data.ai.tools.ToolApprovalDefaults
import io.github.nastechresearch.nastech.data.execution.ToolCategory
import io.github.nastechresearch.nastech.data.execution.ToolMetadata
import io.github.nastechresearch.nastech.data.execution.ToolRiskLevel
import io.github.nastechresearch.nastech.data.execution.ToolSourceKind
import io.github.nastechresearch.nastech.ui.components.nav.BackButton
import io.github.nastechresearch.nastech.ui.components.ui.CardGroup
import io.github.nastechresearch.nastech.ui.context.LocalNavController
import io.github.nastechresearch.nastech.Screen
import io.github.nastechresearch.nastech.ui.theme.CustomColors
import io.github.nastechresearch.nastech.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantToolsPage(id: String) {
    val nav = LocalNavController.current
    val vm: AssistantToolPermissionsVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Tools") },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Tool access is separate from tool permissions. Turning a tool off does not erase its permission policy.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                CardGroup {
                    item(
                        onClick = { nav.navigate(Screen.AssistantEnabledTools(id)) },
                        headlineContent = { Text("Enabled Tools") },
                        supportingContent = {
                            Text(
                                "Choose which registered capabilities this Assistant may see and use." +
                                    (assistant?.let { " Disabled: " + it.disabledToolIds.size } ?: "")
                            )
                        },
                    )
                    item(
                        onClick = { nav.navigate(Screen.AssistantToolPermissions(id)) },
                        headlineContent = { Text("Tool Permissions") },
                        supportingContent = { Text("Manage Ask, Deny and Always Allow for every registry tool.") },
                    )
                }
            }
        }
    }
}

@Composable
fun AssistantEnabledToolsPage(id: String) {
    val vm: AssistantToolPermissionsVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val tools by vm.filteredMetadata.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Enabled Tools") },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = vm::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                label = { Text("Search tools") },
                singleLine = true,
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(tools, key = { it.identity.stableId }) { meta ->
                    val enabled = assistant?.disabledToolIds?.contains(meta.identity.stableId) != true
                    ListItem(
                        headlineContent = {
                            Text(meta.modelName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(
                                meta.description +
                                    " • " + meta.category.displayName + " • " + meta.risk.name,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = enabled,
                                onCheckedChange = { vm.setToolEnabled(meta, it) },
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun AssistantToolPermissionsPage(id: String) {
    val vm: AssistantToolPermissionsVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val tools by vm.filteredMetadata.collectAsStateWithLifecycle()
    val legacyCount by vm.legacyResetCount.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val selectedCategory by vm.selectedCategory.collectAsStateWithLifecycle()
    val selectedRisk by vm.selectedRisk.collectAsStateWithLifecycle()
    val selectedSource by vm.selectedSource.collectAsStateWithLifecycle()
    val selectedPolicy by vm.selectedPolicy.collectAsStateWithLifecycle()
    var defaultMenu by remember { mutableStateOf(false) }
    var openPolicyFor by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Tool Permissions") },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (legacyCount > 0) {
                item {
                    CardGroup {
                        item(
                            headlineContent = { Text("Legacy permissions reset") },
                            supportingContent = {
                                Text(
                                    legacyCount.toString() +
                                        " old global approval(s) were reset to Ask. They were not copied to every Assistant."
                                )
                            },
                            trailingContent = {
                                TextButton(onClick = vm::dismissLegacyNotice) {
                                    Text("Dismiss")
                                }
                            },
                        )
                    }
                }
            }

            item {
                CardGroup {
                    item(
                        headlineContent = { Text("Default tool policy") },
                        supportingContent = {
                            Text(
                                when (assistant?.toolDefaultPolicy) {
                                    AssistantToolDefaultPolicy.ASK -> "Ask for new tools"
                                    AssistantToolDefaultPolicy.ALLOW_LOW_RISK -> "Allow low-risk tools"
                                    AssistantToolDefaultPolicy.DENY_NEW -> "Deny new tools"
                                    null -> "Loading…"
                                }
                            )
                        },
                        onClick = { defaultMenu = true },
                    )
                }
                DropdownMenu(
                    expanded = defaultMenu,
                    onDismissRequest = { defaultMenu = false },
                ) {
                    AssistantToolDefaultPolicy.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(optionLabel(option)) },
                            onClick = {
                                vm.setDefaultPolicy(option)
                                defaultMenu = false
                            },
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = vm::setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search by tool, capability or description") },
                    singleLine = true,
                )
            }

            item {
                FilterRow(
                    categories = ToolCategory.entries,
                    selected = selectedCategory,
                    label = { it.displayName },
                    onSelected = vm::setCategory,
                )
            }
            item {
                FilterRow(
                    categories = ToolRiskLevel.entries,
                    selected = selectedRisk,
                    label = { it.name },
                    onSelected = vm::setRisk,
                )
            }
            item {
                FilterRow(
                    categories = ToolSourceKind.entries,
                    selected = selectedSource,
                    label = { it.name },
                    onSelected = vm::setSource,
                )
            }
            item {
                FilterRow(
                    categories = AssistantToolPermissionPolicy.entries,
                    selected = selectedPolicy,
                    label = { it.name.replace('_', ' ') },
                    onSelected = vm::setPolicy,
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::resetAll) { Text("Reset all") }
                    if (selectedCategory != null) {
                        TextButton(onClick = { vm.resetCategory(selectedCategory!!) }) {
                            Text("Reset " + selectedCategory!!.displayName)
                        }
                    }
                }
            }

            items(tools, key = { it.identity.stableId }) { meta ->
                PermissionRow(
                    meta = meta,
                    enabled = assistant?.disabledToolIds?.contains(meta.identity.stableId) != true,
                    override = vm.overrideFor(meta),
                    effectivePolicy = vm.effectivePolicyFor(meta),
                    policyMenuOpen = openPolicyFor == meta.identity.stableId,
                    onOpenPolicy = { openPolicyFor = meta.identity.stableId },
                    onClosePolicy = { openPolicyFor = null },
                    onPolicy = {
                        vm.setToolPolicy(meta, it)
                        openPolicyFor = null
                    },
                    onReset = {
                        vm.resetTool(meta)
                        openPolicyFor = null
                    },
                )
            }
        }
    }
}

@Composable
private fun <T> FilterRow(
    categories: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelected: (T?) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelected(null) },
                label = { Text("All") },
            )
        }
        items(categories.size) { index ->
            val item = categories[index]
            FilterChip(
                selected = selected == item,
                onClick = { onSelected(if (selected == item) null else item) },
                label = { Text(label(item)) },
            )
        }
    }
}

@Composable
private fun PermissionRow(
    meta: ToolMetadata,
    enabled: Boolean,
    override: AssistantToolPermissionPolicy?,
    effectivePolicy: AssistantToolPermissionPolicy,
    policyMenuOpen: Boolean,
    onOpenPolicy: () -> Unit,
    onClosePolicy: () -> Unit,
    onPolicy: (AssistantToolPermissionPolicy) -> Unit,
    onReset: () -> Unit,
) {
    val alwaysAllowed = ToolApprovalDefaults.allowsAlwaysAllow(meta.modelName)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CardGroup {
            item(
                headlineContent = {
                    Text(meta.modelName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    Text(
                        buildString {
                            append(meta.description)
                            append("\nSource: ")
                            append(meta.identity.source.name)
                            if (meta.identity.sourceId.isNotBlank()) {
                                append(" / ")
                                append(meta.identity.sourceId)
                            }
                            append("\nRisk: ")
                            append(meta.risk.name)
                            append(" • Category: ")
                            append(meta.category.displayName)
                            if (meta.sideEffects.isNotEmpty()) {
                                append("\nSide effects: ")
                                append(meta.sideEffects.joinToString(", "))
                            }
                            if (!enabled) append("\nDisabled for this Assistant")
                            if (override != null) append("\nExplicit override: " + override.name)
                            else append("\nEffective default: " + effectivePolicy.name)
                        },
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    Column {
                        TextButton(onClick = onOpenPolicy) {
                            Text(effectivePolicy.name.replace('_', ' '))
                        }
                        if (override != null) {
                            TextButton(onClick = onReset) { Text("Use default") }
                        }
                    }
                },
            )
        }
        DropdownMenu(
            expanded = policyMenuOpen,
            onDismissRequest = onClosePolicy,
        ) {
            if (alwaysAllowed) {
                DropdownMenuItem(
                    text = { Text("Always allow") },
                    onClick = { onPolicy(AssistantToolPermissionPolicy.ALWAYS_ALLOW) },
                )
            }
            DropdownMenuItem(
                text = { Text("Ask") },
                onClick = { onPolicy(AssistantToolPermissionPolicy.ASK) },
            )
            DropdownMenuItem(
                text = { Text("Deny") },
                onClick = { onPolicy(AssistantToolPermissionPolicy.DENY) },
            )
        }
    }
}

private fun optionLabel(policy: AssistantToolDefaultPolicy): String = when (policy) {
    AssistantToolDefaultPolicy.ASK -> "Ask for new tools"
    AssistantToolDefaultPolicy.ALLOW_LOW_RISK -> "Allow low-risk tools"
    AssistantToolDefaultPolicy.DENY_NEW -> "Deny new tools"
}
