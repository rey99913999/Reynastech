
package me.rerere.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRegistryTest {
    @Test
    fun customProtocolDefaultsAreRegistered() {
        assertEquals(
            "https://router.huggingface.co/v1",
            ProviderRegistry.defaultUrl("hugging_face"),
        )
        assertEquals(
            "http://127.0.0.1:1234/v1",
            ProviderRegistry.defaultUrl("lm_studio"),
        )
        assertTrue(ProviderRegistry.isKnownProtocol("openai_compatible"))
    }

    @Test
    fun customProviderIsMappedToGenericAdapter() {
        val provider = ProviderSetting.Custom(protocolId = "hugging_face")
        assertEquals(
            ProviderAdapterType.HUGGING_FACE,
            ProviderRegistry.descriptorFor(provider)?.adapterType,
        )
    }
}
