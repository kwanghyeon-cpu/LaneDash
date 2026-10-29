package com.khcompany.lanedash.game.highway

import com.khcompany.lanedash.game.GameStatus
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HighwayRoadTest {
    private val w = 1080f
    private val h = 2220f
    private val horizon = h * HW_HORIZON_FRACTION

    private fun strips(cameraZ: Float = 100f, cameraX: Float = 0f): RoadStrips =
        RoadStrips().also { it.compute(cameraZ, cameraX, w, horizon, h, 12f, 4f) }

    @Test
    fun strips_runFromBottomEdgeUpToHorizonAndShrinkWithDistance() {
        val s = strips()
        assertTrue(s.count > 50)
        assertEquals(h, s.yBottom[0], 0.001f)
        assertTrue(s.yTop[s.count - 1] > horizon)
        for (i in 1 until s.count) {
            assertTrue("rows go up the screen", s.yBottom[i] < s.yBottom[i - 1])
            assertTrue("road narrows with distance", s.halfWidth[i] < s.halfWidth[i - 1])
            assertTrue("depth grows", s.dz[i] > s.dz[i - 1])
        }
    }

    @Test
    fun cameraOffset_slidesTheWholeRoadOneHalfWidthPerUnit() {
        val a = strips(cameraX = 0f)
        val b = strips(cameraX = 1f)
        for (i in 0 until a.count) assertEquals(a.centerX[i] - a.halfWidth[i], b.centerX[i], 0.01f)
    }

    @Test
    fun indexForY_mapsRowsToStripsAndClamps() {
        val s = strips()
        assertEquals(0, s.indexForY(h, h, 12f))
        assertEquals(s.count - 1, s.indexForY(0f, h, 12f))
        assertEquals(2, s.indexForY(h - 30f, h, 12f))
    }

    @Test
    fun curvature_staysGentle() {
        var z = 0f
        while (z < 2000f) {
            assertTrue(abs(curveAt(z)) < 0.02f)
            z += 3f
        }
    }
}

class HighwayEngineTest {
    private lateinit var engine: HighwayEngine
    private var nextId = 500L

    @Before
    fun setUp() {
        engine = HighwayEngine(random = Random(7))
        engine.start()
    }

    private fun addTraffic(lx: Float, relativeZ: Float, speed: Float = 8f) {
        engine.traffic.add(TrafficCar(nextId++, lx, engine.z + HW_PLAYER_DZ + relativeZ, speed, TrafficKind.SEDAN))
    }

    private fun tickUntilPassed(car: TrafficCar) {
        var guard = 0
        while (!car.passed && engine.status == GameStatus.RUNNING && guard++ < 400) engine.tick(0.016f)
    }

    @Test
    fun passingCloseToACarEarnsANearMissBonus() {
        addTraffic(lx = 0.3f, relativeZ = 1f) // not touching (0.265), but inside the near-miss margin
        val car = engine.traffic.last()
        tickUntilPassed(car)
        assertEquals(GameStatus.RUNNING, engine.status)
        assertEquals((engine.z * HighwayEngine.SCORE_PER_UNIT).toInt() + HighwayEngine.NEAR_MISS_SCORE, engine.score)
    }

    @Test
    fun aNearMissFlashesBrieflyThenFades() {
        addTraffic(lx = 0.3f, relativeZ = 1f)
        tickUntilPassed(engine.traffic.last())
        assertEquals(HighwayEngine.NEAR_MISS_FLASH_SECONDS, engine.nearMissFlash, 0.05f)
        repeat(70) { engine.traffic.clear(); engine.tick(0.016f) } // ~1.1 s
        assertEquals(0f, engine.nearMissFlash, 0f)
    }

    @Test
    fun startsInTheMiddleLaneOfFive() {
        assertEquals(5, HW_LANE_CENTERS.size)
        assertEquals(2, engine.laneIndex)
        assertEquals(0f, engine.playerX, 0.0001f)
    }

    @Test
    fun theCarLeansIntoAMoveThenSettles() {
        assertEquals(0f, engine.steerLean, 0f)
        engine.moveLane(1)
        engine.tick(0.016f)
        assertTrue(engine.steerLean > 0.3f)
        repeat(60) { engine.tick(0.016f) }
        assertEquals(0f, engine.steerLean, 0.02f)
        engine.moveLane(-1)
        engine.tick(0.016f)
        assertTrue(engine.steerLean < -0.3f)
    }

    @Test
    fun passingFarFromACarEarnsNothingExtra() {
        addTraffic(lx = 0.95f, relativeZ = 1f)
        val car = engine.traffic.last()
        tickUntilPassed(car)
        assertEquals((engine.z * HighwayEngine.SCORE_PER_UNIT).toInt(), engine.score)
    }

    @Test
    fun trafficIsSpawnedFairly_neverAWallNobodyCouldDodge() {
        val seen = HashSet<Long>()
        repeat(6000) { // 100 s at 60 fps, steering nothing: the player sits in the middle lane
            engine.traffic.removeAll { it.z < engine.z + HW_PLAYER_DZ - 1f } // keep it alive and the list small
            engine.tick(1f / 60f)
            if (engine.status != GameStatus.RUNNING) engine.start()
            val difficulty = (engine.elapsedSeconds / HighwayEngine.RAMP_SECONDS).coerceIn(0f, 1f)
            fun passIn(car: TrafficCar) = (car.z - engine.z - HW_PLAYER_DZ) / (engine.speed - car.speed).coerceAtLeast(1f)
            fun laneOf(car: TrafficCar) = HW_LANE_CENTERS.indices.minBy { abs(HW_LANE_CENTERS[it] - car.lx) }
            for (fresh in engine.traffic.filter { seen.add(it.id) }) {
                for (other in engine.traffic) {
                    if (other === fresh || other.id > fresh.id || other.passed) continue
                    val needed = HighwayEngine.requiredGapSeconds(abs(laneOf(fresh) - laneOf(other)), difficulty)
                    assertTrue("cars ${other.id}/${fresh.id} pass too close together", abs(passIn(fresh) - passIn(other)) >= needed - 0.05f)
                }
            }
        }
        assertTrue(seen.size > 20)
    }

    @Test
    fun fiveLanesAndEveryCarFitsItsLane() {
        assertEquals(5, HW_LANE_CENTERS.size)
        val widest = TrafficKind.entries.maxOf { it.halfWidth }
        assertTrue("an adjacent-lane car never touches", HighwayEngine.PLAYER_HALF_WIDTH + widest < HW_LANE_SPACING)
        assertTrue("a drawn car fits inside a lane", HighwayEngine.PLAYER_DRAW_WIDTH < HW_LANE_SPACING)
    }

    @Test
    fun aTapGlidesToTheNextLaneFastEnoughToDodge() {
        engine.moveLane(1)
        assertEquals(3, engine.laneIndex)
        var seconds = 0f
        while (engine.playerX < HW_LANE_CENTERS[3] - 0.04f && seconds < 2f) {
            engine.tick(0.016f)
            seconds += 0.016f
        }
        assertTrue("a lane takes too long: " + seconds + " s", seconds < 0.3f)
        repeat(60) { engine.tick(0.016f) }
        assertEquals("settles on the lane center", HW_LANE_CENTERS[3], engine.playerX, 0.02f)
    }

    @Test
    fun tapsStackUpToCrossSeveralLanesAndStopAtTheRoadEdges() {
        engine.moveLane(1)
        engine.moveLane(1)
        assertEquals(4, engine.laneIndex)
        repeat(10) { engine.moveLane(1) }
        assertEquals(HW_LANE_CENTERS.size - 1, engine.laneIndex)
        repeat(10) { engine.moveLane(-1) }
        assertEquals(0, engine.laneIndex)
    }

    @Test
    fun movesAreIgnoredWhilePaused() {
        engine.pause()
        engine.moveLane(1)
        assertEquals(2, engine.laneIndex)
    }

    @Test
    fun roadBendsPushTheCarButItStaysInItsLane() {
        var widest = 0f
        repeat(20 * 60) { // 20 s of road, no traffic
            engine.traffic.clear()
            engine.tick(1f / 60f)
            widest = maxOf(widest, abs(engine.playerX - HW_LANE_CENTERS[engine.laneIndex]))
        }
        assertTrue("pushed " + widest + " off the lane center", widest in 0.005f..HW_LANE_SPACING / 2f)
    }

    @Test
    fun theTopSpeedClimbsWithTheRun() {
        repeat(100 * 20) { // 100 s, no obstacles
            engine.traffic.clear()
            engine.tick(0.05f)
        }
        assertTrue(engine.speed > HighwayEngine.EARLY_TOP_SPEED + 5f)
        assertTrue(engine.speed <= HighwayEngine.MAX_SPEED)
    }

    @Test
    fun startsRunningAndAcceleratesForward() {
        assertEquals(GameStatus.RUNNING, engine.status)
        repeat(20) { engine.tick(0.05f) }
        assertTrue(engine.z > 0f)
        assertTrue(engine.speed > HighwayEngine.START_SPEED)
    }

    @Test
    fun collidingWithTrafficInYourLaneEndsTheRun() {
        addTraffic(lx = engine.playerX, relativeZ = 0f)
        engine.tick(0.016f)
        assertEquals(GameStatus.GAME_OVER, engine.status)
    }

    @Test
    fun trafficInAnotherLaneIsHarmless() {
        addTraffic(lx = 0.8f, relativeZ = 0f)
        engine.tick(0.016f)
        assertEquals(GameStatus.RUNNING, engine.status)
    }

    @Test
    fun pauseFreezesTheSimulation() {
        engine.tick(0.05f)
        val z = engine.z
        engine.pause()
        engine.tick(0.05f)
        assertEquals(z, engine.z, 0f)
        engine.resume()
        engine.tick(0.05f)
        assertTrue(engine.z > z)
    }

    @Test
    fun trafficSpawnsAheadAndIsRemovedOnceBehind() {
        repeat(40) { engine.tick(0.05f) }
        assertTrue(engine.traffic.isNotEmpty())
        engine.traffic.clear()
        addTraffic(lx = 0.8f, relativeZ = -HighwayEngine.REMOVE_BEHIND - 1f)
        engine.tick(0.016f)
        assertTrue(engine.traffic.none { it.lx == 0.8f })
    }

    @Test
    fun restartResetsTheRun() {
        repeat(10) { engine.tick(0.05f) }
        engine.start()
        assertEquals(0f, engine.z, 0f)
        assertEquals(0, engine.traffic.size)
        assertEquals(0, engine.score)
    }
}
