package io.github.nastechresearch.nastech.workflow.recording

import io.github.nastechresearch.nastech.data.ai.tools.LocalToolOption
import io.github.nastechresearch.nastech.data.datastore.SettingsStore
import io.github.nastechresearch.nastech.data.datastore.findModelById
import io.github.nastechresearch.nastech.data.datastore.findProvider
import io.github.nastechresearch.nastech.utils.JsonInstant
import io.github.nastechresearch.nastech.workflow.model.WorkflowJson
import io.github.nastechresearch.nastech.workflow.repository.WorkflowRepository
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

class WorkflowRecordingConverter(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val repository: WorkflowRepository,
    private val store: WorkflowRecordingStore,
) {
    suspend fun convert(recordingId: String): String? {
        val recording = store.get(recordingId) ?: return null
        val settings = settingsStore.settingsFlow.value
        val model = settings.findModelById(settings.chatModelId) ?: return null
        val providerSetting = model.findProvider(settings.providers) ?: return null
        val provider = providerManager.getProviderByType(providerSetting)
        val assistant = settings.assistants.firstOrNull {
            it.localTools.contains(LocalToolOption.Workflows) &&
                it.localTools.contains(LocalToolOption.ScreenAutomation)
        } ?: settings.assistants.firstOrNull {
            it.localTools.contains(LocalToolOption.Workflows)
        }

        val prompt = """
            Convert the raw mobile interaction recording below into a safe, editable Nastech Workflow draft.
            Return JSON only. Never enable it automatically.

            Use tool "learned_action" for recorded UI actions. Prefer semantic target text, package_name,
            verify_text, verify_package, and verify_change. Avoid fixed coordinates.
            Remove accidental taps or redundant waits only when the recording provides enough evidence.
            Use alternatives for an ordered fallback such as Install then Update.
            Do not invent conditions that are not supported by the recording.
            Use trigger {"type":"manual"}.
            Set enabled=false and approval fields as shown.

            JSON shape:
            {
              "name":"Learned task",
              "description":"...",
              "enabled":false,
              "trigger":{"type":"manual"},
              "conditions":[],
              "actions":[
                {"tool":"learned_action","args":{"kind":"tap","target":"Enable","verify_change":true},"timeout_seconds":60}
              ],
              "source_recording_id":"RECORDING_ID",
              "approved_at_ms":null
            }

            Recording:
        """.trimIndent() + "\n" + JsonInstant.encodeToString(recording).take(30_000)

        val response = withTimeoutOrNull(45_000L) {
            provider.generateText(
                providerSetting = providerSetting,
                messages = listOf(
                    UIMessage.system("Convert interaction recordings into safe editable workflow drafts. Emit JSON only."),
                    UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(prompt))),
                ),
                params = TextGenerationParams(
                    model = model,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                ),
            ).message.toText().trim()
        } ?: return null

        val first = response.indexOf('{')
        val last = response.lastIndexOf('}')
        if (first < 0 || last <= first) return null
        val json = response.substring(first, last + 1)
        val parsed = WorkflowJson.parse(json, setOf("learned_action"))
        val definition = when (parsed) {
            is WorkflowJson.ParseResult.Err -> return null
            is WorkflowJson.ParseResult.Ok -> parsed.definition
        }.copy(
            enabled = false,
            sourceRecordingId = recording.id,
            approvedAtMs = null,
            authoringAssistantId = assistant?.id?.toString(),
        )
        repository.upsert(definition)
        repository.createRevision(
            workflowId = definition.id,
            sourceRunId = null,
            reason = "AI conversion from recording " + recording.id,
            definition = definition,
            status = "DRAFT",
        )
        val draftPath = store.saveDraft(recording.id, WorkflowJson.encode(definition))
        store.markConverted(recording.id, draftPath)
        return definition.id
    }
}
