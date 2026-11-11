package com.sleepissue.awakedrive.navi

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.ExecutionFailure
import kotlinx.coroutines.withTimeoutOrNull

/** 반영 확인 뒤 화면을 여는 시간이 길어져도 확인된 경유지를 실패로 바꾸지 않는다. */
internal suspend fun confirmRestWaypoint(
    config: EngineConfig,
    confirm: suspend () -> WaypointOutcome,
    afterAdded: suspend () -> Unit,
): WaypointOutcome {
    val outcome = withTimeoutOrNull(config.waypointConfirmTimeoutMs) { confirm() }
        ?: WaypointOutcome.Failed(ExecutionFailure.TIMED_OUT,
            "${config.waypointConfirmTimeoutMs} ms 안에 경유지 목록에서 확인 못 함")
    if (outcome is WaypointOutcome.Added) afterAdded()
    return outcome
}
