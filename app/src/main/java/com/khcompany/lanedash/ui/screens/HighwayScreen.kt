package com.khcompany.lanedash.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.khcompany.lanedash.audio.SoundManager
import com.khcompany.lanedash.game.CarOption
import com.khcompany.lanedash.game.CityTheme
import com.khcompany.lanedash.game.GameMode
import com.khcompany.lanedash.game.GameStatus
import com.khcompany.lanedash.game.LeaderboardRepository
import com.khcompany.lanedash.game.highway.HW_D0
import com.khcompany.lanedash.game.highway.HW_DRAW_DISTANCE
import com.khcompany.lanedash.game.highway.HW_HORIZON_FRACTION
import com.khcompany.lanedash.game.highway.HW_PLAYER_T
import com.khcompany.lanedash.game.highway.HighwayEngine
import com.khcompany.lanedash.game.highway.RoadStrips
import com.khcompany.lanedash.game.highway.TrafficKind
import com.khcompany.lanedash.haptics.HapticsManager
import com.khcompany.lanedash.ui.components.GameIcon
import com.khcompany.lanedash.ui.components.SpriteCache
import com.khcompany.lanedash.ui.components.drawCitySkylineSprite
import com.khcompany.lanedash.ui.components.drawGroundShadow
import com.khcompany.lanedash.ui.components.drawRoadHaze
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private const val STRIPE_LENGTH = 4f

/** Scale applied to the road layer so its vibration never exposes an empty edge. */
private const val SHAKE_ZOOM = 1.02f
private const val MAX_LEAN_DEGREES = 4f
private const val VIGNETTE_MAX_ALPHA = 0.5f
private val ROAD_GRASS = Color(0xFF2E7D32)
private val RUMBLE_RED = Color(0xFFC4343A)
private val RUMBLE_WHITE = Color(0xFFD8D8D8)

/**
 * Behind-the-car "OutRun" mode (prototype): placeholder boxes for cars until rear-view sprites
 * exist. Touch the left/right half of the screen to steer. Portrait only — LaneDashApp switches
 * the activity's orientation while this route is showing.
 */
@Composable
fun HighwayScreen(
    city: CityTheme,
    car: CarOption,
    highScore: Int,
    onScoreFinal: (Int) -> Unit,
    leaderboardRepository: LeaderboardRepository,
    onSubmitScore: (name: String) -> Unit,
    onViewLeaderboard: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val soundManager = remember { SoundManager(context) }
    val haptics = remember { HapticsManager(context) }
    val engine = remember {
        HighwayEngine(onSfx = {
            soundManager.play(it)
            haptics.onEvent(it)
        })
    }
    val strips = remember { RoadStrips() }
    var skyline by remember { mutableStateOf<ImageBitmap?>(null) }
    var playerSprite by remember { mutableStateOf<ImageBitmap?>(null) }
    var trafficSprites by remember { mutableStateOf<Map<TrafficKind, ImageBitmap?>>(emptyMap()) }
    var ready by remember { mutableStateOf(false) }

    // Reused every frame: one batched path per color instead of hundreds of separate rects.
    val darkGrass = remember { Path() }
    val roadLight = remember { Path() }
    val roadDark = remember { Path() }
    val rumbleRed = remember { Path() }
    val rumbleWhite = remember { Path() }
    val laneMarks = remember { Path() }
    val roadside = remember { RoadsideBatch() }

    DisposableEffect(Unit) {
        onDispose { soundManager.release() }
    }

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

    LaunchedEffect(Unit) {
        skyline = SpriteCache.load(context, city.spriteName)
        playerSprite = SpriteCache.load(context, car.rearSpriteName)
        trafficSprites = TrafficKind.entries.associateWith { SpriteCache.load(context, it.spriteName) }
        ready = true
        engine.start()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            soundManager.startBgm()
        }
    }

    LaunchedEffect(engine.status) {
        if (engine.status == GameStatus.RUNNING) {
            var lastFrameNanos = -1L
            while (engine.status == GameStatus.RUNNING) {
                androidx.compose.runtime.withFrameNanos { frameNanos ->
                    if (lastFrameNanos >= 0L) engine.tick((frameNanos - lastFrameNanos) / 1_000_000_000f)
                    lastFrameNanos = frameNanos
                }
            }
        } else if (engine.status == GameStatus.GAME_OVER) {
            onScoreFinal(engine.score)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // Road vibration: grows with speed, and the grass shakes the car much harder. The
                // slight zoom keeps the shifted frame from showing an empty edge.
                .graphicsLayer {
                    val speedRatio = (engine.speed / HighwayEngine.MAX_SPEED).coerceIn(0f, 1f)
                    val amplitude = size.height * (0.0009f * speedRatio * speedRatio + if (engine.offRoad) 0.0022f else 0f)
                    scaleX = SHAKE_ZOOM
                    scaleY = SHAKE_ZOOM
                    translationY = (sin(engine.z * 1.7f) * 0.6f + sin(engine.z * 4.1f) * 0.4f) * amplitude
                },
        ) {
            if (!ready) {
                drawRect(Color.Black)
                return@Canvas
            }
            val w = size.width
            val h = size.height
            val horizonY = h * HW_HORIZON_FRACTION
            val roadH = h - horizonY
            val rowH = max(3f, roadH / 110f)

            val sky = skyline
            if (sky != null) {
                drawCitySkylineSprite(sky, horizonY, engine.heading * w * 1.2f, 1f)
            } else {
                drawRect(
                    brush = Brush.verticalGradient(listOf(city.skyTop, city.skyBottom), endY = horizonY),
                    size = Size(w, horizonY),
                )
            }

            val grassLight = lerp(ROAD_GRASS, city.haze, 0.2f)
            val grassDark = lerp(Color(0xFF276B2A), city.haze, 0.2f)
            drawRect(grassLight, Offset(0f, horizonY), Size(w, roadH))

            strips.compute(engine.z, engine.playerX, w, horizonY, h, rowH, STRIPE_LENGTH)
            darkGrass.reset(); roadLight.reset(); roadDark.reset()
            rumbleRed.reset(); rumbleWhite.reset(); laneMarks.reset()
            for (i in 0 until strips.count) {
                val top = strips.yTop[i]
                val bottom = strips.yBottom[i] + 0.5f
                val cx = strips.centerX[i]
                val hw = strips.halfWidth[i]
                val odd = strips.band[i] == 1
                if (odd) darkGrass.addRect(Rect(0f, top, w, bottom))
                val rumble = hw * 0.14f
                val rumblePath = if (odd) rumbleWhite else rumbleRed
                rumblePath.addRect(Rect(cx - hw - rumble, top, cx - hw, bottom))
                rumblePath.addRect(Rect(cx + hw, top, cx + hw + rumble, bottom))
                (if (odd) roadDark else roadLight).addRect(Rect(cx - hw, top, cx + hw, bottom))
                if (!odd) {
                    val half = hw * 0.025f
                    laneMarks.addRect(Rect(cx - hw / 3f - half, top, cx - hw / 3f + half, bottom))
                    laneMarks.addRect(Rect(cx + hw / 3f - half, top, cx + hw / 3f + half, bottom))
                }
            }
            drawPath(darkGrass, grassDark)
            drawPath(rumbleRed, RUMBLE_RED)
            drawPath(rumbleWhite, RUMBLE_WHITE)
            drawPath(roadLight, lerp(city.roadColor, Color.White, 0.10f))
            drawPath(roadDark, lerp(city.roadColor, Color.White, 0.05f))
            drawPath(laneMarks, city.roadLineColor.copy(alpha = 0.9f))
            drawRoadside(
                batch = roadside,
                strips = strips,
                cameraZ = engine.z,
                horizonY = horizonY,
                rowHeight = rowH,
                postColor = lerp(Color(0xFF3A3F4B), city.haze, 0.15f),
                lampColor = Color(0xFFFFE9A8),
                bushColor = lerp(Color(0xFF1F5E27), city.haze, 0.2f),
            )
            drawRoadHaze(city, horizonY)
            drawSpeedLines(
                speedRatio = engine.speed / HighwayEngine.MAX_SPEED,
                elapsedSeconds = engine.elapsedSeconds,
                focalY = horizonY + roadH * 0.2f,
            )

            // Traffic, far to near so closer cars paint over farther ones.
            for (traffic in engine.traffic.sortedByDescending { it.z }) {
                val dz = traffic.z - engine.z
                if (dz < 1f || dz > HW_DRAW_DISTANCE) continue
                val t = HW_D0 / dz
                val y = horizonY + roadH * t
                val idx = strips.indexForY(y, h, rowH)
                val width = traffic.kind.halfWidth * 2f * strips.halfWidth[idx]
                val cx = strips.centerX[idx] + traffic.lx * strips.halfWidth[idx]
                val trafficSprite = trafficSprites[traffic.kind]
                if (trafficSprite != null) {
                    drawRearSprite(trafficSprite, cx = cx, bottomY = y, width = width)
                } else {
                    val (body, roof) = trafficColors(traffic.kind)
                    drawRearCar(cx, y, width, traffic.kind.heightFactor, body, roof)
                }
            }

            // The player stays at the screen's horizontal center; the road slides under them.
            val playerY = horizonY + roadH * HW_PLAYER_T
            val playerIdx = strips.indexForY(playerY, h, rowH)
            val playerWidth = 0.44f * strips.halfWidth[playerIdx]
            val sprite = playerSprite
            if (sprite != null) {
                drawRearSprite(
                    sprite,
                    cx = w / 2f,
                    bottomY = playerY,
                    width = playerWidth,
                    leanDegrees = engine.steerLean * MAX_LEAN_DEGREES,
                )
            } else {
                drawRearCar(
                    cx = w / 2f,
                    bottomY = playerY,
                    width = playerWidth,
                    heightFactor = 0.8f,
                    body = car.bodyColor,
                    roof = car.roofColor,
                )
            }
        }

        // Tunnel vision: the screen edges darken as the speed climbs.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val brush = Brush.radialGradient(
                        0.5f to Color.Transparent,
                        1f to Color.Black,
                        center = Offset(size.width / 2f, size.height * 0.55f),
                        radius = hypot(size.width, size.height) * 0.55f,
                    )
                    onDrawBehind {
                        val speedRatio = (engine.speed / HighwayEngine.MAX_SPEED).coerceIn(0f, 1f)
                        val alpha = ((speedRatio - 0.5f) / 0.5f).coerceIn(0f, 1f) * VIGNETTE_MAX_ALPHA
                        if (alpha > 0.01f) drawRect(brush, alpha = alpha)
                    }
                },
        )

        // Steering: hold the left or right half of the screen.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        engine.steerInput = if (down.position.x < size.width / 2f) -1 else 1
                        do {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull()
                            if (pointer != null && pointer.pressed) {
                                engine.steerInput = if (pointer.position.x < size.width / 2f) -1 else 1
                            }
                        } while (event.changes.any { it.pressed })
                        engine.steerInput = 0
                    }
                },
        )

        HighwayHud(engine, highScore, modifier = Modifier.fillMaxSize())

        if (engine.status == GameStatus.RUNNING && engine.paused) {
            HighwayDialog(title = "일시정지") {
                Button(onClick = { engine.resume() }, modifier = Modifier.fillMaxWidth()) {
                    ButtonLabel(GameIcon.PLAY, "계속하기")
                }
                Button(
                    onClick = onExit,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.background),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    ButtonLabel(GameIcon.HOME, "HOME", MaterialTheme.colorScheme.onBackground)
                }
            }
        }

        if (engine.status == GameStatus.GAME_OVER) {
            HighwayDialog(title = "GAME OVER") {
                Text(
                    text = "SCORE ${engine.score}",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (engine.score >= highScore) "NEW BEST!" else "BEST $highScore",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                )
                HighwayRankSection(
                    score = engine.score,
                    leaderboardRepository = leaderboardRepository,
                    soundManager = soundManager,
                    onSubmitScore = onSubmitScore,
                )
                Button(
                    onClick = onViewLeaderboard,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.background),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                ) {
                    ButtonLabel(GameIcon.RANKING, "순위표", MaterialTheme.colorScheme.onBackground)
                }
                Button(onClick = { engine.start() }, modifier = Modifier.fillMaxWidth()) {
                    ButtonLabel(GameIcon.RETRY, "RETRY")
                }
                Button(
                    onClick = onExit,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.background),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    ButtonLabel(GameIcon.HOME, "HOME", MaterialTheme.colorScheme.onBackground)
                }
            }
        }
    }
}

@Composable
private fun HighwayHud(engine: HighwayEngine, highScore: Int, modifier: Modifier = Modifier) {
    Box(modifier = modifier.windowInsetsPadding(WindowInsets.displayCutout)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            HudIconText(
                icon = GameIcon.GAUGE,
                text = "${(engine.speed * 5f).roundToInt()} KM/H",
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleLarge,
                iconSize = 24,
            )
            Column(horizontalAlignment = Alignment.End) {
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
        if (engine.nearMissFlash > 0f) {
            Text(
                text = "NEAR MISS +${HighwayEngine.NEAR_MISS_SCORE}",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 110.dp)
                    .alpha((engine.nearMissFlash / HighwayEngine.NEAR_MISS_FLASH_SECONDS * 1.6f).coerceIn(0f, 1f)),
            )
        }
        if (engine.status == GameStatus.RUNNING && engine.elapsedSeconds < 4f) {
            Text(
                text = "화면의 왼쪽/오른쪽을 누르고 있으면 조향해요",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp),
            )
        }
    }
}

/** Checks the run against the highway TOP 10 and, if it makes it, asks for a name to submit. */
@Composable
private fun HighwayRankSection(
    score: Int,
    leaderboardRepository: LeaderboardRepository,
    soundManager: SoundManager,
    onSubmitScore: (name: String) -> Unit,
) {
    var qualifies by remember(score) { mutableStateOf<Boolean?>(null) }
    var pointsToTop by remember(score) { mutableStateOf<Int?>(null) }
    var submitted by remember(score) { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    LaunchedEffect(score) {
        val top = try {
            leaderboardRepository.fetchTopScores(10, GameMode.HIGHWAY)
        } catch (e: Exception) {
            android.util.Log.e("Leaderboard", "fetchTopScores failed", e)
            null
        }
        qualifies = top != null && score > 0 && (top.size < 10 || score > top.last().score)
        pointsToTop = if (top != null && top.size >= 10 && score <= top.last().score) {
            top.last().score - score + 1
        } else {
            null
        }
        if (qualifies == true) soundManager.playNewBest()
    }

    pointsToTop?.let {
        Text(
            text = "TOP 10까지 ${it}점!",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(bottom = 10.dp),
        )
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
                .padding(top = 8.dp, bottom = 8.dp),
        ) {
            Text(text = "등록하기", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HighwayDialog(title: String, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.84f)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            content()
        }
    }
}

private fun trafficColors(kind: TrafficKind): Pair<Color, Color> = when (kind) {
    TrafficKind.SEDAN -> Color(0xFF8D99AE) to Color(0xFF2B2D3A)
    TrafficKind.SUV -> Color(0xFF5C6478) to Color(0xFF2B2D3A)
    TrafficKind.BUS -> Color(0xFFA7AFBF) to Color(0xFF2B2D3A)
    TrafficKind.TRUCK -> Color(0xFF7F8CA8) to Color(0xFF3B4256)
}

/** Draws a rear-view sprite bottom-anchored at [bottomY]; the height follows the image's own aspect. */
private fun DrawScope.drawRearSprite(
    sprite: ImageBitmap,
    cx: Float,
    bottomY: Float,
    width: Float,
    leanDegrees: Float = 0f,
) {
    val height = width * sprite.height / sprite.width
    drawGroundShadow(cx, bottomY, width, height)
    // Leaning pivots on the tires' contact line so the car rolls into the turn, not off its shadow.
    rotate(leanDegrees, pivot = Offset(cx, bottomY)) {
        drawImage(
            image = sprite,
            srcSize = IntSize(sprite.width, sprite.height),
            dstOffset = IntOffset((cx - width / 2f).roundToInt(), (bottomY - height).roundToInt()),
            dstSize = IntSize(width.roundToInt(), height.roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
    }
}

/** Placeholder rear view: wheels, body, cabin and two tail lights — replaced by sprites later. */
private fun DrawScope.drawRearCar(cx: Float, bottomY: Float, width: Float, heightFactor: Float, body: Color, roof: Color) {
    val h = width * heightFactor
    drawGroundShadow(cx, bottomY, width, h)
    val wheel = Color(0xFF15121C)
    drawRect(wheel, Offset(cx - width * 0.54f, bottomY - h * 0.22f), Size(width * 0.16f, h * 0.22f))
    drawRect(wheel, Offset(cx + width * 0.38f, bottomY - h * 0.22f), Size(width * 0.16f, h * 0.22f))
    drawRoundRect(body, Offset(cx - width / 2f, bottomY - h * 0.66f), Size(width, h * 0.6f), CornerRadius(width * 0.1f))
    drawRoundRect(roof, Offset(cx - width * 0.37f, bottomY - h), Size(width * 0.74f, h * 0.42f), CornerRadius(width * 0.1f))
    val tail = Color(0xFFFF3B3B)
    drawRect(tail, Offset(cx - width * 0.44f, bottomY - h * 0.5f), Size(width * 0.2f, h * 0.1f))
    drawRect(tail, Offset(cx + width * 0.24f, bottomY - h * 0.5f), Size(width * 0.2f, h * 0.1f))
}
