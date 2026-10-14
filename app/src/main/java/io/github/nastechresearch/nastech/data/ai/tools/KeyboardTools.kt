package io.github.nastechresearch.nastech.data.ai.tools

import android.graphics.Rect
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.nastechresearch.nastech.service.RikkaAccessibilityService
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import io.github.nastechresearch.nastech.data.keyboard.KeyboardApiClient

/**
 * LLM tools that drive the active text field through the co-signed agent-keyboard IME.
 *
 * Every tool funnels through [KeyboardApiClient], whose calls are bind- and
 * transaction-time-bounded, so no tool here can hang. Failures come back as a
 * [KeyboardApiClient.Failure] which [failureEnvelope] turns into a `{error, detail,
 * recovery}` object — the recovery text spells out the install / set-as-IME requirement
 * verbatim when that is the cause.
 *
 * Read tools (`keyboard_read_field`, `keyboard_editor_info`) are NOT approval-gated. The
 * side-effecting tools are listed in [ToolApprovalDefaults.ALWAYS_ASK]. None need a
 * HARDLINE arm: typing a string into a focused field is not shell execution and the
 * keyboard itself refuses password / sensitive fields.
 */

// keycode aliases the LLM is likely to use, mapped to android.view.KeyEvent constants.
private val KEY_NAME_ALIASES: Map<String, Int> = mapOf(
    "enter" to KeyEvent.KEYCODE_ENTER,
    "return" to KeyEvent.KEYCODE_ENTER,
    "tab" to KeyEvent.KEYCODE_TAB,
    "backspace" to KeyEvent.KEYCODE_DEL,
    "delete" to KeyEvent.KEYCODE_FORWARD_DEL,
    "space" to KeyEvent.KEYCODE_SPACE,
    "escape" to KeyEvent.KEYCODE_ESCAPE,
    "esc" to KeyEvent.KEYCODE_ESCAPE,
    "up" to KeyEvent.KEYCODE_DPAD_UP,
    "down" to KeyEvent.KEYCODE_DPAD_DOWN,
    "left" to KeyEvent.KEYCODE_DPAD_LEFT,
    "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
    "home" to KeyEvent.KEYCODE_MOVE_HOME,
    "end" to KeyEvent.KEYCODE_MOVE_END,
    "page_up" to KeyEvent.KEYCODE_PAGE_UP,
    "page_down" to KeyEvent.KEYCODE_PAGE_DOWN,
)

/** Resolve a "key" arg that may be a known name OR a raw int keycode. Null if unresolvable. */
internal fun resolveKeyCode(raw: String?): Int? {
    if (raw.isNullOrBlank()) return null
    raw.trim().toIntOrNull()?.let { return it }
    return KEY_NAME_ALIASES[raw.trim().lowercase()]
}

private fun ok(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject { put("ok", true); build() }.toString()))

private fun errEnvelope(error: String, detail: String, recovery: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject {
        put("error", error)
        put("detail", detail)
        put("recovery", recovery)
    }.toString()))

/** Maps a client [KeyboardApiClient.Failure] to the standard error envelope. */
private fun failureEnvelope(failure: KeyboardApiClient.Failure): List<UIMessagePart> = when (failure) {
    KeyboardApiClient.Failure.NOT_INSTALLED -> errEnvelope(
        error = "keyboard_not_installed",
        detail = "The agent-keyboard companion app is not installed on this device.",
        recovery = "Install the agent-keyboard app and set it as the active keyboard " +
            "(Settings > System > Languages & input > On-screen keyboard) before using keyboard tools.",
    )
    KeyboardApiClient.Failure.BIND_REFUSED -> errEnvelope(
        error = "keyboard_bind_refused",
        detail = "Could not bind to the agent-keyboard service or the handshake was rejected.",
        recovery = "Confirm the agent-keyboard app is installed and that it is set as the " +
            "active keyboard. If it is installed but binding still fails, the two apps may " +
            "not be co-signed with the same key.",
    )
    KeyboardApiClient.Failure.OPERATION_FAILED -> errEnvelope(
        error = "keyboard_operation_failed",
        detail = "The keyboard could not perform the action: there may be no focused text " +
            "field, the field may be a password / sensitive field, the request was " +
            "rate-limited, or agent-keyboard is not the active keyboard.",
        recovery = "Make sure a text field is focused and that agent-keyboard is set as the " +
            "active keyboard. Password and sensitive fields are always refused.",
    )
}

private inline fun <T> handle(
    result: KeyboardApiClient.Result<T>,
    onOk: (T) -> List<UIMessagePart>,
): List<UIMessagePart> = when (result) {
    is KeyboardApiClient.Result.Ok -> onOk(result.value)
    is KeyboardApiClient.Result.Err -> failureEnvelope(result.failure)
}

private data class SendButtonCandidate(
    val node: AccessibilityNodeInfo,
    val score: Int,
    val label: String,
)

private data class SendButtonClickResult(
    val clicked: Boolean,
    val reason: String,
    val label: String? = null,
)

internal fun sendLabelScore(raw: String?): Int {
    val value = raw?.trim()?.lowercase()?.replace(Regex("\\s+"), " ").orEmpty()
    if (value.isEmpty()) return 0
    return when {
        value in setOf("send", "submit", "send message", "send prompt", "send a message", "submit message") -> 5
        listOf("إرسال", "ارسال", "أرسل", "ارسل").any { token ->
            value == token || value.startsWith("$token ")
        } -> 5
        value.startsWith("send ") || value.startsWith("submit ") -> 4
        else -> 0
    }
}

/**
 * Rank only semantically labelled controls in the composer area. A strong label outranks
 * geometric convenience; a directly clickable semantic node is preferred to an ancestor.
 */
internal fun sendButtonCandidateScore(
    labelScore: Int,
    semanticNodeClickable: Boolean,
    ancestorDistance: Int,
    lowerComposerArea: Boolean,
): Int {
    if (labelScore <= 0 || !lowerComposerArea) return Int.MIN_VALUE
    return labelScore * 1_000 +
        (if (semanticNodeClickable) 100 else 0) -
        ancestorDistance.coerceIn(0, 10) * 10
}

/**
 * An ancestor is a safe proxy for a non-clickable semantic label only when it actually
 * contains that label and remains close in size. This rejects a distant/root container
 * that resolveClickable() could otherwise return after walking an unbounded parent chain.
 */
internal fun isPlausibleSendButtonAncestor(
    semanticLeft: Int,
    semanticTop: Int,
    semanticRight: Int,
    semanticBottom: Int,
    ancestorLeft: Int,
    ancestorTop: Int,
    ancestorRight: Int,
    ancestorBottom: Int,
    maxAreaRatio: Int = 25,
): Boolean {
    val semanticWidth = semanticRight - semanticLeft
    val semanticHeight = semanticBottom - semanticTop
    val ancestorWidth = ancestorRight - ancestorLeft
    val ancestorHeight = ancestorBottom - ancestorTop
    if (semanticWidth <= 0 || semanticHeight <= 0 || ancestorWidth <= 0 || ancestorHeight <= 0) {
        return false
    }
    val containsSemanticNode =
        ancestorLeft <= semanticLeft && ancestorTop <= semanticTop &&
            ancestorRight >= semanticRight && ancestorBottom >= semanticBottom
    if (!containsSemanticNode) return false
    val semanticArea = semanticWidth.toLong() * semanticHeight.toLong()
    val ancestorArea = ancestorWidth.toLong() * ancestorHeight.toLong()
    return ancestorArea <= semanticArea * maxAreaRatio.coerceAtLeast(1)
}

private data class ClickableSendTarget(
    val node: AccessibilityNodeInfo,
    val ancestorDistance: Int,
    val semanticNodeClickable: Boolean,
)

/**
 * Resolve the nearest safe click target for a semantic Send node. Unlike the generic
 * resolveClickable(), this walk is bounded and validates that a clickable ancestor actually
 * contains the labelled node without ballooning into a large unrelated container.
 */
private fun resolveSendClickTarget(semanticNode: AccessibilityNodeInfo): ClickableSendTarget? {
    if (!semanticNode.isVisibleToUser || !semanticNode.isEnabled) return null
    val semanticBounds = Rect().also(semanticNode::getBoundsInScreen)
    if (semanticBounds.isEmpty) return null

    if (semanticNode.isClickable) {
        return ClickableSendTarget(semanticNode, ancestorDistance = 0, semanticNodeClickable = true)
    }

    var current = semanticNode
    for (distance in 1..5) {
        current = runCatching { current.parent }.getOrNull() ?: return null
        if (!current.isClickable || !current.isVisibleToUser || !current.isEnabled) continue
        val bounds = Rect().also(current::getBoundsInScreen)
        if (bounds.isEmpty) continue
        if (!isPlausibleSendButtonAncestor(
                semanticBounds.left, semanticBounds.top, semanticBounds.right, semanticBounds.bottom,
                bounds.left, bounds.top, bounds.right, bounds.bottom,
            )
        ) continue
        return ClickableSendTarget(current, distance, semanticNodeClickable = false)
    }
    return null
}

/**
 * Clicks a semantically labelled send/submit control from the active app's accessibility
 * tree. No screenshot or AI inference is used. Candidates must be visible, enabled, clickable,
 * tightly related to their semantic label, and in the lower composer area. Horizontal position
 * is deliberately not used to choose between candidates: RTL layouts can place Send on the left.
 */
private data class PendingAccessibilityNode(
    val node: AccessibilityNodeInfo,
    val depth: Int,
    val bottom: Int,
    val area: Long,
)

/**
 * Prioritize the lower, smaller composer controls to keep the bounded search fast, without a
 * left/right bias. Candidate selection itself is ranked by semantic label and click-target context.
 */
private fun clickAccessibleSendButton(): SendButtonClickResult {
    val service = RikkaAccessibilityService.instance
        ?: return SendButtonClickResult(false, "accessibility_service_inactive")
    val root = service.rootInActiveWindow
        ?: return SendButtonClickResult(false, "no_active_window")
    val rootBounds = Rect().also(root::getBoundsInScreen)
    if (rootBounds.isEmpty) return SendButtonClickResult(false, "window_bounds_unavailable")

    val maxVisitedNodes = 180
    val maxDepth = 18
    val maxQueuedNodes = 600
    val queue = java.util.PriorityQueue<PendingAccessibilityNode>(
        compareByDescending<PendingAccessibilityNode> { it.bottom }
            .thenBy { it.area }
    )
    fun enqueue(node: AccessibilityNodeInfo, depth: Int) {
        if (!node.isVisibleToUser || queue.size >= maxQueuedNodes) return
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) return
        queue.add(
            PendingAccessibilityNode(
                node = node,
                depth = depth,
                bottom = bounds.bottom,
                area = bounds.width().toLong().coerceAtLeast(0L) *
                    bounds.height().toLong().coerceAtLeast(0L),
            )
        )
    }
    enqueue(root, 0)
    var visitedNodes = 0
    var bestCandidate: SendButtonCandidate? = null

    while (queue.isNotEmpty() && visitedNodes < maxVisitedNodes) {
        val pending = queue.remove()
        val node = pending.node
        visitedNodes++

        if (node.isVisibleToUser && node.isEnabled) {
            val text = node.text?.toString()
            val description = node.contentDescription?.toString()
            val labelScore = maxOf(sendLabelScore(text), sendLabelScore(description))

            // A resource ID alone is not sufficient evidence to click. Require an actual
            // Send/Submit label so attachment and microphone controls cannot win by position.
            if (labelScore > 0) {
                val target = resolveSendClickTarget(node)
                if (target != null) {
                    val bounds = Rect().also(target.node::getBoundsInScreen)
                    val lowerComposerArea =
                        !bounds.isEmpty &&
                            bounds.centerY() >= rootBounds.top + rootBounds.height() * 0.45f
                    val score = sendButtonCandidateScore(
                        labelScore = labelScore,
                        semanticNodeClickable = target.semanticNodeClickable,
                        ancestorDistance = target.ancestorDistance,
                        lowerComposerArea = lowerComposerArea,
                    )
                    if (score != Int.MIN_VALUE) {
                        val label = sequenceOf(description, text)
                            .firstOrNull { sendLabelScore(it) > 0 }
                            .orEmpty()
                        val candidate = SendButtonCandidate(
                            node = target.node,
                            score = score,
                            label = label.take(100),
                        )
                        if (bestCandidate == null || candidate.score > bestCandidate!!.score) {
                            bestCandidate = candidate
                        }
                    }
                }
            }
        }

        if (pending.depth >= maxDepth) continue
        for (i in 0 until node.childCount) {
            if (queue.size >= maxQueuedNodes) break
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            enqueue(child, pending.depth + 1)
        }
    }

    val target = bestCandidate
        ?: return SendButtonClickResult(false, "no_confident_lower_right_send_button")
    val clicked = runCatching {
        target.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }.getOrDefault(false)
    return if (clicked) {
        SendButtonClickResult(true, "accessibility_send_button_clicked", target.label)
    } else {
        SendButtonClickResult(false, "accessibility_click_rejected", target.label)
    }
}

// -- tools ------------------------------------------------------------------------------

fun keyboardTypeTool(client: KeyboardApiClient): Tool = Tool(
    name = "keyboard_type",
    description = "FAST PATH: type directly into the focused field through agent-keyboard. Prefer this " +
        "over screenshots, read_window_tree, find_node, or coordinate taps for text entry. If the target " +
        "app is open and its composer is already focused, call keyboard_type immediately; do not inspect " +
        "the screen just to confirm the keyboard or typed text. With submit=true, type then click a clearly " +
        "labelled send button in the lower composer area via Accessibility (no screenshot/AI); if none is found, use Enter. " +
        "Do not add separate click/Enter calls after success. Returns per-step elapsed milliseconds.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "The text to insert at the cursor.")
                })
                put("submit", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, type then click a labelled send button in the lower composer area using " +
                        "Accessibility; if none is confidently detected, fall back to Enter (default false).")
                })
            },
            required = listOf("text"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        val text = args.jsonObject["text"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errEnvelope(
                "missing_text", "text is required",
                "Provide the text to type.",
            )
        val actionStartedAt = android.os.SystemClock.elapsedRealtime()
        val submit = args.jsonObject["submit"]?.jsonPrimitive?.booleanOrNull ?: false
        val typeStartedAt = android.os.SystemClock.elapsedRealtime()
        when (val typed = client.typeText(text)) {
            is KeyboardApiClient.Result.Err -> failureEnvelope(typed.failure)
            is KeyboardApiClient.Result.Ok -> {
                val typingMs = (android.os.SystemClock.elapsedRealtime() - typeStartedAt).coerceAtLeast(0L)
                if (!submit) {
                    ok {
                        put("typed", text.length)
                        put("typing_ms", typingMs)
                        put("total_elapsed_ms", (android.os.SystemClock.elapsedRealtime() - actionStartedAt).coerceAtLeast(0L))
                    }
                } else {
                    val scanStartedAt = android.os.SystemClock.elapsedRealtime()
                    val buttonResult = clickAccessibleSendButton()
                    val scanMs = (android.os.SystemClock.elapsedRealtime() - scanStartedAt).coerceAtLeast(0L)
                    if (buttonResult.clicked) {
                        ok {
                            put("typed", text.length)
                            put("submitted", true)
                            put("submit_method", "accessibility_send_button")
                            put("typing_ms", typingMs)
                            put("send_button_scan_ms", scanMs)
                            put("total_elapsed_ms", (android.os.SystemClock.elapsedRealtime() - actionStartedAt).coerceAtLeast(0L))
                            buttonResult.label?.let { put("send_button_label", it) }
                        }
                    } else {
                        val enterStartedAt = android.os.SystemClock.elapsedRealtime()
                        when (val enterResult = client.pressKey(KeyEvent.KEYCODE_ENTER)) {
                            is KeyboardApiClient.Result.Err -> failureEnvelope(enterResult.failure)
                            is KeyboardApiClient.Result.Ok -> ok {
                                put("typed", text.length)
                                put("submitted", true)
                                put("submit_method", "enter_fallback")
                                put("send_button_lookup", buttonResult.reason)
                                put("typing_ms", typingMs)
                                put("send_button_scan_ms", scanMs)
                                put("enter_ms", (android.os.SystemClock.elapsedRealtime() - enterStartedAt).coerceAtLeast(0L))
                                put("total_elapsed_ms", (android.os.SystemClock.elapsedRealtime() - actionStartedAt).coerceAtLeast(0L))
                                buttonResult.label?.let { put("send_button_label", it) }
                            }
                        }
                    }
                }
            }
        }
    },
)

fun keyboardReadFieldTool(client: KeyboardApiClient): Tool = Tool(
    name = "keyboard_read_field",
    description = "Read the full text content of the currently focused text field via the " +
        "agent-keyboard IME. Returns the field text and the selected substring (if any). " +
        "Read-only. Note: terminal emulators (e.g. Termux) and some custom views do not " +
        "expose their buffer to the IME, so this can return empty even when text is " +
        "visible on screen - take a screenshot to read those. Example: keyboard_read_field().",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        when (val text = client.getCurrentText()) {
            is KeyboardApiClient.Result.Ok -> {
                val selected = (client.getSelectedText() as? KeyboardApiClient.Result.Ok)?.value
                ok {
                    put("text", text.value ?: "")
                    put("selected_text", selected ?: "")
                }
            }
            is KeyboardApiClient.Result.Err -> failureEnvelope(text.failure)
        }
    },
)

fun keyboardPressKeyTool(client: KeyboardApiClient): Tool = Tool(
    name = "keyboard_press_key",
    description = "Send a single key press to the focused field via the agent-keyboard IME. " +
        "The 'key' argument accepts a common name (enter, tab, backspace, delete, space, " +
        "escape, up, down, left, right, home, end, page_up, page_down) or a raw Android " +
        "keycode integer. Example: keyboard_press_key(key=\"enter\").",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("key", buildJsonObject {
                    put("type", "string")
                    put("description", "Key name (enter/tab/backspace/...) or raw keycode integer.")
                })
            },
            required = listOf("key"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        val raw = args.jsonObject["key"]?.jsonPrimitive?.contentOrNull
        val keyCode = resolveKeyCode(raw)
            ?: return@Tool errEnvelope(
                "invalid_key", "could not resolve key '$raw' to a keycode",
                "Use a known key name (enter, tab, backspace, ...) or a raw Android keycode integer.",
            )
        handle(client.pressKey(keyCode)) { ok { put("key_code", keyCode) } }
    },
)

fun keyboardDeleteTool(client: KeyboardApiClient): Tool = Tool(
    name = "keyboard_delete",
    description = "Delete a number of characters before the cursor in the focused field via " +
        "the agent-keyboard IME. Example: keyboard_delete(count=3).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("count", buildJsonObject {
                    put("type", "integer")
                    put("description", "How many characters before the cursor to delete (>= 1).")
                })
            },
            required = listOf("count"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        val count = args.jsonObject["count"]?.jsonPrimitive?.intOrNull
            ?: return@Tool errEnvelope(
                "missing_count", "count is required and must be an integer",
                "Provide a positive integer count.",
            )
        if (count < 1) return@Tool errEnvelope(
            "invalid_count", "count must be >= 1",
            "Provide a positive integer count.",
        )
        handle(client.deleteChars(count)) { ok { put("deleted", count) } }
    },
)

fun keyboardClearTool(client: KeyboardApiClient): Tool = Tool(
    name = "keyboard_clear",
    description = "Clear all text from the currently focused text field via the " +
        "agent-keyboard IME. Example: keyboard_clear().",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    needsApproval = { true },
    execute = {
        handle(client.clearField()) { ok { put("cleared", true) } }
    },
)

fun keyboardEditorInfoTool(client: KeyboardApiClient): Tool = Tool(
    name = "keyboard_editor_info",
    description = "Get metadata about the currently focused editor via the agent-keyboard " +
        "IME: owning package, field hint, input type, whether it is a password / multi-line " +
        "field, and the current selection range. Read-only. Use this to decide whether " +
        "typing is safe before calling keyboard_type. Example: keyboard_editor_info().",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        handle(client.getEditorInfo()) { info ->
            if (info == null) {
                failureEnvelope(KeyboardApiClient.Failure.OPERATION_FAILED)
            } else {
                ok {
                    put("package_name", info.packageName ?: "")
                    put("field_hint", info.fieldHint ?: "")
                    put("input_type", info.inputType)
                    put("ime_options", info.imeOptions)
                    put("is_password", info.isPassword)
                    put("is_multi_line", info.isMultiLine)
                    put("selection_start", info.selectionStart)
                    put("selection_end", info.selectionEnd)
                }
            }
        }
    },
)

fun keyboardSetCursorTool(client: KeyboardApiClient): Tool = Tool(
    name = "keyboard_set_cursor",
    description = "Move the cursor in the focused text field to a character position via the " +
        "agent-keyboard IME. Example: keyboard_set_cursor(pos=0) moves to the start.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("pos", buildJsonObject {
                    put("type", "integer")
                    put("description", "Character offset to place the cursor at (>= 0).")
                })
            },
            required = listOf("pos"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        val pos = args.jsonObject["pos"]?.jsonPrimitive?.intOrNull
            ?: return@Tool errEnvelope(
                "missing_pos", "pos is required and must be an integer",
                "Provide a non-negative integer position.",
            )
        if (pos < 0) return@Tool errEnvelope(
            "invalid_pos", "pos must be >= 0",
            "Provide a non-negative integer position.",
        )
        handle(client.setCursorPosition(pos)) { ok { put("cursor", pos) } }
    },
)

fun keyboardSelectRangeTool(client: KeyboardApiClient): Tool = Tool(
    name = "keyboard_select_range",
    description = "Select a character range in the focused text field via the agent-keyboard " +
        "IME. Example: keyboard_select_range(start=0, end=5) selects the first 5 characters.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("start", buildJsonObject {
                    put("type", "integer")
                    put("description", "Start character offset of the selection (>= 0).")
                })
                put("end", buildJsonObject {
                    put("type", "integer")
                    put("description", "End character offset of the selection (>= 0).")
                })
            },
            required = listOf("start", "end"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        val params = args.jsonObject
        val start = params["start"]?.jsonPrimitive?.intOrNull
            ?: return@Tool errEnvelope(
                "missing_start", "start is required and must be an integer",
                "Provide non-negative integer start and end offsets.",
            )
        val end = params["end"]?.jsonPrimitive?.intOrNull
            ?: return@Tool errEnvelope(
                "missing_end", "end is required and must be an integer",
                "Provide non-negative integer start and end offsets.",
            )
        if (start < 0 || end < 0) return@Tool errEnvelope(
            "invalid_range", "start and end must both be >= 0",
            "Provide non-negative integer start and end offsets.",
        )
        handle(client.selectRange(start, end)) {
            ok { put("selection_start", start); put("selection_end", end) }
        }
    },
)
