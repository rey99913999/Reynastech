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
    fun inspectorFlagsSuspiciousJavascriptPatterns() {
        val source = "const response = fetch(url); return eval(response);"
        val warnings = CustomToolSourceInspector.inspect(
            definition(kind = CustomToolKind.JAVASCRIPT, source = source)
        )
        assertNotNull(warnings.find { it.contains("fetch") })
        assertNotNull(warnings.find { it.contains("eval") })
    }

    @Test
    fun inspectorFlagsPythonShellAndFilesystemPatterns() {
        val source = "import os, shutil\\nos.system('echo test')\\nshutil.rmtree(path)"
        val warnings = CustomToolSourceInspector.inspect(
            definition(kind = CustomToolKind.PYTHON, source = source)
        )
        assertNotNull(warnings.find { it.contains("os.system") })
        assertNotNull(warnings.find { it.contains("filesystem deletion") })
    }

    @Test
    fun inspectorDoesNotWarnForSimpleCalculation() {
        assertNull(
            CustomToolSourceInspector.inspect(
                definition(kind = CustomToolKind.JAVASCRIPT, source = "return input.left + input.right;")
            ).firstOrNull()
        )
    }

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
    fun rejectsNestedCustomToolActions() {
        assertNotNull(CustomToolDefinitionValidator.validate(
            definition(
                kind = CustomToolKind.ACTION_SEQUENCE,
                source = "",
                actions = listOf(CustomToolAction(tool = "ct_other_tool")),
            )
        ))
    }

    @Test
    fun requiresMcpServerAndRemoteTool() {
        assertNotNull(CustomToolDefinitionValidator.validate(
            definition(kind = CustomToolKind.MCP, source = "", mcpServerId = "", remoteToolName = "")
        ))
        assertNotNull(CustomToolDefinitionValidator.validate(
            definition(
                kind = CustomToolKind.MCP,
                source = "",
                mcpServerId = "not-a-uuid",
                remoteToolName = "remote_tool",
            )
        ))
        assertNull(CustomToolDefinitionValidator.validate(
            definition(
                kind = CustomToolKind.MCP,
                source = "",
                mcpServerId = "6f9619ff-8b86-d011-b42d-00cf4fc964ff",
                remoteToolName = "remote_tool",
            )
        ))
    }
}
