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
                definition.mcpServerId.isBlank() -> "An existing MCP server id is required."
                !toolNamePattern.matches(definition.remoteToolName) -> "A valid remote MCP tool name is required."
                definition.source.isNotEmpty() || definition.actions.isNotEmpty() ->
                    "MCP wrappers cannot embed source code or action steps."
                else -> null
            }
        }
    }
}
