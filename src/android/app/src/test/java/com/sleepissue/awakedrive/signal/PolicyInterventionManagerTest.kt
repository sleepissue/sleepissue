package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.intervention.*
import com.sleepissue.awakedrive.engine.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PolicyInterventionManagerTest {
    private class Harness(val scope: TestScope) {
        val clock = FakeClock()
        val output = mutableListOf<ExecutionRequest>()
        private var nextAction = 0
        private var nextCommand = 0
        val manager = PolicyInterventionManager(
            InterventionPolicy(EngineConfig()), EngineConfig(), clock,
            Source.REAL, RequestTimerBasis.ELAPSED_REALTIME,
        ) { RequestId("action-${++nextAction}") }
        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                manager.requests.collect { output += it }
            }
        }
        suspend fun start() { manager.start(RequestId("start")); scope.runCurrent() }
        suspend fun send(input: InterventionInput, id: RequestId = RequestId("command-${++nextCommand}")): ExecutionResult {
            val result = manager.submit(id, input); scope.runCurrent(); return result
        }
        fun at(ms: Long) { clock.scenarioMs = ms; clock.elapsedMs = ms }
        suspend fun score(value: Float = 65f, assisted: Boolean = false) = send(InterventionInput.Assessment(
            DrivingAssessment(clock.scenarioMs, EvaluationStatus.EVALUATED, value, DrivingPattern.UNCERTAIN, assisted)))
        suspend fun checking(consent: Boolean = false) {
            start()
            if (consent) send(InterventionInput.VentilationConsentChanged(true))
            score(); at(30_000); score()
        }
        suspend fun restGuidance() {
            start()
            send(InterventionInput.Safety(SafetyEvent(clock.scenarioMs, SafetyEventType.REPEATED_RAPID_STEERING, Source.REAL)))
        }
        fun latestCheck(context: CheckContext) = output.filterIsInstance<CheckRequest>().last { it.context == context }
        suspend fun started(request: CheckRequest) {
            manager.onResult(ExecutionResult(request.requestId, ExecutionStatus.STARTED)); scope.runCurrent()
        }
        suspend fun finish(request: CheckRequest, outcome: CheckOutcome, intent: ResponseIntent? = null,
            commandId: RequestId? = null): ExecutionResult {
            val input = InterventionInput.CheckCompleted(CheckResult(request.requestId, request.context, outcome, intent = intent))
            return if (commandId == null) send(input) else send(input, commandId)
        }
        suspend fun candidate(progressFirst: Boolean = false) {
            val find = output.filterIsInstance<RestRequest>().last { it.action == RestAction.FIND_AHEAD }
            if (progressFirst) send(InterventionInput.RestAreaResolved(RestResult(ExecutionResult(find.requestId, ExecutionStatus.STARTED))))
            send(InterventionInput.RestAreaResolved(RestResult(ExecutionResult(find.requestId, ExecutionStatus.SUCCEEDED),
                RestAreaCandidate("area-1", "쉼터", GeoPoint(37.0, 127.0), 5_000f))))
        }
    }

    @Test fun frequentChecksUsesTenMinutesAndDefaultUsesTwenty() = runTest {
        for (frequent in listOf(false, true)) {
            val h = Harness(this); h.start()
            h.send(InterventionInput.FrequentChecksChanged(frequent))
            h.score(0f, assisted = true)
            h.at(600_000); h.score(0f, assisted = true)
            assertEquals(frequent, h.manager.state.value.stage != InterventionStage.NORMAL)
            if (!frequent) {
                h.at(1_200_000); h.score(0f, assisted = true)
                assertTrue(h.manager.state.value.stage != InterventionStage.NORMAL)
            }
        }
    }

    @Test fun disablingFrequentChecksDoesNotDelayVulnerableContext() = runTest {
        val h = Harness(this); h.start()
        h.send(InterventionInput.FrequentChecksChanged(false))
        h.send(InterventionInput.VulnerableTimeChanged(true))
        h.score(0f, assisted = true)
        h.at(600_000); h.score(0f, assisted = true)
        assertTrue(h.output.filterIsInstance<CheckRequest>().isNotEmpty())
    }

    @Test fun passengerRestResponseEscalatesThroughExistingRequest() = runTest {
        val h = Harness(this); h.checking()
        val request = h.latestCheck(CheckContext.DRIVER_ALERTNESS)
        h.started(request)
        h.send(InterventionInput.CheckCompleted(CheckResult(request.requestId, request.context,
            CheckOutcome.NORMAL, intent = ResponseIntent.REST, channel = ResponseChannel.PASSENGER_BUTTON)))
        assertEquals(InterventionStage.REST_GUIDANCE, h.manager.state.value.stage)
        assertEquals(TransitionReason.CHECK_RESULT, h.manager.state.value.reason)
    }

    @Test fun cautionRequestsAForwardAreaAndNeverAsksAQuestion() = runTest {
        val h = Harness(this); h.start(); h.score(40f)
        assertEquals(InterventionStage.CAUTION, h.manager.state.value.stage)
        assertEquals(1, h.output.filterIsInstance<RestRequest>().size)
        assertTrue(h.output.filterIsInstance<CheckRequest>().isEmpty())
        h.candidate(progressFirst = true)
        assertEquals("area-1", h.output.filterIsInstance<SpeechRequest>().single().restArea?.id)
        assertTrue(h.output.filterIsInstance<CheckRequest>().isEmpty())
    }

    @Test fun checkRequestsVentilationOnlyWithPriorConsent() = runTest {
        val noConsent = Harness(this); noConsent.checking()
        assertTrue(noConsent.output.filterIsInstance<ClimateRequest>().isEmpty())
        val consent = Harness(this); consent.checking(consent = true)
        assertEquals(1, consent.output.filterIsInstance<ClimateRequest>().size)
        assertTrue(consent.output.filterIsInstance<ClimateRequest>().single().priorConsent)
        assertEquals(CheckContext.DRIVER_ALERTNESS, consent.latestCheck(CheckContext.DRIVER_ALERTNESS).context)
    }

    @Test fun duplicatedSlowResultIsIgnoredAndRealSecondSlowEscalatesImmediately() = runTest {
        val h = Harness(this); h.checking()
        val first = h.latestCheck(CheckContext.DRIVER_ALERTNESS); h.started(first)
        h.finish(first, CheckOutcome.SLOW, commandId = RequestId("slow-result"))
        h.finish(first, CheckOutcome.SLOW, commandId = RequestId("slow-result"))
        h.finish(first, CheckOutcome.SLOW)
        assertEquals(InterventionStage.CHECK, h.manager.state.value.stage)
        h.at(59_999); h.send(InterventionInput.Tick)
        assertEquals(1, h.output.filterIsInstance<CheckRequest>().size)
        h.at(60_000); h.send(InterventionInput.Tick)
        val second = h.latestCheck(CheckContext.DRIVER_ALERTNESS)
        assertNotEquals(first.requestId, second.requestId)
        h.started(second); h.finish(second, CheckOutcome.SLOW)
        assertEquals(InterventionStage.REST_GUIDANCE, h.manager.state.value.stage)
        assertEquals(TransitionReason.CHECK_RESULT, h.manager.state.value.reason)
        assertEquals(SpeechCue.REST_RECOMMENDED, h.output.filterIsInstance<SpeechRequest>().single().cue)
    }

    @Test fun failureAndUnstartedOrWrongContextResultsCannotBecomeHumanNoResponse() = runTest {
        val h = Harness(this); h.checking()
        val request = h.latestCheck(CheckContext.DRIVER_ALERTNESS)
        assertEquals(ExecutionStatus.FAILED, h.finish(request, CheckOutcome.NO_RESPONSE).status)
        h.started(request)
        h.send(InterventionInput.CheckCompleted(CheckResult(request.requestId, CheckContext.RECOVERY, CheckOutcome.NORMAL)))
        h.manager.onResult(ExecutionResult(request.requestId, ExecutionStatus.FAILED, ExecutionFailure.PERMISSION_DENIED))
        h.finish(request, CheckOutcome.NO_RESPONSE)
        assertEquals(InterventionStage.CHECK, h.manager.state.value.stage)
    }

    @Test fun consentNoResponseWaitsTenActualSecondsDespiteAcceleratedScenario() = runTest {
        val h = Harness(this); h.restGuidance(); h.candidate()
        val consent = h.latestCheck(CheckContext.WAYPOINT_CONSENT); h.started(consent)
        h.at(5_000); h.finish(consent, CheckOutcome.NO_RESPONSE)
        assertEquals("area-1", consent.restArea?.id)
        assertEquals(consent.requestId, h.manager.state.value.pendingRequestId)
        h.clock.scenarioMs = 600_000; h.clock.elapsedMs = 14_999
        h.send(InterventionInput.Tick)
        assertTrue(h.output.filterIsInstance<RestRequest>().none { it.action == RestAction.ADD_WAYPOINT })
        h.clock.elapsedMs = 15_000; h.send(InterventionInput.Tick); h.send(InterventionInput.Tick)
        assertEquals(1, h.output.filterIsInstance<RestRequest>().count { it.action == RestAction.ADD_WAYPOINT })
    }

    @Test fun cancellationDuringGraceAndLateSuccessCannotAddWaypoint() = runTest {
        val h = Harness(this); h.restGuidance(); h.candidate()
        val consent = h.latestCheck(CheckContext.WAYPOINT_CONSENT); h.started(consent)
        h.finish(consent, CheckOutcome.NO_RESPONSE)
        assertEquals(ExecutionStatus.CANCELLED, h.manager.cancel(consent.requestId).status)
        h.manager.onResult(ExecutionResult(consent.requestId, ExecutionStatus.SUCCEEDED))
        h.finish(consent, CheckOutcome.NORMAL, ResponseIntent.YES)
        h.at(20_000); h.send(InterventionInput.Tick)
        assertTrue(h.output.filterIsInstance<RestRequest>().none { it.action == RestAction.ADD_WAYPOINT })
    }

    @Test fun explicitConsentAddsOnceButCompletedWaypointRemovalIsNotFaked() = runTest {
        val h = Harness(this); h.restGuidance(); h.candidate()
        val consent = h.latestCheck(CheckContext.WAYPOINT_CONSENT); h.started(consent)
        h.finish(consent, CheckOutcome.NORMAL, ResponseIntent.YES)
        h.finish(consent, CheckOutcome.NORMAL, ResponseIntent.YES)
        val added = h.output.filterIsInstance<RestRequest>().single { it.action == RestAction.ADD_WAYPOINT }
        h.manager.onResult(ExecutionResult(added.requestId, ExecutionStatus.SUCCEEDED))
        val cancelled = h.manager.cancel(added.requestId)
        assertEquals(ExecutionStatus.FAILED, cancelled.status)
        assertEquals(ExecutionFailure.NOT_SUPPORTED, cancelled.failure)
    }

    @Test fun certificationAloneKeepsStageThreeAndNormalRecoveryEmitsOneReset() = runTest {
        val h = Harness(this); h.restGuidance()
        h.send(InterventionInput.RestCertified(RestCertification("rest-a", 0, Source.REAL)))
        assertEquals(InterventionStage.REST_GUIDANCE, h.manager.state.value.stage)
        val recovery = h.latestCheck(CheckContext.RECOVERY); h.started(recovery)
        h.finish(recovery, CheckOutcome.NORMAL); h.finish(recovery, CheckOutcome.NORMAL)
        assertEquals(InterventionStage.NORMAL, h.manager.state.value.stage)
        assertEquals(TransitionReason.REST_COMPLETED, h.manager.state.value.reason)
        assertEquals(EvaluationStatus.NOT_EVALUATED, h.manager.state.value.evaluationStatus)
        val reset = h.output.filterIsInstance<RestRecoveryResetRequest>().single()
        assertEquals("rest-a", reset.restSessionId)
        assertTrue(h.output.filterIsInstance<CancelExecutionRequest>().none { it.requestId == reset.requestId })
    }

    @Test fun oldRestCertificateAndOldAssessmentCannotReplaceCurrentSession() = runTest {
        val h = Harness(this); h.restGuidance()
        h.send(InterventionInput.RestCertified(RestCertification("rest-a", 0, Source.REAL)))
        h.at(1); h.send(InterventionInput.RestCertified(RestCertification("rest-b", 1, Source.REAL)))
        val count = h.output.filterIsInstance<CheckRequest>().count { it.context == CheckContext.RECOVERY }
        h.send(InterventionInput.RestCertified(RestCertification("rest-a", 0, Source.REAL)))
        assertEquals(count, h.output.filterIsInstance<CheckRequest>().count { it.context == CheckContext.RECOVERY })
        val old = h.send(InterventionInput.Assessment(DrivingAssessment(0, EvaluationStatus.EVALUATED, 0f)))
        // 판정의 최신 시각은 실제 새 판정을 받은 뒤 비교한다.
        assertEquals(ExecutionStatus.REQUESTED, old.status)
        h.score(65f)
        assertEquals(ExecutionStatus.FAILED, h.send(InterventionInput.Assessment(DrivingAssessment(0))).status)
        h.send(InterventionInput.Forecast(ForecastAssessment(1)))
        assertEquals(ExecutionStatus.FAILED, h.send(InterventionInput.Forecast(ForecastAssessment(0))).status)
    }

    @Test fun regularAssistedCheckOccursAgainWhileRemainingAtStageTwo() = runTest {
        val h = Harness(this); h.start(); h.score(65f, assisted = true)
        h.at(30_000); h.score(65f, assisted = true)
        val first = h.latestCheck(CheckContext.DRIVER_ALERTNESS); h.started(first)
        h.finish(first, CheckOutcome.NORMAL)
        h.at(1_229_999); h.send(InterventionInput.Tick)
        assertEquals(1, h.output.filterIsInstance<CheckRequest>().size)
        h.at(1_230_000); h.send(InterventionInput.Tick)
        assertEquals(2, h.output.filterIsInstance<CheckRequest>().size)
        assertEquals(InterventionStage.CHECK, h.manager.state.value.stage)
    }

    @Test fun stoppingDropsQueuedStartsAndLateCallbacksCannotChangeStage() = runTest {
        val h = Harness(this); h.checking()
        val check = h.latestCheck(CheckContext.DRIVER_ALERTNESS); h.started(check)
        h.manager.stop(RequestId("stop")); h.scope.runCurrent()
        val before = h.manager.state.value.stage
        h.manager.onResult(ExecutionResult(check.requestId, ExecutionStatus.SUCCEEDED))
        assertEquals(ExecutionStatus.FAILED, h.finish(check, CheckOutcome.NO_RESPONSE).status)
        assertEquals(before, h.manager.state.value.stage)
        assertNull(h.manager.state.value.pendingRequestId)
        assertTrue(h.output.filterIsInstance<CancelExecutionRequest>().any { it.requestId == check.requestId })
    }

    @Test fun refusalAndStageThreeReminderAtSameDeadlineDoNotDuplicateSearch() = runTest {
        val h = Harness(this); h.restGuidance(); h.candidate()
        val consent = h.latestCheck(CheckContext.WAYPOINT_CONSENT); h.started(consent)
        h.finish(consent, CheckOutcome.NORMAL, ResponseIntent.NO)
        h.at(299_999); h.send(InterventionInput.Tick)
        assertEquals(1, h.output.filterIsInstance<RestRequest>().count { it.action == RestAction.FIND_AHEAD })
        h.at(300_000); h.send(InterventionInput.Tick)
        assertEquals(2, h.output.filterIsInstance<RestRequest>().count { it.action == RestAction.FIND_AHEAD })
        assertTrue(h.output.filterIsInstance<RestRequest>().none { it.action == RestAction.ADD_WAYPOINT })
    }

    @Test fun staleCancelCannotClearAnotherConsentRequestsGraceTimer() = runTest {
        val h = Harness(this); h.restGuidance(); h.candidate()
        val first = h.latestCheck(CheckContext.WAYPOINT_CONSENT); h.started(first)
        h.finish(first, CheckOutcome.NORMAL, ResponseIntent.NO)
        h.at(300_000); h.send(InterventionInput.Tick); h.candidate()
        val second = h.latestCheck(CheckContext.WAYPOINT_CONSENT)
        assertNotEquals(first.requestId, second.requestId)
        h.started(second); h.finish(second, CheckOutcome.NO_RESPONSE)
        h.manager.cancel(first.requestId)
        h.at(310_000); h.send(InterventionInput.Tick)
        assertEquals(1, h.output.filterIsInstance<RestRequest>().count { it.action == RestAction.ADD_WAYPOINT })
    }

    @Test fun twoKilometerCrossingAndTimedReminderAreOneNotice() = runTest {
        val h = Harness(this); h.restGuidance(); h.candidate()
        val consent = h.latestCheck(CheckContext.WAYPOINT_CONSENT); h.started(consent)
        h.finish(consent, CheckOutcome.NORMAL, ResponseIntent.YES)
        val added = h.output.filterIsInstance<RestRequest>().single { it.action == RestAction.ADD_WAYPOINT }
        h.manager.onResult(ExecutionResult(added.requestId, ExecutionStatus.SUCCEEDED))
        h.at(300_000); h.send(InterventionInput.RestAreaDistance("area-1", 2_000f))
        h.send(InterventionInput.Tick)
        assertEquals(2, h.output.filterIsInstance<SpeechRequest>().count { it.cue == SpeechCue.SAFETY_REST_RECOMMENDED })
        assertEquals(1, h.output.filterIsInstance<RestRequest>().count { it.action == RestAction.ADD_WAYPOINT })
    }

    @Test fun recoveryResetRequestSurvivesLaterStageTransitions() = runTest {
        val h = Harness(this); h.restGuidance()
        h.send(InterventionInput.RestCertified(RestCertification("rest-a", 0, Source.REAL)))
        val recovery = h.latestCheck(CheckContext.RECOVERY); h.started(recovery)
        h.finish(recovery, CheckOutcome.NORMAL)
        val reset = h.output.filterIsInstance<RestRecoveryResetRequest>().single()
        h.at(30_000); h.score()
        assertEquals(InterventionStage.CAUTION, h.manager.state.value.stage)
        assertTrue(h.output.filterIsInstance<CancelExecutionRequest>().none { it.requestId == reset.requestId })
        assertEquals(ExecutionStatus.STARTED, h.manager.onResult(ExecutionResult(reset.requestId, ExecutionStatus.STARTED)).status)
    }

    @Test fun newSafetyCheckPreventsDescentUntilItsOwnResponse() = runTest {
        val h = Harness(this); h.checking()
        val first = h.latestCheck(CheckContext.DRIVER_ALERTNESS); h.started(first)
        h.score(50f); h.finish(first, CheckOutcome.NORMAL)
        h.at(630_000)
        h.send(InterventionInput.Safety(SafetyEvent(630_000, SafetyEventType.RAPID_STEERING, Source.REAL)))
        val second = h.latestCheck(CheckContext.DRIVER_ALERTNESS)
        assertNotEquals(first.requestId, second.requestId)
        h.at(630_001); h.send(InterventionInput.Tick)
        assertEquals(InterventionStage.CHECK, h.manager.state.value.stage)
    }

    @Test fun queuedStartCancelledBeforeConsumerAttachesIsNeverDelivered() = runTest {
        val clock = FakeClock()
        var id = 0
        val manager = PolicyInterventionManager(InterventionPolicy(EngineConfig(), FirstEscalation.IMMEDIATE),
            EngineConfig(), clock, Source.REAL, RequestTimerBasis.ELAPSED_REALTIME) { RequestId("action-${++id}") }
        manager.start(RequestId("start"))
        manager.submit(RequestId("assessment"), InterventionInput.Assessment(DrivingAssessment(0, EvaluationStatus.EVALUATED, 40f)))
        manager.stop(RequestId("stop"))
        val output = mutableListOf<ExecutionRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { manager.requests.collect { output += it } }
        runCurrent()
        assertTrue(output.isEmpty())
    }

    @Test fun vulnerableAssistedCheckUsesTenMinutesInsteadOfTwenty() = runTest {
        val h = Harness(this); h.start(); h.send(InterventionInput.VulnerableTimeChanged(true))
        h.score(65f, assisted = true); h.at(30_000); h.score(65f, assisted = true)
        val first = h.latestCheck(CheckContext.DRIVER_ALERTNESS); h.started(first); h.finish(first, CheckOutcome.NORMAL)
        h.at(629_999); h.send(InterventionInput.Tick)
        assertEquals(1, h.output.filterIsInstance<CheckRequest>().size)
        h.at(630_000); h.send(InterventionInput.Tick)
        assertEquals(2, h.output.filterIsInstance<CheckRequest>().size)
    }

    @Test fun recoveryCheckRemainsValidAcrossAnUnrelatedStageDescent() = runTest {
        val h = Harness(this); h.checking()
        val driver = h.latestCheck(CheckContext.DRIVER_ALERTNESS); h.started(driver)
        h.score(50f); h.finish(driver, CheckOutcome.NORMAL)
        h.at(630_000); h.send(InterventionInput.RestCertified(RestCertification("rest-a", 630_000, Source.REAL)))
        assertEquals(InterventionStage.CAUTION, h.manager.state.value.stage)
        val recovery = h.latestCheck(CheckContext.RECOVERY); h.started(recovery)
        h.finish(recovery, CheckOutcome.NORMAL)
        assertEquals(InterventionStage.NORMAL, h.manager.state.value.stage)
        assertEquals("rest-a", h.output.filterIsInstance<RestRecoveryResetRequest>().single().restSessionId)
    }

    @Test fun quickResponseWithSleepyIntentDoesNotPretendRecoverySucceeded() = runTest {
        val h = Harness(this); h.restGuidance()
        h.send(InterventionInput.RestCertified(RestCertification("rest-a", 0, Source.REAL)))
        val recovery = h.latestCheck(CheckContext.RECOVERY); h.started(recovery)
        h.finish(recovery, CheckOutcome.NORMAL, ResponseIntent.SLEEPY)
        assertEquals(InterventionStage.REST_GUIDANCE, h.manager.state.value.stage)
        assertTrue(h.output.filterIsInstance<RestRecoveryResetRequest>().isEmpty())
    }

    @Test fun unconfirmedStageTwoRestSuggestionIsDisabledForSlowAndNormalResults() = runTest {
        listOf(CheckOutcome.SLOW, CheckOutcome.NORMAL).forEach { outcome ->
            val h = Harness(this); h.checking()
            val searchCount = h.output.filterIsInstance<RestRequest>().size // 1단계에서 발생한 검색만 있다.
            val oldSearch = h.output.filterIsInstance<RestRequest>().single()
            h.candidate() // 취소된 1단계 검색 결과는 2단계 안내로 사용할 수 없다.
            val check = h.latestCheck(CheckContext.DRIVER_ALERTNESS); h.started(check)
            h.finish(check, outcome)
            assertEquals(InterventionStage.CHECK, h.manager.state.value.stage)
            assertEquals(searchCount, h.output.filterIsInstance<RestRequest>().size)
            assertTrue(h.output.filterIsInstance<SpeechRequest>().none { it.cue == SpeechCue.REST_AREA_AHEAD })
            assertTrue(h.output.filterIsInstance<CheckRequest>().none { it.context == CheckContext.WAYPOINT_CONSENT })
            assertTrue(h.output.filterIsInstance<CancelExecutionRequest>().any { it.requestId == oldSearch.requestId })
        }
    }

    @Test fun passengerTargetIsChosenExternallyAndSupersededDriverResponseIsIgnored() = runTest {
        val h = Harness(this); h.checking()
        val driver = h.latestCheck(CheckContext.DRIVER_ALERTNESS); h.started(driver)
        h.send(InterventionInput.AlertnessTargetChanged(CheckContext.PASSENGER_ALERTNESS))
        val passenger = h.latestCheck(CheckContext.PASSENGER_ALERTNESS); h.started(passenger)
        h.finish(driver, CheckOutcome.NO_RESPONSE)
        assertEquals(InterventionStage.CHECK, h.manager.state.value.stage)
        h.finish(passenger, CheckOutcome.NORMAL, ResponseIntent.REST)
        assertEquals(InterventionStage.REST_GUIDANCE, h.manager.state.value.stage)
    }

    @Test fun staleAreaResultCannotActAsATickThatAdvancesTheStage() = runTest {
        val h = Harness(this); h.start()
        h.send(InterventionInput.Assessment(DrivingAssessment(0, EvaluationStatus.EVALUATED, 100f, DrivingPattern.TRENDING)))
        val old = h.output.filterIsInstance<RestRequest>().single()
        h.at(30_000)
        h.send(InterventionInput.Assessment(DrivingAssessment(30_000, EvaluationStatus.EVALUATED, 100f, DrivingPattern.TRENDING)))
        h.at(60_000)
        val rejected = h.send(InterventionInput.RestAreaResolved(RestResult(ExecutionResult(old.requestId, ExecutionStatus.SUCCEEDED))))
        assertEquals(ExecutionStatus.FAILED, rejected.status)
        assertEquals(InterventionStage.CHECK, h.manager.state.value.stage)
        h.send(InterventionInput.Tick)
        assertEquals(InterventionStage.REST_GUIDANCE, h.manager.state.value.stage)
    }

    @Test fun timeCautionRemindsAtThirtyMinutesAndCertificationReleasesItImmediately() = runTest {
        val h = Harness(this); h.start(); h.score(0f)
        h.send(InterventionInput.TripChanged(TripState(continuousDriveSec = 7_200)))
        h.candidate()
        assertEquals(InterventionStage.CAUTION, h.manager.state.value.stage)
        assertEquals(TransitionReason.TIME, h.manager.state.value.reason)
        h.at(1_799_999); h.send(InterventionInput.Tick)
        assertEquals(1, h.output.filterIsInstance<RestRequest>().size)
        h.at(1_800_000); h.send(InterventionInput.Tick); h.candidate()
        assertEquals(2, h.output.filterIsInstance<RestRequest>().size)
        h.at(1_800_001)
        h.send(InterventionInput.RestCertified(RestCertification("rest-time", h.clock.scenarioMs, Source.REAL)))
        assertEquals(InterventionStage.NORMAL, h.manager.state.value.stage)
        assertEquals(EvaluationStatus.EVALUATED, h.manager.state.value.evaluationStatus)
        assertTrue(h.output.filterIsInstance<RestRecoveryResetRequest>().isEmpty())
        h.at(3_600_001); h.send(InterventionInput.Tick)
        assertEquals(InterventionStage.NORMAL, h.manager.state.value.stage)
        assertEquals(2, h.output.filterIsInstance<RestRequest>().size)
    }

    @Test fun forecastCautionDiscardsPreRestForecastButReevaluatesNewForecast() = runTest {
        val h = Harness(this); h.start(); h.score(0f)
        h.send(InterventionInput.Forecast(ForecastAssessment(0, EvaluationStatus.EVALUATED, dueAtMs = 0)))
        assertEquals(TransitionReason.FORECAST, h.manager.state.value.reason)
        h.at(1); h.send(InterventionInput.RestCertified(RestCertification("rest-forecast", 1, Source.REAL)))
        assertEquals(InterventionStage.NORMAL, h.manager.state.value.stage)
        h.at(30_001); h.send(InterventionInput.Tick)
        assertEquals(InterventionStage.NORMAL, h.manager.state.value.stage)
        h.send(InterventionInput.Forecast(ForecastAssessment(30_001, EvaluationStatus.EVALUATED, dueAtMs = 30_001)))
        assertEquals(InterventionStage.CAUTION, h.manager.state.value.stage)
    }

    @Test fun certificationReevaluatesCurrentScoreInsteadOfForcingNormal() = runTest {
        listOf(39.999f to InterventionStage.NORMAL, 40f to InterventionStage.CAUTION,
            60f to InterventionStage.CHECK).forEach { (score, expected) ->
            val h = Harness(this); h.start()
            h.send(InterventionInput.TripChanged(TripState(continuousDriveSec = 7_200)))
            h.at(29_999); h.score(score)
            h.at(30_000); h.send(InterventionInput.RestCertified(RestCertification("rest-score", 30_000, Source.REAL)))
            assertEquals(expected, h.manager.state.value.stage)
            assertEquals(EvaluationStatus.EVALUATED, h.manager.state.value.evaluationStatus)
            if (expected == InterventionStage.CAUTION)
                assertEquals(TransitionReason.SCORE, h.manager.state.value.reason)
        }
    }

    @Test fun certificationRetainsCurrentVulnerableContextAndSafetyRemainsImmediate() = runTest {
        val h = Harness(this); h.start(); h.score(0f)
        h.send(InterventionInput.TripChanged(TripState(continuousDriveSec = 7_200)))
        h.send(InterventionInput.VulnerableTimeChanged(true))
        h.at(1); h.send(InterventionInput.RestCertified(RestCertification("rest-vulnerable", 1, Source.REAL)))
        assertEquals(InterventionStage.CAUTION, h.manager.state.value.stage)
        h.send(InterventionInput.Safety(SafetyEvent(1, SafetyEventType.REPEATED_RAPID_STEERING, Source.REAL)))
        assertEquals(InterventionStage.REST_GUIDANCE, h.manager.state.value.stage)
        assertEquals(1L, h.manager.transitions.value.last().tMs)
    }

    @Test fun foreignOrDuplicateCertificationCannotReleaseANewContextHold() = runTest {
        val h = Harness(this); h.start(); h.score(0f)
        h.send(InterventionInput.TripChanged(TripState(continuousDriveSec = 7_200)))
        assertEquals(ExecutionStatus.FAILED, h.send(InterventionInput.RestCertified(
            RestCertification("demo-rest", 0, Source.DEMO))).status)
        assertEquals(InterventionStage.CAUTION, h.manager.state.value.stage)
        h.at(1); val certified = InterventionInput.RestCertified(RestCertification("rest-a", 1, Source.REAL))
        h.send(certified)
        assertEquals(InterventionStage.NORMAL, h.manager.state.value.stage)
        h.at(30_001)
        h.send(InterventionInput.Forecast(ForecastAssessment(30_001, EvaluationStatus.EVALUATED, dueAtMs = 30_001)))
        h.send(certified)
        assertEquals(InterventionStage.CAUTION, h.manager.state.value.stage)
        assertEquals(1, h.output.filterIsInstance<CheckRequest>().count { it.context == CheckContext.RECOVERY })
    }

    @Test fun stageThreeDoesNotReleaseForSlowUnavailableOrMissingRecovery() = runTest {
        listOf(CheckOutcome.SLOW, CheckOutcome.UNAVAILABLE, CheckOutcome.NO_RESPONSE).forEach { outcome ->
            val h = Harness(this); h.restGuidance()
            val uncertified = h.send(InterventionInput.CheckCompleted(CheckResult(
                RequestId("unknown-recovery"), CheckContext.RECOVERY, CheckOutcome.NORMAL)))
            assertEquals(ExecutionStatus.FAILED, uncertified.status)
            h.send(InterventionInput.RestCertified(RestCertification("rest-a", 0, Source.REAL)))
            val recovery = h.latestCheck(CheckContext.RECOVERY); h.started(recovery)
            h.finish(recovery, outcome)
            assertEquals(InterventionStage.REST_GUIDANCE, h.manager.state.value.stage)
            assertTrue(h.output.filterIsInstance<RestRecoveryResetRequest>().isEmpty())
        }
    }

    @Test fun scoreNinetyUsesImmediateFirstStepThenExactThirtySecondBoundaries() = runTest {
        val h = Harness(this); h.start()
        h.send(InterventionInput.Assessment(DrivingAssessment(0, EvaluationStatus.EVALUATED, 90f, DrivingPattern.TRENDING)))
        assertEquals(InterventionStage.CAUTION, h.manager.state.value.stage)
        h.at(29_999); h.send(InterventionInput.Tick)
        assertEquals(InterventionStage.CAUTION, h.manager.state.value.stage)
        h.at(30_000); h.send(InterventionInput.Tick)
        assertEquals(InterventionStage.CHECK, h.manager.state.value.stage)
        val pending = h.latestCheck(CheckContext.DRIVER_ALERTNESS)
        h.started(pending)
        h.at(59_999); h.send(InterventionInput.Tick)
        assertEquals(InterventionStage.CHECK, h.manager.state.value.stage)
        h.at(60_000); h.send(InterventionInput.Tick)
        assertEquals(InterventionStage.REST_GUIDANCE, h.manager.state.value.stage)
        assertEquals(TransitionReason.SCORE, h.manager.state.value.reason)
        assertEquals(listOf(0L, 30_000L, 60_000L), h.manager.transitions.value.map { it.tMs })
        assertTrue(h.manager.transitions.value.all { it.reason == TransitionReason.SCORE })
        assertEquals(SpeechCue.REST_RECOMMENDED, h.output.filterIsInstance<SpeechRequest>().single().cue)
        assertTrue(h.output.filterIsInstance<CancelExecutionRequest>().any { it.requestId == pending.requestId })
        h.at(360_000); h.send(InterventionInput.Tick)
        assertEquals(listOf(SpeechCue.REST_RECOMMENDED, SpeechCue.REST_RECOMMENDED),
            h.output.filterIsInstance<SpeechRequest>().map { it.cue })
    }

    @Test fun explicitSafetyBypassKeepsImmediateSafetyReasonAndCueWithOrWithoutPendingCheck() = runTest {
        listOf(false, true).forEach { pending ->
            val h = Harness(this)
            if (pending) h.checking() else h.start()
            h.at(if (pending) 30_001 else 1)
            h.send(InterventionInput.Assessment(DrivingAssessment(h.clock.scenarioMs,
                EvaluationStatus.EVALUATED, 90f, DrivingPattern.TRENDING)))
            h.send(InterventionInput.Safety(SafetyEvent(h.clock.scenarioMs,
                SafetyEventType.REPEATED_RAPID_STEERING, Source.REAL)))
            assertEquals(InterventionStage.REST_GUIDANCE, h.manager.state.value.stage)
            assertEquals(TransitionReason.SAFETY_EVENT, h.manager.state.value.reason)
            assertEquals(h.clock.scenarioMs, h.manager.transitions.value.last().tMs)
            assertEquals(SpeechCue.SAFETY_REST_RECOMMENDED,
                h.output.filterIsInstance<SpeechRequest>().single().cue)
        }
    }

    @Test fun humanNoResponseAndRestIntentKeepImmediateCheckReasonAndGeneralCue() = runTest {
        listOf(CheckOutcome.NO_RESPONSE to null, CheckOutcome.NORMAL to ResponseIntent.REST).forEach { (outcome, intent) ->
            val h = Harness(this); h.checking()
            val check = h.latestCheck(CheckContext.DRIVER_ALERTNESS); h.started(check)
            h.at(30_001); h.finish(check, outcome, intent)
            assertEquals(InterventionStage.REST_GUIDANCE, h.manager.state.value.stage)
            assertEquals(TransitionReason.CHECK_RESULT, h.manager.state.value.reason)
            assertEquals(30_001L, h.manager.transitions.value.last().tMs)
            assertEquals(SpeechCue.REST_RECOMMENDED, h.output.filterIsInstance<SpeechRequest>().single().cue)
        }
    }
}
