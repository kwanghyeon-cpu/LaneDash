package com.khcompany.lanedash.game.highway

import kotlin.math.floor
import kotlin.math.sin

// Behind-the-car "OutRun" road. The screen is split at a horizon; every screen row below it
// shows a slice of road at a distance dz ahead of the camera, found from the row's fraction
// t (0 at the horizon, 1 at the bottom edge) as dz = dzForT(t). That one relation gives the
// road's width (roadHalfWidth), how big a car at that distance is drawn, and which
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

// How the road is projected. A pure 1/dz perspective makes a car crawl for most of its approach and
// then cross the lower half of the screen in a few frames, and pinches the road to a point at the
// horizon. Here the depth mapping is softened (t = A / (dz + soften), so a car moves down the screen
// far more evenly) and the road keeps a real width at the horizon.
const val HW_DEPTH_SOFTEN = 4f
const val HW_DEPTH_SCALE = HW_PLAYER_T * (HW_PLAYER_DZ + HW_DEPTH_SOFTEN)

/** Screen row fraction t (0 horizon .. 1 bottom edge) of something [dz] ahead of the camera. */
fun tForDz(dz: Float): Float = HW_DEPTH_SCALE / (dz + HW_DEPTH_SOFTEN)

/** Inverse of [tForDz]: the depth shown at row fraction [t]. */
fun dzForT(t: Float): Float = HW_DEPTH_SCALE / t - HW_DEPTH_SOFTEN

/** Road half-width at the horizon, relative to the bottom edge (1 would be the old, parallel road). */
const val HW_ROAD_FAR_WIDTH = 0.30f

/** Road half-width at the bottom edge, as a fraction of the screen width (the road nearly fills it at the car). */
const val HW_ROAD_NEAR_HALF = 0.56f

/** Road half-width in pixels at row fraction [t]. */
fun roadHalfWidth(t: Float, screenWidth: Float): Float =
    screenWidth * HW_ROAD_NEAR_HALF * (HW_ROAD_FAR_WIDTH + (1f - HW_ROAD_FAR_WIDTH) * t)

/** Things this close to the draw distance fade in, so nothing pops into view. */
const val HW_FADE_DISTANCE = 14f

/** 0..1 opacity of something [dz] ahead: fades in over the last [HW_FADE_DISTANCE] before the draw distance. */
fun fadeForDz(dz: Float): Float = ((HW_DRAW_DISTANCE - dz) / HW_FADE_DISTANCE).coerceIn(0f, 1f)

/** The horizon's height as a fraction of the screen height. */
const val HW_HORIZON_FRACTION = 0.25f

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
            // The bend is worked out on the plain 1/t depth (so its shape is unchanged); the width
            // and the stripes use the softened depth.
            val dBend = HW_D0 / t
            val step = dBend - prevDz
            // Double integral of the curvature: the road's lateral drift grows with distance.
            dxAcc += curveAt(cameraZ + dBend) * step
            xOff += dxAcc * step
            prevDz = dBend
            val d = dzForT(t)

            yBottom[n] = y
            yTop[n] = y - rowHeight
            halfWidth[n] = roadHalfWidth(t, screenWidth)
            centerX[n] = screenWidth / 2f + xOff * hw0 * t - cameraX * halfWidth[n]
            dz[n] = d
            band[n] = floor((cameraZ + d) / stripeLength).toInt() and 1
            n++
            y -= rowHeight
        }
        count = n
    }

    /** Road center x at screen row [y], interpolated between the two nearest strips (no stair-stepping). */
    fun centerXAt(y: Float, bottomY: Float, rowHeight: Float): Float {
        val last = count - 1
        if (last < 0) return 0f
        val f = (bottomY - y) / rowHeight - 0.5f
        val i0 = floor(f).toInt().coerceIn(0, last)
        val i1 = minOf(last, i0 + 1)
        val k = (f - i0).coerceIn(0f, 1f)
        return centerX[i0] + (centerX[i1] - centerX[i0]) * k
    }

    /** Index of the strip containing screen row [y], clamped to the valid range. */
    fun indexForY(y: Float, bottomY: Float, rowHeight: Float): Int =
        ((bottomY - y) / rowHeight).toInt().coerceIn(0, (count - 1).coerceAtLeast(0))
}

/**
 * How far the skyline slides against the road's bend, as a fraction of how far the road's far end
 * sits from the screen center. A skyline that follows the accumulated bend just keeps drifting in
 * one direction on a long curve; this one leans the other way while the road bends (road to the
 * right: scenery a little to the left) and settles back to center on a straight.
 */
const val HW_SKYLINE_FOLLOW = 0.2f

/** Scroll, in pixels, for the skyline: positive moves it left (the road is bending right). */
fun skylineScroll(strips: RoadStrips, screenWidth: Float): Float =
    if (strips.count == 0) 0f else (strips.centerX[strips.count - 1] - screenWidth / 2f) * HW_SKYLINE_FOLLOW
