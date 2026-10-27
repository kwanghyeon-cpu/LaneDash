package com.khcompany.lanedash

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.khcompany.lanedash.ads.AdManager
import com.khcompany.lanedash.game.GameCatalog
import com.khcompany.lanedash.game.GameMode
import com.khcompany.lanedash.game.GameSessionViewModel
import com.khcompany.lanedash.ui.screens.CarSelectScreen
import com.khcompany.lanedash.ui.screens.CitySelectScreen
import com.khcompany.lanedash.ui.screens.GameScreen
import com.khcompany.lanedash.ui.screens.HighwayScreen
import com.khcompany.lanedash.ui.screens.HomeScreen
import com.khcompany.lanedash.ui.screens.LeaderboardScreen
import com.khcompany.lanedash.ui.screens.ModeSelectScreen

private object Routes {
    const val HOME = "home"
    const val MODES = "modes"
    const val CITIES = "cities"
    const val CARS = "cars"
    const val GAME = "game"
    const val HIGHWAY = "highway"
    const val LEADERBOARD = "leaderboard"
}

@Composable
fun LaneDashApp(adManager: AdManager, modifier: Modifier = Modifier) {
    val navController: NavHostController = rememberNavController()
    val session: GameSessionViewModel = viewModel()

    // Every screen is built for landscape except the behind-the-car mode, which is portrait.
    // The manifest starts the app landscape; this flips it per route (MainActivity handles the
    // orientation config change itself, so nothing is recreated and no state is lost).
    val activity = LocalContext.current as? Activity
    val route by navController.currentBackStackEntryAsState()
    val currentRoute = route?.destination?.route
    LaunchedEffect(currentRoute) {
        if (currentRoute != null) {
            activity?.requestedOrientation = if (currentRoute == Routes.HIGHWAY) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
    }

    NavHost(navController = navController, startDestination = Routes.HOME, modifier = modifier) {
        composable(Routes.HOME) {
            HomeScreen(
                highScore = session.highScore,
                onPlay = { navController.navigate(Routes.MODES) },
            )
        }
        composable(Routes.MODES) {
            ModeSelectScreen(
                selectedMode = session.selectedMode,
                onSelect = { session.selectedMode = it },
                onNext = { navController.navigate(Routes.CITIES) },
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
                onStart = {
                    navController.navigate(if (session.selectedMode == GameMode.HIGHWAY) Routes.HIGHWAY else Routes.GAME)
                },
            )
        }
        composable(Routes.GAME) {
            GameScreen(
                city = GameCatalog.cityById(session.selectedCityId),
                car = GameCatalog.carById(session.selectedCarId),
                highScore = session.highScore,
                playsToday = session.playsToday,
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
        composable(Routes.HIGHWAY) {
            HighwayScreen(
                city = GameCatalog.cityById(session.selectedCityId),
                car = GameCatalog.carById(session.selectedCarId),
                highScore = session.highwayHighScore,
                onScoreFinal = { session.reportHighwayScore(it) },
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
