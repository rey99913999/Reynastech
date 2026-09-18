package io.github.nastechresearch.nastech.data.execution

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.Json

fun buildStructuredPlanTool(
    json: Json,
    availableTools: List<Tool>,
    engine: LocalExecutionEngine,
    isToolAutoApproved: suspend (String) -> Boolean,
): Tool {
    val registry = ExecutionToolRegistry(availableTools)
    val compactIndex = registry.compactIndex()

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
            
            $compactIndex
            
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
                        put("description", JsonPrimitive("Optional Update 02 task id to reuse its state/checkpoints"))
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
                val result = engine.execute(
                    plan = planResult.getOrThrow(),
                    tools = availableTools,
                    isToolAutoApproved = isToolAutoApproved,
                )
                listOf(
                    UIMessagePart.Text(
                        json.encodeToString(ExecutionPlanResult.serializer(), result)
                    )
                )
            }
        }
    )
}
