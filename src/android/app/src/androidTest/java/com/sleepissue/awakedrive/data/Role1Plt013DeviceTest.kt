package com.sleepissue.awakedrive.data

import android.content.*
import android.os.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.sleepissue.awakedrive.debug.*
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.pleos.PlaygroundSdkOwnership
import com.sleepissue.awakedrive.service.RealTripMonitorService
import com.sleepissue.awakedrive.signal.RealTripPersistence
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 실제 30분 전용이다. UI binding은 내비 전환 전에 해제하고 실제 서비스 소비 경로를 감사한다. */
@RunWith(AndroidJUnit4::class)
class Role1Plt013DeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private suspend fun until(timeoutMs: Long = 15_000, condition: () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(50)
    }
    private class Bound(val service: RealTripMonitorService, val connection: ServiceConnection)
    private suspend fun bind(): Bound {
        val ready = CompletableDeferred<RealTripMonitorService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                ready.complete((binder as RealTripMonitorService.LocalBinder).service)
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        withContext(Dispatchers.Main) {
            check(context.bindService(Intent(context, DebugIsolatedMonitorService::class.java), connection, Context.BIND_AUTO_CREATE))
        }
        val service = withTimeout(15_000) { ready.await() }
        until { service.runtime.session.initialized }
        return Bound(service, connection)
    }

    @Test fun isolatedNotificationOpensSafeScreenAndStopsItsOwnService() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("명시적 알림 대상 회귀", args.getString("role1Plt013Notification") == "true")
        val audit = DebugServiceContinuityAudit(requireNotNull(args.getString("plt013RunId")))
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val userChannelBefore = manager.getNotificationChannel(RealTripMonitorService.CHANNEL)?.toString()
        val isolatedChannelExisted = manager.getNotificationChannel(DebugIsolatedMonitorService.ISOLATED_NOTIFICATION_CHANNEL) != null
        val pid = Process.myPid()
        fun openUserDatabases(): List<String> = File("/proc/self/fd").listFiles().orEmpty().mapNotNull {
            runCatching { android.system.Os.readlink(it.path) }.getOrNull()
        }.filter { path ->
            val name = File(path).name
            path.contains("/databases/") && !name.startsWith("role1-final-20261109-")
        }
        assertTrue("사용자 DB가 열린 상태로 시험하지 않는다", openUserDatabases().isEmpty())
        DebugIsolatedMonitorService.continuityAudit = audit
        val bound = bind(); val service = bound.service
        val result = linkedMapOf<String, Any?>("pidBefore" to pid, "userChannelBefore" to userChannelBefore)
        try {
            withContext(Dispatchers.Main) {
                context.startForegroundService(Intent(context, DebugIsolatedMonitorService::class.java).setAction(RealTripMonitorService.ACTION_START))
            }
            until { service.runtime.running && service.runtime.session.latestFrame?.gear == Gear.P }
            val notification = context.getSystemService(android.app.NotificationManager::class.java)
                .activeNotifications.single { it.id == DebugIsolatedMonitorService.ISOLATED_NOTIFICATION_ID }.notification
            assertEquals(DebugIsolatedMonitorService.ISOLATED_NOTIFICATION_CHANNEL, notification.channelId)
            assertNotEquals(RealTripMonitorService.CHANNEL, notification.channelId)
            assertTrue(manager.activeNotifications.none { it.id == RealTripMonitorService.NOTIFICATION_ID })
            assertTrue("격리 서비스 시작 뒤 사용자 DB 미개방", openUserDatabases().isEmpty())
            result["notificationId"] = DebugIsolatedMonitorService.ISOLATED_NOTIFICATION_ID
            result["notificationChannel"] = notification.channelId
            // P/0에서 정상적으로 앞에 연 뒤 알림을 보낸다. NEW_TASK의 기존 Activity 재사용도 허용한다.
            instrumentation.startActivitySync(Intent().setClassName(context, "com.sleepissue.awakedrive.ui.DrivingPreviewActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            notification.contentIntent.send()
            var screen: android.app.Activity? = null
            until {
                instrumentation.runOnMainSync {
                    screen = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                        .firstOrNull { it is DebugSdkProbeActivity }
                }
                screen != null
            }
            assertNotNull("격리 서비스 알림은 Room 없는 SDK 화면을 연다", screen)
            result["safeScreen"] = screen!!.javaClass.name
            val userDbPaths = openUserDatabases()
            assertTrue("알림 화면 진입은 사용자 DB를 열지 않는다", userDbPaths.isEmpty())
            result["openUserDbPathsAfterScreen"] = userDbPaths
            assertEquals("REAL 운행 감시 서비스", PlaygroundSdkOwnership.currentOwner)
            instrumentation.runOnMainSync { screen?.finish() }
            notification.actions.single { it.title.toString() == "감시 중지" }.actionIntent.send()
            until { !service.runtime.running && !service.foreground }
            assertNull(PlaygroundSdkOwnership.currentOwner)
            assertEquals(0, service.runtime.activeTimers)
            result["ownServiceStopped"] = true
            result["sdkOwnerAfterStop"] = PlaygroundSdkOwnership.currentOwner
            result["timersAfterStop"] = service.runtime.activeTimers
            assertEquals(pid, Process.myPid())
            result["pidAfter"] = Process.myPid()
            val userChannelAfter = manager.getNotificationChannel(RealTripMonitorService.CHANNEL)?.toString()
            assertEquals(userChannelBefore, userChannelAfter)
            result["userChannelAfter"] = userChannelAfter
        } finally {
            if (service.runtime.running) {
                withContext(Dispatchers.Main) { service.stopMonitoring() }
                until { !service.runtime.running && !service.foreground }
            }
            withContext(Dispatchers.Main) { context.unbindService(bound.connection) }
            delay(1_000)
            val directory = File(DebugIsolatedMonitorService.storage(context).cacheDir, "plt013-${audit.runId}").also { it.mkdirs() }
            if (!isolatedChannelExisted) manager.deleteNotificationChannel(DebugIsolatedMonitorService.ISOLATED_NOTIFICATION_CHANNEL)
            result["testChannelCreatedThenRemoved"] = !isolatedChannelExisted
            File(directory, "notification-result.json").writeText(GsonBuilder().serializeNulls().create().toJson(result))
            DebugIsolatedMonitorService.continuityAudit = null
        }
    }

    @Test fun readOnlyReentryRestoresLatestStoppedIsolatedCheckpoint() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("30분 이후 읽기 복원만 확인", args.getString("role1Plt013RestoreOnly") == "true")
        val audit = DebugServiceContinuityAudit(requireNotNull(args.getString("plt013RunId")))
        DebugIsolatedMonitorService.continuityAudit = audit
        val storage = DebugIsolatedMonitorService.storage(context)
        val database = TripDatabase.open(storage, RealTripPersistence.DATABASE_NAME)
        val saved = try { database.checkpointDao().readConsistent() } finally { database.close() }
        assertNotNull(saved)
        val bound = bind()
        try {
            val service = bound.service; val session = service.runtime.session
            assertFalse(service.runtime.running)
            assertEquals(0, service.runtime.activeTimers)
            assertNull(PlaygroundSdkOwnership.currentOwner)
            assertEquals(saved!!.checkpoint.tripId, session.trip.value.tripId)
            assertEquals(saved.checkpoint.continuousDriveMs, session.preciseState().continuousDriveMs)
            assertEquals(saved.checkpoint.stage, session.trip.value.stage.name)
            assertEquals(saved.checkpoint.baselineRef, session.trip.value.baselineRef)
            assertEquals(saved.checkpoint.lastLat, session.trip.value.lastLocation?.lat)
            assertEquals(saved.checkpoint.lastLon, session.trip.value.lastLocation?.lon)
            assertEquals(saved.checkpoint.runStartMs, session.trip.value.runStartMs)
            assertEquals(saved.checkpoint.stopStartMs, session.restoredSnapshot?.state?.stopStartMs)
            assertEquals(DriveState.UNKNOWN, session.trip.value.driveState)
            val directory = File(storage.cacheDir, "plt013-${audit.runId}").also { it.mkdirs() }
            File(directory, "restore-result.json").writeText(GsonBuilder().serializeNulls().create().toJson(
                mapOf("saved" to saved, "snapshot" to session.restoredSnapshot, "activePrecise" to session.preciseState(),
                    "running" to service.runtime.running, "timers" to service.runtime.activeTimers, "owner" to PlaygroundSdkOwnership.currentOwner)))
        } finally {
            withContext(Dispatchers.Main) { context.unbindService(bound.connection) }
            delay(1_000); DebugIsolatedMonitorService.continuityAudit = null
        }
    }

    @Test fun navigationForegroundForThirtyRealMinutesAuditsProducerConsumerAndStorage() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("명시적 30분 시험에서만 실행", args.getString("role1Plt013") == "true")
        val audit = DebugServiceContinuityAudit(requireNotNull(args.getString("plt013RunId")))
        assertNull(PlaygroundSdkOwnership.currentOwner)
        DebugIsolatedMonitorService.continuityAudit = audit
        val directory = File(DebugIsolatedMonitorService.storage(context).cacheDir, "plt013-${audit.runId}").also { it.mkdirs() }
        fun write(name: String, value: Any) {
            val temp = File(directory, ".$name.tmp")
            temp.writeText(GsonBuilder().serializeNulls().create().toJson(value))
            check(temp.renameTo(File(directory, name)))
        }
        val result = linkedMapOf<String, Any?>()
        val samples = mutableListOf<Map<String, Any?>>()
        var bound: Bound? = null
        var running: RealTripMonitorService? = null
        try {
            // P/0에서 자신의 검증 화면을 연다. SDK 화면은 연결하지 않아 소유권을 가져가지 않는다.
            instrumentation.startActivitySync(Intent().setClassName(context,
                "com.sleepissue.awakedrive.debug.DebugSdkProbeActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("sdkCaptureFile", "sdk-signal-plt013-${audit.runId}.json"))
            bound = bind()
            val service = requireNotNull(bound).service.also { running = it }
            withContext(Dispatchers.Main) {
                context.startForegroundService(Intent(context, DebugIsolatedMonitorService::class.java).setAction(RealTripMonitorService.ACTION_START))
            }
            until { service.runtime.running && service.runtime.session.latestFrame?.gear == Gear.P }
            result["beforeNavigation"] = mapOf("atMs" to SystemClock.elapsedRealtime(), "precise" to service.runtime.session.preciseState(),
                "pid" to Process.myPid(), "hub" to service.input?.hub?.diagnostics?.value)
            // Activity와 같은 binding을 남겨 서비스를 붙잡지 않는다. started FGS만 유지한다.
            withContext(Dispatchers.Main) { context.unbindService(requireNotNull(bound).connection) }
            bound = null
            withContext(Dispatchers.Main) {
                context.startActivity(Intent().setClassName("ai.umos.maps.android.navigation.app",
                    "ai.umos.maps.android.navigation.app.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            write("progress.json", mapOf("phase" to "WAIT_NAV_CONFIRMATION", "atMs" to SystemClock.elapsedRealtime()))
            until(60_000) { File(directory, "navigation-confirmed.json").exists() }
            val startMs = SystemClock.elapsedRealtime()
            val durationMs = 30L * 60L * 1_000L
            val startHub = requireNotNull(service.input).hub.diagnostics.value
            result["windowStartMs"] = startMs
            result["durationRequiredMs"] = durationMs
            result["startHub"] = startHub
            result["startPrecise"] = service.runtime.session.preciseState()
            var lastWrite = 0L
            while (SystemClock.elapsedRealtime() - startMs < durationMs) {
                val sample = withContext(Dispatchers.Main) {
                    val input = service.input
                    val readings = input?.adapter?.store?.readings().orEmpty()
                    val gear = readings.find { it.key == "GEAR" }
                    val speed = readings.find { it.key == "SPEED_KPH" }
                    val hub = input?.hub?.diagnostics?.value
                    linkedMapOf<String, Any?>("atMs" to SystemClock.elapsedRealtime(), "pid" to Process.myPid(),
                        "foreground" to service.foreground, "running" to service.runtime.running,
                        "starts" to service.runtime.starts, "timers" to service.runtime.activeTimers,
                        "runtimeFailure" to service.runtime.failure.value, "sdkOwner" to PlaygroundSdkOwnership.currentOwner,
                        "sdkStatus" to input?.adapter?.status?.value, "gearReading" to gear, "speedReading" to speed,
                        "sdkErrors" to readings.filter { it.availability in setOf(SignalAvailability.ERROR, SignalAvailability.PERMISSION_DENIED) },
                        "hubSession" to hub?.sessionId, "emittedFrames" to hub?.emittedFrames,
                        "skippedDeadlines" to hub?.skippedDeadlines, "clockFailure" to hub?.clockFailure,
                        "collection" to hub?.inputs?.get(Source.REAL)?.collection,
                        "sourceDeliveries" to hub?.inputs?.get(Source.REAL)?.vehicleCallbacks,
                        "precise" to service.runtime.session.preciseState(), "persistence" to service.runtime.session.persistence.state.value)
                }
                samples += sample
                val now = SystemClock.elapsedRealtime()
                if (now - lastWrite >= 1_000) {
                    write("progress.json", mapOf("phase" to "MEASURING", "windowStartMs" to startMs,
                        "atMs" to now, "sample" to sample, "counts" to audit.summary()))
                    lastWrite = now
                }
                delay(1_000)
            }
            val endMs = SystemClock.elapsedRealtime()
            result["windowEndMs"] = endMs
            result["endHub"] = service.input?.hub?.diagnostics?.value
            result["endPrecise"] = service.runtime.session.preciseState()
            result["endRuntime"] = mapOf("foreground" to service.foreground, "starts" to service.runtime.starts,
                "timers" to service.runtime.activeTimers, "running" to service.runtime.running, "failure" to service.runtime.failure.value)
            // 30분 창을 닫은 뒤 host가 Console P/0으로 되돌린다. 주행 제한 화면을 우회하지 않는다.
            write("progress.json", mapOf("phase" to "WAIT_PARK_FOR_STOP", "windowStartMs" to startMs,
                "windowEndMs" to endMs, "atMs" to SystemClock.elapsedRealtime(), "counts" to audit.summary()))
            until(60_000) { service.runtime.session.latestFrame?.gear == Gear.P &&
                (service.runtime.session.latestFrame?.speedKph ?: Float.MAX_VALUE) < 0.1f }
            result["manualSaveSucceeded"] = service.runtime.session.saveNow()
            withContext(Dispatchers.Main) { service.stopMonitoring() }
            // unbound started service는 stopSelf 뒤 바로 파괴돼 stopping=true일 수 있다.
            until { !service.runtime.running && !service.foreground }
            result["stopped"] = mapOf("atMs" to SystemClock.elapsedRealtime(), "owner" to PlaygroundSdkOwnership.currentOwner,
                "timers" to service.runtime.activeTimers, "running" to service.runtime.running)
            val db = TripDatabase.open(DebugIsolatedMonitorService.storage(context), RealTripPersistence.DATABASE_NAME)
            val rows = try { db.checkpointDao().readConsistent() } finally { db.close() }
            result["savedAfterStop"] = rows
            delay(1_000)
            // 새 서비스 인스턴스로 읽기 복원한다. 새 SDK 수집은 시작하지 않는다.
            bound = bind()
            val restored = requireNotNull(bound).service
            val session = restored.runtime.session
            result["restored"] = mapOf("atMs" to SystemClock.elapsedRealtime(), "snapshot" to session.restoredSnapshot,
                "precise" to session.preciseState(), "starts" to restored.runtime.starts,
                "timers" to restored.runtime.activeTimers, "owner" to PlaygroundSdkOwnership.currentOwner)
            assertNotNull(rows)
            assertEquals(rows!!.checkpoint.tripId, session.trip.value.tripId)
            assertEquals(rows.checkpoint.continuousDriveMs, session.preciseState().continuousDriveMs)
            assertEquals(rows.checkpoint.stage, session.trip.value.stage.name)
            assertEquals(rows.checkpoint.baselineRef, session.trip.value.baselineRef)
            assertEquals(DriveState.UNKNOWN, session.trip.value.driveState)
            assertEquals(0, restored.runtime.activeTimers)
            result["procedureCompleted"] = true
        } finally {
            running?.let { service ->
                if (service.runtime.running) {
                    withContext(Dispatchers.Main) { service.stopMonitoring() }
                    until { !service.runtime.running && !service.foreground }
                }
            }
            bound?.let { b -> withContext(Dispatchers.Main) { context.unbindService(b.connection) } }
            delay(1_000)
            write("frames-and-saves.json", audit.snapshot())
            write("sdk-samples.json", samples)
            write("result.json", result)
            write("progress.json", mapOf("phase" to "DONE", "completed" to result["procedureCompleted"], "atMs" to SystemClock.elapsedRealtime()))
            DebugIsolatedMonitorService.continuityAudit = null
        }
    }
}
