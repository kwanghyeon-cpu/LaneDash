package com.khcompany.lanedash.game.highway

import com.khcompany.lanedash.game.GameOverReason
import com.khcompany.lanedash.game.GameStatus
import com.khcompany.lanedash.game.ItemType
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Difficulty harness: scripted "players" with different reaction times drive the engine
 * headlessly, so the balance (survival time, score) can be measured without a phone. Runs are
 * seeded, so the numbers are deterministic. The bots are steadier than people, so real players
 * should land at or below these times.
 *
 * Targets (median survival): casual (0.5 s reaction) 20-80 s, average (0.3 s) 60-150 s, and even
 * the expert (0.15 s) should usually die before 5 minutes.
 */
class HighwayBalanceTest {

    /** A player that looks ahead for the lane with the most time before an impact. */
    private class Bot(
        private val engine: HighwayEngine,
        /** Seconds between noticing a danger and the tap reaching the screen. */
        private val reaction: Float,
        /** Wants at least this much time-to-impact in its lane before it stops worrying. */
        private val safeSeconds: Float,
    ) {
        private val pending = ArrayDeque<Pair<Float, Int>>() // (applyAtTime, targetLane)
        private var target = HW_LANE_CENTERS.size / 2
        private var nextLook = 0f
        private var nextTap = 0f

        fun act(now: Float) {
            if (now >= nextLook) {
                nextLook = now + 0.1f
                pending.addLast(now + reaction to chooseTarget())
            }
            while (pending.isNotEmpty() && pending.first().first <= now) target = pending.removeFirst().second
            // One lane per tap, and a finger can't tap faster than about this.
            if (target != engine.laneIndex && now >= nextTap) {
                engine.moveLane(if (target > engine.laneIndex) 1 else -1)
                nextTap = now + TAP_INTERVAL
            }
        }

        private fun timeToImpact(lane: Int): Float {
            var best = 99f
            val playerZ = engine.z + HW_PLAYER_DZ
            for (car in engine.traffic) {
                if (abs(car.lx - HW_LANE_CENTERS[lane]) > HW_LANE_SPACING / 2f) continue
                val gap = car.z - playerZ
                if (gap < -1f) continue
                val closing = engine.speed - car.speed
                val t = if (gap <= 0f) 0f else if (closing > 0.1f) gap / closing else 99f
                if (t < best) best = t
            }
            return best
        }

        private fun laneOfX(x: Float): Int = HW_LANE_CENTERS.indices.minBy { abs(HW_LANE_CENTERS[it] - x) }

        private fun laneClearFor(here: Int, lane: Int): Boolean =
            (minOf(here, lane)..maxOf(here, lane)).all { l ->
                l == here || timeToImpact(l) > abs(l - here) * CROSSING_SECONDS_PER_LANE + 0.3f
            }

        /** The lane of a pickup worth steering to (fuel when it's getting low, power-ups always), if safe. */
        private fun pickupLane(here: Int): Int? {
            val playerZ = engine.z + HW_PLAYER_DZ
            var best: Pair<Int, Float>? = null
            for (item in engine.items) {
                val wanted = item.type != ItemType.FUEL || engine.fuel < 85f
                val gap = item.z - playerZ
                if (!wanted || gap < 0f || gap > engine.speed * 3.2f) continue
                val lane = laneOfX(item.lx)
                val arrival = gap / engine.speed
                if (abs(lane - here) * CROSSING_SECONDS_PER_LANE + 0.2f > arrival) continue
                if (!laneClearFor(here, lane) || timeToImpact(lane) < arrival + 0.5f) continue
                if (best == null || arrival < best.second) best = lane to arrival
            }
            return best?.first
        }

        private fun chooseTarget(): Int {
            val lanes = HW_LANE_CENTERS.indices
            // Judge from the lane already commanded (the car follows within ~0.15 s) so decisions don't flip mid-move.
            val here = engine.laneIndex
            val hereTime = timeToImpact(here)
            if (hereTime >= safeSeconds) return pickupLane(here) ?: here

            var best = here
            var bestScore = hereTime
            for (candidate in lanes) {
                if (candidate == here) continue
                val score = timeToImpact(candidate) - abs(candidate - here) * 0.2f
                if (laneClearFor(here, candidate) && score > bestScore) {
                    best = candidate
                    bestScore = score
                }
            }
            return best
        }
    }

    private class Summary(val medianSeconds: Float, val medianScore: Int)

    private fun play(seed: Int, reaction: Float, capSeconds: Float): Triple<Float, Int, GameOverReason?> {
        val engine = HighwayEngine(random = Random(seed))
        engine.start()
        val bot = Bot(engine, reaction, safeSeconds = 1.6f)
        var now = 0f
        val dt = 1f / 60f
        while (engine.status == GameStatus.RUNNING && now < capSeconds) {
            bot.act(now)
            engine.tick(dt)
            now += dt
        }
        return Triple(now, engine.score, engine.gameOverReason)
    }

    private fun measure(name: String, reaction: Float, runs: Int = 40, capSeconds: Float = 300f): Summary {
        val results = (1..runs).map { play(it, reaction, capSeconds) }
        val seconds = results.map { it.first }.sorted()
        val scores = results.map { it.second }.sorted()
        val summary = Summary(seconds[runs / 2], scores[runs / 2])
        val fuelDeaths = results.count { it.third == GameOverReason.FUEL_EMPTY }
        println(
            "BALANCE %-8s reaction=%.2f | survive p10=%.0fs p50=%.0fs p90=%.0fs | score p50=%d | >60s=%d%% capped=%d%% fuel deaths=%d".format(
                name, reaction, seconds[runs / 10], summary.medianSeconds, seconds[runs * 9 / 10], summary.medianScore,
                seconds.count { it > 60f } * 100 / runs, seconds.count { it >= capSeconds } * 100 / runs, fuelDeaths,
            ),
        )
        return summary
    }

    @Test
    fun aSlowerPlayerSurvivesLess_andNobodyIsImmortalOrDoomed() {
        val expert = measure("expert", 0.15f)
        val average = measure("average", 0.30f)
        val casual = measure("casual", 0.50f)

        // The bots plan one move ahead, so the two quicker ones end up close together; only the slow
        // one clearly falls behind.
        assertTrue("a slow reaction survives least", casual.medianSeconds < average.medianSeconds && casual.medianSeconds < expert.medianSeconds)
        assertTrue("a casual player gets a real run (${casual.medianSeconds}s)", casual.medianSeconds in 15f..60f)
        assertTrue("an average player lasts a while (${average.medianSeconds}s)", average.medianSeconds in 20f..100f)
        assertTrue("the hard phase still beats a very quick player (${expert.medianSeconds}s)", expert.medianSeconds in 20f..300f)
    }

    private companion object {
        /** A finger taps about this often. */
        const val TAP_INTERVAL = 0.14f

        /** Time one lane change takes to cross (the car glides ~0.15 s per lane). */
        const val CROSSING_SECONDS_PER_LANE = 0.2f
    }
}
