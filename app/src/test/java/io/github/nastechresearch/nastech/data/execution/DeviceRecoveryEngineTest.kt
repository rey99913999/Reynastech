package io.github.nastechresearch.nastech.data.execution

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRecoveryEngineTest {
    @Test
    fun recoveryUsesFallbackOnlyAfterPrimaryFailure() = runBlocking {
        val calls = mutableListOf<String>()
        val engine = DeviceRecoveryEngine(maxAttempts = 3)

        val result = engine.run(
            primary = {
                calls += "primary"
                "failed"
            },
            fallbacks = listOf(
                {
                    calls += "retry"
                    "success"
                }
            ),
            isSuccess = { it == "success" },
        )

        assertEquals(listOf("primary", "retry"), calls)
        assertEquals("success", result.result)
        assertTrue(result.recovered)
        assertEquals(2, result.attempts)
    }
}
