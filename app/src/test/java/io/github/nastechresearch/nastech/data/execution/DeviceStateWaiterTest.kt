package io.github.nastechresearch.nastech.data.execution

import kotlinx.coroutines.runBlocking
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
}
