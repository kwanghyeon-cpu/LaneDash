package com.khcompany.lanedash.game.highway

import kotlin.math.floor
import kotlin.math.sin

// Behind-the-car "OutRun" road. The screen is split at a horizon; every screen row below it
// shows a slice of road at a distance dz ahead of the camera, found from the row's fraction
// t (0 at the horizon, 1 at the bottom edge) as dz = HW_D0 / t. That one relation gives the
// road's width (proportional to t), how big a car at that distance is drawn, and which
// stripe of the repeating road texture the row belongs to. Lateral positions are in
// "road half-widths" (-1 = left edge, +1 = right edge, 0 = center).

const val HW_LANE_COUNT = 5

/** Width of one lane in road half-widths (the whole road is 2). */
const val HW_LANE_SPACING = 2f / HW_LANE_COUNT

/** How far ahead (world units) the road is drawn and traffic is spawned. */
const val HW_DRAW_DISTANCE = 60f

/** Depth, in world units, shown at the bottom screen row. */
const val HW_D0 = 1f

/** Where the player's car sits, as a fraction of the road height measured from the horizon. */
const val HW_PLAYER_T = 0.80f

/** Distance ahead of the camera at which the player's car is — traffic here is a collision. */
const val HW_PLAYER_DZ = HW_D0 / HW_PLAYER_T

/** The horizon's height as a fraction of the screen height. */
const val HW_HORIZON_FRACTION = 0.36f

/** Lateral centers of the traffic lanes (half-widths), left to right. */
val HW_LANE_CENTERS = FloatArray(HW_LANE_COUNT) { -1f + HW_LANE_SPACING * (it + 0.5f) }

/**
 * Road curvature at world distance [z]: smooth S-bends with straight stretches (the envelope
 * dips toward zero now and then). Positive bends the road to the right.
 */
fun curveAt(z: Float): Float {
    val a = sin(z / 55f)
    val b = sin(z / 23f + 1.3f)
    val envelope = sin(z / 140f) * 0.5f + 0.5f
    return (0.011f * a + 0.005f * b) * (0.25f + 0.75f * envelope)
}

/**
 * The visible road as horizontal strips from the bottom edge up to the horizon, recomputed
 * every frame into preallocated arrays (no per-frame allocation).
 */
class RoadStrips(val capacity: Int = 200) {
    var count = 0
        private set
    val yTop = FloatArray(capacity)
    val yBottom = FloatArray(capacity)

    /** Screen x of the road's center line in this strip (curve and camera offset applied). */
    val centerX = FloatArray(capacity)

    /** Screen pixels per road half-width in this strip. */
    val halfWidth = FloatArray(capacity)
    val dz = FloatArray(capacity)

    /** 0 or 1: which of the two alternating stripe shades this strip uses. */
    val band = IntArray(capacity)

    fun compute(
        cameraZ: Float,
        cameraX: Float,
        screenWidth: Float,
        horizonY: Float,
        bottomY: Float,
        rowHeight: Float,
        stripeLength: Float,
    ) {
        val roadHeight = bottomY - horizonY
        val hw0 = screenWidth * 0.5f
        var n = 0
        var prevDz = 0f
        var dxAcc = 0f
        var xOff = 0f
        var y = bottomY
        while (n < capacity && y - rowHeight > horizonY + 1f) {
            val t = ((y - rowHeight / 2f) - horizonY) / roadHeight
            val d = HW_D0 / t
            val step = d - prevDz
            // Double integral of the curvature: the road's lateral drift grows with distance.
            dxAcc += curveAt(cameraZ + d) * step
            xOff += dxAcc * step
            prevDz = d

            yBottom[n] = y
            yTop[n] = y - rowHeight
            halfWidth[n] = hw0 * t
            centerX[n] = screenWidth / 2f + (xOff - cameraX) * halfWidth[n]
            dz[n] = d
            band[n] = floor((cameraZ + d) / stripeLength).toInt() and 1
            n++
            y -= rowHeight
        }
        count = n
    }

    /** Index of the strip containing screen row [y], clamped to the valid range. */
    fun indexForY(y: Float, bottomY: Float, rowHeight: Float): Int =
        ((bottomY - y) / rowHeight).toInt().coerceIn(0, (count - 1).coerceAtLeast(0))
}
