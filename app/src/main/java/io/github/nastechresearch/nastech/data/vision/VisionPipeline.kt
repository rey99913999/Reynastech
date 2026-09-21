package io.github.nastechresearch.nastech.data.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import io.github.nastechresearch.nastech.data.ai.tools.local.AccessibilityServiceHandle
import io.github.nastechresearch.nastech.data.ai.tools.local.defaultFilter
import io.github.nastechresearch.nastech.data.ai.tools.local.nodeToJson
import io.github.nastechresearch.nastech.service.RikkaAccessibilityService
import io.github.nastechresearch.nastech.data.datastore.findModelById
import io.github.nastechresearch.nastech.data.datastore.findProvider
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

private const val SNAPSHOT_TTL_MS = 5L * 60L * 1000L
private const val MAX_SCREENSHOT_BYTES = 6L * 1024L * 1024L

object VisionSnapshotStore {
    private data class Entry(
        val observation: ScreenObservation,
        val screenshotFile: File?,
        val createdAtMs: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun put(observation: ScreenObservation, screenshotFile: File?): String {
        prune()
        val token = UUID.randomUUID().toString()
        entries[token] = Entry(observation, screenshotFile, System.currentTimeMillis())
        return token
    }

    fun get(token: String): ScreenObservation? {
        prune()
        return entries[token]?.observation
    }

    fun remove(token: String) {
        entries.remove(token)?.screenshotFile?.delete()
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - SNAPSHOT_TTL_MS
        entries.entries.removeIf { (_, entry) ->
            if (entry.createdAtMs < cutoff) {
                entry.screenshotFile?.delete()
                true
            } else {
                false
            }
        }
    }
}

class VisionPipeline(
    private val context: Context,
    private val providerManager: ProviderManager,
) {

    suspend fun captureObservation(
        displayId: Int = 0,
        includeOcr: Boolean = false,
        saveScreenshot: Boolean = false,
    ): Pair<ScreenObservation, File?> = withContext(Dispatchers.IO) {
        val tree = AccessibilityServiceHandle.withService { svc ->
            val root = svc.rootInActiveWindow
                ?: return@withService buildJsonObject {
                    put("error", "no_active_window")
                }
            val nodes = mutableListOf<kotlinx.serialization.json.JsonObject>()
            svc.traverseTree(
                root = root,
                filter = ::defaultFilter,
                cap = 2000,
                emit = { node, _, index ->
                    nodes += nodeToJson(
                        node,
                        root.windowId,
                        index,
                    )
                },
            )
            buildJsonObject {
                put("package", root.packageName?.toString().orEmpty())
                put("window_title", root.window?.title?.toString().orEmpty())
                put("window_id", root.windowId)
                put("nodes", buildJsonArray { nodes.forEach { add(it) } })
            }
        }

        val screenshot = RikkaAccessibilityService.instance?.captureScreenshot(displayId)
        val bitmap = (screenshot as? RikkaAccessibilityService.ScreenshotOutcome.Success)?.bitmap
        val ocr = if (includeOcr && bitmap != null) recognizeBitmap(bitmap) else emptyList()
        val elements = parseTreeElements(tree)
        val packageName = tree["package"]?.toString()?.trim('"').orEmpty()
        val windowTitle = tree["window_title"]?.toString()?.trim('"').orEmpty()
        val width = bitmap?.width ?: elements.maxOfOrNull { it.bounds.right } ?: 0
        val height = bitmap?.height ?: elements.maxOfOrNull { it.bounds.bottom } ?: 0

        val observation = ScreenObservation(
            packageName = packageName,
            windowTitle = windowTitle,
            displayWidth = width,
            displayHeight = height,
            elements = elements,
            ocr = ocr,
            createdAtMs = System.currentTimeMillis(),
            fingerprint = fingerprint(packageName, windowTitle, width, height, elements, ocr),
        )

        val file = if (saveScreenshot && bitmap != null) {
            val dir = File(context.cacheDir, "vision").apply { mkdirs() }
            val out = File(
                dir,
                "vision-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".png",
            )
            writeBoundedPng(bitmap, out)
            out
        } else {
            null
        }
        bitmap?.recycle()
        observation to file
    }

    suspend fun recognizeFile(path: String): List<OcrTextBlock> = withContext(Dispatchers.IO) {
        val file = File(path)
        require(file.exists() && file.length() in 1..MAX_SCREENSHOT_BYTES) {
            "OCR input file is missing or too large"
        }
        val bitmap = BitmapFactory.decodeFile(path) ?: error("Could not decode OCR input")
        try {
            recognizeBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun recognizeBitmap(bitmap: Bitmap): List<OcrTextBlock> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val result = recognizer.process(image).await()
            result.textBlocks.flatMap { block ->
                block.lines.mapNotNull { line ->
                    val bounds = line.boundingBox ?: return@mapNotNull null
                    val text = line.text.trim()
                    if (text.isBlank()) return@mapNotNull null
                    OcrTextBlock(
                        text = text,
                        bounds = ScreenBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
                        confidence = (line.confidence ?: 0.75f).coerceIn(0f, 1f),
                    )
                }
            }
        } finally {
            recognizer.close()
        }
    }

    suspend fun findTarget(
        target: String,
        displayId: Int = 0,
        highConfidence: Float = 0.90f,
        mediumConfidence: Float = 0.70f,
        visionModelId: String? = null,
    ): VisionDecision {
        require(target.isNotBlank()) { "target is required" }
        val policy = VisionConfidencePolicy(highConfidence, mediumConfidence)

        val first = captureObservation(
            displayId = displayId,
            includeOcr = false,
            saveScreenshot = false,
        ).first
        findInElements(first.elements, target)?.let {
            return it.copy(safeToUseForSensitiveAction = policy.sensitiveActionAllowed(it.confidence))
        }

        val second = captureObservation(
            displayId = displayId,
            includeOcr = true,
            saveScreenshot = true,
        )
        findInOcr(second.first.ocr, target)?.let {
            second.second?.delete()
            return it.copy(safeToUseForSensitiveAction = policy.sensitiveActionAllowed(it.confidence))
        }

        val file = second.second
        if (visionModelId != null && file != null) {
            val semantic = analyzeWithVisionModel(file, target, visionModelId)
            file.delete()
            if (semantic != null) {
                return semantic.copy(
                    safeToUseForSensitiveAction = policy.sensitiveActionAllowed(semantic.confidence),
                )
            }
        } else {
            file?.delete()
        }

        return VisionDecision(
            target = target,
            confidence = 0f,
            source = "not_found",
            state = "not_found",
            safeToUseForSensitiveAction = false,
        )
    }

    suspend fun analyzeWithVisionModel(
        imageFile: File,
        target: String? = null,
        modelId: String,
    ): VisionDecision? {
        val settings = org.koin.core.context.GlobalContext.get()
            .get<io.github.nastechresearch.nastech.data.datastore.SettingsStore>()
            .settingsFlow.value
        val modelUuid = runCatching { kotlin.uuid.Uuid.parse(modelId) }.getOrNull() ?: return null
        val model = settings.findModelById(modelUuid) ?: return null
        if (!model.inputModalities.contains(Modality.IMAGE)) return null
        val providerSetting = model.findProvider(settings.providers) ?: return null
        val provider = providerManager.getProviderByType(providerSetting)

        val prompt = buildString {
            append("Return JSON only. Analyze the Android screen image.")
            if (!target.isNullOrBlank()) append(" Find the target named: ").append(target).append('.')
            append(
                """
Return an object with target, type, bounds {left,top,right,bottom}, confidence, state, enabled, relation.
type must be one of TEXT, BUTTON, INPUT, SWITCH, CHECKBOX, LIST, DIALOG, ICON, CLICKABLE_REGION.
confidence is between 0 and 1. bounds are absolute screen pixels from the image.
""".trimIndent(),
            )
        }

        val result = provider.generateText(
            providerSetting = providerSetting,
            messages = listOf(
                UIMessage.system(prompt),
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Image(Uri.fromFile(imageFile).toString())),
                ),
            ),
            params = TextGenerationParams(
                model = model,
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
            ),
        )

        val parsed = runCatching {
            Json.parseToJsonElement(result.message.toText()).jsonObject
        }.getOrNull() ?: return null

        val targetValue = parsed["target"]?.toString()?.trim('"')
            .orEmpty()
            .ifBlank { target ?: "screen" }
        val typeValue = parsed["type"]?.toString()?.trim('"')
        val type = runCatching { typeValue?.let(ScreenElementType::valueOf) }.getOrNull()
        val boundsObject = parsed["bounds"]?.jsonObject
        val bounds = boundsObject?.let {
            ScreenBounds(
                it["left"]?.toString()?.toIntOrNull() ?: 0,
                it["top"]?.toString()?.toIntOrNull() ?: 0,
                it["right"]?.toString()?.toIntOrNull() ?: 0,
                it["bottom"]?.toString()?.toIntOrNull() ?: 0,
            )
        }
        return VisionDecision(
            target = targetValue,
            type = type,
            bounds = bounds,
            confidence = (parsed["confidence"]?.toString()?.toFloatOrNull() ?: 0f).coerceIn(0f, 1f),
            state = parsed["state"]?.toString()?.trim('"').orEmpty(),
            source = "vision",
            relation = parsed["relation"]?.toString()?.trim('"'),
            enabled = parsed["enabled"]?.toString()?.toBooleanStrictOrNull() ?: true,
        )
    }

    private fun parseTreeElements(tree: kotlinx.serialization.json.JsonObject): List<ScreenElement> {
        val nodes = tree["nodes"]?.jsonArray ?: return emptyList()
        return nodes.mapNotNull { element ->
            val obj = element.jsonObject
            val bounds = obj["bounds"]?.jsonArray ?: return@mapNotNull null
            if (bounds.size != 4) return@mapNotNull null
            val screenBounds = ScreenBounds(
                bounds[0].toString().toIntOrNull() ?: 0,
                bounds[1].toString().toIntOrNull() ?: 0,
                bounds[2].toString().toIntOrNull() ?: 0,
                bounds[3].toString().toIntOrNull() ?: 0,
            )
            val className = obj["class"]?.toString()?.trim('"').orEmpty()
            val text = obj["text"]?.toString()?.trim('"').orEmpty()
            val description = obj["content_description"]?.toString()?.trim('"').orEmpty()
            ScreenElement(
                type = inferType(
                    className,
                    obj["clickable"]?.toString()?.toBoolean() == true,
                ),
                text = text.ifBlank { description },
                bounds = screenBounds,
                enabled = obj["enabled"]?.toString()?.toBoolean() ?: true,
                source = "ui_tree",
            )
        }
    }

    private fun findInElements(
        elements: List<ScreenElement>,
        target: String,
    ): VisionDecision? {
        val needle = normalize(target)
        return elements.asSequence()
            .filter { normalize(it.text).contains(needle) }
            .map { element ->
                VisionDecision(
                    target = target,
                    type = element.type,
                    bounds = element.bounds,
                    confidence = if (normalize(element.text) == needle) 1f else 0.96f,
                    state = if (element.enabled) "enabled" else "disabled",
                    source = "ui_tree",
                    enabled = element.enabled,
                )
            }
            .maxByOrNull { it.confidence }
    }

    private fun findInOcr(
        blocks: List<OcrTextBlock>,
        target: String,
    ): VisionDecision? {
        val needle = normalize(target)
        return blocks.asSequence()
            .filter { normalize(it.text).contains(needle) }
            .map { block ->
                VisionDecision(
                    target = target,
                    type = ScreenElementType.CLICKABLE_REGION,
                    bounds = block.bounds,
                    confidence = minOf(
                        block.confidence,
                        if (normalize(block.text) == needle) 0.94f else 0.88f,
                    ),
                    state = "ocr_match",
                    source = "ocr",
                )
            }
            .maxByOrNull { it.confidence }
    }

    private fun inferType(
        className: String,
        clickable: Boolean,
    ): ScreenElementType = when {
        "button" in className.lowercase() -> ScreenElementType.BUTTON
        "edittext" in className.lowercase() -> ScreenElementType.INPUT
        "switch" in className.lowercase() -> ScreenElementType.SWITCH
        "checkbox" in className.lowercase() -> ScreenElementType.CHECKBOX
        "dialog" in className.lowercase() -> ScreenElementType.DIALOG
        "image" in className.lowercase() && clickable -> ScreenElementType.ICON
        clickable -> ScreenElementType.CLICKABLE_REGION
        else -> ScreenElementType.TEXT
    }

    private fun normalize(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").lowercase()

    private fun fingerprint(
        packageName: String,
        windowTitle: String,
        width: Int,
        height: Int,
        elements: List<ScreenElement>,
        ocr: List<OcrTextBlock>,
    ): String {
        val raw = buildString {
            append(packageName).append('|').append(windowTitle).append('|')
                .append(width).append('x').append(height)
            elements.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left })).forEach {
                append('|').append(it.type).append('|').append(normalize(it.text))
                    .append('|').append(it.bounds.left).append(',').append(it.bounds.top)
                    .append(',').append(it.bounds.right).append(',').append(it.bounds.bottom)
                    .append('|').append(it.enabled)
            }
            ocr.forEach { append('|').append(normalize(it.text)) }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun writeBoundedPng(bitmap: Bitmap, target: File) {
        FileOutputStream(target).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        require(target.length() <= MAX_SCREENSHOT_BYTES) {
            "Vision screenshot exceeds size limit"
        }
    }
}