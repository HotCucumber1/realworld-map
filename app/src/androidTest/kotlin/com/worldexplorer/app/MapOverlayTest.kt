package com.worldexplorer.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.worldexplorer.app.feature.map.MapOverlay
import com.worldexplorer.app.feature.map.MapUiState
import com.worldexplorer.app.feature.map.PermissionStatus
import com.worldexplorer.app.feature.map.RecordingUi
import com.worldexplorer.app.ui.theme.WorldExplorerTheme
import com.worldexplorer.domain.model.GpsSignal
import com.worldexplorer.domain.model.MapMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The map controls are tested without a real map (no network, no GPS). */
@RunWith(AndroidJUnit4::class)
class MapOverlayTest {
    @get:Rule val compose = createComposeRule()
    private val clicks = mutableListOf<String>()

    private fun show(state: MapUiState) = compose.setContent {
        WorldExplorerTheme {
            MapOverlay(
                state = state,
                onRecord = { clicks += "record" },
                onToggleMode = { clicks += "mode" },
                onCenter = { clicks += "center" },
                onHistory = { clicks += "history" },
                onSettings = { clicks += "settings" },
                onRequestPermission = { clicks += "permission" },
                onOpenLocationSettings = { clicks += "gps" },
                onRetryMap = { clicks += "retry" },
            )
        }
    }

    @Test
    fun missingPermissionShowsBannerThatRequestsIt() {
        show(MapUiState(permission = PermissionStatus.DENIED))
        compose.onNodeWithTag("permission_banner").assertIsDisplayed()
        compose.onNodeWithText("Нет доступа к геолокации").assertIsDisplayed()
        compose.onNodeWithText("Разрешить").performClick()
        assertEquals(listOf("permission"), clicks)
    }

    @Test
    fun gpsOffAndOfflineAreIndicated() {
        show(MapUiState(permission = PermissionStatus.GRANTED, gpsEnabled = false, online = false))
        compose.onNodeWithTag("gps_off_banner").assertIsDisplayed()
        compose.onNodeWithTag("offline_status").assertIsDisplayed()
        compose.onNodeWithText("Открыть настройки").performClick()
        assertEquals(listOf("gps"), clicks)
    }

    @Test
    fun idleStateOffersStartAndShowsExploredArea() {
        show(MapUiState(permission = PermissionStatus.GRANTED, exploredAreaSquareMeters = 1234.0))
        compose.onNodeWithContentDescription("Начать запись").performClick()
        compose.onNodeWithTag("explored_area").assertIsDisplayed()
        // Russian grouping separator is a no-break space.
        compose.onNodeWithText("1\u00A0234 м²", useUnmergedTree = true).assertExists()
        assertEquals(listOf("record"), clicks)
    }

    @Test
    fun recordingShowsStopDistanceAndLostSignal() {
        show(
            MapUiState(
                permission = PermissionStatus.GRANTED,
                recording = RecordingUi("t", System.currentTimeMillis() - 65_000, 1520.0, 80, GpsSignal.LOST),
            ),
        )
        compose.onNodeWithContentDescription("Остановить запись").assertIsDisplayed()
        compose.onNodeWithText("1,52 км", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Нет сигнала GPS").assertIsDisplayed()
    }

    @Test
    fun modeToggleShowsCurrentModeAndNavigationButtonsWork() {
        show(MapUiState(permission = PermissionStatus.GRANTED, mapMode = MapMode.EXPLORER))
        compose.onNodeWithText("Карта").assertIsDisplayed()
        compose.onNodeWithTag("mode_toggle").performClick()
        compose.onNodeWithTag("history_button").performClick()
        compose.onNodeWithTag("settings_button").performClick()
        compose.onNodeWithTag("center_button").performClick()
        assertEquals(listOf("mode", "history", "settings", "center"), clicks)
    }
}
