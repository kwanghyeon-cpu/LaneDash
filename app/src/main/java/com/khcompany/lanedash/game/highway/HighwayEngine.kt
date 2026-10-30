package com.khcompany.lanedash.game.highway

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.khcompany.lanedash.game.GameOverReason
import com.khcompany.lanedash.game.GameStatus
import com.khcompany.lanedash.game.ItemType
import com.khcompany.lanedash.game.SfxEvent
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.random.Random

/**
 * Kinds of obstacle ahead. Vehicles move with traffic (wider/taller ones slower); cones and
 * barriers stand still on the road (speed 0, so they rush at you at your own speed).
 * [halfWidth] is in road half-widths and [heightFactor] (height / width) matches the sprite named
 * [spriteName]. A lane is [HW_LANE_SPACING] wide, so these stay well inside one.
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
    CONE(0.07f, 1.4f, 0f, 0f, "obstacle_cone"),
    BARRIER(0.17f, 0.6f, 0f, 0f, "obstacle_barrier"),
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

/** A pickup lying on the road (fuel can, shield, magnet). Cans fly toward you under the magnet. */
class HighwayItem(
    val id: Long,
    /** Lateral position in road half-widths; changes only while the magnet pulls a fuel can. */
    var lx: Float,
    /** Position along the road, in world units. */
    val z: Float,
    val type: ItemType,
)

/**
 * Simulation for the behind-the-car mode. Pure and resolution independent, like GameEngine:
 * the UI only reads state and calls [moveLane] (one lane per press, like the classic mode).
 * Fuel, pickups and near misses follow the classic mode's rules.
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

    var fuel by mutableFloatStateOf(MAX_FUEL)
        private set
    var shieldTimeLeft by mutableFloatStateOf(0f)
        private set
    var magnetTimeLeft by mutableFloatStateOf(0f)
        private set

    /** Obstacles passed at close range this run (each awards a score + fuel bonus). */
    var nearMissCount by mutableIntStateOf(0)
        private set

    /** Fuel cans picked up this run. */
    var fuelCollected by mutableIntStateOf(0)
        private set

    val traffic = mutableStateListOf<TrafficCar>()
    val items = mutableStateListOf<HighwayItem>()

    /** Seconds left of the "NEAR MISS" flash after the latest close pass (0 when idle). */
    var nearMissFlash by mutableFloatStateOf(0f)
        private set

    /** -1..1: how far the car still has to travel to its lane; the UI leans the sprite by it. */
    val steerLean: Float get() = ((HW_LANE_CENTERS[laneIndex] - playerX) / HW_LANE_SPACING).coerceIn(-1f, 1f)

    private var lateralVelocity = 0f
    private var spawnCooldown = 1f
    private var nextId = 0L
    private var bonusScore = 0
    private var fuelWarningPlayed = false

    /** The spawn roll waiting for a fair spot (-1 = none). */
    private var pendingRoll = -1f

    fun start() {
        traffic.clear()
        items.clear()
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
        fuel = MAX_FUEL
        shieldTimeLeft = 0f
        magnetTimeLeft = 0f
        nearMissCount = 0
        fuelCollected = 0
        fuelWarningPlayed = false
        nearMissFlash = 0f
        elapsedSeconds = 0f
        heading = 0f
        spawnCooldown = 1f
        pendingRoll = -1f
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
        shieldTimeLeft = (shieldTimeLeft - dt).coerceAtLeast(0f)
        magnetTimeLeft = (magnetTimeLeft - dt).coerceAtLeast(0f)

        // Difficulty (speed, how tight the gaps are) eases from 0 to 1 over RAMP_SECONDS; traffic
        // density ramps up much faster, over DENSITY_RAMP_SECONDS, so the road fills early.
        val difficulty = (elapsedSeconds / RAMP_SECONDS).coerceIn(0f, 1f)
        val density = (elapsedSeconds / DENSITY_RAMP_SECONDS).coerceIn(0f, 1f)

        // Speed: auto-accelerates toward a top speed that creeps up with difficulty.
        val topSpeed = EARLY_TOP_SPEED + (MAX_SPEED - EARLY_TOP_SPEED) * difficulty
        speed = minOf(topSpeed, speed + ACCELERATION * dt)
        z += speed * dt
        heading += curveAt(z) * speed * dt

        // Fuel drains all the time; cans and near misses refill it.
        fuel = (fuel - FUEL_DRAIN_PER_SECOND * dt).coerceAtLeast(0f)
        if (!fuelWarningPlayed && fuel <= MAX_FUEL * 0.2f) {
            fuelWarningPlayed = true
            onSfx(SfxEvent.FUEL_LOW_WARNING)
        }
        if (fuel <= 0f) {
            onSfx(SfxEvent.FUEL_EMPTY)
            endGame(GameOverReason.FUEL_EMPTY)
            return
        }

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
            spawnCooldown = if (spawnSomething(difficulty)) {
                (SPAWN_EASY_INTERVAL + (SPAWN_HARD_INTERVAL - SPAWN_EASY_INTERVAL) * density) *
                    (0.7f + 0.6f * random.nextFloat())
            } else {
                SPAWN_RETRY_INTERVAL
            }
        }

        val playerZ = z + HW_PLAYER_DZ
        val iterator = traffic.listIterator()
        while (iterator.hasNext()) {
            val car = iterator.next()
            car.z += car.speed * dt
            val relative = car.z - playerZ
            val lateral = abs(car.lx - playerX)
            if (abs(relative) < COLLISION_DEPTH && lateral < PLAYER_HALF_WIDTH + car.kind.halfWidth) {
                if (shieldTimeLeft > 0f) {
                    // The shield smashes through: the obstacle is gone, the run goes on.
                    onSfx(SfxEvent.SHIELD_HIT)
                    iterator.remove()
                    continue
                }
                onSfx(SfxEvent.CRASH)
                endGame(GameOverReason.COLLISION)
                return
            }
            if (!car.passed && relative < -COLLISION_DEPTH) {
                car.passed = true
                if (lateral < PLAYER_HALF_WIDTH + car.kind.halfWidth + NEAR_MISS_MARGIN) {
                    bonusScore += NEAR_MISS_SCORE
                    fuel = (fuel + NEAR_MISS_FUEL).coerceAtMost(MAX_FUEL)
                    nearMissCount++
                    nearMissFlash = NEAR_MISS_FLASH_SECONDS
                    onSfx(SfxEvent.NEAR_MISS)
                }
            }
            if (relative < -REMOVE_BEHIND) iterator.remove()
        }

        val itemIterator = items.listIterator()
        while (itemIterator.hasNext()) {
            val item = itemIterator.next()
            val relative = item.z - playerZ
            // The magnet draws fuel cans from any lane toward the car as they come within reach.
            if (magnetTimeLeft > 0f && item.type == ItemType.FUEL && relative > -COLLISION_DEPTH && relative < MAGNET_RANGE) {
                item.lx += (playerX - item.lx) * minOf(1f, dt * MAGNET_PULL_RATE)
            }
            if (abs(relative) < COLLISION_DEPTH && abs(item.lx - playerX) < PLAYER_HALF_WIDTH + ITEM_HALF_WIDTH) {
                collect(item)
                itemIterator.remove()
                continue
            }
            if (relative < -REMOVE_BEHIND) itemIterator.remove()
        }
        score = currentScore()
    }

    private fun endGame(reason: GameOverReason) {
        status = GameStatus.GAME_OVER
        gameOverReason = reason
        score = currentScore()
    }

    private fun collect(item: HighwayItem) {
        when (item.type) {
            ItemType.FUEL -> {
                bonusScore += ITEM_SCORE_BONUS
                fuel = (fuel + FUEL_REFILL_AMOUNT).coerceAtMost(MAX_FUEL)
                fuelCollected++
                onSfx(SfxEvent.COIN)
            }
            ItemType.SHIELD -> {
                bonusScore += POWERUP_SCORE_BONUS
                shieldTimeLeft = SHIELD_DURATION_SECONDS
                onSfx(SfxEvent.POWERUP)
            }
            ItemType.MAGNET -> {
                bonusScore += POWERUP_SCORE_BONUS
                magnetTimeLeft = MAGNET_DURATION_SECONDS
                onSfx(SfxEvent.POWERUP)
            }
        }
    }

    private fun currentScore(): Int = (z * SCORE_PER_UNIT).toInt() + bonusScore

    /** Seconds until something at [thingZ] moving at [thingSpeed] reaches the player (0 if it already has). */
    private fun secondsUntilPassing(thingZ: Float, thingSpeed: Float): Float {
        val gap = thingZ - (z + HW_PLAYER_DZ)
        if (gap <= 0f) return 0f
        return gap / (speed - thingSpeed).coerceAtLeast(MIN_CLOSING_SPEED)
    }

    private fun laneOf(lx: Float): Int = HW_LANE_CENTERS.indices.minBy { abs(HW_LANE_CENTERS[it] - lx) }

    /**
     * One spawn event, like the classic mode's: mostly obstacles, sometimes a fuel can, rarely a
     * shield or magnet. Returns whether anything was added (a fair spot may not exist right now).
     */
    private fun spawnSomething(difficulty: Float): Boolean {
        // The roll sticks until it succeeds, so a crowded road doesn't keep re-rolling toward the
        // pickups (which always fit) and skew the mix.
        if (pendingRoll < 0f) pendingRoll = random.nextFloat()
        val roll = pendingRoll
        val added = when {
            roll < POWERUP_SPAWN_CHANCE -> spawnItem(if (random.nextBoolean()) ItemType.SHIELD else ItemType.MAGNET)
            roll < POWERUP_SPAWN_CHANCE + ITEM_SPAWN_CHANCE -> spawnItem(ItemType.FUEL)
            else -> spawnTraffic(difficulty)
        }
        if (added) pendingRoll = -1f
        return added
    }

    /** A pickup goes in a lane where no obstacle will pass at nearly the same moment. */
    private fun spawnItem(type: ItemType): Boolean {
        val spawnZ = z + HW_DRAW_DISTANCE - 1f
        val passIn = secondsUntilPassing(spawnZ, 0f)
        for (lane in HW_LANE_CENTERS.indices.shuffled(random)) {
            val clear = traffic.none { other ->
                !other.passed && laneOf(other.lx) == lane &&
                    abs(secondsUntilPassing(other.z, other.speed) - passIn) < ITEM_CLEARANCE_SECONDS
            } && items.none { other -> laneOf(other.lx) == lane && abs(secondsUntilPassing(other.z, 0f) - passIn) < ITEM_CLEARANCE_SECONDS }
            if (!clear) continue
            items.add(HighwayItem(nextId++, HW_LANE_CENTERS[lane], spawnZ, type))
            return true
        }
        return false
    }

    /**
     * Adds one obstacle, or none if no lane can take it fairly. Changing lanes takes time, so two
     * obstacles in different lanes must pass the player far enough apart for the move between them
     * (scaled with the lane distance) — otherwise random spawns create walls nobody can dodge. The
     * required gaps tighten as [difficulty] rises. Returns whether an obstacle was added.
     */
    private fun spawnTraffic(difficulty: Float): Boolean {
        val kind = when (random.nextInt(20)) {
            in 0..5 -> TrafficKind.SEDAN
            in 6..10 -> TrafficKind.SUV
            in 11..12 -> TrafficKind.BUS
            in 13..14 -> TrafficKind.TRUCK
            in 15..17 -> TrafficKind.CONE
            else -> TrafficKind.BARRIER
        }
        val carSpeed = kind.minSpeed + (kind.maxSpeed - kind.minSpeed) * random.nextFloat()
        val spawnZ = z + HW_DRAW_DISTANCE - 1f
        val passIn = secondsUntilPassing(spawnZ, carSpeed)

        for (lane in HW_LANE_CENTERS.indices.shuffled(random)) {
            val fair = traffic.none { other ->
                !other.passed &&
                    abs(secondsUntilPassing(other.z, other.speed) - passIn) < requiredGapSeconds(abs(lane - laneOf(other.lx)), difficulty)
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
        const val NEAR_MISS_FUEL = 3f

        /** Extra lateral distance beyond touching that still counts as a near miss. */
        const val NEAR_MISS_MARGIN = 0.10f
        const val NEAR_MISS_FLASH_SECONDS = 0.9f

        // Fuel and pickups — the classic mode's rules (the drain is a bit gentler: pickups here sit in
        // lanes you also have to dodge in).
        const val MAX_FUEL = 100f
        const val FUEL_DRAIN_PER_SECOND = 3.5f
        const val FUEL_REFILL_AMOUNT = 26f
        const val ITEM_SPAWN_CHANCE = 0.18f
        const val POWERUP_SPAWN_CHANCE = 0.05f
        const val ITEM_SCORE_BONUS = 50
        const val POWERUP_SCORE_BONUS = 25
        const val SHIELD_DURATION_SECONDS = 6f
        const val MAGNET_DURATION_SECONDS = 8f

        /** A pickup's hitbox half-width, and the seconds it keeps clear of obstacles in its lane. */
        const val ITEM_HALF_WIDTH = 0.10f
        const val ITEM_CLEARANCE_SECONDS = 0.7f

        /** World units ahead within which the magnet reels fuel cans in, and how fast (per second). */
        const val MAGNET_RANGE = 9f
        const val MAGNET_PULL_RATE = 10f

        // Spawn events: seconds between attempts, easy -> hard, over DENSITY_RAMP_SECONDS.
        const val DENSITY_RAMP_SECONDS = 40f
        const val SPAWN_EASY_INTERVAL = 0.9f
        const val SPAWN_HARD_INTERVAL = 0.35f
        const val SPAWN_RETRY_INTERVAL = 0.08f

        /** Minimum seconds between two obstacles passing the player in the same lane, easy -> hard. */
        const val SEPARATION_EASY = 0.5f
        const val SEPARATION_HARD = 0.22f

        /** Extra seconds required per lane of distance between two obstacles, easy -> hard. */
        const val LANE_GAP_EASY = 0.3f
        const val LANE_GAP_HARD = 0.17f
        const val MIN_CLOSING_SPEED = 1f

        /** Seconds two obstacles [laneDistance] lanes apart must pass the player apart at [difficulty]. */
        fun requiredGapSeconds(laneDistance: Int, difficulty: Float): Float {
            val separation = SEPARATION_EASY + (SEPARATION_HARD - SEPARATION_EASY) * difficulty
            val perLane = LANE_GAP_EASY + (LANE_GAP_HARD - LANE_GAP_EASY) * difficulty
            return separation + perLane * laneDistance
        }
    }
}
