package com.khcompany.lanedash.game

data class LeaderboardEntry(
    val name: String,
    val score: Int,
)

interface LeaderboardRepository {
    suspend fun fetchTopScores(limit: Int = 10): List<LeaderboardEntry>
    suspend fun submitScore(name: String, score: Int)

    /** True if [score] would land inside the top [limit] entries. */
    suspend fun qualifiesForTop(score: Int, limit: Int = 10): Boolean {
        val top = fetchTopScores(limit)
        return top.size < limit || score > top.last().score
    }
}
