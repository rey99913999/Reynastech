package io.github.nastechresearch.nastech.data.ai

import java.io.IOException
import kotlinx.coroutines.CancellationException
import me.rerere.ai.provider.providers.openai.ResponseStreamErrorException
import me.rerere.ai.util.HttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationHandlerTransportRetryTest {
    @Test
    fun `parses provider retry durations`() {
        assertEquals(
            39_866L,
            extractRetryAfterDelayMs(
                HttpException(
                    "Quota exceeded for metric generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 15. Please retry in 39.865980821s.",
                    statusCode = 429,
                ),
            ),
        )
        assertEquals(120_000L, extractRetryAfterDelayMs(HttpException("""{"retryDelay":"2m"}""")))
        assertEquals(40_000L, extractRetryAfterDelayMs(HttpException("Retry-After: 40")))
        assertNull(extractRetryAfterDelayMs(HttpException("rate limited without a duration")))
    }

    @Test
    fun `distinguishes rate limits from exhausted quota`() {
        val googlePerMinuteLimit = HttpException(
            "You exceeded your current quota, please check your plan and billing details. Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 15, model: gemini-3.5-flash-lite. Please retry in 39.865980821s.",
            statusCode = 429,
        )
        assertTrue(isRateLimitFailure(googlePerMinuteLimit))
        assertFalse(isQuotaExhaustionFailure(googlePerMinuteLimit))
        assertTrue(isQuotaExhaustionFailure(HttpException("You exceeded your current quota, check billing", statusCode = 400)))
        assertFalse(isRateLimitFailure(HttpException("insufficient_quota", statusCode = 429)))
        assertTrue(isQuotaExhaustionFailure(HttpException("insufficient_quota", statusCode = 429)))
        assertTrue(isQuotaExhaustionFailure(HttpException("You exceeded your current quota, check billing", statusCode = 429)))
    }

    @Test
    fun `rate limit policy waits or switches according to user threshold`() {
        assertEquals(
            RateLimitRecoveryDecision(RateLimitRecoveryAction.WAIT_AND_RETRY, 45_000L),
            decideRateLimitRecovery(40_000L, 300_000L, 120_000L, alreadyWaitedOnThisModel = false),
        )
        assertEquals(
            RateLimitRecoveryDecision(RateLimitRecoveryAction.SWITCH_MODEL),
            decideRateLimitRecovery(420_000L, 300_000L, 120_000L, alreadyWaitedOnThisModel = false),
        )
        assertEquals(
            RateLimitRecoveryDecision(RateLimitRecoveryAction.WAIT_AND_RETRY, 120_000L),
            decideRateLimitRecovery(null, 300_000L, 120_000L, alreadyWaitedOnThisModel = false),
        )
        assertEquals(
            RateLimitRecoveryDecision(RateLimitRecoveryAction.SWITCH_MODEL),
            decideRateLimitRecovery(40_000L, 300_000L, 120_000L, alreadyWaitedOnThisModel = true),
        )
    }

    @Test
    fun `fallback candidate indexes cycle without a fixed retry cap`() {
        assertEquals(listOf(0, 1, 2, 3), fallbackCandidateIndexes(-1, 4))
        assertEquals(listOf(1, 2, 3, 0), fallbackCandidateIndexes(0, 4))
        assertEquals(listOf(0, 1, 2, 3), fallbackCandidateIndexes(3, 4))
        assertEquals(emptyList<Int>(), fallbackCandidateIndexes(0, 0))
    }
    @Test
    fun `falls back on exhausted quota and transient server failures before output`() {
        assertTrue(shouldFallbackToModel(HttpException("insufficient_quota", statusCode = 429), false))
        assertTrue(shouldFallbackToModel(HttpException("insufficient_quota", statusCode = 400), false))
        assertTrue(shouldFallbackToModel(HttpException("payment required", statusCode = 402), false))
        assertTrue(shouldFallbackToModel(HttpException("upstream error", statusCode = 503), false))
        assertTrue(shouldFallbackToModel(HttpException("The server had an error processing your request"), false))
        assertTrue(shouldFallbackToModel(HttpException("overloaded_error"), false))
        assertTrue(shouldFallbackToModel(IOException("connection reset"), false))
    }

    @Test
    fun `does not fall back after meaningful partial output`() {
        assertFalse(shouldFallbackToModel(IOException("connection reset"), true))
        assertFalse(shouldFallbackToModel(HttpException("rate limited", statusCode = 429), true))
    }

    @Test
    fun `does not fall back for invalid credentials or deterministic requests`() {
        assertFalse(shouldFallbackToModel(HttpException("invalid API key", statusCode = 401), false))
        assertFalse(shouldFallbackToModel(HttpException("forbidden", statusCode = 403), false))
        assertFalse(shouldFallbackToModel(HttpException("bad request", statusCode = 400), false))
    }

    @Test
    fun `context fallback requires a known strictly larger context window`() {
        assertTrue(hasLargerContextWindow(8_000, 32_000))
        assertFalse(hasLargerContextWindow(32_000, 8_000))
        assertFalse(hasLargerContextWindow(8_000, 8_000))
        assertFalse(hasLargerContextWindow(null, 32_000))
        assertFalse(hasLargerContextWindow(8_000, null))
    }

    @Test
    fun `does not fall back for context limits or cancellation`() {
        assertFalse(
            shouldFallbackToModel(
                ResponseStreamErrorException(
                    code = "context_length_exceeded",
                    message = "maximum context length exceeded",
                ),
                receivedMeaningfulOutput = false,
            )
        )
        assertFalse(shouldFallbackToModel(CancellationException("user cancelled"), false))
    }

    @Test
    fun `does not report an empty stream once a chunk arrived, even without parseable parts`() {
        assertFalse(shouldReportEmptyGenerationStream(receivedAnyChunk = true))
    }

    @Test
    fun `still reports an empty stream when zero chunks arrived`() {
        assertTrue(shouldReportEmptyGenerationStream(receivedAnyChunk = false))
    }

    @Test
    fun `retries an abort before any model output`() {
        assertTrue(
            shouldRetryGenerationStreamFailure(
                failure = IOException("Software caused connection abort"),
                retryAttempt = 0,
                maxRetries = 2,
                receivedMeaningfulOutput = false,
            )
        )
    }

    @Test
    fun `does not replay a stream after meaningful output`() {
        assertFalse(
            shouldRetryGenerationStreamFailure(
                failure = IOException("connection reset"),
                retryAttempt = 0,
                maxRetries = 2,
                receivedMeaningfulOutput = true,
            )
        )
    }

    @Test
    fun `does not retry after the configured attempts`() {
        assertFalse(
            shouldRetryGenerationStreamFailure(
                failure = IOException("connection reset"),
                retryAttempt = 2,
                maxRetries = 2,
                receivedMeaningfulOutput = false,
            )
        )
    }

    @Test
    fun `retries non network failures`() {
        assertTrue(
            shouldRetryGenerationStreamFailure(
                failure = IllegalStateException("response body could not be decoded"),
                retryAttempt = 0,
                maxRetries = 5,
                receivedMeaningfulOutput = false,
            )
        )
    }

    @Test
    fun `does not retry user cancellation`() {
        assertFalse(
            shouldRetryGenerationStreamFailure(
                failure = CancellationException("Generation cancelled by user"),
                retryAttempt = 0,
                maxRetries = 5,
                receivedMeaningfulOutput = false,
            )
        )
    }

    @Test
    fun `does not retry a provider business error`() {
        assertFalse(
            shouldRetryGenerationStreamFailure(
                failure = ResponseStreamErrorException(
                    code = "context_length_exceeded",
                    message = "Response API response.failed [context_length_exceeded]: maximum context length",
                ),
                retryAttempt = 0,
                maxRetries = 10,
                receivedMeaningfulOutput = false,
            )
        )
    }

    @Test
    fun `does not retry deterministic 4xx client errors`() {
        for (statusCode in listOf(400, 401, 403, 404)) {
            assertFalse(
                "status $statusCode should not retry",
                shouldRetryGenerationStreamFailure(
                    failure = HttpException("client error", statusCode = statusCode),
                    retryAttempt = 0,
                    maxRetries = 5,
                    receivedMeaningfulOutput = false,
                )
            )
        }
    }

    @Test
    fun `does not waste retries on a confirmed exhausted quota`() {
        assertFalse(
            shouldRetryGenerationStreamFailure(
                failure = HttpException("insufficient_quota", statusCode = 429),
                retryAttempt = 0,
                maxRetries = 5,
                receivedMeaningfulOutput = false,
            )
        )
        assertFalse(
            shouldRetryGenerationStreamFailure(
                failure = HttpException("billing_hard_limit_reached", statusCode = 400),
                retryAttempt = 0,
                maxRetries = 5,
                receivedMeaningfulOutput = false,
            )
        )
        // Rate limits are handled by the outer policy so Retry-After and the user threshold
        // are respected instead of being consumed by the short transport retry loop.
        assertFalse(
            shouldRetryGenerationStreamFailure(
                failure = HttpException("rate limit exceeded", statusCode = 429),
                retryAttempt = 0,
                maxRetries = 5,
                receivedMeaningfulOutput = false,
            )
        )
    }

    @Test
    fun `retries the 4xx statuses that signal a transient condition`() {
        for (statusCode in listOf(408, 409, 425)) {
            assertTrue(
                "status $statusCode should retry",
                shouldRetryGenerationStreamFailure(
                    failure = HttpException("transient client error", statusCode = statusCode),
                    retryAttempt = 0,
                    maxRetries = 5,
                    receivedMeaningfulOutput = false,
                )
            )
        }
    }

    @Test
    fun `retries 5xx server errors`() {
        for (statusCode in listOf(500, 503)) {
            assertTrue(
                "status $statusCode should retry",
                shouldRetryGenerationStreamFailure(
                    failure = HttpException("server error", statusCode = statusCode),
                    retryAttempt = 0,
                    maxRetries = 5,
                    receivedMeaningfulOutput = false,
                )
            )
        }
    }

    @Test
    fun `retries a failure with no status code attached`() {
        assertTrue(
            shouldRetryGenerationStreamFailure(
                failure = HttpException("no status known", statusCode = null),
                retryAttempt = 0,
                maxRetries = 5,
                receivedMeaningfulOutput = false,
            )
        )
    }

    @Test
    fun `a wrapped deterministic 4xx is still found through the cause chain`() {
        val wrapped = RuntimeException(
            "wrapper",
            HttpException("client error", statusCode = 400),
        )
        assertFalse(
            shouldRetryGenerationStreamFailure(
                failure = wrapped,
                retryAttempt = 0,
                maxRetries = 5,
                receivedMeaningfulOutput = false,
            )
        )
    }

    @Test
    fun `a deterministic 4xx still yields to max-retries and cancellation precedence`() {
        assertFalse(
            "receivedMeaningfulOutput still wins",
            shouldRetryGenerationStreamFailure(
                failure = HttpException("client error", statusCode = 429),
                retryAttempt = 0,
                maxRetries = 5,
                receivedMeaningfulOutput = true,
            )
        )
        assertFalse(
            "max retries still wins",
            shouldRetryGenerationStreamFailure(
                failure = HttpException("client error", statusCode = 429),
                retryAttempt = 5,
                maxRetries = 5,
                receivedMeaningfulOutput = false,
            )
        )
    }
}
