package io.github.nastechresearch.nastech.data.agent

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

fun buildAutonomousTaskTool(
    runtime: ConversationAgentRuntime,
    conversationId: Uuid,
    parentAssistantId: Uuid,
    availableTools: List<String>,
): Tool = Tool(
    name = "run_autonomous_task",
    description = "Run this conversation's configured Agent workflow for a concrete user goal. " +
        "The workflow is bounded by autonomy, permissions, Task State and loop limits. " +
        "Use it for multi-step goals when this conversation has Agent Runtime enabled.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("goal", buildJsonObject {
                    put("type", "string")
                    put("description", "Concrete user goal to execute.")
                })
            },
            required = listOf("goal"),
        )
    },
    needsApproval = { false },
    execute = { args ->
        val goal = args.jsonObject["goal"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool listOf(UIMessagePart.Text("""{"status":"INVALID_GOAL"}"""))
        val result = runCatching {
            runtime.run(
                conversationId = conversationId.toString(),
                parentAssistantId = parentAssistantId.toString(),
                goal = goal,
                availableTools = availableTools,
            )
        }.getOrElse { error ->
            AgentRuntimeResult(
                status = "FAILED",
                summary = "Agent Runtime failed: " + (error.message ?: error.javaClass.simpleName),
                failure = error.message,
            )
        }
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("status", result.status)
                    result.taskId?.let { put("task_id", it) }
                    put("summary", result.summary)
                    put("completed_agents", result.completedAgents.joinToString(","))
                    put("waiting_for_approval", result.waitingForApproval)
                    result.failure?.let { put("failure", it) }
                }.toString()
            )
        )
    },
)
