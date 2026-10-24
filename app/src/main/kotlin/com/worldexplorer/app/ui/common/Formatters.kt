package com.worldexplorer.app.ui.common

import android.content.Context
import com.worldexplorer.app.R
import com.worldexplorer.domain.result.AppError
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Human-readable values. Russian locale formatting (decimal comma, local time zone). */
object Formatters {
    private val locale = Locale.forLanguageTag("ru")
    private val oneDecimal = DecimalFormat("0.0", DecimalFormatSymbols(locale))
    private val twoDecimals = DecimalFormat("0.00", DecimalFormatSymbols(locale))
    private val integer = DecimalFormat("#,##0", DecimalFormatSymbols(locale))
    private val dateTime = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale)
    private val time = DateTimeFormatter.ofPattern("HH:mm", locale)

    fun distance(context: Context, meters: Double): String = when {
        meters < 1_000 -> context.getString(R.string.unit_m, integer.format(meters))
        meters < 100_000 -> context.getString(R.string.unit_km, twoDecimals.format(meters / 1_000))
        else -> context.getString(R.string.unit_km, oneDecimal.format(meters / 1_000))
    }

    fun area(context: Context, squareMeters: Double): String = when {
        squareMeters < 10_000 -> context.getString(R.string.unit_m2, integer.format(squareMeters))
        squareMeters < 10_000_000 -> context.getString(R.string.unit_km2, DecimalFormat("0.000", DecimalFormatSymbols(locale)).format(squareMeters / 1e6))
        else -> context.getString(R.string.unit_km2, oneDecimal.format(squareMeters / 1e6))
    }

    fun duration(context: Context, millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return when {
            h > 0 -> context.getString(R.string.duration_hm, h.toInt(), m.toInt())
            m > 0 -> context.getString(R.string.duration_ms, m.toInt(), s.toInt())
            else -> context.getString(R.string.duration_s, s.toInt())
        }
    }

    fun dateTime(utcMillis: Long): String = dateTime.format(Instant.ofEpochMilli(utcMillis).atZone(ZoneId.systemDefault()))
    fun time(utcMillis: Long): String = time.format(Instant.ofEpochMilli(utcMillis).atZone(ZoneId.systemDefault()))
    fun count(value: Long): String = integer.format(value)
    fun accuracy(context: Context, meters: Double?): String = meters?.let { context.getString(R.string.unit_m, oneDecimal.format(it)) } ?: "—"
    fun percent(share: Double?): String = share?.let { "${(it * 100).toInt()} %" } ?: "—"

    fun error(context: Context, error: AppError): String = when (error) {
        AppError.LocationPermissionDenied -> context.getString(R.string.error_permission)
        AppError.LocationDisabled -> context.getString(R.string.error_location_disabled)
        AppError.AlreadyTracking -> context.getString(R.string.error_already_tracking)
        AppError.NotTracking -> context.getString(R.string.error_not_tracking)
        is AppError.ServiceStartFailed -> context.getString(R.string.error_service, error.reason)
        AppError.NoNetwork -> context.getString(R.string.error_no_network)
        AppError.SyncNotConfigured -> context.getString(R.string.error_sync_not_configured)
        AppError.NotSignedIn -> context.getString(R.string.error_not_signed_in)
        AppError.SessionExpired -> context.getString(R.string.error_session_expired)
        is AppError.InvalidCredentials -> context.getString(R.string.error_invalid_credentials, error.reason)
        is AppError.BackendUnavailable -> context.getString(R.string.error_backend_unavailable, error.reason)
        is AppError.BackendRejected -> context.getString(R.string.error_backend_rejected, error.httpCode)
        is AppError.Database -> context.getString(R.string.error_database)
        AppError.StorageFull -> context.getString(R.string.error_storage_full)
        is AppError.CorruptedBackup -> context.getString(R.string.error_backup_corrupted, error.reason)
        is AppError.UnsupportedBackupVersion -> context.getString(R.string.error_backup_version, error.found, error.supported)
        is AppError.BackupTooLarge -> context.getString(R.string.error_backup_too_large)
        is AppError.FileAccess -> context.getString(R.string.error_file_access, error.reason)
        is AppError.Validation -> context.getString(R.string.error_validation)
        is AppError.Unexpected -> context.getString(R.string.error_unexpected, error.reason)
    }
}
