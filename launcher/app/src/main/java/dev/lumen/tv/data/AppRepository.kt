package dev.lumen.tv.data

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import dev.lumen.tv.Config
import dev.lumen.tv.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TvApp(
    /** Unique per launchable activity ("package/Activity"): Lumen has several (Radio, Ambientes). */
    val id: String,
    val packageName: String,
    val label: String,
    val banner: ImageBitmap?,
    val icon: ImageBitmap?,
    val launchIntent: Intent,
)

object AppRepository {

    /** System Settings already has its own button on the home screen. */
    private val HIDDEN = setOf("com.android.tv.settings")

    /**
     * Decoded apps keyed by package, with the install/update time they were decoded at.
     * Coming back home only re-decodes banners for apps that were installed or updated since.
     */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, TvApp>>() // also read by the phone remote

    suspend fun load(context: Context): List<TvApp> = withContext(Dispatchers.Default) {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val seen = mutableSetOf<String>()

        val apps = pm.queryIntentActivities(query, 0).mapNotNull { info ->
            val activity = info.activityInfo
            val pkg = activity.packageName
            val ours = pkg == context.packageName
            // Other apps: one tile per package. Lumen itself: its extra apps, but not the launcher.
            val id = if (ours) "$pkg/${activity.name}" else pkg
            if (ours && activity.name == MainActivity::class.java.name) return@mapNotNull null
            if (pkg in HIDDEN || !seen.add(id)) return@mapNotNull null
            val updated = runCatching { pm.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L)
            cache[id]?.takeIf { it.first == updated }?.second ?: TvApp(
                id = id,
                packageName = pkg,
                label = info.loadLabel(pm).toString(),
                // prepareToDraw uploads the bitmap to the GPU now, so the first scroll doesn't stutter.
                banner = activity.loadBanner(pm)?.toBitmap(320, 180)?.apply { prepareToDraw() }?.asImageBitmap(),
                icon = info.loadIcon(pm)?.toBitmap(144, 144)?.apply { prepareToDraw() }?.asImageBitmap(),
                launchIntent = Intent(query)
                    .setClassName(pkg, activity.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            ).also { cache[id] = updated to it }
        }
        cache.keys.retainAll(seen)

        // Favourites in their configured order, then Lumen's own apps, then its companions
        // (HOUSE_APPS), then the rest A–Z.
        apps.sortedWith(compareBy<TvApp> { app ->
            Config.FAVORITES.indexOf(app.id).let { if (it < 0) Int.MAX_VALUE else it }
        }.thenBy {
            when {
                it.packageName == context.packageName -> 0
                it.packageName in Config.HOUSE_APPS -> 1
                else -> 2
            }
        }.thenBy { it.label.lowercase() })
    }
}
