package com.sleepissue.awakedrive.engine.check

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PassengerCheckPolicyTest {
    private val policy = PassengerCheckPolicy()

    @Test
    fun `뒷좌석 무응답은 대화가 30초 끊기면 운전자에게 묻는다`() {
        assertEquals(
            RearPassengerAction.WAIT,
            policy.rearPassengerAction(0L, 29_999L, conversationSilentSinceMs = 0L),
        )
        assertEquals(
            RearPassengerAction.START_DRIVER_CHECK,
            policy.rearPassengerAction(0L, 30_000L, conversationSilentSinceMs = 0L),
        )
    }

    @Test
    fun `뒷좌석 대기가 3분을 넘으면 조향만으로 정한다`() {
        assertEquals(
            RearPassengerAction.USE_STEERING_ONLY,
            policy.rearPassengerAction(0L, 180_000L, conversationSilentSinceMs = null),
        )
    }

    @Test
    fun `동승자가 괜찮다고 해도 추세가 이어지면 10분 뒤 재확인한다`() {
        assertEquals(610_000L, policy.passengerRecheckAtMs(SpeechIntent.OK, true, 10_000L))
        assertNull(policy.passengerRecheckAtMs(SpeechIntent.OK, false, 10_000L))
        assertNull(policy.passengerRecheckAtMs(SpeechIntent.REST, true, 10_000L))
    }
}
