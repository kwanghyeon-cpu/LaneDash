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
import kotlin.math.ceil
import kotlin.random.Random

/**
 * Kinds of traffic ahead; wider/taller ones are slower. [halfWidth] is in road half-widths and
 * [heightFactor] (height / width) matches the rear-view sprite named [spriteName]. A lane is
 * [HW_LANE_SPACING] wide, so these stay well inside one.
 */
enum class TrafficKind(
    val halfWidth: Float,
    val heightFactor: Float,
    val minSpeed: Float,
    val maxSpeed: Float,
    val spriteName: String,
) {
    SEDAN(0.14f, 0.74f, 9f, 13f, "traffic_rear_sedan"),
    SUV(0.15f, 0.88f, 8.5f, 12.5f, "traffic_rear_suv"),
    BUS(0.19f, 1.05f, 7f, 10.5f, "traffic_rear_bus"),
    TRUCK(0.18f, 1.29f, 6f, 9.5f, "traffic_rear_truck"),
}

class TrafficCar(
    val id: Long,
    /** Lateral position in road half-widths. */
    val lx: Float,
    /** Position along the road, in world units. */
    var z: Float,
    val speed: Float,
    val kind: TrafficKind,
) {
    /** Set once the car is behind the player (near-miss bonus is judged at that moment). */
    var passed = false
}

/**
 * Simulation for the behind-the-car mode. Pure and resolution independent, like GameEngine:
 * the UI only reads state and calls [moveLane] (one lane per press, like the classic mode).
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

    /** Lane the player is heading for (index into [HW_LANE_CENTERS]). */
    var laneIndex by mutableIntStateOf(HW_LANE_CENTERS.size / 2)
        private set

    /**
     * Player's lateral position in road half-widths. It is a spring-damper chasing the target
     * lane's center, so a lane change has weight (overshoot, lean) and road bends still push it.
     */
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

    val traffic = mutableStateListOf<TrafficCar>()

    /** Seconds left of the "NEAR MISS" flash after the latest close pass (0 when idle). */
    var nearMissFlash by mutableFloatStateOf(0f)
        private set

    private var lateralVelocity = 0f

    /** -1..1: how far the car still has to travel to its lane; the UI leans the sprite by it. */
    val steerLean: Float get() = ((HW_LANE_CENTERS[laneIndex] - playerX) / HW_LANE_SPACING).coerceIn(-1f, 1f)

    private var spawnCooldown = 1f
    private var nextId = 0L
    private var bonusScore = 0

    fun start() {
        traffic.clear()
        status = GameStatus.RUNNING
        paused = false
        gameOverReason = null
        z = 0f
        laneIndex = HW_LANE_CENTERS.size / 2
        playerX = HW_LANE_CENTERS[laneIndex]
        lateralVelocity = 0f
        speed = START_SPEED
        score = 0
        bonusScore = 0
        nearMissFlash = 0f
        elapsedSeconds = 0f
        heading = 0f
        spawnCooldown = 1f
    }

    fun pause() {
        if (status == GameStatus.RUNNING) paused = true
    }

    fun resume() {
        paused = false
    }

    /** Heads one lane left (-1) or right (+1); the car glides there in a fraction of a second. */
    fun moveLane(delta: Int) {
        if (status != GameStatus.RUNNING || paused) return
        laneIndex = (laneIndex + delta).coerceIn(0, HW_LANE_CENTERS.size - 1)
    }

    fun tick(dtSeconds: Float) {
        if (status != GameStatus.RUNNING || paused) return
        val dt = dtSeconds.coerceAtMost(0.05f)
        elapsedSeconds += dt
        if (nearMissFlash > 0f) nearMissFlash = maxOf(0f, nearMissFlash - dt)

        // Difficulty eases from 0 to 1 over the first RAMP_SECONDS of a run, then stays maxed.
        val difficulty = (elapsedSeconds / RAMP_SECONDS).coerceIn(0f, 1f)

        // Speed: auto-accelerates toward a top speed that creeps up with difficulty.
        val topSpeed = EARLY_TOP_SPEED + (MAX_SPEED - EARLY_TOP_SPEED) * difficulty
        speed = minOf(topSpeed, speed + ACCELERATION * dt)
        z += speed * dt
        heading += curveAt(z) * speed * dt

        // Chase the target lane with a damped spring (small sub-steps keep it stable), while bends
        // in the road push the car outward.
        val laneX = HW_LANE_CENTERS[laneIndex]
        val steps = ceil(dt / LATERAL_STEP).toInt().coerceAtLeast(1)
        val h = dt / steps
        val push = curveAt(z + HW_PLAYER_DZ) * (speed / MAX_SPEED) * CENTRIFUGAL
        repeat(steps) {
            lateralVelocity += (LANE_SPRING * (laneX - playerX) - LANE_DAMPING * lateralVelocity) * h
            playerX += (lateralVelocity - push) * h
        }

        spawnCooldown -= dt
        if (spawnCooldown <= 0f) {
            spawnCooldown = if (spawnTraffic(difficulty)) {
                (SPAWN_EASY_INTERVAL + (SPAWN_HARD_INTERVAL - SPAWN_EASY_INTERVAL) * difficulty) *
                    (0.7f + 0.6f * random.nextFloat())
            } else {
                SPAWN_RETRY_INTERVAL
            }
        }

        val iterator = traffic.listIterator()
        while (iterator.hasNext()) {
            val car = iterator.next()
            car.z += car.speed * dt
            val relative = car.z - (z + HW_PLAYER_DZ)
            val lateral = abs(car.lx - playerX)
            if (abs(relative) < COLLISION_DEPTH && lateral < PLAYER_HALF_WIDTH + car.kind.halfWidth) {
                onSfx(SfxEvent.CRASH)
                status = GameStatus.GAME_OVER
                gameOverReason = GameOverReason.COLLISION
                score = currentScore()
                return
            }
            if (!car.passed && relative < -COLLISION_DEPTH) {
                car.passed = true
                if (lateral < PLAYER_HALF_WIDTH + car.kind.halfWidth + NEAR_MISS_MARGIN) {
                    bonusScore += NEAR_MISS_SCORE
                    nearMissFlash = NEAR_MISS_FLASH_SECONDS
                    onSfx(SfxEvent.NEAR_MISS)
                }
            }
            if (relative < -REMOVE_BEHIND) iterator.remove()
        }
        score = currentScore()
    }

    private fun currentScore(): Int = (z * SCORE_PER_UNIT).toInt() + bonusScore

    /** When [car] will reach the player's position at the current speeds (0 if it already has). */
    private fun secondsUntilPassing(car: TrafficCar): Float {
        val gap = car.z - (z + HW_PLAYER_DZ)
        if (gap <= 0f) return 0f
        return gap / (speed - car.speed).coerceAtLeast(MIN_CLOSING_SPEED)
    }

    /**
     * Adds one car, or none if no lane can take it fairly. Changing lanes takes time, so two cars
     * in different lanes must pass the player far enough apart for the move between them (scaled
     * with the lane distance) — otherwise random spawns create walls nobody can dodge. The
     * required gaps tighten as [difficulty] rises. Returns whether a car was added.
     */
    private fun spawnTraffic(difficulty: Float): Boolean {
        val kind = when (random.nextInt(20)) {
            in 0..7 -> TrafficKind.SEDAN
            in 8..13 -> TrafficKind.SUV
            in 14..16 -> TrafficKind.BUS
            else -> TrafficKind.TRUCK
        }
        val carSpeed = kind.minSpeed + (kind.maxSpeed - kind.minSpeed) * random.nextFloat()
        val spawnZ = z + HW_DRAW_DISTANCE - 1f
        val passIn = (spawnZ - (z + HW_PLAYER_DZ)) / (speed - carSpeed).coerceAtLeast(MIN_CLOSING_SPEED)

        val lanes = HW_LANE_CENTERS.indices.shuffled(random)
        for (lane in lanes) {
            val fair = traffic.none { other ->
                if (other.passed) return@none false
                val otherLane = HW_LANE_CENTERS.indices.minBy { abs(HW_LANE_CENTERS[it] - other.lx) }
                abs(secondsUntilPassing(other) - passIn) < requiredGapSeconds(abs(lane - otherLane), difficulty)
            }
            if (!fair) continue
            traffic.add(TrafficCar(nextId++, HW_LANE_CENTERS[lane], spawnZ, carSpeed, kind))
            return true
        }
        return false
    }

    companion object {
        const val START_SPEED = 14f

        /** Top speed at the start of a run; it climbs to [MAX_SPEED] over [RAMP_SECONDS]. */
        const val EARLY_TOP_SPEED = 22f
        const val MAX_SPEED = 30f
        const val RAMP_SECONDS = 120f
        const val ACCELERATION = 1.2f

        /**
         * The lane-chasing spring: ~22 rad/s with a damping ratio of ~0.7, so a lane takes about
         * 0.15 s and overshoots a few percent. CENTRIFUGAL scales how hard bends push the car.
         */
        const val LANE_SPRING = 480f
        const val LANE_DAMPING = 31f
        const val LATERAL_STEP = 1f / 120f
        const val CENTRIFUGAL = 40f

        /** Player's drawn width and its (slightly smaller) hitbox half-width, in road half-widths. */
        const val PLAYER_DRAW_WIDTH = 0.28f
        const val PLAYER_HALF_WIDTH = 0.125f
        const val COLLISION_DEPTH = 0.45f
        const val REMOVE_BEHIND = 4f
        const val SCORE_PER_UNIT = 2.4f
        const val NEAR_MISS_SCORE = 50

        /** Extra lateral distance beyond touching that still counts as a near miss. */
        const val NEAR_MISS_MARGIN = 0.10f
        const val NEAR_MISS_FLASH_SECONDS = 0.9f

        // Traffic density: seconds between spawn attempts, easy -> hard.
        const val SPAWN_EASY_INTERVAL = 1.2f
        const val SPAWN_HARD_INTERVAL = 0.35f
        const val SPAWN_RETRY_INTERVAL = 0.08f

        /** Minimum seconds between two cars passing the player in the same lane, easy -> hard. */
        const val SEPARATION_EASY = 0.5f
        const val SEPARATION_HARD = 0.22f

        /** Extra seconds required per lane of distance between two cars, easy -> hard. */
        const val LANE_GAP_EASY = 0.3f
        const val LANE_GAP_HARD = 0.17f
        const val MIN_CLOSING_SPEED = 1f

        /** Seconds two cars [laneDistance] lanes apart must pass the player apart at [difficulty]. */
        fun requiredGapSeconds(laneDistance: Int, difficulty: Float): Float {
            val separation = SEPARATION_EASY + (SEPARATION_HARD - SEPARATION_EASY) * difficulty
            val perLane = LANE_GAP_EASY + (LANE_GAP_HARD - LANE_GAP_EASY) * difficulty
            return separation + perLane * laneDistance
        }
    }
}
