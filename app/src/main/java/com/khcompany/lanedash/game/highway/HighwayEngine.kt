package com.khcompany.lanedash.game.highway

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.khcompany.lanedash.game.GameOverReason
import com.khcompany.lanedash.game.GameStatus
import com.khcompany.lanedash.game.SfxEvent
import kotlin.math.abs
import kotlin.random.Random

/**
 * Kinds of traffic ahead; wider/taller ones are slower. [halfWidth] is in road half-widths and
 * [heightFactor] (height / width) matches the rear-view sprite named [spriteName].
 */
enum class TrafficKind(
    val halfWidth: Float,
    val heightFactor: Float,
    val minSpeed: Float,
    val maxSpeed: Float,
    val spriteName: String,
) {
    SEDAN(0.22f, 0.74f, 7f, 11f, "traffic_rear_sedan"),
    SUV(0.23f, 0.88f, 6.5f, 10.5f, "traffic_rear_suv"),
    BUS(0.29f, 1.05f, 5.5f, 8.5f, "traffic_rear_bus"),
    TRUCK(0.28f, 1.29f, 5f, 8f, "traffic_rear_truck"),
}

class TrafficCar(
    val id: Long,
    /** Lateral position in road half-widths. */
    val lx: Float,
    /** Position along the road, in world units. */
    var z: Float,
    val speed: Float,
    val kind: TrafficKind,
)

/**
 * Simulation for the behind-the-car mode. Pure and resolution independent, like GameEngine:
 * the UI only reads state and feeds [steerInput] (-1 left, 0 straight, +1 right).
 */
class HighwayEngine(
    private val onSfx: (SfxEvent) -> Unit = {},
    private val random: Random = Random.Default,
) {
    var status by mutableStateOf(GameStatus.READY)
        private set
    var paused by mutableStateOf(false)
        private set
    var gameOverReason by mutableStateOf<GameOverReason?>(null)
        private set

    /** Camera position along the road (world units). */
    var z by mutableFloatStateOf(0f)
        private set

    /** Player's lateral position in road half-widths; |x| > 1 means on the grass. */
    var playerX by mutableFloatStateOf(0f)
        private set
    var speed by mutableFloatStateOf(START_SPEED)
        private set
    var score by mutableIntStateOf(0)
        private set
    var elapsedSeconds by mutableFloatStateOf(0f)
        private set

    /** Accumulated road heading; the UI scrolls the skyline backdrop by it. */
    var heading by mutableFloatStateOf(0f)
        private set

    var steerInput = 0

    val traffic = mutableStateListOf<TrafficCar>()

    private var steerVelocity = 0f
    private var spawnCooldown = 1f
    private var nextId = 0L
    private var lastLane = -1

    val offRoad: Boolean get() = abs(playerX) > 1f

    fun start() {
        traffic.clear()
        status = GameStatus.RUNNING
        paused = false
        gameOverReason = null
        z = 0f
        playerX = 0f
        speed = START_SPEED
        score = 0
        elapsedSeconds = 0f
        heading = 0f
        steerInput = 0
        steerVelocity = 0f
        spawnCooldown = 1f
        lastLane = -1
    }

    fun pause() {
        if (status == GameStatus.RUNNING) paused = true
    }

    fun resume() {
        paused = false
    }

    fun tick(dtSeconds: Float) {
        if (status != GameStatus.RUNNING || paused) return
        val dt = dtSeconds.coerceAtMost(0.05f)
        elapsedSeconds += dt

        // Speed: auto-accelerates to the top speed; the grass drags it down.
        val target = if (offRoad) OFFROAD_SPEED else MAX_SPEED
        speed = if (speed < target) minOf(target, speed + ACCELERATION * dt) else maxOf(target, speed - DECELERATION * dt)
        z += speed * dt
        heading += curveAt(z) * speed * dt
        score = (z * SCORE_PER_UNIT).toInt()

        // Steering has a little inertia, and bends in the road push the car outward.
        steerVelocity += (steerInput.toFloat() - steerVelocity) * minOf(1f, dt * 9f)
        val speedFactor = speed / MAX_SPEED
        playerX += steerVelocity * STEER_RATE * (0.6f + 0.4f * speedFactor) * dt
        playerX -= curveAt(z + HW_PLAYER_DZ) * speedFactor * CENTRIFUGAL * dt
        playerX = playerX.coerceIn(-MAX_LATERAL, MAX_LATERAL)

        spawnCooldown -= dt
        if (spawnCooldown <= 0f) {
            spawnTraffic()
            val speedNorm = (speed / MAX_SPEED).coerceIn(0f, 1f)
            spawnCooldown = (SPAWN_MAX_INTERVAL - (SPAWN_MAX_INTERVAL - SPAWN_MIN_INTERVAL) * speedNorm) *
                (0.7f + 0.6f * random.nextFloat())
        }

        val iterator = traffic.listIterator()
        while (iterator.hasNext()) {
            val car = iterator.next()
            car.z += car.speed * dt
            val relative = car.z - (z + HW_PLAYER_DZ)
            if (abs(relative) < COLLISION_DEPTH && abs(car.lx - playerX) < PLAYER_HALF_WIDTH + car.kind.halfWidth) {
                onSfx(SfxEvent.CRASH)
                status = GameStatus.GAME_OVER
                gameOverReason = GameOverReason.COLLISION
                return
            }
            if (relative < -REMOVE_BEHIND) iterator.remove()
        }
    }

    private fun spawnTraffic() {
        var lane = random.nextInt(HW_LANE_CENTERS.size)
        if (lane == lastLane) lane = (lane + 1 + random.nextInt(HW_LANE_CENTERS.size - 1)) % HW_LANE_CENTERS.size
        lastLane = lane
        val kind = when (random.nextInt(20)) {
            in 0..7 -> TrafficKind.SEDAN
            in 8..13 -> TrafficKind.SUV
            in 14..16 -> TrafficKind.BUS
            else -> TrafficKind.TRUCK
        }
        val carSpeed = kind.minSpeed + (kind.maxSpeed - kind.minSpeed) * random.nextFloat()
        traffic.add(TrafficCar(nextId++, HW_LANE_CENTERS[lane], z + HW_DRAW_DISTANCE - 1f, carSpeed, kind))
    }

    companion object {
        const val START_SPEED = 14f
        const val MAX_SPEED = 42f
        const val OFFROAD_SPEED = 10f
        const val ACCELERATION = 1.6f
        const val DECELERATION = 14f
        const val STEER_RATE = 1.8f
        const val CENTRIFUGAL = 40f
        const val MAX_LATERAL = 1.25f
        const val PLAYER_HALF_WIDTH = 0.20f
        const val COLLISION_DEPTH = 0.45f
        const val REMOVE_BEHIND = 4f
        const val SCORE_PER_UNIT = 1.5f
        const val SPAWN_MAX_INTERVAL = 1.5f
        const val SPAWN_MIN_INTERVAL = 0.55f
    }
}
