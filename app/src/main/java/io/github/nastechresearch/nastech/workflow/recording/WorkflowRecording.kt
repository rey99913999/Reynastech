package io.github.nastechresearch.nastech.workflow.recording

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.nastechresearch.nastech.service.RikkaAccessibilityService
import io.github.nastechresearch.nastech.utils.JsonInstant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

@kotlinx.serialization.Serializable
enum class RecordingActionKind { TAP, LONG_PRESS, TYPE_TEXT, SCROLL, SCREEN_CHANGED, WAIT }

@kotlinx.serialization.Serializable
data class RecordingUiState(
    val packageName: String = "",
    val windowTitle: String = "",
    val text: String = "",
    val contentDescription: String = "",
    val role: String = "",
    val resourceId: String = "",
    val relation: String = "",
    val bounds: List<Int> = emptyList(),
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val clickable: Boolean = false,
    val scrollable: Boolean = false,
    val timestampMs: Long = 0L,
)

@kotlinx.serialization.Serializable
data class RecordedAction(
    val index: Int,
    val timestampMs: Long,
    val kind: RecordingActionKind,
    val packageName: String = "",
    val windowTitle: String = "",
    val x: Float? = null,
    val y: Float? = null,
    val direction: String? = null,
    val text: String? = null,
    val durationMs: Long? = null,
    val targetText: String? = null,
    val contentDescription: String? = null,
    val role: String? = null,
    val resourceId: String? = null,
    val relation: String? = null,
    val beforeState: RecordingUiState? = null,
    val afterState: RecordingUiState? = null,
)

@kotlinx.serialization.Serializable
data class RawRecording(
    val id: String,
    val title: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val status: String = "RECORDING",
    val actions: List<RecordedAction> = emptyList(),
    val screenshots: List<String> = emptyList(),
    val draftPath: String? = null,
)

data class RecordingControllerState(
    val recordingId: String? = null,
    val active: Boolean = false,
    val title: String = "",
    val actionCount: Int = 0,
)

class WorkflowRecordingStore(private val context: android.content.Context) {
    private val root = File(context.applicationContext.filesDir, "learned_workflows")
    private val recordingsDir = File(root, "recordings")
    private val draftsDir = File(root, "drafts")
    private val screenshotsDir = File(root, "screenshots")

    init {
        recordingsDir.mkdirs()
        draftsDir.mkdirs()
        screenshotsDir.mkdirs()
    }

    fun listRecordings(): List<RawRecording> =
        recordingsDir.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .mapNotNull { file ->
                runCatching { JsonInstant.decodeFromString<RawRecording>(file.readText()) }.getOrNull()
            }
            .sortedByDescending { it.startedAtMs }

    fun get(id: String): RawRecording? =
        File(recordingsDir, id + ".json").takeIf(File::isFile)?.let {
            runCatching { JsonInstant.decodeFromString<RawRecording>(it.readText()) }.getOrNull()
        }

    fun save(recording: RawRecording) {
        val file = File(recordingsDir, recording.id + ".json")
        val tmp = File(recordingsDir, recording.id + ".tmp")
        tmp.writeText(JsonInstant.encodeToString(recording))
        file.delete()
        tmp.renameTo(file)
    }

    fun saveDraft(id: String, json: String): String {
        val file = File(draftsDir, id + ".json")
        file.writeText(json)
        return "learned_workflows/drafts/" + id + ".json"
    }

    fun getDraft(id: String): String? = File(draftsDir, id + ".json").takeIf(File::isFile)?.readText()

    fun delete(id: String) {
        File(recordingsDir, id + ".json").delete()
        File(draftsDir, id + ".json").delete()
        screenshotsDir.listFiles { file -> file.name.startsWith(id + "_") }.orEmpty().forEach(File::delete)
    }

    fun saveScreenshot(id: String, timestampMs: Long, bitmap: Bitmap): String? = runCatching {
        val file = File(screenshotsDir, id + "_" + timestampMs + ".jpg")
        file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out) }
        file.relativeTo(root).path
    }.getOrNull()

    fun markConverted(id: String, draftPath: String) {
        get(id)?.let { save(it.copy(status = "CONVERTED", draftPath = draftPath)) }
    }
}

object WorkflowRecordingController {
    private const val WAIT_MS = 1_200L
    private const val SHOT_MS = 1_500L
    private const val MAX_ACTIONS = 600
    private const val MAX_SHOTS = 12

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(RecordingControllerState())
    val state: StateFlow<RecordingControllerState> = _state.asStateFlow()
    private val lock = Any()

    private var active: Active? = null

    private data class Active(
        val context: android.content.Context,
        val id: String,
        var recording: RawRecording,
        var lastAt: Long,
        var lastState: RecordingUiState?,
        var lastShotAt: Long,
    )

    fun start(context: android.content.Context, title: String): RawRecording = synchronized(lock) {
        check(active == null) { "recording_already_active" }
        val recording = RawRecording(
            id = UUID.randomUUID().toString(),
            title = title.trim().ifBlank { "Recorded task" }.take(100),
            startedAtMs = System.currentTimeMillis(),
        )
        WorkflowRecordingStore(context).save(recording)
        active = Active(context.applicationContext, recording.id, recording, recording.startedAtMs, null, 0L)
        _state.value = RecordingControllerState(recording.id, true, recording.title, 0)
        recording
    }

    fun stop(context: android.content.Context): RawRecording? = synchronized(lock) {
        val current = active ?: return@synchronized null
        val result = current.recording.copy(endedAtMs = System.currentTimeMillis(), status = "STOPPED")
        WorkflowRecordingStore(context).save(result)
        active = null
        _state.value = RecordingControllerState()
        result
    }

    fun onAccessibilityEvent(service: RikkaAccessibilityService, event: AccessibilityEvent) {
        val kind = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED -> RecordingActionKind.TAP
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> RecordingActionKind.LONG_PRESS
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> RecordingActionKind.TYPE_TEXT
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> RecordingActionKind.SCROLL
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> RecordingActionKind.SCREEN_CHANGED
            else -> return
        }

        val now = System.currentTimeMillis()
        synchronized(lock) {
            val current = active ?: return
            if (current.recording.actions.size >= MAX_ACTIONS) return
            val source = event.source
            val node = source?.let { service.resolveClickable(it) } ?: source
            val pkg = event.packageName?.toString().orEmpty()
            val title = service.rootInActiveWindow?.window?.title?.toString().orEmpty()
            val state = node?.let { snapshot(it, pkg, title, now) }

            if (now - current.lastAt >= WAIT_MS && kind != RecordingActionKind.SCREEN_CHANGED) {
                val wait = RecordedAction(
                    index = current.recording.actions.size,
                    timestampMs = current.lastAt + 1L,
                    kind = RecordingActionKind.WAIT,
                    packageName = pkg,
                    windowTitle = title,
                    durationMs = now - current.lastAt,
                    beforeState = current.lastState,
                )
                current.recording = current.recording.copy(actions = current.recording.actions + wait)
            }

            val bounds = state?.bounds.orEmpty()
            val x = if (bounds.size == 4) (bounds[0] + bounds[2]) / 2f else null
            val y = if (bounds.size == 4) (bounds[1] + bounds[3]) / 2f else null
            val eventText = event.text.joinToString(" ").takeIf(String::isNotBlank)
                ?.let { if (node?.isPassword == true) "<redacted>" else it }
            val direction = if (kind == RecordingActionKind.SCROLL) {
                when {
                    event.scrollDeltaY > 0 -> "up"
                    event.scrollDeltaY < 0 -> "down"
                    event.scrollDeltaX > 0 -> "left"
                    else -> "right"
                }
            } else null

            val action = RecordedAction(
                index = current.recording.actions.size,
                timestampMs = now,
                kind = kind,
                packageName = pkg,
                windowTitle = title,
                x = x,
                y = y,
                direction = direction,
                text = eventText,
                targetText = state?.text?.takeIf(String::isNotBlank),
                contentDescription = state?.contentDescription?.takeIf(String::isNotBlank),
                role = state?.role?.takeIf(String::isNotBlank),
                resourceId = state?.resourceId?.takeIf(String::isNotBlank),
                relation = state?.relation?.takeIf(String::isNotBlank),
                beforeState = current.lastState,
                afterState = state,
            )
            current.recording = current.recording.copy(actions = current.recording.actions + action)
            current.lastAt = now
            current.lastState = state
            persist(current)

            if (kind != RecordingActionKind.TYPE_TEXT && now - current.lastShotAt >= SHOT_MS &&
                node?.isPassword != true
            ) {
                current.lastShotAt = now
                val id = current.id
                scope.launch {
                    val outcome = runCatching { service.captureScreenshot(0) }.getOrNull()
                    if (outcome is RikkaAccessibilityService.ScreenshotOutcome.Success) {
                        val path = WorkflowRecordingStore(service.applicationContext)
                            .saveScreenshot(id, now, outcome.bitmap)
                        outcome.bitmap.recycle()
                        if (path != null) synchronized(lock) {
                            val stillActive = active
                            if (stillActive?.id == id && stillActive.recording.screenshots.size < MAX_SHOTS) {
                                stillActive.recording = stillActive.recording.copy(
                                    screenshots = stillActive.recording.screenshots + path
                                )
                                persist(stillActive)
                            }
                        }
                    }
                }
            }
            _state.value = RecordingControllerState(current.id, true, current.recording.title, current.recording.actions.size)
        }
    }

    private fun persist(current: Active) {
        WorkflowRecordingStore(current.context).save(current.recording)
    }

    private fun snapshot(
        node: AccessibilityNodeInfo,
        pkg: String,
        title: String,
        timestamp: Long,
    ): RecordingUiState {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val text = node.text?.toString().orEmpty()
            .let { if (node.isPassword) "<redacted>" else it }
        val parentText = node.parent?.text?.toString().orEmpty()
        val className = node.className?.toString().orEmpty()
        val role = roleOf(className)
        return RecordingUiState(
            packageName = pkg,
            windowTitle = title,
            text = text,
            contentDescription = if (node.isPassword) "<redacted>" else node.contentDescription?.toString().orEmpty(),
            role = role,
            resourceId = node.viewIdResourceName.orEmpty(),
            relation = if (parentText.isNotBlank() && parentText != text) "inside:" + parentText else "",
            bounds = listOf(rect.left, rect.top, rect.right, rect.bottom),
            enabled = node.isEnabled,
            selected = node.isSelected,
            clickable = node.isClickable,
            scrollable = node.isScrollable,
            timestampMs = timestamp,
        )
    }

    private fun roleOf(className: String): String {
        val c = className.lowercase()
        return when {
            c.contains("edittext") || c.contains("textfield") -> "input"
            c.contains("button") -> "button"
            c.contains("switch") -> "switch"
            c.contains("checkbox") -> "checkbox"
            c.contains("recyclerview") || c.contains("listview") -> "list"
            c.contains("dialog") -> "dialog"
            c.contains("imagebutton") || c.contains("imageview") -> "icon"
            c.contains("textview") -> "text"
            else -> if (className.isBlank()) "" else "clickable_region"
        }
    }
}
