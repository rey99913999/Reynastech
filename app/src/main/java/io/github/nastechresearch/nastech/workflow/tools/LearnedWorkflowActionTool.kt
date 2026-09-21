package io.github.nastechresearch.nastech.workflow.tools

import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import io.github.nastechresearch.nastech.data.agent.AgentConfigRepository
import io.github.nastechresearch.nastech.data.vision.VisionPipeline
import io.github.nastechresearch.nastech.data.ai.tools.ToolInvocationContext
import io.github.nastechresearch.nastech.service.RikkaAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

private object LearnedActionKoin : KoinComponent

private data class TargetMatch(
    val node: AccessibilityNodeInfo?,
    val bounds: Rect,
    val confidence: Float,
    val source: String,
)

private fun findSemanticNode(
    root: AccessibilityNodeInfo,
    target: String,
    packageName: String?,
): AccessibilityNodeInfo? {
    val wanted = target.trim().lowercase()
    if (wanted.isBlank()) return null
    var partial: AccessibilityNodeInfo? = null

    fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (!node.isVisibleToUser) return null
        val nodePackage = node.packageName?.toString().orEmpty()
        if (packageName.isNullOrBlank() || nodePackage == packageName) {
            val text = node.text?.toString().orEmpty().lowercase()
            val desc = node.contentDescription?.toString().orEmpty().lowercase()
            val id = node.viewIdResourceName.orEmpty().lowercase()
            if (text == wanted || desc == wanted || id.endsWith(wanted)) return node
            if (partial == null && (text.contains(wanted) || desc.contains(wanted))) {
                partial = node
            }
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                val exact = walk(child)
                if (exact != null) return exact
            }
        }
        return null
    }

    return walk(root) ?: partial
}

private fun findNodeAtBounds(root: AccessibilityNodeInfo, bounds: Rect): AccessibilityNodeInfo? {
    var best: AccessibilityNodeInfo? = null
    var bestArea = Int.MAX_VALUE
    fun walk(node: AccessibilityNodeInfo) {
        if (node.isVisibleToUser) {
            val rect = Rect().also { node.getBoundsInScreen(it) }
            if (rect.contains(bounds.centerX(), bounds.centerY())) {
                val area = (rect.width().coerceAtLeast(1) * rect.height().coerceAtLeast(1))
                if (area < bestArea) {
                    bestArea = area
                    best = node
                }
            }
        }
        for (index in 0 until node.childCount) node.getChild(index)?.let(::walk)
    }
    walk(root)
    return best
}

private suspend fun resolveTarget(
    context: android.content.Context,
    target: String,
    packageName: String?,
    agentConfig: AgentConfigRepository,
): TargetMatch? {
    val service = RikkaAccessibilityService.instance ?: return null
    val root = service.rootInActiveWindow ?: return null

    findSemanticNode(root, target, packageName)?.let { node ->
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return TargetMatch(node, bounds, 0.98f, "ui_tree")
    }

    val decision = VisionPipeline(context, agentConfig).findTarget(
        target = target,
        displayId = 0,
        highConfidence = 0.90f,
        mediumConfidence = 0.70f,
        visionModelId = null,
    )
    val bounds = decision.bounds?.let {
        Rect(it.left, it.top, it.right, it.bottom)
    } ?: return null
    val node = findNodeAtBounds(root, bounds)
    return TargetMatch(node, bounds, decision.confidence, decision.source)
}

private suspend fun tapAt(
    service: RikkaAccessibilityService,
    bounds: Rect,
    durationMs: Long,
): Boolean {
    val path = service.buildTapPath(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    val gesture = android.accessibilityservice.GestureDescription.Builder()
        .addStroke(
            android.accessibilityservice.GestureDescription.StrokeDescription(
                path, 0L, durationMs
            )
        )
        .build()
    return service.dispatchGestureAsync(gesture)
}

private suspend fun scrollActive(
    service: RikkaAccessibilityService,
    direction: String,
): Boolean {
    val root = service.rootInActiveWindow ?: return false
    fun firstScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                firstScrollable(child)?.let { return it }
            }
        }
        return null
    }
    val scrollable = firstScrollable(root)
    if (scrollable != null) {
        return scrollable.performAction(
            if (direction == "up" || direction == "left") {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
        )
    }

    val rect = Rect().also { root.getBoundsInScreen(it) }
    val cx = rect.centerX().toFloat()
    val cy = rect.centerY().toFloat()
    val h = (rect.height() / 3f).coerceAtLeast(120f)
    val w = (rect.width() / 3f).coerceAtLeast(120f)
    val values = when (direction) {
        "down" -> floatArrayOf(cx, cy + h, cx, cy - h)
        "left" -> floatArrayOf(cx - w, cy, cx + w, cy)
        "right" -> floatArrayOf(cx + w, cy, cx - w, cy)
        else -> floatArrayOf(cx, cy - h, cx, cy + h)
    }
    val path = Path().apply {
        moveTo(values[0], values[1])
        lineTo(values[2], values[3])
    }
    return service.dispatchGestureAsync(
        android.accessibilityservice.GestureDescription.Builder()
            .addStroke(
                android.accessibilityservice.GestureDescription.StrokeDescription(
                    path, 0L, 300L
                )
            )
            .build()
    )
}

private fun performGlobalAction(service: RikkaAccessibilityService, action: String): Boolean =
    when (action) {
        "back" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        "home" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        "recents" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        else -> false
    }

fun learnedWorkflowActionTool(
    context: android.content.Context,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
): Tool = Tool(
    name = "learned_action",
    description = "Execute a reviewed learned-workflow action with semantic UI-tree lookup, OCR/Vision fallback, ordered alternatives, and post-action verification.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("kind", buildJsonObject {
                    put("type", "string")
                    put("description", "tap, long_press, set_text, scroll, or global")
                })
                put("target", buildJsonObject {
                    put("type", "string")
                    put("description", "Semantic target text or meaning, such as Install or Enable")
                })
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional target application package")
                })
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Text to set when kind=set_text")
                })
                put("direction", buildJsonObject {
                    put("type", "string")
                    put("description", "up, down, left, or right when kind=scroll")
                })
                put("global_action", buildJsonObject {
                    put("type", "string")
                    put("description", "back, home, or recents when kind=global")
                })
                put("duration_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Press duration for long_press, 100..5000ms")
                })
                put("verify_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Text that must be visible after the action")
                })
                put("verify_package", buildJsonObject {
                    put("type", "string")
                    put("description", "Package that must be foreground after the action")
                })
                put("verify_change", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Require the UI fingerprint to change")
                })
                put("alternatives", buildJsonObject {
                    put("type", "array")
                    put("description", "Ordered fallback action objects used when the primary action or verification fails")
                    put("items", buildJsonObject { put("type", "object") })
                })
            },
            required = listOf("kind"),
        )
    },
    needsApproval = { true },
    execute = { input ->
        val agentConfig = LearnedActionKoin.get<AgentConfigRepository>()

        suspend fun attempt(spec: JsonObject): Pair<Boolean, String> {
            val service = RikkaAccessibilityService.instance
                ?: return false to """{"error":"accessibility_service_unavailable"}"""

            val before = VisionPipeline(context, agentConfig)
                .captureObservation(includeOcr = true, saveScreenshot = false).first

            val target = spec["target"]?.jsonPrimitive?.contentOrNull
            val packageName = spec["package_name"]?.jsonPrimitive?.contentOrNull
            val match = if (!target.isNullOrBlank()) {
                resolveTarget(context, target, packageName, agentConfig)
            } else null

            if (!target.isNullOrBlank() && match == null) {
                return false to buildJsonObject {
                    put("error", "target_not_found")
                    put("target", target)
                }.toString()
            }

            if (match != null && match.confidence < 0.70f) {
                return false to buildJsonObject {
                    put("error", "low_target_confidence")
                    put("confidence", match.confidence)
                    put("source", match.source)
                }.toString()
            }

            val kind = spec["kind"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val actionOk = when (kind) {
                "tap" -> match?.let { tapAt(service, it.bounds, 50L) } == true
                "long_press" -> match?.let {
                    val duration = spec["duration_ms"]?.jsonPrimitive?.contentOrNull
                        ?.toLongOrNull()?.coerceIn(100L, 5000L) ?: 600L
                    tapAt(service, it.bounds, duration)
                } == true
                "set_text" -> {
                    val node = match?.node
                    val value = spec["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    node?.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                value,
                            )
                        },
                    ) == true
                }
                "scroll" -> scrollActive(
                    service,
                    spec["direction"]?.jsonPrimitive?.contentOrNull ?: "down",
                )
                "global" -> performGlobalAction(
                    service,
                    spec["global_action"]?.jsonPrimitive?.contentOrNull ?: "back",
                )
                else -> false
            }

            if (!actionOk) {
                return false to buildJsonObject {
                    put("error", "action_failed")
                    put("kind", kind)
                    put("target_confidence", match?.confidence ?: 1f)
                }.toString()
            }

            delay(250L)
            val after = VisionPipeline(context, agentConfig)
                .captureObservation(includeOcr = true, saveScreenshot = false).first

            val expectedText = spec["verify_text"]?.jsonPrimitive?.contentOrNull
            val expectedPackage = spec["verify_package"]?.jsonPrimitive?.contentOrNull
            val requireChange = spec["verify_change"]?.jsonPrimitive?.contentOrNull
                ?.toBooleanStrictOrNull() ?: false
            val textOk = expectedText.isNullOrBlank() ||
                after.ocr.any { it.text.contains(expectedText, ignoreCase = true) } ||
                after.elements.any { it.text.contains(expectedText, ignoreCase = true) }
            val packageOk = expectedPackage.isNullOrBlank() || after.packageName == expectedPackage
            val changed = before.fingerprint != after.fingerprint
            val verified = textOk && packageOk && (!requireChange || changed)

            if (!verified) {
                return false to buildJsonObject {
                    put("error", "verification_failed")
                    put("expected_text_present", textOk)
                    put("package_ok", packageOk)
                    put("changed", changed)
                }.toString()
            }

            return true to buildJsonObject {
                put("success", true)
                put("verified", !expectedText.isNullOrBlank() || !expectedPackage.isNullOrBlank() || requireChange)
                put("changed", changed)
                put("package", after.packageName)
                put("target_confidence", match?.confidence ?: 1f)
                put("target_source", match?.source ?: "none")
            }.toString()
        }

        val specs = buildList {
            add(input.jsonObject)
            input.jsonObject["alternatives"]?.jsonArray
                ?.mapNotNull { it as? JsonObject }
                ?.let { addAll(it) }
        }
        var last = """{"error":"action_failed"}"""
        for (spec in specs) {
            val (ok, result) = attempt(spec)
            if (ok) {
                return@Tool listOf(UIMessagePart.Text(result))
            }
            last = result
        }
        listOf(UIMessagePart.Text(last))
    },
)
