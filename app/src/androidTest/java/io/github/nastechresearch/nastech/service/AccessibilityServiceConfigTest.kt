package io.github.nastechresearch.nastech.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.EditText
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

        val serviceReady = awaitCondition(timeoutMs = 15_000L) {
            RikkaAccessibilityService.instance != null
        }
        assertTrue(
            "RikkaAccessibilityService must be enabled by the emulator setup before this test",
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
            "The connected service must observe click events",
            (runtimeInfo.eventTypes and AccessibilityEvent.TYPE_VIEW_CLICKED) != 0,
        )
        assertTrue(
            "The connected service must observe text-change events",
            (runtimeInfo.eventTypes and AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) != 0,
        )

        val recording = WorkflowRecordingController.start(
            context,
            "Accessibility service integration test",
        )
        try {
            ActivityScenario.launch(AccessibilityConfigTestActivity::class.java).use { scenario ->
                instrumentation.waitForIdleSync()

                val buttonResourceId = "android:id/button1"
                val editResourceId = "android:id/edit"
                val buttonIdVisible = awaitCondition(timeoutMs = 10_000L) {
                    activeService.rootInActiveWindow
                        ?.findAccessibilityNodeInfosByViewId(buttonResourceId)
                        .orEmpty()
                        .any { node -> node.viewIdResourceName == buttonResourceId }
                }
                assertTrue(
                    "The active window should expose the button by its Android resource ID",
                    buttonIdVisible,
                )

                scenario.onActivity { activity ->
                    activity.findViewById<Button>(android.R.id.button1).performClick()
                    activity.findViewById<EditText>(android.R.id.edit).setText("typed-from-test")
                }
                instrumentation.waitForIdleSync()

                val actionsRecorded = awaitCondition(timeoutMs = 10_000L) {
                    val actions = WorkflowRecordingStore(context).get(recording.id)?.actions.orEmpty()
                    actions.any {
                        it.kind == RecordingActionKind.TAP && it.resourceId == buttonResourceId
                    } && actions.any {
                        it.kind == RecordingActionKind.TYPE_TEXT &&
                            it.resourceId == editResourceId &&
                            it.text?.contains("typed-from-test") == true
                    }
                }
                assertTrue(
                    "The workflow recorder should persist click and text-change events with their node IDs",
                    actionsRecorded,
                )
            }
        } finally {
            WorkflowRecordingController.stop(context)
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
}
