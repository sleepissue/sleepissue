package com.sleepissue.awakedrive.debug

import android.app.Application
import android.content.Context
import android.content.Intent
import com.sleepissue.awakedrive.service.*

/** Receiver 자체는 제품 컴포넌트다. 명시적 arm에서만 저장소/서비스를 격리 대상으로 선택한다. */
class DebugBootApplication : Application(), MonitorBootEnvironment {
    private fun armed(context: Context) = DebugBootAudit.runId(context) != null
    override fun preferenceContext(context: Context) = if (armed(context)) DebugIsolatedMonitorService.storage(context) else context
    override fun monitoringIntent(context: Context) = Intent(context,
        if (armed(context)) DebugBootMonitorService::class.java else RealTripMonitorService::class.java)
    override fun bootEvent(context: Context, stage: String, error: Exception?) {
        if (!armed(context)) return
        val preference = MonitorPreference(preferenceContext(context))
        DebugBootAudit.record(context, stage, mapOf("receiver" to MonitorBootReceiver::class.java.name,
            "requested" to preference.requested, "bootPending" to preference.bootPending,
            "errorType" to error?.javaClass?.name, "error" to error?.message))
    }
}
