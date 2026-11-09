package com.sleepissue.awakedrive.voice

import com.sleepissue.awakedrive.engine.model.CheckContext
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.model.ResponseIntent
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.fatigue.FatigueAssessmentRunner
import com.sleepissue.awakedrive.signal.InterventionInput
import com.sleepissue.awakedrive.signal.InterventionManager
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** 음성 포트의 실행·사람 응답을 1번 단계 전환과 2번 출발 피로 기준선에 전달한다. */
class VoiceInterventionConnector(
    private val port: VoiceCheckPort,
    private val runner: FatigueAssessmentRunner,
    private val manager: InterventionManager,
    private val voiceAgent: VoiceAgent,
    private val clock: Clock,
) {
    /** 앱 수명주기 scope에서 한 번만 실행한다. */
    suspend fun run() = coroutineScope {
        launch {
            port.executionResults.collect(manager::onResult)
        }
        launch {
            port.results.collect { result ->
                if (result.context == CheckContext.PRE_DEPARTURE_CONDITION) {
                    val sleepy = when (result.intent) {
                        ResponseIntent.SLEEPY -> true
                        ResponseIntent.REFRESHED, ResponseIntent.NEUTRAL -> false
                        else -> null
                    }
                    if (sleepy != null) {
                        runner.reportCondition(sleepy, clock.scenarioTimeMs(), manager)
                        if (sleepy) voiceAgent.speak(
                            VoiceScripts.PRE_DRIVE_REST_RECOMMENDATION,
                            FallbackCue.GENERIC_GUIDANCE,
                        )
                    }
                }
                manager.submit(
                    RequestId("voice-result-${result.requestId.value}"),
                    InterventionInput.CheckCompleted(result),
                )
            }
        }
    }

}
