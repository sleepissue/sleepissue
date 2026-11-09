package com.sleepissue.awakedrive.session

import com.sleepissue.awakedrive.engine.forecast.ForecastRecalcTrigger
import com.sleepissue.awakedrive.engine.model.ExecutionFailure
import com.sleepissue.awakedrive.engine.model.ExecutionResult
import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.signal.InterventionRouter
import com.sleepissue.awakedrive.signal.RestRecoveryResetRequest
import kotlinx.coroutines.CancellationException

/**
 * [4번] 휴식 인증 + 회복 정상 뒤 초기화(REQ_RES_011). [InterventionRouter]의 `recoveryReset` 자리에 넣는다.
 *
 * 1번 PolicyInterventionManager가 단계 0·다음 확인 일정을 맡고 `RestRecoveryResetRequest`를 낸다. 여기서는 나머지를 한다.
 *
 * | 항목 (RES_011 표) | 누가 |
 * |---|---|
 * | 위험 점수 R 평활값 0, 기준선 불변 | [resetFatigue] = 2번 `runner.onRestCertified()` |
 * | 예보 재계산 1회 (연속 0 기준) | [forecast] `REST_VERIFIED` |
 *
 * 하나라도 실패하면 FAILED(ERROR)를 돌려준다. 성공으로 꾸미지 않는다.
 * [forecast]가 null이면(예보 미조립) 엔진 초기화만 하고 성공으로 본다 — 예보는 선택 경로라서다 [가정].
 */
class RestRecoveryReset(
    private val resetFatigue: suspend () -> Unit,
    private val forecast: ForecastCoordinator? = null,
) {
    suspend operator fun invoke(request: RestRecoveryResetRequest): ExecutionResult = try {
        resetFatigue()
        forecast?.recalculate(ForecastRecalcTrigger.REST_VERIFIED)
        ExecutionResult(request.requestId, ExecutionStatus.SUCCEEDED)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ExecutionResult(request.requestId, ExecutionStatus.FAILED, ExecutionFailure.ERROR)
    }
}
