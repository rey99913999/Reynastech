package io.github.nastechresearch.nastech.data.execution

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

private const val DEFAULT_HARD_MAX_WAIT_MS = 300_000L

fun interface DeviceStateWaiter {
    suspend fun waitFor(
        postcondition: DevicePostcondition,
        before: DeviceObservation?,
        maxWaitMs: Long = postcondition.timeoutMs,
        pollMs: Long = 250L,
        maxChecks: Int = Int.MAX_VALUE,
    ): DeviceVerificationResult
}

class BoundedDeviceStateWaiter(
    private val verifier: DevicePostconditionVerifier,
    private val maxTotalWaitMs: Long = DEFAULT_HARD_MAX_WAIT_MS,
    private val pollMs: Long = 250L,
) : DeviceStateWaiter {

    override suspend fun waitFor(
        postcondition: DevicePostcondition,
        before: DeviceObservation?,
        maxWaitMs: Long = postcondition.timeoutMs,
        pollMs: Long = this.pollMs,
        maxChecks: Int = Int.MAX_VALUE,
    ): DeviceVerificationResult {
        val timeoutMs = postcondition.timeoutMs
            .coerceAtMost(maxWaitMs)
            .coerceIn(100L, maxTotalWaitMs)
        val effectivePollMs = pollMs.coerceIn(50L, 5_000L)
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = DeviceVerificationResult(false, "wait_timeout")
        var checks = 0

        while (checks < maxChecks.coerceAtLeast(1) &&
            System.currentTimeMillis() < deadline
        ) {
            checks++
            currentCoroutineContext().ensureActive()
            last = verifier.verify(postcondition, before)
            if (last.verified) return last
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) break
            delay(minOf(effectivePollMs, remaining))
        }

        return last.copy(
            verified = false,
            reason = "timeout:" + postcondition.type.name.lowercase(),
        )
    }
}
