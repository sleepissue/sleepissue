package com.sleepissue.awakedrive.voice

import android.os.Handler
import android.os.Looper

class AndroidDelay(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : (Long, () -> Unit) -> Unit {
    override fun invoke(delayMs: Long, block: () -> Unit) {
        handler.postDelayed(block, delayMs)
    }
}
