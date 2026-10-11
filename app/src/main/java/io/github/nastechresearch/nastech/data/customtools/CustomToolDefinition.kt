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
    /**
     * This deliberately uses conservative substring checks instead of a regex parser.
     * It is a review aid, not a security verdict or a sandbox.
     */
    fun inspect(definition: CustomToolDefinition): List<String> {
        val source = definition.source.lowercase()
        val warnings = mutableListOf<String>()

        fun containsAny(vararg needles: String): Boolean = needles.any { needle -> source.contains(needle) }

        when (definition.kind) {
            CustomToolKind.JAVASCRIPT -> {
                if (containsAny("eval(", "eval (")) warnings += "eval(...) dynamic evaluation"
                if (containsAny("new function")) warnings += "new Function(...) dynamic code"
                if (containsAny("fetch(", "fetch (")) warnings += "fetch(...) network requests"
                if (containsAny("xmlhttprequest", "websocket")) warnings += "XHR / WebSocket network access"
                if (containsAny("globalthis", "process", "require(")) warnings += "global/process/module access"
                if (containsAny("while(true)", "while (true)", "for(;;)", "for (;;)", "for (;;)")) {
                    warnings += "possible infinite loop"
                }
            }
            CustomToolKind.PYTHON -> {
                if (containsAny("os.system(", "os.system (")) warnings += "os.system(...) shell execution"
                if (containsAny("subprocess")) warnings += "subprocess command execution"
                if (containsAny("socket", "requests", "urllib")) warnings += "network access libraries"
                if (containsAny("eval(", "exec(", "eval (", "exec (")) warnings += "eval(...) / exec(...) dynamic code"
                if (containsAny("open(" , "open (")) warnings += "open(...) filesystem access"
                if (containsAny("shutil.rmtree", "os.remove(", "os.unlink(", "os.remove (", "os.unlink (")) {
                    warnings += "filesystem deletion"
                }
                if (containsAny("while true:")) warnings += "possible infinite loop"
                if (containsAny("ctypes")) warnings += "ctypes native-library access"
            }
            else -> return emptyList()
        }
        return warnings.distinct()
    }
}
