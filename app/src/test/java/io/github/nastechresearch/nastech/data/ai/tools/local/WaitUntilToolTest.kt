package io.github.nastechresearch.nastech.data.ai.tools.local

import io.github.nastechresearch.nastech.data.ai.tools.ToolInvocationContext
import io.github.nastechresearch.nastech.data.execution.DeviceAccessibilityQueryResult
import io.github.nastechresearch.nastech.data.execution.DeviceAccessibilitySelector
import io.github.nastechresearch.nastech.data.execution.DeviceObservation
import io.github.nastechresearch.nastech.data.execution.DeviceObserver
import io.github.nastechresearch.nastech.data.execution.DevicePostcondition
import io.github.nastechresearch.nastech.data.execution.DeviceStateWaiter
import io.github.nastechresearch.nastech.data.execution.DeviceVerificationResult
import io.github.nastechresearch.nastech.data.execution.DevicePostconditionType
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
        override suspend fun observe(
            captureScreenshot: Boolean,
            captureOcr: Boolean,
        ): DeviceObservation =
            DeviceObservation(
                foregroundPackage = "com.example.app",
                visibleText = listOf("loading"),
                screenFingerprint = "same",
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
        ): DeviceVerificationResult =
            result

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

    private fun execute(
        condition: WaitUntilCondition,
        waiter: FakeWaiter,
        settings: WaitUntilSettings = WaitUntilSettings(maxConsecutiveWaits = 3),
        extra: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit)? = null,
    ) = runBlocking {
        val result = waitUntilTool(
            context = null,
            observer = FakeObserver(),
            waiter = waiter,
            invocationContext = ToolInvocationContext(waitUntilSettings = settings),
        ).execute(
            buildJsonObject {
                put("condition", when (condition) {
                    WaitUntilCondition.FIXED_DELAY -> "fixed_delay"
                    WaitUntilCondition.TEXT_APPEARS -> "text_appears"
                    WaitUntilCondition.TEXT_DISAPPEARS -> "text_disappears"
                    WaitUntilCondition.UI_ELEMENT_APPEARS -> "ui_element_appears"
                    WaitUntilCondition.SCREEN_CHANGED -> "screen_changes"
                    WaitUntilCondition.SCREEN_STABLE -> "screen_stable"
                })
                extra?.invoke(this)
            }
        )
        (result.single() as me.rerere.ai.ui.UIMessagePart.Text).text
    }

    @Test
    fun fixedDelayReturnsCompleted() {
        val waiter = FakeWaiter(DeviceVerificationResult(true, "unused", "fixed_delay"))
        val text = execute(
            condition = WaitUntilCondition.FIXED_DELAY,
            waiter = waiter,
        ) { put("timeout_ms", 40) }
        assertTrue(text.contains(""""status":"completed""""))
        assertTrue(text.contains(""""matched_by":"fixed_delay""""))
    }

    @Test
    fun textAppearsUsesConfiguredBounds() {
        val waiter = FakeWaiter(DeviceVerificationResult(true, "postcondition_verified", "accessibility"))
        val text = execute(
            condition = WaitUntilCondition.TEXT_APPEARS,
            waiter = waiter,
            settings = WaitUntilSettings(
                maxWaitSeconds = 12,
                checkIntervalMs = 1_000L,
                maxConsecutiveWaits = 7,
            ),
        )
        assertEquals(DevicePostconditionType.TEXT_PRESENT, waiter.lastPostcondition?.type)
        assertEquals(12_000L, waiter.lastMaxWaitMs)
        assertEquals(1_000L, waiter.lastPollMs)
        assertEquals(7, waiter.lastMaxChecks)
        assertTrue(text.contains(""""condition_met":true"""))
    }

    @Test
    fun textDisappearsUsesAbsentPostcondition() {
        val waiter = FakeWaiter(DeviceVerificationResult(true, "postcondition_verified", "accessibility"))
        val text = execute(
            condition = WaitUntilCondition.TEXT_DISAPPEARS,
            waiter = waiter,
        ) { put("expected_text", "loading") }
        assertEquals(DevicePostconditionType.TEXT_ABSENT, waiter.lastPostcondition?.type)
        assertTrue(text.contains(""""status":"completed""""))
    }

    @Test
    fun uiElementAppearsUsesSelectorPostcondition() {
        val waiter = FakeWaiter(DeviceVerificationResult(true, "accessibility_match", "accessibility"))
        execute(
            condition = WaitUntilCondition.UI_ELEMENT_APPEARS,
            waiter = waiter,
        ) {
            put("selector", buildJsonObject {
                put("by", "text")
                put("value", "Done")
                put("nth", 0)
            })
        }
        assertEquals(DevicePostconditionType.UI_ELEMENT_PRESENT, waiter.lastPostcondition?.type)
        assertEquals("Done", waiter.lastPostcondition?.selector?.value)
    }

    @Test
    fun screenChangeAndStableUseScreenPostconditions() {
        val changeWaiter = FakeWaiter(DeviceVerificationResult(true, "postcondition_verified", "screen_fingerprint"))
        execute(WaitUntilCondition.SCREEN_CHANGED, changeWaiter)
        assertEquals(DevicePostconditionType.SCREEN_FINGERPRINT_CHANGED, changeWaiter.lastPostcondition?.type)

        val stableWaiter = FakeWaiter(DeviceVerificationResult(true, "postcondition_verified", "screen_fingerprint"))
        execute(WaitUntilCondition.SCREEN_STABLE, stableWaiter)
        assertEquals(DevicePostconditionType.SCREEN_STABLE, stableWaiter.lastPostcondition?.type)
    }

    @Test
    fun unmetConditionReturnsStructuredTimeout() {
        val waiter = FakeWaiter(DeviceVerificationResult(false, "timeout:text_present", "accessibility"))
        val text = execute(WaitUntilCondition.TEXT_APPEARS, waiter) {
            put("expected_text", "never")
        }
        assertTrue(text.contains(""""status":"timeout""""))
        assertTrue(text.contains(""""reason":"condition_not_met""""))
    }

    @Test
    fun accessibilityUnavailableReturnsStructuredError() = runBlocking {
        val unavailable = object : DeviceObserver {
            override val isAvailable: Boolean = false

            override suspend fun observe(
                captureScreenshot: Boolean,
                captureOcr: Boolean,
            ): DeviceObservation = DeviceObservation()

            override suspend fun findAccessibilityNode(
                selector: DeviceAccessibilitySelector,
            ): DeviceAccessibilityQueryResult = DeviceAccessibilityQueryResult.Unavailable
        }
        val result = waitUntilTool(
            context = null,
            observer = unavailable,
            waiter = FakeWaiter(DeviceVerificationResult(true, "unused")),
            invocationContext = ToolInvocationContext(),
        ).execute(
            buildJsonObject {
                put("condition", "screen_changes")
            }
        )
        val text = (result.single() as me.rerere.ai.ui.UIMessagePart.Text).text
        assertTrue(text.contains(""""error":"accessibility_unavailable""""))
    }

    @Test
    fun cancellationPropagatesDuringFixedDelay() = runBlocking {
        val job = launch {
            waitUntilTool(
                context = null,
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
