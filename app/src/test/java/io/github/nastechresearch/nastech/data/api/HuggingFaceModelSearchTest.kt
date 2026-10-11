package io.github.nastechresearch.nastech.data.api

import kotlinx.serialization.decodeFromString
import io.github.nastechresearch.nastech.data.model.HfModelDetail
import io.github.nastechresearch.nastech.data.model.HfSibling
import io.github.nastechresearch.nastech.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests the current Hugging Face model-detail wire format without making network calls. */
class HuggingFaceModelSearchTest {

    @Test
    fun `gated decodes the literal JSON boolean false as public`() {
        val detail = JsonInstant.decodeFromString<HfModelDetail>(
            """{"id":"Qwen/Qwen3-4B-GGUF","gated":false,"private":false,"siblings":[]}""",
        )
        assertEquals(false, detail.gated)
    }

    @Test
    fun `gated decodes gate-kind strings as gated`() {
        val manual = JsonInstant.decodeFromString<HfModelDetail>(
            """{"id":"x/y","gated":"manual","private":false,"siblings":[]}""",
        )
        val auto = JsonInstant.decodeFromString<HfModelDetail>(
            """{"id":"x/y","gated":"auto","private":false,"siblings":[]}""",
        )
        assertEquals(true, manual.gated)
        assertEquals(true, auto.gated)
    }

    @Test
    fun `size is null when blobs were not requested`() {
        val detail = JsonInstant.decodeFromString<HfModelDetail>(
            """{"id":"x/y","gated":false,"private":false,"siblings":[{"rfilename":"model.gguf"}]}""",
        )
        assertEquals(listOf(HfSibling("model.gguf", size = null)), detail.siblings)
    }
}
