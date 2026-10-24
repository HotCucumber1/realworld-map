package com.worldexplorer.domain.sync

data class CloudAccount(val userId: String, val email: String)

data class SyncStatus(
    /** False when the app was built without backend credentials. */
    val configured: Boolean,
    val account: CloudAccount?,
    val running: Boolean,
    val lastSuccessAtUtc: Long?,
    val lastAttemptAtUtc: Long?,
    val lastError: String?,
    val pendingTracks: Int,
    val pendingPoints: Long,
    val pendingCells: Long,
)

data class SyncReport(
    val pushedTracks: Int,
    val pushedPoints: Long,
    val pushedCells: Long,
    val pulledTracks: Int,
    val pulledPoints: Long,
    val pulledCells: Long,
)

enum class SignUpOutcome {
    /** Account created and signed in. */
    SIGNED_IN,
    /** Account created; the backend sent a confirmation e-mail that must be opened first. */
    CONFIRMATION_REQUIRED,
}
