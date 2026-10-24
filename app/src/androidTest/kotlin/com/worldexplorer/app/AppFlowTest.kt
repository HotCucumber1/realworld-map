package com.worldexplorer.app

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.worldexplorer.app.di.AppEntryPoint
import com.worldexplorer.domain.model.MapMode
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end through the real app (Hilt graph, Room, DataStore, MapLibre). Does not assert map
 * tiles, so it passes offline; location permission is pre-granted to avoid the system dialog.
 */
@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val permissions: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33) {
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private val hooks = EntryPointAccessors.fromApplication(ApplicationProvider.getApplicationContext(), AppEntryPoint::class.java)
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun freshUser() = runBlocking {
        hooks.settings().update { it.copy(onboardingCompleted = false, mapMode = MapMode.FOG_OF_WAR) }
    }

    @After
    fun close() { scenario?.close() }

    @Test
    fun onboardingLeadsToMapAndModeSwitchPersists() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("onboarding_pager").assertIsDisplayed()
        repeat(3) { compose.onNodeWithTag("onboarding_next").performClick() }
        compose.onNodeWithText("Начать исследование").performClick()

        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("record_button")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("explored_area").assertIsDisplayed()
        compose.onNodeWithText("Туман").assertIsDisplayed()

        compose.onNodeWithTag("mode_toggle").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Карта")).fetchSemanticsNodes().isNotEmpty() }
        val settings = runBlocking { hooks.settings().current() }
        assertEquals(MapMode.EXPLORER, settings.mapMode)
        assertEquals(true, settings.onboardingCompleted)
    }

    @Test
    fun historyAndSettingsOpenFromTheMap() {
        runBlocking { hooks.settings().update { it.copy(onboardingCompleted = true) } }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("history_button")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history_button").performClick()
        compose.onNodeWithText("История маршрутов").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("settings_button")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("settings_button").performClick()
        compose.onNodeWithTag("settings_list").assertIsDisplayed()
        compose.onNodeWithText("Радиус открытия территории").assertExists()
    }
}
