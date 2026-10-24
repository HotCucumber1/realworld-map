package com.worldexplorer.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.worldexplorer.domain.usecase.ObserveSettingsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class StartDestination { ONBOARDING, MAP }

@HiltViewModel
class MainViewModel @Inject constructor(observeSettings: ObserveSettingsUseCase) : ViewModel() {
    private val start = MutableStateFlow<StartDestination?>(null)
    /** Decided once per Activity instance; recreation (rotation) keeps the current back stack. */
    val startDestination: StateFlow<StartDestination?> = start.asStateFlow()

    init {
        viewModelScope.launch {
            val completed = observeSettings().first().onboardingCompleted
            start.value = if (completed) StartDestination.MAP else StartDestination.ONBOARDING
        }
    }
}
