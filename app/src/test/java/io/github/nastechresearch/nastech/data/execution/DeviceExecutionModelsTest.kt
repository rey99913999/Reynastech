package io.github.nastechresearch.nastech.data.execution

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceExecutionModelsTest {
    @Test
    fun deviceIntentPreflightClassifiesKeyboardAndAppTasks() {
        val result = analyzeDeviceIntent("Open Gemini and use the keyboard to type hello")

        assertTrue(result.isDeviceTask)
        assertTrue(result.confidence >= 0.95f)
        assertTrue("app_launch" in result.requiredCapabilities)
        assertTrue("keyboard" in result.requiredCapabilities)
        assertTrue("use_keyboard" in result.requiredConstraints)
    }

    @Test
    fun oldStructuredPlanJsonRemainsBackwardCompatible() {
        val json = Json { ignoreUnknownKeys = false }
        val old = """{
            "goal":"read a file",
            "steps":[
                {"id":"s1","kind":"TOOL","level":"LOCAL_DETERMINISTIC","toolName":"read_file","args":{}}
            ]
        }"""

        val decoded = json.decodeFromString(StructuredExecutionPlan.serializer(), old)

        assertEquals("read a file", decoded.goal)
        assertTrue(decoded.requiredCapabilities.isEmpty())
        assertTrue(decoded.requiredConstraints.isEmpty())
        assertEquals(null, decoded.steps.single().deviceAction)
        assertTrue(decoded.steps.single().preconditions.isEmpty())
    }
    @Test
    fun responseAndClipboardGoalsRequireVerifiedOutcomeEvidence() {
        assertTrue(analyzeDeviceIntent("Open Gemini and copy the response").requiresVerifiedOutcome)
        assertTrue(analyzeDeviceIntent("افتح Gemini وانسخ الرد").requiresVerifiedOutcome)
        assertTrue(!analyzeDeviceIntent("Open Gemini").requiresVerifiedOutcome)
    }


}
