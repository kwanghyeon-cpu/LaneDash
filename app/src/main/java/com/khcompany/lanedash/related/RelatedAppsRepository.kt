package com.khcompany.lanedash.related

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "RelatedApps"
private const val PREFS = "related_apps"
private const val KEY_JSON = "json"
private const val KEY_FETCHED_AT = "fetched_at"
private const val ASSET = "related-apps.json"
private const val TIMEOUT_MS = 5000

/**
 * Loads the sibling list (cached copy, refreshed at most once a day; the copy bundled in assets when
 * nothing else works) and opens a sibling. Nothing about the player is sent: it's a plain GET of a
 * public file and icons.
 */
object RelatedAppsRepository {
    private val lock = Mutex()
    private var cached: List<RelatedApp>? = null
    private val icons = HashMap<String, ImageBitmap?>()

    suspend fun siblings(context: Context): List<RelatedApp> = lock.withLock {
        cached ?: withContext(Dispatchers.IO) { load(context.applicationContext) }.also { cached = it }
    }

    private fun load(context: Context): List<RelatedApp> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var json = prefs.getString(KEY_JSON, null)
        if (RelatedApps.shouldRefresh(prefs.getLong(KEY_FETCHED_AT, 0L), System.currentTimeMillis())) {
            val fresh = runCatching { download(RelatedApps.LIST_URL).decodeToString() }
                .mapCatching { RelatedApps.parse(it); it } // only keep a copy that parses
                .onFailure { Log.w(TAG, "list download failed: ${it.message}") }
                .getOrNull()
            if (fresh != null) {
                json = fresh
                prefs.edit().putString(KEY_JSON, fresh).putLong(KEY_FETCHED_AT, System.currentTimeMillis()).apply()
            }
        }
        val catalog = json?.let { runCatching { RelatedApps.parse(it) }.getOrNull() }
            ?: runCatching { RelatedApps.parse(context.assets.open(ASSET).use { it.readBytes().decodeToString() }) }.getOrNull()
            ?: return emptyList()
        return RelatedApps.siblings(catalog)
    }

    /** The sibling's icon, kept in the cache folder after the first download; null → caller draws a glyph. */
    suspend fun icon(context: Context, app: RelatedApp): ImageBitmap? {
        val url = app.iconUrl ?: return null
        lock.withLock { if (icons.containsKey(url)) return icons[url] }
        val bitmap = withContext(Dispatchers.IO) {
            val file = File(File(context.cacheDir, "related-icons").apply { mkdirs() }, "${app.id}.png")
            runCatching {
                if (!file.exists() || file.length() == 0L) file.writeBytes(download(url))
                BitmapFactory.decodeFile(file.path)?.asImageBitmap()
            }.onFailure { file.delete() }.getOrNull()
        }
        lock.withLock { icons[url] = bitmap }
        return bitmap
    }

    /** Installed → open it; otherwise the Play Store (app, then browser) or the web build. */
    fun open(context: Context, app: RelatedApp) {
        app.packageName?.let { pkg ->
            context.packageManager.getLaunchIntentForPackage(pkg)?.let { launch ->
                if (tryStart(context, launch)) return
            }
        }
        when (val link = RelatedApps.linkFor(app)) {
            is RelatedLink.Store ->
                if (!tryStart(context, view(link.marketUri))) tryStart(context, view(link.webUri))
            is RelatedLink.Web -> tryStart(context, view(link.url))
            null -> Unit
        }
    }

    private fun view(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    private fun download(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
