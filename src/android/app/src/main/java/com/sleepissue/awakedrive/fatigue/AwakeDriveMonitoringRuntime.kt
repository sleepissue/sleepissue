package com.sleepissue.awakedrive.fatigue

import com.sleepissue.awakedrive.engine.model.NaviState
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.model.SignalFrame
import com.sleepissue.awakedrive.engine.model.TripState
import com.sleepissue.awakedrive.signal.InterventionManager
import com.sleepissue.awakedrive.signal.InterventionRequestRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** 차량 감시 수명주기에서 엔진과 외부 실행 라우터를 함께 띄우는 조립 지점이다. */
class AwakeDriveMonitoringRuntime(
    private val runner: FatigueAssessmentRunner,
    private val manager: InterventionManager,
    private val router: InterventionRequestRouter,
    private val frames: Flow<SignalFrame>,
    private val navi: () -> NaviState?,
    private val trip: () -> TripState?,
) {
    private var jobs: List<Job> = emptyList()

    suspend fun start(scope: CoroutineScope) {
        if (jobs.any { it.isActive }) return
        manager.start(RequestId("monitoring-start"))
        jobs = listOf(
            scope.launch { router.run() },
            scope.launch { runner.run(frames, navi, trip, manager) },
        )
    }

    suspend fun stop() {
        jobs.forEach(Job::cancel)
        jobs = emptyList()
        manager.stop(RequestId("monitoring-stop"))
    }
}
