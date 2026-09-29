package com.khcompany.lanedash

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.khcompany.lanedash.ads.AdManager
import com.khcompany.lanedash.game.GameCatalog
import com.khcompany.lanedash.game.GameSessionViewModel
import com.khcompany.lanedash.ui.screens.CarSelectScreen
import com.khcompany.lanedash.ui.screens.CitySelectScreen
import com.khcompany.lanedash.ui.screens.GameScreen
import com.khcompany.lanedash.ui.screens.HomeScreen
import com.khcompany.lanedash.ui.screens.LeaderboardScreen

private object Routes {
    const val HOME = "home"
    const val CITIES = "cities"
    const val CARS = "cars"
    const val GAME = "game"
    const val LEADERBOARD = "leaderboard"
}

@Composable
fun LaneDashApp(adManager: AdManager, modifier: Modifier = Modifier) {
    val navController: NavHostController = rememberNavController()
    val session: GameSessionViewModel = viewModel()

    NavHost(navController = navController, startDestination = Routes.HOME, modifier = modifier) {
        composable(Routes.HOME) {
            HomeScreen(
                highScore = session.highScore,
                onPlay = { navController.navigate(Routes.CITIES) },
            )
        }
        composable(Routes.CITIES) {
            CitySelectScreen(
                selectedCityId = session.selectedCityId,
                onSelect = { session.selectedCityId = it },
                onNext = { navController.navigate(Routes.CARS) },
            )
        }
        composable(Routes.CARS) {
            CarSelectScreen(
                selectedCarId = session.selectedCarId,
                onSelect = { session.selectedCarId = it },
                onStart = { navController.navigate(Routes.GAME) },
            )
        }
        composable(Routes.GAME) {
            GameScreen(
                city = GameCatalog.cityById(session.selectedCityId),
                car = GameCatalog.carById(session.selectedCarId),
                highScore = session.highScore,
                leaderboardRepository = session.leaderboardRepository,
                adManager = adManager,
                onScoreFinal = { session.reportScore(it) },
                onGameOverAndShouldShowInterstitial = { session.onGameOverAndShouldShowInterstitial() },
                onSubmitScore = { name ->
                    session.submitToLeaderboard(name) {
                        navController.navigate(Routes.LEADERBOARD)
                    }
                },
                onViewLeaderboard = {
                    session.clearJustSubmitted()
                    navController.navigate(Routes.LEADERBOARD)
                },
                onExit = {
                    navController.popBackStack(Routes.HOME, inclusive = false)
                },
            )
        }
        composable(Routes.LEADERBOARD) {
            LeaderboardScreen(
                repository = session.leaderboardRepository,
                highlightName = session.justSubmittedName,
                highlightScore = session.lastRunScore,
                onHome = {
                    navController.popBackStack(Routes.HOME, inclusive = false)
                },
            )
        }
    }
}
