package io.github.nastechresearch.nastech.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginModelsTest {
    @Test
    fun compareVersionsHandlesDifferentLengths() {
        assertTrue(compareVersions("2.4.28", "2.4.27") > 0)
        assertEquals(0, compareVersions("1.2", "1.2.0"))
        assertTrue(compareVersions("1.9.9", "1.10.0") < 0)
    }

    @Test
    fun manifestIdIsNormalized() {
        val manifest = PluginManifest(
            id = "My Plugin",
            name = "Example",
            version = "1.0.0",
        )
        assertEquals("my-plugin", manifest.normalizedId())
    }

    @Test
    fun sensitivePermissionDetectionIsConservative() {
        assertTrue(isSensitivePermission("credentials"))
        assertTrue(isSensitivePermission("custom.secret.read"))
    }
}
