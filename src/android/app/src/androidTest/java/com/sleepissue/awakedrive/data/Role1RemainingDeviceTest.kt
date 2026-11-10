package com.sleepissue.awakedrive.data

import android.content.*
import android.os.*
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.sleepissue.awakedrive.debug.DebugIsolatedMonitorService
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.pleos.PlaygroundSdkOwnership
import com.sleepissue.awakedrive.service.*
import com.sleepissue.awakedrive.signal.RealTripPersistence
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

/** 명시적 runner에서만 실제 경과/공개 Console 입력을 기다린다. 가짜 Clock·사용자 DB를 쓰지 않는다. */
@RunWith(AndroidJUnit4::class)
class Role1RemainingDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val storage get() = DebugIsolatedMonitorService.storage(context)
    private fun enabled() = assumeTrue("명시적 기기 후속 시험", InstrumentationRegistry.getArguments().getString("role1Remaining") == "true")
    private fun write(name: String, value: Any) {
        val file = File(storage.cacheDir, name)
        val temp = File(storage.cacheDir, ".$name.tmp")
        temp.writeText(GsonBuilder().serializeNulls().create().toJson(value))
        check(temp.renameTo(file))
    }
    private suspend fun until(timeout: Long = 15_000, condition: () -> Boolean) = withTimeout(timeout) {
        while (!condition()) delay(50)
    }
    private class Bound(val service: RealTripMonitorService, val connection: ServiceConnection)
    private suspend fun bind(): Bound {
        val ready = CompletableDeferred<RealTripMonitorService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { ready.complete((binder as RealTripMonitorService.LocalBinder).service) }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        withContext(Dispatchers.Main) {
            check(context.bindService(Intent(context, DebugIsolatedMonitorService::class.java), connection, Context.BIND_AUTO_CREATE))
        }
        val service = withTimeout(15_000) { ready.await() }
        until { service.runtime.session.initialized }
        return Bound(service, connection)
    }
    private suspend fun start() = withContext(Dispatchers.Main) {
        context.startForegroundService(Intent(context, DebugIsolatedMonitorService::class.java).setAction(RealTripMonitorService.ACTION_START))
    }
    private suspend fun close(bound: Bound) {
        withContext(Dispatchers.Main) { bound.service.stopMonitoring() }
        until { !bound.service.runtime.running && !bound.service.foreground && !bound.service.stopping }
        withContext(Dispatchers.Main) { context.unbindService(bound.connection) }
        delay(1_000)
    }

    @Test fun externalSignalLatencyCapture() = runBlocking {
        enabled()
        val live = Role1LiveSdkSession(context)
        val frames = Collections.synchronizedList(mutableListOf<Map<String, Any?>>())
        val navi = Collections.synchronizedList(mutableListOf<Map<String, Any?>>())
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            owner.launch(start = CoroutineStart.UNDISPATCHED) {
                live.hub.samples.collect { frames += mapOf("consumerAtMs" to SystemClock.elapsedRealtime(), "frame" to it) }
            }
            owner.launch(start = CoroutineStart.UNDISPATCHED) {
                live.hub.naviSamples.collect { navi += mapOf("consumerAtMs" to SystemClock.elapsedRealtime(), "navi" to it) }
            }
            live.start()
            write("latency-progress.json", mapOf("phase" to "CAPTURING", "elapsedMs" to SystemClock.elapsedRealtime()))
            delay(100_000)
            val readings = withContext(Dispatchers.Main) { live.adapter.store.readings() }
            write("latency-capture.json", mapOf("frames" to synchronized(frames) { frames.toList() },
                "navi" to synchronized(navi) { navi.toList() }, "readings" to readings,
                "history" to withContext(Dispatchers.Main) { live.adapter.store.history() }))
            assertTrue(frames.isNotEmpty())
        } finally { owner.cancel(); live.close() }
    }

    @Test fun isolatedServiceOwnershipFailureAndRecovery() = runBlocking {
        enabled()
        val b = bind(); var lease: PlaygroundSdkOwnership.Lease? = null
        val result = linkedMapOf<String, Any?>()
        try {
            lease = requireNotNull(PlaygroundSdkOwnership.acquire("격리 시험 SDK 기존 소유자"))
            start()
            until { b.service.runtime.status.value.startsWith("시작 실패") }
            result["rejectedStatus"] = b.service.runtime.status.value
            assertFalse(b.service.runtime.running)
            assertEquals(0, b.service.runtime.activeTimers)
            assertEquals("격리 시험 SDK 기존 소유자", PlaygroundSdkOwnership.currentOwner)
            PlaygroundSdkOwnership.release(requireNotNull(lease)); lease = null
            start(); until { b.service.runtime.running }
            until { b.service.input?.hub?.latestFrame?.value?.gear != null }
            assertEquals(1, b.service.runtime.starts)
            assertEquals(1, b.service.runtime.activeTimers)
            start(); delay(1_000)
            assertEquals(1, b.service.runtime.starts)
            result["recovered"] = true; result["starts"] = b.service.runtime.starts
            result["foreground"] = b.service.foreground
        } finally {
            lease?.let(PlaygroundSdkOwnership::release)
            close(b); result["finalOwner"] = PlaygroundSdkOwnership.currentOwner
            write("service-ownership.json", result)
        }
    }

    @Test fun isolatedServiceNotificationDenialAndRecovery() = runBlocking {
        enabled()
        // 호스트가 기존 granted 상태를 확인하고 이 runner 직전에만 실제 권한을 철회한다.
        assumeTrue("실제 권한 철회/복구 절차 전용", InstrumentationRegistry.getArguments().getString("notificationPermissionWasGranted") == "true")
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission("android.permission.POST_NOTIFICATIONS"))
        fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
        }
        val b = bind(); val result = linkedMapOf<String, Any?>()
        try {
            start(); until { b.service.diagnostic.startsWith("서비스 시작 불가") }
            result["denied"] = b.service.diagnostic
            assertFalse(b.service.foreground)
            assertFalse(b.service.runtime.running)
            assertEquals(0, b.service.runtime.activeTimers)
            assertFalse(MonitorPreference(storage).requested)
            shell("pm grant --user 10 ${context.packageName} android.permission.POST_NOTIFICATIONS")
            start(); until { b.service.runtime.running }
            until { b.service.input?.hub?.latestFrame?.value?.gear != null }
            assertTrue(b.service.foreground)
            result["recovered"] = true
        } finally {
            shell("pm grant --user 10 ${context.packageName} android.permission.POST_NOTIFICATIONS")
            close(b); write("service-notification.json", result)
        }
    }

    @Test fun actualFiveAndFifteenMinuteParkWithIsolatedRoom() = runBlocking {
        enabled()
        val b = bind(); val s = b.service; val results = linkedMapOf<String, Any?>()
        val saves = mutableListOf<Long>(); var lastSave: Long? = null
        fun progress(phase: String) {
            val precise = s.runtime.session.preciseState()
            write("long-progress.json", mapOf("phase" to phase, "elapsedMs" to SystemClock.elapsedRealtime(),
                "precise" to precise, "persistence" to s.runtime.session.persistence.state.value))
        }
        fun snapshot(label: String) {
            results[label] = mapOf("elapsedMs" to SystemClock.elapsedRealtime(), "precise" to s.runtime.session.preciseState(),
                "trip" to s.runtime.session.trip.value, "events" to s.runtime.session.events.value)
        }
        suspend fun waitPark(duration: Long, phase: String) {
            val timeout = SystemClock.elapsedRealtime() + duration + 30_000
            var written = 0L
            while (true) {
                val p = s.runtime.session.preciseState()
                assertEquals("실제 SDK P 유지", Gear.P, p.lastObservation?.gear)
                val age = p.stopStartedAtMs?.let { (p.lastObservation?.tMs ?: it) - it } ?: 0
                val saved = s.runtime.session.persistence.state.value.lastSavedElapsedMs
                if (saved != null && saved != lastSave) { saves += saved; lastSave = saved }
                if (SystemClock.elapsedRealtime() - written >= 1_000) { progress(phase); written = SystemClock.elapsedRealtime() }
                if (age >= duration) break
                check(SystemClock.elapsedRealtime() < timeout) { "정차 타이머 진행 중단" }
                delay(100)
            }
        }
        try {
            start(); until { s.runtime.running }
            progress("WAIT_FIRST_D"); until(60_000) { s.runtime.session.latestFrame?.gear == Gear.D }
            delay(4_000); progress("WAIT_FIRST_P")
            until(60_000) { s.runtime.session.preciseState().lastObservation?.gear == Gear.P }
            val first = s.runtime.session.preciseState(); val anchor = requireNotNull(first.continuousBeforeStopMs)
            snapshot("firstPark")
            waitPark(299_000, "FIRST_PARK"); snapshot("at299Seconds")
            assertTrue(s.runtime.session.preciseState().continuousDriveMs >= anchor + 299_000)
            waitPark(300_200, "FIRST_PARK"); snapshot("afterFiveMinutes")
            assertEquals(anchor, s.runtime.session.preciseState().continuousDriveMs)
            progress("WAIT_SECOND_D"); until(60_000) { s.runtime.session.latestFrame?.gear == Gear.D }
            assertTrue(s.runtime.session.preciseState().continuousDriveMs < anchor + 5_000)
            delay(4_000); progress("WAIT_SECOND_P")
            until(60_000) { s.runtime.session.preciseState().lastObservation?.gear == Gear.P }
            val second = s.runtime.session.preciseState(); snapshot("secondPark")
            waitPark(899_000, "SECOND_PARK"); snapshot("at899Seconds")
            assertFalse(s.runtime.session.preciseState().restResetEmitted)
            assertEquals(second.continuousBeforeStopMs, s.runtime.session.preciseState().continuousDriveMs)
            waitPark(900_300, "SECOND_PARK"); snapshot("afterFifteenMinutes")
            assertEquals(0L, s.runtime.session.preciseState().continuousDriveMs)
            assertTrue(s.runtime.session.preciseState().restResetEmitted)
            assertEquals(first.trip.tripId, s.runtime.session.trip.value.tripId)
            results["saveElapsedTimes"] = saves
            results["completed"] = true
        } finally {
            snapshot("beforeStop"); close(b)
            results["finalOwner"] = PlaygroundSdkOwnership.currentOwner
            write("long-result.json", results); write("long-progress.json", mapOf("phase" to "DONE", "completed" to results["completed"]))
        }
    }

    @Test fun restoreIsolatedCheckpointWithoutReplayingParkGap() = runBlocking {
        enabled()
        val db = TripDatabase.open(storage, RealTripPersistence.DATABASE_NAME)
        val saved = try { db.checkpointDao().readConsistent() } finally { db.close() }
        assertNotNull(saved)
        val b = bind()
        try {
            val session = b.service.runtime.session
            assertFalse(session.connected)
            assertEquals(saved!!.checkpoint.tripId, session.trip.value.tripId)
            assertEquals(saved.checkpoint.stage, session.trip.value.stage.name)
            assertEquals(saved.checkpoint.baselineRef, session.trip.value.baselineRef)
            assertEquals(saved.checkpoint.lastLat, session.trip.value.lastLocation?.lat)
            assertEquals(saved.checkpoint.lastLon, session.trip.value.lastLocation?.lon)
            assertEquals(saved.checkpoint.runStartMs, session.trip.value.runStartMs)
            assertEquals(saved.checkpoint.stopStartMs, session.restoredSnapshot?.state?.stopStartMs)
            assertEquals(saved.checkpoint.lastGear, session.trip.value.lastGear?.name)
            assertEquals(saved.checkpoint.continuousDriveMs, session.preciseState().continuousDriveMs)
            assertEquals(DriveState.UNKNOWN, session.trip.value.driveState)
            write("restore-result.json", mapOf("pid" to Process.myPid(), "saved" to saved,
                "restored" to session.preciseState(), "restoredSnapshot" to session.restoredSnapshot))
        } finally { close(b) }
    }

    @Test fun officialVentilationLatencyAndRestoration() = runBlocking {
        enabled()
        val vehicle = Vehicle(context).also { it.initialize() }
        val live = Role1LiveSdkSession(context)
        val result = linkedMapOf<String, Any?>()
        var original: Int? = null
        try {
            val seat = vehicle.getSeat(); val driver = SeatArea(SeatValue.ROW_1_LEFT)
            val capability = CompletableDeferred<Boolean>()
            seat.checkSeatVentilationCapabilities({ _, areas -> capability.complete(driver in areas) }, { capability.completeExceptionally(it) })
            assertTrue(withTimeout(5_000) { capability.await() })
            suspend fun read(): Int? {
                val value = CompletableDeferred<Int?>()
                seat.getHvacSeatVentilationState(driver, { value.complete(it) }, { value.completeExceptionally(it) })
                return withTimeout(5_000) { value.await() }
            }
            suspend fun set(value: Int) {
                val done = CompletableDeferred<Int?>()
                seat.setHvacSeatVentilation(driver, value, { _, actual -> done.complete(actual) }, { done.completeExceptionally(it) })
                withTimeout(5_000) { done.await() }
            }
            original = read(); assertNotNull(original)
            live.start(); live.awaitVentilation(requireNotNull(original))
            val events = mutableListOf<Map<String, Any?>>()
            for (level in listOf(if (original == 1) 0 else 1, requireNotNull(original))) {
                val before = SystemClock.elapsedRealtime()
                set(level); val returned = SystemClock.elapsedRealtime()
                until { live.hub.latestFrame.value?.let { f ->
                    f.ventilationLevel == level && (f.qualityOf(SignalField.VENTILATION_LEVEL).receivedAtElapsedMs ?: -1) >= before
                } == true }
                val f = requireNotNull(live.hub.latestFrame.value)
                events += mapOf("requestedLevel" to level, "inputBeforeMs" to before, "setReturnedMs" to returned,
                    "observedByMs" to SystemClock.elapsedRealtime(), "frame" to f)
            }
            result["events"] = events; result["original"] = original; result["restored"] = read()
            assertEquals(original, result["restored"])
        } finally {
            // 실패 경로에서도 최초 읽은 실제 통풍 상태로 복구한다.
            original?.let { value ->
                val done = CompletableDeferred<Unit>()
                vehicle.getSeat().setHvacSeatVentilation(SeatArea(SeatValue.ROW_1_LEFT), value,
                    { _, _ -> done.complete(Unit) }, { done.completeExceptionally(it) })
                withTimeout(5_000) { done.await() }
            }
            live.close(); vehicle.release(); write("ventilation-latency.json", result)
        }
    }

    @Test fun prepareIsolatedRunningCheckpointForExternalForceStop() = runBlocking {
        enabled()
        assumeTrue("외부 force-stop 절차 전용", InstrumentationRegistry.getArguments().getString("externalForceStop") == "true")
        // 외부 모듈 실행 없이 복원할10종 필드를 만든다. 실제 경과로 모은 격리 checkpoint만 사용한다.
        val seedDb = TripDatabase.open(storage, RealTripPersistence.DATABASE_NAME)
        try {
            val rows = requireNotNull(seedDb.checkpointDao().readConsistent())
            seedDb.checkpointDao().replace(rows.checkpoint.copy(stage = InterventionStage.CHECK.name,
                baselineRef = "role1-isolated-baseline", lastLat = 37.4127212, lastLon = 127.094681), rows.segments)
        } finally { seedDb.close() }
        val b = bind(); val s = b.service
        try {
            start(); until { s.runtime.running }
            write("kill-progress.json", mapOf("phase" to "WAIT_D", "pid" to Process.myPid()))
            until(60_000) { s.runtime.session.latestFrame?.gear == Gear.D }
            delay(13_000)
            val db = TripDatabase.open(storage, RealTripPersistence.DATABASE_NAME)
            val saved = try { db.checkpointDao().readConsistent() } finally { db.close() }
            write("kill-progress.json", mapOf("phase" to "READY_TO_KILL", "pid" to Process.myPid(),
                "elapsedMs" to SystemClock.elapsedRealtime(), "beforeKill" to s.runtime.session.preciseState(), "saved" to saved))
            delay(60_000)
            fail("외부 force-stop이 실행되지 않음")
        } finally { close(b) }
    }
}
