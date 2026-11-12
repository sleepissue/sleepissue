package com.sleepissue.awakedrive.ui

import androidx.compose.runtime.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 실제 조립 지점이 제공한 상태와 실행 라우터를 화면에 함께 연결한다. */
@Composable
fun ConnectedDriveNavigation(state: StateFlow<DriveSessionUiState>, actions: DriveUiActionRouter) {
    val snapshot by state.collectAsState()
    val scope = rememberCoroutineScope()
    AppNavigation(session = snapshot, onAction = { action -> scope.launch { actions.dispatch(action) } })
}
