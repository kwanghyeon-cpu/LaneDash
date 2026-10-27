package com.khcompany.lanedash.game

data class LeaderboardEntry(
    val name: String,
    val score: Int,
)

/** Each [GameMode] has its own board — scores from the two modes aren't comparable. */
interface LeaderboardRepository {
    suspend fun fetchTopScores(limit: Int = 10, mode: GameMode = GameMode.CLASSIC): List<LeaderboardEntry>
    suspend fun submitScore(name: String, score: Int, mode: GameMode = GameMode.CLASSIC)

    /** True if [score] would land inside the top [limit] entries of [mode]'s board. */
    suspend fun qualifiesForTop(score: Int, limit: Int = 10, mode: GameMode = GameMode.CLASSIC): Boolean {
        val top = fetchTopScores(limit, mode)
        return top.size < limit || score > top.last().score
    }
}
