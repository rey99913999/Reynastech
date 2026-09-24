package io.github.nastechresearch.nastech.data.ai.tools

import io.github.nastechresearch.nastech.data.ai.tools.createSearchTools
import io.github.nastechresearch.nastech.data.ai.tools.local.*
import io.github.nastechresearch.nastech.data.datastore.Settings
import io.github.nastechresearch.nastech.data.datastore.findModelById
import io.github.nastechresearch.nastech.data.execution.ExecutionToolRegistry
import io.github.nastechresearch.nastech.data.execution.ToolSourceHint
import io.github.nastechresearch.nastech.data.execution.ToolSourceKind
import io.github.nastechresearch.nastech.data.files.SkillManager
import io.github.nastechresearch.nastech.data.repository.WorkspaceRepository
import io.github.nastechresearch.nastech.data.ai.mcp.McpManager
import io.github.nastechresearch.nastech.plugin.PluginManager
import io.github.nastechresearch.nastech.data.ai.tools.createSkillTools
import io.github.nastechresearch.nastech.data.model.Assistant
import me.rerere.workspace.WorkspaceShellStatus
import io.github.nastechresearch.nastech.data.ai.tools.createWorkspaceTools
import me.rerere.ai.provider.Modality
import me.rerere.ai.core.Tool
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.Uuid

/**
 * Builds a permissions-center registry from the same tool-producing components used by chat.
 *
 * The registry itself remains the canonical catalogue/identity layer. This class only composes
 * the currently available tools for the selected Assistant; it does not store a second list.
 */
class AssistantToolRegistryCatalog(
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val pluginManager: PluginManager,
    private val workspaceRepository: WorkspaceRepository,
) {
    suspend fun build(
        settings: Settings,
        assistant: Assistant,
        conversationId: Uuid = assistant.id,
        workspaceCwd: String? = null,
    ): ExecutionToolRegistry {
        val sourceHints = mutableListOf<ToolSourceHint>()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
        val tools = buildList {
            if (assistant.enableWebSearch) {
                addAll(createSearchTools(settings))
            }

            val invocationContext = ToolInvocationContext(
                callerAssistantId = assistant.id.toString(),
                callerConversationId = conversationId.toString(),
                isHeadless = false,
                modelCanSeeImages = model?.let { Modality.IMAGE in it.inputModalities } == true,
            )
            addAll(
                localTools.getTools(
                    assistant.localTools,
                    invocationContext,
                    onSourceHint = { sourceHints += it },
                )
            )

            val workspaceId = assistant.workspaceId?.toString()
            if (!workspaceId.isNullOrBlank()) {
                val workspace = workspaceRepository.getById(workspaceId)
                if (workspace?.shellStatus == WorkspaceShellStatus.READY.name) {
                    addAll(createWorkspaceTools(workspaceId, workspaceRepository, workspaceCwd))
                }
            }

            if (assistant.enabledSkills.isNotEmpty()) {
                addAll(
                    createSkillTools(
                        enabledSkills = assistant.enabledSkills,
                        allSkills = skillManager.listSkills(),
                        skillManager = skillManager,
                    )
                )
            }

            mcpManager.getAllAvailableTools().forEach { (serverId, serverName, tool) ->
                if (serverName.isBlank() || !serverName.all { it.isLetterOrDigit() }) return@forEach
                val serverSlug = serverId.toString().take(8).replace("-", "")
                val modelName = "mcp__" + serverSlug + "_" + serverName + "__" + tool.name
                add(
                    Tool(
                        name = modelName,
                        description = tool.description ?: "",
                        parameters = { tool.inputSchema },
                        needsApproval = {
                            ToolApprovalDefaults.requiresApproval(modelName) || tool.needsApproval
                        },
                        execute = { args ->
                            mcpManager.callTool(serverId, tool.name, args.jsonObject)
                        },
                    )
                )
            }
        }

        return ExecutionToolRegistry(
            tools = tools,
            sourceHints = sourceHints,
        )
    }
}
