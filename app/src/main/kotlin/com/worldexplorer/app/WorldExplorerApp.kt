package com.worldexplorer.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.worldexplorer.app.core.map.MapEnvironment
import com.worldexplorer.app.di.ApplicationScope
import com.worldexplorer.app.tracking.TrackingNotifications
import com.worldexplorer.domain.repository.SettingsRepository
import com.worldexplorer.domain.repository.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class WorldExplorerApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        TrackingNotifications.createChannels(this)
        MapEnvironment.init(this)
        appScope.launch {
            val s = settings.current()
            if (s.syncEnabled && s.autoSync) syncScheduler.schedulePeriodic(s.syncOnlyOnUnmetered)
        }
    }
}
