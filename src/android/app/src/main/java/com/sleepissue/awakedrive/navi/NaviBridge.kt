package com.sleepissue.awakedrive.navi

import com.sleepissue.awakedrive.engine.model.ExecutionFailure
import com.sleepissue.awakedrive.engine.rest.RestArea

/**
 * 내비게이션에 쉼터를 경유지로 넣는 창구 (REQ_RST_004).
 * 실제 SDK 구현은 `pleos/PleosNaviBridge`, 테스트에서는 가짜 구현을 쓴다.
 *
 * 한 번의 호출이 끝나면 다음이 모두 끝났거나 실패한 상태여야 한다.
 *   1) 경유지 추가 요청  2) 내비 경유지 목록에 실제로 들어갔는지 확인
 *   3) 경로 재탐색 요청  4) 내비게이션 화면 열기
 * 최종 목적지는 바꾸지 않는다(경유지만 끼워 넣음).
 */
interface NaviBridge {
    suspend fun addRestWaypoint(area: RestArea): WaypointOutcome
}

sealed interface WaypointOutcome {
    /** 내비 경유지 목록에서 이 쉼터를 확인했다. */
    data object Added : WaypointOutcome

    /**
     * @param failure 1번 계약의 실패 종류. UNAVAILABLE = 내비 앱 없음·연결 실패, TIMED_OUT = 반영 확인 못 함
     * @param detail 로그용 설명 (화면·음성에 쓰지 않음)
     */
    data class Failed(val failure: ExecutionFailure, val detail: String) : WaypointOutcome
}
