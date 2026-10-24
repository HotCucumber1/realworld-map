package com.worldexplorer.domain.tracking

fun interface Clock {
    fun nowUtcMillis(): Long
}

fun interface IdGenerator {
    fun newId(): String
}
