package com.worldexplorer.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.worldexplorer.data.backup.BackupRepositoryImpl
import com.worldexplorer.data.backup.ContentResolverDocumentStreams
import com.worldexplorer.data.backup.DocumentStreams
import com.worldexplorer.data.database.DatabaseMigrations
import com.worldexplorer.data.database.WorldExplorerDatabase
import com.worldexplorer.data.location.ConnectivityRepositoryImpl
import com.worldexplorer.data.location.LocationClient
import com.worldexplorer.data.location.LocationClientFactory
import com.worldexplorer.data.location.LocationRepositoryImpl
import com.worldexplorer.data.repository.ExplorationRepositoryImpl
import com.worldexplorer.data.repository.TrackRepositoryImpl
import com.worldexplorer.data.settings.SettingsRepositoryImpl
import com.worldexplorer.data.sync.AuthBackend
import com.worldexplorer.data.sync.CloudBackend
import com.worldexplorer.data.sync.KeystoreSessionStore
import com.worldexplorer.data.sync.SessionStore
import com.worldexplorer.data.sync.SupabaseBackend
import com.worldexplorer.data.sync.SupabaseConfig
import com.worldexplorer.data.sync.SyncBackend
import com.worldexplorer.data.sync.SyncRepositoryImpl
import com.worldexplorer.data.sync.UnconfiguredBackend
import com.worldexplorer.data.sync.WorkManagerSyncScheduler
import com.worldexplorer.domain.AppDispatchers
import com.worldexplorer.domain.repository.BackupRepository
import com.worldexplorer.domain.repository.ConnectivityRepository
import com.worldexplorer.domain.repository.ExplorationRepository
import com.worldexplorer.domain.repository.LocationRepository
import com.worldexplorer.domain.repository.SettingsRepository
import com.worldexplorer.domain.repository.SyncRepository
import com.worldexplorer.domain.repository.SyncScheduler
import com.worldexplorer.domain.repository.TrackRepository
import com.worldexplorer.domain.tracking.Clock
import com.worldexplorer.domain.tracking.IdGenerator
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataProvidesModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): WorldExplorerDatabase =
        Room.databaseBuilder(context, WorldExplorerDatabase::class.java, WorldExplorerDatabase.NAME)
            .addMigrations(*DatabaseMigrations.ALL)
            .build()

    @Provides
    @Singleton
    fun settingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("settings") })

    @Provides
    fun clock(): Clock = Clock { System.currentTimeMillis() }

    @Provides
    fun ids(): IdGenerator = IdGenerator { UUID.randomUUID().toString() }

    @Provides
    @Singleton
    fun dispatchers() = AppDispatchers(io = Dispatchers.IO, default = Dispatchers.Default)

    @Provides
    @Singleton
    fun locationClient(@ApplicationContext context: Context): LocationClient = LocationClientFactory.create(context)

    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun cloudBackend(config: SupabaseConfig, http: OkHttpClient, sessions: SessionStore, clock: Clock): CloudBackend =
        if (config.isConfigured) SupabaseBackend(config, http, sessions, clock) else UnconfiguredBackend()

    @Provides
    fun authBackend(backend: CloudBackend): AuthBackend = backend

    @Provides
    fun syncBackend(backend: CloudBackend): SyncBackend = backend
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindsModule {
    @Binds abstract fun tracks(impl: TrackRepositoryImpl): TrackRepository
    @Binds abstract fun exploration(impl: ExplorationRepositoryImpl): ExplorationRepository
    @Binds abstract fun settings(impl: SettingsRepositoryImpl): SettingsRepository
    @Binds abstract fun location(impl: LocationRepositoryImpl): LocationRepository
    @Binds abstract fun connectivity(impl: ConnectivityRepositoryImpl): ConnectivityRepository
    @Binds abstract fun backup(impl: BackupRepositoryImpl): BackupRepository
    @Binds abstract fun documents(impl: ContentResolverDocumentStreams): DocumentStreams
    @Binds abstract fun sync(impl: SyncRepositoryImpl): SyncRepository
    @Binds abstract fun scheduler(impl: WorkManagerSyncScheduler): SyncScheduler
    @Binds abstract fun sessions(impl: KeystoreSessionStore): SessionStore
}
