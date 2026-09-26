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

sealed interface DeviceAccessibilityQueryResult {
    data object Matched : DeviceAccessibilityQueryResult
    data class NotFound(val currentPackage: String = "") : DeviceAccessibilityQueryResult
    data class WrongForeground(val currentPackage: String) : DeviceAccessibilityQueryResult
    data object Unavailable : DeviceAccessibilityQueryResult
}

interface DeviceObserver {
    val isAvailable: Boolean
        get() = false

    suspend fun observe(
        captureScreenshot: Boolean = false,
        captureOcr: Boolean = false,
    ): DeviceObservation

    suspend fun findAccessibilityNode(
        selector: DeviceAccessibilitySelector,
    ): DeviceAccessibilityQueryResult = DeviceAccessibilityQueryResult.Unavailable
}

class AndroidDeviceObserver(
    private val context: android.content.Context,
    private val cache: DeviceStateCache,
) : DeviceObserver {

    private val maxScreenshotBytes = 6L * 1024L * 1024L

    override val isAvailable: Boolean
        get() = RikkaAccessibilityService.instance != null

    override suspend fun findAccessibilityNode(
        selector: DeviceAccessibilitySelector,
    ): DeviceAccessibilityQueryResult {
        val service = RikkaAccessibilityService.instance
            ?: return DeviceAccessibilityQueryResult.Unavailable
        val root = service.rootInActiveWindow
            ?: return DeviceAccessibilityQueryResult.NotFound()
        val currentPackage = root.packageName?.toString().orEmpty()

        selector.packageName?.let { expected ->
            if (!currentPackage.equals(expected, ignoreCase = true)) {
                return DeviceAccessibilityQueryResult.WrongForeground(currentPackage)
            }
        }
        if (selector.nth < 0) return DeviceAccessibilityQueryResult.NotFound(currentPackage)

        val matches = runCatching {
            when (selector.by) {
                "view_id_resource_name" ->
                    root.findAccessibilityNodeInfosByViewId(selector.value).orEmpty()
                "text" ->
                    root.findAccessibilityNodeInfosByText(selector.value).orEmpty().filter {
                        it.isVisibleToUser && it.text?.toString() == selector.value
                    }
                "content_description" -> buildList {
                    fun walk(node: AccessibilityNodeInfo) {
                        if (node.isVisibleToUser &&
                            node.contentDescription?.toString() == selector.value
                        ) {
                            add(node)
                        }
                        for (i in 0 until node.childCount) {
                            node.getChild(i)?.let(::walk)
                        }
                    }
                    walk(root)
                }
                else -> emptyList()
            }
        }.getOrDefault(emptyList())

        return if (selector.nth < matches.size) {
            DeviceAccessibilityQueryResult.Matched
        } else {
            DeviceAccessibilityQueryResult.NotFound(currentPackage)
        }
    }

    override suspend fun observe(
        captureScreenshot: Boolean,
        captureOcr: Boolean,
    ): DeviceObservation = withContext(Dispatchers.IO) {
        val service = RikkaAccessibilityService.instance
            ?: return@withContext DeviceObservation(createdAtMs = System.currentTimeMillis())

        val root = service.rootInActiveWindow
            ?: return@withContext DeviceObservation(createdAtMs = System.currentTimeMillis())

        val visibleText = ArrayList<String>(128)
        val accessibilityText = ArrayList<String>(128)
        val fingerprintParts = ArrayList<String>(256)
        var focusedText: String? = null

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || fingerprintParts.size >= 1_200) return

            val text = node.text?.toString()?.trim().orEmpty()
            val description = node.contentDescription?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) {
                visibleText += text
                accessibilityText += text
            }
            if (description.isNotBlank() && description != text) {
                visibleText += description
                accessibilityText += description
            }
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

        if ((captureScreenshot || captureOcr) &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
        ) {
            when (val result = service.captureScreenshot(0)) {
                is RikkaAccessibilityService.ScreenshotOutcome.Success -> {
                    val bitmap = result.bitmap
                    try {
                        val dir = File(context.cacheDir, "device-core").apply { mkdirs() }

                        if (captureScreenshot) {
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
                            } else {
                                file.delete()
                            }
                        }

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
            accessibilityText = accessibilityText.distinct().take(600),
            ocrText = ocrText.distinct().take(600),
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

    private fun sha256(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
