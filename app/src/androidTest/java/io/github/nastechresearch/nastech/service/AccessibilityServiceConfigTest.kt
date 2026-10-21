package io.github.nastechresearch.nastech.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ComponentName
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nastechresearch.nastech.R
import io.github.nastechresearch.nastech.workflow.recording.RecordingActionKind
import io.github.nastechresearch.nastech.workflow.recording.WorkflowRecordingController
import io.github.nastechresearch.nastech.workflow.recording.WorkflowRecordingStore
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class AccessibilityServiceConfigTest {

    @Test
    fun configEnablesNodeIdsInteractiveWindowsAndWorkflowEvents() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val parser = context.resources.getXml(R.xml.accessibility_service_config)

        try {
            var event = parser.eventType
            while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) {
                event = parser.next()
            }
            assertTrue("Expected an accessibility-service root element", event == XmlPullParser.START_TAG)

            val namespace = "http://schemas.android.com/apk/res/android"
            val flags = parser.getAttributeIntValue(namespace, "accessibilityFlags", 0)
            val eventTypes = parser.getAttributeIntValue(namespace, "accessibilityEventTypes", 0)
            val canRetrieveWindowContent =
                parser.getAttributeBooleanValue(namespace, "canRetrieveWindowContent", false)

            assertTrue(
                "flagReportViewIds must be enabled for view_id_resource_name selectors",
                (flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS) != 0,
            )
            assertTrue(
                "flagRetrieveInteractiveWindows must be enabled for interactive-window inspection",
                (flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) != 0,
            )
            assertTrue(
                "The service must be allowed to retrieve window content",
                canRetrieveWindowContent,
            )

            val requiredEvents = listOf(
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED,
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            )
            requiredEvents.forEach { requiredEvent ->
                assertTrue(
                    "Missing accessibility event type: $requiredEvent",
                    (eventTypes and requiredEvent) != 0,
                )
            }
        } finally {
            parser.close()
        }
    }

    @Test
    fun connectedServiceExposesViewIdsAndRecordsClickAndTextEvents() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        // UiAutomation normally suppresses other accessibility services. Preserve them so
        // this instrumentation can exercise the real service bound by the emulator.
        val automation = instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
        )
        val originalEnabledServices = shell(automation, "settings get secure enabled_accessibility_services")
        val originalAccessibilityEnabled = shell(automation, "settings get secure accessibility_enabled")
        val component = ComponentName(context, RikkaAccessibilityService::class.java).flattenToString()
        val enabledComponents = originalEnabledServices
            .split(':')
            .filter { it.isNotBlank() && it != "null" }
            .toMutableSet()
            .apply { add(component) }

        try {
            shell(
                automation,
                "settings put secure enabled_accessibility_services ${enabledComponents.joinToString(":")}",
            )
            shell(automation, "settings put secure accessibility_enabled 1")

            val serviceReady = awaitCondition(timeoutMs = 15_000L) {
                RikkaAccessibilityService.instance != null
            }
            assertTrue(
                "RikkaAccessibilityService must connect with UiAutomation suppression disabled; " +
                    "enabled services are: ${shell(automation, "settings get secure enabled_accessibility_services")}",
                serviceReady,
            )

            val service = RikkaAccessibilityService.instance
            assertNotNull("Accessibility service should be connected", service)
            val activeService = requireNotNull(service)
            val runtimeInfo = activeService.serviceInfo
            assertTrue(
                "The connected service must report view IDs",
                (runtimeInfo.flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS) != 0,
            )
            assertTrue(
                "The connected service must expose interactive windows",
                (runtimeInfo.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) != 0,
            )
            val requiredRuntimeEvents = listOf(
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED,
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            )
            requiredRuntimeEvents.forEach { requiredEvent ->
                assertTrue(
                    "The connected service must subscribe to event type $requiredEvent",
                    (runtimeInfo.eventTypes and requiredEvent) != 0,
                )
            }

            val recording = WorkflowRecordingController.start(
                context,
                "Accessibility service integration test",
            )
            try {
                ActivityScenario.launch(AccessibilityConfigTestActivity::class.java).use {

                    val interactiveWindowVisible = awaitCondition(timeoutMs = 10_000L) {
                        activeService.windows.any { window ->
                            if (!window.isActive) {
                                false
                            } else {
                                val root = window.root
                                try {
                                    root?.packageName?.toString() == context.packageName
                                } finally {
                                    root?.recycle()
                                }
                            }
                        }
                    }
                    assertTrue(
                        "The active app window should be present in AccessibilityService.windows",
                        interactiveWindowVisible,
                    )

                    val buttonResourceId = "android:id/button1"
                    val editResourceId = "android:id/edit"
                    val buttonBounds = Rect()
                    val buttonNode = activeService.rootInActiveWindow
                        ?.findAccessibilityNodeInfosByViewId(buttonResourceId)
                        .orEmpty()
                        .firstOrNull { node -> node.viewIdResourceName == buttonResourceId }
                    assertNotNull(
                        "The active window should expose the button by its Android resource ID",
                        buttonNode,
                    )
                    requireNotNull(buttonNode).let { node ->
                        node.getBoundsInScreen(buttonBounds)
                        node.recycle()
                    }
                    assertTrue("The button must have visible screen bounds", !buttonBounds.isEmpty)

                    // Inject actual emulator input rather than calling View.performClick()/setText():
                    // the acceptance test must exercise Android's user-event delivery path.
                    injectTap(automation, buttonBounds.centerX().toFloat(), buttonBounds.centerY().toFloat())
                    val clickRecorded = awaitCondition(timeoutMs = 10_000L) {
                        WorkflowRecordingStore(context).get(recording.id)?.actions.orEmpty().any {
                            it.kind == RecordingActionKind.TAP && it.resourceId == buttonResourceId
                        }
                    }
                    val actionsAfterClick = WorkflowRecordingStore(context).get(recording.id)?.actions.orEmpty()
                    assertTrue(
                        "The workflow recorder should persist the emulator click with its node ID. " +
                            "Observed actions: " + actionsAfterClick.joinToString {
                                "${it.kind}(id=${it.resourceId}, text=${it.text})"
                            },
                        clickRecorded,
                    )

                    val editBounds = Rect()
                    val editNode = activeService.rootInActiveWindow
                        ?.findAccessibilityNodeInfosByViewId(editResourceId)
                        .orEmpty()
                        .firstOrNull { node -> node.viewIdResourceName == editResourceId }
                    assertNotNull(
                        "The active window should expose the text input by its Android resource ID",
                        editNode,
                    )
                    requireNotNull(editNode).let { node ->
                        node.getBoundsInScreen(editBounds)
                        node.recycle()
                    }
                    assertTrue("The text input must have visible screen bounds", !editBounds.isEmpty)

                    injectTap(automation, editBounds.centerX().toFloat(), editBounds.centerY().toFloat())
                    SystemClock.sleep(300L)
                    shell(automation, "input text typed-from-test >/dev/null 2>&1 & echo text-input-dispatched")

                    val actionsRecorded = awaitCondition(timeoutMs = 10_000L) {
                        WorkflowRecordingStore(context).get(recording.id)?.actions.orEmpty().any {
                            it.kind == RecordingActionKind.TYPE_TEXT &&
                                it.resourceId == editResourceId &&
                                it.text?.contains("typed-from-test") == true
                        }
                    }
                    val finalActions = WorkflowRecordingStore(context).get(recording.id)?.actions.orEmpty()
                    assertTrue(
                        "The workflow recorder should persist the emulator text-change event with its node ID. " +
                            "Observed actions: " + finalActions.joinToString {
                                "${it.kind}(id=${it.resourceId}, text=${it.text})"
                            },
                        actionsRecorded,
                    )
                }
            } finally {
                WorkflowRecordingController.stop(context)
            }
        } finally {
            restoreSecureSetting(automation, "enabled_accessibility_services", originalEnabledServices)
            restoreSecureSetting(automation, "accessibility_enabled", originalAccessibilityEnabled)
        }
    }

    private fun injectTap(automation: UiAutomation, x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            x,
            y,
            0,
        )
        try {
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(
                "UiAutomation should inject the touch-down event",
                automation.injectInputEvent(down, true),
            )
            SystemClock.sleep(80L)

            val up = MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP,
                x,
                y,
                0,
            )
            try {
                up.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(
                    "UiAutomation should inject the touch-up event",
                    automation.injectInputEvent(up, true),
                )
            } finally {
                up.recycle()
            }
        } finally {
            down.recycle()
        }
    }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return true
            SystemClock.sleep(100L)
        }
        return condition()
    }

    private fun shell(automation: UiAutomation, command: String): String {
        val descriptor = automation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader()
            .use { it.readText().trim() }
    }

    private fun restoreSecureSetting(
        automation: UiAutomation,
        key: String,
        previousValue: String,
    ) {
        if (previousValue.isBlank() || previousValue == "null") {
            shell(automation, "settings delete secure $key")
        } else {
            shell(automation, "settings put secure $key $previousValue")
        }
    }
}
