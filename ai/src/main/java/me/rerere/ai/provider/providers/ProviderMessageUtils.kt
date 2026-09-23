package me.rerere.ai.provider.providers

import me.rerere.ai.ui.UIMessagePart

/**
 * Provider-neutral media artifact extracted from an executed tool result.
 *
 * The artifact keeps the original content reference and stable tool/output identity.
 * Provider adapters decide how (or whether) to materialize it on their own wire format.
 */
data class ToolMediaArtifact(
    val id: String,
    val contentRef: String,
    val mimeType: String,
    val metadata: JsonObject?,
    val source: UIMessagePart,
)

fun UIMessagePart.Tool.mediaArtifacts(): List<ToolMediaArtifact> =
    output.mapIndexedNotNull { index, part ->
        when (part) {
            is UIMessagePart.Image -> ToolMediaArtifact(
                id = mediaArtifactId(toolCallId, index),
                contentRef = part.url,
                mimeType = part.mediaMimeType(default = "image/png"),
                metadata = part.metadata,
                source = part,
            )

            is UIMessagePart.Video -> ToolMediaArtifact(
                id = mediaArtifactId(toolCallId, index),
                contentRef = part.url,
                mimeType = part.mediaMimeType(default = "video/mp4"),
                metadata = part.metadata,
                source = part,
            )

            is UIMessagePart.Audio -> ToolMediaArtifact(
                id = mediaArtifactId(toolCallId, index),
                contentRef = part.url,
                mimeType = part.mediaMimeType(default = "audio/mp3"),
                metadata = part.metadata,
                source = part,
            )

            else -> null
        }
    }

private fun mediaArtifactId(toolCallId: String, index: Int): String {
    val owner = toolCallId.ifBlank { "tool" }
    return "$owner:$index"
}

private fun UIMessagePart.Image.mediaMimeType(default: String): String =
    metadataMimeType() ?: dataUrlMimeType(url) ?: fileExtensionMimeType(url) ?: default

private fun UIMessagePart.Video.mediaMimeType(default: String): String =
    metadataMimeType() ?: dataUrlMimeType(url) ?: default

private fun UIMessagePart.Audio.mediaMimeType(default: String): String =
    metadataMimeType() ?: dataUrlMimeType(url) ?: default

private fun UIMessagePart.metadataMimeType(): String? =
    metadata?.get("mimeType")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }

private fun dataUrlMimeType(url: String): String? =
    url.takeIf { it.startsWith("data:", ignoreCase = true) }
        ?.substringAfter("data:", "")
        ?.substringBefore(';')
        ?.takeIf { it.isNotBlank() }

private fun fileExtensionMimeType(url: String): String? {
    val name = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
    val extension = name.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4", "m4v" -> "video/mp4"
        "mp3" -> "audio/mp3"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        else -> null
    }
}


/**
 * 消息 parts 按工具边界分组的结果
 * - Content: 普通内容（Text、Image、Reasoning 等）
 * - Tools: 连续的已执行工具
 */
sealed class PartGroup {
    data class Content(val parts: List<UIMessagePart>) : PartGroup()
    data class Tools(val tools: List<UIMessagePart.Tool>) : PartGroup()
}

/**
 * 将消息 parts 按工具边界分组
 *
 * 例如 [Text1, Tool1, Tool2, Text2, Tool3] 会分组为:
 * - Content([Text1])
 * - Tools([Tool1, Tool2])
 * - Content([Text2])
 * - Tools([Tool3])
 *
 * 这样可以确保 tool_use/functionCall 后面紧跟 tool_result/functionResponse
 */
fun groupPartsByToolBoundary(parts: List<UIMessagePart>): List<PartGroup> {
    val groups = mutableListOf<PartGroup>()
    val currentContent = mutableListOf<UIMessagePart>()
    val currentTools = mutableListOf<UIMessagePart.Tool>()

    fun flushContent() {
        if (currentContent.isNotEmpty()) {
            groups.add(PartGroup.Content(currentContent.toList()))
            currentContent.clear()
        }
    }

    fun flushTools() {
        if (currentTools.isNotEmpty()) {
            groups.add(PartGroup.Tools(currentTools.toList()))
            currentTools.clear()
        }
    }

    for (part in parts) {
        if (part is UIMessagePart.Tool && part.isExecuted) {
            flushContent()
            currentTools.add(part)
        } else {
            flushTools()
            currentContent.add(part)
        }
    }

    flushContent()
    flushTools()
    return groups
}
