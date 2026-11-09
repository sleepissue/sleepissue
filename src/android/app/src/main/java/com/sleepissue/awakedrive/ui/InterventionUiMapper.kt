package com.sleepissue.awakedrive.ui

import com.sleepissue.awakedrive.engine.model.InterventionStage
import com.sleepissue.awakedrive.engine.model.InterventionState

/**
 * 팀원 1번의 개입 단계 데이터를
 * AwakeDrive UI 상태로 변환하는 코드.
 *
 * 위험도를 직접 계산하지 않으며
 * 팀원들의 판단 결과만 변환한다.
 */
fun InterventionState.toDrivingStatus(): DrivingStatus {

    return when (stage) {

        InterventionStage.NORMAL -> {
            DrivingStatus.NORMAL
        }

        InterventionStage.CAUTION -> {
            DrivingStatus.CAUTION
        }

        InterventionStage.CHECK -> {
            DrivingStatus.CHECK
        }

        InterventionStage.REST_GUIDANCE -> {
            DrivingStatus.REST
        }
    }
}