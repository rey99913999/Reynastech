package io.github.nastechresearch.nastech.data.execution

import android.content.Context
import android.content.Intent

data class InstalledAppCandidate(
    val label: String,
    val packageName: String,
    val activityName: String,
)

sealed interface AppResolution {
    data class Resolved(val candidate: InstalledAppCandidate) : AppResolution
    data class Ambiguous(val query: String, val candidates: List<InstalledAppCandidate>) : AppResolution
    data class NotFound(val query: String) : AppResolution
}

fun interface InstalledAppCatalog {
    fun listLaunchableApps(): List<InstalledAppCandidate>
}

class PackageManagerInstalledAppCatalog(
    private val context: Context,
) : InstalledAppCatalog {
    override fun listLaunchableApps(): List<InstalledAppCandidate> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return context.packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val activity = info.activityInfo ?: return@mapNotNull null
                InstalledAppCandidate(
                    label = info.loadLabel(context.packageManager).toString(),
                    packageName = activity.packageName,
                    activityName = activity.name.orEmpty(),
                )
            }
            .distinctBy { it.packageName }
    }
}

class AppResolver(
    private val catalog: InstalledAppCatalog,
) {
    fun resolve(query: String): AppResolution {
        val normalized = normalizeDeviceText(query)
        if (normalized.isBlank()) return AppResolution.NotFound(query)

        val matches = catalog.listLaunchableApps()
            .filter { normalizeDeviceText(it.label) == normalized }
            .distinctBy { it.packageName }

        return when (matches.size) {
            0 -> AppResolution.NotFound(query)
            1 -> AppResolution.Resolved(matches.single())
            else -> AppResolution.Ambiguous(query, matches.take(8))
        }
    }
}
