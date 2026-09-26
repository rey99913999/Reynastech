package io.github.nastechresearch.nastech.data.api

import io.github.nastechresearch.nastech.data.model.HfGatedSerializer
import io.github.nastechresearch.nastech.data.model.HfModelDetail
import io.github.nastechresearch.nastech.data.model.HfSibling
import io.github.nastechresearch.nastech.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-only coverage for the current HuggingFace wire models. The former implementation-level
 * HuggingFaceModelSearch test referenced a class removed from the production tree; these tests
 * exercise the public models that the current picker uses instead.
 */
class HuggingFaceModelSearchTest {

    @Test
    fun `public repo decodes gated false`() {
        val detail = JsonInstant.decodeFromString<HfModelDetail>(
            """{"id":"Qwen/Qwen3-4B-GGUF","gated":false,"private":false,"siblings":[]}""",
        )
        assertEquals(false, detail.gated)
        assertEquals(false, detail.private)
    }

    @Test
    fun `manual and auto gate kinds decode as gated`() {
        val manual = JsonInstant.decodeFromString<HfModelDetail>(
            """{"id":"x/y","gated":"manual","private":false,"siblings":[]}""",
        )
        val auto = JsonInstant.decodeFromString<HfModelDetail>(
            """{"id":"x/y","gated":"auto","private":false,"siblings":[]}""",
        )
        assertTrue(manual.gated)
        assertTrue(auto.gated)
    }

    @Test
    fun `missing sibling size remains null when blobs are not requested`() {
        val detail = JsonInstant.decodeFromString<HfModelDetail>(
            """{"id":"x/y","gated":false,"private":false,"siblings":[{"rfilename":"model.gguf"}]}""",
        )
        assertEquals(listOf(HfSibling("model.gguf", size = null)), detail.siblings)
    }
}
