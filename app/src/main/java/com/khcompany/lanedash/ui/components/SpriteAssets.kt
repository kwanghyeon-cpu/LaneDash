package com.khcompany.lanedash.ui.components

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext

/**
 * Looks up a drawable by file name (no extension) at runtime, e.g. "car_gen1" for a file
 * dropped into res/drawable as car_gen1.png. Returns null when no such drawable exists yet,
 * so callers can fall back to procedural art until the real sprite is added.
 */
@Composable
fun rememberOptionalSprite(name: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(name) {
        if (name.isBlank()) return@remember null
        val id = context.resources.getIdentifier(name, "drawable", context.packageName)
        if (id == 0) return@remember null
        runCatching { BitmapFactory.decodeResource(context.resources, id).asImageBitmap() }.getOrNull()
    }
}
