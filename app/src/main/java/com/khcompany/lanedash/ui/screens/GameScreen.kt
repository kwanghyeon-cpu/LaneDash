package com.khcompany.lanedash.ui.screens

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.khcompany.lanedash.ads.AdManager
import com.khcompany.lanedash.audio.SoundManager
import com.khcompany.lanedash.game.CAR_X_UNITS
import com.khcompany.lanedash.game.CarOption
import com.khcompany.lanedash.game.CityTheme
import com.khcompany.lanedash.game.EntityKind
import com.khcompany.lanedash.game.GameEngine
import com.khcompany.lanedash.game.GameOverReason
import com.khcompany.lanedash.game.GameStatus
import com.khcompany.lanedash.game.ItemType
import com.khcompany.lanedash.game.LANE_COUNT
import com.khcompany.lanedash.game.LeaderboardRepository
import com.khcompany.lanedash.game.ObstacleVariant
import com.khcompany.lanedash.game.VEHICLE_WIDTH_PER_METER
import com.khcompany.lanedash.game.WORLD_WIDTH_UNITS
import com.khcompany.lanedash.game.laneSizeScale
import com.khcompany.lanedash.haptics.HapticsManager
import com.khcompany.lanedash.ui.components.FOG_ALPHA_PER_LANE
import com.khcompany.lanedash.ui.components.GameIcon
import com.khcompany.lanedash.ui.components.MAGNET_ITEM_COLOR
import com.khcompany.lanedash.ui.components.SHIELD_ITEM_COLOR
import com.khcompany.lanedash.ui.components.vector
import com.khcompany.lanedash.ui.components.drawCitySkyline
import com.khcompany.lanedash.ui.components.drawCitySkylineSprite
import com.khcompany.lanedash.ui.components.drawCurbs
import com.khcompany.lanedash.ui.components.drawFuelCanItem
import com.khcompany.lanedash.ui.components.drawGroundShadow
import com.khcompany.lanedash.ui.components.drawLampPosts
import com.khcompany.lanedash.ui.components.drawRoadHaze
import com.khcompany.lanedash.ui.components.drawSpeedStreaks
import com.khcompany.lanedash.ui.components.fittedHeight
import com.khcompany.lanedash.ui.components.fittedWidth
import com.khcompany.lanedash.ui.components.drawMagnetAura
import com.khcompany.lanedash.ui.components.drawMagnetItem
import com.khcompany.lanedash.ui.components.drawObstacle
import com.khcompany.lanedash.ui.components.drawRetroCar
import com.khcompany.lanedash.ui.components.drawRoad
import com.khcompany.lanedash.ui.components.drawShieldBubble
import com.khcompany.lanedash.ui.components.drawShieldItem
import com.khcompany.lanedash.ui.components.SpriteCache
import com.khcompany.lanedash.ui.components.perspectiveY
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val WEB_PLAY_URL = "https://kwanghyeon-cpu.github.io/LaneDash/web/"
private const val STORE_URL = "https://play.google.com/store/apps/details?id=com.khcompany.lanedash"

/**
 * Safety net for devices we can't benchmark: if frames run slow for a stretch, the purely
 * decorative effects (haze, lamps, streaks, fog veils) switch off for the rest of the session.
 * Shadows, curbs and the car tilt are cheap enough to always keep. Mirrors fxState in render.js.
 */
private class FxQuality {
    var low = false
        private set
    private var ema = 1f / 60f

    fun onFrame(dt: Float, runSeconds: Float) {
        ema = ema * 0.95f + dt * 0.05f
        if (!low && runSeconds > 3f && ema > SLOW_FRAME_SECONDS) low = true
    }

    private companion object {
        const val SLOW_FRAME_SECONDS = 0.024f // ~42 fps
    }
}

private class LoadedSprites(
    val car: ImageBitmap?,
    val city: ImageBitmap?,
    val obstacles: Map<ObstacleVariant, ImageBitmap?>,
)

private fun shareScore(context: Context, score: Int, best: Int, cityName: String) {
    val text = "🚗💨 Lane Dash — ${cityName}에서 ${score}점! (내 최고 ${best}점)\n" +
        "웹에서 바로 플레이 → $WEB_PLAY_URL\n" +
        "Google Play → $STORE_URL"
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "점수 공유하기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private const val SKY_FRACTION = 0.4f
private const val MAX_FUEL_DISPLAY = 100f

/** Mirrors GameEngine.MAX_SPEED (private there); only used to scale the speed streaks. */
private const val MAX_SPEED_FOR_FX = 16f

/** Extra size bump applied to every obstacle/rival-vehicle sprite (1.5x, then 2x, then another 1.5x). */
private const val OBSTACLE_SCALE = 4.5f

/** How far (world units) the car body slides back at the peak of a lane change. */
private const val LANE_SLIP_UNITS = 0.25f

@Composable
fun GameScreen(
    city: CityTheme,
    car: CarOption,
    highScore: Int,
    playsToday: Int,
    leaderboardRepository: LeaderboardRepository,
    adManager: AdManager,
    onScoreFinal: (Int) -> Unit,
    onGameOverAndShouldShowInterstitial: () -> Boolean,
    onSubmitScore: (name: String) -> Unit,
    onViewLeaderboard: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val soundManager = remember { SoundManager(context) }
    val haptics = remember { HapticsManager(context) }
    val engine = remember {
        GameEngine(onSfx = {
            soundManager.play(it)
            haptics.onEvent(it)
        })
    }
    var toast by remember { mutableStateOf<String?>(null) }

    // Leaving the app (home button, phone call, ad overlay) must not let the run keep going.
    DisposableEffect(activity) {
        val lifecycle = (activity as? ComponentActivity)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) engine.pause()
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    LaunchedEffect(engine.paused) {
        if (engine.paused) soundManager.pauseBgm() else soundManager.resumeBgm()
    }

    LaunchedEffect(engine.nearMissCount) {
        if (engine.nearMissCount > 0) {
            toast = "NEAR MISS!  +30"
            kotlinx.coroutines.delay(900)
            toast = null
        }
    }
    // Sprites decode off the UI thread (see SpriteCache); the run only starts once they're
    // all in, so the player never sees procedural placeholder art flash before the real one.
    var sprites by remember { mutableStateOf<LoadedSprites?>(null) }
    val fx = remember { FxQuality() }

    DisposableEffect(Unit) {
        onDispose { soundManager.release() }
    }

    LaunchedEffect(Unit) {
        sprites = LoadedSprites(
            car = SpriteCache.load(context, car.spriteName),
            city = SpriteCache.load(context, city.spriteName),
            obstacles = ObstacleVariant.entries.associateWith { SpriteCache.load(context, it.spriteName) },
        )
        engine.start(city.traits)
        // MediaPlayer.create() decodes synchronously — keep it off the UI thread.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            soundManager.startBgm()
        }
    }

    LaunchedEffect(engine.status) {
        if (engine.status == GameStatus.RUNNING) {
            var lastFrameNanos = -1L
            while (engine.status == GameStatus.RUNNING) {
                androidx.compose.runtime.withFrameNanos { frameNanos ->
                    if (lastFrameNanos >= 0L) {
                        val dt = (frameNanos - lastFrameNanos) / 1_000_000_000f
                        engine.tick(dt)
                        if (!engine.paused) fx.onFrame(dt.coerceAtMost(0.05f), engine.elapsedSeconds)
                    }
                    lastFrameNanos = frameNanos
                }
            }
        } else if (engine.status == GameStatus.GAME_OVER) {
            onScoreFinal(engine.score)
            // Interstitials are gated to roughly every Nth game (see
            // GameSessionViewModel.onGameOverAndShouldShowInterstitial), not shown every
            // single run — frequent full-screen ads between short arcade runs get old fast.
            if (onGameOverAndShouldShowInterstitial() && activity != null) {
                adManager.showInterstitial(activity) {}
            }
        }
    }

    // The lane glide is a lightly damped spring rather than a fixed-time tween: the car overshoots
    // a touch and settles, so a lane change has weight and inertia (like the highway steering).
    val laneGlide by animateFloatAsState(
        targetValue = engine.laneIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 650f),
        label = "lane",
    )
    // The perspective maths only knows real lanes, so placement uses the clamped value; the tilt
    // and slip below use the raw one so the overshoot swings the nose back.
    val animatedLane = laneGlide.coerceIn(0f, (LANE_COUNT - 1).toFloat())

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    var dragAccum = 0f
                    detectVerticalDragGestures(
                        onDragStart = { dragAccum = 0f },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            dragAccum += dragAmount
                            when {
                                dragAccum > 50f -> {
                                    engine.moveLaneDown()
                                    dragAccum = 0f
                                }
                                dragAccum < -50f -> {
                                    engine.moveLaneUp()
                                    dragAccum = 0f
                                }
                            }
                        },
                    )
                },
        ) {
            val loaded = sprites
            if (loaded == null) {
                drawRect(color = Color.Black)
                return@Canvas
            }
            val carSprite = loaded.car
            val citySprite = loaded.city
            val obstacleSprites = loaded.obstacles
            val horizonY = size.height * SKY_FRACTION
            val roadHeight = size.height - horizonY
            val laneHeight = roadHeight / LANE_COUNT
            val pxPerUnit = size.width / WORLD_WIDTH_UNITS

            if (citySprite != null) {
                drawCitySkylineSprite(
                    sprite = citySprite,
                    horizonY = horizonY,
                    parallaxUnits = engine.distance * 0.4f,
                    pxPerUnit = pxPerUnit,
                )
            } else {
                drawRect(brush = Brush.verticalGradient(listOf(city.skyTop, city.skyBottom)))
                drawCitySkyline(
                    city = city,
                    worldWidthUnits = WORLD_WIDTH_UNITS,
                    horizonY = horizonY,
                    parallaxUnits = engine.distance * 0.4f,
                )
            }
            drawRoad(
                city = city,
                laneCount = LANE_COUNT,
                horizonY = horizonY,
                worldWidthUnits = WORLD_WIDTH_UNITS,
                distanceUnits = engine.distance,
            )
            val fancy = !fx.low
            if (fancy) drawRoadHaze(city, horizonY)
            drawCurbs(horizonY, pxPerUnit, engine.distance)
            if (fancy) {
                drawLampPosts(horizonY, pxPerUnit, engine.distance)
                drawSpeedStreaks(horizonY, pxPerUnit, engine.distance, (engine.speed / MAX_SPEED_FOR_FX).coerceIn(0f, 1f))
            }

            // Baseline (old uniform-lane-equivalent) car size — each entity scales this by its
            // own lane's perspective size (laneSizeScale, Model.kt) so farther lanes read
            // smaller, and lane boundaries themselves converge toward the horizon
            // (perspectiveY) instead of being evenly spaced. Works for the player's
            // continuously-tweened animatedLane too, since laneSizeScale/perspectiveY are
            // defined for any real lane position, not just integers.
            val baseCarHeight = laneHeight * 0.58f
            val baseCarWidth = baseCarHeight * 1.6f
            fun laneCarHeight(lane: Float) = baseCarHeight * laneSizeScale(lane)
            fun laneCarWidth(lane: Float) = laneCarHeight(lane) * 1.6f

            // Every vehicle is anchored to the bottom of its lane (with a small margin above the
            // dashed line), never centered, so wheels line up at the same "road contact line"
            // regardless of how tall a given car/obstacle is drawn. Taller vehicles simply grow
            // upward from that shared line instead of floating at inconsistent heights.
            fun laneBottomY(lane: Float): Float {
                val top = perspectiveY(lane, horizonY, roadHeight)
                val bottom = perspectiveY(lane + 1f, horizonY, roadHeight)
                return bottom - (bottom - top) * 0.06f
            }

            // Vehicle widths come from real-world lengths (CarOption/ObstacleVariant.lengthMeters)
            // on one shared scale, so the player's car and the traffic are in true proportion;
            // each sprite's own aspect ratio then sets its height. The box height only needs to
            // be generous enough not to clamp any sprite (every vehicle is wider than 1.7:1).
            // The car is bottom-anchored, so only its wheels need to sit inside the current lane.
            val playerCarWidth = laneCarWidth(animatedLane) * car.lengthMeters * VEHICLE_WIDTH_PER_METER
            val playerCarHeight = playerCarWidth / 1.7f
            val playerCarY = laneBottomY(animatedLane) - playerCarHeight / 2f
            val playerSpriteHeight = fittedHeight(carSprite, playerCarWidth, playerCarHeight)
            val playerSpriteWidth = fittedWidth(carSprite, playerCarWidth, playerCarHeight)

            // Oversized sprites (OBSTACLE_SCALE) spill into neighboring lanes, so draw order
            // has to follow lane depth or a far-lane sprite can wrongly paint over a near-lane
            // one. Lane 0 is the top (farthest) lane and LANE_COUNT-1 is the bottom (nearest)
            // one, so sorting by lane ascending and painting in that order puts nearer lanes on
            // top — a simple painter's algorithm. The player car sorts in by its own
            // (possibly mid-tween) lane too.
            val drawables = mutableListOf<Pair<Float, () -> Unit>>()
            drawables.add(
                animatedLane to {
                    // Mid-change the body slides back a little (the car's weight lagging the turn).
                    val slip = abs(engine.laneIndex - laneGlide).coerceAtMost(1f) * pxPerUnit * LANE_SLIP_UNITS
                    val carCenter = Offset(CAR_X_UNITS * pxPerUnit - slip, playerCarY)
                    val carBottom = playerCarY + playerCarHeight / 2f
                    // Center of the car's visible body (the sprite sits on the bottom of its box).
                    val bodyCenter = Offset(carCenter.x, carBottom - playerSpriteHeight / 2f)
                    drawGroundShadow(
                        cx = carCenter.x,
                        bottomY = carBottom,
                        width = playerSpriteWidth,
                        height = playerSpriteHeight,
                    )
                    // The nose leans toward the direction of a lane change, pivoting on the wheels.
                    val tilt = ((engine.laneIndex - laneGlide) * 5.2f).coerceIn(-6.9f, 6.9f)
                    withTransform({ rotate(tilt, pivot = Offset(carCenter.x, carBottom)) }) {
                        drawRetroCar(
                            car = car,
                            center = carCenter,
                            width = playerCarWidth,
                            height = playerCarHeight,
                            sprite = carSprite,
                        )
                    }
                    if (engine.magnetTimeLeft > 0f) {
                        drawMagnetAura(bodyCenter, playerSpriteWidth * 0.62f, playerSpriteHeight * 0.95f, engine.elapsedSeconds * 60f)
                    }
                    if (engine.shieldTimeLeft > 0f) {
                        // Blinks during the last 1.5s so the player sees it about to run out.
                        val ending = engine.shieldTimeLeft < 1.5f
                        val alpha = if (ending && (engine.shieldTimeLeft * 8f).toInt() % 2 == 0) 0.3f else 1f
                        drawShieldBubble(bodyCenter, playerSpriteWidth * 0.58f, playerSpriteHeight * 0.85f, alpha)
                    }
                },
            )

            val cullMargin = baseCarWidth * 8f
            for (entity in engine.entities) {
                val ex = entity.x * pxPerUnit
                if (ex < -cullMargin || ex > size.width + cullMargin) continue
                val entityLane = entity.lane.toFloat()
                val entityCarHeight = laneCarHeight(entityLane)
                val entityCarWidth = laneCarWidth(entityLane)
                if (entity.kind == EntityKind.OBSTACLE) {
                    // Scale width/height by the same per-variant factor the hitbox uses,
                    // so e.g. a bus's crash zone matches how much wider it's actually drawn.
                    val variant = entity.obstacleVariant
                    val obstacleWidth: Float
                    val obstacleHeight: Float
                    if (variant.lengthMeters > 0f) {
                        // Vehicles: true-to-scale width from real length; height follows the sprite.
                        obstacleWidth = entityCarWidth * variant.lengthMeters * VEHICLE_WIDTH_PER_METER
                        obstacleHeight = obstacleWidth / 1.7f
                    } else {
                        obstacleWidth = entityCarWidth * 0.85f * OBSTACLE_SCALE * variant.hitWidthScale
                        obstacleHeight = entityCarHeight * 0.95f * OBSTACLE_SCALE * variant.heightScale
                    }
                    val ey = laneBottomY(entityLane) - obstacleHeight / 2f
                    drawables.add(
                        entityLane to {
                            val obstacleSprite = obstacleSprites[entity.obstacleVariant]
                            drawGroundShadow(
                                cx = ex,
                                bottomY = ey + obstacleHeight / 2f,
                                width = fittedWidth(obstacleSprite, obstacleWidth, obstacleHeight),
                                height = fittedHeight(obstacleSprite, obstacleWidth, obstacleHeight),
                            )
                            drawObstacle(
                                variant = entity.obstacleVariant,
                                center = Offset(ex, ey),
                                width = obstacleWidth,
                                height = obstacleHeight,
                                sprite = obstacleSprites[entity.obstacleVariant],
                            )
                        },
                    )
                } else {
                    val ey = (perspectiveY(entityLane, horizonY, roadHeight) + perspectiveY(entityLane + 1f, horizonY, roadHeight)) / 2f
                    val radius = entityCarHeight * 0.56f
                    drawables.add(
                        entityLane to {
                            drawGroundShadow(ex, ey + radius * 1.05f, radius * 1.7f, radius * 1.2f, alpha = 0.3f)
                            when (entity.itemType) {
                                ItemType.FUEL -> drawFuelCanItem(center = Offset(ex, ey), radius = radius)
                                ItemType.SHIELD -> drawShieldItem(center = Offset(ex, ey), radius = radius)
                                ItemType.MAGNET -> drawMagnetItem(center = Offset(ex, ey), radius = radius)
                            }
                        },
                    )
                }
            }

            // Paint lane by lane, far to near, laying a thin veil of city haze over everything
            // drawn so far after each lane. The farthest lane ends up under the most veils
            // (hazier, softer), the nearest under none — atmospheric perspective for the price
            // of a few translucent rects.
            val sorted = drawables.sortedBy { it.first }
            var next = 0
            val veil = city.haze.copy(alpha = FOG_ALPHA_PER_LANE)
            for (lane in 0 until LANE_COUNT) {
                while (next < sorted.size && sorted[next].first.roundToInt() <= lane) sorted[next++].second()
                if (fancy && lane < LANE_COUNT - 1) {
                    drawRect(
                        color = veil,
                        topLeft = Offset(0f, horizonY),
                        size = Size(size.width, perspectiveY(lane + 1f, horizonY, roadHeight) - horizonY),
                    )
                }
            }
        }

        // HUD: speed (top-left), fuel gauge (top-center), score + best (top-right).
        // Each HUD piece reads its engine state inside its own composable, so a per-frame
        // value change recomposes just that small piece instead of this whole screen.
        // Corner HUD elements stay clear of display cutouts (camera notch/punch-hole) now that
        // the game draws edge-to-edge; the insets are zero on screens without a cutout.
        SpeedHud(engine, modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.displayCutout))

        toast?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp),
            )
        }

        FuelGauge(
            fuelFraction = { engine.fuel / MAX_FUEL_DISPLAY },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 20.dp),
        )

        ScoreHud(engine, highScore, modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.displayCutout))

        // Lane controls, in addition to swipe.
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LaneButton(icon = GameIcon.CARET_UP, description = "위 차선", onClick = { engine.moveLaneUp() })
            LaneButton(icon = GameIcon.CARET_DOWN, description = "아래 차선", onClick = { engine.moveLaneDown() })
        }

        if (engine.status == GameStatus.RUNNING && !engine.paused) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(24.dp),
            ) {
                LaneButton(icon = GameIcon.PAUSE, description = "일시정지", onClick = { engine.pause() })
            }
        }

        if (engine.status == GameStatus.RUNNING && engine.paused) {
            PauseOverlay(
                vibrationEnabled = haptics.enabled,
                onToggleVibration = { haptics.toggle(!haptics.enabled) },
                onResume = { engine.resume() },
                onRestart = { engine.start(city.traits) },
                onExit = onExit,
            )
        }

        if (engine.status == GameStatus.GAME_OVER) {
            GameOverOverlay(
                score = engine.score,
                highScore = highScore,
                reason = engine.gameOverReason,
                runSeconds = engine.elapsedSeconds.toInt(),
                nearMisses = engine.nearMissCount,
                fuelCans = engine.fuelCollected,
                playsToday = playsToday,
                onShare = { shareScore(context, engine.score, maxOf(highScore, engine.score), city.displayName) },
                leaderboardRepository = leaderboardRepository,
                soundManager = soundManager,
                canContinue = !engine.usedContinue && adManager.isRewardedReady,
                onContinue = {
                    activity?.let { a ->
                        adManager.showRewarded(a, onReward = { engine.continueAfterAd() }, onUnavailable = {})
                    }
                },
                onRetry = { engine.start(city.traits) },
                onSubmitScore = onSubmitScore,
                onViewLeaderboard = onViewLeaderboard,
                onExit = onExit,
            )
        }
    }
}

@Composable
private fun SpeedHud(engine: GameEngine, modifier: Modifier = Modifier) {
    val speedKmh = (engine.effectiveSpeed * 20f).roundToInt()
    Column(modifier = modifier.padding(20.dp)) {
        HudIconText(
            icon = GameIcon.GAUGE,
            text = "$speedKmh KM/H",
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleLarge,
            iconSize = 24,
        )
        if (engine.shieldTimeLeft > 0f) {
            HudIconText(
                icon = GameIcon.SHIELD,
                text = "방어막 ${ceil(engine.shieldTimeLeft).toInt()}초",
                color = SHIELD_ITEM_COLOR,
                style = MaterialTheme.typography.bodyLarge,
                iconSize = 18,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (engine.magnetTimeLeft > 0f) {
            HudIconText(
                icon = GameIcon.MAGNET,
                text = "자석 ${ceil(engine.magnetTimeLeft).toInt()}초",
                color = MAGNET_ITEM_COLOR,
                style = MaterialTheme.typography.bodyLarge,
                iconSize = 18,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ScoreHud(engine: GameEngine, highScore: Int, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.End, modifier = modifier.padding(20.dp)) {
        Text(
            text = "SCORE ${engine.score}",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "BEST $highScore",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun FuelGauge(fuelFraction: () -> Float, modifier: Modifier = Modifier) {
    val fraction = fuelFraction().coerceIn(0f, 1f)
    val barColor = when {
        fraction > 0.5f -> Color(0xFF4CAF50)
        fraction > 0.2f -> Color(0xFFFFC93C)
        else -> Color(0xFFE63946)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        HudIconText(
            icon = GameIcon.GAS_PUMP,
            text = "FUEL",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodyLarge,
            iconSize = 16,
        )
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .width(180.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(8.dp))
                    .background(barColor),
            )
        }
    }
}

@Composable
internal fun LaneButton(icon: GameIcon, description: String, onClick: () -> Unit, size: Dp = 56.dp) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        ),
        modifier = Modifier.size(size),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Icon(
            imageVector = icon.vector,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(size * 0.46f),
        )
    }
}

/** Small leading icon + bold label, used inside buttons. */
@Composable
internal fun ButtonLabel(icon: GameIcon, text: String, color: Color = Color.Unspecified) {
    val tint = if (color == Color.Unspecified) LocalContentColor.current else color
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(imageVector = icon.vector, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(text = text, fontWeight = FontWeight.Bold, color = color)
    }
}

/** HUD readout: icon + text in one color. */
@Composable
internal fun HudIconText(icon: GameIcon, text: String, color: Color, style: androidx.compose.ui.text.TextStyle, iconSize: Int, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        Icon(imageVector = icon.vector, contentDescription = null, tint = color, modifier = Modifier.size(iconSize.dp))
        Text(text = text, style = style, color = color)
    }
}

@Composable
private fun PauseOverlay(
    vibrationEnabled: Boolean,
    onToggleVibration: () -> Unit,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onExit: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth(0.7f)
                .heightIn(max = 340.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "일시정지",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Button(
                onClick = onResume,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
            ) {
                ButtonLabel(GameIcon.PLAY, "계속하기")
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onRestart,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.background),
                    modifier = Modifier.weight(1f),
                ) {
                    ButtonLabel(GameIcon.RETRY, "다시 시작", MaterialTheme.colorScheme.onBackground)
                }
                Button(
                    onClick = onExit,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.background),
                    modifier = Modifier.weight(1f),
                ) {
                    ButtonLabel(GameIcon.HOME, "HOME", MaterialTheme.colorScheme.onBackground)
                }
            }
            Button(
                onClick = onToggleVibration,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.background),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                ButtonLabel(
                    GameIcon.VIBRATE,
                    if (vibrationEnabled) "진동: 켜짐" else "진동: 꺼짐",
                    MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

@Composable
private fun GameOverOverlay(
    score: Int,
    highScore: Int,
    reason: GameOverReason?,
    runSeconds: Int,
    nearMisses: Int,
    fuelCans: Int,
    playsToday: Int,
    onShare: () -> Unit,
    leaderboardRepository: LeaderboardRepository,
    soundManager: SoundManager,
    canContinue: Boolean,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
    onSubmitScore: (name: String) -> Unit,
    onViewLeaderboard: () -> Unit,
    onExit: () -> Unit,
) {
    var qualifies by remember { mutableStateOf<Boolean?>(null) }
    var pointsToTop by remember { mutableStateOf<Int?>(null) }
    var submitted by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    LaunchedEffect(score) {
        val top = try {
            leaderboardRepository.fetchTopScores(10)
        } catch (e: Exception) {
            android.util.Log.e("Leaderboard", "fetchTopScores failed", e)
            null
        }
        // Same rule as LeaderboardRepository.qualifiesForTop, but keeping the list lets us
        // also say how far a non-qualifying run was from the TOP 10 cutoff.
        qualifies = top != null && (top.size < 10 || score > top.last().score)
        pointsToTop = if (top != null && top.size >= 10 && score <= top.last().score) {
            top.last().score - score + 1
        } else {
            null
        }
        if (qualifies == true) soundManager.playNewBest()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        // Landscape-shaped (wide, not tall) so the whole dialog fits within the game's
        // short landscape screen without needing to scroll: score summary on the left,
        // every action stacked compactly on the right (RETRY/HOME share a row).
        Row(
            modifier = Modifier
                .widthIn(max = 620.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 340.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = "GAME OVER",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    text = if (reason == GameOverReason.FUEL_EMPTY) "연료 소진" else "충돌 발생",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = "SCORE $score",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 14.dp),
                )
                Text(
                    text = if (score >= highScore) "NEW BEST!" else "BEST $highScore",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                val statStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 13.sp)
                val statColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                Text(
                    text = "생존 ${runSeconds}초 · 근접 회피 ${nearMisses}회 · 연료 ${fuelCans}개",
                    style = statStyle,
                    color = statColor,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    text = "오늘 ${playsToday}번째 판",
                    style = statStyle,
                    color = statColor,
                    modifier = Modifier.padding(top = 2.dp),
                )
                pointsToTop?.let {
                    Text(
                        text = "TOP 10까지 ${it}점!",
                        style = statStyle,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(20.dp))

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                var continueRequested by remember { mutableStateOf(false) }
                if (canContinue && !continueRequested) {
                    Button(
                        onClick = {
                            continueRequested = true
                            onContinue()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    ) {
                        ButtonLabel(GameIcon.PLAY_CIRCLE, "광고 보고 계속하기")
                    }
                }
                if (qualifies == true && !submitted) {
                    HudIconText(
                        icon = GameIcon.TROPHY,
                        text = "TOP 10 진입!",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyLarge,
                        iconSize = 20,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { if (it.length <= 12) name = it },
                        singleLine = true,
                        placeholder = { Text("이름 입력") },
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            submitted = true
                            onSubmitScore(name)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) {
                        Text(text = "등록하기", fontWeight = FontWeight.Bold)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onViewLeaderboard,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.background),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        ButtonLabel(GameIcon.RANKING, "순위표", MaterialTheme.colorScheme.onBackground)
                    }
                    Button(
                        onClick = onShare,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.background),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        ButtonLabel(GameIcon.SHARE, "공유하기", MaterialTheme.colorScheme.onBackground)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onRetry, modifier = Modifier.weight(1f)) {
                        ButtonLabel(GameIcon.RETRY, "RETRY")
                    }
                    Button(
                        onClick = onExit,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.background),
                        modifier = Modifier.weight(1f),
                    ) {
                        ButtonLabel(GameIcon.HOME, "HOME", MaterialTheme.colorScheme.onBackground)
                    }
                }
            }
        }
    }
}
