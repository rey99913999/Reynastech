package io.github.nastechresearch.nastech.ui.pages.extensions.customtools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.nastechresearch.nastech.data.customtools.CustomToolDefinition
import io.github.nastechresearch.nastech.data.customtools.CustomToolKind
import io.github.nastechresearch.nastech.data.customtools.CustomToolRepository
import io.github.nastechresearch.nastech.ui.components.nav.BackButton

@Composable
fun CustomToolsPage() {
    val context = LocalContext.current
    val repository = remember(context) { CustomToolRepository(context) }
    val tools by repository.tools.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var importMode by remember { mutableStateOf("script") }
    var jsonDraft by remember { mutableStateOf("") }
    var scriptName by remember { mutableStateOf("") }
    var scriptDescription by remember { mutableStateOf("") }
    var scriptKind by remember { mutableStateOf(CustomToolKind.JAVASCRIPT) }
    var scriptSource by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var reviewedTool by remember { mutableStateOf<CustomToolDefinition?>(null) }
    var deleteTarget by remember { mutableStateOf<CustomToolDefinition?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                            ?: throw IllegalStateException("Could not read the selected file.")
                    }
                }.onSuccess {
                    jsonDraft = it
                    importMode = "json"
                    message = "File loaded. Review the JSON, then import it."
                }.onFailure {
                    message = it.message ?: "Could not read the selected file."
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Custom tools") },
                navigationIcon = { BackButton() },
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Import tools generated in your external AI app. Nothing is activated automatically.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (importMode == "script") {
                    Button(onClick = { importMode = "script" }) { Text("JavaScript / Python") }
                    OutlinedButton(onClick = { importMode = "json" }) { Text("JSON definition") }
                } else {
                    OutlinedButton(onClick = { importMode = "script" }) { Text("JavaScript / Python") }
                    Button(onClick = { importMode = "json" }) { Text("JSON definition") }
                }
            }

            if (importMode == "script") {
                OutlinedTextField(
                    value = scriptName,
                    onValueChange = { scriptName = it },
                    label = { Text("Tool name (example: format_text)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = scriptDescription,
                    onValueChange = { scriptDescription = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 3,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (scriptKind == CustomToolKind.JAVASCRIPT) {
                        Button(onClick = { scriptKind = CustomToolKind.JAVASCRIPT }) { Text("JavaScript") }
                        OutlinedButton(onClick = { scriptKind = CustomToolKind.PYTHON }) { Text("Python / Termux") }
                    } else {
                        OutlinedButton(onClick = { scriptKind = CustomToolKind.JAVASCRIPT }) { Text("JavaScript") }
                        Button(onClick = { scriptKind = CustomToolKind.PYTHON }) { Text("Python / Termux") }
                    }
                }
                OutlinedTextField(
                    value = scriptSource,
                    onValueChange = { scriptSource = it },
                    label = { Text("Paste script source") },
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    minLines = 5,
                    maxLines = 12,
                )
                Text(
                    if (scriptKind == CustomToolKind.PYTHON)
                        "Python runs through your existing Termux integration and may access the Termux environment."
                    else
                        "JavaScript runs in the local engine without DOM or Node.js APIs."
                )
                Button(
                    onClick = {
                        val normalizedId = scriptName.trim().lowercase()
                            .replace(Regex("[^a-z0-9._-]+"), "-")
                            .trim('-', '.', '_')
                            .take(64)
                        val kindName = if (scriptKind == CustomToolKind.JAVASCRIPT) "javascript" else "python"
                        val inputSchema = buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject { })
                        }
                        val definitionJson = buildJsonObject {
                            put("id", normalizedId)
                            put("name", scriptName.trim())
                            put("description", scriptDescription.trim())
                            put("kind", kindName)
                            put("inputSchema", inputSchema)
                            put("source", scriptSource)
                            put("enabled", false)
                        }.toString()
                        repository.importJson(definitionJson).fold(
                            onSuccess = {
                                message = "Imported '${it.name}' disabled. Review it below before enabling."
                                scriptSource = ""
                                scriptName = ""
                                scriptDescription = ""
                            },
                            onFailure = { message = it.message ?: "Import failed." },
                        )
                    },
                    enabled = scriptName.isNotBlank() && scriptDescription.isNotBlank() && scriptSource.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Import script (disabled)")
                }
            } else {
                OutlinedTextField(
                    value = jsonDraft,
                    onValueChange = { jsonDraft = it },
                    label = { Text("Paste JSON tool definition") },
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    minLines = 6,
                    maxLines = 14,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { filePicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                        Text("Choose JSON file")
                    }
                    Button(
                        onClick = {
                            repository.importJson(jsonDraft).fold(
                                onSuccess = {
                                    message = "Imported '${it.name}' disabled. Review it below before enabling."
                                    jsonDraft = ""
                                },
                                onFailure = { message = it.message ?: "Import failed." },
                            )
                        },
                        enabled = jsonDraft.isNotBlank(),
                    ) { Text("Import JSON (disabled)") }
                }
            }

            message?.let { Text(it) }
            Text("Installed tools (${tools.size})")
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tools.forEach { tool ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(tool.name)
                            Text(tool.description)
                            Text("Type: ${tool.kind.name.lowercase()} · ${if (tool.enabled) "Enabled" else "Disabled"}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { reviewedTool = tool }) { Text("Review") }
                                TextButton(onClick = { deleteTarget = tool }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }

    reviewedTool?.let { tool ->
        AlertDialog(
            onDismissRequest = { reviewedTool = null },
            title = { Text("Review: ${tool.name}") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(tool.description)
                    Text("Tool id: ${tool.id}")
                    Text("Type: ${tool.kind.name.lowercase()}")
                    Text("Input schema: ${tool.inputSchema}")
                    when (tool.kind) {
                        CustomToolKind.JAVASCRIPT -> {
                            Text("Runs in the local JavaScript engine. Review the complete source:")
                            Text(tool.source)
                        }
                        CustomToolKind.PYTHON -> {
                            Text("Runs in Termux and can access its installed packages and files. Review the complete source:")
                            Text(tool.source)
                        }
                        CustomToolKind.ACTION_SEQUENCE -> {
                            Text("Calls these existing Nastech tools in sequence:")
                            tool.actions.forEachIndexed { index, action ->
                                Text("${index + 1}. ${action.tool}(${action.args})")
                            }
                        }
                        CustomToolKind.MCP -> {
                            Text("Calls the configured MCP server id: ${tool.mcpServerId}")
                            Text("Remote tool: ${tool.remoteToolName}")
                        }
                    }
                    Text("Custom scripts and action sequences require approval when called.")
                }
            },
            confirmButton = {
                Button(onClick = {
                    repository.setEnabled(tool.id, !tool.enabled).fold(
                        onSuccess = {
                            message = if (it.enabled) "Enabled '${it.name}'." else "Disabled '${it.name}'."
                        },
                        onFailure = { message = it.message ?: "Could not change tool state." },
                    )
                    reviewedTool = null
                }) { Text(if (tool.enabled) "Disable" else "I reviewed this — enable") }
            },
            dismissButton = {
                TextButton(onClick = { reviewedTool = null }) { Text("Cancel") }
            },
        )
    }

    deleteTarget?.let { tool ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${tool.name}?") },
            text = { Text("This removes the saved definition from Nastech.") },
            confirmButton = {
                Button(onClick = {
                    repository.remove(tool.id).fold(
                        onSuccess = { message = "Deleted '${tool.name}'." },
                        onFailure = { message = it.message ?: "Delete failed." },
                    )
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}
