package com.worldexplorer.domain.usecase

import com.worldexplorer.domain.model.AppSettings
import com.worldexplorer.domain.model.ExploreRadius
import com.worldexplorer.domain.model.MapMode
import com.worldexplorer.domain.repository.SettingsRepository
import com.worldexplorer.domain.repository.SyncScheduler
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveSettingsUseCase @Inject constructor(private val settings: SettingsRepository) {
    operator fun invoke(): Flow<AppSettings> = settings.settings
}

/**
 * Changes only the display mode. Tracks and explored cells are untouched: both modes render the
 * same territory data.
 */
class SwitchMapModeUseCase @Inject constructor(private val settings: SettingsRepository) {
    suspend operator fun invoke(mode: MapMode) = settings.update { it.copy(mapMode = mode) }
    suspend fun toggle() = settings.update {
        it.copy(mapMode = if (it.mapMode == MapMode.FOG_OF_WAR) MapMode.EXPLORER else MapMode.FOG_OF_WAR)
    }
}

class UpdateSettingsUseCase @Inject constructor(
    private val settings: SettingsRepository,
    private val syncScheduler: SyncScheduler,
) {
    suspend operator fun invoke(transform: (AppSettings) -> AppSettings) {
        val before = settings.current()
        settings.update { transform(it).let { s -> s.copy(exploreRadiusMeters = ExploreRadius.sanitize(s.exploreRadiusMeters), customStyleUrl = s.customStyleUrl.trim()) } }
        val after = settings.current()
        if (before.syncEnabled != after.syncEnabled || before.autoSync != after.autoSync || before.syncOnlyOnUnmetered != after.syncOnlyOnUnmetered) {
            if (after.syncEnabled && after.autoSync) syncScheduler.schedulePeriodic(after.syncOnlyOnUnmetered) else syncScheduler.cancelPeriodic()
        }
    }
}
