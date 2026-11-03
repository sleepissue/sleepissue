package com.sleepissue.awakedrive.voice

import android.os.SystemClock
import com.sleepissue.awakedrive.engine.model.Clock

/** 벽시계 변경의 영향을 받지 않는 Android 경과 시간 Clock. */
class AndroidElapsedClock : Clock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}
