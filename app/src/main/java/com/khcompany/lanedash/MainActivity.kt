package com.khcompany.lanedash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.khcompany.lanedash.ads.AdManager
import com.khcompany.lanedash.ui.theme.LaneDashTheme

class MainActivity : ComponentActivity() {
    val adManager by lazy { AdManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()
        adManager.init()
        setContent {
            LaneDashTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    LaneDashApp(adManager = adManager)
                }
            }
        }
    }

    // A game screen shouldn't share its bottom edge with the system nav bar — on 3-button
    // nav devices the bar sits on top of the lane control buttons. Full immersive mode
    // hides both system bars; a swipe from the edge still reveals them temporarily if the
    // player needs to, e.g., use the phone's back gesture.
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Swiping to reveal the bars, or returning from another app/dialog, can un-hide
        // them — re-apply whenever the window regains focus so it stays immersive.
        if (hasFocus) hideSystemBars()
    }
}
