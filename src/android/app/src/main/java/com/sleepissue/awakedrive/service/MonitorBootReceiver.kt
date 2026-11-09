package com.sleepissue.awakedrive.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 사용자 시작 의사가 남은 비마이크 specialUse만 BOOT 예외로 시작한다. 부팅을 시동 OFF로 만들지 않는다. */
class MonitorBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val preference = MonitorPreference(context)
        preference.reconcileUserStop()
        if (!preference.requested) return
        try {
            context.startForegroundService(Intent(context, RealTripMonitorService::class.java)
                .setAction(RealTripMonitorService.ACTION_BOOT))
        } catch (_: Exception) {
            // OEM/OS 시작 제한이면 반복 실행으로 우회하지 않고 사용자의 재개 경로로 남긴다.
            preference.bootDeferred()
        }
    }
}
