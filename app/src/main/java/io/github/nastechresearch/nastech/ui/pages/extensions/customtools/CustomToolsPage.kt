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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.stringResource
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.nastechresearch.nastech.data.customtools.CustomToolDefinition
import io.github.nastechresearch.nastech.data.customtools.CustomToolKind
import io.github.nastechresearch.nastech.data.customtools.CustomToolRepository
import io.github.nastechresearch.nastech.R
import io.github.nastechresearch.nastech.ui.components.nav.BackButton

private const val MAX_IMPORTED_FILE_BYTES = 1024 * 1024

@OptIn(ExperimentalMaterial3Api::class)
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
    var inputSchemaDraft by remember { mutableStateOf("""{"type":"object","properties":{}}""") }
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
                        val displayName = context.contentResolver.query(
                            uri,
                            arrayOf(OpenableColumns.DISPLAY_NAME),
                            null,
                            null,
                            null,
                        )?.use { cursor ->
                            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                        } ?: "imported-script.js"
                        val text = context.contentResolver.openInputStream(uri)?.use { input ->
                            val out = ByteArrayOutputStream()
                            val buffer = ByteArray(8 * 1024)
                            var total = 0
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > MAX_IMPORTED_FILE_BYTES) {
                                    throw IllegalArgumentException(
                                        context.getString(R.string.custom_tools_file_too_large)
                                    )
                                }
                                out.write(buffer, 0, count)
                            }
                            out.toString(Charsets.UTF_8.name())
                        } ?: throw IllegalStateException(context.getString(R.string.custom_tools_file_read_error))
                        displayName to text
                    }
                }.onSuccess { (displayName, text) ->
                    if (importMode == "json") {
                        jsonDraft = text
                        message = context.getString(R.string.custom_tools_file_loaded_json)
                    } else {
                        val extension = displayName.substringAfterLast('.', "").lowercase()
                        val baseName = displayName.substringBeforeLast('.', displayName)
                        scriptName = baseName.replace(Regex("[^A-Za-z0-9_.-]+"), "_").trim('_', '.', '-')
                        scriptDescription = context.getString(R.string.custom_tools_imported_from, displayName)
                        scriptKind = if (extension == "py") CustomToolKind.PYTHON else CustomToolKind.JAVASCRIPT
                        scriptSource = text
                        message = context.getString(R.string.custom_tools_file_loaded_script)
                    }
                }.onFailure {
                    message = it.message ?: context.getString(R.string.custom_tools_file_read_error)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.custom_tools_title)) },
                navigationIcon = { BackButton() },
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.custom_tools_intro))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (importMode == "script") {
                    Button(onClick = { importMode = "script" }) { Text(stringResource(R.string.custom_tools_script_mode)) }
                    OutlinedButton(onClick = { importMode = "json" }) { Text(stringResource(R.string.custom_tools_json_mode)) }
                } else {
                    OutlinedButton(onClick = { importMode = "script" }) { Text(stringResource(R.string.custom_tools_script_mode)) }
                    Button(onClick = { importMode = "json" }) { Text(stringResource(R.string.custom_tools_json_mode)) }
                }
            }

            if (importMode == "script") {
                OutlinedTextField(
                    value = scriptName,
                    onValueChange = { scriptName = it },
                    label = { Text(stringResource(R.string.custom_tools_name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = scriptDescription,
                    onValueChange = { scriptDescription = it },
                    label = { Text(stringResource(R.string.custom_tools_description_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 3,
                )
                OutlinedTextField(
                    value = inputSchemaDraft,
                    onValueChange = { inputSchemaDraft = it },
                    label = { Text(stringResource(R.string.custom_tools_schema_label)) },
                    supportingText = { Text(stringResource(R.string.custom_tools_schema_support)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (scriptKind == CustomToolKind.JAVASCRIPT) {
                        Button(onClick = { scriptKind = CustomToolKind.JAVASCRIPT }) { Text(stringResource(R.string.custom_tools_javascript)) }
                        OutlinedButton(onClick = { scriptKind = CustomToolKind.PYTHON }) { Text(stringResource(R.string.custom_tools_python)) }
                    } else {
                        OutlinedButton(onClick = { scriptKind = CustomToolKind.JAVASCRIPT }) { Text(stringResource(R.string.custom_tools_javascript)) }
                        Button(onClick = { scriptKind = CustomToolKind.PYTHON }) { Text(stringResource(R.string.custom_tools_python)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                        Text(stringResource(R.string.custom_tools_choose_script))
                    }
                }
                OutlinedTextField(
                    value = scriptSource,
                    onValueChange = { scriptSource = it },
                    label = { Text(stringResource(R.string.custom_tools_paste_script)) },
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    minLines = 5,
                    maxLines = 12,
                )
                Text(
                    if (scriptKind == CustomToolKind.PYTHON)
                        stringResource(R.string.custom_tools_python_help)
                    else
                        stringResource(R.string.custom_tools_javascript_help)
                )
                Button(
                    onClick = {
                        val normalizedName = scriptName.trim()
                            .replace(Regex("[^A-Za-z0-9_.-]+"), "_")
                            .trim('_', '.', '-')
                            .let { candidate ->
                                if (candidate.firstOrNull()?.isLetter() == true) candidate else "tool_" + candidate
                            }
                            .take(61)
                        val normalizedId = normalizedName.lowercase()
                            .replace(Regex("[^a-z0-9._-]+"), "-")
                            .trim('-', '.', '_')
                        val parsedSchema = runCatching {
                            Json.parseToJsonElement(inputSchemaDraft) as? JsonObject
                                ?: throw IllegalArgumentException(context.getString(R.string.custom_tools_schema_json_error))
                        }.getOrElse {
                            message = it.message ?: context.getString(R.string.custom_tools_schema_invalid_error)
                            null
                        }
                        if (parsedSchema != null) {
                            val kindName = if (scriptKind == CustomToolKind.JAVASCRIPT) "javascript" else "python"
                            val definitionJson = buildJsonObject {
                                put("id", normalizedId)
                                put("name", normalizedName)
                                put("description", scriptDescription.trim())
                                put("kind", kindName)
                                put("inputSchema", parsedSchema)
                                put("source", scriptSource)
                                put("enabled", false)
                            }.toString()
                            repository.importJson(definitionJson).fold(
                                onSuccess = {
                                    message = context.getString(R.string.custom_tools_imported_disabled_status, it.name)
                                    scriptSource = ""
                                    scriptName = ""
                                    scriptDescription = ""
                                },
                                onFailure = { message = it.message ?: "Import failed." },
                            )
                        }
                    },
                    enabled = scriptName.isNotBlank() && scriptDescription.isNotBlank() && scriptSource.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.custom_tools_import_script))
                }
            } else {
                OutlinedTextField(
                    value = jsonDraft,
                    onValueChange = { jsonDraft = it },
                    label = { Text(stringResource(R.string.custom_tools_paste_json)) },
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    minLines = 6,
                    maxLines = 14,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { filePicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                        Text(stringResource(R.string.custom_tools_choose_json))
                    }
                    Button(
                        onClick = {
                            repository.importJson(jsonDraft).fold(
                                onSuccess = {
                                    message = context.getString(R.string.custom_tools_imported_disabled_status, it.name)
                                    jsonDraft = ""
                                },
                                onFailure = { message = it.message ?: "Import failed." },
                            )
                        },
                        enabled = jsonDraft.isNotBlank(),
                    ) { Text(stringResource(R.string.custom_tools_import_json)) }
                }
            }

            message?.let { Text(it) }
            Text(stringResource(R.string.custom_tools_installed_count, tools.size))
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
                            Text(stringResource(R.string.custom_tools_status, stringResource(when (tool.kind) { CustomToolKind.JAVASCRIPT -> R.string.custom_tools_javascript; CustomToolKind.PYTHON -> R.string.custom_tools_python; CustomToolKind.ACTION_SEQUENCE -> R.string.custom_tools_action_sequence; CustomToolKind.MCP -> R.string.custom_tools_mcp }), stringResource(if (tool.enabled) R.string.custom_tools_enabled else R.string.custom_tools_disabled)))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { reviewedTool = tool }) { Text(stringResource(R.string.custom_tools_review)) }
                                TextButton(onClick = { deleteTarget = tool }) { Text(stringResource(R.string.custom_tools_delete)) }
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
            title = { Text(stringResource(R.string.custom_tools_review_title, tool.name)) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(tool.description)
                    Text(stringResource(R.string.custom_tools_id, tool.id))
                    Text(stringResource(R.string.custom_tools_callable_name, tool.name))
                    Text(stringResource(R.string.custom_tools_type, stringResource(when (tool.kind) { CustomToolKind.JAVASCRIPT -> R.string.custom_tools_javascript; CustomToolKind.PYTHON -> R.string.custom_tools_python; CustomToolKind.ACTION_SEQUENCE -> R.string.custom_tools_action_sequence; CustomToolKind.MCP -> R.string.custom_tools_mcp })))
                    Text(stringResource(R.string.custom_tools_input_schema, tool.inputSchema.toString()))
                    when (tool.kind) {
                        CustomToolKind.JAVASCRIPT -> {
                            Text(stringResource(R.string.custom_tools_js_review))
                            Text(tool.source)
                        }
                        CustomToolKind.PYTHON -> {
                            Text(stringResource(R.string.custom_tools_python_review))
                            Text(tool.source)
                        }
                        CustomToolKind.ACTION_SEQUENCE -> {
                            Text(stringResource(R.string.custom_tools_actions_review))
                            tool.actions.forEachIndexed { index, action ->
                                Text("${index + 1}. ${action.tool}(${action.args})")
                            }
                        }
                        CustomToolKind.MCP -> {
                            Text(stringResource(R.string.custom_tools_mcp_server, tool.mcpServerId))
                            Text(stringResource(R.string.custom_tools_mcp_remote, tool.remoteToolName))
                        }
                    }
                    Text(stringResource(R.string.custom_tools_approval_note))
                }
            },
            confirmButton = {
                Button(onClick = {
                    repository.setEnabled(tool.id, !tool.enabled).fold(
                        onSuccess = {
                            message = context.getString(if (it.enabled) R.string.custom_tools_import_enabled else R.string.custom_tools_import_disabled, it.name)
                        },
                        onFailure = { message = it.message ?: "Could not change tool state." },
                    )
                    reviewedTool = null
                }) { Text(stringResource(if (tool.enabled) R.string.custom_tools_disable_button else R.string.custom_tools_enable_reviewed)) }
            },
            dismissButton = {
                TextButton(onClick = { reviewedTool = null }) { Text(stringResource(R.string.custom_tools_cancel)) }
            },
        )
    }

    deleteTarget?.let { tool ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.custom_tools_delete_title, tool.name)) },
            text = { Text(stringResource(R.string.custom_tools_delete_description)) },
            confirmButton = {
                Button(onClick = {
                    repository.remove(tool.id).fold(
                        onSuccess = { message = context.getString(R.string.custom_tools_deleted_status, tool.name) },
                        onFailure = { message = it.message ?: "Delete failed." },
                    )
                    deleteTarget = null
                }) { Text(stringResource(R.string.custom_tools_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.custom_tools_cancel)) }
            },
        )
    }
}
