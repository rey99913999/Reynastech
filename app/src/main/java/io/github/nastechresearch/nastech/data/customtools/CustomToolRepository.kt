package io.github.nastechresearch.nastech.data.customtools

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * File-backed repository for imported custom-tool definitions.
 *
 * Import is intentionally non-activating: any enabled flag in incoming JSON is ignored.
 * Runtime code must separately review permissions and opt a definition in before exposing it.
 */
class CustomToolRepository(context: Context) {
    private val root = File(context.filesDir, DIRECTORY_NAME)
    private val json = Json {
        ignoreUnknownKeys = false
        prettyPrint = true
        encodeDefaults = true
    }
    private val lock = Any()
    private val mutableTools = MutableStateFlow(loadExisting())
    val tools: StateFlow<List<CustomToolDefinition>> = mutableTools.asStateFlow()

    init {
        root.mkdirs()
    }

    fun get(id: String): CustomToolDefinition? =
        mutableTools.value.firstOrNull { it.id == id }

    /** Import one JSON definition; existing ids require explicit replacement. */
    fun importJson(rawJson: String, replaceExisting: Boolean = false): Result<CustomToolDefinition> {
        if (rawJson.length > MAX_JSON_CHARS) {
            return Result.failure(IllegalArgumentException("Tool definition exceeds the $MAX_JSON_CHARS-character limit."))
        }

        return runCatching {
            val imported = json.decodeFromString<CustomToolDefinition>(rawJson)
            val validationError = CustomToolDefinitionValidator.validate(imported)
            require(validationError == null) { validationError ?: "Invalid custom-tool definition." }

            synchronized(lock) {
                val existing = mutableTools.value.firstOrNull { it.id == imported.id }
                require(existing == null || replaceExisting) {
                    "A custom tool with id '${imported.id}' already exists. Choose replace to overwrite it."
                }
                val sameName = mutableTools.value.firstOrNull {
                    it.id != imported.id && it.name == imported.name
                }
                require(sameName == null) {
                    "Another custom tool already uses the name '${imported.name}'. Choose a different name."
                }

                val now = System.currentTimeMillis()
                val safeDefinition = imported.copy(
                    enabled = false,
                    createdAtMs = existing?.createdAtMs?.takeIf { it > 0L } ?: now,
                    updatedAtMs = now,
                )
                writeAtomically(safeDefinition)
                publishFromDisk()
                safeDefinition
            }
        }
    }

    fun setEnabled(id: String, enabled: Boolean): Result<CustomToolDefinition> = runCatching {
        synchronized(lock) {
            val existing = mutableTools.value.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Custom tool '$id' was not found.")
            val next = existing.copy(enabled = enabled, updatedAtMs = System.currentTimeMillis())
            val validationError = CustomToolDefinitionValidator.validate(next)
            require(validationError == null) { validationError ?: "Invalid custom-tool definition." }
            writeAtomically(next)
            publishFromDisk()
            next
        }
    }

    fun remove(id: String): Result<Unit> = runCatching {
        require(ID_PATTERN.matches(id)) { "Invalid custom-tool id." }
        synchronized(lock) {
            val file = fileFor(id)
            if (file.exists() && !file.delete()) {
                throw IllegalStateException("Could not remove custom tool '$id'.")
            }
            publishFromDisk()
        }
    }

    private fun loadExisting(): List<CustomToolDefinition> {
        if (!root.exists()) return emptyList()
        return root.listFiles().orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension == "json" && ID_PATTERN.matches(it.nameWithoutExtension) }
            .mapNotNull { file ->
                runCatching {
                    val definition = json.decodeFromString<CustomToolDefinition>(file.readText())
                    if (CustomToolDefinitionValidator.validate(definition) == null) definition else null
                }.getOrNull()
            }
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    private fun publishFromDisk() {
        mutableTools.value = loadExisting()
    }

    private fun writeAtomically(definition: CustomToolDefinition) {
        root.mkdirs()
        val target = fileFor(definition.id)
        val temp = File(root, definition.id + ".json.tmp")
        temp.writeText(json.encodeToString(definition))
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IllegalStateException("Could not save custom tool '${definition.id}'.")
        }
    }

    private fun fileFor(id: String): File {
        require(ID_PATTERN.matches(id)) { "Invalid custom-tool id." }
        return File(root, "$id.json")
    }

    private companion object {
        const val DIRECTORY_NAME = "custom_tools"
        const val MAX_JSON_CHARS = 256 * 1024
        val ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    }
}
