package com.worldexplorer.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.worldexplorer.domain.model.AppSettings
import com.worldexplorer.domain.model.MapMode
import com.worldexplorer.domain.model.MapStyle
import com.worldexplorer.domain.model.TrackingProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsRepositoryImplTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun close() = scope.cancel()

    private fun store(file: File) = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })

    @Test
    fun `defaults when nothing is stored`() = runTest {
        assertEquals(AppSettings(), SettingsRepositoryImpl(store(File(tmp.root, "a.preferences_pb"))).current())
    }

    @Test
    fun `updates are persisted`() = runTest {
        val file = File(tmp.root, "b.preferences_pb")
        val repo = SettingsRepositoryImpl(store(file))
        repo.update { it.copy(mapMode = MapMode.EXPLORER, exploreRadiusMeters = 100, trackingProfile = TrackingProfile.PRECISE, mapStyle = MapStyle.CUSTOM, customStyleUrl = "https://example.com/style.json", onboardingCompleted = true) }
        val read = repo.current()
        assertEquals(MapMode.EXPLORER, read.mapMode)
        assertEquals(100, read.exploreRadiusMeters)
        assertEquals(TrackingProfile.PRECISE, read.trackingProfile)
        assertEquals("https://example.com/style.json", read.customStyleUrl)
        assertEquals(true, read.onboardingCompleted)
    }

    @Test
    fun `unknown or invalid stored values fall back to defaults`() = runTest {
        val ds = store(File(tmp.root, "c.preferences_pb"))
        ds.edit {
            it[stringPreferencesKey("map_mode")] = "SATELLITE"
            it[intPreferencesKey("explore_radius_m")] = 7
        }
        val s = SettingsRepositoryImpl(ds).current()
        assertEquals(MapMode.FOG_OF_WAR, s.mapMode)
        assertEquals(20, s.exploreRadiusMeters)
    }
}
