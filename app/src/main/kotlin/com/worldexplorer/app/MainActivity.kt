package com.worldexplorer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.worldexplorer.app.ui.navigation.AppNavHost
import com.worldexplorer.app.ui.theme.WorldExplorerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Keep the splash until we know whether onboarding is needed (avoids a flash of the wrong screen).
        splash.setKeepOnScreenCondition { viewModel.startDestination.value == null }
        enableEdgeToEdge()
        setContent {
            WorldExplorerTheme {
                val start by viewModel.startDestination.collectAsStateWithLifecycle()
                start?.let { AppNavHost(startAtOnboarding = it == StartDestination.ONBOARDING) }
            }
        }
    }
}
