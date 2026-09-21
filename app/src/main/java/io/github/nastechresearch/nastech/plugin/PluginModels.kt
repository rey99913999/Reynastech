package io.github.nastechresearch.nastech.plugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class PluginTrust { OFFICIAL, VERIFIED, COMMUNITY, UNKNOWN }

@Serializable
enum class PluginStatus { INSTALLED, ENABLED, DISABLED, BROKEN, UPDATE_AVAILABLE }

object PluginPermissions {
    const val SKILL_READ = "skill.read"
    const val MCP_CONNECT = "mcp.connect"
    const val NETWORK = "network"
    const val FILE_READ = "file.read"
    const val FILE_WRITE = "file.write"
    const val CREDENTIALS = "credentials"
    const val SHELL = "shell"
    const val SENSITIVE = "sensitive"

    val sensitive = setOf(CREDENTIALS, SHELL, FILE_WRITE, SENSITIVE)
}

@Serializable
data class PluginHeader(val name: String, val value: String)

@Serializable
data class PluginSkillSpec(val name: String, val path: String = "")

@Serializable
data class PluginAuthSpec(
    val type: String = "none",
    val provider: String = "",
    val account: String = "",
)

@Serializable
data class PluginMcpServerSpec(
    val key: String,
    val name: String,
    val transport: String = "streamable_http",
    val url: String,
    val headers: List<PluginHeader> = emptyList(),
    val auth: PluginAuthSpec = PluginAuthSpec(),
)

@Serializable
data class PluginToolSpec(
    val name: String,
    val description: String = "",
    val mcpServerKey: String = "",
    val remoteToolName: String = "",
    val requiredPermissions: Set<String> = emptySet(),
    val inputSchema: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class PluginContents(
    val skills: Boolean = false,
    val mcp: Boolean = false,
    val tools: Boolean = false,
    val configuration: Boolean = false,
    val authentication: Boolean = false,
)

@Serializable
data class PluginSignature(
    val algorithm: String = "",
    val keyId: String = "",
    val signature: String = "",
)

@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val developer: String = "",
    val description: String = "",
    val source: String = "",
    val license: String = "",
    val minAppVersion: String = "0.0.0",
    val requiredServices: Set<String> = emptySet(),
    val permissions: Set<String> = emptySet(),
    val contents: PluginContents = PluginContents(),
    val trust: PluginTrust = PluginTrust.UNKNOWN,
    val skills: List<PluginSkillSpec> = emptyList(),
    val mcpServers: List<PluginMcpServerSpec> = emptyList(),
    val tools: List<PluginToolSpec> = emptyList(),
    val configurationSchema: JsonObject = JsonObject(emptyMap()),
    val signature: PluginSignature? = null,
)

@Serializable
data class PluginRecord(
    val manifest: PluginManifest,
    val enabled: Boolean = false,
    val grantedPermissions: Set<String> = emptySet(),
    val config: JsonObject = JsonObject(emptyMap()),
    val installedAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val sourceLabel: String = "",
    val versionPath: String = "",
    val mcpServerIds: Map<String, String> = emptyMap(),
    val ownedSkillNames: Set<String> = emptySet(),
    val conversationIds: Set<String> = emptySet(),
    val agentPluginBindings: Map<String, Set<String>> = emptyMap(),
    val previousVersions: List<String> = emptyList(),
    val status: PluginStatus = PluginStatus.INSTALLED,
    val installError: String? = null,
)

@Serializable
data class PluginState(val plugins: List<PluginRecord> = emptyList())

data class PluginToolBinding(
    val pluginId: String,
    val tool: PluginToolSpec,
    val requiredPermissions: Set<String>,
)

fun PluginManifest.normalizedId(): String =
    id.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "-").take(80)

fun PluginManifest.isCompatible(appVersion: String): Boolean =
    compareVersions(appVersion, minAppVersion) >= 0

fun compareVersions(a: String, b: String): Int {
    fun parts(v: String) = Regex("\\d+").findAll(v).map { it.value.toLongOrNull() ?: 0L }.toList()
    val aa = parts(a)
    val bb = parts(b)
    val size = maxOf(aa.size, bb.size)
    for (i in 0 until size) {
        val av = aa.getOrElse(i) { 0L }
        val bv = bb.getOrElse(i) { 0L }
        if (av != bv) return av.compareTo(bv)
    }
    return 0
}

fun isSensitivePermission(permission: String): Boolean =
    permission in PluginPermissions.sensitive ||
        permission.contains("secret", ignoreCase = true) ||
        permission.contains("credential", ignoreCase = true) ||
        permission.contains("shell", ignoreCase = true)
