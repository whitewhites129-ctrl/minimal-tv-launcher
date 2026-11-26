package br.com.launcher.tv.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import java.util.Locale

/**
 * Queries launchable TV/mobile activities and builds their launch Intents.
 * It has no Activity or UI dependency.
 */
class AppCatalogRepository(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    fun loadLaunchableApps(): List<ResolveInfo> {
        val byPackage = LinkedHashMap<String, ResolveInfo>()
        val queries = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        )

        queries.forEach { query ->
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(query, PackageManager.MATCH_ALL).forEach appLoop@{ info ->
                val packageName = info.activityInfo?.packageName ?: return@appLoop
                if (packageName != appContext.packageName) {
                    byPackage.putIfAbsent(packageName, info)
                }
            }
        }

        return byPackage.values.sortedBy {
            it.loadLabel(packageManager).toString().lowercase(Locale.getDefault())
        }
    }

    fun createLaunchIntent(app: ResolveInfo): Intent {
        val activityInfo = requireNotNull(app.activityInfo) {
            "O aplicativo não possui Activity de inicialização"
        }

        return packageManager.getLaunchIntentForPackage(activityInfo.packageName)
            ?: Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setClassName(activityInfo.packageName, activityInfo.name)
    }
}
