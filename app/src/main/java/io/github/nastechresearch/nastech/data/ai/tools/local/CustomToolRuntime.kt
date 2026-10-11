package io.github.nastechresearch.nastech.data.ai.tools.local

import android.content.Context
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import io.github.nastechresearch.nastech.data.ai.mcp.McpManager
import io.github.nastechresearch.nastech.service.DirectModeActionRunner
import io.github.nastechresearch.nastech.data.customtools.CustomToolDefinition
import io.github.nastechresearch.nastech.data.customtools.CustomToolKind
import io.github.nastechresearch.nastech.data.customtools.CustomToolDefinitionValidator
import io.github.nastechresearch.nastech.data.preferences.TermuxRuntime
import kotlin.uuid.Uuid

private const val CUSTOM_JS_TIMEOUT_MS = 5_000L
private const val CUSTOM_OUTPUT_LIMIT_CHARS = 24_000
private const val TERMUX_PYTHON = "/data/data/com.termux/files/usr/bin/python"

/** A validated custom definition paired with its assistant-callable tool. */
data class CustomToolRegistration(
    val definition: CustomToolDefinition,
    val tool: Tool,
)

/**
 * Turns reviewed custom-tool definitions into normal Nastech tools.
 *
 * Every custom entry is approval-gated. JavaScript is evaluated in a bounded QuickJS context,
 * Python goes through the existing Termux RUN_COMMAND bridge, action sequences are run through
 * DirectModeActionRunner (including its per-step HARDLINE checks), and MCP wrappers use the
 * already-configured MCP manager. Imported source is never run by the repository itself.
 */
object CustomToolRuntime {
    fun createTools(
        context: Context,
        definitions: List<CustomToolDefinition>,
        baseTools: List<Tool>,
        mcpManager: McpManager,
        settingsStore: io.github.nastechresearch.nastech.data.datastore.SettingsStore,
        callerAssistantId: String?,
    ): List<CustomToolRegistration> {
        val registrations = definitions
            .filter { it.enabled && CustomToolDefinitionValidator.validate(it) == null }
            .map { definition ->
                val tool = createTool(
                    context = context,
                    definition = definition,
                    availableTools = { baseTools },
                    mcpManager = mcpManager,
                )
                CustomToolRegistration(definition, tool)
            }
        return registrations
    }

    private fun createTool(
        context: Context,
        definition: CustomToolDefinition,
        availableTools: () -> List<Tool>,
        mcpManager: McpManager,
    ): Tool {
        val properties = definition.inputSchema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = (definition.inputSchema["required"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        val callableName = "ct_" + definition.name

        return Tool(
            name = callableName,
            description = definition.description + " (User-installed custom tool; review its source in Custom tools.)",
            parameters = { InputSchema.Obj(properties = properties, required = required) },
            needsApproval = { true },
            execute = { args ->
                val input = args as? JsonObject ?: JsonObject(emptyMap())
                runCatching {
                    when (definition.kind) {
                        CustomToolKind.JAVASCRIPT -> runJavaScript(definition.id, definition.source, input)
                        CustomToolKind.PYTHON -> runPython(context, definition, input)
                        CustomToolKind.ACTION_SEQUENCE -> runActions(definition, availableTools())
                        CustomToolKind.MCP -> runMcpTool(
                            definition = definition,
                            input = input,
                            mcpManager = mcpManager,
                            settingsStore = settingsStore,
                            callerAssistantId = callerAssistantId,
                        )
                    }
                }.getOrElse { error ->
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("error", "custom_tool_failed")
                                put("tool_id", definition.id)
                                put("detail", (error.message ?: error::class.java.simpleName).take(800))
                            }.toString()
                        )
                    )
                }
            },
        )
    }

    /**
     * Match the same authorization gates used by the normal MCP tool catalogue at call time.
     * Custom wrappers must not bypass a disabled server, an assistant's server allow-list, or a
     * disabled individual remote tool merely because a UUID was embedded in imported JSON.
     */
    private suspend fun runMcpTool(
        definition: CustomToolDefinition,
        input: JsonObject,
        mcpManager: McpManager,
        settingsStore: io.github.nastechresearch.nastech.data.datastore.SettingsStore,
        callerAssistantId: String?,
    ): List<UIMessagePart> {
        if (callerAssistantId.isNullOrBlank()) {
            return failure(
                definition.id,
                "mcp_assistant_context_missing",
                "MCP custom tools can only run when the calling assistant is known.",
            )
        }
        val serverId = runCatching { Uuid.parse(definition.mcpServerId) }.getOrNull()
            ?: return failure(definition.id, "mcp_server_id_invalid", "The configured MCP server id is invalid.")
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.assistants.firstOrNull { it.id.toString() == callerAssistantId }
            ?: return failure(definition.id, "mcp_assistant_not_found", "The calling assistant no longer exists.")
        if (serverId !in assistant.mcpServers) {
            return failure(
                definition.id,
                "mcp_server_not_allowed",
                "This MCP server is not enabled for the calling assistant.",
            )
        }
        val server = settings.mcpServers.firstOrNull { it.id == serverId }
            ?: return failure(definition.id, "mcp_server_not_found", "The referenced MCP server is not configured.")
        if (!server.commonOptions.enable) {
            return failure(definition.id, "mcp_server_disabled", "The referenced MCP server is disabled.")
        }
        val remoteTool = server.commonOptions.tools.firstOrNull { it.name == definition.remoteToolName }
            ?: return failure(
                definition.id,
                "mcp_tool_not_configured",
                "The remote tool is not present in this server's configured tool list.",
            )
        if (!remoteTool.enable) {
            return failure(definition.id, "mcp_tool_disabled", "The referenced remote MCP tool is disabled.")
        }
        return mcpManager.callTool(serverId, definition.remoteToolName, input)
    }

    private suspend fun runActions(
        definition: CustomToolDefinition,
        tools: List<Tool>,
    ): List<UIMessagePart> {
        val result = DirectModeActionRunner(Json.Default).run(
            actions = definition.actions.map { DirectModeActionRunner.Action(it.tool, it.args) },
            availableTools = tools,
        )
        return listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("tool_id", definition.id)
                    put("outcome", result.finalOutcome)
                    result.errorMessage?.let { put("detail", it) }
                }.toString()
            )
        )
    }

    private suspend fun runJavaScript(
        toolId: String,
        source: String,
        input: JsonObject,
    ): List<UIMessagePart> {
        val done = CompletableDeferred<String>()
        val inputLiteral = JsonPrimitive(input.toString()).toString()
        Thread {
            var engine: QuickJSContext? = null
            try {
                engine = QuickJSContext.create()
                engine.setMemoryLimit(64 * 1024 * 1024)
                engine.setMaxStackSize(512 * 1024)
                val code = "(function(input) {\n" + source + "\n})(JSON.parse(" + inputLiteral + "))"
                val value = engine.evaluate(code)
                val output = when (value) {
                    null -> "null"
                    is QuickJSObject -> runCatching { value.stringify() }.getOrElse { value.toString() }
                    else -> value.toString()
                }
                done.complete(
                    buildJsonObject {
                        put("tool_id", toolId)
                        put("result", output.take(CUSTOM_OUTPUT_LIMIT_CHARS))
                    }.toString()
                )
            } catch (t: Throwable) {
                done.complete(
                    buildJsonObject {
                        put("error", "javascript_execution_failed")
                        put("detail", (t.message ?: t::class.java.simpleName).take(800))
                    }.toString()
                )
            } finally {
                try { engine?.destroy() } catch (_: Throwable) { }
            }
        }.apply {
            name = "custom-js-${System.nanoTime()}"
            isDaemon = true
            start()
        }

        val payload = withTimeoutOrNull(CUSTOM_JS_TIMEOUT_MS) { done.await() }
            ?: buildJsonObject {
                put("error", "javascript_timeout")
                put("detail", "Execution exceeded ${CUSTOM_JS_TIMEOUT_MS} ms.")
            }.toString()
        return listOf(UIMessagePart.Text(payload))
    }

    private suspend fun runPython(
        context: Context,
        definition: CustomToolDefinition,
        input: JsonObject,
    ): List<UIMessagePart> {
        when (TermuxIntegration.state(context)) {
            TermuxIntegration.State.NOT_INSTALLED ->
                return failure(definition.id, "termux_not_installed", "Install Termux before running this Python tool.")
            TermuxIntegration.State.NO_PERMISSION ->
                return failure(definition.id, "termux_permission_missing", "Grant Nastech permission to run Termux commands, then verify the Termux integration.")
            TermuxIntegration.State.READY -> Unit
        }

        val source = "import json, sys\ninput = json.loads(sys.argv[1])\n" + definition.source
        return when (
            val result = runCommandCapture(
                ctx = context,
                executable = TERMUX_PYTHON,
                arguments = arrayOf("-c", source, input.toString()),
                workingDir = TermuxRuntime.defaultWorkingDir,
                timeoutMs = TermuxRuntime.commandTimeoutMs,
                commandLabel = ("Custom Python: " + definition.name).take(80),
            )
        ) {
            is CaptureResult.Success -> listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("tool_id", definition.id)
                        put("exit_code", result.exitCode)
                        put("stdout", result.stdout.take(CUSTOM_OUTPUT_LIMIT_CHARS))
                        put("stderr", result.stderr.take(CUSTOM_OUTPUT_LIMIT_CHARS))
                        if (result.stdout.length > CUSTOM_OUTPUT_LIMIT_CHARS) put("stdout_truncated", true)
                        if (result.stderr.length > CUSTOM_OUTPUT_LIMIT_CHARS) put("stderr_truncated", true)
                    }.toString()
                )
            )
            CaptureResult.Timeout ->
                failure(definition.id, "python_timeout", "Termux did not return before the configured command timeout.")
            CaptureResult.Denied ->
                failure(definition.id, "termux_permission_denied", "Termux denied this command.")
            is CaptureResult.OtherError ->
                failure(definition.id, "termux_execution_error", result.message)
        }
    }

    private fun failure(id: String, error: String, detail: String): List<UIMessagePart> =
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("tool_id", id)
                    put("error", error)
                    put("detail", detail.take(800))
                }.toString()
            )
        )
}
