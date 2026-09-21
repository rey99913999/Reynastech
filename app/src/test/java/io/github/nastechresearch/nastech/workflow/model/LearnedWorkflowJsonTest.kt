package io.github.nastechresearch.nastech.workflow.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LearnedWorkflowJsonTest {

    @Test
    fun learnedMetadataSurvivesRoundTrip() {
        val definition = WorkflowDefinition(
            id = "learned-1",
            name = "Learned task",
            enabled = false,
            trigger = TriggerSpec.Manual,
            actions = listOf(
                WorkflowAction(
                    tool = "learned_action",
                    args = buildJsonObject {
                        put("kind", "tap")
                        put("target", "Enable")
                        put("verify_change", true)
                    },
                ),
            ),
            sourceRecordingId = "recording-1",
            approvedAtMs = null,
        )

        val encoded = WorkflowJson.encode(definition)
        val parsed = WorkflowJson.parseStored(encoded)

        requireNotNull(parsed)
        assertEquals("recording-1", parsed.sourceRecordingId)
        assertEquals(null, parsed.approvedAtMs)
        assertEquals("learned_action", parsed.actions.single().tool)
    }

    @Test
    fun approvedLearnedMetadataIsRetained() {
        val definition = WorkflowDefinition(
            id = "learned-2",
            name = "Approved task",
            trigger = TriggerSpec.Manual,
            actions = listOf(
                WorkflowAction(
                    tool = "learned_action",
                    args = buildJsonObject { put("kind", "global"); put("global_action", "back") },
                ),
            ),
            sourceRecordingId = "recording-2",
            approvedAtMs = 1234L,
        )

        val parsed = WorkflowJson.parseStored(WorkflowJson.encode(definition))
        requireNotNull(parsed)
        assertEquals("recording-2", parsed.sourceRecordingId)
        assertEquals(1234L, parsed.approvedAtMs)
    }
}
