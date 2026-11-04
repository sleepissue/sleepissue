package com.sleepissue.awakedrive.voice

import android.os.SystemClock
import com.sleepissue.awakedrive.engine.time.Clock

/**
 * 벽시계 변경의 영향을 받지 않는 Android 경과 시간 Clock.
 * 음성 대답 시간·실내 말소리 측정은 [elapsedRealtimeMs]만 쓴다 (데모 배속 미적용).
 */
class AndroidElapsedClock : Clock {
    // [가정] 음성·실내 모듈 단독 사용 시 논리 시각도 경과 시각으로 둔다. 데모 배속 연동은 1번 DemoClock과 협의.
    override fun scenarioTimeMs(): Long = SystemClock.elapsedRealtime()
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
    override fun wallTimeMs(): Long = java.time.Clock.systemUTC().millis()
}
