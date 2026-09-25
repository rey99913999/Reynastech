package me.rerere.ai.provider.providers.google

import me.rerere.ai.provider.providers.ToolMediaArtifact
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.encodeBase64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.registry.ModelRegistry

internal class GoogleMediaReferenceAllocator {
    private var nextIndex = 0

    fun next(): String = "nastech_media_${nextIndex++}"
}

internal enum class GoogleMediaSerializationMode {
    PRIMARY_MULTIMODAL_FUNCTION_RESPONSE,
    FALLBACK_INLINE_IMAGES,
}

internal object GoogleFunctionResponseMediaFallback {
    internal fun isMediaReferenceValidationError(errorText: String): Boolean {
        val normalized = errorText.lowercase()
        val hasFunctionResponsePath =
            "function_response.response" in normalized ||
                "functionresponse.response" in normalized
        val hasDisplayName = "display_name" in normalized || "displayname" in normalized
        val hasReference = "\$ref" in normalized || "reference" in normalized
        val hasMismatch = listOf(
            "mismatch",
            "mismatched",
            "does not match",
            "doesn't match",
            "no matching",
            "not match",
            "must match",
            "must equal",
        ).any(normalized::contains)

        return hasFunctionResponsePath && hasDisplayName && hasReference && hasMismatch
    }

    internal fun shouldAttemptFallback(
        mode: GoogleMediaSerializationMode,
        fallbackAttempted: Boolean,
        meaningfulOutputDelivered: Boolean,
        errorText: String,
    ): Boolean =
        mode == GoogleMediaSerializationMode.PRIMARY_MULTIMODAL_FUNCTION_RESPONSE &&
            !fallbackAttempted &&
            !meaningfulOutputDelivered &&
            isMediaReferenceValidationError(errorText)
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

    internal fun validateMultimodalReferences(
        response: JsonObject,
        inlineDataDisplayNames: List<String>,
    ): Boolean {
        val references = response.entries
            .filter { it.key.startsWith("nastech_media_") }
            .map { (key, value) ->
                key to value.jsonObject["\$ref"]?.jsonPrimitive?.content
            }

        if (references.size != inlineDataDisplayNames.size) return false
        if (references.any { (key, ref) -> ref == null || ref != key }) return false
        if (references.map { it.first }.distinct().size != references.size) return false
        if (inlineDataDisplayNames.distinct().size != inlineDataDisplayNames.size) return false

        return references.map { it.first } == inlineDataDisplayNames
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
