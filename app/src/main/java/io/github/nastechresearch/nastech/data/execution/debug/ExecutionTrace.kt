package io.github.nastechresearch.nastech.data.execution.debug

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import io.github.nastechresearch.nastech.utils.JsonInstant

@Serializable
enum class ExecutionTraceStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED,
    RETRIED,
    WAITING,
    CANCELLED,
}

@Serializable
data class ExecutionTraceStep(
    val index: Int,
    val action: String,
    val method: String? = null,
    val status: ExecutionTraceStatus = ExecutionTraceStatus.PENDING,
    val tool: String? = null,
    val inputsSummary: String? = null,
    val resultSummary: String? = null,
    val error: String? = null,
    val startedAtMs: Long? = null,
    val endedAtMs: Long? = null,
    val retries: Int = 0,
    val checkpoint: Boolean = false,
    val diagnosticRef: String? = null,
    val expectedState: String? = null,
    val actualState: String? = null,
    val visionConfidence: Double? = null,
)

@Serializable
data class ExecutionTrace(
    val runId: String,
    val taskId: String? = null,
    val workflowId: String? = null,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val currentStep: Int? = null,
    val status: ExecutionTraceStatus = ExecutionTraceStatus.PENDING,
    val steps: List<ExecutionTraceStep> = emptyList(),
    val checkpointStep: Int? = null,
    val diagnostics: List<String> = emptyList(),
    val diagnosis: String? = null,
    val parentRunId: String? = null,
    val retryCount: Int = 0,
)

object ExecutionTraceCodec {
    fun encode(trace: ExecutionTrace): String = JsonInstant.encodeToString(trace)

    fun decode(raw: String?): ExecutionTrace? {
        if (raw.isNullOrBlank()) return null
        return runCatching { JsonInstant.decodeFromString<ExecutionTrace>(raw) }.getOrNull()
    }
}

fun safeJsonSummary(element: JsonElement, maxLength: Int = 500): String {
    fun redact(key: String): Boolean = key.lowercase() in setOf(
        "token", "password", "secret", "api_key", "apikey", "authorization", "cookie"
    )

    fun render(value: JsonElement, key: String? = null): String = when {
        key != null && redact(key) -> "<redacted>"
        value is kotlinx.serialization.json.JsonObject ->
            value.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
                "$k:${render(v, k)}"
            }
        value is kotlinx.serialization.json.JsonArray ->
            value.joinToString(prefix = "[", postfix = "]") { render(it) }
        else -> value.toString()
    }

    return render(element).take(maxLength)
}

fun humanReadableExecutionError(error: String?): String {
    val raw = error?.trim().orEmpty()
    if (raw.isBlank()) return "The step failed without a recorded error."
    val normalized = raw.lowercase()
    return when {
        "permission" in normalized || "denied" in normalized ->
            "Permission was denied while executing the step."
        "timeout" in normalized || "exceeded" in normalized ->
            "The step exceeded its allowed time."
        "network" in normalized || "connection" in normalized ->
            "A network connection failed or timed out."
        "vision" in normalized || ("target" in normalized && "found" in normalized) ->
            "The requested visual target could not be found."
        "unknown_tool" in normalized ->
            "The required tool is not available."
        "invalid" in normalized || "malformed" in normalized ->
            "The tool returned an invalid result."
        else -> raw.take(500)
    }
}
