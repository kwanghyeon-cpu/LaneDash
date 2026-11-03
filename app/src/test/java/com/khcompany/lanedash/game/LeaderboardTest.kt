package com.khcompany.lanedash.game

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeaderboardTest {

    private class FakeRepository : LeaderboardRepository {
        val board = mutableListOf<LeaderboardEntry>()

        override suspend fun fetchTopScores(limit: Int): List<LeaderboardEntry> =
            board.sortedByDescending { it.score }.take(limit)

        override suspend fun submitScore(name: String, score: Int) {
            board.add(LeaderboardEntry(name, score))
        }
    }

    @Test
    fun topScoresComeBackBestFirst() = runBlocking {
        val repo = FakeRepository()
        repo.submitScore("A", 90)
        repo.submitScore("B", 500)

        assertEquals(listOf("B", "A"), repo.fetchTopScores(10).map { it.name })
    }

    @Test
    fun qualifyingNeedsARoomySlotOrABetterScore() = runBlocking {
        val repo = FakeRepository()
        assertTrue("an empty board always has room", repo.qualifiesForTop(50, 10))

        repeat(10) { repo.submitScore("P$it", 1000 + it) }
        assertFalse(repo.qualifiesForTop(50, 10))
        assertTrue(repo.qualifiesForTop(5000, 10))
    }
}
