package com.khcompany.lanedash.game.highway

import com.khcompany.lanedash.game.GameStatus
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

        private fun chooseTarget(): Int {
            val lanes = HW_LANE_CENTERS.indices
            val here = lanes.minBy { abs(HW_LANE_CENTERS[it] - engine.playerX) }
            val hereTime = timeToImpact(here)
            if (hereTime >= safeSeconds) return here

            var best = here
            var bestScore = hereTime
            for (candidate in lanes) {
                if (candidate == here) continue
                // Every lane on the way (and the destination) must stay clear while crossing it.
                val clear = (minOf(here, candidate)..maxOf(here, candidate)).all { lane ->
                    lane == here || timeToImpact(lane) > abs(lane - here) * CROSSING_SECONDS_PER_LANE + 0.3f
                }
                val score = timeToImpact(candidate) - abs(candidate - here) * 0.2f
                if (clear && score > bestScore) {
                    best = candidate
                    bestScore = score
                }
            }
            return best
        }
    }

    private class Summary(val medianSeconds: Float, val medianScore: Int)

    private fun play(seed: Int, reaction: Float, capSeconds: Float): Pair<Float, Int> {
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
        return now to engine.score
    }

    private fun measure(name: String, reaction: Float, runs: Int = 40, capSeconds: Float = 300f): Summary {
        val results = (1..runs).map { play(it, reaction, capSeconds) }
        val seconds = results.map { it.first }.sorted()
        val scores = results.map { it.second }.sorted()
        val summary = Summary(seconds[runs / 2], scores[runs / 2])
        println(
            "BALANCE %-8s reaction=%.2f | survive p10=%.0fs p50=%.0fs p90=%.0fs | score p50=%d | >60s=%d%% capped=%d%%".format(
                name, reaction, seconds[runs / 10], summary.medianSeconds, seconds[runs * 9 / 10], summary.medianScore,
                seconds.count { it > 60f } * 100 / runs, seconds.count { it >= capSeconds } * 100 / runs,
            ),
        )
        return summary
    }

    @Test
    fun survivalFollowsSkill_andNobodyIsImmortalOrDoomed() {
        val expert = measure("expert", 0.15f)
        val average = measure("average", 0.30f)
        val casual = measure("casual", 0.50f)

        assertTrue("faster reactions survive longer", expert.medianSeconds > average.medianSeconds)
        assertTrue("faster reactions survive longer", average.medianSeconds > casual.medianSeconds)
        assertTrue("a casual player gets a real run (${casual.medianSeconds}s)", casual.medianSeconds in 20f..80f)
        assertTrue("an average player lasts a few minutes at most (${average.medianSeconds}s)", average.medianSeconds in 60f..150f)
        assertTrue("the hard phase still beats a very quick player (${expert.medianSeconds}s)", expert.medianSeconds < 300f)
    }

    private companion object {
        /** A finger taps about this often. */
        const val TAP_INTERVAL = 0.14f

        /** Time one lane change takes to cross (the car glides ~0.15 s per lane). */
        const val CROSSING_SECONDS_PER_LANE = 0.2f
    }
}
