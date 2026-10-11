package io.github.nastechresearch.nastech.data.customtools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid

/**
 * Portable description of a user-authored tool.
 * Imported definitions stay disabled until the user reviews and enables them.
 */
@Serializable
data class CustomToolDefinition(
    val id: String,
    val name: String,
    val description: String,
    val kind: CustomToolKind,
    val inputSchema: JsonObject = JsonObject(emptyMap()),
    val actions: List<CustomToolAction> = emptyList(),
    val source: String = "",
    val mcpServerId: String = "",
    val remoteToolName: String = "",
    val enabled: Boolean = false,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
)

@Serializable
enum class CustomToolKind {
    @SerialName("actions") ACTION_SEQUENCE,
    @SerialName("javascript") JAVASCRIPT,
    @SerialName("python") PYTHON,
    @SerialName("mcp") MCP,
}

@Serializable
data class CustomToolAction(
    val tool: String,
    val args: JsonObject = JsonObject(emptyMap()),
)

object CustomToolDefinitionValidator {
    const val MAX_SOURCE_CHARS = 128 * 1024
    const val MAX_DESCRIPTION_CHARS = 2_000
    const val MAX_ACTIONS = 32

    private val idPattern = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    private val toolNamePattern = Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,60}")

    /** Returns a user-readable validation error, or null when structurally valid. */
    fun validate(definition: CustomToolDefinition): String? {
        if (!idPattern.matches(definition.id)) {
            return "Tool id must be 1–64 lowercase letters, digits, dots, underscores, or hyphens."
        }
        if (!toolNamePattern.matches(definition.name)) {
            return "Tool name must start with a letter and contain only letters, digits, dots, underscores, or hyphens (maximum 61 characters)."
        }
        if (definition.description.isBlank() || definition.description.length > MAX_DESCRIPTION_CHARS) {
            return "Description must be between 1 and $MAX_DESCRIPTION_CHARS characters."
        }
        val schemaType = (definition.inputSchema["type"] as? JsonPrimitive)?.content
        if (schemaType != "object") return "inputSchema.type must be 'object'."

        return when (definition.kind) {
            CustomToolKind.ACTION_SEQUENCE -> when {
                definition.actions.isEmpty() -> "Action-sequence tools must contain at least one action."
                definition.actions.size > MAX_ACTIONS -> "A tool can contain at most $MAX_ACTIONS actions."
                definition.actions.any { !toolNamePattern.matches(it.tool) } ->
                    "Every action must reference a valid Nastech tool name."
                definition.actions.any { it.tool.startsWith("ct_") } ->
                    "Action-sequence tools cannot nest other custom tools; this prevents hidden approval chains."
                definition.source.isNotEmpty() -> "Action-sequence tools cannot include script source."
                else -> null
            }
            CustomToolKind.JAVASCRIPT, CustomToolKind.PYTHON -> when {
                definition.source.isBlank() -> "Script source is required."
                definition.source.length > MAX_SOURCE_CHARS ->
                    "Script source exceeds the $MAX_SOURCE_CHARS-character limit."
                definition.actions.isNotEmpty() -> "Script tools cannot also contain action steps."
                else -> null
            }
            CustomToolKind.MCP -> when {
                runCatching { Uuid.parse(definition.mcpServerId) }.getOrNull() == null ->
                    "A valid id for an already configured MCP server is required."
                !toolNamePattern.matches(definition.remoteToolName) -> "A valid remote MCP tool name is required."
                definition.source.isNotEmpty() || definition.actions.isNotEmpty() ->
                    "MCP wrappers cannot embed source code or action steps."
                else -> null
            }
        }
    }
}


/**
 * Lightweight source heuristics for the review screen. A match is a prompt for human review,
 * not a malware verdict; absence of matches does not establish safety.
 */
object CustomToolSourceInspector {
    private data class PatternHint(val regex: Regex, val label: String)

    private val javascriptHints = listOf(
        PatternHint(Regex("""\\beval\\s*\\(""", RegexOption.IGNORE_CASE), "eval(...)"),
        PatternHint(Regex("""\\bnew\\s+Function\\b""", RegexOption.IGNORE_CASE), "new Function(...)"),
        PatternHint(Regex("""\\bfetch\\s*\\(""", RegexOption.IGNORE_CASE), "fetch(...) network requests"),
        PatternHint(Regex("""XMLHttpRequest|WebSocket""", RegexOption.IGNORE_CASE), "XHR / WebSocket network access"),
        PatternHint(Regex("""\\b(globalThis|process|require)\\b"""), "global/process/module access"),
        PatternHint(Regex("""\\bwhile\\s*\\(\\s*true\\s*\\)|for\\s*\\(\\s*;\\s*;\\s*\\)"""), "possible infinite loop"),
    )

    private val pythonHints = listOf(
        PatternHint(Regex("""\\bos\\s*\\.\\s*system\\s*\\(""", RegexOption.IGNORE_CASE), "os.system(...) shell execution"),
        PatternHint(Regex("""\\bsubprocess\\b""", RegexOption.IGNORE_CASE), "subprocess command execution"),
        PatternHint(Regex("""\\b(socket|requests|urllib)\\b""", RegexOption.IGNORE_CASE), "network access libraries"),
        PatternHint(Regex("""\\b(eval|exec)\\s*\\(""", RegexOption.IGNORE_CASE), "eval(...) / exec(...) dynamic code"),
        PatternHint(Regex("""\\bopen\\s*\\(""", RegexOption.IGNORE_CASE), "open(...) filesystem access"),
        PatternHint(Regex("""shutil\\s*\\.\\s*rmtree|os\\s*\\.\\s*(remove|unlink)\\s*\\(""", RegexOption.IGNORE_CASE), "filesystem deletion"),
        PatternHint(Regex("""\\bwhile\\s+True\\s*:"""), "possible infinite loop"),
        PatternHint(Regex("""\\bctypes\\b""", RegexOption.IGNORE_CASE), "ctypes native-library access"),
    )

    fun inspect(definition: CustomToolDefinition): List<String> {
        val hints = when (definition.kind) {
            CustomToolKind.JAVASCRIPT -> javascriptHints
            CustomToolKind.PYTHON -> pythonHints
            else -> return emptyList()
        }
        return hints.filter { it.regex.containsMatchIn(definition.source) }
            .map { it.label }
            .distinct()
    }
}
