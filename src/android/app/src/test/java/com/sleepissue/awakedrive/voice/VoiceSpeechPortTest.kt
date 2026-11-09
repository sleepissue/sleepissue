package com.sleepissue.awakedrive.voice

import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.GeoPoint
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.signal.RestAreaCandidate
import com.sleepissue.awakedrive.signal.SpeechCue
import com.sleepissue.awakedrive.signal.SpeechRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceSpeechPortTest {
    @Test
    fun `1단계 쉼터 안내 요청은 TTS 시작과 완료 결과를 낸다`() = runTest {
        val spoken = mutableListOf<String>()
        val output = object : VoiceOutput {
            override fun speak(text: String, onComplete: () -> Unit, onError: (Throwable) -> Unit) {
                spoken += text
                onComplete()
            }
            override fun playFallback(cue: FallbackCue, onComplete: () -> Unit) = onComplete()
        }
        val agent = VoiceAgent(output, object : DuckingAudioFocus {
            override fun acquire() = Unit
            override fun release() = Unit
        }, delay = { _, action -> action() })
        val port = VoiceSpeechPort(agent)
        val results = async(start = CoroutineStart.UNDISPATCHED) { port.results.take(2).toList() }
        val request = SpeechRequest(
            RequestId("speech-1"),
            SpeechCue.REST_AREA_AHEAD,
            RestAreaCandidate("rest-1", "행복쉼터", GeoPoint(37.0, 127.0), 2_400f),
        )

        assertEquals(ExecutionStatus.REQUESTED, port.start(request).status)
        assertEquals(listOf(ExecutionStatus.STARTED, ExecutionStatus.SUCCEEDED), results.await().map { it.status })
        assertEquals(listOf("2킬로미터 앞에 행복쉼터 있어요."), spoken)
    }
}
