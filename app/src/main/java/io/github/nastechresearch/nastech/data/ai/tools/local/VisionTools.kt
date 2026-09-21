package io.github.nastechresearch.nastech.data.ai.tools.local

import android.content.Context
import io.github.nastechresearch.nastech.data.agent.AgentConfigRepository
import io.github.nastechresearch.nastech.data.agent.AgentRole
import io.github.nastechresearch.nastech.data.ai.tools.ToolInvocationContext
import io.github.nastechresearch.nastech.data.vision.VisionPipeline
import io.github.nastechresearch.nastech.data.vision.VisionSnapshotStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

private object VisionToolKoin : KoinComponent

private fun visionModelId(invocationContext: ToolInvocationContext): String? {
    val conversationId = invocationContext.callerConversationId ?: return null
    return kotlinx.coroutines.runBlocking {
        VisionToolKoin.get<AgentConfigRepository>().get(conversationId).agents
            .firstOrNull { it.enabled && it.role == AgentRole.VISION }
            ?.modelId
    }
}

fun visionCaptureTool(
    context: Context,
): Tool = Tool(
    name = "vision_capture",
    description = "Capture the current Android screen as a short-lived vision snapshot. The screenshot is cached temporarily only and is deleted automatically.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("display_id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Display id, default 0")
                })
            },
        )
    },
    execute = { input ->
        val displayId = input.jsonObject["display_id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val pipeline = VisionPipeline(context, VisionToolKoin.get())
        val (observation, file) = pipeline.captureObservation(
            displayId = displayId,
            includeOcr = false,
            saveScreenshot = true,
        )
        val token = VisionSnapshotStore.put(observation, file)
        buildList {
            add(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", file != null)
                        put("snapshot_token", token)
                        put("package", observation.packageName)
                        put("window_title", observation.windowTitle)
                        put("display_width", observation.displayWidth)
                        put("display_height", observation.displayHeight)
                        put("element_count", observation.elements.size)
                        put("expires_in_ms", 5 * 60 * 1000)
                    }.toString(),
                ),
            )
            file?.let { add(UIMessagePart.Image(url = "file://" + it.absolutePath)) }
        }
    },
)

fun ocrExtractTool(
    context: Context,
): Tool = Tool(
    name = "ocr_extract",
    description = "Extract visible text with bounding boxes and confidence. Uses on-device OCR and never needs a cloud model for basic text extraction.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("file_path", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional local image path. Omit to OCR the current screen.")
                })
            },
        )
    },
    execute = { input ->
        val path = input.jsonObject["file_path"]?.jsonPrimitive?.contentOrNull
        val pipeline = VisionPipeline(context, VisionToolKoin.get())
        val blocks = if (!path.isNullOrBlank()) {
            pipeline.recognizeFile(path)
        } else {
            val (observation, file) = pipeline.captureObservation(
                includeOcr = true,
                saveScreenshot = true,
            )
            file?.delete()
            observation.ocr
        }
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("count", blocks.size)
                    put("blocks", Json.encodeToString(blocks))
                }.toString(),
            ),
        )
    },
)

fun uiFindVisualTargetTool(
    context: Context,
    invocationContext: ToolInvocationContext,
): Tool = Tool(
    name = "ui_find_visual_target",
    description = "Find an Android UI target by text/meaning using UI Tree first, then OCR, then the configured Vision Agent. Returns structured bounds and confidence and never performs the action.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("target", buildJsonObject {
                    put("type", "string")
                    put("description", "Visible target, such as Open file or Wi-Fi")
                })
                put("display_id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Display id, default 0")
                })
                put("high_confidence", buildJsonObject {
                    put("type", "number")
                    put("description", "Threshold for sensitive-action safety, default 0.90")
                })
                put("medium_confidence", buildJsonObject {
                    put("type", "number")
                    put("description", "Threshold for medium-confidence verification, default 0.70")
                })
            },
            required = listOf("target"),
        )
    },
    execute = { input ->
        val target = input.jsonObject["target"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool listOf(UIMessagePart.Text("{\"error\":\"target_required\"}"))
        val displayId = input.jsonObject["display_id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val high = input.jsonObject["high_confidence"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0.90f
        val medium = input.jsonObject["medium_confidence"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0.70f
        val modelId = visionModelId(invocationContext)
        val result = VisionPipeline(context, VisionToolKoin.get()).findTarget(
            target = target,
            displayId = displayId,
            highConfidence = high,
            mediumConfidence = medium,
            visionModelId = modelId,
        )
        listOf(UIMessagePart.Text(Json.encodeToString(result)))
    },
)

fun visionAnalyzeTool(
    context: Context,
    invocationContext: ToolInvocationContext,
): Tool = Tool(
    name = "vision_analyze",
    description = "Explicitly analyze the current screen with the configured Vision Agent model and return structured output. Call this only when UI Tree and OCR are insufficient.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("target", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional target to locate semantically")
                })
            },
        )
    },
    execute = { input ->
        val modelId = visionModelId(invocationContext)
            ?: return@Tool listOf(
                UIMessagePart.Text(
                    "{\"error\":\"vision_model_not_configured\",\"detail\":\"Configure an enabled Vision Agent with a vision-capable model first.\"}",
                ),
            )
        val target = input.jsonObject["target"]?.jsonPrimitive?.contentOrNull
        val pipeline = VisionPipeline(context, VisionToolKoin.get())
        val (_, file) = pipeline.captureObservation(includeOcr = false, saveScreenshot = true)
        if (file == null) {
            return@Tool listOf(UIMessagePart.Text("{\"error\":\"screenshot_unavailable\"}"))
        }
        try {
            val result = pipeline.analyzeWithVisionModel(file, target, modelId)
                ?: return@Tool listOf(UIMessagePart.Text("{\"error\":\"vision_analysis_failed\"}"))
            listOf(UIMessagePart.Text(Json.encodeToString(result)))
        } finally {
            file.delete()
        }
    },
)

fun visionVerifyStateTool(
    context: Context,
): Tool = Tool(
    name = "vision_verify_state",
    description = "Compare the current screen with a previous vision snapshot using UI structure and OCR semantics. It reports screen change and optional expected text; it does not approve or execute an action.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("before_token", buildJsonObject {
                    put("type", "string")
                    put("description", "Token from vision_capture")
                })
                put("expected_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional text that should now exist")
                })
            },
            required = listOf("before_token"),
        )
    },
    execute = { input ->
        val token = input.jsonObject["before_token"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool listOf(UIMessagePart.Text("{\"error\":\"before_token_required\"}"))
        val before = VisionSnapshotStore.get(token)
            ?: return@Tool listOf(UIMessagePart.Text("{\"error\":\"snapshot_expired\"}"))
        val expected = input.jsonObject["expected_text"]?.jsonPrimitive?.contentOrNull
        val after = VisionPipeline(context, VisionToolKoin.get())
            .captureObservation(includeOcr = true, saveScreenshot = false)
            .first
        val expectedPresent = expected.isNullOrBlank() ||
            after.ocr.any { it.text.contains(expected, ignoreCase = true) } ||
            after.elements.any { it.text.contains(expected.orEmpty(), ignoreCase = true) }
        val changed = before.fingerprint != after.fingerprint
        VisionSnapshotStore.remove(token)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("changed", changed)
                    put("expected_present", expectedPresent)
                    put("before_fingerprint", before.fingerprint)
                    put("after_fingerprint", after.fingerprint)
                }.toString(),
            ),
        )
    },
)
