package io.github.nastechresearch.nastech.plugin

import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.nastechresearch.nastech.BuildConfig
import io.github.nastechresearch.nastech.data.ai.mcp.McpCommonOptions
import io.github.nastechresearch.nastech.data.ai.mcp.McpManager
import io.github.nastechresearch.nastech.data.ai.mcp.McpOAuthState
import io.github.nastechresearch.nastech.data.ai.mcp.McpServerConfig
import io.github.nastechresearch.nastech.data.datastore.SettingsStore
import io.github.nastechresearch.nastech.data.files.SkillManager
import io.github.nastechresearch.nastech.data.ai.tools.ToolInvocationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipFile
import kotlin.uuid.Uuid

private const val TAG = "PluginManager"
private const val MAX_PACKAGE_BYTES = 12L * 1024L * 1024L
private const val MAX_UNCOMPRESSED_BYTES = 32L * 1024L * 1024L
private const val MAX_FILES = 256
private const val MANIFEST_NAME = "plugin.json"
private const val PLUGINS_DIR = "plugins"
private const val STATE_NAME = "state.json"

class PluginManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val okHttpClient: OkHttpClient,
) {
    private val root = File(context.filesDir, PLUGINS_DIR)
    private val stateFile = File(root, STATE_NAME)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val _plugins = MutableStateFlow(loadState().plugins)
    val plugins: StateFlow<List<PluginRecord>> = _plugins.asStateFlow()
    private val writeLock = Any()

    init {
        root.mkdirs()
        reconcileMcpState()
    }

    suspend fun installFromUrl(url: String): Result<PluginRecord> = withContext(Dispatchers.IO) {
        val source = url.trim()
        if (source.isBlank() || (!source.startsWith("http://") && !source.startsWith("https://"))) {
            return@withContext Result.failure(IllegalArgumentException("Only http(s) plugin URLs are supported."))
        }
        val request = Request.Builder().url(source).get().build()
        val bytes = okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Plugin download failed: HTTP " + response.code)
            val body = response.body ?: throw IllegalStateException("Plugin download returned no body.")
            if (body.contentLength() > MAX_PACKAGE_BYTES) {
                throw IllegalStateException("Plugin package exceeds " + (MAX_PACKAGE_BYTES / 1024 / 1024) + " MiB.")
            }
            body.bytes().also { bytes ->
                if (bytes.size > MAX_PACKAGE_BYTES) {
                    throw IllegalStateException("Plugin package exceeds " + (MAX_PACKAGE_BYTES / 1024 / 1024) + " MiB.")
                }
            }
        }
        installBytes(bytes, source)
    }

    suspend fun installFromUri(uri: Uri, sourceLabel: String = uri.toString()): Result<PluginRecord> =
        withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext Result.failure(IllegalStateException("Could not read the selected plugin package."))
            if (bytes.size > MAX_PACKAGE_BYTES) {
                return@withContext Result.failure(
                    IllegalStateException("Plugin package exceeds " + (MAX_PACKAGE_BYTES / 1024 / 1024) + " MiB.")
                )
            }
            installBytes(bytes, sourceLabel)
        }

    private suspend fun installBytes(bytes: ByteArray, sourceLabel: String): Result<PluginRecord> {
        val staging = File(root, ".staging-" + System.nanoTime())
        return try {
            staging.mkdirs()
            val packageFile = File(staging, "package.zip")
            FileOutputStream(packageFile).use { it.write(bytes) }
            val manifest = readAndValidateManifest(packageFile)
            if (!manifest.isCompatible(BuildConfig.VERSION_NAME)) {
                throw IllegalStateException(
                    "Plugin " + manifest.id + " requires app " + manifest.minAppVersion + " or newer."
                )
            }

            val normalizedId = manifest.normalizedId()
            val pluginRoot = File(root, normalizedId)
            val versionDir = File(pluginRoot, "versions/" + safeSegment(manifest.version))
            if (versionDir.exists()) versionDir.deleteRecursively()
            versionDir.mkdirs()
            extractZip(packageFile, versionDir)

            val old = _plugins.value.firstOrNull { it.manifest.normalizedId() == normalizedId }
            if (old != null && compareVersions(manifest.version, old.manifest.version) < 0) {
                throw IllegalStateException(
                    "Installed version " + old.manifest.version + " is newer than " + manifest.version + "."
                )
            }

            val ownedSkills = mutableSetOf<String>()
            val sharedSkills = mutableSetOf<String>()
            for (skill in manifest.skills) {
                val skillDir = versionDir.resolve(skill.path.ifBlank { "skills/" + skill.name })
                val skillFile = skillDir.resolve("SKILL.md")
                if (!skillFile.exists()) {
                    throw IllegalStateException("Plugin skill '" + skill.name + "' is missing SKILL.md.")
                }
                val existing = skillManager.getSkillDir(skill.name)
                if (existing == null) {
                    val files = mutableMapOf<String, String>()
                    collectSkillFiles(skillDir, skillDir, files)
                    if (!skillManager.saveSkillFilesAtomically(skill.name, files)) {
                        throw IllegalStateException("Failed to install plugin skill '" + skill.name + "'.")
                    }
                    ownedSkills += skill.name
                } else {
                    sharedSkills += skill.name
                }
            }

            val currentSettings = settingsStore.settingsFlow.value
            val newServers = currentSettings.mcpServers.toMutableList()
            val serverIds = mutableMapOf<String, String>()

            for (server in manifest.mcpServers) {
                val existingId = old?.mcpServerIds?.get(server.key)
                val serverId = existingId?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: Uuid.random()
                serverIds[server.key] = serverId.toString()

                val previous = currentSettings.mcpServers.firstOrNull { it.id == serverId }
                val common = McpCommonOptions(
                    enable = false,
                    name = server.name,
                    headers = server.headers.map { it.name to it.value },
                    tools = emptyList(),
                    oauth = previous?.commonOptions?.oauth
                        ?: if (server.auth.type == "mcp_oauth") McpOAuthState(enabled = true) else null,
                )
                val config = if (server.transport.equals("sse", ignoreCase = true)) {
                    McpServerConfig.SseTransportServer(serverId, common, server.url)
                } else {
                    McpServerConfig.StreamableHTTPServer(serverId, common, server.url)
                }
                newServers.removeAll { it.id == serverId }
                newServers += config
            }
            settingsStore.update { it.copy(mcpServers = newServers) }

            val record = PluginRecord(
                manifest = manifest.copy(source = manifest.source.ifBlank { sourceLabel }),
                enabled = old?.enabled ?: false,
                grantedPermissions = old?.grantedPermissions?.intersect(manifest.permissions).orEmpty(),
                config = old?.config ?: manifest.configurationSchema.takeIf { it.isNotEmpty() }
                    ?: JsonObject(emptyMap()),
                installedAtMs = old?.installedAtMs ?: System.currentTimeMillis(),
                updatedAtMs = System.currentTimeMillis(),
                sourceLabel = sourceLabel,
                versionPath = versionDir.absolutePath,
                mcpServerIds = serverIds,
                ownedSkillNames = if (ownedSkills.isNotEmpty()) ownedSkills else old?.ownedSkillNames.orEmpty(),
                conversationIds = old?.conversationIds.orEmpty(),
                agentPluginBindings = old?.agentPluginBindings.orEmpty(),
                previousVersions = (old?.previousVersions.orEmpty() + (old?.manifest?.version ?: ""))
                    .filter { it.isNotBlank() && it != manifest.version }
                    .distinct()
                    .takeLast(8),
                status = if (old?.enabled == true) PluginStatus.ENABLED else PluginStatus.INSTALLED,
                installError = if (sharedSkills.isNotEmpty()) {
                    "Shared skills kept intact: " + sharedSkills.joinToString(", ")
                } else null,
            )
            val next = _plugins.value.filterNot { it.manifest.normalizedId() == normalizedId } + record
            persist(next)
            refreshMcpActivation(record)
            Result.success(record)
        } catch (t: Throwable) {
            Log.w(TAG, "Plugin install failed", t)
            Result.failure(t)
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun setEnabled(pluginId: String, enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        val current = find(pluginId) ?: return@withContext Result.failure(IllegalArgumentException("Plugin not found."))
        val next = current.copy(
            enabled = enabled,
            status = if (enabled) PluginStatus.ENABLED else PluginStatus.DISABLED,
            updatedAtMs = System.currentTimeMillis(),
        )
        persist(_plugins.value.map { if (same(it, current)) next else it })
        refreshMcpActivation(next)
        Result.success(Unit)
    }

    suspend fun setPermission(pluginId: String, permission: String, granted: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            val current = find(pluginId)
                ?: return@withContext Result.failure(IllegalArgumentException("Plugin not found."))
            if (permission !in current.manifest.permissions) {
                return@withContext Result.failure(
                    IllegalArgumentException("Plugin did not declare permission '" + permission + "'.")
                )
            }
            val permissions = if (granted) current.grantedPermissions + permission
            else current.grantedPermissions - permission
            val next = current.copy(
                grantedPermissions = permissions,
                updatedAtMs = System.currentTimeMillis(),
            )
            persist(_plugins.value.map { if (same(it, current)) next else it })
            refreshMcpActivation(next)
            Result.success(Unit)
        }

    suspend fun setConversationBinding(
        pluginId: String,
        conversationId: String,
        bound: Boolean,
    ): Result<Unit> = mutateRecord(pluginId) { record ->
        val ids = if (bound) record.conversationIds + conversationId
        else record.conversationIds - conversationId
        val agentMap = if (bound) record.agentPluginBindings
        else record.agentPluginBindings.filterKeys { !it.startsWith(conversationId + "/") }
        record.copy(
            conversationIds = ids,
            agentPluginBindings = agentMap,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    suspend fun setAgentBinding(
        pluginId: String,
        conversationId: String,
        agentId: String,
        allowed: Boolean,
    ): Result<Unit> = mutateRecord(pluginId) { record ->
        if (conversationId !in record.conversationIds) return@mutateRecord record
        val key = conversationId + "/" + agentId
        val allowedSet = record.agentPluginBindings[key].orEmpty()
        val nextSet = if (allowed) allowedSet + pluginId else allowedSet - pluginId
        val map = if (nextSet.isEmpty()) record.agentPluginBindings - key
        else record.agentPluginBindings + (key to nextSet)
        record.copy(agentPluginBindings = map, updatedAtMs = System.currentTimeMillis())
    }

    suspend fun setConfig(pluginId: String, config: JsonObject): Result<Unit> =
        mutateRecord(pluginId) { it.copy(config = config, updatedAtMs = System.currentTimeMillis()) }

    suspend fun uninstall(pluginId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val record = find(pluginId)
            ?: return@withContext Result.failure(IllegalArgumentException("Plugin not found."))
        val serverIds = record.mcpServerIds.values.mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }.toSet()
        settingsStore.update { settings ->
            settings.copy(mcpServers = settings.mcpServers.filterNot { it.id in serverIds })
        }
        for (skillName in record.ownedSkillNames) {
            runCatching { skillManager.deleteSkill(skillName) }
        }
        File(root, record.manifest.normalizedId()).deleteRecursively()
        persist(_plugins.value.filterNot { same(it, record) })
        Result.success(Unit)
    }

    suspend fun rollback(pluginId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val current = find(pluginId)
            ?: return@withContext Result.failure(IllegalArgumentException("Plugin not found."))
        val previous = current.previousVersions.lastOrNull()
            ?: return@withContext Result.failure(IllegalStateException("No previous plugin version is available."))
        val target = File(
            root,
            current.manifest.normalizedId() + "/versions/" + safeSegment(previous),
        )
        if (!target.exists()) {
            return@withContext Result.failure(IllegalStateException("Previous plugin package is missing."))
        }
        runCatching {
            val manifest = json.decodeFromString(
                PluginManifest.serializer(),
                target.resolve(MANIFEST_NAME).readText(),
            )
            activateRetainedVersion(current, manifest, target)
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.failure(it) },
        )
    }

    private suspend fun activateRetainedVersion(
        current: PluginRecord,
        manifest: PluginManifest,
        versionDir: File,
    ) {
        for (skill in manifest.skills) {
            val skillDir = versionDir.resolve(skill.path.ifBlank { "skills/" + skill.name })
            if (!skillDir.resolve("SKILL.md").exists()) continue
            if (current.ownedSkillNames.contains(skill.name) || skillManager.getSkillDir(skill.name) == null) {
                val files = mutableMapOf<String, String>()
                collectSkillFiles(skillDir, skillDir, files)
                if (!skillManager.saveSkillFilesAtomically(skill.name, files)) {
                    throw IllegalStateException("Failed to restore plugin skill '" + skill.name + "'.")
                }
            }
        }

        val currentSettings = settingsStore.settingsFlow.value
        val newServers = currentSettings.mcpServers.toMutableList()
        for (server in manifest.mcpServers) {
            val serverId = current.mcpServerIds[server.key]?.let {
                runCatching { Uuid.parse(it) }.getOrNull()
            } ?: continue
            val previous = currentSettings.mcpServers.firstOrNull { it.id == serverId }
            val common = McpCommonOptions(
                enable = false,
                name = server.name,
                headers = server.headers.map { it.name to it.value },
                tools = emptyList(),
                oauth = previous?.commonOptions?.oauth
                    ?: if (server.auth.type == "mcp_oauth") McpOAuthState(enabled = true) else null,
            )
            val config = if (server.transport.equals("sse", ignoreCase = true)) {
                McpServerConfig.SseTransportServer(serverId, common, server.url)
            } else {
                McpServerConfig.StreamableHTTPServer(serverId, common, server.url)
            }
            newServers.removeAll { it.id == serverId }
            newServers += config
        }
        settingsStore.update { it.copy(mcpServers = newServers) }

        val next = current.copy(
            manifest = manifest,
            grantedPermissions = current.grantedPermissions.intersect(manifest.permissions),
            versionPath = versionDir.absolutePath,
            previousVersions = (current.previousVersions.filter { it != manifest.version } + current.manifest.version)
                .distinct()
                .takeLast(8),
            updatedAtMs = System.currentTimeMillis(),
            status = if (current.enabled) PluginStatus.ENABLED else PluginStatus.INSTALLED,
            installError = null,
        )
        persist(_plugins.value.map { if (same(it, current)) next else it })
        refreshMcpActivation(next)
    }

    fun getActiveSkillNames(conversationId: String): Set<String> =
        _plugins.value
            .filter { it.enabled && conversationId in it.conversationIds }
            .flatMap { it.manifest.skills.map { skill -> skill.name } }
            .toSet()

    fun getPluginTools(invocationContext: ToolInvocationContext): List<Tool> {
        val conversationId = invocationContext.callerConversationId ?: return emptyList()
        return _plugins.value
            .filter { it.enabled && conversationId in it.conversationIds }
            .flatMap { record ->
                record.manifest.tools
                    .filter { spec ->
                        spec.requiredPermissions.all { it in record.grantedPermissions } &&
                            record.manifest.permissions.containsAll(spec.requiredPermissions)
                    }
                    .mapNotNull { spec -> createTool(record, spec) }
            }
            .distinctBy { it.name }
    }

    fun filterAllowedToolNames(
        toolNames: List<String>,
        conversationId: String,
        agentId: String,
    ): List<String> {
        val knownPluginNames = _plugins.value
            .flatMap { it.manifest.tools.map { tool -> tool.name } }
            .toSet()

        val allowedPluginIds = _plugins.value
            .filter { it.enabled && conversationId in it.conversationIds }
            .filter { record ->
                val key = conversationId + "/" + agentId
                record.agentPluginBindings[key].orEmpty().contains(record.manifest.id)
            }
            .map { it.manifest.id }
            .toSet()

        val allowedNames = _plugins.value
            .filter { it.manifest.id in allowedPluginIds }
            .flatMap { record ->
                record.manifest.tools.filter { spec ->
                    spec.requiredPermissions.all { it in record.grantedPermissions } &&
                        record.manifest.permissions.containsAll(spec.requiredPermissions)
                }.map { it.name }
            }
            .toSet()

        return toolNames.filter { it !in knownPluginNames || it in allowedNames }
    }

    fun installed(pluginId: String): PluginRecord? = find(pluginId)

    private fun createTool(record: PluginRecord, spec: PluginToolSpec): Tool? {
        if (spec.mcpServerKey.isBlank() || spec.remoteToolName.isBlank()) return null
        val serverId = record.mcpServerIds[spec.mcpServerKey]?.let {
            runCatching { Uuid.parse(it) }.getOrNull()
        } ?: return null

        val schema = spec.inputSchema
        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = (schema["required"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()

        return Tool(
            name = spec.name,
            description = spec.description.ifBlank { "Plugin tool provided by " + record.manifest.name + "." },
            parameters = { InputSchema.Obj(properties = properties, required = required) },
            needsApproval = {
                spec.requiredPermissions.any(::isSensitivePermission)
            },
            execute = { args ->
                try {
                    mcpManager.callTool(serverId, spec.remoteToolName, args)
                } catch (t: Throwable) {
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("error", "plugin_tool_failed")
                                put("plugin_id", record.manifest.id)
                                put("detail", t.message ?: t.javaClass.simpleName)
                            }.toString()
                        )
                    )
                }
            },
        )
    }

    private suspend fun mutateRecord(
        pluginId: String,
        transform: (PluginRecord) -> PluginRecord,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val current = find(pluginId)
            ?: return@withContext Result.failure(IllegalArgumentException("Plugin not found."))
        val next = transform(current)
        persist(_plugins.value.map { if (same(it, current)) next else it })
        refreshMcpActivation(next)
        Result.success(Unit)
    }

    private fun find(pluginId: String): PluginRecord? =
        _plugins.value.firstOrNull {
            it.manifest.normalizedId() == pluginId.trim().lowercase() || it.manifest.id == pluginId
        }

    private fun same(a: PluginRecord, b: PluginRecord): Boolean =
        a.manifest.normalizedId() == b.manifest.normalizedId()

    private fun persist(records: List<PluginRecord>) {
        val state = PluginState(records.sortedBy { it.manifest.name.lowercase() })
        synchronized(writeLock) {
            root.mkdirs()
            val temp = File(root, STATE_NAME + ".tmp")
            temp.writeText(json.encodeToString(PluginState.serializer(), state))
            if (!temp.renameTo(stateFile)) {
                stateFile.delete()
                if (!temp.renameTo(stateFile)) {
                    throw IllegalStateException("Could not persist plugin state.")
                }
            }
            _plugins.value = state.plugins
        }
    }

    private fun loadState(): PluginState =
        runCatching {
            if (!stateFile.exists()) PluginState()
            else json.decodeFromString(PluginState.serializer(), stateFile.readText())
        }.getOrElse {
            Log.w(TAG, "Failed to decode plugin state; starting empty", it)
            PluginState()
        }

    private suspend fun refreshMcpActivation(record: PluginRecord) {
        val active = record.enabled && PluginPermissions.MCP_CONNECT in record.grantedPermissions
        settingsStore.update { settings ->
            val ids = record.mcpServerIds.values.mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }.toSet()
            settings.copy(
                mcpServers = settings.mcpServers.map { server ->
                    if (server.id in ids) {
                        when (server) {
                            is McpServerConfig.SseTransportServer ->
                                server.copy(commonOptions = server.commonOptions.copy(enable = active))
                            is McpServerConfig.StreamableHTTPServer ->
                                server.copy(commonOptions = server.commonOptions.copy(enable = active))
                        }
                    } else server
                }
            )
        }
    }

    private fun reconcileMcpState() {
        val records = _plugins.value
        if (records.isEmpty()) return
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            runCatching {
                settingsStore.update { settings ->
                    settings.copy(
                        mcpServers = settings.mcpServers.map { server ->
                            val record = records.firstOrNull {
                                it.mcpServerIds.values.contains(server.id.toString())
                            } ?: return@map server
                            val active = record.enabled &&
                                PluginPermissions.MCP_CONNECT in record.grantedPermissions
                            when (server) {
                                is McpServerConfig.SseTransportServer ->
                                    server.copy(commonOptions = server.commonOptions.copy(enable = active))
                                is McpServerConfig.StreamableHTTPServer ->
                                    server.copy(commonOptions = server.commonOptions.copy(enable = active))
                            }
                        }
                    )
                }
            }.onFailure { Log.w(TAG, "Failed to reconcile plugin MCP state", it) }
        }
    }

    private fun readAndValidateManifest(packageFile: File): PluginManifest {
        ZipFile(packageFile).use { zip ->
            val entry = zip.getEntry(MANIFEST_NAME)
                ?: throw IllegalArgumentException("Plugin package is missing plugin.json.")
            if (entry.isDirectory) throw IllegalArgumentException("plugin.json must be a file.")
            val text = zip.getInputStream(entry).use { it.bufferedReader(Charsets.UTF_8).readText() }
            val manifest = json.decodeFromString(PluginManifest.serializer(), text)
            validateManifest(manifest)
            return manifest
        }
    }

    private fun validateManifest(manifest: PluginManifest) {
        require(manifest.normalizedId().isNotBlank()) { "Plugin id is required." }
        require(manifest.version.isNotBlank()) { "Plugin version is required." }
        require(manifest.name.isNotBlank()) { "Plugin name is required." }
        require(manifest.id == manifest.normalizedId()) {
            "Plugin id must contain only lowercase letters, digits, '.', '_' or '-'."
        }
        require(manifest.permissions.all { it.isNotBlank() && it.length <= 80 }) {
            "Invalid plugin permission declaration."
        }
        require(manifest.mcpServers.distinctBy { it.key }.size == manifest.mcpServers.size) {
            "Duplicate MCP server keys."
        }
        require(manifest.tools.distinctBy { it.name }.size == manifest.tools.size) {
            "Duplicate plugin tool names."
        }
        val serverKeys = manifest.mcpServers.map { it.key }.toSet()
        require(manifest.tools.all {
            it.name.matches(Regex("[a-zA-Z0-9_.-]{1,80}")) &&
                it.mcpServerKey in serverKeys
        }) {
            "Every plugin tool must reference a declared MCP server."
        }
        val dangerousHeaders = setOf("authorization", "proxy-authorization", "x-api-key", "api-key")
        require(
            manifest.mcpServers.flatMap { it.headers }
                .none { it.name.lowercase() in dangerousHeaders }
        ) {
            "Plugin packages may not embed credential headers."
        }
    }

    private fun extractZip(zipFile: File, targetRoot: File) {
        var count = 0
        var total = 0L
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                count++
                if (count > MAX_FILES) throw IllegalArgumentException("Plugin contains too many files.")
                if (entry.name.length > 512 || entry.name.startsWith("/") || entry.name.contains("..")) {
                    throw IllegalArgumentException("Unsafe plugin package path: " + entry.name)
                }
                if (entry.isDirectory) continue
                total += entry.size.coerceAtLeast(0L)
                if (total > MAX_UNCOMPRESSED_BYTES) {
                    throw IllegalArgumentException("Plugin package expands beyond the allowed size.")
                }
                val out = File(targetRoot, entry.name)
                val rootCanonical = targetRoot.canonicalFile
                if (!out.canonicalFile.path.startsWith(rootCanonical.path + File.separator)) {
                    throw IllegalArgumentException("Plugin package path escapes its root.")
                }
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun collectSkillFiles(root: File, current: File, out: MutableMap<String, String>) {
        current.listFiles().orEmpty().forEach { file ->
            if (file.isDirectory) {
                collectSkillFiles(root, file, out)
            } else if (!file.name.startsWith(".")) {
                val relative = file.relativeTo(root).invariantSeparatorsPath
                val bytes = file.readBytes()
                if (bytes.size > SkillManager.MAX_SKILL_FILE_BYTES) {
                    throw IllegalArgumentException("Plugin skill file '" + file.name + "' is too large.")
                }
                out[relative] = bytes.toString(Charsets.UTF_8)
            }
        }
    }

    private fun safeSegment(value: String): String =
        value.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(100).ifBlank { "version" }
}
