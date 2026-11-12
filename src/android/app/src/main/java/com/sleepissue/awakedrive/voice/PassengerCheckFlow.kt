package com.sleepissue.awakedrive.voice

import com.sleepissue.awakedrive.engine.check.CheckPlan
import com.sleepissue.awakedrive.engine.check.CheckPolicy
import com.sleepissue.awakedrive.engine.check.CheckResult
import com.sleepissue.awakedrive.engine.check.CheckTarget
import com.sleepissue.awakedrive.engine.check.CheckType
import com.sleepissue.awakedrive.engine.check.OccupancyContext
import com.sleepissue.awakedrive.engine.check.QuestionType

/** 좌석 정보는 음량 맥락에만 쓰고, 실제 확인 질문은 항상 운전자에게 한다. */
class PassengerCheckFlow(
    private val policy: CheckPolicy,
    private val voiceAgent: VoiceAgent,
    private val coordinator: VoiceCheckCoordinator,
) {
    fun start(
        context: OccupancyContext,
        baselineMs: Long,
        onPlanSelected: (CheckPlan) -> Unit = {},
        onPromptStarted: () -> Unit = {},
        onResult: (CheckResult, Boolean) -> Unit,
        onButtonRequired: (CheckTarget) -> Unit,
        shouldContinue: () -> Boolean = { true },
    ) {
        val plan = policy.selectPlan(context)
        onPlanSelected(plan)
        when (plan.target) {
            CheckTarget.DRIVER_BUTTON -> onButtonRequired(plan.target)

            CheckTarget.DRIVER_VOICE -> startVoice(
                prompt = VoiceScripts.DRIVER_CHECK,
                questionType = QuestionType.DRIVER_CHECK,
                checkType = CheckType.VOICE,
                baselineMs = baselineMs,
                onPromptStarted = onPromptStarted,
                onResult = onResult,
                onButtonRequired = { onButtonRequired(CheckTarget.DRIVER_BUTTON) },
                shouldContinue = shouldContinue,
            )

            CheckTarget.PASSENGER,
            CheckTarget.REAR_PASSENGER,
            CheckTarget.PASSENGER_BUTTON,
            -> error("동승자는 졸음 확인 응답 대상이 아닙니다.")
        }
    }

    private fun startVoice(
        prompt: String,
        questionType: QuestionType,
        checkType: CheckType,
        baselineMs: Long,
        onPromptStarted: () -> Unit,
        onResult: (CheckResult, Boolean) -> Unit,
        onButtonRequired: () -> Unit,
        shouldContinue: () -> Boolean,
    ) {
        voiceAgent.speak(
            text = prompt,
            fallback = FallbackCue.CHECK_PROMPT,
            onComplete = {
                if (shouldContinue()) coordinator.start(
                    baselineMs = baselineMs,
                    questionType = questionType,
                    checkType = checkType,
                    onResult = onResult,
                    onSttFallbackRequired = onButtonRequired,
                )
            },
            onStarted = onPromptStarted,
        )
    }

    fun cancel() = coordinator.cancel()
}
