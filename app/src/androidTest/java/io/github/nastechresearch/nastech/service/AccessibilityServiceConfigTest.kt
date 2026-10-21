package io.github.nastechresearch.nastech.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nastechresearch.nastech.R
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
}
