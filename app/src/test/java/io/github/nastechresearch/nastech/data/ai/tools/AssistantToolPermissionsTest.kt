package io.github.nastechresearch.nastech.data.ai.tools

import io.github.nastechresearch.nastech.data.execution.ExecutionToolRegistry
import io.github.nastechresearch.nastech.data.execution.ToolCategory
import io.github.nastechresearch.nastech.data.execution.ToolRiskLevel
import io.github.nastechresearch.nastech.data.execution.ToolSourceHint
import io.github.nastechresearch.nastech.data.execution.ToolSourceKind
import io.github.nastechresearch.nastech.data.model.Assistant
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantToolPermissionsTest {
    private fun tool(
        name: String,
        needsApproval: Boolean = true,
    ): Tool = Tool(
        name = name,
        description = name,
        parameters = { me.rerere.ai.core.InputSchema.Obj(properties = buildJsonObject {}, required = emptyList()) },
        needsApproval = { needsApproval },
        execute = { emptyList() },
    )

    private fun registry(
        tool: Tool,
        risk: ToolRiskLevel = ToolRiskLevel.HIGH,
        source: ToolSourceKind = ToolSourceKind.BUILTIN,
        sourceId: String = "builtin",
    ): ExecutionToolRegistry =
        ExecutionToolRegistry(
            tools = listOf(tool),
            sourceHints = listOf(
                ToolSourceHint(
                    tool = tool,
                    source = source,
                    sourceId = sourceId,
                    category = ToolCategory.FILES,
                    risk = risk,
                )
            ),
        ).also { it.loadTools(listOf(tool.name)) }

    @Test
    fun alwaysAllowIsolatedPerAssistant() {
        val tool = tool("write_text_file")
        val registry = registry(tool)
        val assistantA = Assistant(id = Uuid.random()).copy(
            toolPermissionOverrides = listOf(
                AssistantToolPermission(
                    toolId = registry.identityFor(tool.name)!!.stableId,
                    policy = AssistantToolPermissionPolicy.ALWAYS_ALLOW,
                )
            )
        )
        val assistantB = Assistant(id = Uuid.random())
        val resolver = AssistantToolPermissionResolver()

        val a = kotlinx.coroutines.runBlocking {
            resolver.decide(assistantA, registry, tool.name, buildJsonObject {})
        }
        val b = kotlinx.coroutines.runBlocking {
            resolver.decide(assistantB, registry, tool.name, buildJsonObject {})
        }

        assertEquals(ToolPermissionDecision.Action.ALLOW, a.action)
        assertEquals(ToolPermissionDecision.Action.ASK, b.action)
    }

    @Test
    fun allowForTaskSurvivesSecondCallButExpiresAfterTaskClear() = kotlinx.coroutines.runBlocking {
        val tool = tool("write_text_file")
        val registry = registry(tool)
        val assistant = Assistant(id = Uuid.random())
        val resolver = AssistantToolPermissionResolver()
        val taskId = "task-04"

        TaskToolApprovalGrants.grant(
            taskId = taskId,
            assistantId = assistant.id,
            toolId = registry.identityFor(tool.name)!!.stableId,
        )

        assertTrue(TaskToolApprovalGrants.isGranted(taskId, assistant.id, registry.identityFor(tool.name)!!.stableId))
        assertEquals(
            ToolPermissionDecision.Action.ALLOW,
            resolver.decide(assistant, registry, tool.name, buildJsonObject {}, taskId).action,
        )

        TaskToolApprovalGrants.clearTask(taskId)

        assertFalse(TaskToolApprovalGrants.isGranted(taskId, assistant.id, registry.identityFor(tool.name)!!.stableId))
        assertEquals(
            ToolPermissionDecision.Action.ASK,
            resolver.decide(assistant, registry, tool.name, buildJsonObject {}, taskId).action,
        )
    }

    @Test
    fun disabledToolCannotRunEvenWhenAlwaysAllowed() = kotlinx.coroutines.runBlocking {
        val tool = tool("write_text_file")
        val registry = registry(tool)
        val toolId = registry.identityFor(tool.name)!!.stableId
        val assistant = Assistant(id = Uuid.random()).copy(
            toolPermissionOverrides = listOf(
                AssistantToolPermission(toolId, AssistantToolPermissionPolicy.ALWAYS_ALLOW)
            ),
            disabledToolIds = setOf(toolId),
        )
        val result = AssistantToolPermissionResolver().decide(
            assistant = assistant,
            registry = registry,
            toolName = tool.name,
            args = buildJsonObject {},
        )
        assertEquals(ToolPermissionDecision.Action.DENY, result.action)
    }

    @Test
    fun nativeAndPluginSameNameDoNotSharePolicy() = kotlinx.coroutines.runBlocking {
        val native = tool("delete_file")
        val plugin = tool("delete_file")
        val registry = ExecutionToolRegistry(
            tools = listOf(native, plugin),
            sourceHints = listOf(
                ToolSourceHint(
                    tool = native,
                    source = ToolSourceKind.BUILTIN,
                    sourceId = "builtin",
                    category = ToolCategory.FILES,
                    risk = ToolRiskLevel.HIGH,
                ),
                ToolSourceHint(
                    tool = plugin,
                    source = ToolSourceKind.PLUGIN,
                    sourceId = "demo-plugin",
                    category = ToolCategory.MCP_PLUGIN,
                    risk = ToolRiskLevel.HIGH,
                ),
            ),
        ).also { it.loadTools(it.names().toList()) }

        val nativeId = registry.allMetadata().first { it.identity.source == ToolSourceKind.BUILTIN }.identity.stableId
        val pluginId = registry.allMetadata().first { it.identity.source == ToolSourceKind.PLUGIN }.identity.stableId
        assertTrue(nativeId != pluginId)

        val assistant = Assistant(id = Uuid.random()).copy(
            toolPermissionOverrides = listOf(
                AssistantToolPermission(nativeId, AssistantToolPermissionPolicy.ALWAYS_ALLOW)
            )
        )
        val pluginName = registry.allMetadata().first { it.identity.source == ToolSourceKind.PLUGIN }.modelName
        val result = AssistantToolPermissionResolver().decide(
            assistant = assistant,
            registry = registry,
            toolName = pluginName,
            args = buildJsonObject {},
        )
        assertEquals(ToolPermissionDecision.Action.ASK, result.action)
    }
}
