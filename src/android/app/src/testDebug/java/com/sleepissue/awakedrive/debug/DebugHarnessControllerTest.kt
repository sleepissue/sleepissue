package com.sleepissue.awakedrive.debug

import com.sleepissue.awakedrive.engine.model.CheckContext
import com.sleepissue.awakedrive.engine.model.CheckOutcome
import com.sleepissue.awakedrive.engine.model.DriveState
import com.sleepissue.awakedrive.engine.model.EvaluationStatus
import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.Gear
import com.sleepissue.awakedrive.engine.model.InterventionStage
import com.sleepissue.awakedrive.engine.model.PowerState
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.model.ResponseIntent
import com.sleepissue.awakedrive.engine.model.TransitionReason
import com.sleepissue.awakedrive.signal.CheckRequest
import com.sleepissue.awakedrive.signal.RestAction
import com.sleepissue.awakedrive.signal.RestRecoveryResetRequest
import com.sleepissue.awakedrive.signal.RestRequest
import com.sleepissue.awakedrive.signal.SpeechCue
import com.sleepissue.awakedrive.signal.SpeechRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** 화면 버튼이 조립한 입력으로 기존 운행·개입 규칙을 검증한다. SDK는 실행하지 않는다. */
@OptIn(ExperimentalCoroutinesApi::class)
class DebugHarnessControllerTest {
    private suspend fun TestScope.newController(): DebugHarnessController =
        DebugHarnessController(backgroundScope).also { it.reset(); runCurrent() }

    private fun DebugHarnessController.latestCheck(context: CheckContext): CheckRequest =
        state.value.requests.map { it.request }.filterIsInstance<CheckRequest>().last { it.context == context }

    private fun DebugHarnessController.latestSearch(): RestRequest =
        state.value.requests.map { it.request }.filterIsInstance<RestRequest>().last { it.action == RestAction.FIND_AHEAD }

    private fun DebugHarnessController.statusOf(requestId: RequestId): ExecutionStatus =
        state.value.requests.single { it.request.requestId == requestId }.status

    private suspend fun TestScope.enterCheck(controller: DebugHarnessController) {
        controller.applyAssessment(64f, trending = true)
        runCurrent()
        controller.advanceTime(30_000)
        runCurrent()
        assertEquals(InterventionStage.CHECK, controller.state.value.intervention.stage)
    }

    private suspend fun TestScope.enterRestGuidance(controller: DebugHarnessController) {
        enterCheck(controller)
        val check = controller.latestCheck(CheckContext.DRIVER_ALERTNESS)
        controller.completeCheck(check.requestId, CheckOutcome.NO_RESPONSE)
        runCurrent()
        assertEquals(InterventionStage.REST_GUIDANCE, controller.state.value.intervention.stage)
    }

    @Test fun resetStartsAnUnevaluatedParkedSessionWithOnlyTheResetEvent() = runTest {
        val controller = newController()
        val state = controller.state.value
        assertTrue(state.ready)
        assertEquals(0L, state.timeMs)
        assertEquals(Gear.P, state.gear)
        assertEquals(PowerState.ON, state.power)
        assertEquals(0L, state.trip.continuousDriveSec)
        assertEquals(0L, state.trip.last24hDriveSec)
        assertNull(state.trip.runStartMs)
        assertEquals(InterventionStage.NORMAL, state.intervention.stage)
        assertEquals(EvaluationStatus.NOT_EVALUATED, state.intervention.evaluationStatus)
        assertTrue(state.requests.isEmpty())
        assertEquals(1, state.events.size)
        assertTrue(state.events.single().message.contains("초기화"))
    }

    @Test fun gearAndVirtualTimePreserveTheRetroactiveStopAndRollingDriveRules() = runTest {
        val controller = newController()
        controller.setGear(Gear.D)
        controller.advanceTime(60_000)
        runCurrent()
        val tripId = controller.state.value.trip.tripId
        assertNotNull(tripId)
        assertEquals(DriveState.DRIVING, controller.state.value.trip.driveState)
        assertEquals(60L, controller.state.value.trip.continuousDriveSec)
        assertEquals(60L, controller.state.value.trip.last24hDriveSec)

        controller.setGear(Gear.P)
        controller.advanceTime(30_000)
        runCurrent()
        assertEquals(DriveState.PAUSED, controller.state.value.trip.driveState)
        assertEquals(90L, controller.state.value.trip.continuousDriveSec)
        assertEquals(30L, controller.state.value.trip.provisionalStopSec)
        assertEquals(60L, controller.state.value.trip.last24hDriveSec)

        controller.advanceTime(270_000)
        runCurrent()
        assertEquals(60L, controller.state.value.trip.continuousDriveSec)
        assertEquals(0L, controller.state.value.trip.provisionalStopSec)
        controller.advanceTime(600_000)
        runCurrent()
        assertEquals(0L, controller.state.value.trip.continuousDriveSec)
        assertEquals(60L, controller.state.value.trip.last24hDriveSec)
        assertEquals(tripId, controller.state.value.trip.tripId)
        assertEquals(InterventionStage.NORMAL, controller.state.value.intervention.stage)
    }

    @Test fun powerOffStopsDrivingEvenIfTheSelectedGearIsD() = runTest {
        val controller = newController()
        controller.setGear(Gear.D)
        controller.advanceTime(60_000)
        controller.setPower(PowerState.OFF)
        controller.advanceTime(300_000)
        runCurrent()
        assertEquals(Gear.D, controller.state.value.gear)
        assertEquals(PowerState.OFF, controller.state.value.power)
        assertEquals(DriveState.PAUSED, controller.state.value.trip.driveState)
        assertEquals(60L, controller.state.value.trip.continuousDriveSec)
        assertEquals(60L, controller.state.value.trip.last24hDriveSec)
        controller.setPower(PowerState.ON)
        controller.advanceTime(30_000)
        runCurrent()
        assertEquals(DriveState.DRIVING, controller.state.value.trip.driveState)
        assertEquals(90L, controller.state.value.trip.continuousDriveSec)
        assertEquals(90L, controller.state.value.trip.last24hDriveSec)
    }

    @Test fun aLargeAdvanceVisitsEachThirtySecondEscalationAndCancelsSupersededRequests() = runTest {
        val controller = newController()
        controller.setGear(Gear.D)
        controller.applyAssessment(90f, trending = true)
        runCurrent()
        assertEquals(InterventionStage.CAUTION, controller.state.value.intervention.stage)
        val firstSearch = controller.latestSearch()
        assertEquals(ExecutionStatus.STARTED, controller.statusOf(firstSearch.requestId))

        controller.advanceTime(60_000)
        runCurrent()
        assertEquals(60_000L, controller.state.value.timeMs)
        assertEquals(InterventionStage.REST_GUIDANCE, controller.state.value.intervention.stage)
        // 확인 요청은 대기 중이지만 이번 3단계의 원인은 점수·추세형이다.
        assertEquals(TransitionReason.SCORE, controller.state.value.intervention.reason)
        assertEquals(SpeechCue.REST_RECOMMENDED,
            (controller.state.value.requests.single { it.request is SpeechRequest }.request as SpeechRequest).cue)
        assertEquals(ExecutionStatus.CANCELLED, controller.statusOf(firstSearch.requestId))
        val check = controller.latestCheck(CheckContext.DRIVER_ALERTNESS)
        assertEquals(ExecutionStatus.CANCELLED, controller.statusOf(check.requestId))
        assertNotEquals(firstSearch.requestId, controller.latestSearch().requestId)
        val transitions = controller.state.value.events.filter { it.message.startsWith("개입 단계 ") }
        assertEquals(listOf(0L, 30_000L, 60_000L), transitions.map { it.timeMs })
        assertEquals(listOf("개입 단계 0 → 1", "개입 단계 1 → 2", "개입 단계 2 → 3"),
            transitions.map { it.message.substringBefore(" / ") })
        assertTrue(transitions.all { it.message.endsWith("SCORE") })
        assertTrue(controller.state.value.events.zipWithNext().all { (a, b) ->
            a.sequence < b.sequence && a.timeMs <= b.timeMs
        })
    }

    @Test fun scoreBasedRestGuidanceKeepsGeneralRemindersAndCertificationRecoveryGate() = runTest {
        val controller = newController()
        controller.setGear(Gear.D)
        controller.applyAssessment(90f, trending = true)
        controller.advanceTime(30_000)
        runCurrent()
        assertEquals(InterventionStage.CHECK, controller.state.value.intervention.stage)
        controller.advanceTime(30_000)
        controller.setGear(Gear.P)
        controller.advanceTime(900_000)
        runCurrent()
        assertEquals(InterventionStage.REST_GUIDANCE, controller.state.value.intervention.stage)
        assertEquals(TransitionReason.SCORE, controller.state.value.intervention.reason)
        assertEquals(0L, controller.state.value.trip.continuousDriveSec)
        assertEquals(60L, controller.state.value.trip.last24hDriveSec)
        val speech = controller.state.value.requests.map { it.request }.filterIsInstance<SpeechRequest>()
        assertTrue(speech.size > 1)
        assertTrue(speech.all { it.cue == SpeechCue.REST_RECOMMENDED })
        controller.certifyRest()
        runCurrent()
        assertEquals(InterventionStage.REST_GUIDANCE, controller.state.value.intervention.stage)
        controller.completeCheck(controller.latestCheck(CheckContext.RECOVERY).requestId, CheckOutcome.NORMAL)
        runCurrent()
        assertEquals(InterventionStage.NORMAL, controller.state.value.intervention.stage)
        assertEquals(TransitionReason.REST_COMPLETED, controller.state.value.intervention.reason)
        assertEquals(60L, controller.state.value.trip.last24hDriveSec)
        assertEquals(ExecutionStatus.NOT_CONNECTED,
            controller.state.value.requests.single { it.request is RestRecoveryResetRequest }.status)
    }

    @Test fun twoDistinctSlowChecksEscalateAndDuplicateResultsDoNotCountTwice() = runTest {
        val controller = newController()
        enterCheck(controller)
        val first = controller.latestCheck(CheckContext.DRIVER_ALERTNESS)
        assertEquals(ExecutionStatus.STARTED, controller.statusOf(first.requestId))
        controller.completeCheck(first.requestId, CheckOutcome.SLOW)
        controller.completeCheck(first.requestId, CheckOutcome.SLOW)
        runCurrent()
        assertEquals(ExecutionStatus.SUCCEEDED, controller.statusOf(first.requestId))
        assertEquals(InterventionStage.CHECK, controller.state.value.intervention.stage)

        controller.advanceTime(29_999)
        runCurrent()
        assertEquals(first.requestId, controller.latestCheck(CheckContext.DRIVER_ALERTNESS).requestId)
        controller.advanceTime(1)
        runCurrent()
        val second = controller.latestCheck(CheckContext.DRIVER_ALERTNESS)
        assertNotEquals(first.requestId, second.requestId)
        controller.completeCheck(second.requestId, CheckOutcome.SLOW)
        runCurrent()
        assertEquals(InterventionStage.REST_GUIDANCE, controller.state.value.intervention.stage)
        assertEquals(TransitionReason.CHECK_RESULT, controller.state.value.intervention.reason)
    }

    @Test fun certificationRequiresItsOwnNormalRecoveryBeforeReleasingStageThree() = runTest {
        val controller = newController()
        enterRestGuidance(controller)
        controller.completeCheck(RequestId("missing-recovery"), CheckOutcome.NORMAL, ResponseIntent.REFRESHED)
        runCurrent()
        assertEquals(InterventionStage.REST_GUIDANCE, controller.state.value.intervention.stage)

        controller.certifyRest()
        runCurrent()
        assertEquals(InterventionStage.REST_GUIDANCE, controller.state.value.intervention.stage)
        val recovery = controller.latestCheck(CheckContext.RECOVERY)
        assertEquals(ExecutionStatus.STARTED, controller.statusOf(recovery.requestId))
        controller.completeCheck(recovery.requestId, CheckOutcome.NORMAL, ResponseIntent.REFRESHED)
        runCurrent()
        assertEquals(InterventionStage.NORMAL, controller.state.value.intervention.stage)
        assertEquals(TransitionReason.REST_COMPLETED, controller.state.value.intervention.reason)
        assertEquals(EvaluationStatus.NOT_EVALUATED, controller.state.value.intervention.evaluationStatus)
        val reset = controller.state.value.requests.single { it.request is RestRecoveryResetRequest }
        assertEquals(ExecutionStatus.NOT_CONNECTED, reset.status)
    }

    @Test fun aResolvedSearchProducesSpeechAndOnlyExplicitCompletionMarksItSucceeded() = runTest {
        val controller = newController()
        controller.applyAssessment(40f, trending = false)
        runCurrent()
        val search = controller.latestSearch()
        controller.resolveRest(search.requestId, found = true)
        runCurrent()
        assertEquals(ExecutionStatus.SUCCEEDED, controller.statusOf(search.requestId))
        val speech = controller.state.value.requests.single { it.request is SpeechRequest }
        assertEquals(ExecutionStatus.STARTED, speech.status)
        controller.completeExecution(speech.request.requestId, succeeded = true)
        runCurrent()
        assertEquals(ExecutionStatus.SUCCEEDED, controller.statusOf(speech.request.requestId))
        assertTrue(controller.state.value.requests.none { it.request is CheckRequest })
    }

    @Test fun anEmptySearchProducesOnlyTheNoRestAreaSpeechAndNoConsentRequest() = runTest {
        val controller = newController()
        controller.applyAssessment(40f, trending = false)
        runCurrent()
        val search = controller.latestSearch()
        controller.resolveRest(search.requestId, found = false)
        runCurrent()

        assertEquals(ExecutionStatus.SUCCEEDED, controller.statusOf(search.requestId))
        val speech = controller.state.value.requests.single { it.request is SpeechRequest }
        assertEquals(SpeechCue.NO_REST_AREA, (speech.request as SpeechRequest).cue)
        assertEquals(ExecutionStatus.STARTED, speech.status)
        assertTrue(controller.state.value.requests.none { it.request is CheckRequest })
        assertTrue(controller.state.value.requests.none {
            (it.request as? RestRequest)?.action == RestAction.ADD_WAYPOINT
        })
    }

    @Test fun aFailedSearchKeepsItsFailureAndRejectsALateCandidateWithoutAnnouncingIt() = runTest {
        val controller = newController()
        controller.applyAssessment(40f, trending = false)
        runCurrent()
        val search = controller.latestSearch()
        controller.completeExecution(search.requestId, succeeded = false)
        runCurrent()
        assertEquals(ExecutionStatus.FAILED, controller.statusOf(search.requestId))

        controller.resolveRest(search.requestId, found = true)
        controller.completeExecution(search.requestId, succeeded = true)
        runCurrent()
        assertEquals(ExecutionStatus.FAILED, controller.statusOf(search.requestId))
        assertEquals(InterventionStage.CAUTION, controller.state.value.intervention.stage)
        assertTrue(controller.state.value.requests.none { it.request is SpeechRequest || it.request is CheckRequest })
        assertTrue(controller.state.value.events.any { it.message.contains("가상 실행 실패") })
        assertTrue(controller.state.value.events.any { it.message.contains("검색 결과 거부") })
    }

    @Test fun cancelledSearchAndCheckResultsCannotChangeTheCurrentStageOrCreateActions() = runTest {
        val controller = newController()
        controller.applyAssessment(90f, trending = true)
        runCurrent()
        val cancelledSearch = controller.latestSearch()
        controller.advanceTime(60_000)
        runCurrent()
        val cancelledCheck = controller.latestCheck(CheckContext.DRIVER_ALERTNESS)
        val requestCount = controller.state.value.requests.size
        controller.resolveRest(cancelledSearch.requestId, found = true)
        controller.completeCheck(cancelledCheck.requestId, CheckOutcome.NORMAL)
        runCurrent()
        assertEquals(InterventionStage.REST_GUIDANCE, controller.state.value.intervention.stage)
        assertEquals(requestCount, controller.state.value.requests.size)
        assertEquals(ExecutionStatus.CANCELLED, controller.statusOf(cancelledSearch.requestId))
        assertEquals(ExecutionStatus.CANCELLED, controller.statusOf(cancelledCheck.requestId))
    }

    @Test fun waypointNoResponseUsesVirtualGraceTimeAndCreatesOnlyOneAddRequest() = runTest {
        val controller = newController()
        enterRestGuidance(controller)
        controller.resolveRest(controller.latestSearch().requestId, found = true)
        runCurrent()
        val consent = controller.latestCheck(CheckContext.WAYPOINT_CONSENT)
        controller.completeCheck(consent.requestId, CheckOutcome.NO_RESPONSE)
        runCurrent()
        controller.advanceTime(9_999)
        runCurrent()
        assertTrue(controller.state.value.requests.none {
            (it.request as? RestRequest)?.action == RestAction.ADD_WAYPOINT
        })
        controller.advanceTime(1)
        runCurrent()
        val add = controller.state.value.requests.single {
            (it.request as? RestRequest)?.action == RestAction.ADD_WAYPOINT
        }
        assertEquals(ExecutionStatus.STARTED, add.status)
        controller.completeExecution(add.request.requestId, succeeded = true)
        controller.advanceTime(30_000)
        runCurrent()
        assertEquals(ExecutionStatus.SUCCEEDED, controller.statusOf(add.request.requestId))
        assertEquals(1, controller.state.value.requests.count {
            (it.request as? RestRequest)?.action == RestAction.ADD_WAYPOINT
        })
    }

    @Test fun resetDropsOldRequestsCountersAndRetryTimersAndRejectsOldSessionCallbacks() = runTest {
        val controller = newController()
        controller.setGear(Gear.D)
        enterCheck(controller)
        val old = controller.latestCheck(CheckContext.DRIVER_ALERTNESS)
        controller.completeCheck(old.requestId, CheckOutcome.SLOW)
        runCurrent()
        controller.reset()
        runCurrent()
        assertEquals(0L, controller.state.value.timeMs)
        assertEquals(0L, controller.state.value.trip.last24hDriveSec)
        assertNull(controller.state.value.trip.tripId)
        assertEquals(1, controller.state.value.events.size)
        assertTrue(controller.state.value.requests.isEmpty())

        controller.completeCheck(old.requestId, CheckOutcome.NO_RESPONSE)
        controller.advanceTime(60_000)
        runCurrent()
        assertEquals(InterventionStage.NORMAL, controller.state.value.intervention.stage)
        assertEquals(0L, controller.state.value.trip.continuousDriveSec)
        assertEquals(0L, controller.state.value.trip.last24hDriveSec)
        assertTrue(controller.state.value.requests.isEmpty())

        enterCheck(controller)
        val current = controller.latestCheck(CheckContext.DRIVER_ALERTNESS)
        assertNotEquals(old.requestId, current.requestId)
        controller.completeCheck(old.requestId, CheckOutcome.NO_RESPONSE)
        runCurrent()
        assertEquals(InterventionStage.CHECK, controller.state.value.intervention.stage)
        assertEquals(ExecutionStatus.STARTED, controller.statusOf(current.requestId))
    }
}
