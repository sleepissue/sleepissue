package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.model.Gear
import com.sleepissue.awakedrive.engine.model.NaviField
import com.sleepissue.awakedrive.engine.model.NaviState
import com.sleepissue.awakedrive.engine.model.ObservationTimeBasis
import com.sleepissue.awakedrive.engine.model.PowerEvent
import com.sleepissue.awakedrive.engine.model.PowerState
import com.sleepissue.awakedrive.engine.model.SignalAvailability
import com.sleepissue.awakedrive.engine.model.SignalField
import com.sleepissue.awakedrive.engine.model.SignalFrame
import com.sleepissue.awakedrive.engine.model.SignalQuality
import com.sleepissue.awakedrive.engine.model.Source
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VehicleSignalHubTest {
    @Test
    fun `원시 속도를 한 번 변환하고 Fake Hub 구독자까지 전달한다`() = runTest {
        val source = FakeSignalSource()
        val clock = FakeClock(elapsedMs = 900L)
        val hub = VehicleSignalHub(source, clock)
        val received = mutableListOf<SignalFrame>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { hub.samples.collect { received += it } }
        hub.start(backgroundScope)

        source.emit(VehicleSignalInput(100L, Source.REAL, speed = 22.22f, speedUnit = SpeedUnit.METERS_PER_SECOND, gear = Gear.D))
        runCurrent()

        assertEquals(1, received.size)
        assertEquals(79.992f, received.single().speedKph!!, 0.001f)
        assertEquals(Gear.D, received.single().gear)
        assertEquals(received.single(), hub.latestFrame.value)
        assertEquals(Source.REAL, received.single().qualityOf(SignalField.SPEED_KPH).source)
        assertEquals(900L, received.single().qualityOf(SignalField.SPEED_KPH).receivedAtElapsedMs)
        hub.stop()
    }

    @Test
    fun `이미 kmh인 데모 속도는 다시 변환하지 않는다`() = runTest {
        val source = FakeSignalSource()
        val hub = VehicleSignalHub(source, FakeClock())
        hub.start(backgroundScope)
        source.emit(VehicleSignalInput(0L, Source.DEMO, speed = 80f))
        runCurrent()
        assertEquals(80f, hub.latestFrame.value!!.speedKph!!, 0f)
        assertEquals(Source.DEMO, hub.latestFrame.value!!.qualityOf(SignalField.SPEED_KPH).source)
        hub.stop()
    }

    @Test
    fun `실제 영값과 미수신 권한없음 미지원은 구분한다`() = runTest {
        val source = FakeSignalSource()
        val hub = VehicleSignalHub(source, FakeClock())
        hub.start(backgroundScope)
        source.emit(VehicleSignalInput(0L, Source.REAL, speed = 0f, brakeOn = false))
        runCurrent()
        assertEquals(0f, hub.latestFrame.value!!.speedKph!!, 0f)
        assertEquals(false, hub.latestFrame.value!!.brakeOn)
        assertEquals(SignalAvailability.AVAILABLE, hub.latestFrame.value!!.qualityOf(SignalField.SPEED_KPH).availability)

        source.emit(VehicleSignalInput(100L, Source.REAL, speed = 90f, quality = mapOf(
            SignalField.SPEED_KPH to quality(SignalAvailability.PERMISSION_DENIED),
            SignalField.CRUISE_ACTIVE to quality(SignalAvailability.UNSUPPORTED),
        )))
        runCurrent()
        val frame = hub.latestFrame.value!!
        assertNull(frame.speedKph)
        assertNull(frame.gear)
        assertNull(frame.cruiseActive)
        assertEquals(SignalAvailability.PERMISSION_DENIED, frame.qualityOf(SignalField.SPEED_KPH).availability)
        assertEquals(SignalAvailability.UNSUPPORTED, frame.qualityOf(SignalField.CRUISE_ACTIVE).availability)
        assertEquals(SignalAvailability.UNAVAILABLE, frame.qualityOf(SignalField.GEAR).availability)
        hub.stop()
    }

    @Test
    fun `available이라도 값이 없으면 미수신이며 잘못된 필드만 오류로 바꾼다`() = runTest {
        val source = FakeSignalSource()
        val hub = VehicleSignalHub(source, FakeClock())
        hub.start(backgroundScope)
        source.emit(VehicleSignalInput(0L, Source.REAL, speed = Float.NaN, gear = Gear.D,
            quality = mapOf(SignalField.STEERING_DEG to quality(SignalAvailability.AVAILABLE))))
        runCurrent()
        val frame = hub.latestFrame.value!!
        assertNull(frame.speedKph)
        assertEquals(SignalAvailability.ERROR, frame.qualityOf(SignalField.SPEED_KPH).availability)
        assertEquals(SignalAvailability.UNAVAILABLE, frame.qualityOf(SignalField.STEERING_DEG).availability)
        assertEquals(Gear.D, frame.gear)
        assertEquals(InputCollectionStatus.COLLECTING, hub.collectionState.value.vehicle)
        hub.stop()
    }

    @Test
    fun `필드별 출처와 원천 시각을 보존하고 같은 값도 연속 통로로 전달한다`() = runTest {
        val source = FakeSignalSource()
        val clock = FakeClock(elapsedMs = 1000L)
        val hub = VehicleSignalHub(source, clock)
        val received = mutableListOf<SignalFrame>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { hub.samples.collect { received += it } }
        hub.start(backgroundScope)
        repeat(2) { index ->
            source.emit(VehicleSignalInput(index * 100L, Source.DEMO, steeringDeg = 1f, speed = 80f, quality = mapOf(
                SignalField.STEERING_DEG to SignalQuality(SignalAvailability.AVAILABLE, Source.REAL, observedAtMs = index * 50L,
                    sourceRateHz = 2f, observedTimeBasis = ObservationTimeBasis.ELAPSED_REALTIME),
            )))
            runCurrent()
            clock.elapsedMs += 100L
        }
        assertEquals(2, received.size)
        assertEquals(listOf(0L, 50L), received.map { it.qualityOf(SignalField.STEERING_DEG).observedAtMs })
        assertEquals(Source.REAL, received.last().qualityOf(SignalField.STEERING_DEG).source)
        assertEquals(Source.DEMO, received.last().qualityOf(SignalField.SPEED_KPH).source)
        assertEquals(2f, received.last().qualityOf(SignalField.STEERING_DEG).sourceRateHz!!, 0f)
        assertEquals(ObservationTimeBasis.ELAPSED_REALTIME,
            received.last().qualityOf(SignalField.STEERING_DEG).observedTimeBasis)
        hub.stop()
    }

    @Test
    fun `내용이 완전히 같은 프레임도 분석 통로에서는 합치지 않는다`() = runTest {
        val source = FakeSignalSource()
        val hub = VehicleSignalHub(source, FakeClock())
        val received = mutableListOf<SignalFrame>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { hub.samples.collect { received += it } }
        hub.start(backgroundScope)
        val input = VehicleSignalInput(0L, Source.REAL, speed = 80f)
        source.emit(input)
        runCurrent()
        source.emit(input)
        runCurrent()
        assertEquals(2, received.size)
        assertEquals(received.first(), received.last())
        hub.stop()
    }

    @Test
    fun `중복 시작을 막고 중지하면 구독과 최신값을 정리하며 재시작할 수 있다`() = runTest {
        val source = FakeSignalSource()
        val hub = VehicleSignalHub(source, FakeClock())
        assertTrue(hub.start(backgroundScope))
        assertFalse(hub.start(backgroundScope))
        assertEquals(1, source.activeSubscriptions)
        source.emit(VehicleSignalInput(0L, Source.REAL, speed = 20f))
        runCurrent()
        hub.stop()
        assertEquals(0, source.activeSubscriptions)
        assertNull(hub.latestFrame.value)
        assertEquals(InputCollectionStatus.STOPPED, hub.collectionState.value.vehicle)
        source.emit(VehicleSignalInput(100L, Source.REAL, speed = 40f))
        runCurrent()
        assertNull(hub.latestFrame.value)
        assertTrue(hub.start(backgroundScope))
        assertEquals(2, source.totalSubscriptions)
        source.emit(VehicleSignalInput(200L, Source.REAL, speed = 60f))
        runCurrent()
        assertEquals(60f, hub.latestFrame.value!!.speedKph!!, 0f)
        hub.stop()
    }

    @Test
    fun `느린 활성 분석 구독자에게 샘플을 버리지 않고 순서대로 전달한다`() = runTest {
        val source = FakeSignalSource()
        val hub = VehicleSignalHub(source, FakeClock())
        val received = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            hub.samples.collect { frame ->
                delay(10L)
                received += frame.tMs
            }
        }
        hub.start(backgroundScope)
        val producer = launch {
            repeat(3) { source.emit(VehicleSignalInput(it * 100L, Source.DEMO, speed = 80f)) }
        }
        runCurrent()
        advanceTimeBy(31L)
        runCurrent()
        producer.join()
        assertEquals(listOf(0L, 100L, 200L), received)
        hub.stop()
    }

    @Test
    fun `차량 입력 실패가 별도 내비와 전원 입력을 종료하지 않는다`() = runTest {
        val source = FakeSignalSource()
        val navi = FakeNaviSource()
        val power = FakePowerSource()
        val hub = VehicleSignalHub(source, FakeClock(elapsedMs = 200L), navi, power)
        hub.start(backgroundScope)
        source.emit(VehicleSignalInput(0L, Source.REAL, speed = 80f))
        runCurrent()
        source.fail()
        runCurrent()
        assertEquals(InputCollectionStatus.FAILED, hub.collectionState.value.vehicle)
        assertNull(hub.latestFrame.value)
        assertEquals(0, source.activeSubscriptions)
        navi.emit(NaviState(100L, distToNextTurnM = 500f, quality = mapOf(
            NaviField.DIST_TO_NEXT_TURN_M to quality(SignalAvailability.AVAILABLE),
        )))
        power.emit(PowerEvent(100L, PowerState.OFF, quality(SignalAvailability.AVAILABLE)))
        runCurrent()
        assertEquals(500f, hub.latestNavi.value!!.distToNextTurnM!!, 0f)
        assertEquals(PowerState.OFF, hub.latestPower.value!!.state)
        assertEquals(InputCollectionStatus.COLLECTING, hub.collectionState.value.navi)
        assertEquals(InputCollectionStatus.COLLECTING, hub.collectionState.value.power)
        hub.stop()
    }

    @Test
    fun `소유 범위가 취소되면 구독과 마지막 available 값을 함께 정리한다`() = runTest {
        val source = FakeSignalSource()
        val ownerJob = Job(backgroundScope.coroutineContext[Job])
        val ownerScope = CoroutineScope(backgroundScope.coroutineContext + ownerJob)
        val hub = VehicleSignalHub(source, FakeClock())
        hub.start(ownerScope)
        source.emit(VehicleSignalInput(0L, Source.REAL, speed = 80f))
        runCurrent()
        assertEquals(80f, hub.latestFrame.value!!.speedKph!!, 0f)
        ownerJob.cancelAndJoin()
        assertEquals(0, source.activeSubscriptions)
        assertNull(hub.latestFrame.value)
        assertEquals(InputCollectionStatus.STOPPED, hub.collectionState.value.vehicle)
        hub.stop()
    }

    @Test
    fun `분석 구독자가 대기 중이어도 stop이 입력 발행을 취소하고 끝난다`() = runTest {
        val source = FakeSignalSource()
        val hub = VehicleSignalHub(source, FakeClock())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            hub.samples.collect { awaitCancellation() }
        }
        hub.start(backgroundScope)
        source.emit(VehicleSignalInput(0L, Source.REAL, speed = 80f))
        runCurrent()
        source.emit(VehicleSignalInput(100L, Source.REAL, speed = 81f))
        runCurrent()
        hub.stop()
        assertEquals(0, source.activeSubscriptions)
        assertNull(hub.latestFrame.value)
    }

    @Test
    fun `유한 입력의 종료는 무응답 영값이나 정상 관측으로 꾸미지 않는다`() = runTest {
        val source = object : VehicleSignalSource {
            override val samples = emptyFlow<VehicleSignalInput>()
        }
        val hub = VehicleSignalHub(source, FakeClock())
        hub.start(backgroundScope)
        assertEquals(InputCollectionStatus.COMPLETED, hub.collectionState.value.vehicle)
        assertNull(hub.latestFrame.value)
        hub.stop()
    }

    @Test
    fun `차량 Flow getter의 권한 오류도 격리하고 내비 입력은 계속 받는다`() = runTest {
        val source = object : VehicleSignalSource {
            override val samples: kotlinx.coroutines.flow.Flow<VehicleSignalInput>
                get() = throw SecurityException("시험용 권한 거부")
        }
        val navi = FakeNaviSource()
        val hub = VehicleSignalHub(source, FakeClock(), naviSource = navi)
        hub.start(backgroundScope)
        assertEquals(InputCollectionStatus.FAILED, hub.collectionState.value.vehicle)
        assertNull(hub.latestFrame.value)
        assertEquals(InputCollectionStatus.COLLECTING, hub.collectionState.value.navi)
        navi.emit(NaviState(0L, distToNextTurnM = 500f, quality = mapOf(
            NaviField.DIST_TO_NEXT_TURN_M to quality(SignalAvailability.AVAILABLE),
        )))
        runCurrent()
        assertEquals(500f, hub.latestNavi.value!!.distToNextTurnM!!, 0f)
        hub.stop()
    }

    @Test
    fun `내비와 전원 Flow getter 실패도 차량 입력과 격리한다`() = runTest {
        val source = FakeSignalSource()
        val navi = object : NaviSignalSource {
            override val states: kotlinx.coroutines.flow.Flow<NaviState>
                get() = throw IllegalStateException("시험용 내비 초기화 실패")
        }
        val power = object : PowerSignalSource {
            override val events: kotlinx.coroutines.flow.Flow<PowerEvent>
                get() = throw SecurityException("시험용 전원 권한 거부")
        }
        val hub = VehicleSignalHub(source, FakeClock(), navi, power)
        hub.start(backgroundScope)
        assertEquals(InputCollectionStatus.FAILED, hub.collectionState.value.navi)
        assertEquals(InputCollectionStatus.FAILED, hub.collectionState.value.power)
        assertNull(hub.latestNavi.value)
        assertNull(hub.latestPower.value)
        source.emit(VehicleSignalInput(0L, Source.REAL, speed = 80f))
        runCurrent()
        assertEquals(80f, hub.latestFrame.value!!.speedKph!!, 0f)
        assertEquals(InputCollectionStatus.COLLECTING, hub.collectionState.value.vehicle)
        hub.stop()
    }

    @Test
    fun `내비 미지원 값과 관측 불가 전원을 정상 위치나 OFF로 전달하지 않는다`() = runTest {
        val source = FakeSignalSource()
        val navi = FakeNaviSource()
        val power = FakePowerSource()
        val hub = VehicleSignalHub(source, FakeClock(), navi, power)
        hub.start(backgroundScope)
        navi.emit(NaviState(0L, lat = 0.0, lon = 0.0, distToNextTurnM = 300f, quality = mapOf(
            NaviField.LAT to quality(SignalAvailability.AVAILABLE),
            NaviField.LON to quality(SignalAvailability.AVAILABLE),
            NaviField.DIST_TO_NEXT_TURN_M to quality(SignalAvailability.UNSUPPORTED),
        )))
        power.emit(PowerEvent(0L, PowerState.OFF, quality(SignalAvailability.PERMISSION_DENIED)))
        runCurrent()
        assertEquals(0.0, hub.latestNavi.value!!.lat!!, 0.0)
        assertEquals(0.0, hub.latestNavi.value!!.lon!!, 0.0)
        assertNull(hub.latestNavi.value!!.distToNextTurnM)
        assertEquals(PowerState.UNKNOWN, hub.latestPower.value!!.state)
        assertEquals(SignalAvailability.PERMISSION_DENIED, hub.latestPower.value!!.quality.availability)
        hub.stop()
    }

    private fun quality(availability: SignalAvailability) =
        SignalQuality(availability, Source.REAL)
}
