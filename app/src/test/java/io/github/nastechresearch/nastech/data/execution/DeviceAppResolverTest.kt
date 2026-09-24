package io.github.nastechresearch.nastech.data.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAppResolverTest {
    private val apps = listOf(
        InstalledAppCandidate("Gemini", "com.google.android.apps.bard", "MainActivity"),
        InstalledAppCandidate("Calculator", "com.example.calc", "MainActivity"),
        InstalledAppCandidate("Notes", "com.example.notes", "MainActivity"),
    )

    @Test
    fun resolvesExactLabel() {
        val resolver = AppResolver(InstalledAppCatalog { apps })

        val result = resolver.resolve(" gemini ")

        assertTrue(result is AppResolution.Resolved)
        assertEquals("com.google.android.apps.bard", (result as AppResolution.Resolved).candidate.packageName)
    }

    @Test
    fun unknownLabelFailsWithoutGuessing() {
        val resolver = AppResolver(InstalledAppCatalog { apps })

        val result = resolver.resolve("Gemini 2")

        assertTrue(result is AppResolution.NotFound)
    }

    @Test
    fun duplicateLabelsBecomeAmbiguous() {
        val resolver = AppResolver(
            InstalledAppCatalog {
                apps + InstalledAppCandidate("Gemini", "com.other.gemini", "OtherActivity")
            }
        )

        val result = resolver.resolve("Gemini")

        assertTrue(result is AppResolution.Ambiguous)
        assertEquals(2, (result as AppResolution.Ambiguous).candidates.size)
    }
}
