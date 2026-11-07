package com.sleepissue.awakedrive.cabin

import com.sleepissue.awakedrive.engine.model.SeatOccupancy
import com.sleepissue.awakedrive.signal.SeatBeltState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassengerOccupancyResolverTest {
    @Test fun `조수석 착석 또는 안전벨트 중 하나가 참이면 동승자가 있다`() {
        val occupied = PassengerOccupancyResolver.resolve(
            seats = SeatOccupancy(frontPassenger = true),
            belts = SeatBeltState(frontPassenger = false),
            speechDetected = true,
            microphoneAvailable = true,
        )
        val buckled = PassengerOccupancyResolver.resolve(
            seats = SeatOccupancy(frontPassenger = null),
            belts = SeatBeltState(frontPassenger = true),
            speechDetected = true,
            microphoneAvailable = true,
        )

        assertTrue(occupied.frontPassengerPresent)
        assertTrue(buckled.frontPassengerPresent)
    }

    @Test fun `뒷좌석 어느 한 곳의 안전벨트만 체결돼도 뒷좌석 동승자로 본다`() {
        val result = PassengerOccupancyResolver.resolve(
            seats = null,
            belts = SeatBeltState(rearCenter = true),
            speechDetected = true,
            microphoneAvailable = true,
        )

        assertTrue(result.rearPassengerPresent)
        assertFalse(result.frontPassengerPresent)
    }

    @Test fun `모든 값이 없으면 동승자가 있다고 추측하지 않는다`() {
        val result = PassengerOccupancyResolver.resolve(
            seats = null,
            belts = null,
            speechDetected = false,
            microphoneAvailable = false,
        )

        assertFalse(result.frontPassengerPresent)
        assertFalse(result.rearPassengerPresent)
    }
}
