package com.worldexplorer.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.worldexplorer.app.feature.backup.BackupScreen
import com.worldexplorer.app.feature.backup.BackupUiState
import com.worldexplorer.app.feature.backup.PendingImport
import com.worldexplorer.app.feature.history.HistoryScreen
import com.worldexplorer.app.feature.history.HistoryUiState
import com.worldexplorer.app.feature.settings.SettingsEvent
import com.worldexplorer.app.feature.settings.SettingsScreen
import com.worldexplorer.app.feature.settings.SettingsUiState
import com.worldexplorer.app.ui.theme.WorldExplorerTheme
import com.worldexplorer.domain.backup.BackupInfo
import com.worldexplorer.domain.model.AppSettings
import com.worldexplorer.domain.model.MapMode
import com.worldexplorer.domain.model.Track
import com.worldexplorer.domain.model.TrackStatus
import com.worldexplorer.domain.model.TrackingProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun emptyHistoryExplainsWhatToDo() {
        compose.setContent { WorldExplorerTheme { HistoryScreen(HistoryUiState.Loaded(emptyList()), {}, {}) } }
        compose.onNodeWithTag("history_empty").assertIsDisplayed()
        compose.onNodeWithText("Пока нет маршрутов").assertIsDisplayed()
    }

    @Test
    fun historyListsTracksAndOpensOne() {
        val now = System.currentTimeMillis()
        val tracks = listOf(
            Track("live", now - 60_000, null, TrackStatus.ACTIVE, 250.0, 20, 0, now, now - 60_000, now),
            Track("old", now - 86_400_000, now - 86_000_000, TrackStatus.COMPLETED, 4200.0, 300, 3, now - 86_000_000, now - 86_400_000, now - 86_000_000),
        )
        var opened: String? = null
        compose.setContent { WorldExplorerTheme { HistoryScreen(HistoryUiState.Loaded(tracks), {}, { opened = it }) } }
        compose.onNodeWithText("Идёт запись").assertIsDisplayed()
        compose.onNodeWithText("4,20 км", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("track_old").performClick()
        assertEquals("old", opened)
    }

    @Test
    fun settingsEmitEventsForRadiusModeAndProfile() {
        val events = mutableListOf<SettingsEvent>()
        compose.setContent {
            WorldExplorerTheme { SettingsScreen(SettingsUiState(AppSettings(), loaded = true), false, { events += it }, {}, {}, {}, {}, {}) }
        }
        compose.onNodeWithTag("settings_mode_EXPLORER").performClick()
        compose.onNodeWithTag("radius_50").performScrollTo().performClick()
        compose.onNodeWithTag("profile_PRECISE").performScrollTo().performClick()
        assertEquals(
            listOf(SettingsEvent.SetMapMode(MapMode.EXPLORER), SettingsEvent.SetRadius(50), SettingsEvent.SetProfile(TrackingProfile.PRECISE)),
            events,
        )
    }

    @Test
    fun settingsShowCloudAndBackupEntries() {
        var backup = false
        compose.setContent {
            WorldExplorerTheme { SettingsScreen(SettingsUiState(AppSettings(), loaded = true, cloudConfigured = false), true, {}, {}, { backup = true }, {}, {}, {}) }
        }
        compose.onNodeWithText("Не настроена в сборке").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("open_backup").performScrollTo().performClick()
        assertTrue(backup)
    }

    @Test
    fun replaceImportNeedsExplicitConfirmation() {
        val info = BackupInfo(1, 1, "0.1.0", System.currentTimeMillis(), 2, 50, 300, true)
        var confirmed = false
        compose.setContent {
            WorldExplorerTheme {
                BackupScreen(
                    BackupUiState(pending = PendingImport("content://x", info), confirmReplace = true),
                    {}, {}, {}, {}, {}, { confirmed = true }, {}, {},
                )
            }
        }
        compose.onNodeWithTag("replace_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("replace_understood").performClick()
        compose.onNodeWithTag("replace_confirm").assertIsEnabled().performClick()
        assertTrue(confirmed)
    }

    @Test
    fun importDialogOffersMergeAndReplace() {
        val info = BackupInfo(1, 1, "0.1.0", System.currentTimeMillis(), 2, 50, 300, true)
        var merged = false
        var replace = false
        compose.setContent {
            WorldExplorerTheme {
                BackupScreen(BackupUiState(pending = PendingImport("content://x", info)), {}, {}, {}, { merged = true }, { replace = true }, {}, {}, {})
            }
        }
        compose.onNodeWithTag("import_merge").performClick()
        compose.onNodeWithTag("import_replace").performClick()
        assertTrue(merged && replace)
    }
}
