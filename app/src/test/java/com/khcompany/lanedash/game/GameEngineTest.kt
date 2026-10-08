package com.khcompany.lanedash.game

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Pure simulation tests: no clocks, no sleeping — time only advances through tick(dt). */
class GameEngineTest {

    private val sfx = mutableListOf<SfxEvent>()
    private lateinit var engine: GameEngine
    private var nextId = 1000L

    @Before
    fun setUp() {
        sfx.clear()
        engine = GameEngine(onSfx = { sfx.add(it) }, random = Random(1))
        engine.start()
    }

    private fun addObstacle(lane: Int, x: Float, variant: ObstacleVariant = ObstacleVariant.CONE) {
        engine.entities.add(RoadEntity(nextId++, lane, x, EntityKind.OBSTACLE, obstacleVariant = variant))
    }

    private fun addItem(lane: Int, x: Float, type: ItemType) {
        engine.entities.add(RoadEntity(nextId++, lane, x, EntityKind.ITEM, itemType = type))
    }

    @Test
    fun nearMiss_awardsBonusWhenSwervingOutJustBeforeImpact() {
        addObstacle(engine.laneIndex, CAR_X_UNITS + 1.5f)
        engine.moveLaneUp()
        assertEquals(1, engine.nearMissCount)
        assertTrue(SfxEvent.NEAR_MISS in sfx)
        engine.tick(0.016f)
        assertTrue("bonus must survive the per-tick score recompute", engine.score >= 30)
    }

    @Test
    fun nearMiss_notAwardedForDistantObstacle() {
        addObstacle(engine.laneIndex, CAR_X_UNITS + 10f)
        engine.moveLaneUp()
        assertEquals(0, engine.nearMissCount)
    }

    @Test
    fun nearMiss_paysOutOncePerObstacle() {
        val lane = engine.laneIndex
        addObstacle(lane, CAR_X_UNITS + 1.5f)
        engine.moveLaneUp()
        engine.moveLaneDown()
        engine.moveLaneUp()
        assertEquals(1, engine.nearMissCount)
    }

    @Test
    fun collision_endsRunWithoutShield() {
        addObstacle(engine.laneIndex, CAR_X_UNITS)
        engine.tick(0.016f)
        assertEquals(GameStatus.GAME_OVER, engine.status)
        assertEquals(GameOverReason.COLLISION, engine.gameOverReason)
    }

    @Test
    fun shield_absorbsObstacleAndExpires() {
        addItem(engine.laneIndex, CAR_X_UNITS, ItemType.SHIELD)
        engine.tick(0.016f)
        assertTrue(engine.shieldTimeLeft > 0f)

        addObstacle(engine.laneIndex, CAR_X_UNITS)
        engine.tick(0.016f)
        assertEquals(GameStatus.RUNNING, engine.status)
        assertTrue(SfxEvent.SHIELD_HIT in sfx)

        repeat(((GameEngine.SHIELD_DURATION_SECONDS + 1f) / 0.05f).toInt()) { engine.tick(0.05f) }
        assertEquals(0f, engine.shieldTimeLeft, 0f)
    }

    @Test
    fun magnet_collectsFuelFromOtherLanes() {
        addItem(engine.laneIndex, CAR_X_UNITS, ItemType.MAGNET)
        engine.tick(0.016f)
        assertTrue(engine.magnetTimeLeft > 0f)

        val otherLane = if (engine.laneIndex == 0) 1 else 0
        addItem(otherLane, CAR_X_UNITS + 5f, ItemType.FUEL)
        engine.tick(0.016f)
        assertEquals(1, engine.fuelCollected)
    }

    @Test
    fun fuelCan_scoreBonusIsNotWipedByNextTick() {
        addItem(engine.laneIndex, CAR_X_UNITS, ItemType.FUEL)
        engine.tick(0.016f)
        engine.tick(0.016f)
        assertTrue(engine.score >= 50)
    }

    @Test
    fun pause_freezesSimulationAndIgnoresInput() {
        engine.tick(0.016f)
        val distance = engine.distance
        val lane = engine.laneIndex
        engine.pause()
        engine.tick(0.05f)
        engine.moveLaneUp()
        assertEquals(distance, engine.distance, 0f)
        assertEquals(lane, engine.laneIndex)

        engine.resume()
        engine.tick(0.016f)
        assertTrue(engine.distance > distance)
    }

    @Test
    fun cityTraits_scaleSpeedAndFuelDrain() {
        val tokyo = GameCatalog.cityById("tokyo").traits
        engine.start(tokyo)
        assertEquals(engine.speed * 1.15f, engine.effectiveSpeed, 0.0001f)

        val paris = GameCatalog.cityById("paris").traits
        val base = GameEngine(random = Random(1)).apply { start() }
        val heavy = GameEngine(random = Random(1)).apply { start(paris) }
        base.tick(0.05f)
        heavy.tick(0.05f)
        assertTrue(heavy.fuel < base.fuel)
    }

    @Test
    fun restart_resetsRunStats() {
        addObstacle(engine.laneIndex, CAR_X_UNITS + 1.5f)
        engine.moveLaneUp()
        engine.start()
        assertEquals(0, engine.nearMissCount)
        assertEquals(0f, engine.elapsedSeconds, 0f)
        assertEquals(0, engine.entities.size)
    }
}
