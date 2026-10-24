package com.worldexplorer.data.settings

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.worldexplorer.domain.model.AppSettings
import com.worldexplorer.domain.model.ExploreRadius
import com.worldexplorer.domain.model.FogStyle
import com.worldexplorer.domain.model.MapStyle
import com.worldexplorer.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e("WorldExplorer", "settings read failed, using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map(::fromPreferences)
        .distinctUntilChanged()

    override suspend fun current(): AppSettings = settings.first()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val next = transform(fromPreferences(prefs))
            write(prefs, next)
        }
    }

    private fun write(p: androidx.datastore.preferences.core.MutablePreferences, s: AppSettings) {
        p[Keys.MAP_MODE] = s.mapMode.name
        p[Keys.RADIUS] = ExploreRadius.sanitize(s.exploreRadiusMeters)
        p[Keys.PROFILE] = s.trackingProfile.name
        p[Keys.SHOW_TRACK] = s.showTrack
        p[Keys.SHOW_HIGHLIGHT] = s.showExploredHighlight
        p[Keys.FOG_STYLE] = s.fogStyle.name
        p[Keys.MAP_STYLE] = s.mapStyle.name
        p[Keys.CUSTOM_STYLE_URL] = s.customStyleUrl
        p[Keys.SYNC_ENABLED] = s.syncEnabled
        p[Keys.AUTO_SYNC] = s.autoSync
        p[Keys.SYNC_UNMETERED] = s.syncOnlyOnUnmetered
        p[Keys.ONBOARDING_DONE] = s.onboardingCompleted
    }

    private object Keys {
        val MAP_MODE = stringPreferencesKey("map_mode")
        val RADIUS = intPreferencesKey("explore_radius_m")
        val PROFILE = stringPreferencesKey("tracking_profile")
        val SHOW_TRACK = booleanPreferencesKey("show_track")
        val SHOW_HIGHLIGHT = booleanPreferencesKey("show_explored_highlight")
        val FOG_STYLE = stringPreferencesKey("fog_style")
        val MAP_STYLE = stringPreferencesKey("map_style")
        val CUSTOM_STYLE_URL = stringPreferencesKey("custom_style_url")
        val SYNC_ENABLED = booleanPreferencesKey("sync_enabled")
        val AUTO_SYNC = booleanPreferencesKey("auto_sync")
        val SYNC_UNMETERED = booleanPreferencesKey("sync_unmetered_only")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_completed")
    }

    companion object {
        private inline fun <reified E : Enum<E>> enumOr(value: String?, default: E): E =
            value?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default

        fun fromPreferences(p: Preferences): AppSettings {
            val d = AppSettings()
            return AppSettings(
                mapMode = enumOr(p[Keys.MAP_MODE], d.mapMode),
                exploreRadiusMeters = ExploreRadius.sanitize(p[Keys.RADIUS] ?: d.exploreRadiusMeters),
                trackingProfile = enumOr(p[Keys.PROFILE], d.trackingProfile),
                showTrack = p[Keys.SHOW_TRACK] ?: d.showTrack,
                showExploredHighlight = p[Keys.SHOW_HIGHLIGHT] ?: d.showExploredHighlight,
                fogStyle = enumOr<FogStyle>(p[Keys.FOG_STYLE], d.fogStyle),
                mapStyle = enumOr<MapStyle>(p[Keys.MAP_STYLE], d.mapStyle),
                customStyleUrl = p[Keys.CUSTOM_STYLE_URL] ?: d.customStyleUrl,
                syncEnabled = p[Keys.SYNC_ENABLED] ?: d.syncEnabled,
                autoSync = p[Keys.AUTO_SYNC] ?: d.autoSync,
                syncOnlyOnUnmetered = p[Keys.SYNC_UNMETERED] ?: d.syncOnlyOnUnmetered,
                onboardingCompleted = p[Keys.ONBOARDING_DONE] ?: d.onboardingCompleted,
            )
        }
    }
}

