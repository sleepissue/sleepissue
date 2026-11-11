package com.sleepissue.awakedrive.debug

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import com.sleepissue.awakedrive.service.RealTripMonitorService
import com.sleepissue.awakedrive.service.PlaygroundMonitorInput
import com.sleepissue.awakedrive.service.MonitoringPipeline
import com.sleepissue.awakedrive.data.TripDatabase
import java.io.File

/** 사용자 DB·설정·진단을 열지 않는 명시적 기기시험 서비스다. release에는 포함하지 않는다. */
open class DebugIsolatedMonitorService : RealTripMonitorService() {
    override val notificationChannelId: String get() = ISOLATED_NOTIFICATION_CHANNEL
    override val notificationId: Int get() = ISOLATED_NOTIFICATION_ID
    override val persistenceContext by lazy {
        val base = storage(this)
        continuityAudit?.let { audit -> object : ContextWrapper(base) {
            override fun getCacheDir() = File(base.cacheDir, "plt013-${audit.runId}").also { it.mkdirs() }
        } } ?: base
    }
    override fun createMonitorInput(): PlaygroundMonitorInput {
        val audit = continuityAudit
        audit?.inputCreated()
        return PlaygroundMonitorInput(this, clock, audit?.let { it::published }, audit?.let { it::consumed })
    }
    override fun createTripRepository(database: TripDatabase) = super.createTripRepository(database).let { repository ->
        continuityAudit?.repository(repository) ?: repository
    }
    // 1번 저장·복원 기기시험은 신호·운행만 본다. 음성·내비·통풍을 실제로 건드리지 않게 판단·개입 조립을 끈다.
    override suspend fun createPipeline(): MonitoringPipeline? = null
    override fun monitoringScreenIntent() = Intent(this, DebugSdkProbeActivity::class.java)
    override fun onCreate() { continuityAudit?.lifecycle("CREATE"); super.onCreate() }
    override fun onDestroy() { continuityAudit?.lifecycle("DESTROY"); super.onDestroy() }

    companion object {
        const val ISOLATED_NOTIFICATION_ID = 4102
        const val ISOLATED_NOTIFICATION_CHANNEL = "role1_isolated_trip_monitor"
        @Volatile var continuityAudit: DebugServiceContinuityAudit? = null
        private const val PREFIX = "role1-final-20261109-"
        fun storage(base: Context): Context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = base.getDatabasePath(PREFIX + name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) =
                base.openOrCreateDatabase(PREFIX + name, mode, factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?) =
                base.openOrCreateDatabase(PREFIX + name, mode, factory, handler)
            override fun deleteDatabase(name: String) = base.deleteDatabase(PREFIX + name)
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(PREFIX + name, mode)
            override fun getCacheDir(): File = File(base.cacheDir, PREFIX + "diagnostics").also { it.mkdirs() }
        }
    }
}
