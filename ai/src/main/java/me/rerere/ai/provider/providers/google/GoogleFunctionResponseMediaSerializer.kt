package me.rerere.ai.provider.providers.google

import me.rerere.ai.provider.providers.ToolMediaArtifact
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.registry.ModelRegistry

internal class GoogleMediaReferenceAllocator {
    private var nextIndex = 0

    fun next(): String = "nastech_media_${nextIndex++}"
}

internal data class GoogleFunctionResponseMedia(
    val displayName: String,
    val mimeType: String,
    val data: String,
)

internal sealed interface GoogleMediaEncodingResult {
    data class Encoded(val media: GoogleFunctionResponseMedia) : GoogleMediaEncodingResult

    data class Skipped(
        val artifact: ToolMediaArtifact,
        val reason: String,
    ) : GoogleMediaEncodingResult
}

internal object GoogleFunctionResponseMediaSerializer {
    private val supportedInlineImageMimeTypes = setOf(
        "image/png",
        "image/jpeg",
        "image/webp",
    )

    fun supportsMultimodalFunctionResponses(modelId: String?): Boolean {
        val normalized = modelId?.lowercase()?.trim() ?: return false
        return ModelRegistry.GEMINI_3_SERIES.match(normalized)
    }

    fun encode(
        artifact: ToolMediaArtifact,
        displayName: String,
        allowMultimodal: Boolean,
    ): GoogleMediaEncodingResult {
        if (!allowMultimodal) {
            return GoogleMediaEncodingResult.Skipped(
                artifact = artifact,
                reason = "multimodal_function_response_unsupported_for_model",
            )
        }

        val image = artifact.source as? UIMessagePart.Image
            ?: return GoogleMediaEncodingResult.Skipped(
                artifact = artifact,
                reason = "media_type_not_supported_in_function_response",
            )

        val encoded = image.encodeBase64(withPrefix = false).getOrElse { error ->
            return GoogleMediaEncodingResult.Skipped(
                artifact = artifact,
                reason = "media_encoding_failed:${error.message ?: error::class.simpleName ?: "unknown"}",
            )
        }

        if (encoded.mimeType !in supportedInlineImageMimeTypes) {
            return GoogleMediaEncodingResult.Skipped(
                artifact = artifact,
                reason = "unsupported_function_response_mime_type:${encoded.mimeType}",
            )
        }

        return GoogleMediaEncodingResult.Encoded(
            media = GoogleFunctionResponseMedia(
                displayName = displayName,
                mimeType = encoded.mimeType,
                data = encoded.base64,
            )
        )
    }
}
