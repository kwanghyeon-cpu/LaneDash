package com.khcompany.lanedash.ui.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.khcompany.lanedash.game.highway.HW_D0
import com.khcompany.lanedash.game.highway.HW_DRAW_DISTANCE
import com.khcompany.lanedash.game.highway.RoadStrips
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

// Speed-feel effects for the behind-the-car mode. Everything is cheap on purpose: a few dozen
// rectangles in batched paths and a handful of lines per frame, driven only by engine state.

/** Reused every frame, like the road paths: one batched path per color. */
internal class RoadsideBatch {
    val posts = Path()
    val lamps = Path()
    val bushes = Path()
    fun reset() {
        posts.reset(); lamps.reset(); bushes.reset()
    }
}

private const val ROADSIDE_SPACING = 5f

/** Lateral position (road half-widths) of lamp posts and bushes, just outside the rumble strip. */
private const val LAMP_LATERAL = 1.34f
private const val BUSH_LATERAL = 1.62f

/**
 * Lamp posts on one side and bushes on the other, alternating, scrolling past at the road's own
 * pace. Nothing tells you how fast you are going like objects whizzing by at the edge.
 * Call before the road haze so far ones fade into it.
 */
internal fun DrawScope.drawRoadside(
    batch: RoadsideBatch,
    strips: RoadStrips,
    cameraZ: Float,
    horizonY: Float,
    rowHeight: Float,
    postColor: Color,
    lampColor: Color,
    bushColor: Color,
) {
    batch.reset()
    val roadH = size.height - horizonY
    val firstIndex = floor(cameraZ / ROADSIDE_SPACING).toInt() + 1
    val slots = (HW_DRAW_DISTANCE / ROADSIDE_SPACING).toInt() + 1
    for (n in slots downTo 0) { // far to near
        val index = firstIndex + n
        val dz = index * ROADSIDE_SPACING - cameraZ
        if (dz < HW_D0 || dz > HW_DRAW_DISTANCE) continue
        val y = horizonY + roadH * (HW_D0 / dz)
        val i = strips.indexForY(y, size.height, rowHeight)
        val hw = strips.halfWidth[i]
        val cx = strips.centerX[i]
        val lampSide = if (index % 2 == 0) -1f else 1f

        val postW = maxOf(1f, hw * 0.035f)
        val postH = hw * 1.5f
        val lampX = cx + lampSide * LAMP_LATERAL * hw
        batch.posts.addRect(Rect(lampX - postW / 2f, y - postH, lampX + postW / 2f, y))
        // The arm reaches over the road, with the light at its tip.
        val armLen = hw * 0.26f
        val armEndX = lampX - lampSide * armLen
        batch.posts.addRect(Rect(minOf(lampX, armEndX), y - postH, maxOf(lampX, armEndX), y - postH + postW))
        val lampW = hw * 0.11f
        batch.lamps.addRect(Rect(armEndX - lampW / 2f, y - postH + postW, armEndX + lampW / 2f, y - postH + postW * 2.2f))

        val bushR = hw * 0.16f
        val bushX = cx - lampSide * BUSH_LATERAL * hw
        batch.bushes.addOval(Rect(bushX - bushR * 1.3f, y - bushR * 1.5f, bushX + bushR * 1.3f, y))
    }
    drawPath(batch.bushes, bushColor)
    drawPath(batch.posts, postColor)
    drawPath(batch.lamps, lampColor)
}

private const val SPEED_LINE_COUNT = 14

/**
 * Streaks that rush outward from near the vanishing point, only once you are well past cruising
 * speed ([speedRatio] 0..1 of the top speed), so the top end feels different from the start.
 */
internal fun DrawScope.drawSpeedLines(speedRatio: Float, elapsedSeconds: Float, focalY: Float) {
    val strength = ((speedRatio - 0.6f) / 0.4f).coerceIn(0f, 1f)
    if (strength <= 0.02f) return
    val cx = size.width / 2f
    val maxR = hypot(cx, size.height - focalY)
    val stroke = maxOf(2f, size.width * 0.0035f)
    for (i in 0 until SPEED_LINE_COUNT) {
        val phase = (elapsedSeconds * 1.9f + i * 0.371f) % 1f
        // Spread over the left and right sides, fanning out a little above and below horizontal.
        val side = if (i % 2 == 0) 1f else -1f
        val spread = (((i * 53) % 100) / 100f - 0.5f) * 1.7f // ~ -49..49 degrees in radians
        val dirX = side * cos(spread)
        val dirY = sin(spread)
        val r0 = maxR * (0.28f + 0.62f * phase)
        val r1 = r0 + maxR * (0.04f + 0.14f * phase)
        drawLine(
            color = Color.White.copy(alpha = strength * 0.42f * phase),
            start = Offset(cx + dirX * r0, focalY + dirY * r0),
            end = Offset(cx + dirX * r1, focalY + dirY * r1),
            strokeWidth = stroke * (0.6f + phase),
        )
    }
}
