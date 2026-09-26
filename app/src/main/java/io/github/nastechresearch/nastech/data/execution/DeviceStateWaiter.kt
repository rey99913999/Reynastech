package io.github.nastechresearch.nastech.data.execution

import kotlinx.coroutines.delay

fun interface DeviceStateWaiter {
    suspend fun waitFor(
        postcondition: DevicePostcondition,
        before: DeviceObservation?,
    ): DeviceVerificationResult
}

class BoundedDeviceStateWaiter(
    private val verifier: DevicePostconditionVerifier,
    private val maxTotalWaitMs: Long = 30_000L,
    private val pollMs: Long = 250L,
) : DeviceStateWaiter {

    override suspend fun waitFor(
        postcondition: DevicePostcondition,
        before: DeviceObservation?,
    ): DeviceVerificationResult {
        val timeoutMs = postcondition.timeoutMs.coerceIn(100L, maxTotalWaitMs)
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = DeviceVerificationResult(false, "wait_timeout")

        while (System.currentTimeMillis() < deadline) {
            last = verifier.verify(postcondition, before)
            if (last.verified) return last
            delay(pollMs.coerceIn(50L, 1_000L))
        }

        return last.copy(
            verified = false,
            reason = "timeout:${postcondition.type.name.lowercase()}",
        )
    }
}
