package com.khcompany.lanedash.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.floor
import kotlin.random.Random

/** Gameplay sound cues the engine fires; the UI layer maps these to actual audio playback. */
enum class SfxEvent { COIN, CRASH, FUEL_EMPTY, LANE_CHANGE, FUEL_LOW_WARNING, NEAR_MISS, POWERUP, SHIELD_HIT }

/**
 * Pure, resolution-independent game simulation. Positions are tracked in world units
 * (see [WORLD_WIDTH_UNITS]); the UI layer converts to pixels when drawing.
 * Mirrors the web build's engine.js — keep formulas in sync.
 */
class GameEngine(
    private val onSfx: (SfxEvent) -> Unit = {},
    private val random: Random = Random.Default,
) {

    var status by mutableStateOf(GameStatus.READY)
        private set

    var paused by mutableStateOf(false)
        private set

    var laneIndex by mutableIntStateOf(LANE_COUNT / 2)
        private set

    var distance by mutableFloatStateOf(0f)
        private set

    var speed by mutableFloatStateOf(START_SPEED)
        private set

    var score by mutableIntStateOf(0)
        private set

    var fuel by mutableFloatStateOf(MAX_FUEL)
        private set

    var gameOverReason by mutableStateOf<GameOverReason?>(null)
        private set

    /** True once a rewarded-ad continue has been used this run — only one per run. */
    var usedContinue by mutableStateOf(false)
        private set

    var shieldTimeLeft by mutableFloatStateOf(0f)
        private set

    var magnetTimeLeft by mutableFloatStateOf(0f)
        private set

    /** Obstacles dodged at the last moment this run (each awards a score + fuel bonus). */
    var nearMissCount by mutableIntStateOf(0)
        private set

    /** Fuel cans picked up this run. */
    var fuelCollected by mutableIntStateOf(0)
        private set

    var elapsedSeconds by mutableFloatStateOf(0f)
        private set

    /** Per-city tuning for the current run (see [RunTraits]). */
    var traits: RunTraits = RunTraits.DEFAULT
        private set

    /** Ground speed after the city's speed multiplier — what actually moves the world. */
    val effectiveSpeed: Float get() = speed * traits.speed

    val entities = mutableStateListOf<RoadEntity>()

    private var spawnCooldown = 1.2f
    private var nextEntityId = 0L
    private var fuelWarningPlayed = false
    private var lastSpawnLane = -1

    // Score from near-misses, pickups etc. — kept separate because [score] is recomputed
    // from distance every tick and would otherwise wipe these out one frame later.
    private var bonusScore = 0

    fun start(runTraits: RunTraits = RunTraits.DEFAULT) {
        traits = runTraits
        entities.clear()
        laneIndex = LANE_COUNT / 2
        distance = 0f
        speed = START_SPEED
        score = 0
        bonusScore = 0
        fuel = MAX_FUEL
        spawnCooldown = 1.0f
        gameOverReason = null
        fuelWarningPlayed = false
        lastSpawnLane = -1
        usedContinue = false
        paused = false
        shieldTimeLeft = 0f
        magnetTimeLeft = 0f
        nearMissCount = 0
        fuelCollected = 0
        elapsedSeconds = 0f
        status = GameStatus.RUNNING
    }

    /**
     * Resumes a GAME_OVER run after the player watches a rewarded ad — refills fuel and
     * clears anything dangerously close to the car (otherwise a collision that just ended
     * the run would instantly re-fire on the very next tick). Score/distance/speed carry
     * over unchanged. Only usable once per run.
     */
    fun continueAfterAd() {
        if (status != GameStatus.GAME_OVER || usedContinue) return
        usedContinue = true
        fuel = MAX_FUEL
        entities.removeAll { kotlin.math.abs(it.x - CAR_X_UNITS) < 3f }
        gameOverReason = null
        fuelWarningPlayed = fuel <= MAX_FUEL * 0.2f
        paused = false
        status = GameStatus.RUNNING
    }

    fun pause() {
        if (status == GameStatus.RUNNING) paused = true
    }

    fun resume() {
        paused = false
    }

    fun moveLaneUp() = changeLane((laneIndex - 1).coerceAtLeast(0))

    fun moveLaneDown() = changeLane((laneIndex + 1).coerceAtMost(LANE_COUNT - 1))

    private fun changeLane(newLane: Int) {
        if (status != GameStatus.RUNNING || paused || newLane == laneIndex) return
        awardNearMisses(fromLane = laneIndex)
        onSfx(SfxEvent.LANE_CHANGE)
        laneIndex = newLane
    }

    /**
     * Swerving out of a lane just before an obstacle in it arrives is a "near miss" — a
     * reward for risky dodging, which matters now that traffic is dense. Each obstacle pays
     * out at most once, and only if it was within a short, speed-scaled reach of the car.
     */
    private fun awardNearMisses(fromLane: Int) {
        val reach = effectiveSpeed * NEAR_MISS_WINDOW_SECONDS
        var awarded = 0
        for (entity in entities) {
            if (entity.kind != EntityKind.OBSTACLE || entity.lane != fromLane || entity.nearMissAwarded) continue
            val halfWidth = entity.obstacleVariant.hitHalfWidthUnits * laneSizeScale(entity.lane.toFloat())
            val dx = entity.x - CAR_X_UNITS
            if (dx > -halfWidth && dx < halfWidth + reach) {
                entity.nearMissAwarded = true
                awarded++
            }
        }
        if (awarded > 0) {
            nearMissCount += awarded
            bonusScore += NEAR_MISS_SCORE * awarded
            fuel = (fuel + NEAR_MISS_FUEL * awarded).coerceAtMost(MAX_FUEL)
            onSfx(SfxEvent.NEAR_MISS)
        }
    }

    fun tick(dtSeconds: Float) {
        if (status != GameStatus.RUNNING || paused) return
        val dt = dtSeconds.coerceAtMost(0.05f)

        elapsedSeconds += dt
        speed = (speed + SPEED_RAMP_PER_SECOND * dt).coerceAtMost(MAX_SPEED)
        val groundSpeed = effectiveSpeed
        distance += groundSpeed * dt
        score = floor(distance * 10f * traits.score).toInt() + bonusScore

        shieldTimeLeft = (shieldTimeLeft - dt).coerceAtLeast(0f)
        magnetTimeLeft = (magnetTimeLeft - dt).coerceAtLeast(0f)

        fuel = (fuel - FUEL_DRAIN_PER_SECOND * traits.fuelDrain * dt).coerceAtLeast(0f)
        if (!fuelWarningPlayed && fuel <= MAX_FUEL * 0.2f) {
            fuelWarningPlayed = true
            onSfx(SfxEvent.FUEL_LOW_WARNING)
        }
        if (fuel <= 0f) {
            onSfx(SfxEvent.FUEL_EMPTY)
            endGame(GameOverReason.FUEL_EMPTY)
            return
        }

        spawnCooldown -= dt
        if (spawnCooldown <= 0f) {
            spawnEntity()
            val speedFactor = (speed / MAX_SPEED).coerceIn(0f, 1f)
            spawnCooldown = (MAX_SPAWN_INTERVAL - (MAX_SPAWN_INTERVAL - MIN_SPAWN_INTERVAL) * speedFactor) / traits.spawnDensity
        }

        val iterator = entities.listIterator()
        while (iterator.hasNext()) {
            val entity = iterator.next()
            entity.x -= groundSpeed * dt

            // Scaled by the entity's own lane depth (see laneSizeScale) — the player sits in
            // that same lane when this fires, so both shrink together and the "touch" point
            // this half-width was calibrated at stays correct at any lane.
            val halfWidth = (
                if (entity.kind == EntityKind.OBSTACLE) {
                    entity.obstacleVariant.hitHalfWidthUnits
                } else {
                    HITBOX_HALF_WIDTH
                }
                ) * laneSizeScale(entity.lane.toFloat())
            val touching = entity.lane == laneIndex && isOverlappingCar(entity.x, halfWidth)

            if (entity.kind == EntityKind.OBSTACLE) {
                if (touching) {
                    if (shieldTimeLeft > 0f) {
                        onSfx(SfxEvent.SHIELD_HIT)
                        iterator.remove()
                        continue
                    }
                    onSfx(SfxEvent.CRASH)
                    endGame(GameOverReason.COLLISION)
                    return
                }
            } else {
                val magnetPull = magnetTimeLeft > 0f &&
                    entity.itemType == ItemType.FUEL &&
                    entity.x < CAR_X_UNITS + MAGNET_RANGE_UNITS &&
                    entity.x > CAR_X_UNITS - halfWidth
                if (touching || magnetPull) {
                    collect(entity)
                    iterator.remove()
                    continue
                }
            }

            if (entity.x < -1.5f) {
                iterator.remove()
            }
        }
    }

    private fun collect(entity: RoadEntity) {
        when (entity.itemType) {
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

    private fun isOverlappingCar(entityX: Float, halfWidth: Float): Boolean {
        val dx = entityX - CAR_X_UNITS
        return dx > -halfWidth && dx < halfWidth
    }

    private fun spawnEntity() {
        // Never repeat the lane an entity just spawned in — a uniform pick has a flat
        // 1-in-LANE_COUNT chance of repeating, which reads as "the same lane keeps
        // getting stuff" far more often than it should over a run.
        val lane = if (lastSpawnLane in 0 until LANE_COUNT) {
            val choice = random.nextInt(LANE_COUNT - 1)
            if (choice < lastSpawnLane) choice else choice + 1
        } else {
            random.nextInt(LANE_COUNT)
        }
        lastSpawnLane = lane
        val x = WORLD_WIDTH_UNITS * 1.05f
        val roll = random.nextFloat()
        val entity = when {
            roll < POWERUP_SPAWN_CHANCE -> RoadEntity(
                id = nextEntityId++,
                lane = lane,
                x = x,
                kind = EntityKind.ITEM,
                itemType = if (random.nextBoolean()) ItemType.SHIELD else ItemType.MAGNET,
            )
            roll < POWERUP_SPAWN_CHANCE + ITEM_SPAWN_CHANCE ->
                RoadEntity(id = nextEntityId++, lane = lane, x = x, kind = EntityKind.ITEM)
            else -> RoadEntity(
                id = nextEntityId++,
                lane = lane,
                x = x,
                kind = EntityKind.OBSTACLE,
                obstacleVariant = WEIGHTED_OBSTACLE_VARIANTS[random.nextInt(WEIGHTED_OBSTACLE_VARIANTS.size)],
            )
        }
        entities.add(entity)
    }

    private fun endGame(reason: GameOverReason) {
        status = GameStatus.GAME_OVER
        gameOverReason = reason
    }

    companion object {
        // Difficulty bumped up again ahead of a content update — higher top speed, faster
        // ramp to it, and denser spawns throughout (both at the min and max end).
        private const val START_SPEED = 5.5f
        private const val MAX_SPEED = 16f
        private const val SPEED_RAMP_PER_SECOND = 0.5f
        private const val MIN_SPAWN_INTERVAL = 0.4f
        private const val MAX_SPAWN_INTERVAL = 1.1f
        private const val ITEM_SPAWN_CHANCE = 0.15f
        private const val POWERUP_SPAWN_CHANCE = 0.05f
        private const val ITEM_SCORE_BONUS = 50
        private const val POWERUP_SCORE_BONUS = 25
        private const val MAX_FUEL = 100f
        private const val FUEL_DRAIN_PER_SECOND = 4.5f
        private const val FUEL_REFILL_AMOUNT = 26f

        private const val NEAR_MISS_WINDOW_SECONDS = 0.3f
        private const val NEAR_MISS_SCORE = 30
        private const val NEAR_MISS_FUEL = 3f

        const val SHIELD_DURATION_SECONDS = 6f
        const val MAGNET_DURATION_SECONDS = 8f
        private const val MAGNET_RANGE_UNITS = 9f

        // Small/common obstacles spawn more often than big rare ones like buses.
        private val WEIGHTED_OBSTACLE_VARIANTS = buildList {
            addAll(List(3) { ObstacleVariant.CONE })
            addAll(List(2) { ObstacleVariant.BARRIER })
            addAll(List(3) { ObstacleVariant.SEDAN })
            addAll(List(2) { ObstacleVariant.SUV })
            addAll(List(1) { ObstacleVariant.BUS })
            addAll(List(1) { ObstacleVariant.TRUCK })
        }
    }
}

enum class GameOverReason { COLLISION, FUEL_EMPTY }
