package com.sleepissue.awakedrive.debug

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import com.sleepissue.awakedrive.ui.*
import kotlinx.coroutines.flow.StateFlow

/** 화면은 순수한 상태 관찰만 한다. 판단·시간·인증은 데모 세션 한 곳에서 실행한다. */
@Composable
fun DebugFatigueNavigation(
    state: StateFlow<DriveSessionUiState>,
    onAction: (DriveUiAction) -> Unit,
) {
    val snapshot by state.collectAsState()
    MaterialTheme { AppNavigation(session = snapshot, onAction = onAction) }
}
