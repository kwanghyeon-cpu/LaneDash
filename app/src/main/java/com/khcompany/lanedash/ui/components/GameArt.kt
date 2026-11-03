package com.khcompany.lanedash.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.khcompany.lanedash.game.CityTheme
import kotlin.math.ceil
import kotlin.math.max

/**
 * Draws a tileable skyline image, scaled to exactly fill the sky height and repeated
 * across the screen width using its own aspect ratio for the tile width. Scrolls with
 * [parallaxUnits] the same way the procedural skyline does.
 */
fun DrawScope.drawCitySkylineSprite(sprite: ImageBitmap, horizonY: Float, parallaxUnits: Float, pxPerUnit: Float) {
    if (sprite.height <= 0 || horizonY <= 0f) return
    val tileWidthPx = horizonY * (sprite.width.toFloat() / sprite.height.toFloat())
    if (tileWidthPx <= 0f) return
    val scrollPx = (parallaxUnits * pxPerUnit).mod(tileWidthPx)
    val tilesNeeded = ceil(size.width / tileWidthPx).toInt() + 2
    val dstSize = IntSize(ceil(tileWidthPx).toInt().coerceAtLeast(1), ceil(horizonY).toInt().coerceAtLeast(1))
    for (t in -1..tilesNeeded) {
        val tileBaseX = t * tileWidthPx - scrollPx
        drawImage(image = sprite, dstOffset = IntOffset(tileBaseX.toInt(), 0), dstSize = dstSize)
    }
}

// Pickups are Phosphor glyphs (GameIcons.kt) on a dark badge with a colored ring — flat, legible
// at small sizes, and told apart at a glance by ring color. Mirrors drawItemBadge in render.js.
val FUEL_ITEM_COLOR = Color(0xFFFFA23C)
val SHIELD_ITEM_COLOR = Color(0xFF4DA3FF)
val MAGNET_ITEM_COLOR = Color(0xFFE63946)

private fun DrawScope.drawItemBadge(icon: GameIcon, center: Offset, radius: Float, color: Color) {
    drawCircle(color = Color(0xFF1B1035).copy(alpha = 0.9f), radius = radius, center = center)
    drawCircle(color = color, radius = radius * 0.93f, center = center, style = Stroke(width = radius * 0.14f))
    drawGameIcon(icon, center, radius * 1.15f, color)
}

/** Fuel refill pickup. */
fun DrawScope.drawFuelCanItem(center: Offset, radius: Float) = drawItemBadge(GameIcon.FUEL_CAN, center, radius, FUEL_ITEM_COLOR)

/** Timed-invincibility pickup. */
fun DrawScope.drawShieldItem(center: Offset, radius: Float) = drawItemBadge(GameIcon.SHIELD, center, radius, SHIELD_ITEM_COLOR)

/** Timed fuel-magnet pickup. */
fun DrawScope.drawMagnetItem(center: Offset, radius: Float) = drawItemBadge(GameIcon.MAGNET, center, radius, MAGNET_ITEM_COLOR)

/** Translucent bubble drawn around the player while the shield is active. */
fun DrawScope.drawShieldBubble(center: Offset, radiusX: Float, radiusY: Float, alpha: Float) {
    val topLeft = Offset(center.x - radiusX, center.y - radiusY)
    val size = Size(radiusX * 2f, radiusY * 2f)
    drawOval(color = Color(0xFF6FD6FF).copy(alpha = 0.2f * alpha), topLeft = topLeft, size = size)
    drawOval(
        color = Color(0xFFBDEBFF).copy(alpha = 0.9f * alpha),
        topLeft = topLeft,
        size = size,
        style = Stroke(width = 3f),
    )
}

/** Dashed ring around the player while the fuel magnet is active; [phase] animates it. */
fun DrawScope.drawMagnetAura(center: Offset, radiusX: Float, radiusY: Float, phase: Float) {
    val topLeft = Offset(center.x - radiusX, center.y - radiusY)
    val size = Size(radiusX * 2f, radiusY * 2f)
    drawOval(
        color = Color(0xFFFF5A6E).copy(alpha = 0.85f),
        topLeft = topLeft,
        size = size,
        style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), phase)),
    )
}

/** Soft elliptical shadow on the asphalt — anchors a vehicle/pickup to the road. */
fun DrawScope.drawGroundShadow(cx: Float, bottomY: Float, width: Float, height: Float, alpha: Float = 0.38f) {
    val rx = width * 0.5f
    val ry = max(2f, height * 0.075f)
    drawOval(
        color = Color.Black.copy(alpha = alpha),
        topLeft = Offset(cx - rx, bottomY - height * 0.05f - ry),
        size = Size(rx * 2f, ry * 2f),
    )
}

// Gradient brushes never change within a run, so they're built once and reused (re-made only
// when the city or canvas size changes) instead of allocating new shaders every frame.
private var hazeKey = ""
private var hazeFarBrush: Brush? = null
private var hazeNearBrush: Brush? = null

/** Far-end haze (the road melts into the skyline) and a darker near edge (vignette). */
fun DrawScope.drawRoadHaze(city: CityTheme, horizonY: Float) {
    val roadHeight = size.height - horizonY
    val key = "${city.id}|${size.width.toInt()}|${horizonY.toInt()}|${roadHeight.toInt()}"
    val far = roadHeight * 0.42f
    val nearTop = horizonY + roadHeight * 0.72f
    if (hazeKey != key) {
        hazeFarBrush = Brush.verticalGradient(
            colors = listOf(city.haze.copy(alpha = 0.55f), city.haze.copy(alpha = 0f)),
            startY = horizonY,
            endY = horizonY + far,
        )
        hazeNearBrush = Brush.verticalGradient(
            colors = listOf(Color.Black.copy(alpha = 0f), Color.Black.copy(alpha = 0.38f)),
            startY = nearTop,
            endY = horizonY + roadHeight,
        )
        hazeKey = key
    }
    drawRect(brush = hazeFarBrush!!, topLeft = Offset(0f, horizonY), size = Size(size.width, far))
    drawRect(brush = hazeNearBrush!!, topLeft = Offset(0f, nearTop), size = Size(size.width, horizonY + roadHeight - nearTop))
}
