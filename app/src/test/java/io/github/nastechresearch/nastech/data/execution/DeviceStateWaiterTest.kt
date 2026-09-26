package io.github.nastechresearch.nastech.data.execution

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DeviceStateWaiterTest {
    @Test
    fun waiterStopsAsSoonAsPostconditionBecomesTrue() = runBlocking {
        val attempts = AtomicInteger(0)
        val verifier = DevicePostconditionVerifier { _, _ ->
            val current = attempts.incrementAndGet()
            DeviceVerificationResult(
                verified = current >= 2,
                reason = if (current >= 2) "ok" else "not_yet",
            )
        }
        val waiter = BoundedDeviceStateWaiter(
            verifier = verifier,
            maxTotalWaitMs = 2_000L,
            pollMs = 20L,
        )

        val result = waiter.waitFor(
            DevicePostcondition(DevicePostconditionType.TEXT_PRESENT, "done", timeoutMs = 1_000L),
            null,
        )

        assertTrue(result.verified)
        assertTrue(attempts.get() >= 2)
    }
    @Test
    fun waitHonorsCustomPollAndMaxTimeoutBounds() = runBlocking {
        val attempts = AtomicInteger(0)
        val verifier = DevicePostconditionVerifier { _, _ ->
            attempts.incrementAndGet()
            DeviceVerificationResult(false, "not_yet")
        }
        val waiter = BoundedDeviceStateWaiter(verifier, maxTotalWaitMs = 300_000L, pollMs = 250L)

        val result = waiter.waitFor(
            DevicePostcondition(
                DevicePostconditionType.TEXT_PRESENT,
                value = "done",
                timeoutMs = 10_000L,
            ),
            null,
            450L,
            250L,
            Int.MAX_VALUE,
        )

        assertTrue(!result.verified)
        assertTrue(attempts.get() >= 1)
        assertTrue(attempts.get() <= 3)
    }

    @Test
    fun cancellationStopsPollingImmediately() = runBlocking {
        val attempts = AtomicInteger(0)
        val verifier = DevicePostconditionVerifier { _, _ ->
            attempts.incrementAndGet()
            DeviceVerificationResult(false, "not_yet")
        }
        val waiter = BoundedDeviceStateWaiter(verifier, pollMs = 250L)

        val job: Job = launch {
            waiter.waitFor(
                DevicePostcondition(DevicePostconditionType.TEXT_PRESENT, "done", timeoutMs = 30_000L),
                null,
                30_000L,
                250L,
                Int.MAX_VALUE,
            )
        }
        delay(50L)
        job.cancel()
        withTimeout(1_000L) { job.join() }
        assertTrue(attempts.get() >= 1)
    }

}
