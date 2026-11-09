package com.sleepissue.awakedrive.engine.assist

import com.sleepissue.awakedrive.engine.model.AssistReason
import com.sleepissue.awakedrive.engine.model.DriveFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 보조 주행 판정·인계 품질·조작 공백 시험. 기대값은 SRS v0.2 REQ_AST_001·003·006·007·008 합격기준. */
class AssistModeDetectorTest {

    /** 기본은 고속도로 직선 주행, 크루즈 꺼짐, 조향 0° */
    private fun frame(
        tMs: Long,
        cruise: Boolean? = false,
        steering: Float? = 0f,
        brake: Boolean? = false,
        target: Float? = null,
        vent: Int? = null,
    ) = DriveFrame(
        tMs = tMs,
        steeringDeg = steering,
        speedKph = 80f,
        inDrive = true,
        turnSignalOn = false,
        brakeOn = brake,
        distToNextTurnM = 1_000f,
        cruiseActive = cruise,
        cruiseTargetKph = target,
        ventilationLevel = vent,
    )

    /** [fromMs]~[toMs]를 100 ms마다 넣는다 */
    private fun AssistModeDetector.run(fromMs: Long, toMs: Long, cruise: Boolean?) {
        for (t in fromMs..toMs step 100L) update(frame(t, cruise = cruise))
    }

    @Test
    fun cruiseSwitchesAssistModeOnTheSameFrame() {
        // 크루즈 ON → 다음 프레임(≤ 110 ms)부터 모드 ON, OFF → 모드 OFF + 30 s 인계 감시 시작 (REQ_AST_001)
        val detector = AssistModeDetector()
        assertFalse(detector.update(frame(0L, cruise = false)).assistMode)

        val on = detector.update(frame(100L, cruise = true))
        assertTrue(on.assistMode)
        assertEquals(AssistReason.CRUISE, on.reason)

        val off = detector.update(frame(5_000L, cruise = false))
        assertFalse(off.assistMode)
        assertEquals(AssistReason.NONE, off.reason)
        // 해제와 함께 인계 감시가 시작됐으므로 해제 29 s 뒤 급조향은 인계 품질 이상
        detector.run(5_100L, 34_000L, cruise = false)
        assertTrue(detector.onSafetySudden(eventMs = 34_000L, frameNowMs = 34_000L))
        assertTrue(detector.state.handoverAbnormal)
    }

    @Test
    fun suddenSteerWithinThirtySecondsAfterReleaseIsUnsafe() {
        // 해제 후 29 s 급조향 1회 → 2단계(인계 품질 이상), 31 s → 아님 (REQ_AST_007)
        assertTrue(suddenAfterRelease(29_000L))
        assertFalse(suddenAfterRelease(31_000L))
        assertTrue("30.0 s는 감시 안 [해석]", suddenAfterRelease(30_000L))
    }

    /** 10 s에 크루즈를 끄고, 그 [afterMs] 뒤 급조향이 인계 품질 이상이 되는지 */
    private fun suddenAfterRelease(afterMs: Long): Boolean {
        val detector = AssistModeDetector()
        detector.run(0L, 9_900L, cruise = true)
        detector.run(10_000L, 10_000L + afterMs, cruise = false)
        return detector.onSafetySudden(eventMs = 10_000L + afterMs, frameNowMs = 10_000L + afterMs)
    }

    @Test
    fun largeCorrectionsAreComparedWithUsualHandover() {
        // 큰 수정 기록 2회·현재 3회(1.5배) → 2단계, 기록 없음·3회 → 2단계, 2회 → 아님 (REQ_AST_007)
        assertTrue(largeAfterRelease(count = 3, usual = 2f))
        assertFalse(largeAfterRelease(count = 2, usual = 2f))
        assertTrue(largeAfterRelease(count = 3, usual = null))
        assertFalse(largeAfterRelease(count = 2, usual = null))
    }

    /** 10 s에 크루즈를 끄고 그 뒤 5 s마다 큰 수정을 [count]번 넣었을 때 인계 품질 이상이 되는지 */
    private fun largeAfterRelease(count: Int, usual: Float?): Boolean {
        val detector = AssistModeDetector()
        detector.run(0L, 9_900L, cruise = true)
        detector.run(10_000L, 40_000L, cruise = false)
        return (1..count).map { i ->
            detector.onSafetyLargeSwing(endMs = 10_000L + i * 5_000L, frameNowMs = 40_000L, usualHandoverLargeCount = usual)
        }.any { it }
    }

    @Test
    fun handoverIsFlaggedOncePerReleaseAndShownForThirtySeconds() {
        // 한 번 해제에 사건은 한 번. 받는 쪽이 놓치지 않게 이상 표시는 30초 유지 [해석]
        val detector = AssistModeDetector()
        detector.run(0L, 9_900L, cruise = true)
        detector.run(10_000L, 20_000L, cruise = false)
        assertTrue(detector.onSafetySudden(eventMs = 15_000L, frameNowMs = 20_000L))
        assertFalse(detector.onSafetySudden(eventMs = 18_000L, frameNowMs = 20_000L))

        detector.run(20_100L, 49_900L, cruise = false)
        assertTrue(detector.state.handoverAbnormal)
        detector.run(50_000L, 50_000L, cruise = false)
        assertFalse(detector.state.handoverAbnormal)
    }

    @Test
    fun reEngagingCruiseEndsTheWatch() {
        // 해제 5초 뒤 크루즈를 다시 켜면 인계 감시는 거기서 끝남. 판정이 늦게 오는 그 전 사건은 그대로 봄
        val detector = AssistModeDetector()
        detector.run(0L, 9_900L, cruise = true)
        detector.run(10_000L, 14_900L, cruise = false)
        detector.run(15_000L, 25_000L, cruise = true)
        assertFalse(detector.onSafetySudden(eventMs = 16_000L, frameNowMs = 25_000L))
        assertTrue(detector.onSafetySudden(eventMs = 14_000L, frameNowMs = 25_000L))
    }

    @Test
    fun missingSteeringMeansAssistUntilItComesBack() {
        // 조향 null → 모드 ON, 조향 값 수신 재개 → 다음 프레임(≤ 110 ms)에 모드 OFF(크루즈 꺼짐일 때) (REQ_AST_003)
        val detector = AssistModeDetector()
        // 출발 직후(게이트 통과 시간 0)에도 조향 값이 오면 일반 모드
        assertFalse(detector.update(frame(0L, steering = 0f)).assistMode)

        val lost = detector.update(frame(100L, steering = null))
        assertTrue(lost.assistMode)
        assertEquals(AssistReason.UNCERTAIN, lost.reason)

        val back = detector.update(frame(200L, steering = 1f))
        assertFalse(back.assistMode)
        assertEquals(AssistReason.NONE, back.reason)
        // 조향 신호가 돌아온 것은 인계가 아니므로 인계 감시를 시작하지 않음 [해석]
        assertFalse(detector.onSafetySudden(eventMs = 1_000L, frameNowMs = 1_000L))
    }

    @Test
    fun unknownCruiseMeansNormalMode() {
        // 크루즈 null 입력 → 보조 주행 모드 OFF(일반 모드), 조향 계산 유지 (REQ_AST_008 [제안])
        val state = AssistModeDetector().update(frame(0L, cruise = null))
        assertFalse(state.assistMode)
        assertEquals(AssistReason.NONE, state.reason)
    }

    @Test
    fun operationGapCountsSecondsSinceLastOperation() {
        // 조작 공백 900 s → 900, 901 s → 901(15분 넘음은 1번이 판단), 조작 1회 발생 시 공백 리셋 (REQ_AST_006)
        val detector = AssistModeDetector()
        detector.update(frame(0L, cruise = true, target = 100f, vent = 0)) // 첫 프레임부터 셈
        detector.update(frame(900_000L, cruise = true, target = 100f, vent = 0))
        assertEquals(900, detector.state.opGapSec)
        detector.update(frame(901_000L, cruise = true, target = 100f, vent = 0))
        assertEquals(901, detector.state.opGapSec)

        detector.update(frame(901_100L, cruise = true, target = 110f, vent = 0)) // 크루즈 속도 변경
        assertEquals(0, detector.state.opGapSec)
        detector.update(frame(960_000L, cruise = true, target = 110f, vent = 0))
        assertEquals(58, detector.state.opGapSec)
        detector.update(frame(960_100L, cruise = true, target = 110f, vent = 1)) // 통풍 조작
        assertEquals(0, detector.state.opGapSec)
        detector.update(frame(990_000L, cruise = true, target = 110f, vent = 1, brake = true)) // 브레이크
        assertEquals(0, detector.state.opGapSec)
    }
}
