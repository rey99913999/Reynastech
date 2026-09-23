package io.github.nastechresearch.nastech.ui.pages.assistant.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.nastechresearch.nastech.data.ai.tools.AssistantToolPermissionRepository
import io.github.nastechresearch.nastech.data.ai.tools.AssistantToolPermissionPolicy
import io.github.nastechresearch.nastech.data.ai.tools.AssistantToolDefaultPolicy
import io.github.nastechresearch.nastech.data.ai.tools.AssistantToolRegistryCatalog
import io.github.nastechresearch.nastech.data.datastore.SettingsStore
import io.github.nastechresearch.nastech.data.execution.ExecutionToolRegistry
import io.github.nastechresearch.nastech.data.execution.ToolCategory
import io.github.nastechresearch.nastech.data.execution.ToolMetadata
import io.github.nastechresearch.nastech.data.execution.ToolRiskLevel
import io.github.nastechresearch.nastech.data.execution.ToolSourceKind
import io.github.nastechresearch.nastech.data.model.Assistant
import io.github.nastechresearch.nastech.data.preferences.ToolApprovalPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class AssistantToolPermissionsVM(
    private val id: String,
    private val settingsStore: SettingsStore,
    private val repository: AssistantToolPermissionRepository,
    private val catalog: AssistantToolRegistryCatalog,
    private val legacyPreferences: ToolApprovalPreferences,
) : ViewModel() {
    private val assistantId = Uuid.parse(id)
    private val registryState = MutableStateFlow<ExecutionToolRegistry?>(null)
    private val queryState = MutableStateFlow("")
    private val categoryState = MutableStateFlow<ToolCategory?>(null)
    private val riskState = MutableStateFlow<ToolRiskLevel?>(null)
    private val sourceState = MutableStateFlow<ToolSourceKind?>(null)
    private val policyState = MutableStateFlow<AssistantToolPermissionPolicy?>(null)

    val assistant: StateFlow<Assistant?> = repository.observeAssistant(assistantId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val registry: StateFlow<ExecutionToolRegistry?> = registryState

    val query: StateFlow<String> = queryState
    val selectedCategory: StateFlow<ToolCategory?> = categoryState
    val selectedRisk: StateFlow<ToolRiskLevel?> = riskState
    val selectedSource: StateFlow<ToolSourceKind?> = sourceState
    val selectedPolicy: StateFlow<AssistantToolPermissionPolicy?> = policyState

    val legacyResetCount = legacyPreferences.legacyResetCountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val filteredMetadata: StateFlow<List<ToolMetadata>> = combine(
        registryState,
        queryState,
        categoryState,
        riskState,
        sourceState,
        policyState,
        assistant,
    ) { registry, query, category, risk, source, policy, currentAssistant ->
        val tools = registry?.allMetadata().orEmpty()
        if (currentAssistant == null) return@combine emptyList()
        tools.filter { meta ->
            val searchable = buildString {
                append(meta.modelName)
                append(" ")
                append(meta.identity.logicalName)
                append(" ")
                append(meta.description)
                append(" ")
                append(meta.keywords.joinToString(" "))
                append(" ")
                append(meta.capabilities.joinToString(" "))
            }.lowercase()
            val qMatch = query.isBlank() || searchable.contains(query.trim().lowercase())
            val categoryMatch = category == null || meta.category == category
            val riskMatch = risk == null || meta.risk == risk
            val sourceMatch = source == null || meta.identity.source == source
            val policyMatch = policy == null || effectivePolicy(currentAssistant, meta) == policy
            qMatch && categoryMatch && riskMatch && sourceMatch && policyMatch
        }.sortedWith(compareBy<ToolMetadata>({ it.category.displayName }, { it.modelName.lowercase() }))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            legacyPreferences.migrateLegacyGlobalState()
            refreshRegistry()
        }
    }

    fun setQuery(value: String) { queryState.value = value }
    fun setCategory(value: ToolCategory?) { categoryState.value = value }
    fun setRisk(value: ToolRiskLevel?) { riskState.value = value }
    fun setSource(value: ToolSourceKind?) { sourceState.value = value }
    fun setPolicy(value: AssistantToolPermissionPolicy?) { policyState.value = value }

    fun setDefaultPolicy(policy: AssistantToolDefaultPolicy) {
        viewModelScope.launch {
            repository.setDefaultPolicy(assistantId, policy)
        }
    }

    fun setToolPolicy(meta: ToolMetadata, policy: AssistantToolPermissionPolicy) {
        viewModelScope.launch {
            repository.setToolPolicy(assistantId, meta.identity.stableId, policy)
        }
    }

    fun resetTool(meta: ToolMetadata) {
        viewModelScope.launch {
            repository.resetToolPolicy(assistantId, meta.identity.stableId)
        }
    }

    fun resetAll() {
        viewModelScope.launch { repository.resetAll(assistantId) }
    }

    fun resetCategory(category: ToolCategory) {
        viewModelScope.launch {
            val ids = registryState.value?.allMetadata()
                .orEmpty()
                .filter { it.category == category }
                .map { it.identity.stableId }
                .toSet()
            repository.resetCategory(assistantId, ids)
        }
    }

    fun setToolEnabled(meta: ToolMetadata, enabled: Boolean) {
        viewModelScope.launch {
            repository.setToolEnabled(assistantId, meta.identity.stableId, enabled)
        }
    }

    fun dismissLegacyNotice() {
        viewModelScope.launch { legacyPreferences.dismissLegacyResetNotice() }
    }

    fun effectivePolicyFor(meta: ToolMetadata): AssistantToolPermissionPolicy =
        assistant.value?.let { effectivePolicy(it, meta) } ?: AssistantToolPermissionPolicy.ASK

    fun overrideFor(meta: ToolMetadata): AssistantToolPermissionPolicy? =
        assistant.value?.toolPermissionOverrides
            ?.firstOrNull { it.toolId == meta.identity.stableId }
            ?.policy

    private fun effectivePolicy(
        assistant: Assistant,
        meta: ToolMetadata,
    ): AssistantToolPermissionPolicy {
        assistant.toolPermissionOverrides
            .firstOrNull { it.toolId == meta.identity.stableId }
            ?.let { return it.policy }

        return when (assistant.toolDefaultPolicy) {
            AssistantToolDefaultPolicy.ASK -> AssistantToolPermissionPolicy.ASK
            AssistantToolDefaultPolicy.DENY_NEW -> AssistantToolPermissionPolicy.DENY
            AssistantToolDefaultPolicy.ALLOW_LOW_RISK ->
                if (meta.risk == ToolRiskLevel.LOW) {
                    AssistantToolPermissionPolicy.ALWAYS_ALLOW
                } else {
                    AssistantToolPermissionPolicy.ASK
                }
        }
    }

    private suspend fun refreshRegistry() {
        val settings = settingsStore.settingsFlow.first()
        val currentAssistant = settings.assistants.firstOrNull { it.id == assistantId } ?: return
        registryState.value = catalog.build(
            assistant = currentAssistant,
            conversationId = currentAssistant.id,
        )
    }
}
