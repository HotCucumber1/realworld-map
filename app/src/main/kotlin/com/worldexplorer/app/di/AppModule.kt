package com.worldexplorer.app.di

import com.worldexplorer.app.BuildConfig
import com.worldexplorer.app.tracking.TrackingServiceController
import com.worldexplorer.data.backup.AppBuildInfo
import com.worldexplorer.data.sync.SupabaseConfig
import com.worldexplorer.domain.repository.TrackingController
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** Process-wide scope for work that must outlive a screen or the service (e.g. final writes). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppProvidesModule {
    @Provides
    @Singleton
    fun supabaseConfig() = SupabaseConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)

    @Provides
    fun buildInfo() = AppBuildInfo(BuildConfig.VERSION_NAME)

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindsModule {
    @Binds abstract fun trackingController(impl: TrackingServiceController): TrackingController
}
