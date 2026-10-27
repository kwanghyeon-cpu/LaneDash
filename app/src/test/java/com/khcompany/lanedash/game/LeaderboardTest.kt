package com.khcompany.lanedash.game

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeaderboardTest {

    private class FakeRepository : LeaderboardRepository {
        val boards = GameMode.entries.associateWith { mutableListOf<LeaderboardEntry>() }

        override suspend fun fetchTopScores(limit: Int, mode: GameMode): List<LeaderboardEntry> =
            boards.getValue(mode).sortedByDescending { it.score }.take(limit)

        override suspend fun submitScore(name: String, score: Int, mode: GameMode) {
            boards.getValue(mode).add(LeaderboardEntry(name, score))
        }
    }

    @Test
    fun eachModeKeepsItsOwnBoard() = runBlocking {
        val repo = FakeRepository()
        repo.submitScore("A", 500, GameMode.CLASSIC)
        repo.submitScore("B", 90, GameMode.HIGHWAY)

        assertEquals(listOf("A"), repo.fetchTopScores(10, GameMode.CLASSIC).map { it.name })
        assertEquals(listOf("B"), repo.fetchTopScores(10, GameMode.HIGHWAY).map { it.name })
    }

    @Test
    fun qualifyingIsJudgedAgainstThatModesBoardOnly() = runBlocking {
        val repo = FakeRepository()
        repeat(10) { repo.submitScore("P$it", 1000 + it, GameMode.CLASSIC) }

        assertFalse(repo.qualifiesForTop(50, 10, GameMode.CLASSIC))
        assertTrue("an empty highway board always has room", repo.qualifiesForTop(50, 10, GameMode.HIGHWAY))
    }

    @Test
    fun modesUseSeparateCollections_withClassicKeepingTheOriginal() {
        assertEquals("scores", leaderboardCollection(GameMode.CLASSIC))
        assertEquals("scores_highway", leaderboardCollection(GameMode.HIGHWAY))
    }
}
