package io.github.nastechresearch.nastech.data.ai.tools

import io.github.nastechresearch.nastech.data.datastore.SettingsStore
import io.github.nastechresearch.nastech.data.execution.ExecutionToolRegistry
import io.github.nastechresearch.nastech.data.execution.ToolCategory
import io.github.nastechresearch.nastech.data.execution.ToolRiskLevel
import io.github.nastechresearch.nastech.data.model.Assistant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/**
 * Persistent approval policy owned by a single Assistant/tool identity.
 *
 * The record lives inside [Assistant], so assistant ownership is unambiguous and the
 * effective key is always the enclosing Assistant id + [toolId].
 */
@Serializable
enum class AssistantToolPermissionPolicy {
    ALWAYS_ALLOW,
    ASK,
    DENY,
}

@Serializable
enum class AssistantToolDefaultPolicy {
    ASK,
    ALLOW_LOW_RISK,
    DENY_NEW,
}

@Serializable
data class AssistantToolPermission(
    val toolId: String,
    val policy: AssistantToolPermissionPolicy = AssistantToolPermissionPolicy.ASK,
    val scope: String = UNRESTRICTED_SCOPE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
) {
    companion object {
        const val UNRESTRICTED_SCOPE = "unrestricted"
    }
}

data class ToolPermissionDecision(
    val action: Action,
    val reason: String? = null,
    val metadata: io.github.nastechresearch.nastech.data.execution.ToolMetadata? = null,
) {
    enum class Action {
        ALLOW,
        ASK,
        DENY,
    }
}

/**
 * Runtime-only task grants. These are intentionally not persisted in DataStore or Room:
 * a process restart loses them, which is the safe fallback for resumable tasks.
 */
object TaskToolApprovalGrants {
    private data class Key(
        val taskId: String,
        val assistantId: String,
        val toolId: String,
    )

    private val grants = java.util.concurrent.ConcurrentHashMap.newKeySet<Key>()

    fun grant(taskId: String, assistantId: Uuid, toolId: String) {
        if (taskId.isBlank() || toolId.isBlank()) return
        grants += Key(taskId, assistantId.toString(), toolId)
    }

    fun isGranted(taskId: String?, assistantId: Uuid, toolId: String): Boolean {
        if (taskId.isNullOrBlank() || toolId.isBlank()) return false
        return grants.contains(Key(taskId, assistantId.toString(), toolId))
    }

    fun clearTask(taskId: String) {
        grants.removeIf { it.taskId == taskId }
    }

    fun clearAll() {
        grants.clear()
    }

    fun size(): Int = grants.size
}

/**
 * Assistant-scoped persistence facade. It deliberately stores only per-Assistant state;
 * there is no global tool permission list here.
 */
class AssistantToolPermissionRepository(
    private val settingsStore: SettingsStore,
    private val registryCatalog: AssistantToolRegistryCatalog,
) {
    fun observeAssistant(assistantId: Uuid): Flow<Assistant?> =
        settingsStore.settingsFlow.map { settings ->
            settings.assistants.firstOrNull { it.id == assistantId }
        }

    suspend fun setDefaultPolicy(
        assistantId: Uuid,
        policy: AssistantToolDefaultPolicy,
    ) {
        updateAssistant(assistantId) { it.copy(toolDefaultPolicy = policy) }
    }

    suspend fun setToolPolicy(
        assistantId: Uuid,
        toolId: String,
        policy: AssistantToolPermissionPolicy,
    ) {
        require(toolId.isNotBlank()) { "toolId cannot be blank" }

        // Backend safety: NO_ALWAYS_ALLOW is not a UI-only restriction. Resolve the canonical
        // registry identity before persisting so callers cannot bypass the hidden button by
        // directly writing ALWAYS_ALLOW for a forbidden tool.
        if (policy == AssistantToolPermissionPolicy.ALWAYS_ALLOW) {
            val settings = settingsStore.settingsFlow.first()
            val assistant = settings.assistants.firstOrNull { it.id == assistantId }
                ?: throw IllegalArgumentException("Assistant not found")
            val registry = registryCatalog.build(
                settings = settings,
                assistant = assistant,
                conversationId = assistant.id,
            )
            val metadata = registry.allMetadata().firstOrNull { it.identity.stableId == toolId }
                ?: throw IllegalArgumentException("Tool is not registered for this Assistant")
            require(ToolApprovalDefaults.allowsAlwaysAllow(metadata.modelName)) {
                "Always Allow is forbidden"
            }
        }

        val now = System.currentTimeMillis()
        updateAssistant(assistantId) { assistant ->
            val existing = assistant.toolPermissionOverrides.firstOrNull { it.toolId == toolId }
            val next = AssistantToolPermission(
                toolId = toolId,
                policy = policy,
                scope = existing?.scope ?: AssistantToolPermission.UNRESTRICTED_SCOPE,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
            assistant.copy(
                toolPermissionOverrides = assistant.toolPermissionOverrides
                    .filterNot { it.toolId == toolId } + next
            )
        }
    }

    suspend fun resetToolPolicy(
        assistantId: Uuid,
        toolId: String,
    ) {
        updateAssistant(assistantId) { assistant ->
            assistant.copy(
                toolPermissionOverrides = assistant.toolPermissionOverrides
                    .filterNot { it.toolId == toolId }
            )
        }
    }

    suspend fun resetAll(
        assistantId: Uuid,
    ) {
        updateAssistant(assistantId) { assistant ->
            assistant.copy(toolPermissionOverrides = emptyList())
        }
    }

    suspend fun resetCategory(
        assistantId: Uuid,
        toolIds: Set<String>,
    ) {
        if (toolIds.isEmpty()) return
        updateAssistant(assistantId) { assistant ->
            assistant.copy(
                toolPermissionOverrides = assistant.toolPermissionOverrides
                    .filterNot { it.toolId in toolIds }
            )
        }
    }

    suspend fun setToolEnabled(
        assistantId: Uuid,
        toolId: String,
        enabled: Boolean,
    ) {
        require(toolId.isNotBlank()) { "toolId cannot be blank" }
        updateAssistant(assistantId) { assistant ->
            val nextDisabled = if (enabled) {
                assistant.disabledToolIds - toolId
            } else {
                assistant.disabledToolIds + toolId
            }
            assistant.copy(disabledToolIds = nextDisabled)
        }
    }

    private suspend fun updateAssistant(
        assistantId: Uuid,
        transform: (Assistant) -> Assistant,
    ) {
        settingsStore.update { current ->
            val assistant = current.assistants.firstOrNull { it.id == assistantId }
                ?: return@update current
            val next = transform(assistant)
            current.copy(
                assistants = current.assistants.map {
                    if (it.id == assistantId) next else it
                }
            )
        }
    }
}

/**
 * Single effective permission resolver used by generation and local structured execution.
 *
 * HARDLINE and enabled-state are safety floors. Explicit DENY remains stronger than a
 * task grant. A task grant is deliberately checked before the Assistant default so that
 * "Allow for this task" can unblock an otherwise ASK/DENY_NEW tool without changing the
 * persistent policy.
 */
class AssistantToolPermissionResolver {
    suspend fun decide(
        assistant: Assistant,
        registry: ExecutionToolRegistry,
        toolName: String,
        args: JsonObject,
        taskId: String? = null,
    ): ToolPermissionDecision {
        val metadata = registry.metadata(toolName)
        val identity = registry.identityFor(toolName)
        val toolId = identity?.stableId ?: toolName

        val hardlineReason = HardlineCommandGuard.checkToolParsed(toolName, args)
        if (hardlineReason != null) {
            return ToolPermissionDecision(
                ToolPermissionDecision.Action.DENY,
                "blocked by safety floor (hardline): $hardlineReason",
                metadata,
            )
        }

        if (identity != null && toolId in assistant.disabledToolIds) {
            return ToolPermissionDecision(
                ToolPermissionDecision.Action.DENY,
                "tool is disabled for this Assistant",
                metadata,
            )
        }

        val requiresApproval = registry.requiresApproval(toolName, args)
        val override = assistant.toolPermissionOverrides.firstOrNull { it.toolId == toolId }
        when (override?.policy) {
            AssistantToolPermissionPolicy.DENY -> {
                return ToolPermissionDecision(
                    ToolPermissionDecision.Action.DENY,
                    "denied by this Assistant's tool policy",
                    metadata,
                )
            }

            AssistantToolPermissionPolicy.ALWAYS_ALLOW -> {
                if (canPersistAlwaysAllow(toolName)) {
                    return ToolPermissionDecision(
                        ToolPermissionDecision.Action.ALLOW,
                        metadata = metadata,
                    )
                }
                // A stale persisted value must never revive a forbidden Always Allow grant.
                return ToolPermissionDecision(
                    ToolPermissionDecision.Action.ASK,
                    "this tool cannot use Always Allow",
                    metadata,
                )
            }

            AssistantToolPermissionPolicy.ASK,
            null -> Unit
        }

        if (taskId != null && identity != null &&
            TaskToolApprovalGrants.isGranted(taskId, assistant.id, toolId)
        ) {
            return ToolPermissionDecision(
                ToolPermissionDecision.Action.ALLOW,
                "allowed for this task",
                metadata,
            )
        }

        if (override?.policy == AssistantToolPermissionPolicy.ASK) {
            return ToolPermissionDecision(
                ToolPermissionDecision.Action.ASK,
                "this Assistant is configured to ask for this tool",
                metadata,
            )
        }

        return when (assistant.toolDefaultPolicy) {
            AssistantToolDefaultPolicy.ASK ->
                if (!requiresApproval) {
                    ToolPermissionDecision(
                        ToolPermissionDecision.Action.ALLOW,
                        "tool does not require approval",
                        metadata,
                    )
                } else {
                    ToolPermissionDecision(
                        ToolPermissionDecision.Action.ASK,
                        "Assistant default policy is Ask",
                        metadata,
                    )
                }

            AssistantToolDefaultPolicy.ALLOW_LOW_RISK -> {
                if (!requiresApproval || metadata?.risk == ToolRiskLevel.LOW) {
                    ToolPermissionDecision(
                        ToolPermissionDecision.Action.ALLOW,
                        if (!requiresApproval) {
                            "tool does not require approval"
                        } else {
                            "low-risk tool allowed by Assistant default"
                        },
                        metadata,
                    )
                } else {
                    ToolPermissionDecision(
                        ToolPermissionDecision.Action.ASK,
                        "tool risk is not LOW",
                        metadata,
                    )
                }
            }

            AssistantToolDefaultPolicy.DENY_NEW ->
                if (!requiresApproval) {
                    ToolPermissionDecision(
                        ToolPermissionDecision.Action.ALLOW,
                        "tool does not require approval",
                        metadata,
                    )
                } else {
                    ToolPermissionDecision(
                        ToolPermissionDecision.Action.DENY,
                        "tool has no explicit permission under the Assistant default policy",
                        metadata,
                    )
                }
        }
    }

    fun canPersistAlwaysAllow(toolName: String): Boolean =
        ToolApprovalDefaults.allowsAlwaysAllow(toolName)

    fun approvalKey(registry: ExecutionToolRegistry, toolName: String): String =
        registry.identityFor(toolName)?.stableId ?: toolName

    fun categoryFor(
        registry: ExecutionToolRegistry,
        toolName: String,
    ): ToolCategory = registry.metadata(toolName)?.category ?: ToolCategory.OTHER
}
