package com.khcompany.lanedash.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.khcompany.lanedash.ads.AdManager
import com.khcompany.lanedash.audio.SoundManager
import com.khcompany.lanedash.game.CAR_X_UNITS
import com.khcompany.lanedash.game.CarOption
import com.khcompany.lanedash.game.CityTheme
import com.khcompany.lanedash.game.EntityKind
import com.khcompany.lanedash.game.GameEngine
import com.khcompany.lanedash.game.GameOverReason
import com.khcompany.lanedash.game.GameStatus
import com.khcompany.lanedash.game.LANE_COUNT
import com.khcompany.lanedash.game.LeaderboardRepository
import com.khcompany.lanedash.game.ObstacleVariant
import com.khcompany.lanedash.game.WORLD_WIDTH_UNITS
import com.khcompany.lanedash.game.laneSizeScale
import com.khcompany.lanedash.ui.components.drawCitySkyline
import com.khcompany.lanedash.ui.components.drawCitySkylineSprite
import com.khcompany.lanedash.ui.components.drawFuelCanItem
import com.khcompany.lanedash.ui.components.drawObstacle
import com.khcompany.lanedash.ui.components.drawRetroCar
import com.khcompany.lanedash.ui.components.drawRoad
import com.khcompany.lanedash.ui.components.perspectiveY
import com.khcompany.lanedash.ui.components.rememberOptionalSprite
import kotlin.math.roundToInt

private const val SKY_FRACTION = 0.4f
private const val MAX_FUEL_DISPLAY = 100f

/** Extra size bump applied to every obstacle/rival-vehicle sprite (1.5x, then 2x, then another 1.5x). */
private const val OBSTACLE_SCALE = 4.5f

@Composable
fun GameScreen(
    city: CityTheme,
    car: CarOption,
    highScore: Int,
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
    val engine = remember { GameEngine(onSfx = { soundManager.play(it) }) }
    val carSprite = rememberOptionalSprite(car.spriteName)
    val citySprite = rememberOptionalSprite(city.spriteName)
    val obstacleSprites = ObstacleVariant.entries.associateWith { rememberOptionalSprite(it.spriteName) }

    DisposableEffect(Unit) {
        onDispose { soundManager.release() }
    }

    LaunchedEffect(Unit) {
        // MediaPlayer.create() decodes synchronously — keep it off the UI thread.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            soundManager.startBgm()
        }
        engine.start()
    }

    LaunchedEffect(engine.status) {
        if (engine.status == GameStatus.RUNNING) {
            var lastFrameNanos = -1L
            while (engine.status == GameStatus.RUNNING) {
                androidx.compose.runtime.withFrameNanos { frameNanos ->
                    if (lastFrameNanos >= 0L) {
                        val dt = (frameNanos - lastFrameNanos) / 1_000_000_000f
                        engine.tick(dt)
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

    val animatedLane by animateFloatAsState(
        targetValue = engine.laneIndex.toFloat(),
        animationSpec = tween(160),
        label = "lane",
    )

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

            // The player's own car is drawn larger (3x baseline — the original 2x bumped another
            // 1.5x, then scaled 0.7x ahead of release) but bottom-anchored, so only its
            // base/wheels need to sit inside the current lane — the rest is free to overlap the
            // lane above.
            val playerCarHeight = laneCarHeight(animatedLane) * 3f * 0.7f
            val playerCarWidth = laneCarWidth(animatedLane) * 3f * 0.7f
            val playerCarY = laneBottomY(animatedLane) - playerCarHeight / 2f

            // Oversized sprites (OBSTACLE_SCALE) spill into neighboring lanes, so draw order
            // has to follow lane depth or a far-lane sprite can wrongly paint over a near-lane
            // one. Lane 0 is the top (farthest) lane and LANE_COUNT-1 is the bottom (nearest)
            // one, so sorting by lane ascending and painting in that order puts nearer lanes on
            // top — a simple painter's algorithm. The player car sorts in by its own
            // (possibly mid-tween) lane too.
            val drawables = mutableListOf<Pair<Float, () -> Unit>>()
            drawables.add(
                animatedLane to {
                    drawRetroCar(
                        car = car,
                        center = Offset(CAR_X_UNITS * pxPerUnit, playerCarY),
                        width = playerCarWidth,
                        height = playerCarHeight,
                        sprite = carSprite,
                    )
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
                    val obstacleWidth = entityCarWidth * 0.85f * OBSTACLE_SCALE * entity.obstacleVariant.hitWidthScale
                    val obstacleHeight = entityCarHeight * 0.95f * OBSTACLE_SCALE * entity.obstacleVariant.heightScale
                    val ey = laneBottomY(entityLane) - obstacleHeight / 2f
                    drawables.add(
                        entityLane to {
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
                    drawables.add(entityLane to { drawFuelCanItem(center = Offset(ex, ey), radius = radius) })
                }
            }

            for ((_, draw) in drawables.sortedBy { it.first }) draw()
        }

        // HUD: speed (top-left), fuel gauge (top-center), score + best (top-right).
        val speedKmh = (engine.speed * 20f).roundToInt()
        Text(
            text = "$speedKmh KM/H",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(20.dp),
        )

        FuelGauge(
            fuelFraction = engine.fuel / MAX_FUEL_DISPLAY,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 20.dp),
        )

        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(20.dp),
        ) {
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

        // Lane controls, in addition to swipe.
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LaneButton(label = "▲", onClick = { engine.moveLaneUp() })
            LaneButton(label = "▼", onClick = { engine.moveLaneDown() })
        }

        if (engine.status == GameStatus.GAME_OVER) {
            GameOverOverlay(
                score = engine.score,
                highScore = highScore,
                reason = engine.gameOverReason,
                leaderboardRepository = leaderboardRepository,
                soundManager = soundManager,
                canContinue = !engine.usedContinue && adManager.isRewardedReady,
                onContinue = {
                    activity?.let { a ->
                        adManager.showRewarded(a, onReward = { engine.continueAfterAd() }, onUnavailable = {})
                    }
                },
                onRetry = { engine.start() },
                onSubmitScore = onSubmitScore,
                onViewLeaderboard = onViewLeaderboard,
                onExit = onExit,
            )
        }
    }
}

@Composable
private fun FuelGauge(fuelFraction: Float, modifier: Modifier = Modifier) {
    val fraction = fuelFraction.coerceIn(0f, 1f)
    val barColor = when {
        fraction > 0.5f -> Color(0xFF4CAF50)
        fraction > 0.2f -> Color(0xFFFFC93C)
        else -> Color(0xFFE63946)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(
            text = "FUEL",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
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
private fun LaneButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        ),
        modifier = Modifier.size(56.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun GameOverOverlay(
    score: Int,
    highScore: Int,
    reason: GameOverReason?,
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
    var submitted by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    LaunchedEffect(score) {
        qualifies = try {
            leaderboardRepository.qualifiesForTop(score)
        } catch (e: Exception) {
            android.util.Log.e("Leaderboard", "qualifiesForTop failed", e)
            false
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
                        Text(text = "📺 광고 보고 계속하기", fontWeight = FontWeight.Bold)
                    }
                }
                if (qualifies == true && !submitted) {
                    Text(
                        text = "🏆 TOP 10 진입!",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
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
                Button(
                    onClick = onViewLeaderboard,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.background),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Text(text = "순위표 보기", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onRetry, modifier = Modifier.weight(1f)) {
                        Text(text = "RETRY", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onExit,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.background),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = "HOME", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                    }
                }
            }
        }
    }
}
