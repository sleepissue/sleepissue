package com.sleepissue.awakedrive.pleos

import com.sleepissue.awakedrive.signal.FakeClock
import org.junit.Assert.*
import org.junit.Test

class SdkNaviTimingAuditTest {
    @Test fun disabledTracingPreservesSingleCallReturnAndOriginalFailure() {
        var calls = 0
        val value = Any()
        assertSame(value, traceNaviCall(null, "destination") { calls++; value })
        assertEquals(1, calls)
        val error = IllegalStateException("SDK 오류")
        try {
            traceNaviCall(null, "destination") { calls++; throw error }
            fail("원래 SDK 오류 전파 필요")
        } catch (actual: IllegalStateException) { assertSame(error, actual) }
        assertEquals(2, calls)
    }

    @Test fun failingObserverCannotPreventDeliveryOrMaskSdkFailure() {
        var calls = 0
        var points = 0
        val audit = SdkNaviTimingAudit(FakeClock()) { points++; throw IllegalArgumentException("진단 소비자 오류") }
        assertEquals(60, traceNaviCall(audit, "destination") { calls++; 60 })
        val sdkError = IllegalStateException("SDK 오류")
        try {
            traceNaviCall(audit, "destination") { calls++; throw sdkError }
            fail("SDK 오류를 진단 예외로 바꾸면 안 됨")
        } catch (actual: IllegalStateException) { assertSame(sdkError, actual) }
        assertEquals(2, calls)
        assertEquals(4, points)
    }
}
