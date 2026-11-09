package com.sleepissue.awakedrive.pleos

import com.sleepissue.awakedrive.engine.time.Clock
import kotlinx.coroutines.*

/** 재조회 예약·실행은 같은 IO 작업에서 직렬로 진행한다. 화면 Main으로 돌아와야 다음 조회가 예약되는 의존을 없앤다. */
internal fun launchGearRequeryLoop(
    scope: CoroutineScope, clock: Clock, periodMs: Long, generation: Long, audit: GearCollectionAudit,
    current: () -> Boolean, dispatcher: CoroutineDispatcher = Dispatchers.IO,
    query: suspend (GearQueryAttempt, Long) -> Unit,
): Job {
    require(periodMs > 0)
    return scope.launch(dispatcher) {
        while (isActive && current()) {
            val planned = audit.schedule(generation, SdkDelivery.REQUERY,
                Math.addExact(clock.elapsedRealtimeMs(), periodMs))
            delay(periodMs)
            ensureActive()
            if (!current()) break
            val resumed = audit.resumed(planned)
            val attempt = audit.ioStarted(resumed)
            query(attempt, requireNotNull(attempt.ioStartedMs))
        }
    }
}
