package com.sleepissue.awakedrive.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** debug Application만 구현하는 저장소/대상 선택 경계다. release Application에는 없다. */
interface MonitorBootEnvironment {
    fun preferenceContext(context: Context): Context
    fun monitoringIntent(context: Context): Intent
    fun bootEvent(context: Context, stage: String, error: Exception? = null)
}

/** 사용자 시작 의사가 남은 비마이크 specialUse만 BOOT 예외로 시작한다. 부팅을 시동 OFF로 만들지 않는다. */
class MonitorBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val environment = context.applicationContext as? MonitorBootEnvironment
        environment?.bootEvent(context, "BOOT_RECEIVED")
        val preference = MonitorPreference(environment?.preferenceContext(context) ?: context)
        preference.reconcileUserStop()
        if (!preference.requested) { environment?.bootEvent(context, "REQUEST_SKIPPED"); return }
        try {
            environment?.bootEvent(context, "START_REQUEST")
            val target = environment?.monitoringIntent(context) ?: Intent(context, RealTripMonitorService::class.java)
            context.startForegroundService(target.setAction(RealTripMonitorService.ACTION_BOOT))
            environment?.bootEvent(context, "START_ACCEPTED")
        } catch (error: Exception) {
            // OEM/OS 시작 제한이면 반복 실행으로 우회하지 않고 사용자의 재개 경로로 남긴다.
            preference.bootDeferred()
            environment?.bootEvent(context, "START_REJECTED", error)
        }
    }
}
