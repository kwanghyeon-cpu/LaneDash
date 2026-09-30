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
enum class SfxEvent { COIN, CRASH, FUEL_EMPTY, LANE_CHANGE, FUEL_LOW_WARNING }

/**
 * Pure, resolution-independent game simulation. Positions are tracked in world units
 * (see [WORLD_WIDTH_UNITS]); the UI layer converts to pixels when drawing.
 */
class GameEngine(private val onSfx: (SfxEvent) -> Unit = {}) {

    var status by mutableStateOf(GameStatus.READY)
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

    val entities = mutableStateListOf<RoadEntity>()

    private var spawnCooldown = 1.2f
    private var nextEntityId = 0L
    private var fuelWarningPlayed = false
    private var lastSpawnLane = -1

    fun start() {
        entities.clear()
        laneIndex = LANE_COUNT / 2
        distance = 0f
        speed = START_SPEED
        score = 0
        fuel = MAX_FUEL
        spawnCooldown = 1.0f
        gameOverReason = null
        fuelWarningPlayed = false
        lastSpawnLane = -1
        usedContinue = false
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
        status = GameStatus.RUNNING
    }

    fun moveLaneUp() {
        if (status != GameStatus.RUNNING) return
        val newLane = (laneIndex - 1).coerceAtLeast(0)
        if (newLane != laneIndex) onSfx(SfxEvent.LANE_CHANGE)
        laneIndex = newLane
    }

    fun moveLaneDown() {
        if (status != GameStatus.RUNNING) return
        val newLane = (laneIndex + 1).coerceAtMost(LANE_COUNT - 1)
        if (newLane != laneIndex) onSfx(SfxEvent.LANE_CHANGE)
        laneIndex = newLane
    }

    fun tick(dtSeconds: Float) {
        if (status != GameStatus.RUNNING) return
        val dt = dtSeconds.coerceAtMost(0.05f)

        speed = (speed + SPEED_RAMP_PER_SECOND * dt).coerceAtMost(MAX_SPEED)
        distance += speed * dt
        score = floor(distance * 10f).toInt()

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

        spawnCooldown -= dt
        if (spawnCooldown <= 0f) {
            spawnEntity()
            val speedFactor = (speed / MAX_SPEED).coerceIn(0f, 1f)
            spawnCooldown = MAX_SPAWN_INTERVAL - (MAX_SPAWN_INTERVAL - MIN_SPAWN_INTERVAL) * speedFactor
        }

        val iterator = entities.listIterator()
        while (iterator.hasNext()) {
            val entity = iterator.next()
            entity.x -= speed * dt

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
            if (entity.lane == laneIndex && isOverlappingCar(entity.x, halfWidth)) {
                if (entity.kind == EntityKind.OBSTACLE) {
                    onSfx(SfxEvent.CRASH)
                    endGame(GameOverReason.COLLISION)
                    return
                } else {
                    score += ITEM_SCORE_BONUS
                    fuel = (fuel + FUEL_REFILL_AMOUNT).coerceAtMost(MAX_FUEL)
                    onSfx(SfxEvent.COIN)
                    iterator.remove()
                    continue
                }
            }

            if (entity.x < -1.5f) {
                iterator.remove()
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
            val choice = Random.nextInt(LANE_COUNT - 1)
            if (choice < lastSpawnLane) choice else choice + 1
        } else {
            Random.nextInt(LANE_COUNT)
        }
        lastSpawnLane = lane
        val spawnItem = Random.nextFloat() < ITEM_SPAWN_CHANCE
        val entity = if (spawnItem) {
            RoadEntity(id = nextEntityId++, lane = lane, x = WORLD_WIDTH_UNITS * 1.05f, kind = EntityKind.ITEM)
        } else {
            val variant = WEIGHTED_OBSTACLE_VARIANTS[Random.nextInt(WEIGHTED_OBSTACLE_VARIANTS.size)]
            RoadEntity(
                id = nextEntityId++,
                lane = lane,
                x = WORLD_WIDTH_UNITS * 1.05f,
                kind = EntityKind.OBSTACLE,
                obstacleVariant = variant,
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
        private const val ITEM_SCORE_BONUS = 50
        private const val MAX_FUEL = 100f
        private const val FUEL_DRAIN_PER_SECOND = 4.5f
        private const val FUEL_REFILL_AMOUNT = 26f

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
