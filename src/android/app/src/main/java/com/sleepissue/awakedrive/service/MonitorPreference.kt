package com.sleepissue.awakedrive.service

import android.content.Context
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.Build

/** 운행 DB와 별개인 실행 의사다. 기록 복원은 이 값이 false여도 가능하다. */
class MonitorPreference(private val context: Context) {
    private val preferences = context.getSharedPreferences("real_trip_monitor", Context.MODE_PRIVATE)
    val requested get() = preferences.getBoolean("requested", false)
    val bootPending get() = preferences.getBoolean("boot_pending", false)
    fun request(value: Boolean): Boolean {
        val editor = preferences.edit().putBoolean("requested", value).putBoolean("boot_pending", false)
        if (value && !requested) editor.putLong("requested_at_wall_ms", java.time.Clock.systemUTC().millis())
        return editor.commit()
    }
    fun bootDeferred(): Boolean = preferences.edit().putBoolean("boot_pending", requested || bootPending)
        .putBoolean("requested", false).commit()

    /** OS Active apps 중지/설정 강제 종료는 onDestroy가 없다. 다음 실행에서도 재시작 의사를 철회한다. */
    fun reconcileUserStop() {
        if (!requested || Build.VERSION.SDK_INT < 30) return
        val since = preferences.getLong("requested_at_wall_ms", Long.MAX_VALUE)
        val exits = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 8)
        if (exits.any { it.reason == ApplicationExitInfo.REASON_USER_REQUESTED && it.timestamp >= since }) request(false)
    }
}
