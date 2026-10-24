package com.worldexplorer.data.repository

import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.util.Log
import com.worldexplorer.domain.result.AppError
import com.worldexplorer.domain.result.AppResult
import kotlinx.coroutines.CancellationException

internal const val TAG = "WorldExplorer"

/** Runs a database operation and maps storage exceptions to typed errors (logged, without user data). */
internal suspend inline fun <T> dbCall(operation: String, crossinline block: suspend () -> AppResult<T>): AppResult<T> =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SQLiteFullException) {
        Log.e(TAG, "$operation: storage full", e)
        AppResult.Failure(AppError.StorageFull)
    } catch (e: SQLiteException) {
        Log.e(TAG, "$operation: database error", e)
        AppResult.Failure(AppError.Database(e.javaClass.simpleName))
    }
