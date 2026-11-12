package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.fatigue.FatigueEngine
import com.sleepissue.awakedrive.engine.fatigue.GateDecision
import com.sleepissue.awakedrive.engine.fatigue.GateInput
import com.sleepissue.awakedrive.engine.fatigue.GateReason
import com.sleepissue.awakedrive.engine.fatigue.SteeringGate
import com.sleepissue.awakedrive.engine.fatigue.toDriveFrame
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.signal.MissingObservationPolicy
import com.sleepissue.awakedrive.engine.signal.SignalFallbackPolicy
import com.sleepissue.awakedrive.engine.signal.SignalFreshnessPolicy
import com.sleepissue.awakedrive.pleos.SdkDelivery
import com.sleepissue.awakedrive.pleos.SdkProbePolicy
import com.sleepissue.awakedrive.pleos.SdkSignalStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * 1번 수집 경계 → 실제 주기 Hub → 기존 2번 입력 변환의 결합 계약이다.
 * SDK/AVD 성능 시험이 아니며, 2번의 문턱값·지표·점수식을 변경하지 않는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FatigueInputHubContractTest {
    private class Rig(val scope: TestScope) {
        val clock = FakeClock()
        val vehicle = FakeSignalSource()
        val navigation = FakeNaviSource()
        // [가정] 이 시험의 TTL 1초. 제품 정책을 변경하지 않는다.
        private val freshness = SignalFreshnessPolicy(1_000, 1_000, MissingObservationPolicy.REQUIRE_TIMESTAMP)
        val hub = VehicleSignalHub(listOf(HubSourceSet(Source.REAL, vehicle, navigation,
            rateTimeBasis = ObservationTimeBasis.ELAPSED_REALTIME)), clock, HubPolicy(
            SignalField.entries.associateWith { freshness }, NaviField.entries.associateWith { freshness }, freshness,
            SignalFallbackPolicy(false, 0, ObservationTimeBasis.ELAPSED_REALTIME),
            ObservationTimeBasis.ELAPSED_REALTIME, HubTickDriver.MANUAL))

        fun at(ms: Long) { clock.scenarioMs = ms; clock.elapsedMs = ms }
        fun quality(at: Long, availability: SignalAvailability = SignalAvailability.AVAILABLE) =
            SignalQuality(availability, Source.REAL, at, observedTimeBasis = ObservationTimeBasis.ELAPSED_REALTIME)
        fun input(at: Long, angle: Float = 1f) = VehicleSignalInput(at, Source.REAL,
            steeringDeg = angle, speed = 80f, gear = Gear.D, turnSignal = TurnSignal.OFF,
            brakeOn = false, cruiseActive = false,
            quality = SignalField.entries.associateWith { quality(at) })

        suspend fun open() { hub.start(scope.backgroundScope); scope.runCurrent() }
        suspend fun observe(input: VehicleSignalInput, distance: Float? = 500f) {
            at(input.tMs)
            vehicle.emit(input)
            navigation.emit(NaviState(input.tMs, distToNextTurnM = distance,
                quality = NaviField.entries.associateWith { quality(input.tMs) }))
            scope.runCurrent()
        }
        suspend fun tick(ms: Long): SignalFrame {
            at(ms)
            assertTrue(hub.publishDue())
            scope.runCurrent()
            return requireNotNull(hub.latestFrame.value)
        }
        fun gate(frame: SignalFrame): GateInput = frame.toDriveFrame(hub.latestNavi.value).let {
            GateInput(it.tMs, it.steeringDeg, it.speedKph, it.inDrive, it.turnSignalOn,
                it.brakeOn, it.distToNextTurnM, assistMode = false)
        }
    }

    @Test fun sensorUnitsAndSmallSignedAnglesReachTheEngineWithoutRoundingOrRescaling() = runTest {
        val rig = Rig(this); rig.open()
        val angles = listOf(-6f, -5f, -3f, -0.5f, 0f, 0.5f, 3f, 5f, 6f)
        angles.forEachIndexed { index, angle ->
            val at = (index + 1) * 100L
            rig.observe(rig.input(at, angle).copy(speed = 22.222223f, speedUnit = SpeedUnit.METERS_PER_SECOND))
            val frame = rig.tick(at)
            val engineInput = frame.toDriveFrame(rig.hub.latestNavi.value)
            assertEquals(angle, engineInput.steeringDeg!!, 0f)
            assertEquals(80f, engineInput.speedKph!!, 0.0001f)
            assertEquals(500f, engineInput.distToNextTurnM!!, 0f)
            assertEquals(at, engineInput.tMs)
            assertEquals(at, frame.qualityOf(SignalField.STEERING_DEG).observedAtMs)
        }
        rig.hub.stop()
    }

    @Test fun speedGearAndTurnDistanceBoundariesReachTheExistingGate() = runTest {
        val rig = Rig(this); rig.open()
        data class Case(val speed: Float, val gear: Gear, val distance: Float?, val reason: GateReason)
        val cases = listOf(
            Case(59.9f, Gear.D, 500f, GateReason.LOW_SPEED),
            Case(60f, Gear.D, 300f, GateReason.PASS), // 기획서는 300m 이상이다.
            Case(60f, Gear.D, 299.9f, GateReason.NEAR_TURN),
            Case(80f, Gear.P, 500f, GateReason.NOT_DRIVE),
            Case(80f, Gear.R, 500f, GateReason.NOT_DRIVE),
            Case(80f, Gear.N, 500f, GateReason.NOT_DRIVE),
            Case(80f, Gear.D, null, GateReason.TURN_UNKNOWN_HOLD),
        )
        cases.forEachIndexed { index, case ->
            val at = (index + 1) * 100L
            rig.observe(rig.input(at, 0f).copy(speed = case.speed, gear = case.gear), case.distance)
            val gate = SteeringGate()
            gate.push(rig.gate(rig.tick(at)))
            assertEquals(case.reason, gate.flush().single().reason)
        }
        rig.hub.stop()
    }

    @Test fun turnAndBrakeEventsRetainTheBeforeAndAfterExclusionWindowsThroughTheHub() = runTest {
        val rig = Rig(this); rig.open()
        val gate = SteeringGate()
        val decisions = mutableListOf<GateDecision>()
        for (at in 100L..50_000L step 100) {
            rig.observe(rig.input(at).copy(
                turnSignal = if (at == 15_000L) TurnSignal.LEFT else TurnSignal.OFF,
                brakeOn = at == 35_000L))
            decisions += gate.push(rig.gate(rig.tick(at)))
        }
        decisions += gate.flush()
        val byTime = decisions.associateBy { it.tMs }
        assertEquals(GateReason.PASS, byTime.getValue(4_900).reason)
        for (at in 5_000L..25_000L step 100) assertEquals(GateReason.TURN_SIGNAL, byTime.getValue(at).reason)
        assertEquals(GateReason.PASS, byTime.getValue(25_100).reason)
        for (at in 30_000L..40_000L step 100) assertEquals(GateReason.BRAKE, byTime.getValue(at).reason)
        assertEquals(GateReason.PASS, byTime.getValue(40_100).reason)
        rig.hub.stop()
    }

    @Test fun unavailableRequiredSignalsCannotTurnIntoZeroOrFalseAndPassTheGate() = runTest {
        val rig = Rig(this); rig.open()
        val required = listOf(SignalField.STEERING_DEG, SignalField.SPEED_KPH, SignalField.GEAR,
            SignalField.TURN_SIGNAL, SignalField.BRAKE_ON)
        var at = 0L
        for (field in required) for (unavailable in SignalAvailability.entries.filter { it != SignalAvailability.AVAILABLE }) {
            at += 100
            // 원시 값이 남아 있어도 품질이 불가하면 Hub가 제거해야 한다.
            val input = rig.input(at)
            rig.observe(input.copy(quality = input.quality + (field to rig.quality(at, unavailable))))
            val frame = rig.tick(at)
            assertEquals(unavailable, frame.qualityOf(field).availability)
            val gate = SteeringGate()
            gate.push(rig.gate(frame))
            assertEquals("$field/$unavailable", GateReason.NO_SIGNAL, gate.flush().single().reason)
        }
        rig.hub.stop()
    }

    @Test fun expiredVehicleAndNavigationCannotLeaveAnOldPassingEngineInput() = runTest {
        val rig = Rig(this); rig.open()
        rig.observe(rig.input(0))
        assertNotNull(rig.tick(1_000).steeringDeg)
        val expired = rig.tick(1_100).toDriveFrame(rig.hub.latestNavi.value)
        assertNull(expired.steeringDeg); assertNull(expired.speedKph); assertNull(expired.inDrive)
        assertNull(expired.turnSignalOn); assertNull(expired.brakeOn); assertNull(expired.distToNextTurnM)
        assertEquals(SignalAvailability.STALE,
            rig.hub.latestFrame.value!!.qualityOf(SignalField.STEERING_DEG).availability)
        rig.hub.stop()
    }

    @Test fun sdkSteeringMeaningHoldRemainsNullAfterTheHubAndEngineInputBoundary() = runTest {
        val rig = Rig(this); rig.open()
        rig.observe(rig.input(0))
        rig.at(100)
        val store = SdkSignalStore(rig.clock, SdkProbePolicy(3_000, 1_000))
        store.declare("STEERING_DEG", "test", true)
        store.capability("STEERING_DEG", true, "READ")
        store.receiveSteeringSdkValue(-10f, SdkDelivery.CALLBACK, 100)
        rig.vehicle.emit(requireNotNull(store.vehicleInput("STEERING_DEG")))
        runCurrent()
        val frame = rig.tick(100)
        assertEquals("-10.0", store.readings().single().raw)
        assertNull(frame.toDriveFrame(rig.hub.latestNavi.value).steeringDeg)
        assertEquals(SignalAvailability.UNSUPPORTED, frame.qualityOf(SignalField.STEERING_DEG).availability)
        assertNotNull(frame.speedKph) // 보류 조향이 나머지 정상 신호를 지우지는 않는다.
        rig.hub.stop()
    }

    @Test fun twoHzSourceStaysBelowMinimumDespiteTenHzHubPublication() = runTest {
        engineRun(sourceIntervalMs = 500, expectedRate = 2f, expectedMicroEnabled = false)
    }

    @Test fun fiveHzSourceEnablesMicroAfterWarmupWithoutInventingExtraObservations() = runTest {
        engineRun(sourceIntervalMs = 200, expectedRate = 5f, expectedMicroEnabled = true)
    }

    @Test fun tenHzHubDrivesOneMinuteWindowsAtThirtySecondCadenceUsingTheUnchangedEngine() = runTest {
        engineRun(sourceIntervalMs = 100, expectedRate = 10f, expectedMicroEnabled = true)
    }

    private suspend fun TestScope.engineRun(sourceIntervalMs: Long, expectedRate: Float, expectedMicroEnabled: Boolean) {
        val rig = Rig(this)
        val engine = FatigueEngine()
        val states = mutableListOf<FatigueState>()
        val consumed = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rig.hub.samples.collect { frame ->
                consumed += requireNotNull(frame.publication).sequence
                states += engine.push(frame.toDriveFrame(rig.hub.latestNavi.value))
            }
        }
        rig.open()
        rig.observe(rig.input(0, -1f))
        for (at in 100L..100_100L step 100) {
            if (at % sourceIntervalMs == 0L) {
                // 미세 조향 삼각파. 값이 0이어도 정상 관측이며 멈춤으로 둔갑시키지 않는다.
                val phase = (at % 2_000L) / 1_000f
                val angle = if (phase < 1f) -1f + 2f * phase else 3f - 2f * phase
                rig.observe(rig.input(at, angle))
            }
            rig.tick(at)
        }
        val rate = requireNotNull(rig.hub.latestFrame.value!!.qualityOf(SignalField.STEERING_DEG).sourceRateInfo)
        assertEquals(expectedRate, rate.hz!!, 0f)
        assertEquals(1L + 100_100L / sourceIntervalMs, rate.acceptedObservations)
        assertEquals((1L..1_001L).toList(), consumed)
        // 방향지시등의 '전 10초' 소급 제외가 있어 60.1초 결과는 70.1초 입력에서 확정된다.
        assertEquals(listOf(60_100L, 90_100L), states.map { it.tMs })
        val settled = states.last()
        assertTrue(settled.valid)
        assertEquals(expectedMicroEnabled, settled.microEnabled)
        assertEquals(0f, settled.behaviorB, 0f)
        assertEquals(0f, settled.riskR, 0f)
        rig.hub.stop()
    }
}
