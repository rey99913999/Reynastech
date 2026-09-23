package io.github.nastechresearch.nastech.data.execution

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Modality
import me.rerere.ai.ui.UIMessagePart

enum class ToolSourceKind {
    BUILTIN, PLUGIN, MCP, PROVIDER, WORKSPACE, UNKNOWN
}

enum class ToolCategory {
    FILES, BROWSER, GIT, MEMORY, SYSTEM_DEVICE, COMMUNICATION, MEDIA, NETWORK,
    AUTOMATION, MCP_PLUGIN, WORKSPACE, SEARCH, SUB_AGENTS, OTHER;

    val displayName: String
        get() = when (this) {
            FILES -> "Files"
            BROWSER -> "Browser"
            GIT -> "Git"
            MEMORY -> "Memory"
            SYSTEM_DEVICE -> "System/Device"
            COMMUNICATION -> "Communication"
            MEDIA -> "Media"
            NETWORK -> "Network"
            AUTOMATION -> "Automation"
            MCP_PLUGIN -> "MCP/Plugin"
            WORKSPACE -> "Workspace"
            SEARCH -> "Search"
            SUB_AGENTS -> "Sub-agents"
            OTHER -> "Other"
        }

    companion object {
        fun fromLabel(value: String): ToolCategory? {
            val normalized = value.trim().lowercase().replace('_', ' ').replace('-', ' ')
            return entries.firstOrNull {
                it.displayName.lowercase() == normalized ||
                    it.name.lowercase() == normalized.replace(' ', '_')
            }
        }
    }
}

enum class ToolRiskLevel { LOW, MEDIUM, HIGH }

enum class ToolVisibilityStage {
    HIDDEN, CATEGORY_VISIBLE, METADATA_VISIBLE, FULL_SCHEMA_LOADED, EXECUTED
}

data class ToolSourceHint(
    val tool: Tool,
    val source: ToolSourceKind,
    val sourceId: String,
    val permissions: Set<String> = emptySet(),
    val category: ToolCategory? = null,
    val risk: ToolRiskLevel? = null,
    val capabilities: Set<String> = emptySet(),
    val sideEffects: Set<String> = emptySet(),
    val preconditions: Set<String> = emptySet(),
    val keywords: Set<String> = emptySet(),
    val outputContract: String = "Tool result parts",
)

data class ToolIdentity(
    val source: ToolSourceKind,
    val sourceId: String,
    val logicalName: String,
) {
    val stableId: String
        get() = source.name.lowercase() + ":" + sourceId + ":" + logicalName
}

data class ToolMetadata(
    val identity: ToolIdentity,
    val modelName: String,
    val category: ToolCategory,
    val description: String,
    val keywords: Set<String>,
    val capabilities: Set<String>,
    val risk: ToolRiskLevel,
    val permissions: Set<String>,
    val sideEffects: Set<String>,
    val preconditions: Set<String>,
    val inputSummary: List<String>,
    val requiredFields: List<String>,
    val outputContract: String,
)

private data class RegisteredTool(
    val identity: ToolIdentity,
    val metadata: ToolMetadata,
    val tool: Tool,
)

data class LoadToolsResult(
    val loaded: List<String>,
    val alreadyLoaded: List<String>,
    val notFound: List<String>,
)

data class ToolCategorySummary(
    val category: ToolCategory,
    val count: Int,
    val sampleTools: List<String>,
)

class ExecutionToolRegistry(
    tools: List<Tool> = emptyList(),
    sourceHints: List<ToolSourceHint> = emptyList(),
) {
    private var sourceHints = sourceHints.toList()
    private val toolsByIdentity = LinkedHashMap<ToolIdentity, RegisteredTool>()
    private val identityByModelName = LinkedHashMap<String, ToolIdentity>()
    private val identitiesByLogicalName = LinkedHashMap<String, MutableList<ToolIdentity>>()
    private val visibility = LinkedHashMap<ToolIdentity, ToolVisibilityStage>()
    private val alwaysVisibleTools = LinkedHashMap<String, Tool>()
    private var allTools: List<Tool> = emptyList()

    init {
        rebuild(tools)
    }

    fun register(tool: Tool, sourceHint: ToolSourceHint? = null): ToolIdentity {
        if (sourceHint != null) {
            sourceHints = sourceHints.filterNot { it.tool === tool } + sourceHint
        }
        val next = (allTools + tool).distinctBy { System.identityHashCode(it).toString() + ":" + it.name }
        rebuild(next)
        return requireNotNull(identityFor(tool))
    }

    fun registerAll(tools: List<Tool>, sourceHints: List<ToolSourceHint> = emptyList()) {
        if (sourceHints.isNotEmpty()) {
            sourceHints.forEach { hint ->
                this.sourceHints = this.sourceHints.filterNot { it.tool === hint.tool } + hint
            }
        }
        rebuild(allTools + tools)
    }

    fun registerAlwaysVisible(tool: Tool, sourceHint: ToolSourceHint? = null) {
        alwaysVisibleTools[tool.name] = tool
        register(tool, sourceHint)
    }

    fun applySourceHints(hints: List<ToolSourceHint>) {
        if (hints.isEmpty()) return
        hints.forEach { hint ->
            sourceHints = sourceHints.filterNot { it.tool === hint.tool } + hint
        }
        rebuild(allTools)
    }

    fun resetVisibility() {
        visibility.keys.toList().forEach { visibility[it] = ToolVisibilityStage.HIDDEN }
        alwaysVisibleTools.values.forEach { tool ->
            findRegistered(tool.name)?.let { visibility[it.identity] = ToolVisibilityStage.FULL_SCHEMA_LOADED }
        }
    }

    fun hasRegisteredTools(): Boolean = toolsByIdentity.isNotEmpty()

    fun names(): Set<String> = identityByModelName.keys

    fun find(name: String): Tool? = findRegistered(name)?.tool

    fun findLoaded(name: String): Tool? =
        findRegistered(name)?.takeIf {
            visibility[it.identity] == ToolVisibilityStage.FULL_SCHEMA_LOADED ||
                visibility[it.identity] == ToolVisibilityStage.EXECUTED ||
                isAlwaysVisible(it.identity)
        }?.tool

    fun metadata(name: String): ToolMetadata? = findRegistered(name)?.metadata

    fun identityFor(name: String): ToolIdentity? = findRegistered(name)?.identity

    fun identityFor(tool: Tool): ToolIdentity? =
        toolsByIdentity.values.firstOrNull { it.tool === tool }?.identity
            ?: toolsByIdentity.values.firstOrNull { it.tool.name == tool.name }?.identity

    fun logicalName(name: String): String = findRegistered(name)?.identity?.logicalName ?: name

    fun requiresApproval(name: String, args: JsonObject = JsonObject(emptyMap())): Boolean =
        findRegistered(name)?.tool?.needsApproval(args) == true

    fun approvalKey(name: String): String = name

    fun stage(name: String): ToolVisibilityStage =
        findRegistered(name)?.let { visibility[it.identity] ?: ToolVisibilityStage.HIDDEN }
            ?: ToolVisibilityStage.HIDDEN

    fun markExecuted(name: String) {
        findRegistered(name)?.let { visibility[it.identity] = ToolVisibilityStage.EXECUTED }
    }

    fun currentTools(model: Model? = null): List<Tool> {
        if (!hasRegisteredTools()) return emptyList()
        if (model != null && !modelSupportsToolCalling(model)) return emptyList()
        return buildList {
            alwaysVisibleTools.values.forEach { add(it) }
            toolsByIdentity.values.forEach { entry ->
                val state = visibility[entry.identity] ?: ToolVisibilityStage.HIDDEN
                if ((state == ToolVisibilityStage.FULL_SCHEMA_LOADED || state == ToolVisibilityStage.EXECUTED) &&
                    modelSupports(entry.metadata, model)
                ) {
                    add(entry.tool)
                }
            }
        }.distinctBy { it.name }
    }

    fun discoveryTools(model: Model? = null): List<Tool> {
        if (!hasRegisteredTools()) return emptyList()
        if (model != null && !modelSupportsToolCalling(model)) return emptyList()
        return listOf(
            capabilityDiscoveryTool(),
            metadataDiscoveryTool(),
            schemaLoaderTool(),
        )
    }

    fun providerTools(model: Model? = null): List<Tool> =
        (discoveryTools(model) + currentTools(model)).distinctBy { it.name }

    fun loadTools(names: List<String>): LoadToolsResult {
        val loaded = mutableListOf<String>()
        val alreadyLoaded = mutableListOf<String>()
        val notFound = mutableListOf<String>()

        names.distinct().forEach { requested ->
            val matches = resolveCandidates(requested)
            if (matches.isEmpty() || (matches.size > 1 && requested !in identityByModelName)) {
                notFound += requested
                return@forEach
            }
            matches.forEach { entry ->
                val state = visibility[entry.identity] ?: ToolVisibilityStage.HIDDEN
                if (state == ToolVisibilityStage.FULL_SCHEMA_LOADED || state == ToolVisibilityStage.EXECUTED) {
                    alreadyLoaded += entry.tool.name
                } else {
                    visibility[entry.identity] = ToolVisibilityStage.FULL_SCHEMA_LOADED
                    loaded += entry.tool.name
                }
            }
        }

        return LoadToolsResult(loaded.distinct(), alreadyLoaded.distinct(), notFound.distinct())
    }

    fun unloadTool(name: String) {
        findRegistered(name)?.let { entry ->
            if (!isAlwaysVisible(entry.identity)) visibility[entry.identity] = ToolVisibilityStage.HIDDEN
        }
    }

    fun discoverCapabilities(model: Model? = null): List<ToolCategorySummary> =
        toolsByIdentity.values
            .asSequence()
            .filter { modelSupports(it.metadata, model) }
            .groupBy { it.metadata.category }
            .entries
            .sortedBy { it.key.name }
            .map { (category, entries) ->
                ToolCategorySummary(
                    category = category,
                    count = entries.size,
                    sampleTools = entries.sortedBy { it.metadata.modelName }
                        .take(6)
                        .map { it.metadata.modelName },
                )
            }

    fun discoverTools(
        query: String? = null,
        category: ToolCategory? = null,
        limit: Int = 12,
        model: Model? = null,
    ): List<ToolMetadata> {
        val normalized = query.orEmpty().trim().lowercase()
        return toolsByIdentity.values
            .asSequence()
            .filter { modelSupports(it.metadata, model) }
            .filter { category == null || it.metadata.category == category }
            .map { it to score(it.metadata, normalized) }
            .filter { normalized.isBlank() || it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<RegisteredTool, Int>> { it.second }
                    .thenBy { it.first.metadata.modelName }
            )
            .take(limit.coerceIn(1, 25))
            .map { it.first.metadata }
            .toList()
    }

    fun compactIndex(maxChars: Int = 6_000): String =
        discoverySummary().take(maxChars)

    fun discoverySummary(model: Model? = null): String = buildString {
        appendLine("Tool Registry")
        appendLine("Runtime-owned progressive discovery. Do not assume a tool is executable until it is loaded.")
        val categories = discoverCapabilities(model)
        if (categories.isEmpty()) {
            appendLine("Available capabilities: none.")
        } else {
            appendLine("Available capabilities:")
            categories.forEach { item ->
                appendLine("- " + item.category.displayName + ": " + item.count)
            }
        }
        appendLine("Flow: discover_tools -> load_tools -> execute loaded tool.")
    }.trim()

    private fun capabilityDiscoveryTool(): Tool = Tool(
        name = "discover_capabilities",
        description = "Return the compact category/capability catalogue. Full schemas are never returned here.",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = {
            val payload = buildJsonObject {
                put("capabilities", buildJsonArray {
                    discoverCapabilities().forEach { item ->
                        add(buildJsonObject {
                            put("category", item.category.displayName)
                            put("count", item.count)
                            put("sample_tools", buildJsonArray { item.sampleTools.forEach(::add) })
                        })
                    }
                })
                put("next_step", "Use discover_tools for relevant metadata.")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )

    private fun metadataDiscoveryTool(): Tool = Tool(
        name = "discover_tools",
        description = "Find tools by natural-language capability or category. Returns compact metadata, not full schemas.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Capability need, for example: read text from a file")
                    })
                    put("category", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional category such as Files, Browser, Memory, System/Device, Communication, Media, Network, Automation, MCP/Plugin")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum candidates, default 8, max 25")
                    })
                },
            )
        },
        execute = { args ->
            val obj = args as? JsonObject ?: JsonObject(emptyMap())
            val category = obj["category"]?.jsonPrimitive?.contentOrNull?.let(ToolCategory::fromLabel)
            val limit = obj["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 8
            val payload = buildJsonObject {
                put(
                    "matches",
                    buildJsonArray {
                        discoverTools(
                            query = obj["query"]?.jsonPrimitive?.contentOrNull,
                            category = category,
                            limit = limit,
                        ).forEach { add(it.toDiscoveryJson()) }
                    },
                )
                put("next_step", "Use load_tools with exact tool names.")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )

    private fun schemaLoaderTool(): Tool = Tool(
        name = "load_tools",
        description = "Load full provider-facing schemas for selected tools. Loaded definitions appear on the next model request.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("tool_names", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Exact model-facing tool names from discover_tools")
                    })
                },
                required = listOf("tool_names"),
            )
        },
        execute = { args ->
            val obj = args as? JsonObject ?: JsonObject(emptyMap())
            val requested = (obj["tool_names"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty()
            val result = loadTools(requested)
            val payload = buildJsonObject {
                put("loaded", buildJsonArray { result.loaded.forEach(::add) })
                put("already_loaded", buildJsonArray { result.alreadyLoaded.forEach(::add) })
                put("not_found", buildJsonArray { result.notFound.forEach(::add) })
                put(
                    "next_step",
                    if (result.loaded.isNotEmpty() || result.alreadyLoaded.isNotEmpty()) {
                        "Continue with the loaded tool definitions on the next request."
                    } else {
                        "No tool was loaded; discover another candidate."
                    },
                )
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )

    private fun rebuild(tools: List<Tool>) {
        allTools = tools.distinctBy { System.identityHashCode(it).toString() + ":" + it.name }
        toolsByIdentity.clear()
        identityByModelName.clear()
        identitiesByLogicalName.clear()
        visibility.clear()

        val duplicates = allTools.groupBy { it.name }.mapValues { it.value.size }
        allTools.forEach { tool ->
            val hint = sourceHints.firstOrNull { it.tool === tool }
            val source = hint?.source ?: defaultSource(tool)
            val sourceId = hint?.sourceId ?: defaultSourceId(tool)
            val logicalName = tool.name
            val identity = ToolIdentity(source, sourceId, logicalName)
            val modelName = if ((duplicates[logicalName] ?: 0) > 1) {
                buildAlias(source, sourceId, logicalName)
            } else {
                logicalName
            }
            val description = tool.description.replace(Regex("\s+"), " ").trim()
            val risk = hint?.risk ?: defaultRisk(tool)
            val category = hint?.category ?: classifyCategory(source, logicalName, description)
            val keywords = (hint?.keywords.orEmpty() + deriveKeywords(logicalName, description)).toSet()
            val capabilities = (hint?.capabilities.orEmpty() + deriveCapabilities(logicalName, description)).toSet()
            val permissions = (hint?.permissions.orEmpty() + if (defaultRequiresApproval(tool)) setOf("tool_approval") else emptySet()).toSet()
            val sideEffects = (hint?.sideEffects.orEmpty() + if (risk != ToolRiskLevel.LOW) setOf("runtime_side_effect") else emptySet()).toSet()
            val schema = runCatching { tool.parameters() }.getOrNull()
            val summary = summarizeSchema(schema)
            val metadata = ToolMetadata(
                identity = identity,
                modelName = modelName,
                category = category,
                description = description,
                keywords = keywords,
                capabilities = capabilities,
                risk = risk,
                permissions = permissions,
                sideEffects = sideEffects,
                preconditions = hint?.preconditions.orEmpty(),
                inputSummary = summary.fields,
                requiredFields = summary.required,
                outputContract = hint?.outputContract ?: "Tool result parts",
            )
            val modelTool = if (logicalName == modelName) tool else tool.copy(name = modelName)
            val entry = RegisteredTool(identity, metadata, modelTool)
            toolsByIdentity[identity] = entry
            identityByModelName[modelName] = identity
            identitiesByLogicalName.getOrPut(logicalName) { mutableListOf() }.add(identity)
            visibility[identity] = ToolVisibilityStage.HIDDEN
        }

        alwaysVisibleTools.values.forEach { tool ->
            val entry = findRegistered(tool.name) ?: return@forEach
            visibility[entry.identity] = ToolVisibilityStage.FULL_SCHEMA_LOADED
        }
    }

    private fun findRegistered(name: String): RegisteredTool? {
        identityByModelName[name]?.let { return toolsByIdentity[it] }
        val matches = identitiesByLogicalName[name].orEmpty()
        return if (matches.size == 1) toolsByIdentity[matches.first()] else null
    }

    private fun resolveCandidates(name: String): List<RegisteredTool> {
        identityByModelName[name]?.let { return listOfNotNull(toolsByIdentity[it]) }
        return identitiesByLogicalName[name].orEmpty().mapNotNull { toolsByIdentity[it] }
    }

    private fun isAlwaysVisible(identity: ToolIdentity): Boolean =
        alwaysVisibleTools.values.any { identityFor(it) == identity }

    private fun defaultSource(tool: Tool): ToolSourceKind = when {
        tool.name.startsWith("mcp__", ignoreCase = true) -> ToolSourceKind.MCP
        tool.name.startsWith("plugin__", ignoreCase = true) -> ToolSourceKind.PLUGIN
        tool.name.startsWith("provider__", ignoreCase = true) -> ToolSourceKind.PROVIDER
        else -> ToolSourceKind.BUILTIN
    }

    private fun defaultSourceId(tool: Tool): String = when {
        tool.name.startsWith("mcp__", ignoreCase = true) ->
            tool.name.removePrefix("mcp__").substringBefore("__").ifBlank { "mcp" }
        tool.name.startsWith("plugin__", ignoreCase = true) ->
            tool.name.removePrefix("plugin__").substringBefore("__").ifBlank { "plugin" }
        else -> "builtin"
    }

    private fun defaultRequiresApproval(tool: Tool): Boolean =
        runCatching { tool.needsApproval(JsonObject(emptyMap())) }.getOrDefault(false)

    private fun defaultRisk(tool: Tool): ToolRiskLevel =
        if (defaultRequiresApproval(tool)) ToolRiskLevel.HIGH else ToolRiskLevel.LOW

    private fun buildAlias(source: ToolSourceKind, sourceId: String, originalName: String): String {
        val prefix = source.name.lowercase()
        val id = sanitize(sourceId).take(18)
        return sanitize(prefix + "_" + id + "__" + originalName).take(64)
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun deriveKeywords(name: String, description: String): Set<String> =
        Regex("[A-Za-z0-9_]{3,}").findAll(name + " " + description)
            .map { it.value.lowercase() }
            .filterNot { it in setOf("the", "and", "for", "with", "from", "this", "that", "tool", "use", "the") }
            .take(40)
            .toSet()

    private fun deriveCapabilities(name: String, description: String): Set<String> {
        val text = (name + " " + description).lowercase()
        val result = mutableSetOf<String>()
        if (listOf("screenshot", "vision", "show_image", "ocr").any(text::contains)) result += "image_input"
        return result
    }

    private fun classifyCategory(source: ToolSourceKind, name: String, description: String): ToolCategory {
        if (source == ToolSourceKind.MCP || source == ToolSourceKind.PLUGIN) return ToolCategory.MCP_PLUGIN
        val text = (name + " " + description).lowercase()
        return when {
            "memory" in text -> ToolCategory.MEMORY
            "workspace" in text -> ToolCategory.WORKSPACE
            "subagent" in text || "sub_agent" in text -> ToolCategory.SUB_AGENTS
            "browser_" in text -> ToolCategory.BROWSER
            "git" in text -> ToolCategory.GIT
            listOf("file", "directory", "archive", "zip", "clipboard").any(text::contains) -> ToolCategory.FILES
            listOf("telegram", "sms", "contact", "notification", "email", "call_log").any(text::contains) -> ToolCategory.COMMUNICATION
            listOf("camera", "audio", "mic", "media", "screenshot", "vision", "image", "ocr", "tts").any(text::contains) -> ToolCategory.MEDIA
            listOf("web_", "http", "download", "ssh", "dns").any(text::contains) -> ToolCategory.NETWORK
            listOf("workflow", "job", "task", "automation", "schedule").any(text::contains) -> ToolCategory.AUTOMATION
            listOf("search", "translate").any(text::contains) -> ToolCategory.SEARCH
            listOf("tap", "click", "swipe", "scroll", "accessibility", "shizuku", "termux", "keyboard", "launch_app", "global_action", "brightness", "volume", "torch", "vibrate", "wallpaper", "keystore", "nfc", "permission").any(text::contains) -> ToolCategory.SYSTEM_DEVICE
            else -> ToolCategory.OTHER
        }
    }

    private fun summarizeSchema(schema: InputSchema?): SchemaSummary {
        val objectSchema = schema as? InputSchema.Obj ?: return SchemaSummary()
        val fields = objectSchema.properties.mapNotNull { (name, value) ->
            val type = value.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "value"
            name + ":" + type
        }
        return SchemaSummary(fields, objectSchema.required.orEmpty())
    }

    private fun score(metadata: ToolMetadata, query: String): Int {
        if (query.isBlank()) return 1
        val tokens = Regex("[A-Za-z0-9_]{2,}").findAll(query).map { it.value }.toSet()
        return tokens.sumOf { token ->
            (if (metadata.modelName.lowercase().contains(token)) 8 else 0) +
                (if (metadata.category.name.lowercase().contains(token) || metadata.category.displayName.lowercase().contains(token)) 5 else 0) +
                (if (metadata.keywords.contains(token)) 3 else 0) +
                (if (metadata.description.lowercase().contains(token)) 2 else 0)
        }
    }

    private fun modelSupportsToolCalling(model: Model): Boolean =
        model.abilities.isEmpty() || ModelAbility.TOOL in model.abilities

    private fun modelSupports(metadata: ToolMetadata, model: Model?): Boolean {
        if (model == null) return true
        if (!modelSupportsToolCalling(model)) return false
        if ("image_input" in metadata.capabilities && Modality.IMAGE !in model.inputModalities) return false
        return true
    }

    private data class SchemaSummary(
        val fields: List<String> = emptyList(),
        val required: List<String> = emptyList(),
    )
}

fun ToolMetadata.toDiscoveryJson(): JsonObject = buildJsonObject {
    put("tool_name", identity.logicalName)
    put("model_tool_name", modelName)
    put("stable_id", identity.stableId)
    put("source", identity.source.name.lowercase())
    put("source_id", identity.sourceId)
    put("category", category.displayName)
    put("description", description)
    put("keywords", buildJsonArray { keywords.take(16).forEach(::add) })
    put("capabilities", buildJsonArray { capabilities.forEach(::add) })
    put("risk", risk.name.lowercase())
    put("permissions", buildJsonArray { permissions.forEach(::add) })
    put("side_effects", buildJsonArray { sideEffects.forEach(::add) })
    put("preconditions", buildJsonArray { preconditions.forEach(::add) })
    put("input", buildJsonArray { inputSummary.forEach(::add) })
    put("required", buildJsonArray { requiredFields.forEach(::add) })
    put("output_contract", outputContract)
}

private fun ToolCategory.displayNameLegacy(): String = displayName
