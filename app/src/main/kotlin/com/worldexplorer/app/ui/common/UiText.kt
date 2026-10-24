package com.worldexplorer.app.ui.common

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.worldexplorer.domain.result.AppError

/**
 * Text produced by a ViewModel without touching Android resources there; resolved in the UI.
 * Keeps ViewModels unit-testable on the JVM.
 */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Error(val error: AppError) : UiText
    data class Distance(val meters: Double) : UiText

    fun resolve(context: Context): String = when (this) {
        is Res -> context.getString(id, *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray())
        is Error -> Formatters.error(context, error)
        is Distance -> Formatters.distance(context, meters)
    }
}

@Composable
fun UiText.asString(): String = resolve(LocalContext.current)
