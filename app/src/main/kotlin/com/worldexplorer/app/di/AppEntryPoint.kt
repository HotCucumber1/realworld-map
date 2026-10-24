package com.worldexplorer.app.di

import com.worldexplorer.domain.repository.SettingsRepository
import com.worldexplorer.domain.repository.TrackRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Access to singletons from code Hilt cannot inject (instrumented end-to-end tests). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppEntryPoint {
    fun settings(): SettingsRepository
    fun tracks(): TrackRepository
}
