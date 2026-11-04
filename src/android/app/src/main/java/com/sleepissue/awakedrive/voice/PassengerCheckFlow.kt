package com.sleepissue.awakedrive.voice

import com.sleepissue.awakedrive.engine.check.CheckPlan
import com.sleepissue.awakedrive.engine.check.CheckPolicy
import com.sleepissue.awakedrive.engine.check.CheckResult
import com.sleepissue.awakedrive.engine.check.CheckTarget
import com.sleepissue.awakedrive.engine.check.CheckType
import com.sleepissue.awakedrive.engine.check.OccupancyContext
import com.sleepissue.awakedrive.engine.check.QuestionType

/** 좌석·말소리 결과를 실제 운전자/동승자 음성 확인 흐름으로 연결한다. */
class PassengerCheckFlow(
    private val policy: CheckPolicy,
    private val voiceAgent: VoiceAgent,
    private val coordinator: VoiceCheckCoordinator,
) {
    fun start(
        context: OccupancyContext,
        baselineMs: Long,
        onPlanSelected: (CheckPlan) -> Unit = {},
        onResult: (CheckResult, Boolean) -> Unit,
        onButtonRequired: (CheckTarget) -> Unit,
    ) {
        val plan = policy.selectPlan(context)
        onPlanSelected(plan)
        when (plan.target) {
            CheckTarget.DRIVER_BUTTON,
            CheckTarget.PASSENGER_BUTTON,
            -> onButtonRequired(plan.target)

            CheckTarget.DRIVER_VOICE -> startVoice(
                prompt = VoiceScripts.DRIVER_CHECK,
                questionType = QuestionType.DRIVER_CHECK,
                checkType = CheckType.VOICE,
                baselineMs = baselineMs,
                onResult = onResult,
                onButtonRequired = { onButtonRequired(CheckTarget.DRIVER_BUTTON) },
            )

            CheckTarget.PASSENGER,
            CheckTarget.REAR_PASSENGER,
            -> startVoice(
                prompt = VoiceScripts.PASSENGER_CHECK,
                questionType = QuestionType.PASSENGER_CHECK,
                checkType = CheckType.PASSENGER,
                baselineMs = baselineMs,
                onResult = onResult,
                onButtonRequired = { onButtonRequired(CheckTarget.PASSENGER_BUTTON) },
            )
        }
    }

    private fun startVoice(
        prompt: String,
        questionType: QuestionType,
        checkType: CheckType,
        baselineMs: Long,
        onResult: (CheckResult, Boolean) -> Unit,
        onButtonRequired: () -> Unit,
    ) {
        voiceAgent.speak(prompt, FallbackCue.CHECK_PROMPT) {
            coordinator.start(
                baselineMs = baselineMs,
                questionType = questionType,
                checkType = checkType,
                onResult = onResult,
                onSttFallbackRequired = onButtonRequired,
            )
        }
    }

    fun cancel() = coordinator.cancel()
}
