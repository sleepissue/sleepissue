package com.sleepissue.awakedrive.voice

import com.sleepissue.awakedrive.engine.model.ExecutionFailure
import com.sleepissue.awakedrive.engine.model.ExecutionResult
import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.signal.SpeechCue
import com.sleepissue.awakedrive.signal.SpeechPort
import com.sleepissue.awakedrive.signal.SpeechRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 개입 관리자의 단계별 안내 요청을 Pleos TTS 흐름으로 실행한다. */
class VoiceSpeechPort(private val voiceAgent: VoiceAgent) : SpeechPort {
    private val mutableResults = MutableSharedFlow<ExecutionResult>(extraBufferCapacity = 16)
    override val results: Flow<ExecutionResult> = mutableResults.asSharedFlow()

    private val lock = Any()
    private val active = mutableSetOf<RequestId>()

    override suspend fun start(request: SpeechRequest): ExecutionResult {
        val accepted = synchronized(lock) { active.add(request.requestId) }
        if (!accepted) return result(request.requestId, ExecutionStatus.FAILED, ExecutionFailure.ERROR)

        voiceAgent.speak(
            text = script(request),
            fallback = if (request.cue in restCues) FallbackCue.REST_WARNING else FallbackCue.GENERIC_GUIDANCE,
            onStarted = { emitIfActive(request.requestId, ExecutionStatus.STARTED) },
            onComplete = {
                val completed = synchronized(lock) { active.remove(request.requestId) }
                if (completed) mutableResults.tryEmit(result(request.requestId, ExecutionStatus.SUCCEEDED))
            },
        )
        return result(request.requestId, ExecutionStatus.REQUESTED)
    }

    override suspend fun cancel(requestId: RequestId): ExecutionResult {
        val cancelled = synchronized(lock) { active.remove(requestId) }
        return if (cancelled) {
            result(requestId, ExecutionStatus.CANCELLED).also { mutableResults.emit(it) }
        } else result(requestId, ExecutionStatus.FAILED, ExecutionFailure.UNAVAILABLE)
    }

    private fun script(request: SpeechRequest): String = when (request.cue) {
        SpeechCue.CAUTION -> VoiceScripts.CAUTION
        SpeechCue.REST_AREA_AHEAD -> {
            val area = request.restArea
            VoiceScripts.stage1(
                area?.name ?: "앞쪽 쉼터",
                area?.distanceM?.div(1_000f)?.toInt()?.coerceAtLeast(1) ?: 1,
            )
        }
        SpeechCue.NO_REST_AREA -> VoiceScripts.NO_REST_AREA
        SpeechCue.REST_RECOMMENDED -> VoiceScripts.REST_RECOMMENDED
        SpeechCue.SAFETY_REST_RECOMMENDED -> VoiceScripts.SAFETY_BYPASS
    }

    private fun emitIfActive(requestId: RequestId, status: ExecutionStatus) {
        if (synchronized(lock) { requestId in active }) mutableResults.tryEmit(result(requestId, status))
    }

    private fun result(id: RequestId, status: ExecutionStatus, failure: ExecutionFailure? = null) =
        ExecutionResult(id, status, failure)

    private companion object {
        val restCues = setOf(
            SpeechCue.REST_RECOMMENDED,
            SpeechCue.SAFETY_REST_RECOMMENDED,
        )
    }
}
