package com.worldexplorer.domain

import kotlinx.coroutines.CoroutineDispatcher

/** Injected dispatchers so tests can run everything on a test scheduler. */
data class AppDispatchers(
    val io: CoroutineDispatcher,
    val default: CoroutineDispatcher,
)
