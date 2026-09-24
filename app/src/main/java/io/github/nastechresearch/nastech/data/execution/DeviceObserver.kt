package io.github.nastechresearch.nastech.data.execution

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.nastechresearch.nastech.service.RikkaAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

interface DeviceObserver {
    suspend fun observe(
        captureScreenshot: Boolean = false,
        captureOcr: Boolean = false,
    ): DeviceObservation
}

class AndroidDeviceObserver(
    private val context: android.content.Context,
    private val cache: DeviceStateCache,
) : DeviceObserver {

    private val maxScreenshotBytes = 6L * 1024L * 1024L

    override suspend fun observe(
        captureScreenshot: Boolean,
        captureOcr: Boolean,
    ): DeviceObservation = withContext(Dispatchers.IO) {
        val service = RikkaAccessibilityService.instance
            ?: return@withContext DeviceObservation(createdAtMs = System.currentTimeMillis())

        val root = service.rootInActiveWindow
            ?: return@withContext DeviceObservation(createdAtMs = System.currentTimeMillis())

        val visibleText = ArrayList<String>(128)
        val fingerprintParts = ArrayList<String>(256)
        var focusedText: String? = null

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || fingerprintParts.size >= 1_200) return

            val text = node.text?.toString()?.trim().orEmpty()
            val description = node.contentDescription?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) visibleText += text
            if (description.isNotBlank() && description != text) visibleText += description
            if (node.isFocused && text.isNotBlank()) focusedText = text

            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            fingerprintParts += listOf(
                node.className?.toString().orEmpty(),
                normalizeDeviceText(text),
                normalizeDeviceText(description),
                bounds.left.toString(),
                bounds.top.toString(),
                bounds.right.toString(),
                bounds.bottom.toString(),
                node.isClickable.toString(),
                node.isEnabled.toString(),
            ).joinToString("|")

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it, depth + 1) }
            }
        }

        walk(root, 0)

        var screenshotPath: String? = null
        var screenshotState = DeviceArtifactState.NONE
        val ocrText = ArrayList<String>()

        if (captureScreenshot && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            when (val result = service.captureScreenshot(0)) {
                is RikkaAccessibilityService.ScreenshotOutcome.Success -> {
                    val bitmap = result.bitmap
                    try {
                        val dir = File(context.cacheDir, "device-core").apply { mkdirs() }
                        val file = File(
                            dir,
                            "shot-${System.currentTimeMillis()}-${UUID.randomUUID()}.png",
                        )
                        FileOutputStream(file).use { output ->
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                        }
                        if (file.length() in 1..maxScreenshotBytes) {
                            screenshotPath = file.absolutePath
                            screenshotState = DeviceArtifactState.CAPTURED_NOT_DELIVERED
                            if (captureOcr) {
                                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                                try {
                                    val resultText = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
                                    resultText.textBlocks.forEach { block ->
                                        block.lines.forEach { line ->
                                            line.text.trim().takeIf { it.isNotBlank() }?.let(ocrText::add)
                                        }
                                    }
                                } finally {
                                    recognizer.close()
                                }
                            }
                        } else {
                            file.delete()
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
                is RikkaAccessibilityService.ScreenshotOutcome.Failure -> Unit
            }
        }

        val packageName = root.packageName?.toString().orEmpty()
        val windowTitle = root.window?.title?.toString().orEmpty()
        val treeFingerprint = sha256(fingerprintParts.joinToString("\n"))
        val screenFingerprint = sha256(
            (packageName + "|" + windowTitle + "|" +
                fingerprintParts.joinToString("\n") + "|" +
                ocrText.joinToString("|")).toByteArray(Charsets.UTF_8),
        )
        val keyboardVisible = service.windows.orEmpty().any {
            it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.isActive
        }

        val observation = DeviceObservation(
            foregroundPackage = packageName,
            windowTitle = windowTitle,
            visibleText = (visibleText + ocrText).distinct().take(600),
            focusedText = focusedText,
            keyboardVisible = keyboardVisible,
            screenFingerprint = screenFingerprint,
            windowTreeFingerprint = treeFingerprint,
            screenshotPath = screenshotPath,
            screenshotState = screenshotState,
        )
        cache.put(observation)
        observation
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
