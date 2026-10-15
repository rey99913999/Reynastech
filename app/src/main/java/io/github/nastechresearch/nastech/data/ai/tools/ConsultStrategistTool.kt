package io.github.nastechresearch.nastech.data.ai.tools

import io.github.nastechresearch.nastech.data.datastore.Settings
import io.github.nastechresearch.nastech.data.datastore.findProvider
import io.github.nastechresearch.nastech.data.model.Assistant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleTextGenerationResult
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-generation consultation budget. A null limit is intentionally unlimited;
 * reservations are atomic so concurrent tool calls cannot overrun a configured limit.
 */
internal class StrategistConsultationBudget(private val limit: Long?) {
    private val used = AtomicLong(0L)

    fun reserve(): Long? {
        while (true) {
            val current = used.get()
            if (limit != null && current >= limit) return null
            if (current == Long.MAX_VALUE) return null
            if (used.compareAndSet(current, current + 1L)) return current + 1L
        }
    }

    fun usedCount(): Long = used.get()
}

/**
 * Directly asks the Assistant's configured strategist model for advice. This does not create
 * a conversation, dispatch a sub-agent, or invoke the Agent Runtime.
 */
internal fun buildConsultStrategistTool(
    settings: Settings,
    assistant: Assistant,
    strategistModel: Model,
    providerManager: ProviderManager,
    originalGoal: String,
    budget: StrategistConsultationBudget,
): Tool = Tool(
    name = "consult_strategist",
    description = buildString {
        appendLine("Consult the thinking/strategist model configured for this same Assistant. The strategist returns advice to you; you remain responsible for the task and must continue execution afterward.")
        appendLine("Use this tool only according to the following user-configured consultation policy:")
        appendLine(assistant.strategistConsultationPolicy.trim().ifBlank { "Consult when another perspective is genuinely useful." }.take(4_000))
        appendLine("Provide the most relevant current state in the fields below. The original user goal is attached automatically when enabled in Assistant settings.")
        append("Each successful reservation consumes one consultation from this turn's configured budget.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("question", buildJsonObject {
                    put("type", "string")
                    put("description", "The focused question you want the strategist to resolve. Be explicit about the decision or blocker.")
                })
                put("current_plan", buildJsonObject {
                    put("type", "string")
                    put("description", "Your current plan or the part of the plan you are reconsidering.")
                })
                put("work_completed", buildJsonObject {
                    put("type", "string")
                    put("description", "What has already been completed and any evidence/results observed.")
                })
                put("blocker", buildJsonObject {
                    put("type", "string")
                    put("description", "The current obstacle, uncertainty, or unexpected result.")
                })
                put("previous_attempts", buildJsonObject {
                    put("type", "string")
                    put("description", "Relevant attempts already tried and why they did or did not work.")
                })
                put("additional_context", buildJsonObject {
                    put("type", "string")
                    put("description", "Other relevant constraints, tool outputs, errors, or details.")
                })
            },
            required = listOf("question"),
        )
    },
    needsApproval = { false },
    execute = { input ->
        val consultationNumber = budget.reserve()
        if (consultationNumber == null) {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "consultation_limit_reached")
                        put("detail", "The configured strategist consultation budget has been exhausted for this generation.")
                        put("consultations_used", budget.usedCount())
                        assistant.strategistConsultationLimit?.let { put("consultation_limit", it) }
                    }.toString(),
                ),
            )
        } else {
        val args = input.jsonObject
        fun field(name: String): String =
            args[name]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        val prompt = buildString {
            appendLine("You are advising the execution model for the same Assistant. Give strategic guidance, not a claim that you performed the task.")
            if (assistant.strategistSystemPrompt.isNotBlank()) {
                appendLine()
                appendLine("CUSTOM STRATEGIST SYSTEM INSTRUCTIONS:")
                appendLine(assistant.strategistSystemPrompt.trim())
            }
            appendLine("Answer the current question with actionable next steps, key trade-offs, and any important verification to perform.")
            if (assistant.strategistIncludeOriginalGoal && originalGoal.isNotBlank()) {
                appendLine()
                appendLine("ORIGINAL USER GOAL:")
                appendLine(originalGoal.trim())
            }
            appendLine()
            appendLine("CURRENT QUESTION:")
            appendLine(field("question").ifBlank { "Review the current situation and recommend the best next step." })
            fun addContext(enabled: Boolean, key: String, heading: String) {
                if (!enabled) return
                val value = field(key)
                if (value.isNotBlank()) {
                    appendLine()
                    appendLine(heading + ":")
                    appendLine(value)
                }
            }
            addContext(assistant.strategistIncludeCurrentPlan, "current_plan", "CURRENT PLAN")
            addContext(assistant.strategistIncludeCompletedWork, "work_completed", "WORK COMPLETED AND OBSERVED RESULTS")
            addContext(assistant.strategistIncludeBlocker, "blocker", "CURRENT BLOCKER")
            addContext(assistant.strategistIncludePreviousAttempts, "previous_attempts", "PREVIOUS ATTEMPTS")
            addContext(assistant.strategistIncludeAdditionalContext, "additional_context", "ADDITIONAL CONTEXT")
        }

        val provider = strategistModel.findProvider(settings.providers)
            ?: error("The configured strategist model has no matching provider.")
        check(provider.enabled) {
            "The strategist model's provider '${provider.name}' is disabled. Enable it or select another strategist model."
        }
        val providerImpl = providerManager.getProviderByType(provider)
        val requestMessages = listOf(UIMessage.user(prompt))
        val result = providerImpl.generateText(
            providerSetting = provider,
            messages = requestMessages,
            params = TextGenerationParams(
                model = strategistModel,
                temperature = assistant.strategistTemperature,
                topP = assistant.strategistTopP,
                maxTokens = assistant.strategistMaxTokens,
                maxStreamRetries = settings.responseStreamMaxRetries,
                reasoningLevel = assistant.strategistReasoningLevel,
                customHeaders = buildList {
                    addAll(assistant.customHeaders)
                    addAll(strategistModel.customHeaders)
                },
                customBody = buildList {
                    addAll(assistant.customBodies)
                    addAll(strategistModel.customBodies)
                },
            ),
        )
        val answer = requestMessages
            .handleTextGenerationResult(result = result, model = strategistModel)
            .lastOrNull()
            ?.toText()
            ?.trim()
            .orEmpty()
        check(answer.isNotBlank()) { "The strategist model returned an empty response." }

        val limitText = assistant.strategistConsultationLimit?.let { " of $it" } ?: " (unlimited)"
        listOf(
            UIMessagePart.Text(
                "Strategist consultation $consultationNumber$limitText:\n" + answer.take(16_000),
            ),
        )
        }
    },
)
