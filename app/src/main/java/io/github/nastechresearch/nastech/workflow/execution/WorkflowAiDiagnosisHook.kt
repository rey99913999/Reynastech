package io.github.nastechresearch.nastech.workflow.execution

import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import io.github.nastechresearch.nastech.data.datastore.SettingsStore
import io.github.nastechresearch.nastech.data.datastore.findModelById
import io.github.nastechresearch.nastech.data.datastore.findProvider
import io.github.nastechresearch.nastech.data.execution.debug.ExecutionTrace
import io.github.nastechresearch.nastech.workflow.model.WorkflowDefinition

class WorkflowAiDiagnosisHook(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
) {
    suspend fun diagnose(
        workflow: WorkflowDefinition,
        trace: ExecutionTrace,
    ): String? {
        val settings = settingsStore.settingsFlow.value
        val model = settings.findModelById(settings.chatModelId) ?: return null
        val providerSetting = model.findProvider(settings.providers) ?: return null
        val provider = providerManager.getProviderByType(providerSetting)
        val tracePayload = io.github.nastechresearch.nastech.utils.JsonInstant.encodeToString(trace).take(12_000)
        val prompt = """
            Diagnose this failed Nastech workflow execution.
            Do not propose or apply a workflow change. Explain the most likely root cause,
            cite the failed step and evidence, and give one or two safe repair suggestions.
            Keep the response concise and factual.

            Workflow: ${workflow.name}
            Workflow id: ${workflow.id}
            Execution trace:
            $tracePayload
        """.trimIndent()
        return withTimeoutOrNull(30_000L) {
            provider.generateText(
                providerSetting = providerSetting,
                messages = listOf(
                    UIMessage.system("You are a workflow execution diagnostics assistant."),
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(me.rerere.ai.ui.UIMessagePart.Text(prompt))
                    ),
                ),
                params = TextGenerationParams(
                    model = model,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                ),
            ).message.toText().trim().takeIf { it.isNotBlank() }
        }
    }
}
