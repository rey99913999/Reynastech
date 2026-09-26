package io.github.nastechresearch.nastech.data.ai.tools.local

import io.github.nastechresearch.nastech.data.ai.tools.ToolInvocationContext
import io.github.nastechresearch.nastech.data.execution.DeviceAccessibilityQueryResult
import io.github.nastechresearch.nastech.data.execution.DeviceAccessibilitySelector
import io.github.nastechresearch.nastech.data.execution.DeviceObservation
import io.github.nastechresearch.nastech.data.execution.DeviceObserver
import io.github.nastechresearch.nastech.data.execution.DevicePostcondition
import io.github.nastechresearch.nastech.data.execution.DeviceStateWaiter
import io.github.nastechresearch.nastech.data.execution.DeviceVerificationResult
import io.github.nastechresearch.nastech.data.model.WaitUntilCondition
import io.github.nastechresearch.nastech.data.model.WaitUntilSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaitUntilToolTest {
    private class FakeObserver : DeviceObserver {
        override val isAvailable: Boolean = true
        private var sequence = 0

        override suspend fun observe(
            captureScreenshot: Boolean,
            captureOcr: Boolean,
        ): DeviceObservation =
            DeviceObservation(
                foregroundPackage = "com.example.app",
                visibleText = if (++sequence > 1) listOf("done") else listOf("loading"),
                screenFingerprint = sequence.toString(),
            )

        override suspend fun findAccessibilityNode(
            selector: DeviceAccessibilitySelector,
        ): DeviceAccessibilityQueryResult = DeviceAccessibilityQueryResult.Matched
    }

    private class FakeWaiter(
        private val result: DeviceVerificationResult,
    ) : DeviceStateWaiter {
        var lastPostcondition: DevicePostcondition? = null
        var lastMaxWaitMs: Long = 0L
        var lastPollMs: Long = 0L
        var lastMaxChecks: Int = 0

        override suspend fun waitFor(
            postcondition: DevicePostcondition,
            before: DeviceObservation?,
            maxWaitMs: Long,
            pollMs: Long,
            maxChecks: Int,
        ): DeviceVerificationResult {
            lastPostcondition = postcondition
            lastMaxWaitMs = maxWaitMs
            lastPollMs = pollMs
            lastMaxChecks = maxChecks
            return result
        }
    }

    private fun tool(
        condition: WaitUntilCondition,
        waiter: FakeWaiter,
        observer: FakeObserver = FakeObserver(),
        settings: WaitUntilSettings = WaitUntilSettings(maxConsecutiveWaits = 3),
    ) = waitUntilTool(
        context = org.robolectric.RuntimeEnvironment.getApplication(),
        observer = observer,
        waiter = waiter,
        invocationContext = ToolInvocationContext(waitUntilSettings = settings),
    ).also {
        @Suppress("UNUSED_VARIABLE")
        val ignored = condition
    }

    @Test
    fun fixedDelayReturnsCompleted() = runBlocking {
        val waiter = FakeWaiter(DeviceVerificationResult(true, "unused", "fixed_delay"))
        val started = System.currentTimeMillis()
        val result = waitUntilTool(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            observer = FakeObserver(),
            waiter = waiter,
            invocationContext = ToolInvocationContext(
                waitUntilSettings = WaitUntilSettings(maxWaitSeconds = 1),
            ),
        ).execute(buildJsonObject {
            put("condition", "fixed_delay")
            put("timeout_ms", 40)
        })

        val elapsed = System.currentTimeMillis() - started
        val text = (result.single() as me.rerere.ai.ui.UIMessagePart.Text).text
        assertTrue(elapsed >= 30L)
        assertTrue(text.contains(""status":"completed""))
        assertTrue(text.contains(""matched_by":"fixed_delay""))
    }

    @Test
    fun textAppearsUsesConfiguredBoundsAndSucceeds() = runBlocking {
        val waiter = FakeWaiter(DeviceVerificationResult(true, "postcondition_verified", "accessibility"))
        val result = waitUntilTool(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            observer = FakeObserver(),
            waiter = waiter,
            invocationContext = ToolInvocationContext(
                waitUntilSettings = WaitUntilSettings(
                    maxWaitSeconds = 12,
                    checkIntervalMs = 1_000L,
                    maxConsecutiveWaits = 7,
                ),
            ),
        ).execute(buildJsonObject {
            put("condition", "text_appears")
            put("expected_text", "done")
        })

        assertEquals(
            io.github.nastechresearch.nastech.data.execution.DevicePostconditionType.TEXT_PRESENT,
            waiter.lastPostcondition?.type,
        )
        assertEquals(12_000L, waiter.lastMaxWaitMs)
        assertEquals(1_000L, waiter.lastPollMs)
        assertEquals(7, waiter.lastMaxChecks)
        assertTrue((result.single() as me.rerere.ai.ui.UIMessagePart.Text).text.contains(""condition_met":true"))
    }

    @Test
    fun textDisappearsUsesAbsentPostcondition() = runBlocking {
        val waiter = FakeWaiter(DeviceVerificationResult(true, "postcondition_verified", "accessibility"))
        val result = waitUntilTool(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            observer = FakeObserver(),
            waiter = waiter,
            invocationContext = ToolInvocationContext(),
        ).execute(buildJsonObject {
            put("condition", "text_disappears")
            put("expected_text", "loading")
        })

        assertEquals(
            io.github.nastechresearch.nastech.data.execution.DevicePostconditionType.TEXT_ABSENT,
            waiter.lastPostcondition?.type,
        )
        assertTrue((result.single() as me.rerere.ai.ui.UIMessagePart.Text).text.contains(""status":"completed""))
    }

    @Test
    fun uiElementAppearsUsesSelectorPostcondition() = runBlocking {
        val waiter = FakeWaiter(DeviceVerificationResult(true, "accessibility_match", "accessibility"))
        waitUntilTool(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            observer = FakeObserver(),
            waiter = waiter,
            invocationContext = ToolInvocationContext(),
        ).execute(buildJsonObject {
            put("condition", "ui_element_appears")
            put("selector", buildJsonObject {
                put("by", "text")
                put("value", "Done")
                put("nth", 0)
            })
        })

        assertEquals(
            io.github.nastechresearch.nastech.data.execution.DevicePostconditionType.UI_ELEMENT_PRESENT,
            waiter.lastPostcondition?.type,
        )
        assertEquals("Done", waiter.lastPostcondition?.selector?.value)
    }

    @Test
    fun screenChangeAndStableUseScreenPostconditions() = runBlocking {
        val observer = FakeObserver()

        val changeWaiter = FakeWaiter(DeviceVerificationResult(true, "postcondition_verified", "screen_fingerprint"))
        waitUntilTool(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            observer = observer,
            waiter = changeWaiter,
            invocationContext = ToolInvocationContext(),
        ).execute(buildJsonObject { put("condition", "screen_changes") })

        assertEquals(
            io.github.nastechresearch.nastech.data.execution.DevicePostconditionType.SCREEN_FINGERPRINT_CHANGED,
            changeWaiter.lastPostcondition?.type,
        )

        val stableWaiter = FakeWaiter(DeviceVerificationResult(true, "postcondition_verified", "screen_fingerprint"))
        waitUntilTool(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            observer = observer,
            waiter = stableWaiter,
            invocationContext = ToolInvocationContext(),
        ).execute(buildJsonObject { put("condition", "screen_stable") })

        assertEquals(
            io.github.nastechresearch.nastech.data.execution.DevicePostconditionType.SCREEN_STABLE,
            stableWaiter.lastPostcondition?.type,
        )
    }

    @Test
    fun unmetConditionReturnsStructuredTimeout() = runBlocking {
        val waiter = FakeWaiter(DeviceVerificationResult(false, "timeout:text_present", "accessibility"))
        val result = waitUntilTool(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            observer = FakeObserver(),
            waiter = waiter,
            invocationContext = ToolInvocationContext(),
        ).execute(buildJsonObject {
            put("condition", "text_appears")
            put("expected_text", "never")
        })

        val text = (result.single() as me.rerere.ai.ui.UIMessagePart.Text).text
        assertTrue(text.contains(""status":"timeout""))
        assertTrue(text.contains(""reason":"condition_not_met""))
    }

    @Test
    fun fixedDelayCancellationPropagates() = runBlocking {
        val job = launch {
            waitUntilTool(
                context = org.robolectric.RuntimeEnvironment.getApplication(),
                observer = FakeObserver(),
                waiter = FakeWaiter(DeviceVerificationResult(true, "unused")),
                invocationContext = ToolInvocationContext(
                    waitUntilSettings = WaitUntilSettings(maxWaitSeconds = 300),
                ),
            ).execute(buildJsonObject {
                put("condition", "fixed_delay")
                put("timeout_ms", 300_000)
            })
        }
        delay(30L)
        job.cancel()
        withTimeout(1_000L) { job.join() }
        assertTrue(job.isCancelled)
    }
}
