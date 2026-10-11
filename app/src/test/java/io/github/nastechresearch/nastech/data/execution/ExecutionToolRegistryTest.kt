package io.github.nastechresearch.nastech.data.execution

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionToolRegistryTest {

    private fun tool(
        name: String,
        description: String,
        needsApproval: Boolean = false,
        field: String = "value",
    ): Tool = Tool(
        name = name,
        description = description,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put(field, buildJsonObject {
                        put("type", "string")
                        put("description", "input for $name")
                    })
                },
                required = listOf(field),
            )
        },
        needsApproval = { needsApproval },
        execute = { emptyList() },
    )

    @Test
    fun metadataContainsCanonicalIdentityCategoryRiskAndSideEffects() {
        val read = tool("read_file", "Read UTF-8 text from a file.")
        val registry = ExecutionToolRegistry(
            tools = listOf(read),
            sourceHints = listOf(
                ToolSourceHint(
                    tool = read,
                    source = ToolSourceKind.BUILTIN,
                    sourceId = "builtin",
                    category = ToolCategory.FILES,
                    risk = ToolRiskLevel.LOW,
                    keywords = setOf("read", "file", "text"),
                )
            )
        )

        val metadata = registry.metadata("read_file")
        assertNotNull(metadata)
        assertEquals(ToolSourceKind.BUILTIN, metadata!!.identity.source)
        assertEquals("builtin", metadata.identity.sourceId)
        assertEquals(ToolCategory.FILES, metadata.category)
        assertEquals(ToolRiskLevel.LOW, metadata.risk)
        assertTrue(metadata.sideEffects.isEmpty())
        assertEquals(listOf("value:string"), metadata.inputSummary)
        assertEquals(listOf("value"), metadata.requiredFields)
    }

    @Test
    fun highRiskMetadataIsExplicitAndApprovalComesFromTheRegisteredTool() {
        val destructive = tool(
            name = "delete_file",
            description = "Delete a file from local storage.",
            needsApproval = true,
        )
        val registry = ExecutionToolRegistry(
            tools = listOf(destructive),
            sourceHints = listOf(
                ToolSourceHint(
                    tool = destructive,
                    source = ToolSourceKind.BUILTIN,
                    sourceId = "builtin",
                    category = ToolCategory.FILES,
                    risk = ToolRiskLevel.HIGH,
                )
            )
        )

        val metadata = registry.metadata("delete_file")!!
        assertEquals(ToolRiskLevel.HIGH, metadata.risk)
        assertTrue("tool_approval" in metadata.permissions)
        assertTrue(metadata.sideEffects.isNotEmpty())
        assertTrue(registry.requiresApproval("delete_file"))
    }

    @Test
    fun categoriesContainExpectedToolsWithoutRegisteringDisabledOnes() {
        val fileTool = tool("read_file", "Read file text.")
        val browserTool = tool("browser_get_text", "Read text from the browser.")
        val registry = ExecutionToolRegistry(listOf(fileTool))

        val categories = registry.discoverCapabilities()
        assertTrue(categories.any { it.category == ToolCategory.FILES })
        assertFalse(categories.any { it.category == ToolCategory.BROWSER })
        assertNotNull(registry.find("read_file"))
        assertNull(registry.find("browser_get_text"))
    }

    @Test
    fun naturalLanguageDiscoveryReturnsRelevantCandidates() {
        val readFile = tool("read_file", "Read text from a local file.")
        val writeFile = tool("write_text_file", "Write text into a local file.")
        val browser = tool("browser_get_text", "Read text from the current browser page.")
        val registry = ExecutionToolRegistry(listOf(readFile, writeFile, browser))

        val results = registry.discoverTools(query = "read text from a file")
        val names = results.map { it.modelName }

        assertTrue(names.contains("read_file"))
        assertFalse(names.first() == "write_text_file" && results.size == 1)
    }

    @Test
    fun visibilityMovesFromHiddenToCategoryMetadataFullAndExecuted() {
        val readFile = tool("read_file", "Read text from a file.")
        val registry = ExecutionToolRegistry(listOf(readFile))

        assertEquals(ToolVisibilityStage.HIDDEN, registry.stage("read_file"))

        registry.discoverCapabilities()
        assertEquals(ToolVisibilityStage.CATEGORY_VISIBLE, registry.stage("read_file"))

        registry.discoverTools(query = "read file")
        assertEquals(ToolVisibilityStage.METADATA_VISIBLE, registry.stage("read_file"))

        assertTrue(registry.currentTools().none { it.name == "read_file" })
        registry.loadTools(listOf("read_file"))
        assertEquals(ToolVisibilityStage.FULL_SCHEMA_LOADED, registry.stage("read_file"))
        assertTrue(registry.currentTools().any { it.name == "read_file" })

        registry.markExecuted("read_file")
        assertEquals(ToolVisibilityStage.EXECUTED, registry.stage("read_file"))
    }

    @Test
    fun initialProviderSurfaceIsCompactAndDoesNotContainFullToolSchemas() {
        val tools = (1..80).map { index ->
            tool("tool_$index", "A long description for tool $index " + "x".repeat(120))
        }
        val registry = ExecutionToolRegistry(tools)

        val providerTools = registry.providerTools()
        val names = providerTools.map { it.name }.toSet()

        assertEquals(setOf("discover_capabilities", "discover_tools", "load_tools"), names)
        assertFalse(names.contains("tool_1"))
        assertTrue(registry.compactIndex().length < 6000)
    }

    @Test
    fun loadingOneToolMaterializesOnlyThatToolSchema() {
        val tools = (1..20).map { index ->
            tool("tool_$index", "Tool $index")
        }
        val registry = ExecutionToolRegistry(tools)

        registry.loadTools(listOf("tool_7"))
        val loaded = registry.currentTools().map { it.name }.toSet()

        assertEquals(setOf("tool_7"), loaded)
        assertFalse(loaded.contains("tool_6"))
        assertFalse(loaded.contains("tool_8"))
    }

    @Test
    fun nativeAndPluginToolsWithTheSameHumanNameRemainDistinct() {
        val native = tool("inspect", "Built-in inspect.")
        val plugin = tool("inspect", "Plugin inspect.")
        val registry = ExecutionToolRegistry(
            tools = listOf(native, plugin),
            sourceHints = listOf(
                ToolSourceHint(
                    tool = native,
                    source = ToolSourceKind.BUILTIN,
                    sourceId = "builtin",
                    category = ToolCategory.SYSTEM_DEVICE,
                    risk = ToolRiskLevel.LOW,
                ),
                ToolSourceHint(
                    tool = plugin,
                    source = ToolSourceKind.PLUGIN,
                    sourceId = "plugin.demo",
                    category = ToolCategory.MCP_PLUGIN,
                    risk = ToolRiskLevel.MEDIUM,
                    permissions = setOf("network"),
                ),
            )
        )

        val nativeId = registry.identityFor("builtin_builtin__inspect")
        val pluginId = registry.identityFor("plugin_plugin_demo__inspect")

        assertNotNull(nativeId)
        assertNotNull(pluginId)
        assertTrue(nativeId != pluginId)
        assertEquals(ToolSourceKind.BUILTIN, nativeId!!.source)
        assertEquals(ToolSourceKind.PLUGIN, pluginId!!.source)
        assertTrue(registry.discoverTools(query = "inspect").size >= 2)
    }

    @Test
    fun modelWithoutToolAbilityGetsNoToolSurface() {
        val readFile = tool("read_file", "Read file text.")
        val registry = ExecutionToolRegistry(listOf(readFile))
        val model = Model(abilities = listOf(ModelAbility.REASONING))

        assertTrue(registry.providerTools(model).isEmpty())
        assertTrue(registry.currentTools(model).isEmpty())
    }
}
