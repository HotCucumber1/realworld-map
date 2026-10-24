package com.worldexplorer.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.worldexplorer.app.feature.backup.BackupRoute
import com.worldexplorer.app.feature.history.HistoryRoute
import com.worldexplorer.app.feature.history.TrackDetailRoute
import com.worldexplorer.app.feature.map.MapRoute
import com.worldexplorer.app.feature.onboarding.OnboardingRoute
import com.worldexplorer.app.feature.settings.SettingsRoute
import com.worldexplorer.app.feature.sync.CloudSyncRoute
import kotlinx.serialization.Serializable

@Serializable data object OnboardingDest
@Serializable data object MapDest
@Serializable data object HistoryDest
@Serializable data class TrackDetailDest(val trackId: String)
@Serializable data object SettingsDest
@Serializable data object BackupDest
@Serializable data object CloudDest

@Composable
fun AppNavHost(startAtOnboarding: Boolean) {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = if (startAtOnboarding) OnboardingDest else MapDest,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        composable<OnboardingDest> {
            OnboardingRoute(onFinished = {
                nav.navigate(MapDest) { popUpTo(OnboardingDest) { inclusive = true } }
            })
        }
        composable<MapDest> {
            MapRoute(onOpenHistory = { nav.navigate(HistoryDest) }, onOpenSettings = { nav.navigate(SettingsDest) })
        }
        composable<HistoryDest> {
            HistoryRoute(onBack = { nav.popBackStack() }, onOpenTrack = { nav.navigate(TrackDetailDest(it)) })
        }
        composable<TrackDetailDest> { entry ->
            TrackDetailRoute(trackId = entry.toRoute<TrackDetailDest>().trackId, onBack = { nav.popBackStack() })
        }
        composable<SettingsDest> {
            SettingsRoute(onBack = { nav.popBackStack() }, onOpenBackup = { nav.navigate(BackupDest) }, onOpenCloud = { nav.navigate(CloudDest) })
        }
        composable<BackupDest> { BackupRoute(onBack = { nav.popBackStack() }) }
        composable<CloudDest> { CloudSyncRoute(onBack = { nav.popBackStack() }) }
    }
}
