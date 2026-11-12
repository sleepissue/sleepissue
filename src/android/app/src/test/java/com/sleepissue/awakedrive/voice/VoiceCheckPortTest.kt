package com.sleepissue.awakedrive.voice

import com.sleepissue.awakedrive.engine.check.CheckPolicy
import com.sleepissue.awakedrive.engine.check.CheckType
import com.sleepissue.awakedrive.engine.check.OccupancyContext
import com.sleepissue.awakedrive.engine.check.QuestionType
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.signal.CheckRequest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VoiceCheckPortTest {
    private class Session : VoiceCheckSession {
        var starts = 0
        var cancels = 0
        override fun start(baselineMs: Long, questionType: QuestionType, checkType: CheckType,
            onResult: (com.sleepissue.awakedrive.engine.check.CheckResult, Boolean) -> Unit,
            onSttFallbackRequired: () -> Unit) { starts++ }
        override fun cancel() { cancels++ }
    }
    private class Harness(microphoneAvailable: Boolean = true) {
        val session = Session()
        var finishPrompt: () -> Unit = {}
        val agent = VoiceAgent(object : VoiceOutput {
            override fun speak(text: String, onComplete: () -> Unit, onError: (Throwable) -> Unit) { finishPrompt = onComplete }
            override fun playFallback(cue: FallbackCue, onComplete: () -> Unit) { finishPrompt = onComplete }
        }, object : DuckingAudioFocus {
            override fun acquire() = Unit
            override fun release() = Unit
        }, { _, action -> action() })
        val port = VoiceCheckPort(PassengerCheckFlow(CheckPolicy(), agent, session), agent, session,
            { OccupancyContext(true, false, false, microphoneAvailable) }, { 1000L })
    }

    @Test fun passengerResponseCompletesOnlyOnceAndStopsDelayedRecognition() = runTest {
        for (rest in listOf(false, true)) {
            val h = Harness()
            val request = CheckRequest(RequestId("check"), CheckContext.DRIVER_ALERTNESS)
            h.port.start(request)
            val response = async(start = CoroutineStart.UNDISPATCHED) { h.port.results.first() }
            assertEquals(ExecutionStatus.SUCCEEDED, h.port.completeWithPassenger(rest).status)
            val result = response.await()
            assertEquals(request.requestId, result.requestId)
            assertEquals(request.context, result.context)
            assertEquals(ResponseChannel.PASSENGER_BUTTON, result.channel)
            assertEquals(if (rest) ResponseIntent.REST else ResponseIntent.OK, result.intent)
            h.finishPrompt()
            assertEquals(0, h.session.starts)
            assertTrue(h.session.cancels > 0)
            assertEquals(ExecutionStatus.FAILED, h.port.completeWithPassenger(rest).status)
        }
    }

    @Test fun noMicrophoneAllowsPassengerFallbackWithoutStartingRecognition() = runTest {
        val h = Harness(microphoneAvailable = false)
        val started = async(start = CoroutineStart.UNDISPATCHED) { h.port.executionResults.first() }
        val request = CheckRequest(RequestId("no-microphone"), CheckContext.DRIVER_ALERTNESS)
        h.port.start(request)
        assertEquals(ExecutionStatus.STARTED, started.await().status)
        val response = async(start = CoroutineStart.UNDISPATCHED) { h.port.results.first() }
        assertEquals(ExecutionStatus.SUCCEEDED, h.port.completeWithPassenger(false).status)
        assertEquals(ResponseChannel.PASSENGER_BUTTON, response.await().channel)
        assertEquals(0, h.session.starts)
    }

    @Test fun waypointWithoutMicrophoneUsesButtonsAndCancelledPromptCannotStartRecognition() = runTest {
        val h = Harness(microphoneAvailable = false)
        val request = CheckRequest(RequestId("waypoint"), CheckContext.WAYPOINT_CONSENT)
        h.port.start(request)
        h.finishPrompt()
        assertEquals(0, h.session.starts)
        assertEquals(ExecutionStatus.SUCCEEDED,
            h.port.completeWithButton(request.requestId, ResponseIntent.YES, false).status)
        assertEquals(ExecutionStatus.FAILED,
            h.port.completeWithButton(request.requestId, ResponseIntent.YES, false).status)
        val cancelled = CheckRequest(RequestId("cancelled-waypoint"), CheckContext.WAYPOINT_CONSENT)
        h.port.start(cancelled)
        h.port.cancel(cancelled.requestId)
        h.finishPrompt()
        assertEquals(0, h.session.starts)
    }

    @Test fun passengerCannotAnswerMissingWaypointOrRecoveryChecks() = runTest {
        val h = Harness()
        assertEquals(ExecutionStatus.FAILED, h.port.completeWithPassenger(true).status)
        for (context in listOf(CheckContext.WAYPOINT_CONSENT, CheckContext.RECOVERY)) {
            val request = CheckRequest(RequestId(context.name), context)
            h.port.start(request)
            assertEquals(ExecutionStatus.FAILED, h.port.completeWithPassenger(true).status)
            h.port.cancel(request.requestId)
        }
    }
}
