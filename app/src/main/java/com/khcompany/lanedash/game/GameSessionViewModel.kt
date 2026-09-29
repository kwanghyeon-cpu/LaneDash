package com.khcompany.lanedash.game

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

private const val TAG = "Leaderboard"

private const val PREFS_NAME = "lane_dash_prefs"
private const val KEY_HIGH_SCORE = "high_score"
private const val KEY_GAMES_PLAYED = "games_played"

/** Show the game-over interstitial roughly this often, not on every single run. */
private const val INTERSTITIAL_EVERY_N_GAMES = 10

class GameSessionViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Application.MODE_PRIVATE)

    val leaderboardRepository: LeaderboardRepository = FirestoreLeaderboardRepository()

    var selectedCityId by mutableStateOf(GameCatalog.cities.first().id)
    var selectedCarId by mutableStateOf(GameCatalog.cars.first().id)

    var highScore by mutableIntStateOf(prefs.getInt(KEY_HIGH_SCORE, 0))
        private set

    /** Score from the run that just ended, so the leaderboard flow can reference it after nav. */
    var lastRunScore by mutableIntStateOf(0)
        private set

    /** Name just submitted for [lastRunScore], so the leaderboard screen can highlight it. */
    var justSubmittedName by mutableStateOf<String?>(null)
        private set

    private var gamesPlayed = prefs.getInt(KEY_GAMES_PLAYED, 0)

    fun reportScore(score: Int) {
        lastRunScore = score
        if (score > highScore) {
            highScore = score
            prefs.edit().putInt(KEY_HIGH_SCORE, score).apply()
        }
    }

    /** Call once per completed run. Returns true on every [INTERSTITIAL_EVERY_N_GAMES]th game. */
    fun onGameOverAndShouldShowInterstitial(): Boolean {
        gamesPlayed += 1
        prefs.edit().putInt(KEY_GAMES_PLAYED, gamesPlayed).apply()
        return gamesPlayed % INTERSTITIAL_EVERY_N_GAMES == 0
    }

    fun submitToLeaderboard(name: String, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                leaderboardRepository.submitScore(name, lastRunScore)
                justSubmittedName = name.trim().take(12).ifBlank { "PLAYER" }
            } catch (e: Exception) {
                Log.e(TAG, "submitScore failed", e)
            }
            onDone()
        }
    }

    /** Call before navigating to the leaderboard without submitting a new score. */
    fun clearJustSubmitted() {
        justSubmittedName = null
    }
}
