package com.sleepissue.awakedrive.engine.fatigue

import com.sleepissue.awakedrive.engine.model.AgeBand
import com.sleepissue.awakedrive.engine.model.Gear
import com.sleepissue.awakedrive.engine.model.NaviState
import com.sleepissue.awakedrive.engine.model.SeatOccupancy
import com.sleepissue.awakedrive.engine.model.SignalField
import com.sleepissue.awakedrive.engine.model.SignalFrame
import com.sleepissue.awakedrive.engine.model.SignalQuality
import com.sleepissue.awakedrive.engine.model.TripState
import com.sleepissue.awakedrive.engine.model.TurnSignal
import com.sleepissue.awakedrive.engine.signal.RateStatus
import com.sleepissue.awakedrive.engine.signal.SourceRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1번 SignalFrame·NaviState·TripState → 2번 입력 변환 시험. */
class SignalAdaptersTest {

    private fun rated(status: RateStatus, below: Boolean?) =
        mapOf(SignalField.STEERING_DEG to SignalQuality(sourceRateInfo = SourceRate(status = status, belowMinimum = below)))

    private val driving = SignalFrame(
        tMs = 1_000L,
        steeringDeg = 1.5f,
        speedKph = 80f,
        gear = Gear.D,
        turnSignal = TurnSignal.OFF,
        brakeOn = false,
        cruiseActive = true,
        cruiseTargetKph = 100f,
        ventilationLevel = 2,
        quality = rated(RateStatus.MEASURED, below = false),
    )

    @Test
    fun signalFrameMapsFieldForField() {
        val frame = driving.toDriveFrame(NaviState(tMs = 1_000L, distToNextTurnM = 850f))
        assertEquals(1_000L, frame.tMs)
        assertEquals(1.5f, frame.steeringDeg!!, 0f)
        assertEquals(80f, frame.speedKph!!, 0f)
        assertEquals(true, frame.inDrive)
        assertEquals(false, frame.turnSignalOn)
        assertEquals(false, frame.brakeOn)
        assertEquals(850f, frame.distToNextTurnM!!, 0f)
        assertEquals(true, frame.cruiseActive)
        assertEquals(100f, frame.cruiseTargetKph!!, 0f)
        assertEquals(2, frame.ventilationLevel)
        assertTrue(frame.steeringRateOk)
    }

    @Test
    fun unknownStaysUnknown() {
        // 모르는 값을 0·false로 바꾸지 않는다. 내비가 없으면 회전 거리 null → 게이트가 5분 유지 규칙을 씀
        val frame = SignalFrame(tMs = 0L).toDriveFrame(navi = null)
        assertNull(frame.steeringDeg)
        assertNull(frame.inDrive)
        assertNull(frame.turnSignalOn)
        assertNull(frame.brakeOn)
        assertNull(frame.distToNextTurnM)
        assertNull(frame.cruiseActive)
        assertFalse("조향 원천 정보가 없으면 충분하다고 보지 않음", frame.steeringRateOk)
    }

    @Test
    fun gearAndTurnSignalAreReducedToWhatTheGateNeeds() {
        assertEquals(false, driving.copy(gear = Gear.P).toDriveFrame(null).inDrive)
        assertEquals(true, driving.copy(turnSignal = TurnSignal.LEFT).toDriveFrame(null).turnSignalOn)
        assertEquals("비상등도 켜짐 [해석]", true, driving.copy(turnSignal = TurnSignal.HAZARD).toDriveFrame(null).turnSignalOn)
    }

    @Test
    fun steeringRateIsOkOnlyWhenMeasuredAtFiveHzOrMore() {
        // REQ_SIG_009: 1번 RateMeter가 MEASURED이고 5 Hz 미만이 아닐 때만 미세 수정을 씀
        assertTrue(driving.copy(quality = rated(RateStatus.MEASURED, below = false)).steeringRateOk())
        assertFalse(driving.copy(quality = rated(RateStatus.MEASURED, below = true)).steeringRateOk())
        assertFalse(driving.copy(quality = rated(RateStatus.WARMING_UP, below = null)).steeringRateOk())
        assertFalse(driving.copy(quality = rated(RateStatus.UNMEASURABLE, below = null)).steeringRateOk())
        assertFalse(driving.copy(quality = emptyMap()).steeringRateOk())
    }

    @Test
    fun passengerCountNeedsEverySeatKnown() {
        // 동승자 0명 → 혼자 운전, 1명 이상 → 아님, 착석 null → 모름 (REQ_FAT_016)
        assertEquals(0, SeatOccupancy(true, false, false, false, false).passengerCount())
        assertEquals(2, SeatOccupancy(true, true, false, true, false).passengerCount())
        assertNull(SeatOccupancy(true, false, null, false, false).passengerCount())
    }

    @Test
    fun contextComesFromTripStateAndFrame() {
        // 운전 시간은 TripState의 초 단위 그대로 (단위 변환 없음, REQ_SIG_010)
        val trip = TripState(continuousDriveSec = 7_200L, last24hDriveSec = 14_400L)
        val frame = driving.copy(seats = SeatOccupancy(true, false, false, false, false), cabinTempC = 26f)
        val context = contextInputOf(trip, frame, minuteOfDay = 3 * 60, ageBand = null)
        assertEquals(7_200L, context.continuousDriveSec)
        assertEquals(14_400L, context.last24hDriveSec)
        assertEquals(0, context.passengerCount)
        assertEquals(26f, context.cabinTempC!!, 0f)
        assertEquals("가중 5개 모두 켜짐", 1.35f, ContextWeight.kOf(context), 1e-6f)
    }
}
