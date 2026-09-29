package com.khcompany.lanedash.ui.components

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.khcompany.lanedash.game.CarBodyStyle
import com.khcompany.lanedash.game.CarOption
import com.khcompany.lanedash.game.CityTheme
import com.khcompany.lanedash.game.LANE_COUNT
import com.khcompany.lanedash.game.ObstacleVariant
import com.khcompany.lanedash.game.PERSPECTIVE_MIN_SCALE
import com.khcompany.lanedash.game.PERSPECTIVE_TOTAL
import com.khcompany.lanedash.game.perspectiveCumulative
import kotlin.math.ceil

/** One repeating skyline tile, hand-tuned for a pleasant irregular silhouette. */
private data class BuildingSpec(
    val offsetUnits: Float,
    val widthUnits: Float,
    val heightFraction: Float,
    val colorIndex: Int,
    val isLandmark: Boolean = false,
)

private const val TILE_WIDTH_UNITS = 16f

private val BUILDING_LAYOUT = listOf(
    BuildingSpec(0.5f, 1.6f, 0.35f, 0),
    BuildingSpec(2.2f, 1.1f, 0.55f, 1),
    BuildingSpec(3.4f, 1.4f, 0.42f, 2),
    BuildingSpec(5.0f, 0.9f, 0.7f, 1, isLandmark = true),
    BuildingSpec(6.1f, 1.3f, 0.5f, 0),
    BuildingSpec(7.6f, 1.7f, 0.32f, 2),
    BuildingSpec(9.5f, 1.0f, 0.6f, 1),
    BuildingSpec(10.7f, 1.5f, 0.4f, 0),
    BuildingSpec(12.4f, 1.2f, 0.48f, 2),
    BuildingSpec(13.8f, 1.6f, 0.36f, 1),
)

fun DrawScope.drawCitySkyline(city: CityTheme, worldWidthUnits: Float, horizonY: Float, parallaxUnits: Float) {
    val pxPerUnit = size.width / worldWidthUnits
    val tileWidthPx = TILE_WIDTH_UNITS * pxPerUnit
    val scrollPx = (parallaxUnits * pxPerUnit).mod(tileWidthPx)
    val tilesNeeded = ceil(size.width / tileWidthPx).toInt() + 2

    for (t in -1..tilesNeeded) {
        val tileBaseX = t * tileWidthPx - scrollPx
        for (spec in BUILDING_LAYOUT) {
            val bx = tileBaseX + spec.offsetUnits * pxPerUnit
            val bw = spec.widthUnits * pxPerUnit
            val bh = horizonY * spec.heightFraction
            if (bx + bw < 0f || bx > size.width) continue

            val color = if (spec.isLandmark) city.landmarkColor else city.buildingColors[spec.colorIndex % city.buildingColors.size]
            val top = horizonY - bh
            drawRect(color = color, topLeft = Offset(bx, top), size = Size(bw, bh))

            if (spec.isLandmark) {
                // Antenna/spire so the landmark reads distinctly from ordinary buildings.
                val spireX = bx + bw / 2f
                drawLine(
                    color = city.landmarkColor,
                    start = Offset(spireX, top),
                    end = Offset(spireX, top - bh * 0.22f),
                    strokeWidth = (bw * 0.06f).coerceAtLeast(2f),
                )
            }

            // Window grid for a bit more retro detail than a flat silhouette.
            val cols = (bw / (pxPerUnit * 0.35f)).toInt().coerceIn(1, 4)
            val rows = (bh / (pxPerUnit * 0.35f)).toInt().coerceIn(1, 6)
            val winW = bw / (cols * 2f)
            val winH = bh / (rows * 2.2f)
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    if ((r + c + spec.colorIndex) % 3 == 0) continue
                    val wx = bx + bw * (c + 0.5f) / cols - winW / 2f
                    val wy = top + bh * (r + 0.5f) / rows - winH / 2f
                    drawRect(color = city.windowColor.copy(alpha = 0.85f), topLeft = Offset(wx, wy), size = Size(winW, winH))
                }
            }
        }
    }
}

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

/** Pixel Y for perspective position p (0 = horizon, LANE_COUNT = bottom of road). */
fun perspectiveY(p: Float, horizonY: Float, roadHeight: Float): Float =
    horizonY + roadHeight * perspectiveCumulative(p) / PERSPECTIVE_TOTAL

/** Local (instantaneous, not lane-averaged) perspective scale at position p. */
fun localScaleAt(p: Float): Float = PERSPECTIVE_MIN_SCALE + (1f - PERSPECTIVE_MIN_SCALE) * (p / LANE_COUNT)

fun DrawScope.drawRoad(
    city: CityTheme,
    laneCount: Int,
    horizonY: Float,
    worldWidthUnits: Float,
    distanceUnits: Float,
) {
    val roadHeight = size.height - horizonY
    drawRect(color = city.roadColor, topLeft = Offset(0f, horizonY), size = Size(size.width, roadHeight))

    val pxPerUnit = size.width / worldWidthUnits
    val baseDash = pxPerUnit * 0.9f
    val baseGap = pxPerUnit * 0.6f

    // Each divider line's own dash size/width/spacing shrinks toward the horizon (its own
    // localScaleAt), instead of all laneCount-1 lines sharing one uniform dash pattern — so
    // the road markings themselves read as converging into the distance.
    for (i in 1 until laneCount) {
        val y = perspectiveY(i.toFloat(), horizonY, roadHeight)
        val scale = localScaleAt(i.toFloat())
        val dash = baseDash * scale
        val gap = baseGap * scale
        val phase = (distanceUnits * pxPerUnit) % (dash + gap)
        drawLine(
            color = city.roadLineColor.copy(alpha = 0.7f),
            start = Offset(size.width, y),
            end = Offset(0f, y),
            strokeWidth = (roadHeight * 0.015f * scale).coerceAtLeast(1f),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, gap), -phase),
        )
    }
}

private val WINDOW_COLOR = Color(0xFF9FE3FF)
private val HEADLIGHT_COLOR = Color(0xFFFFF3B0)
private val HUBCAP_COLOR = Color(0xFFB8B8B8)
private val TIRE_SIDEWALL_COLOR = Color(0xFF3A3A3A)

/** Blends a color toward white by [factor] (0..1) — a cheap "glossy paint highlight" tint. */
private fun Color.lighten(factor: Float): Color = Color(
    red = red + (1f - red) * factor,
    green = green + (1f - green) * factor,
    blue = blue + (1f - blue) * factor,
    alpha = alpha,
)

/** A vertical paint gradient (bright highlight near the roofline, true color lower down). */
private fun paintBrush(bodyColor: Color, top: Float, bottom: Float): Brush = Brush.verticalGradient(
    colors = listOf(bodyColor.lighten(0.35f), bodyColor, bodyColor),
    startY = top,
    endY = bottom,
)

/** Soft contact shadow so the car reads as sitting on the road, not floating on it. */
private fun DrawScope.drawGroundShadow(left: Float, top: Float, width: Float, height: Float) {
    drawOval(
        color = Color.Black.copy(alpha = 0.28f),
        topLeft = Offset(left + width * 0.04f, top + height * 0.88f),
        size = Size(width * 0.92f, height * 0.14f),
    )
}

private fun DrawScope.drawDoorHandle(x: Float, y: Float, length: Float, thickness: Float, color: Color) {
    drawLine(color, Offset(x, y), Offset(x + length, y), strokeWidth = thickness)
}

private fun DrawScope.drawSideMirror(x: Float, y: Float, size: Float, color: Color) {
    drawRoundRect(
        color = color,
        topLeft = Offset(x, y),
        size = Size(size * 1.4f, size),
        cornerRadius = CornerRadius(size * 0.4f, size * 0.4f),
    )
}

/** A fender-arch line hugging the top of a wheel, for a bit of body-panel volume. */
private fun DrawScope.drawFenderArch(cx: Float, wheelY: Float, wheelRadius: Float, color: Color) {
    val archRadius = wheelRadius * 1.3f
    drawArc(
        color = color,
        startAngle = 195f,
        sweepAngle = 150f,
        useCenter = false,
        topLeft = Offset(cx - archRadius, wheelY - archRadius),
        size = Size(archRadius * 2f, archRadius * 2f),
        style = Stroke(width = wheelRadius * 0.12f),
    )
}

/** A short curved stroke bridging the windshield pillar down into the mirror, like a real A-pillar/mirror seam. */
private fun DrawScope.drawPillarMirrorSeam(pillarX: Float, pillarTopY: Float, mirrorX: Float, mirrorY: Float, color: Color, strokeWidth: Float) {
    val path = Path().apply {
        moveTo(pillarX, pillarTopY)
        quadraticTo(pillarX + (mirrorX - pillarX) * 0.3f, mirrorY - (mirrorY - pillarTopY) * 0.15f, mirrorX, mirrorY)
    }
    drawPath(path, color = color, style = Stroke(width = strokeWidth))
}

/** A small two-tone rear combination lamp (red body colour + a bright amber/clear segment) at the tail. */
private fun DrawScope.drawRearCombinationLamp(x: Float, y: Float, lampWidth: Float, lampHeight: Float) {
    drawRoundRect(
        color = Color(0xFFE63946),
        topLeft = Offset(x, y),
        size = Size(lampWidth, lampHeight),
        cornerRadius = CornerRadius(lampHeight * 0.25f, lampHeight * 0.25f),
    )
    drawRoundRect(
        color = Color(0xFFFFC93C),
        topLeft = Offset(x + lampWidth * 0.14f, y + lampHeight * 0.58f),
        size = Size(lampWidth * 0.72f, lampHeight * 0.3f),
        cornerRadius = CornerRadius(lampHeight * 0.12f, lampHeight * 0.12f),
    )
}

private fun DrawScope.drawWheelPair(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    wheelColor: Color,
    radiusScale: Float = 0.16f,
    xInset: Float = 0.22f,
    spoked: Boolean = false,
) {
    val wheelRadius = height * radiusScale
    val y = top + height * 0.92f
    for (fx in floatArrayOf(xInset, 1f - xInset)) {
        val cx = left + width * fx
        // Tire, then a thin sidewall ring, then the rim/hubcap on top.
        drawCircle(color = wheelColor, radius = wheelRadius, center = Offset(cx, y))
        drawCircle(
            color = TIRE_SIDEWALL_COLOR,
            radius = wheelRadius * 0.66f,
            center = Offset(cx, y),
            style = Stroke(width = wheelRadius * 0.1f),
        )
        drawCircle(color = HUBCAP_COLOR, radius = wheelRadius * 0.42f, center = Offset(cx, y))
        if (spoked) {
            drawLine(wheelColor, Offset(cx - wheelRadius * 0.42f, y), Offset(cx + wheelRadius * 0.42f, y), strokeWidth = wheelRadius * 0.18f)
            drawLine(wheelColor, Offset(cx, y - wheelRadius * 0.42f), Offset(cx, y + wheelRadius * 0.42f), strokeWidth = wheelRadius * 0.18f)
        } else {
            drawCircle(color = wheelColor, radius = wheelRadius * 0.1f, center = Offset(cx, y))
        }
    }
}

/**
 * Draws a sprite bitmap scaled to fit the given box while preserving its own aspect ratio
 * (never stretched/distorted). Fit is width-first and the sprite's bottom edge is always
 * anchored to the box's bottom edge — never vertically centered — so every vehicle (player
 * or obstacle, vector or sprite) sits on the same "road contact line" and wheels line up
 * across different sprite aspect ratios instead of floating at inconsistent heights.
 * The sprite must already face right (the direction of travel) with a transparent background.
 */
fun DrawScope.drawCarSprite(sprite: ImageBitmap, center: Offset, width: Float, height: Float) {
    val srcAspect = sprite.width.toFloat() / sprite.height.toFloat()
    var drawWidth = width
    var drawHeight = width / srcAspect
    if (drawHeight > height) {
        drawHeight = height
        drawWidth = height * srcAspect
    }
    val boxBottom = center.y + height / 2f
    val left = center.x - drawWidth / 2f
    val top = boxBottom - drawHeight
    drawImage(
        image = sprite,
        dstOffset = IntOffset(left.toInt(), top.toInt()),
        dstSize = IntSize(drawWidth.toInt().coerceAtLeast(1), drawHeight.toInt().coerceAtLeast(1)),
    )
}

/**
 * Side-profile (silhouette) car, facing right — the direction of travel.
 * The road/lane layout reads top-down, but the car itself is drawn from the side.
 * Draws the car's PNG sprite if one is available (see [CarOption.spriteName]); otherwise
 * dispatches to an era-styled procedural silhouette — see [CarBodyStyle].
 */
fun DrawScope.drawRetroCar(car: CarOption, center: Offset, width: Float, height: Float, sprite: ImageBitmap? = null) {
    if (sprite != null) {
        drawCarSprite(sprite, center, width, height)
        return
    }
    when (car.bodyStyle) {
        CarBodyStyle.BOXY_90S -> drawBoxy90sCar(car, center, width, height)
        CarBodyStyle.ROUNDED_2000S -> drawRounded2000sCar(car, center, width, height)
        CarBodyStyle.SMOOTH_2010S -> drawSmooth2010sCar(car, center, width, height)
        CarBodyStyle.SHARP_MODERN -> drawSharpModernCar(car, center, width, height)
        CarBodyStyle.FACETED_CURRENT -> drawFacetedCurrentCar(car, center, width, height)
    }
}

/** Gen 1 — upright, boxy off-roader: tall glasshouse, roof rails, rear-mounted spare. */
private fun DrawScope.drawBoxy90sCar(car: CarOption, center: Offset, width: Float, height: Float) {
    val left = center.x - width / 2f
    val top = center.y - height / 2f
    val bodyHeight = height * 0.56f
    val bodyTop = top + height * 0.36f

    drawGroundShadow(left, top, width, height)
    drawWheelPair(left, top, width, height, car.wheelColor, radiusScale = 0.185f, xInset = 0.2f)

    drawRoundRect(
        brush = paintBrush(car.bodyColor, bodyTop, bodyTop + bodyHeight),
        topLeft = Offset(left, bodyTop),
        size = Size(width, bodyHeight),
        cornerRadius = CornerRadius(height * 0.05f, height * 0.05f),
    )
    drawDoorHandle(left + width * 0.58f, bodyTop + bodyHeight * 0.32f, width * 0.09f, height * 0.02f, car.roofColor)
    drawLine(
        color = car.bodyShade,
        start = Offset(left + width * 0.58f, bodyTop + bodyHeight * 0.32f),
        end = Offset(left + width * 0.98f, bodyTop + bodyHeight * 0.28f),
        strokeWidth = height * 0.012f,
    )
    drawRoundRect(
        color = car.bodyShade,
        topLeft = Offset(left, bodyTop + bodyHeight * 0.66f),
        size = Size(width, bodyHeight * 0.34f),
        cornerRadius = CornerRadius(height * 0.04f, height * 0.04f),
    )
    val wheelY1 = top + height * 0.92f
    val wheelRadius1 = height * 0.185f
    drawFenderArch(left + width * 0.2f, wheelY1, wheelRadius1, car.bodyShade)
    drawFenderArch(left + width * 0.8f, wheelY1, wheelRadius1, car.bodyShade)

    // Rear-mounted spare tire, the signature 90s off-roader detail.
    val spareRadius = height * 0.15f
    val spareCenter = Offset(left + width * 0.05f, bodyTop + bodyHeight * 0.55f)
    drawCircle(color = car.wheelColor, radius = spareRadius, center = spareCenter)
    drawCircle(color = HUBCAP_COLOR.copy(alpha = 0.6f), radius = spareRadius * 0.55f, center = spareCenter)
    drawRearCombinationLamp(left + width * 0.01f, bodyTop + bodyHeight * 0.06f, width * 0.05f, bodyHeight * 0.22f)

    // Upright cabin with a center pillar splitting two window panes.
    val cabinWidth = width * 0.56f
    val cabinHeight = height * 0.42f
    val cabinLeft = center.x - cabinWidth / 2f
    drawRoundRect(
        color = car.roofColor,
        topLeft = Offset(cabinLeft, top),
        size = Size(cabinWidth, cabinHeight),
        cornerRadius = CornerRadius(height * 0.04f, height * 0.04f),
    )
    drawRoundRect(
        color = WINDOW_COLOR.copy(alpha = 0.9f),
        topLeft = Offset(cabinLeft + cabinWidth * 0.08f, top + cabinHeight * 0.2f),
        size = Size(cabinWidth * 0.84f, cabinHeight * 0.55f),
        cornerRadius = CornerRadius(height * 0.02f, height * 0.02f),
    )
    drawLine(
        color = car.roofColor,
        start = Offset(center.x, top + cabinHeight * 0.2f),
        end = Offset(center.x, top + cabinHeight * 0.75f),
        strokeWidth = width * 0.03f,
    )
    drawSideMirror(cabinLeft + cabinWidth * 0.86f, top + cabinHeight * 0.68f, height * 0.06f, car.roofColor)
    drawPillarMirrorSeam(
        pillarX = cabinLeft + cabinWidth * 0.92f,
        pillarTopY = top,
        mirrorX = cabinLeft + cabinWidth * 0.9f,
        mirrorY = top + cabinHeight * 0.68f,
        color = car.roofColor,
        strokeWidth = height * 0.014f,
    )
    // Roof rails.
    val railY1 = top + cabinHeight * 0.06f
    val railY2 = top + cabinHeight * 0.14f
    drawLine(car.roofColor, Offset(cabinLeft + cabinWidth * 0.1f, railY1), Offset(cabinLeft + cabinWidth * 0.9f, railY1), strokeWidth = height * 0.02f)
    drawLine(car.roofColor, Offset(cabinLeft + cabinWidth * 0.1f, railY2), Offset(cabinLeft + cabinWidth * 0.9f, railY2), strokeWidth = height * 0.015f)

    // Round headlight, bumper.
    drawCircle(color = HEADLIGHT_COLOR, radius = bodyHeight * 0.14f, center = Offset(left + width * 0.9f, bodyTop + bodyHeight * 0.32f))
    drawRect(
        color = Color(0xFF2B2B2B),
        topLeft = Offset(left, bodyTop + bodyHeight * 0.86f),
        size = Size(width, bodyHeight * 0.14f),
    )
}

/** Gen 2 — softer, rounded early crossover with a single unified glasshouse. */
private fun DrawScope.drawRounded2000sCar(car: CarOption, center: Offset, width: Float, height: Float) {
    val left = center.x - width / 2f
    val top = center.y - height / 2f
    val bodyHeight = height * 0.56f
    val bodyTop = top + height * 0.34f

    drawGroundShadow(left, top, width, height)
    drawWheelPair(left, top, width, height, car.wheelColor, radiusScale = 0.155f, xInset = 0.23f)

    drawRoundRect(
        brush = paintBrush(car.bodyColor, bodyTop, bodyTop + bodyHeight),
        topLeft = Offset(left, bodyTop),
        size = Size(width, bodyHeight),
        cornerRadius = CornerRadius(height * 0.26f, height * 0.26f),
    )
    drawDoorHandle(left + width * 0.6f, bodyTop + bodyHeight * 0.34f, width * 0.08f, height * 0.018f, car.roofColor)
    drawLine(
        color = car.bodyShade,
        start = Offset(left + width * 0.6f, bodyTop + bodyHeight * 0.34f),
        end = Offset(left + width * 0.97f, bodyTop + bodyHeight * 0.3f),
        strokeWidth = height * 0.01f,
    )
    drawRoundRect(
        color = car.bodyShade,
        topLeft = Offset(left, bodyTop + bodyHeight * 0.64f),
        size = Size(width, bodyHeight * 0.36f),
        cornerRadius = CornerRadius(height * 0.14f, height * 0.14f),
    )
    drawRearCombinationLamp(left + width * 0.02f, bodyTop + bodyHeight * 0.22f, width * 0.055f, bodyHeight * 0.24f)
    run {
        val wheelY = top + height * 0.92f
        val wheelRadius = height * 0.155f
        drawFenderArch(left + width * 0.23f, wheelY, wheelRadius, car.bodyShade)
        drawFenderArch(left + width * 0.77f, wheelY, wheelRadius, car.bodyShade)
    }

    val cabinWidth = width * 0.5f
    val cabinHeight = height * 0.36f
    val cabinTop = top + height * 0.02f
    val cabinLeft = center.x - cabinWidth / 2f
    drawRoundRect(
        color = car.roofColor,
        topLeft = Offset(cabinLeft, cabinTop),
        size = Size(cabinWidth, cabinHeight),
        cornerRadius = CornerRadius(height * 0.2f, height * 0.2f),
    )
    drawRoundRect(
        color = WINDOW_COLOR.copy(alpha = 0.9f),
        topLeft = Offset(cabinLeft + cabinWidth * 0.14f, cabinTop + cabinHeight * 0.22f),
        size = Size(cabinWidth * 0.72f, cabinHeight * 0.56f),
        cornerRadius = CornerRadius(height * 0.1f, height * 0.1f),
    )
    drawSideMirror(cabinLeft + cabinWidth * 0.82f, cabinTop + cabinHeight * 0.62f, height * 0.055f, car.roofColor)
    drawPillarMirrorSeam(
        pillarX = cabinLeft + cabinWidth * 0.88f,
        pillarTopY = cabinTop,
        mirrorX = cabinLeft + cabinWidth * 0.86f,
        mirrorY = cabinTop + cabinHeight * 0.62f,
        color = car.roofColor,
        strokeWidth = height * 0.012f,
    )

    drawRoundRect(
        color = HEADLIGHT_COLOR,
        topLeft = Offset(left + width * 0.85f, bodyTop + bodyHeight * 0.2f),
        size = Size(width * 0.12f, bodyHeight * 0.24f),
        cornerRadius = CornerRadius(height * 0.05f, height * 0.05f),
    )
}

/** Gen 3 — smooth 2010s crossover with a raked D-pillar and a body-side character line. */
private fun DrawScope.drawSmooth2010sCar(car: CarOption, center: Offset, width: Float, height: Float) {
    val left = center.x - width / 2f
    val top = center.y - height / 2f
    val bodyHeight = height * 0.54f
    val bodyTop = top + height * 0.36f

    drawGroundShadow(left, top, width, height)
    drawWheelPair(left, top, width, height, car.wheelColor, radiusScale = 0.16f, xInset = 0.24f)

    drawRoundRect(
        brush = paintBrush(car.bodyColor, bodyTop, bodyTop + bodyHeight),
        topLeft = Offset(left, bodyTop),
        size = Size(width, bodyHeight),
        cornerRadius = CornerRadius(height * 0.3f, height * 0.3f),
    )
    drawLine(
        color = car.bodyShade,
        start = Offset(left + width * 0.08f, bodyTop + bodyHeight * 0.42f),
        end = Offset(left + width * 0.94f, bodyTop + bodyHeight * 0.34f),
        strokeWidth = height * 0.025f,
    )
    drawDoorHandle(left + width * 0.62f, bodyTop + bodyHeight * 0.3f, width * 0.08f, height * 0.018f, car.roofColor)
    drawRoundRect(
        color = car.bodyShade,
        topLeft = Offset(left, bodyTop + bodyHeight * 0.68f),
        size = Size(width, bodyHeight * 0.32f),
        cornerRadius = CornerRadius(height * 0.16f, height * 0.16f),
    )
    drawRearCombinationLamp(left + width * 0.015f, bodyTop + bodyHeight * 0.14f, width * 0.06f, bodyHeight * 0.26f)
    run {
        val wheelY = top + height * 0.92f
        val wheelRadius = height * 0.16f
        drawFenderArch(left + width * 0.24f, wheelY, wheelRadius, car.bodyShade)
        drawFenderArch(left + width * 0.76f, wheelY, wheelRadius, car.bodyShade)
    }

    // Raked greenhouse: narrower at the rear (left) than the front (right).
    val cabinTop = top + height * 0.04f
    val cabinBottom = top + height * 0.42f
    val rearX = left + width * 0.3f
    val frontX = left + width * 0.86f
    val roofPath = Path().apply {
        moveTo(left + width * 0.42f, cabinTop)
        lineTo(frontX, cabinTop + height * 0.02f)
        lineTo(frontX, cabinBottom)
        lineTo(rearX, cabinBottom)
        close()
    }
    drawPath(roofPath, color = car.roofColor)
    val windowPath = Path().apply {
        moveTo(left + width * 0.46f, cabinTop + height * 0.06f)
        lineTo(frontX - width * 0.05f, cabinTop + height * 0.07f)
        lineTo(frontX - width * 0.05f, cabinBottom - height * 0.04f)
        lineTo(rearX + width * 0.04f, cabinBottom - height * 0.04f)
        close()
    }
    drawPath(windowPath, color = WINDOW_COLOR.copy(alpha = 0.9f))
    drawSideMirror(frontX - width * 0.1f, cabinBottom - height * 0.02f, height * 0.055f, car.roofColor)
    drawPillarMirrorSeam(
        pillarX = frontX - width * 0.04f,
        pillarTopY = cabinTop + height * 0.07f,
        mirrorX = frontX - width * 0.08f,
        mirrorY = cabinBottom - height * 0.02f,
        color = car.roofColor,
        strokeWidth = height * 0.012f,
    )

    val headlightPath = Path().apply {
        moveTo(left + width * 0.86f, bodyTop + bodyHeight * 0.16f)
        lineTo(left + width * 0.98f, bodyTop + bodyHeight * 0.22f)
        lineTo(left + width * 0.98f, bodyTop + bodyHeight * 0.4f)
        lineTo(left + width * 0.86f, bodyTop + bodyHeight * 0.36f)
        close()
    }
    drawPath(headlightPath, color = HEADLIGHT_COLOR)
}

/** Gen 4 — sharper stance: kinked beltline crease, raked pillar, small roof spoiler. */
private fun DrawScope.drawSharpModernCar(car: CarOption, center: Offset, width: Float, height: Float) {
    val left = center.x - width / 2f
    val top = center.y - height / 2f
    val bodyHeight = height * 0.54f
    val bodyTop = top + height * 0.36f

    drawGroundShadow(left, top, width, height)
    drawWheelPair(left, top, width, height, car.wheelColor, radiusScale = 0.17f, xInset = 0.22f, spoked = true)

    drawRoundRect(
        brush = paintBrush(car.bodyColor, bodyTop, bodyTop + bodyHeight),
        topLeft = Offset(left, bodyTop),
        size = Size(width, bodyHeight),
        cornerRadius = CornerRadius(height * 0.15f, height * 0.15f),
    )
    drawDoorHandle(left + width * 0.6f, bodyTop + bodyHeight * 0.28f, width * 0.08f, height * 0.02f, car.roofColor)
    // Sharp beltline crease.
    val creasePath = Path().apply {
        moveTo(left + width * 0.06f, bodyTop + bodyHeight * 0.5f)
        lineTo(left + width * 0.55f, bodyTop + bodyHeight * 0.4f)
        lineTo(left + width * 0.95f, bodyTop + bodyHeight * 0.3f)
    }
    drawPath(creasePath, color = car.bodyShade, style = Stroke(width = height * 0.03f))
    drawRoundRect(
        color = car.bodyShade,
        topLeft = Offset(left, bodyTop + bodyHeight * 0.7f),
        size = Size(width, bodyHeight * 0.3f),
        cornerRadius = CornerRadius(height * 0.1f, height * 0.1f),
    )
    drawRearCombinationLamp(left + width * 0.01f, bodyTop + bodyHeight * 0.12f, width * 0.06f, bodyHeight * 0.26f)
    run {
        val wheelY = top + height * 0.92f
        val wheelRadius = height * 0.17f
        drawFenderArch(left + width * 0.22f, wheelY, wheelRadius, car.bodyShade)
        drawFenderArch(left + width * 0.78f, wheelY, wheelRadius, car.bodyShade)
    }

    // Raked cabin with a small rear spoiler.
    val cabinTop = top + height * 0.06f
    val cabinBottom = top + height * 0.4f
    val rearX = left + width * 0.32f
    val frontX = left + width * 0.84f
    val roofPath = Path().apply {
        moveTo(left + width * 0.46f, cabinTop)
        lineTo(frontX, cabinTop + height * 0.03f)
        lineTo(frontX, cabinBottom)
        lineTo(rearX, cabinBottom)
        close()
    }
    drawPath(roofPath, color = car.roofColor)
    val windowPath = Path().apply {
        moveTo(left + width * 0.5f, cabinTop + height * 0.055f)
        lineTo(frontX - width * 0.04f, cabinTop + height * 0.075f)
        lineTo(frontX - width * 0.04f, cabinBottom - height * 0.035f)
        lineTo(rearX + width * 0.04f, cabinBottom - height * 0.035f)
        close()
    }
    drawPath(windowPath, color = WINDOW_COLOR.copy(alpha = 0.9f))
    drawSideMirror(frontX - width * 0.1f, cabinBottom - height * 0.015f, height * 0.06f, car.roofColor)
    drawPillarMirrorSeam(
        pillarX = frontX - width * 0.03f,
        pillarTopY = cabinTop + height * 0.08f,
        mirrorX = frontX - width * 0.07f,
        mirrorY = cabinBottom - height * 0.015f,
        color = car.roofColor,
        strokeWidth = height * 0.012f,
    )
    drawRect(
        color = car.roofColor,
        topLeft = Offset(rearX - width * 0.02f, cabinBottom - height * 0.05f),
        size = Size(width * 0.05f, height * 0.06f),
    )

    // Wedge-shaped headlight.
    val headlightPath = Path().apply {
        moveTo(left + width * 0.84f, bodyTop + bodyHeight * 0.14f)
        lineTo(left + width * 0.98f, bodyTop + bodyHeight * 0.24f)
        lineTo(left + width * 0.94f, bodyTop + bodyHeight * 0.4f)
        lineTo(left + width * 0.82f, bodyTop + bodyHeight * 0.32f)
        close()
    }
    drawPath(headlightPath, color = HEADLIGHT_COLOR)
}

/** Gen 5 — faceted current-day SUV: chiseled surfacing, floating-roof two-tone, DRL bar. */
private fun DrawScope.drawFacetedCurrentCar(car: CarOption, center: Offset, width: Float, height: Float) {
    val left = center.x - width / 2f
    val top = center.y - height / 2f
    val bodyHeight = height * 0.55f
    val bodyTop = top + height * 0.35f

    drawGroundShadow(left, top, width, height)
    drawWheelPair(left, top, width, height, car.wheelColor, radiusScale = 0.18f, xInset = 0.21f, spoked = true)

    val bodyPath = Path().apply {
        moveTo(left + width * 0.02f, bodyTop + bodyHeight * 0.55f)
        lineTo(left + width * 0.08f, bodyTop + bodyHeight * 0.18f)
        lineTo(left + width * 0.4f, bodyTop)
        lineTo(left + width * 0.92f, bodyTop)
        lineTo(left + width * 0.99f, bodyTop + bodyHeight * 0.3f)
        lineTo(left + width * 0.98f, bodyTop + bodyHeight)
        lineTo(left + width * 0.02f, bodyTop + bodyHeight)
        close()
    }
    drawPath(bodyPath, brush = paintBrush(car.bodyColor, bodyTop, bodyTop + bodyHeight))
    drawDoorHandle(left + width * 0.58f, bodyTop + bodyHeight * 0.5f, width * 0.08f, height * 0.02f, car.bodyShade)
    drawPath(
        Path().apply {
            moveTo(left + width * 0.08f, bodyTop + bodyHeight * 0.55f)
            lineTo(left + width * 0.94f, bodyTop + bodyHeight * 0.4f)
            lineTo(left + width * 0.94f, bodyTop + bodyHeight * 0.78f)
            lineTo(left + width * 0.08f, bodyTop + bodyHeight * 0.85f)
            close()
        },
        color = car.bodyShade,
    )
    drawRearCombinationLamp(left + width * 0.02f, bodyTop + bodyHeight * 0.24f, width * 0.055f, bodyHeight * 0.26f)
    run {
        val wheelY = top + height * 0.92f
        val wheelRadius = height * 0.18f
        drawFenderArch(left + width * 0.21f, wheelY, wheelRadius, car.bodyShade)
        drawFenderArch(left + width * 0.79f, wheelY, wheelRadius, car.bodyShade)
    }

    // Floating-roof effect: a thin body-color reveal beneath a near-black roof.
    val cabinTop = top + height * 0.05f
    val cabinBottom = top + height * 0.4f
    val rearX = left + width * 0.34f
    val frontX = left + width * 0.82f
    drawRect(color = car.bodyColor, topLeft = Offset(rearX, cabinBottom - height * 0.02f), size = Size(frontX - rearX, height * 0.045f))
    val roofPath = Path().apply {
        moveTo(left + width * 0.48f, cabinTop)
        lineTo(frontX, cabinTop + height * 0.02f)
        lineTo(frontX, cabinBottom - height * 0.02f)
        lineTo(rearX, cabinBottom - height * 0.02f)
        close()
    }
    drawPath(roofPath, color = car.roofColor)
    val windowPath = Path().apply {
        moveTo(left + width * 0.52f, cabinTop + height * 0.05f)
        lineTo(frontX - width * 0.035f, cabinTop + height * 0.06f)
        lineTo(frontX - width * 0.035f, cabinBottom - height * 0.06f)
        lineTo(rearX + width * 0.035f, cabinBottom - height * 0.06f)
        close()
    }
    drawPath(windowPath, color = WINDOW_COLOR.copy(alpha = 0.9f))
    drawSideMirror(frontX - width * 0.09f, cabinBottom - height * 0.01f, height * 0.055f, car.roofColor)
    drawPillarMirrorSeam(
        pillarX = frontX - width * 0.02f,
        pillarTopY = cabinTop + height * 0.06f,
        mirrorX = frontX - width * 0.06f,
        mirrorY = cabinBottom - height * 0.01f,
        color = car.roofColor,
        strokeWidth = height * 0.012f,
    )

    // Slim DRL bar plus a small primary headlight beneath it.
    drawRect(
        color = HEADLIGHT_COLOR,
        topLeft = Offset(left + width * 0.82f, bodyTop + bodyHeight * 0.12f),
        size = Size(width * 0.14f, bodyHeight * 0.07f),
    )
    drawRoundRect(
        color = HEADLIGHT_COLOR.copy(alpha = 0.85f),
        topLeft = Offset(left + width * 0.87f, bodyTop + bodyHeight * 0.24f),
        size = Size(width * 0.09f, bodyHeight * 0.14f),
        cornerRadius = CornerRadius(height * 0.02f, height * 0.02f),
    )
}

fun DrawScope.drawObstacle(variant: ObstacleVariant, center: Offset, width: Float, height: Float, sprite: ImageBitmap? = null) {
    if (sprite != null) {
        drawCarSprite(sprite, center, width, height)
        return
    }
    when (variant) {
        ObstacleVariant.CONE -> {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(center.x, center.y - height / 2f)
                lineTo(center.x + width * 0.4f, center.y + height / 2f)
                lineTo(center.x - width * 0.4f, center.y + height / 2f)
                close()
            }
            drawPath(path, color = Color(0xFFFF7A1A))
            drawRect(
                color = Color(0xFFF4F1E8),
                topLeft = Offset(center.x - width * 0.28f, center.y + height * 0.08f),
                size = Size(width * 0.56f, height * 0.14f),
            )
            drawRect(
                color = Color(0xFF3A2A1A),
                topLeft = Offset(center.x - width * 0.46f, center.y + height * 0.42f),
                size = Size(width * 0.92f, height * 0.12f),
            )
        }

        ObstacleVariant.SEDAN -> drawRetroCar(car = SEDAN_PALETTE, center = center, width = width, height = height)

        ObstacleVariant.SUV -> drawRetroCar(car = SUV_PALETTE, center = center, width = width, height = height)

        ObstacleVariant.BUS -> drawBus(center, width, height)

        ObstacleVariant.TRUCK -> drawTruck(center, width, height)

        ObstacleVariant.BARRIER -> {
            val stripeCount = 4
            val stripeWidth = width / stripeCount
            for (i in 0 until stripeCount) {
                drawRect(
                    color = if (i % 2 == 0) Color(0xFFE63946) else Color(0xFFF4F1E8),
                    topLeft = Offset(center.x - width / 2f + stripeWidth * i, center.y - height * 0.22f),
                    size = Size(stripeWidth, height * 0.44f),
                )
            }
            drawRect(
                color = Color(0xFF2B2B2B),
                topLeft = Offset(center.x - width * 0.06f, center.y - height / 2f),
                size = Size(width * 0.12f, height),
            )
        }
    }
}

/** Fuel refill pickup, drawn as a small retro jerry can (matches what the item does). Mirrors drawFuelCanItem in the web build's render.js. */
fun DrawScope.drawFuelCanItem(center: Offset, radius: Float) {
    val w = radius * 1.7f
    val h = radius * 2.0f
    val left = center.x - w / 2f
    val top = center.y - h / 2f + radius * 0.12f
    val r = radius * 0.22f

    val bodyPath = Path().apply {
        addRoundRect(RoundRect(left = left, top = top, right = left + w, bottom = top + h, cornerRadius = CornerRadius(r, r)))
    }

    drawArc(
        color = Color(0xFF2A1B0E),
        startAngle = 198f,
        sweepAngle = 144f,
        useCenter = false,
        topLeft = Offset(center.x - radius * 0.4f, top - radius * 0.4f),
        size = Size(radius * 0.8f, radius * 0.8f),
        style = Stroke(width = (radius * 0.18f).coerceAtLeast(1.5f), cap = StrokeCap.Round),
    )

    val capW = w * 0.24f
    val capH = radius * 0.3f
    drawRect(
        color = Color(0xFFFFC93C),
        topLeft = Offset(center.x - capW / 2f, top - capH * 0.55f),
        size = Size(capW, capH),
    )

    drawPath(bodyPath, color = Color(0xFFE63946))

    clipPath(bodyPath) {
        drawRect(
            color = Color.Black.copy(alpha = 0.2f),
            topLeft = Offset(left, top + h * 0.66f),
            size = Size(w, h * 0.34f),
        )
        val gripW = w * 0.52f
        val gripH = h * 0.15f
        drawRect(
            color = Color.Black.copy(alpha = 0.28f),
            topLeft = Offset(center.x - gripW / 2f, top + h * 0.3f),
            size = Size(gripW, gripH),
        )
        drawRect(
            color = Color.White.copy(alpha = 0.3f),
            topLeft = Offset(left + w * 0.12f, top + h * 0.1f),
            size = Size(w * 0.14f, h * 0.28f),
        )
        val dropX = center.x
        val dropY = top + h * 0.56f
        val dropR = radius * 0.15f
        val dropPath = Path().apply {
            moveTo(dropX, dropY - dropR * 1.4f)
            quadraticTo(dropX + dropR * 1.15f, dropY + dropR * 0.4f, dropX, dropY + dropR * 1.15f)
            quadraticTo(dropX - dropR * 1.15f, dropY + dropR * 0.4f, dropX, dropY - dropR * 1.4f)
            close()
        }
        drawPath(dropPath, color = Color.White.copy(alpha = 0.85f))
    }
}

private val SEDAN_PALETTE = CarOption(
    id = "traffic_sedan",
    displayName = "",
    englishName = "",
    bodyStyle = CarBodyStyle.SMOOTH_2010S,
    bodyColor = Color(0xFF8D99AE),
    bodyShade = Color(0xFF5C6478),
    roofColor = Color(0xFF2B2D3A),
)

private val SUV_PALETTE = CarOption(
    id = "traffic_suv",
    displayName = "",
    englishName = "",
    bodyStyle = CarBodyStyle.BOXY_90S,
    bodyColor = Color(0xFF8A7F6B),
    bodyShade = Color(0xFF5E564A),
    roofColor = Color(0xFF272420),
)

private val BUS_BODY_COLOR = Color(0xFFE0A020)
private val BUS_SHADE_COLOR = Color(0xFFB07E14)
private val TRUCK_CAB_COLOR = Color(0xFF6B7B8C)
private val TRUCK_CAB_SHADE = Color(0xFF48545F)
private val TRUCK_BOX_COLOR = Color(0xFFEDE6D6)
private val TRUCK_BOX_SHADE = Color(0xFFC9BFA8)

/** Long-bodied city bus: a row of side windows, a wide windshield, and two wheel pairs. */
private fun DrawScope.drawBus(center: Offset, width: Float, height: Float) {
    val left = center.x - width / 2f
    val top = center.y - height / 2f
    val busHeight = height * 0.86f

    val wheelRadius = height * 0.15f
    val wheelY = top + height * 0.92f
    for (fx in floatArrayOf(0.14f, 0.86f)) {
        val cx = left + width * fx
        drawCircle(color = Color(0xFF1A1A1A), radius = wheelRadius, center = Offset(cx, wheelY))
        drawCircle(color = HUBCAP_COLOR, radius = wheelRadius * 0.4f, center = Offset(cx, wheelY))
    }

    drawRoundRect(
        color = BUS_BODY_COLOR,
        topLeft = Offset(left, top),
        size = Size(width, busHeight),
        cornerRadius = CornerRadius(height * 0.08f, height * 0.08f),
    )
    drawRect(
        color = BUS_SHADE_COLOR,
        topLeft = Offset(left, top + busHeight * 0.72f),
        size = Size(width, busHeight * 0.2f),
    )

    // Windshield at the front (right).
    drawRoundRect(
        color = WINDOW_COLOR.copy(alpha = 0.9f),
        topLeft = Offset(left + width * 0.86f, top + busHeight * 0.14f),
        size = Size(width * 0.1f, busHeight * 0.5f),
        cornerRadius = CornerRadius(height * 0.02f, height * 0.02f),
    )
    // Row of side windows.
    val windowCount = 4
    val windowWidth = width * 0.12f
    val span = width * 0.78f
    val gap = (span - windowWidth * windowCount) / (windowCount - 1).coerceAtLeast(1)
    for (i in 0 until windowCount) {
        val wx = left + width * 0.05f + i * (windowWidth + gap)
        drawRoundRect(
            color = WINDOW_COLOR.copy(alpha = 0.85f),
            topLeft = Offset(wx, top + busHeight * 0.16f),
            size = Size(windowWidth, busHeight * 0.36f),
            cornerRadius = CornerRadius(height * 0.02f, height * 0.02f),
        )
    }
    drawRect(
        color = HEADLIGHT_COLOR,
        topLeft = Offset(left + width * 0.95f, top + busHeight * 0.58f),
        size = Size(width * 0.035f, busHeight * 0.1f),
    )
}

/** Cab-over box truck: a taller cargo box trailing a shorter windowed cab. */
private fun DrawScope.drawTruck(center: Offset, width: Float, height: Float) {
    val left = center.x - width / 2f
    val top = center.y - height / 2f

    val wheelRadius = height * 0.16f
    val wheelY = top + height * 0.92f
    for (fx in floatArrayOf(0.2f, 0.82f)) {
        val cx = left + width * fx
        drawCircle(color = Color(0xFF1A1A1A), radius = wheelRadius, center = Offset(cx, wheelY))
        drawCircle(color = HUBCAP_COLOR, radius = wheelRadius * 0.4f, center = Offset(cx, wheelY))
    }

    // Cargo box (rear).
    val boxWidth = width * 0.6f
    val boxHeight = height * 0.8f
    drawRoundRect(
        color = TRUCK_BOX_COLOR,
        topLeft = Offset(left, top),
        size = Size(boxWidth, boxHeight),
        cornerRadius = CornerRadius(height * 0.04f, height * 0.04f),
    )
    drawRect(
        color = TRUCK_BOX_SHADE,
        topLeft = Offset(left, top + boxHeight * 0.62f),
        size = Size(boxWidth, boxHeight * 0.2f),
    )

    // Cab (front).
    val cabLeft = left + boxWidth * 0.96f
    val cabWidth = width - boxWidth * 0.96f
    val cabTop = top + height * 0.28f
    val cabHeight = height * 0.52f
    drawRoundRect(
        color = TRUCK_CAB_COLOR,
        topLeft = Offset(cabLeft, cabTop),
        size = Size(cabWidth, cabHeight),
        cornerRadius = CornerRadius(height * 0.08f, height * 0.08f),
    )
    drawRoundRect(
        color = TRUCK_CAB_SHADE,
        topLeft = Offset(cabLeft, cabTop + cabHeight * 0.64f),
        size = Size(cabWidth, cabHeight * 0.36f),
        cornerRadius = CornerRadius(height * 0.04f, height * 0.04f),
    )
    drawRoundRect(
        color = WINDOW_COLOR.copy(alpha = 0.9f),
        topLeft = Offset(cabLeft + cabWidth * 0.16f, cabTop + cabHeight * 0.1f),
        size = Size(cabWidth * 0.68f, cabHeight * 0.42f),
        cornerRadius = CornerRadius(height * 0.03f, height * 0.03f),
    )
    drawRect(
        color = HEADLIGHT_COLOR,
        topLeft = Offset(left + width * 0.96f, top + height * 0.5f),
        size = Size(width * 0.03f, height * 0.1f),
    )
}
