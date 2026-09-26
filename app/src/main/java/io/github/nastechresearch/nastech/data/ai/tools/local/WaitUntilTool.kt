package io.github.nastechresearch.nastech.data.ai.tools.local

import android.content.Context
import io.github.nastechresearch.nastech.data.ai.tools.ToolInvocationContext
import io.github.nastechresearch.nastech.data.execution.DevicePostcondition
import io.github.nastechresearch.nastech.data.execution.DevicePostconditionType
import io.github.nastechresearch.nastech.data.execution.DeviceStateWaiter
import io.github.nastechresearch.nastech.data.execution.DeviceAccessibilitySelector
import io.github.nastechresearch.nastech.data.execution.DeviceObservation
import io.github.nastechresearch.nastech.data.execution.DeviceObserver
import io.github.nastechresearch.nastech.data.model.WaitUntilCondition
import io.github.nastechresearch.nastech.data.model.WaitUntilSettings
import io.github.nastechresearch.nastech.service.RikkaAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.util.Locale

private const val HARD_MAX_WAIT_MS = 300_000L

internal fun parseWaitUntilCondition(
    raw: String?,
    defaultCondition: WaitUntilCondition,
): WaitUntilCondition? {
    val value = raw?.trim()?.lowercase(Locale.ROOT)
    if (value.isNullOrBlank()) return defaultCondition
    return when (value) {
        "fixed_delay" -> WaitUntilCondition.FIXED_DELAY
        "text_appears" -> WaitUntilCondition.TEXT_APPEARS
        "text_disappears" -> WaitUntilCondition.TEXT_DISAPPEARS
        "ui_element_appears" -> WaitUntilCondition.UI_ELEMENT_APPEARS
        "screen_changes" -> WaitUntilCondition.SCREEN_CHANGED
        "screen_stable" -> WaitUntilCondition.SCREEN_STABLE
        else -> null
    }
}

internal fun waitUntilConditionWireName(condition: WaitUntilCondition): String = when (condition) {
    WaitUntilCondition.FIXED_DELAY -> "fixed_delay"
    WaitUntilCondition.TEXT_APPEARS -> "text_appears"
    WaitUntilCondition.TEXT_DISAPPEARS -> "text_disappears"
    WaitUntilCondition.UI_ELEMENT_APPEARS -> "ui_element_appears"
    WaitUntilCondition.SCREEN_CHANGED -> "screen_changes"
    WaitUntilCondition.SCREEN_STABLE -> "screen_stable"
}

internal fun normalizeWaitUntilIntervalMs(value: Long): Long {
    val safe = value.coerceIn(250L, 5_000L)
    return WaitUntilSettings.SUPPORTED_INTERVALS.minBy { kotlin.math.abs(it - safe) }
}

private fun success(
    condition: WaitUntilCondition,
    elapsedMs: Long,
    matchedBy: String,
): UIMessagePart.Text =
    UIMessagePart.Text(
        buildJsonObject {
            put("status", "completed")
            put("condition_met", true)
            put("condition", waitUntilConditionWireName(condition))
            put("elapsed_ms", elapsedMs)
            put("matched_by", matchedBy)
        }.toString(),
    )

private fun timeout(
    condition: WaitUntilCondition,
    elapsedMs: Long,
    lastPackage: String?,
): UIMessagePart.Text =
    UIMessagePart.Text(
        buildJsonObject {
            put("status", "timeout")
            put("condition_met", false)
            put("condition", waitUntilConditionWireName(condition))
            put("elapsed_ms", elapsedMs)
            put("reason", "condition_not_met")
            if (!lastPackage.isNullOrBlank()) put("last_observed_package", lastPackage)
        }.toString(),
    )

private fun error(code: String, detail: String? = null): UIMessagePart.Text =
    UIMessagePart.Text(
        buildJsonObject {
            put("status", "error")
            put("error", code)
            if (!detail.isNullOrBlank()) put("detail", detail)
        }.toString(),
    )

private fun parseSelector(input: JsonObject): DeviceAccessibilitySelector? {
    val selector = input["selector"] as? JsonObject ?: return null
    val by = selector["by"]?.jsonPrimitive?.contentOrNull ?: when {
        selector["text"] != null -> "text"
        selector["content_description"] != null -> "content_description"
        selector["view_id_resource_name"] != null -> "view_id_resource_name"
        else -> null
    } ?: return null
    if (by !in setOf("text", "content_description", "view_id_resource_name")) return null
    val value = selector["value"]?.jsonPrimitive?.contentOrNull
        ?: selector[by]?.jsonPrimitive?.contentOrNull
        ?: return null
    val nth = selector["nth"]?.jsonPrimitive?.intOrNull ?: 0
    if (nth < 0) return null
    val packageName = input["package_name"]?.jsonPrimitive?.contentOrNull
        ?: selector["package_name"]?.jsonPrimitive?.contentOrNull
    return DeviceAccessibilitySelector(by = by, value = value, nth = nth, packageName = packageName)
}

private suspend fun visionFallback(
    context: Context,
    invocationContext: ToolInvocationContext,
    target: String,
    timeoutMs: Long,
): String? {
    if (timeoutMs <= 0L) return null
    return try {
        val text = withTimeoutOrNull(timeoutMs) {
            uiFindVisualTargetTool(context, invocationContext)
                .execute(buildJsonObject { put("target", target) })
                .filterIsInstance<UIMessagePart.Text>()
                .firstOrNull()
                ?.text
        } ?: return null
        val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val state = obj["state"]?.jsonPrimitive?.contentOrNull
        val source = obj["source"]?.jsonPrimitive?.contentOrNull
        if (state.equals("found", ignoreCase = true) && !source.isNullOrBlank() && source != "not_found") {
            source
        } else {
            null
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }
}

fun waitUntilTool(
    context: Context,
    observer: DeviceObserver,
    waiter: DeviceStateWaiter,
    invocationContext: ToolInvocationContext,
): Tool = Tool(
    name = "wait_until",
    description = "Wait for a bounded Android device condition without performing side effects. Conditions: fixed_delay, text_appears, text_disappears, ui_element_appears, screen_changes, screen_stable. Uses Accessibility/UI Tree first, OCR when enabled, and one bounded Vision fallback when enabled.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("condition", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional: fixed_delay | text_appears | text_disappears | ui_element_appears | screen_changes | screen_stable")
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Requested maximum wait or fixed-delay duration; bounded by the assistant setting and a 300 second application ceiling.")
                })
                put("interval_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional polling interval. Supported values: 250, 500, 1000, 2000, 5000 ms.")
                })
                put("expected_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Required for text_appears and text_disappears.")
                })
                put("selector", buildJsonObject {
                    put("type", "object")
                    put("description", "Required for ui_element_appears. Reuses find_node/click_node selector semantics.")
                })
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional foreground package guard.")
                })
            },
        )
    },
    execute = { input ->
        currentCoroutineContext().ensureActive()
        val settings = invocationContext.waitUntilSettings.normalized()
        val condition = parseWaitUntilCondition(
            raw = input["condition"]?.jsonPrimitive?.contentOrNull,
            defaultCondition = settings.defaultCondition,
        ) ?: return@Tool listOf(error("invalid_condition"))

        val requestedTimeout = input["timeout_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        if (requestedTimeout != null && requestedTimeout <= 0L) {
            return@Tool listOf(error("invalid_timeout", "timeout_ms must be greater than zero"))
        }

        val configuredMaxWaitMs = settings.maxWaitSeconds * 1_000L
        val timeoutMs = (requestedTimeout ?: configuredMaxWaitMs)
            .coerceAtMost(configuredMaxWaitMs)
            .coerceAtMost(HARD_MAX_WAIT_MS)
        val intervalMs = normalizeWaitUntilIntervalMs(
            input["interval_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: settings.checkIntervalMs
        )
        val packageName = input["package_name"]?.jsonPrimitive?.contentOrNull

        if (condition != WaitUntilCondition.FIXED_DELAY &&
            RikkaAccessibilityService.instance == null
        ) {
            return@Tool listOf(
                error(
                    "accessibility_unavailable",
                    "Accessibility service is required for device observation.",
                )
            )
        }

        val startedAt = System.currentTimeMillis()

        when (condition) {
            WaitUntilCondition.FIXED_DELAY -> {
                delay(timeoutMs)
                currentCoroutineContext().ensureActive()
                listOf(success(condition, System.currentTimeMillis() - startedAt, "fixed_delay"))
            }

            WaitUntilCondition.TEXT_APPEARS,
            WaitUntilCondition.TEXT_DISAPPEARS -> {
                val expected = input["expected_text"]?.jsonPrimitive?.contentOrNull?.trim()
                if (expected.isNullOrBlank()) {
                    return@Tool listOf(error("expected_text_required"))
                }
                if (!settings.useAccessibility && !settings.useOcr) {
                    return@Tool listOf(
                        error(
                            "observation_unavailable",
                            "Enable Accessibility or OCR for text conditions.",
                        )
                    )
                }

                val type = if (condition == WaitUntilCondition.TEXT_APPEARS) {
                    DevicePostconditionType.TEXT_PRESENT
                } else {
                    DevicePostconditionType.TEXT_ABSENT
                }

                val result = waiter.waitFor(
                    postcondition = DevicePostcondition(
                        type = type,
                        value = expected,
                        timeoutMs = timeoutMs,
                    ),
                    before = null,
                    maxWaitMs = configuredMaxWaitMs,
                    pollMs = intervalMs,
                )

                if (result.verified) {
                    listOf(
                        success(
                            condition,
                            System.currentTimeMillis() - startedAt,
                            result.observationSource ?: "accessibility",
                        )
                    )
                } else {
                    listOf(timeout(condition, System.currentTimeMillis() - startedAt, null))
                }
            }

            WaitUntilCondition.UI_ELEMENT_APPEARS -> {
                val selector = parseSelector(input)
                    ?: return@Tool listOf(
                        error(
                            "selector_required",
                            "selector must specify by/value or a supported selector dimension",
                        )
                    )

                if (!settings.useAccessibility && !settings.useVisionFallback) {
                    return@Tool listOf(
                        error(
                            "observation_unavailable",
                            "Enable Accessibility or Vision fallback for UI-element waits.",
                        )
                    )
                }

                val localBudget = if (settings.useVisionFallback) {
                    (timeoutMs * 0.8).toLong().coerceAtLeast(intervalMs)
                } else {
                    timeoutMs
                }

                val localResult = if (settings.useAccessibility) {
                    waiter.waitFor(
                        postcondition = DevicePostcondition(
                            type = DevicePostconditionType.UI_ELEMENT_PRESENT,
                            selector = selector,
                            timeoutMs = localBudget,
                        ),
                        before = null,
                        maxWaitMs = localBudget,
                        pollMs = intervalMs,
                    )
                } else {
                    null
                }

                if (localResult?.verified == true) {
                    listOf(success(condition, System.currentTimeMillis() - startedAt, "accessibility"))
                } else {
                    val remaining = timeoutMs - (System.currentTimeMillis() - startedAt)
                    val visionSource = if (settings.useVisionFallback && remaining > 0L) {
                        visionFallback(
                            context = context,
                            invocationContext = invocationContext,
                            target = selector.value,
                            timeoutMs = remaining,
                        )
                    } else {
                        null
                    }

                    if (visionSource != null) {
                        listOf(success(condition, System.currentTimeMillis() - startedAt, visionSource))
                    } else {
                        listOf(timeout(condition, System.currentTimeMillis() - startedAt, null))
                    }
                }
            }

            WaitUntilCondition.SCREEN_CHANGED -> {
                if (!settings.useScreenState) {
                    return@Tool listOf(
                        error(
                            "screen_state_unavailable",
                            "Enable screen-state/fingerprint checks for screen_changes.",
                        )
                    )
                }
                val before = observer.observe(
                    captureScreenshot = false,
                    captureOcr = false,
                )
                val result = waiter.waitFor(
                    postcondition = DevicePostcondition(
                        type = DevicePostconditionType.SCREEN_FINGERPRINT_CHANGED,
                        timeoutMs = timeoutMs,
                    ),
                    before = before,
                    maxWaitMs = configuredMaxWaitMs,
                    pollMs = intervalMs,
                )
                if (result.verified) {
                    listOf(
                        success(
                            condition,
                            System.currentTimeMillis() - startedAt,
                            result.observationSource ?: "screen_fingerprint",
                        )
                    )
                } else {
                    listOf(timeout(condition, System.currentTimeMillis() - startedAt, null))
                }
            }

            WaitUntilCondition.SCREEN_STABLE -> {
                if (!settings.useScreenState) {
                    return@Tool listOf(
                        error(
                            "screen_state_unavailable",
                            "Enable screen-state/fingerprint checks for screen_stable.",
                        )
                    )
                }
                val before = observer.observe(
                    captureScreenshot = false,
                    captureOcr = false,
                )
                val result = waiter.waitFor(
                    postcondition = DevicePostcondition(
                        type = DevicePostconditionType.SCREEN_STABLE,
                        timeoutMs = timeoutMs,
                    ),
                    before = before,
                    maxWaitMs = configuredMaxWaitMs,
                    pollMs = intervalMs,
                )
                if (result.verified) {
                    listOf(
                        success(
                            condition,
                            System.currentTimeMillis() - startedAt,
                            result.observationSource ?: "screen_fingerprint",
                        )
                    )
                } else {
                    listOf(timeout(condition, System.currentTimeMillis() - startedAt, null))
                }
            }
        }
    },
)
