package io.github.nastechresearch.nastech.plugin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.nastechresearch.nastech.data.agent.AgentConfigRepository
import io.github.nastechresearch.nastech.data.repository.ConversationRepository
import io.github.nastechresearch.nastech.plugin.PluginManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

data class PluginConversationOption(
    val id: String,
    val title: String,
)

class PluginManagerViewModel(
    private val pluginManager: PluginManager,
    private val conversationRepository: ConversationRepository,
    private val agentConfigRepository: AgentConfigRepository,
) : ViewModel() {
    val plugins = pluginManager.plugins.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val conversations = conversationRepository.getAllConversationOptions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun installUrl(url: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val result = pluginManager.installFromUrl(url)
            onResult(result.fold({ "Installed " + it.manifest.name }, { it.message ?: "Install failed" }))
        }
    }

    fun installUri(uri: android.net.Uri, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val result = pluginManager.installFromUri(uri)
            onResult(result.fold({ "Installed " + it.manifest.name }, { it.message ?: "Install failed" }))
        }
    }

    fun setEnabled(id: String, enabled: Boolean, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            pluginManager.setEnabled(id, enabled).onFailure { onError(it.message ?: "Failed") }
        }
    }

    fun setPermission(id: String, permission: String, enabled: Boolean, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            pluginManager.setPermission(id, permission, enabled).onFailure { onError(it.message ?: "Failed") }
        }
    }

    fun bindConversation(id: String, conversationId: String, bound: Boolean, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            pluginManager.setConversationBinding(id, conversationId, bound)
                .onFailure { onError(it.message ?: "Failed") }
        }
    }

    fun bindAgent(
        id: String,
        conversationId: String,
        agentId: String,
        allowed: Boolean,
        onError: (String) -> Unit = {},
    ) {
        viewModelScope.launch {
            pluginManager.setAgentBinding(id, conversationId, agentId, allowed)
                .onFailure { onError(it.message ?: "Failed") }
        }
    }

    fun agentsForConversation(
        conversationId: String,
        onResult: (List<Pair<String, String>>) -> Unit,
    ) {
        viewModelScope.launch {
            val config = agentConfigRepository.get(conversationId)
            onResult(config.agents.filter { it.enabled }.map { it.id to it.name })
        }
    }

    fun authorize(id: String, context: android.content.Context, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            pluginManager.authorize(id, context).onFailure { onError(it.message ?: "Authorization failed") }
        }
    }

    fun cancelAuthorization(id: String, onError: (String) -> Unit = {}) {
        pluginManager.cancelAuthorization(id).onFailure { onError(it.message ?: "Authorization failed") }
    }

    fun saveConfig(id: String, rawJson: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val parsed = runCatching {
                json.parseToJsonElement(rawJson) as? JsonObject
                    ?: throw IllegalArgumentException("Configuration must be a JSON object.")
            }
            parsed.onSuccess {
                pluginManager.setConfig(id, it)
                    .onSuccess { onResult("Configuration saved") }
                    .onFailure { onResult(it.message ?: "Save failed") }
            }.onFailure { onResult(it.message ?: "Invalid JSON") }
        }
    }

    fun uninstall(id: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            pluginManager.uninstall(id)
                .onSuccess { onResult("Plugin uninstalled") }
                .onFailure { onResult(it.message ?: "Uninstall failed") }
        }
    }

    fun rollback(id: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            pluginManager.rollback(id)
                .onSuccess { onResult("Rollback requested") }
                .onFailure { onResult(it.message ?: "Rollback failed") }
        }
    }

    fun isBound(recordId: String, conversationId: String): Boolean =
        plugins.value.firstOrNull { it.manifest.normalizedId() == recordId }
            ?.conversationIds
            ?.contains(conversationId) == true

    fun isAgentAllowed(recordId: String, conversationId: String, agentId: String): Boolean {
        val record = plugins.value.firstOrNull { it.manifest.normalizedId() == recordId } ?: return false
        return record.agentPluginBindings[conversationId + "/" + agentId]
            .orEmpty()
            .contains(record.manifest.id)
    }

    fun configText(id: String): String {
        val record = plugins.value.firstOrNull { it.manifest.normalizedId() == id } ?: return "{}"
        return json.encodeToString(JsonObject.serializer(), record.config)
    }

    fun pluginId(recordId: String): String? =
        plugins.value.firstOrNull { it.manifest.normalizedId() == recordId }?.manifest?.id
}
