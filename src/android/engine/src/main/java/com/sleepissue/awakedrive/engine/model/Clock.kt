package com.sleepissue.awakedrive.engine.model

fun interface Clock {
    fun nowMs(): Long
}
