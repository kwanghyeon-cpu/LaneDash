package com.khcompany.lanedash.game

import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * One Firestore collection per mode. The classic board keeps the original "scores" collection so
 * existing entries (and the web version, which shares it) are untouched.
 */
internal fun leaderboardCollection(mode: GameMode): String = when (mode) {
    GameMode.CLASSIC -> "scores"
    GameMode.HIGHWAY -> "scores_highway"
}

/** Global leaderboard backed by Firestore — every entry here is shared across all players. */
class FirestoreLeaderboardRepository : LeaderboardRepository {
    private val db = FirebaseFirestore.getInstance()

    override suspend fun fetchTopScores(limit: Int, mode: GameMode): List<LeaderboardEntry> {
        val snapshot = db.collection(leaderboardCollection(mode))
            .orderBy("score", Query.Direction.DESCENDING)
            .limit(limit.toLong())
            .get()
            .await()
        return snapshot.documents.mapNotNull { doc ->
            val name = doc.getString("name") ?: return@mapNotNull null
            val score = doc.getLong("score")?.toInt() ?: return@mapNotNull null
            LeaderboardEntry(name, score)
        }
    }

    override suspend fun submitScore(name: String, score: Int, mode: GameMode) {
        val data = hashMapOf(
            "name" to name.trim().take(12).ifBlank { "PLAYER" },
            "score" to score,
        )
        db.collection(leaderboardCollection(mode)).add(data).await()
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}
