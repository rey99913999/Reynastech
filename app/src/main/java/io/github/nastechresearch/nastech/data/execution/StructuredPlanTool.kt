package io.github.nastechresearch.nastech.data.execution

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
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
                        put("type", "string")
                        put("description", "The original logical goal")
                    })
                    put("logicalPlan", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional human-readable logical plan")
                    })
                    put("taskId", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional Update 02 task id to reuse its state/checkpoints")
                    })
                    put("steps", buildJsonObject {
                        put("type", "array")
                        put("minItems", 1)
                        put("maxItems", 32)
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("id", buildJsonObject { put("type", "string") })
                                put("kind", buildJsonObject {
                                    put("type", "string")
                                    put("enum", kotlinx.serialization.json.buildJsonArray {
                                        add("TOOL")
                                        add("VERIFY")
                                        add("RETURN")
                                        add("DECIDE")
                                        add("RECOVER")
                                    })
                                })
                                put("level", buildJsonObject {
                                    put("type", "string")
                                    put("enum", kotlinx.serialization.json.buildJsonArray {
                                        add("LOCAL_DETERMINISTIC")
                                        add("LOCAL_RULES")
                                        add("VISION")
                                        add("LLM")
                                    })
                                })
                                put("toolName", buildJsonObject { put("type", "string") })
                                put("args", buildJsonObject { put("type", "object") })
                                put("logicalGoal", buildJsonObject { put("type", "string") })
                                put("expectedResult", buildJsonObject { put("type", "string") })
                                put("requiresVerification", buildJsonObject { put("type", "boolean") })
                                put("approvalRequired", buildJsonObject { put("type", "boolean") })
                                put("taskStepId", buildJsonObject { put("type", "string") })
                                put("alternativeToolNames", buildJsonObject {
                                    put("type", "array")
                                    put("items", buildJsonObject { put("type", "string") })
                                })
                            })
                            put("required", kotlinx.serialization.json.buildJsonArray {
                                add("id")
                            })
                        })
                    })
                },
                required = listOf("goal", "steps")
            )
        },
        execute = { params ->
            val plan = runCatching {
                json.decodeFromJsonElement(StructuredExecutionPlan.serializer(), params)
            }.getOrElse { error ->
                return@Tool listOf(
                    UIMessagePart.Text(
                        "invalid_execution_plan: " + (error.message ?: error.javaClass.simpleName).take(500)
                    )
                )
            }

            val result = engine.execute(
                plan = plan,
                tools = availableTools,
                isToolAutoApproved = isToolAutoApproved,
            )
            listOf(
                UIMessagePart.Text(
                    json.encodeToString(ExecutionPlanResult.serializer(), result)
                )
            )
        }
    )
}
