package io.github.nastechresearch.nastech.data.execution

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import io.github.nastechresearch.nastech.data.ai.tools.AssistantToolPermissionResolver
import io.github.nastechresearch.nastech.data.model.Assistant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

fun buildStructuredPlanTool(
    json: Json,
    registry: ExecutionToolRegistry,
    engine: LocalExecutionEngine,
    isToolAutoApproved: suspend (String) -> Boolean,
    toolPermissionResolver: AssistantToolPermissionResolver? = null,
    assistant: Assistant? = null,
): Tool {
    return Tool(
        name = "execute_structured_plan",
        description = """
            Execute a short multi-step plan locally without asking the LLM after every step.
            Use only when the steps are well-defined and their expected results are locally
            observable. The executor preserves the logical goal, uses the current Task Manager
            when taskId/taskStepId are supplied, performs local retries, and returns a partial
            replan request when a step cannot be completed safely.
            
            Safety rules:
            - verification is never removed to save tokens;
            - actions that normally require approval still require the corresponding tool to be
              pre-approved;
            - steps with level VISION or LLM are returned for higher-level planning instead of
              blind execution;
            - completed steps are not repeated during partial replanning.
            
            Tool discovery is runtime-managed. Select tools from discover_tools, then load_tools
            before creating the plan. Full schemas are loaded only for the selected tools.
            
            JSON shape:
            {"goal":"...","taskId":"optional","steps":[
              {"id":"s1","toolName":"...","level":"LOCAL_DETERMINISTIC","args":{},"expectedResult":"..."},
              {"id":"s2","toolName":"...","level":"LOCAL_RULES","args":{}}
            ]}
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("goal", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("The original logical goal"))
                    })
                    put("logicalPlan", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("Optional human-readable logical plan"))
                    })
                    put("taskId", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("Optional task id to reuse its state/checkpoints"))
                    })
                    put("requiredCapabilities", buildJsonObject {
                        put("type", JsonPrimitive("array"))
                        put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                        put("description", JsonPrimitive("Required device capabilities such as app_launch, device_control, keyboard"))
                    })
                    put("requiredConstraints", buildJsonObject {
                        put("type", JsonPrimitive("array"))
                        put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                        put("description", JsonPrimitive("Explicit constraints such as use_keyboard or use_screenshot_tool"))
                    })
                    put("steps", buildJsonObject {
                        put("type", JsonPrimitive("array"))
                        put("minItems", JsonPrimitive(1))
                        put("maxItems", JsonPrimitive(32))
                        put("items", buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put("properties", buildJsonObject {
                                put("id", buildJsonObject { put("type", JsonPrimitive("string")) })
                                put("kind", buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("enum", kotlinx.serialization.json.buildJsonArray {
                                        add(JsonPrimitive("TOOL"))
                                        add(JsonPrimitive("VERIFY"))
                                        add(JsonPrimitive("RETURN"))
                                        add(JsonPrimitive("DECIDE"))
                                        add(JsonPrimitive("RECOVER"))
                                    })
                                })
                                put("level", buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("enum", kotlinx.serialization.json.buildJsonArray {
                                        add(JsonPrimitive("LOCAL_DETERMINISTIC"))
                                        add(JsonPrimitive("LOCAL_RULES"))
                                        add(JsonPrimitive("VISION"))
                                        add(JsonPrimitive("LLM"))
                                    })
                                })
                                put("toolName", buildJsonObject { put("type", JsonPrimitive("string")) })
                                put("args", buildJsonObject { put("type", JsonPrimitive("object")) })
                                put("logicalGoal", buildJsonObject { put("type", JsonPrimitive("string")) })
                                put("expectedResult", buildJsonObject { put("type", JsonPrimitive("string")) })
                                put("requiresVerification", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                                put("approvalRequired", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                                put("taskStepId", buildJsonObject { put("type", JsonPrimitive("string")) })
                                put("alternativeToolNames", buildJsonObject {
                                    put("type", JsonPrimitive("array"))
                                    put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                                })
                                put("deviceAction", buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("enum", kotlinx.serialization.json.buildJsonArray {
                                        DeviceAction.entries.forEach { add(JsonPrimitive(it.name)) }
                                    })
                                })
                                put("preconditions", buildJsonObject {
                                    put("type", JsonPrimitive("array"))
                                    put("items", buildJsonObject { put("type", JsonPrimitive("object")) })
                                })
                                put("postconditions", buildJsonObject {
                                    put("type", JsonPrimitive("array"))
                                    put("items", buildJsonObject { put("type", JsonPrimitive("object")) })
                                })
                                put("requiredConstraints", buildJsonObject {
                                    put("type", JsonPrimitive("array"))
                                    put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                                })
                            })
                            put("required", kotlinx.serialization.json.buildJsonArray {
                                add(JsonPrimitive("id"))
                            })
                        })
                    })
                },
                required = listOf("goal", "steps")
            )
        },
        execute = { params ->
            val planResult = runCatching {
                json.decodeFromJsonElement(StructuredExecutionPlan.serializer(), params)
            }
            if (planResult.isFailure) {
                listOf(
                    UIMessagePart.Text(
                        "invalid_execution_plan: " +
                            (planResult.exceptionOrNull()?.message
                                ?: planResult.exceptionOrNull()?.javaClass?.simpleName
                                ?: "unknown").take(500)
                    )
                )
            } else {
                val plan = planResult.getOrThrow()
                val selected = plan.steps
                    .filter { it.kind == ExecutionStepKind.TOOL }
                    .flatMap { step ->
                        listOfNotNull(step.toolName) + step.alternativeToolNames
                    }
                    .distinct()
                registry.loadTools(selected)
                val result = engine.execute(
                    plan = plan,
                    registry = registry,
                    isToolAutoApproved = isToolAutoApproved,
                    toolPermissionResolver = toolPermissionResolver,
                    assistant = assistant,
                )
                val parts = mutableListOf<UIMessagePart>(
                    UIMessagePart.Text(
                        json.encodeToString(ExecutionPlanResult.serializer(), result)
                    )
                )

                val artifactPaths = result.results
                    .asSequence()
                    .mapNotNull { it.output }
                    .flatMap { output ->
                        val element = runCatching { Json.parseToJsonElement(output).jsonObject }.getOrNull()
                            ?: return@flatMap emptySequence<String>()
                        sequenceOf(
                            element["screenshot_path"]?.jsonPrimitive?.contentOrNull,
                            element["file_path"]?.jsonPrimitive?.contentOrNull,
                        ).filterNotNull()
                    }
                    .distinct()
                    .filter { path -> File(path).isFile && File(path).length() > 0L }
                    .take(4)
                    .toList()

                artifactPaths.forEach { artifactPath ->
                    parts += UIMessagePart.Image(url = "file://" + artifactPath)
                }

                parts
            }
        }
    )
}
