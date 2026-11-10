package com.sleepissue.awakedrive.data

import android.app.NotificationManager
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.sleepissue.awakedrive.debug.*
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.session.*
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.pleos.PlaygroundSdkOwnership
import com.sleepissue.awakedrive.service.MonitorPreference
import com.sleepissue.awakedrive.signal.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 저장/arm과 마지막 정리만 수행한다. 재부팅 이후 서비스를 시작하는 runner는 없다. */
@RunWith(AndroidJUnit4::class)
class Role1BootDeviceTest {
    @Test fun prepareTenFieldsAndArmActualBoot() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("실제 재부팅 준비 전용", args.getString("role1Boot") == "prepare")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runId = requireNotNull(args.getString("role1BootRunId"))
        require(runId.matches(Regex("[a-zA-Z0-9-]+")))
        assertNull(PlaygroundSdkOwnership.currentOwner)
        val storage = DebugIsolatedMonitorService.storage(context)
        check(!File(storage.cacheDir, "trp003-$runId").exists()) { "이전 진단 덮기 금지" }
        val clock = object : Clock {
            override fun scenarioTimeMs() = java.time.Clock.systemUTC().millis()
            override fun elapsedRealtimeMs() = SystemClock.elapsedRealtime()
            override fun wallTimeMs() = java.time.Clock.systemUTC().millis()
        }
        // 식별 가능한 저장 fixture다. 실제5분 정차/센서 관측을 했다는 뜻이 아니다.
        val now = clock.scenarioTimeMs()
        val policy = TripRulePolicy(StopTimePolicy.V03_RETROACTIVE, ShortStopAccumulationPolicy.EXCLUDE)
        val rules = TripRules(EngineConfig(), policy) { "trp003-$runId" }
        var state = rules.reduce(TripRuleState(), TripObservation(now - 420_579, Gear.D, null)).state
        state = rules.reduce(state, TripObservation(now - 300_123, Gear.P, null)).state
        state = rules.reduce(state, TripObservation(now, Gear.P, null)).state
        state = state.copy(trip = state.trip.copy(stage = InterventionStage.CHECK,
            baselineRef = "baseline-$runId", lastLocation = GeoPoint(37.423456, 127.098765)))
        val snapshot = RealTripPersistence.capture(state, policy, clock)
        val db = TripDatabase.open(storage, RealTripPersistence.DATABASE_NAME)
        try {
            val repository = RoomTripRepository(db.checkpointDao())
            assertEquals(RepositoryResult.Succeeded, repository.save(snapshot))
            assertEquals(snapshot, (repository.load() as TripLoadResult.Loaded).snapshot)
        } finally { db.close() }
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(manager.areNotificationsEnabled())
        assertTrue(DebugBootAudit.settings(context).edit().putBoolean("armed", true).putString("run_id", runId)
            .putBoolean("channel_existed", manager.getNotificationChannel(DebugIsolatedMonitorService.ISOLATED_NOTIFICATION_CHANNEL) != null).commit())
        File(DebugBootAudit.directory(context), "expected-snapshot.json").writeText(
            GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(snapshot))
        // runner 종료는 USER_REQUESTED로 기록될 수 있다. 보이는 확인 화면에서 이후 새 의사를 받는다.
        assertTrue(MonitorPreference(storage).request(false))
        DebugBootAudit.record(context, "PREPARED", mapOf("snapshot" to snapshot, "servicesStartedByRunner" to 0,
            "owner" to PlaygroundSdkOwnership.currentOwner, "pid" to Process.myPid()))
    }

    @Test fun finishAndDisarmWithoutStartingService() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("두 실제 부팅/명시 중지 뒤 정리 전용", args.getString("role1Boot") == "finish")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(args.getString("role1BootRunId"), DebugBootAudit.runId(context))
        assertFalse(MonitorPreference(DebugIsolatedMonitorService.storage(context)).requested)
        assertNull(PlaygroundSdkOwnership.currentOwner)
        val events = File(DebugBootAudit.directory(context), "events.jsonl").readLines()
        assertTrue(events.any { it.contains("RUNTIME_STARTED") })
        assertTrue(events.any { it.contains("REQUEST_SKIPPED") })
        DebugBootAudit.record(context, "FINAL_CHECK", mapOf("owner" to PlaygroundSdkOwnership.currentOwner,
            "requested" to false, "servicesStartedByRunner" to 0))
        if (!DebugBootAudit.settings(context).getBoolean("channel_existed", false)) {
            context.getSystemService(NotificationManager::class.java).deleteNotificationChannel(DebugIsolatedMonitorService.ISOLATED_NOTIFICATION_CHANNEL)
        }
        assertTrue(DebugBootAudit.settings(context).edit().putBoolean("armed", false).commit())
    }
}
