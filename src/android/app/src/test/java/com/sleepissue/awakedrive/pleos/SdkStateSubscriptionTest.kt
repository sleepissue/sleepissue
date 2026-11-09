package com.sleepissue.awakedrive.pleos

import com.sleepissue.awakedrive.engine.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SdkStateSubscriptionTest {
    private class Fixture {
        val io = TestCoroutineScheduler()
        val main = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(main))
        val clock = object : Clock {
            override fun elapsedRealtimeMs() = io.currentTime
            override fun wallTimeMs() = io.currentTime
            override fun scenarioTimeMs() = io.currentTime
        }
        var reads = 0
        lateinit var respond: (Boolean?) -> Unit
        lateinit var reject: (Exception) -> Unit
        data class Delivery(val value: Boolean?, val kind: SdkDelivery, val at: Long, val began: Long?, val order: Long, val valid: () -> Boolean)
        val deliveries = mutableListOf<Delivery>()
        val errors = mutableListOf<Exception>()
        val collector = SdkStateSubscription(scope, clock, SdkProbePolicy(3_000, 1_000),
            query = { success: (Boolean?) -> Unit, failure -> reads++; respond = success; reject = failure },
            deliver = { v, k, t, b, o, valid -> deliveries += Delivery(v, k, t, b, o, valid) },
            failed = { errors += it }, dispatcher = StandardTestDispatcher(io))
        fun begin() { collector.start(); io.runCurrent() }
        fun advance(ms: Long) { io.advanceTimeBy(ms); io.runCurrent() }
        fun close() { collector.close(); scope.cancel(); io.runCurrent(); main.runCurrent() }
    }
    @Test fun onlyOneOutstandingReadAndRepeatedStartDoesNotCreateAnotherTimer() {
        val f = Fixture(); f.begin(); f.collector.start(); f.advance(2_000)
        assertEquals(1, f.reads)
        f.respond(false); f.io.runCurrent(); f.advance(1_000)
        assertEquals(2, f.reads); assertEquals(0L, f.main.currentTime)
        assertEquals(SdkDelivery.QUERY, f.deliveries.single().kind)
        f.respond(false); f.io.runCurrent(); assertEquals(SdkDelivery.REQUERY, f.deliveries.last().kind)
        f.close()
    }
    @Test fun callbackDuringQueryInvalidatesThatOldQueryEvenIfBothArriveInSameMillisecond() {
        val f = Fixture(); f.begin(); f.collector.callback(true); f.respond(false); f.io.runCurrent()
        assertTrue(f.deliveries.first().valid()); assertFalse(f.deliveries.last().valid())
        assertEquals(true, f.deliveries.first().value); f.close()
    }
    @Test fun callbackQueuedAfterResponseStillInvalidatesQueryBeforeMainAppliesIt() {
        val f = Fixture(); f.begin(); f.respond(false); f.io.runCurrent()
        assertTrue(f.deliveries.single().valid())
        f.collector.callback(true)
        assertFalse(f.deliveries.first().valid()); assertTrue(f.deliveries.last().valid()); f.close()
    }
    @Test fun closeInvalidatesQueuedDeliveriesAndLateResponseFromPreviousInstance() {
        val f = Fixture(); f.begin(); f.collector.callback(true); val late = f.respond
        f.collector.close(); late(false); f.advance(10_000)
        assertEquals(1, f.reads); assertFalse(f.deliveries.single().valid()); assertTrue(f.errors.isEmpty())
        f.collector.callback(false); assertEquals(1, f.deliveries.size); f.close()
    }
    @Test fun listenerErrorBlocksQueuedCallbacksAndPendingReadUntilNewInstance() {
        val f = Fixture(); f.begin(); f.collector.callback(true)
        f.collector.error(IllegalStateException("연결 해제")); f.respond(false); f.advance(10_000)
        assertFalse(f.deliveries.single().valid()); assertEquals(1, f.errors.size)
        f.collector.start(); assertEquals(1, f.reads); f.close()
    }
    @Test fun failedQueryAndPermissionErrorAreTerminalAndLateSuccessCannotRecover() {
        for (error in listOf(IllegalStateException("조회 실패"), SecurityException("권한 상실"))) {
            val f = Fixture(); f.begin(); f.reject(error); f.io.runCurrent(); f.respond(false); f.advance(10_000)
            assertEquals(1, f.errors.size)
            // 코루틴 stacktrace 복구가 예외를 복제할 수 있으므로 객체 동일성 대신 계약을 확인한다.
            assertEquals(error.javaClass, f.errors.single().javaClass)
            assertEquals(error.message, f.errors.single().message)
            assertTrue(f.deliveries.isEmpty()); assertEquals(1, f.reads); f.close()
        }
    }
    @Test fun missingReplyTimesOutWithoutOverlappingOrRetryingUncancelableSdkRequest() {
        val f = Fixture(); f.begin(); f.advance(3_000)
        assertEquals(1, f.errors.size); assertEquals(1, f.reads)
        f.respond(false); f.advance(10_000)
        assertTrue(f.deliveries.isEmpty()); assertEquals(1, f.reads); f.close()
    }
    @Test fun duplicateSdkResultIsConsumedOnceAndReceiptIsCapturedAtCallbackIngress() {
        val f = Fixture(); f.begin(); f.advance(100); f.respond(false); f.respond(true)
        f.advance(200)
        assertEquals(1, f.deliveries.size); assertEquals(false, f.deliveries.single().value)
        assertEquals(100L, f.deliveries.single().at); assertEquals(0L, f.deliveries.single().began); f.close()
    }
}
