package com.khcompany.lanedash.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Decodes each sprite once, off the UI thread, and keeps it for the life of the process.
 * The sprites live in res/drawable-nodpi so they decode at their file size instead of being
 * scaled up by the screen density (that scaling once turned a 6 MB skyline into ~150 MB).
 * Decodes are serialized so two screens asking at once can't spike memory together.
 */
object SpriteCache {
    private val cache = ConcurrentHashMap<String, ImageBitmap>()
    private val decodeLock = Mutex()

    fun peek(name: String): ImageBitmap? = cache[name]

    /** Null when [name] is blank or no such drawable exists, so callers can fall back to procedural art. */
    suspend fun load(context: Context, name: String): ImageBitmap? {
        if (name.isBlank()) return null
        cache[name]?.let { return it }
        return withContext(Dispatchers.IO) {
            decodeLock.withLock {
                cache[name] ?: decode(context.applicationContext, name)?.also { cache[name] = it }
            }
        }
    }

    private fun decode(context: Context, name: String): ImageBitmap? {
        val id = context.resources.getIdentifier(name, "drawable", context.packageName)
        if (id == 0) return null
        return runCatching { BitmapFactory.decodeResource(context.resources, id).asImageBitmap() }.getOrNull()
    }
}

/**
 * Looks up a drawable by file name (no extension) at runtime, e.g. "car_gen1" for a file
 * dropped into res/drawable-nodpi as car_gen1.png. Null while it's still loading or when no
 * such drawable exists, so callers fall back to procedural art until the real sprite is there.
 */
@Composable
fun rememberOptionalSprite(name: String): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val sprite by produceState(initialValue = SpriteCache.peek(name), name) {
        if (value == null) value = SpriteCache.load(context, name)
    }
    return sprite
}
