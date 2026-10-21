package io.github.nastechresearch.nastech.service

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Debug-only screen used by AccessibilityServiceConfigTest for end-to-end checks
 * against a real Android accessibility window. It is not present in release APKs.
 */
class AccessibilityConfigTestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val status = TextView(this).apply {
            id = android.R.id.text1
            text = "Accessibility test screen"
            contentDescription = "Accessibility test status"
        }
        val button = Button(this).apply {
            id = android.R.id.button1
            text = "Accessibility test button"
            contentDescription = "Accessibility test button"
            setOnClickListener { status.text = "Button clicked" }
        }
        val edit = EditText(this).apply {
            id = android.R.id.edit
            hint = "Accessibility test input"
            contentDescription = "Accessibility test input"
        }

        content.addView(status, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        content.addView(button, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        content.addView(edit, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val scroll = ScrollView(this).apply {
            id = android.R.id.list
            isFillViewport = true
            addView(content)
        }
        setContentView(scroll)
    }
}
