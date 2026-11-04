package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.model.Gear
import com.sleepissue.awakedrive.engine.model.SignalAvailability
import com.sleepissue.awakedrive.engine.model.SignalField
import com.sleepissue.awakedrive.engine.model.SignalFrame
import com.sleepissue.awakedrive.engine.model.Source
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 다른 담당이 연결 지점을 복사해 실행할 수 있는 예시다. 졸음 판단은 구현하지 않는다. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScaffoldUsageExampleTest {
    @Test
    fun fakeHubAnalysisAndLatestStateShareTheSameNormalizedContract() = runTest {
        val source = FakeSignalSource()
        val clock = FakeClock(elapsedMs = 1_000)
        val hub = VehicleSignalHub(source, clock)
        val tripStore = TripStateStore()
        val analysisInputs = mutableListOf<SignalFrame>()
        val screenFrames = mutableListOf<SignalFrame?>()

        // 2번 연결 지점: 분석용 연속 샘플을 구독한다. 실제 FatigueEngine 계산은 후속이다.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            hub.samples.collect { frame ->
                analysisInputs += frame
                // 메모리 보관 예시다. 운전 시간·정차·새 운행을 계산하지 않는다.
                tripStore.update { it.copy(lastGear = frame.gear) }
            }
        }
        // 5번 연결 지점: 화면은 최신 상태를 관찰하고 null을 수신 불가로 표시한다.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            hub.latestFrame.collect { screenFrames += it }
        }

        assertTrue(hub.start(backgroundScope))
        source.emit(
            VehicleSignalInput(
                tMs = 0,
                source = Source.REAL,
                speed = 22.22f,
                speedUnit = SpeedUnit.METERS_PER_SECOND,
                gear = Gear.D,
            ),
        )
        runCurrent()

        assertEquals(79.992f, analysisInputs.single().speedKph!!, 0.001f)
        assertEquals(Source.REAL, analysisInputs.single().qualityOf(SignalField.SPEED_KPH).source)
        assertEquals(Gear.D, tripStore.state.value.lastGear)
        assertEquals(analysisInputs.single(), screenFrames.last())

        clock.elapsedMs = 1_100
        source.emit(VehicleSignalInput(tMs = 100, source = Source.DEMO))
        runCurrent()

        assertEquals(2, analysisInputs.size)
        assertNull(analysisInputs.last().speedKph)
        assertEquals(SignalAvailability.UNAVAILABLE,
            analysisInputs.last().qualityOf(SignalField.SPEED_KPH).availability)
        assertEquals(Source.DEMO, analysisInputs.last().qualityOf(SignalField.SPEED_KPH).source)
        assertNull(tripStore.state.value.lastGear)
        assertEquals(analysisInputs, screenFrames.filterNotNull())

        hub.stop()
        source.emit(VehicleSignalInput(tMs = 200, source = Source.REAL, speed = 0f))
        runCurrent()

        assertEquals(2, analysisInputs.size)
        assertEquals(0, source.activeSubscriptions)
        assertNull(screenFrames.last())
        assertEquals(InputCollectionStatus.STOPPED, hub.collectionState.value.vehicle)
    }
}
