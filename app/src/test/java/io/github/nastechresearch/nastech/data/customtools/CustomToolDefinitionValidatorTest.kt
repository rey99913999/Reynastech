package io.github.nastechresearch.nastech.data.customtools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CustomToolDefinitionValidatorTest {
    private fun definition(
        kind: CustomToolKind = CustomToolKind.JAVASCRIPT,
        source: String = "return 1;",
        actions: List<CustomToolAction> = emptyList(),
        mcpServerId: String = "",
        remoteToolName: String = "",
        id: String = "sample-tool",
    ) = CustomToolDefinition(
        id = id,
        name = "sample_tool",
        description = "A test custom tool.",
        kind = kind,
        inputSchema = buildJsonObject { put("type", "object") },
        actions = actions,
        source = source,
        mcpServerId = mcpServerId,
        remoteToolName = remoteToolName,
    )

    @Test
    fun acceptsJavascriptDefinition() {
        assertNull(CustomToolDefinitionValidator.validate(definition()))
    }

    @Test
    fun acceptsPythonDefinition() {
        assertNull(CustomToolDefinitionValidator.validate(
            definition(kind = CustomToolKind.PYTHON, source = "print('hello')")
        ))
    }

    @Test
    fun rejectsPathLikeIds() {
        assertNotNull(CustomToolDefinitionValidator.validate(definition(id = "../unsafe")))
    }

    @Test
    fun rejectsScriptlessTools() {
        assertNotNull(CustomToolDefinitionValidator.validate(
            definition(kind = CustomToolKind.JAVASCRIPT, source = " ")
        ))
    }

    @Test
    fun rejectsActionSequencesWithoutSteps() {
        assertNotNull(CustomToolDefinitionValidator.validate(
            definition(kind = CustomToolKind.ACTION_SEQUENCE, source = "", actions = emptyList())
        ))
    }

    @Test
    fun acceptsActionSequenceWithNamedTool() {
        assertNull(CustomToolDefinitionValidator.validate(
            definition(
                kind = CustomToolKind.ACTION_SEQUENCE,
                source = "",
                actions = listOf(CustomToolAction(tool = "device_info")),
            )
        ))
    }

    @Test
    fun requiresMcpServerAndRemoteTool() {
        assertNotNull(CustomToolDefinitionValidator.validate(
            definition(kind = CustomToolKind.MCP, source = "", mcpServerId = "", remoteToolName = "")
        ))
        assertNull(CustomToolDefinitionValidator.validate(
            definition(
                kind = CustomToolKind.MCP,
                source = "",
                mcpServerId = "configured-server",
                remoteToolName = "remote_tool",
            )
        ))
    }
}
