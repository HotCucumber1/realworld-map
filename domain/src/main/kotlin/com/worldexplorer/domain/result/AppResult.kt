package com.worldexplorer.domain.result

/** Typed outcome of a business operation. Expected failures are values, not exceptions. */
sealed interface AppResult<out T> {
    data class Success<out T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(value))
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.onSuccess(block: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) block(value)
    return this
}

inline fun <T> AppResult<T>.onFailure(block: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) block(error)
    return this
}

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success)?.value

/** Error categories the UI knows how to explain to the user. */
sealed interface AppError {
    data object LocationPermissionDenied : AppError
    data object LocationDisabled : AppError
    data object AlreadyTracking : AppError
    data object NotTracking : AppError
    data class ServiceStartFailed(val reason: String) : AppError

    data object NoNetwork : AppError
    data object SyncNotConfigured : AppError
    data object NotSignedIn : AppError
    /** The session expired and could not be refreshed: the user must sign in again. */
    data object SessionExpired : AppError
    data class InvalidCredentials(val reason: String) : AppError
    data class BackendUnavailable(val reason: String) : AppError
    data class BackendRejected(val httpCode: Int, val reason: String) : AppError

    data class Database(val reason: String) : AppError
    data object StorageFull : AppError

    data class CorruptedBackup(val reason: String) : AppError
    data class UnsupportedBackupVersion(val found: Int, val supported: Int) : AppError
    data class BackupTooLarge(val limitBytes: Long) : AppError
    data class FileAccess(val reason: String) : AppError

    data class Validation(val reason: String) : AppError
    data class Unexpected(val reason: String) : AppError
}
