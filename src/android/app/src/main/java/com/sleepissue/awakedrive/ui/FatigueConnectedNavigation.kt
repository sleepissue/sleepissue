package com.sleepissue.awakedrive.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.sleepissue.awakedrive.fatigue.FatigueAssessmentRunner

/**
 * 엔진 판정 상태를 관찰해서 기존 UI에 전달한다.
 *
 * 엔진 실행과 Hub 연결은 담당하지 않는다.
 */
@Composable
fun FatigueConnectedNavigation(
    runner: FatigueAssessmentRunner
) {
    val snapshot by runner.snapshots.collectAsState()

    AppNavigation(
        fatigueUiState = snapshot.toFatigueUiState()
    )
}
