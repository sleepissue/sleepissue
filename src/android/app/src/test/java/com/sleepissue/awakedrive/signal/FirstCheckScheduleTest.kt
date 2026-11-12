package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.intervention.InterventionPolicy
import com.sleepissue.awakedrive.engine.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** 2번이 준 시각을 1번이 실행하는 계약이다. 점수·민감 모드 계산과 음성 판정은 변경하지 않는다. */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstCheckScheduleTest {
    private class Owner(val scope: TestScope, val clock: FakeClock = FakeClock(), val prefix: String = "a") {
        var serial = 0
        val output = mutableListOf<ExecutionRequest>()
        val manager = PolicyInterventionManager(InterventionPolicy(EngineConfig()), EngineConfig(), clock,
            Source.DEMO, RequestTimerBasis.SCENARIO) { RequestId("$prefix-action-${++serial}") }
        init { scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { manager.requests.collect { output += it } } }
        fun at(ms: Long) { clock.scenarioMs = ms; clock.elapsedMs = ms }
        suspend fun send(value: InterventionInput) = manager.submit(RequestId("$prefix-command-${++serial}"), value).also { scope.runCurrent() }
        suspend fun begin() {
            manager.start(RequestId("start"))
            send(InterventionInput.TripChanged(TripState(tripId = "trip", runStartMs = 0)))
            send(InterventionInput.SensitiveModeChanged(true))
            send(InterventionInput.Assessment(DrivingAssessment(0, EvaluationStatus.EVALUATED, 0f)))
        }
        suspend fun schedule(due: Long? = 900_000, tripId: String = "trip") = send(InterventionInput.FirstCheckScheduleChanged(tripId, due))
        fun checks() = output.filterIsInstance<CheckRequest>().filter { it.context != CheckContext.RECOVERY }
        suspend fun startCheck(request: CheckRequest) {
            manager.onResult(ExecutionResult(request.requestId, ExecutionStatus.STARTED)); scope.runCurrent()
        }
        suspend fun outcome(request: CheckRequest, outcome: CheckOutcome) = send(InterventionInput.CheckCompleted(
            CheckResult(request.requestId, request.context, outcome)))
        suspend fun encoded(): TripSessionOwnerState = TripSessionCodec.decode(TripSessionCodec.encode(
            TripSessionOwnerState(Gear.D, PowerState.ON, SaveTimerBasis.SCENARIO, manager.checkpoint())))
    }

    @Test fun dueBoundaryProducesOneRequestWithoutInventingScoreOrRaisingStage() = runTest {
        val h = Owner(this); h.begin(); h.schedule()
        h.at(899_999); h.send(InterventionInput.Tick); assertTrue(h.checks().isEmpty())
        h.at(900_000); h.send(InterventionInput.Tick)
        assertEquals(1, h.checks().size)
        h.schedule(); h.send(InterventionInput.Tick)
        assertEquals(1, h.checks().size)
        assertEquals(InterventionStage.NORMAL, h.manager.state.value.stage)
        h.startCheck(h.checks().single())
        h.at(901_000); h.send(InterventionInput.Tick)
        assertEquals(InterventionStage.NORMAL, h.manager.state.value.stage)
        h.outcome(h.checks().single(), CheckOutcome.NORMAL)
        h.at(1_800_000); h.send(InterventionInput.Tick)
        assertEquals(1, h.checks().size)
    }

    @Test fun nullScheduleSensitivityOffAndNewTripEachRemoveTheOldReservation() = runTest {
        for (cancel in listOf<InterventionInput>(
            InterventionInput.FirstCheckScheduleChanged("trip", null),
            InterventionInput.SensitiveModeChanged(false),
            InterventionInput.TripChanged(TripState(tripId = "next", runStartMs = 100)),
        )) {
            val h = Owner(this); h.begin(); h.schedule(); h.at(100); h.send(cancel)
            h.at(900_000); h.send(InterventionInput.Tick)
            assertTrue("$cancel", h.checks().isEmpty())
        }
    }

    @Test fun oldTripAndBeforeNewTripAnchorsCannotArmAQuestion() = runTest {
        val h = Owner(this); h.begin()
        assertEquals(ExecutionStatus.FAILED, h.schedule(tripId = "old").status)
        h.at(1_000); h.send(InterventionInput.TripChanged(TripState(tripId = "next", runStartMs = 1_000)))
        assertEquals(ExecutionStatus.FAILED, h.schedule(tripId = "next").status)
        h.at(900_000); h.send(InterventionInput.Tick); assertTrue(h.checks().isEmpty())
    }

    @Test fun oneSlowResultRetriesAfterThirtySecondsAndTheSecondUsesTheExistingRestRule() = runTest {
        val h = Owner(this); h.begin(); h.schedule(); h.at(900_000); h.send(InterventionInput.Tick)
        val first = h.checks().single(); h.startCheck(first); h.outcome(first, CheckOutcome.SLOW)
        h.at(929_999); h.send(InterventionInput.Tick); assertEquals(1, h.checks().size)
        h.at(930_000); h.send(InterventionInput.Tick); assertEquals(2, h.checks().size)
        val second = h.checks().last(); h.startCheck(second); h.outcome(second, CheckOutcome.SLOW)
        assertEquals(InterventionStage.REST_GUIDANCE, h.manager.state.value.stage)
        assertEquals(TransitionReason.CHECK_RESULT, h.manager.state.value.reason)
    }

    @Test fun deviceFailureDoesNotBecomeHumanNoResponseOrRestGuidance() = runTest {
        val h = Owner(this); h.begin(); h.schedule(); h.at(900_000); h.send(InterventionInput.Tick)
        val check = h.checks().single()
        h.manager.onResult(ExecutionResult(check.requestId, ExecutionStatus.FAILED, ExecutionFailure.PERMISSION_DENIED))
        assertEquals(ExecutionStatus.FAILED, h.outcome(check, CheckOutcome.NO_RESPONSE).status)
        h.send(InterventionInput.Tick)
        assertEquals(InterventionStage.NORMAL, h.manager.state.value.stage)
        assertEquals(1, h.checks().size)
    }

    @Test fun noResponseUsesTheExistingImmediateRestRuleAndRestPrioritySuppressesAnExtraQuestion() = runTest {
        val h = Owner(this); h.begin(); h.schedule(); h.at(900_000); h.send(InterventionInput.Tick)
        val check = h.checks().single(); h.startCheck(check); h.outcome(check, CheckOutcome.NO_RESPONSE)
        assertEquals(InterventionStage.REST_GUIDANCE, h.manager.state.value.stage)
        h.at(1_000_000); h.send(InterventionInput.Tick); assertEquals(1, h.checks().size)
    }

    @Test fun aRiskTriggeredQuestionAlreadyStartedInThisSensitivePeriodFulfilsTheFirstCheck() = runTest {
        val h = Owner(this); h.begin()
        h.send(InterventionInput.Assessment(DrivingAssessment(0, EvaluationStatus.EVALUATED, 65f)))
        h.at(30_000); h.send(InterventionInput.Tick)
        val riskCheck = h.checks().single(); h.startCheck(riskCheck)
        h.schedule()
        h.outcome(riskCheck, CheckOutcome.NORMAL)
        h.at(900_000); h.send(InterventionInput.Tick)
        assertEquals(1, h.checks().size)
    }

    @Test fun targetChangeCancelsTheOldFirstQuestionAndUsesTheCurrentTarget() = runTest {
        val h = Owner(this); h.begin(); h.schedule(); h.at(900_000); h.send(InterventionInput.Tick)
        val old = h.checks().single()
        h.send(InterventionInput.AlertnessTargetChanged(CheckContext.PASSENGER_ALERTNESS))
        assertEquals(CheckContext.PASSENGER_ALERTNESS, h.checks().last().context)
        assertTrue(h.output.filterIsInstance<CancelExecutionRequest>().any { it.requestId == old.requestId })
        assertEquals(ExecutionStatus.FAILED, h.outcome(old, CheckOutcome.NO_RESPONSE).status)
    }

    @Test fun cautionTransitionDoesNotConsumeAnUnfinishedFirstQuestion() = runTest {
        val h = Owner(this); h.begin(); h.schedule(); h.at(900_000); h.send(InterventionInput.Tick)
        val old = h.checks().single(); h.startCheck(old)
        h.send(InterventionInput.VulnerableTimeChanged(true))
        assertEquals(InterventionStage.CAUTION, h.manager.state.value.stage)
        assertTrue(h.output.filterIsInstance<CancelExecutionRequest>().any { it.requestId == old.requestId })
        assertEquals(2, h.checks().size)
        val current = h.checks().last(); h.startCheck(current)
        h.send(InterventionInput.Tick)
        assertEquals(2, h.checks().size)
        assertEquals(ExecutionStatus.FAILED, h.outcome(old, CheckOutcome.NO_RESPONSE).status)
        assertEquals(ExecutionStatus.REQUESTED, h.outcome(current, CheckOutcome.NORMAL).status)
    }

    @Test fun futureScheduleSurvivesCodecAndRestoreDoesNotAskUntilDue() = runTest {
        val h = Owner(this); h.begin(); h.schedule()
        val saved = h.encoded().intervention
        val next = Owner(this, h.clock, "b")
        next.manager.restore(saved, TripState(tripId = "trip", runStartMs = 0))
        assertTrue(next.output.isEmpty())
        next.at(899_999); next.send(InterventionInput.Tick); assertTrue(next.checks().isEmpty())
        next.at(900_000); next.send(InterventionInput.Tick); assertEquals(1, next.checks().size)
    }

    @Test fun interruptedFirstCheckAndItsRetryRestoreWithoutReusingTheOldRequestId() = runTest {
        for (slow in listOf(false, true)) {
            val h = Owner(this); h.begin(); h.schedule(); h.at(900_000); h.send(InterventionInput.Tick)
            val old = h.checks().single(); h.startCheck(old)
            if (slow) h.outcome(old, CheckOutcome.SLOW)
            val saved = h.encoded().intervention
            assertTrue(saved.firstCheckInterrupted)
            val next = Owner(this, h.clock, "b")
            next.manager.restore(saved, TripState(tripId = "trip", runStartMs = 0))
            assertTrue(next.checks().isEmpty())
            if (slow) {
                assertFalse(next.manager.resumeInterruptedChecks())
                next.at(930_000)
            }
            assertTrue(next.manager.resumeInterruptedChecks()); runCurrent()
            val resumed = next.checks().single()
            assertNotEquals(old.requestId, resumed.requestId)
            next.startCheck(resumed); next.send(InterventionInput.Tick)
            assertEquals(InterventionStage.NORMAL, next.manager.state.value.stage)
        }
    }

    @Test fun stopAndRestCertificateClearFirstCheckAndRejectLateDelivery() = runTest {
        for (rest in listOf(false, true)) {
            val h = Owner(this); h.begin(); h.schedule(); h.at(900_000); h.send(InterventionInput.Tick)
            val old = h.checks().single(); h.startCheck(old)
            if (rest) h.send(InterventionInput.RestCertified(RestCertification("rest", 900_000, Source.DEMO)))
            else h.manager.stop(RequestId("stop"))
            assertEquals(ExecutionStatus.FAILED, h.outcome(old, CheckOutcome.NO_RESPONSE).status)
            assertTrue(h.output.filterIsInstance<CancelExecutionRequest>().any { it.requestId == old.requestId })
        }
    }

    @Test fun legacyVersionOneOwnerPayloadStillDecodesWithoutASchedule() {
        // 이전 v1 필드 순서의 고정 자료. 새 encoder로 만들어 자르는 시험이 아니다.
        val fixture = "AAAAAQABRAACT04ACFNDRU5BUklPAAZOT1JNQUwADU5PVF9FVkFMVUFURUQAAQAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADU5PVF9FVkFMVUFURUQAAAAAAQAAAAAAEERSSVZFUl9BTEVSVE5FU1MAAAAAAAAAAAAAAAAAAAAAAA=="
        val old = TripSessionCodec.decode(fixture)
        assertNull(old.intervention.firstCheckDueMs)
        assertFalse(old.intervention.firstCheckRequested)
        old.intervention.requireValid(0, TripState(tripId = "trip", runStartMs = 0))
        assertEquals(old, TripSessionCodec.decode(TripSessionCodec.encode(old)))
    }
}
