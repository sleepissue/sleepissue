package com.sleepissue.awakedrive.engine.intervention

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.*
import org.junit.Assert.*
import org.junit.Test

class InterventionPolicyTest {
    private val policy = InterventionPolicy(EngineConfig())
    private fun scored(t: Long, score: Float?, trending: Boolean = false, sensitive: Boolean = false,
        confirmation: CheckResult? = null, checkRequired: Boolean = false) = InterventionSnapshot(
        t, EvaluationStatus.EVALUATED, score, trending, sensitive = sensitive,
        confirmation = confirmation, checkRequired = checkRequired)
    private fun check(outcome: CheckOutcome, context: CheckContext = CheckContext.DRIVER_ALERTNESS,
        intent: ResponseIntent? = null) = CheckResult(RequestId("check"), context, outcome, intent = intent)
    private fun checking(): InterventionPolicyState {
        val caution = policy.evaluate(InterventionPolicyState(), scored(0, 60f)).state
        return policy.evaluate(caution, scored(30_000, 60f)).state
    }

    @Test fun highTrendingScoreRisesOnlyOneStepAtEachThirtySecondBoundary() {
        var state = policy.evaluate(InterventionPolicyState(), scored(0, 90f, true)).state
        assertEquals(InterventionStage.CAUTION, state.publicState.stage)
        state = policy.evaluate(state, scored(29_999, 90f, true)).state
        assertEquals(InterventionStage.CAUTION, state.publicState.stage)
        state = policy.evaluate(state, scored(30_000, 90f, true)).state
        assertEquals(InterventionStage.CHECK, state.publicState.stage)
        state = policy.evaluate(state, scored(59_999, 90f, true)).state
        assertEquals(InterventionStage.CHECK, state.publicState.stage)
        state = policy.evaluate(state, scored(60_000, 90f, true)).state
        assertEquals(InterventionStage.REST_GUIDANCE, state.publicState.stage)
    }

    @Test fun delayedFirstEscalationUsesFirstEvaluationInsteadOfSlidingEveryTick() {
        val delayed = InterventionPolicy(EngineConfig(), FirstEscalation.AFTER_INTERVAL)
        var state = InterventionPolicyState()
        listOf(0L, 10_000L, 20_000L, 29_999L).forEach { t ->
            state = delayed.evaluate(state, scored(t, 80f, true)).state
            assertEquals(InterventionStage.NORMAL, state.publicState.stage)
        }
        assertEquals(InterventionStage.CAUTION, delayed.evaluate(state, scored(30_000, 80f, true)).state.publicState.stage)
    }

    @Test fun highScoreWithoutTrendStopsAtCheckEvenAfterLongTime() {
        var state = checking()
        state = policy.evaluate(state, scored(300_000, 100f)).state
        assertEquals(InterventionStage.CHECK, state.publicState.stage)
    }

    @Test fun normalAndSensitiveThresholdsIncludeTheirExactBoundary() {
        assertEquals(InterventionStage.NORMAL, policy.evaluate(InterventionPolicyState(), scored(0, 39.999f)).state.publicState.stage)
        assertEquals(InterventionStage.CAUTION, policy.evaluate(InterventionPolicyState(), scored(0, 40f)).state.publicState.stage)
        assertEquals(InterventionStage.NORMAL, policy.evaluate(InterventionPolicyState(), scored(0, 29.999f, sensitive = true)).state.publicState.stage)
        var state = policy.evaluate(InterventionPolicyState(), scored(0, 30f, sensitive = true)).state
        state = policy.evaluate(state, scored(30_000, 50f, sensitive = true)).state
        assertEquals(InterventionStage.CHECK, state.publicState.stage)
        state = policy.evaluate(state, scored(60_000, 70f, true, true)).state
        assertEquals(InterventionStage.REST_GUIDANCE, state.publicState.stage)
    }

    @Test fun timeCanCauseCautionWithoutPretendingFatigueWasEvaluated() {
        val normal = policy.evaluate(InterventionPolicyState(), InterventionSnapshot(0, continuousDriveSec = 7_200)).state
        assertEquals(InterventionStage.CAUTION, normal.publicState.stage)
        assertEquals(EvaluationStatus.NOT_EVALUATED, normal.publicState.evaluationStatus)
        assertEquals(InterventionStage.NORMAL, policy.evaluate(InterventionPolicyState(), InterventionSnapshot(0, continuousDriveSec = 7_199)).state.publicState.stage)
        assertEquals(InterventionStage.CAUTION, policy.evaluate(InterventionPolicyState(), InterventionSnapshot(0, continuousDriveSec = 3_600, sensitive = true)).state.publicState.stage)
    }

    @Test fun twoSlowResponsesAndNoResponseBypassGeneralStepDelay() {
        var state = policy.evaluate(checking(), scored(30_001, 64f, confirmation = check(CheckOutcome.SLOW))).state
        assertEquals(InterventionStage.CHECK, state.publicState.stage)
        state = policy.evaluate(state, scored(30_002, 64f, confirmation = check(CheckOutcome.SLOW))).state
        assertEquals(InterventionStage.REST_GUIDANCE, state.publicState.stage)
        val noResponse = policy.evaluate(checking(), scored(30_001, null, confirmation = check(CheckOutcome.NO_RESPONSE))).state
        assertEquals(InterventionStage.REST_GUIDANCE, noResponse.publicState.stage)
        assertEquals(EvaluationStatus.UNAVAILABLE, noResponse.publicState.evaluationStatus)
    }

    @Test fun consentOrRecoveryResultsCannotBecomeAnAlertnessFailure() {
        listOf(CheckContext.WAYPOINT_CONSENT, CheckContext.RECOVERY).forEach { context ->
            val state = policy.evaluate(checking(), scored(30_001, 64f, confirmation = check(CheckOutcome.NO_RESPONSE, context))).state
            assertEquals(InterventionStage.CHECK, state.publicState.stage)
            assertEquals(0, state.consecutiveSlow)
        }
    }

    @Test fun deviceFailureIsNotNoResponseAndNormalBreaksSlowStreak() {
        var state = policy.evaluate(checking(), scored(30_001, 64f, confirmation = check(CheckOutcome.SLOW))).state
        state = policy.evaluate(state, scored(30_002, 64f, confirmation = check(CheckOutcome.UNAVAILABLE))).state
        assertEquals(InterventionStage.CHECK, state.publicState.stage)
        state = policy.evaluate(state, scored(30_003, 64f, confirmation = check(CheckOutcome.NORMAL))).state
        state = policy.evaluate(state, scored(30_004, 64f, confirmation = check(CheckOutcome.SLOW))).state
        assertEquals(InterventionStage.CHECK, state.publicState.stage)
        assertEquals(1, state.consecutiveSlow)
    }

    @Test fun lowScoreRequiresTenContinuousValidMinutesAndNormalConfirmation() {
        var state = policy.evaluate(checking(), scored(30_001, 50f, confirmation = check(CheckOutcome.NORMAL))).state
        state = policy.evaluate(state, scored(630_000, 50f)).state
        assertEquals(InterventionStage.CHECK, state.publicState.stage)
        state = policy.evaluate(state, scored(630_001, 50f)).state
        assertEquals(InterventionStage.CAUTION, state.publicState.stage)
        val noConfirmation = policy.evaluate(checking(), scored(30_001, 50f)).state
        assertEquals(InterventionStage.CHECK, policy.evaluate(noConfirmation, scored(630_001, 50f)).state.publicState.stage)
    }

    @Test fun invalidScoreBreaksLowStreakInsteadOfCountingAsZero() {
        var state = policy.evaluate(checking(), scored(30_001, 0f, confirmation = check(CheckOutcome.NORMAL))).state
        state = policy.evaluate(state, scored(500_000, Float.NaN)).state
        assertEquals(EvaluationStatus.UNAVAILABLE, state.publicState.evaluationStatus)
        assertNull(state.lowScoreSinceMs)
        state = policy.evaluate(state, scored(630_001, 0f)).state
        assertEquals(InterventionStage.CHECK, state.publicState.stage)
    }

    @Test fun pendingCheckPreventsDescentAndSensitiveDescentUsesSensitiveThreshold() {
        var state = policy.evaluate(checking(), scored(30_001, 40f, true, true, check(CheckOutcome.NORMAL))).state
        state = policy.evaluate(state, scored(630_001, 40f, sensitive = true, checkRequired = true)).state
        assertEquals(InterventionStage.CHECK, state.publicState.stage)
        val sensitive = policy.evaluate(checking(), scored(30_001, 50f, sensitive = true, confirmation = check(CheckOutcome.NORMAL))).state
        assertNull(sensitive.lowScoreSinceMs)
    }

    @Test fun timeCautionPersistsUntilRecoveryAndSafetyOutranksSimultaneousRecovery() {
        var state = policy.evaluate(InterventionPolicyState(), InterventionSnapshot(0, continuousDriveSec = 7_200)).state
        state = policy.evaluate(state, scored(1, 0f)).state
        state = policy.evaluate(state, scored(600_001, 0f)).state
        assertEquals(InterventionStage.CAUTION, state.publicState.stage)
        val recovered = policy.evaluate(state, InterventionSnapshot(600_002, recoveredAfterCertifiedRest = true)).state
        assertEquals(InterventionStage.NORMAL, recovered.publicState.stage)
        val safety = policy.evaluate(state, InterventionSnapshot(600_002, safetyBypass = true, recoveredAfterCertifiedRest = true)).state
        assertEquals(InterventionStage.REST_GUIDANCE, safety.publicState.stage)
    }

    @Test fun singleCheckEventRemainsPendingUntilStageTwoAndClockCannotReverse() {
        var state = policy.evaluate(InterventionPolicyState(), InterventionSnapshot(0, checkRequired = true)).state
        assertEquals(InterventionStage.CAUTION, state.publicState.stage)
        state = policy.evaluate(state, InterventionSnapshot(30_000)).state
        assertEquals(InterventionStage.CHECK, state.publicState.stage)
        assertThrows(IllegalArgumentException::class.java) { policy.evaluate(state, InterventionSnapshot(29_999)) }
    }

    @Test fun defaultFirstEscalationIsImmediateEvenAfterEarlierNormalEvaluations() {
        val normal = policy.evaluate(InterventionPolicyState(), scored(0, 0f)).state
        val raised = policy.evaluate(normal, scored(1, 90f, true))
        assertEquals(InterventionStage.CAUTION, raised.state.publicState.stage)
        assertEquals(1L, raised.transition?.tMs)
        assertEquals(InterventionStage.CAUTION, policy.evaluate(raised.state, scored(30_000, 90f, true)).state.publicState.stage)
        assertEquals(InterventionStage.CHECK, policy.evaluate(raised.state, scored(30_001, 90f, true)).state.publicState.stage)
    }

    @Test fun certificationReleasesTimeOrForecastHoldWithoutRecoveryOrTenMinuteWait() {
        listOf(InterventionSnapshot(0, continuousDriveSec = 7_200),
            InterventionSnapshot(0, forecastDue = true)).forEach { trigger ->
            var state = policy.evaluate(InterventionPolicyState(), trigger).state
            state = policy.evaluate(state, scored(1, 0f)).state
            assertEquals(InterventionStage.CAUTION, state.publicState.stage)
            val released = policy.evaluate(state, scored(2, 0f).copy(restCertified = true))
            assertEquals(InterventionStage.NORMAL, released.state.publicState.stage)
            assertEquals(TransitionReason.REST_COMPLETED, released.transition?.reason)
            assertEquals(EvaluationStatus.EVALUATED, released.state.publicState.evaluationStatus)
            assertFalse(released.state.cautionHeldUntilRest)
        }
    }

    @Test fun certificationReevaluatesNormalAndSensitiveScoreBoundaries() {
        listOf(false to 40f, true to 30f).forEach { (sensitive, threshold) ->
            val held = policy.evaluate(InterventionPolicyState(), InterventionSnapshot(0, forecastDue = true)).state
            val below = policy.evaluate(held, scored(1, threshold - 0.001f, sensitive = sensitive).copy(restCertified = true)).state
            assertEquals(InterventionStage.NORMAL, below.publicState.stage)
            val exact = policy.evaluate(held, scored(1, threshold, sensitive = sensitive).copy(restCertified = true)).state
            assertEquals(InterventionStage.CAUTION, exact.publicState.stage)
            assertFalse(exact.cautionHeldUntilRest)
            assertEquals(TransitionReason.SCORE, exact.publicState.reason)
            val high = policy.evaluate(held, scored(30_000, if (sensitive) 50f else 60f,
                sensitive = sensitive).copy(restCertified = true)).state
            assertEquals(InterventionStage.CHECK, high.publicState.stage)
        }
    }

    @Test fun certificationDoesNotEraseCurrentContextOrPendingCheck() {
        val held = policy.evaluate(InterventionPolicyState(), InterventionSnapshot(0, forecastDue = true)).state
        listOf(InterventionSnapshot(1, continuousDriveSec = 7_200),
            InterventionSnapshot(1, forecastDue = true),
            InterventionSnapshot(1, vulnerableTime = true)).forEach { current ->
            val state = policy.evaluate(held, current.copy(restCertified = true)).state
            assertEquals(InterventionStage.CAUTION, state.publicState.stage)
            assertTrue(state.cautionHeldUntilRest)
        }
        val checking = policy.evaluate(held, InterventionSnapshot(1, checkRequired = true, restCertified = true)).state
        assertEquals(InterventionStage.CAUTION, checking.publicState.stage)
        assertTrue(checking.pendingCheckEscalation)
        assertEquals(InterventionStage.CHECK, policy.evaluate(checking, InterventionSnapshot(30_000)).state.publicState.stage)
        val safety = policy.evaluate(held, InterventionSnapshot(1, safetyBypass = true, restCertified = true)).state
        assertEquals(InterventionStage.REST_GUIDANCE, safety.publicState.stage)
    }

    @Test fun certificationDoesNotShortCircuitScoreOnlyCautionDescent() {
        val caution = policy.evaluate(InterventionPolicyState(), scored(0, 40f)).state
        val certified = policy.evaluate(caution, scored(1, 0f).copy(restCertified = true)).state
        assertEquals(InterventionStage.CAUTION, certified.publicState.stage)
        assertEquals(InterventionStage.CAUTION, policy.evaluate(certified, scored(600_000, 0f)).state.publicState.stage)
        assertEquals(InterventionStage.NORMAL, policy.evaluate(certified, scored(600_001, 0f)).state.publicState.stage)
    }

    @Test fun certificationAloneCannotReleaseStageThreeOrClaimUnobservedScoreIsNormal() {
        val rest = policy.evaluate(InterventionPolicyState(), InterventionSnapshot(0, safetyBypass = true)).state
        val certified = policy.evaluate(rest, scored(1, 0f).copy(restCertified = true)).state
        assertEquals(InterventionStage.REST_GUIDANCE, certified.publicState.stage)
        assertEquals(InterventionStage.REST_GUIDANCE, policy.evaluate(certified, scored(600_001, 0f)).state.publicState.stage)
        val held = policy.evaluate(InterventionPolicyState(), InterventionSnapshot(0, forecastDue = true)).state
        val unavailable = policy.evaluate(held, InterventionSnapshot(1, restCertified = true)).state
        assertEquals(InterventionStage.NORMAL, unavailable.publicState.stage)
        assertEquals(EvaluationStatus.NOT_EVALUATED, unavailable.publicState.evaluationStatus)
    }
}
