package com.sleepissue.awakedrive.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleepissue.awakedrive.engine.rest.NapState

/** 타이머 감소·인증 판단은 세션이 담당하며 화면은 관측한 상태만 표시한다. */
@Composable
fun RestScreen(
    session: DriveSessionUiState = DriveSessionUiState(),
    actionsEnabled: Boolean = false,
    onAction: (DriveUiAction) -> Unit = {},
) {
    val napRunning = session.nap !is NapState.Idle
    val remaining = if (napRunning) session.napRemainingMs else session.restRemainingMs
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (napRunning) "파워냅" else "휴식·회복", color = Color.White, fontSize = 28.sp)
        Text(session.restAreaName ?: "쉼터 도착 확인 전", color = Color(0xFF90DFC9), fontSize = 20.sp)
        Text(remaining?.let { "%02d:%02d".format(it / 60_000, (it / 1000) % 60) } ?: "타이머 대기",
            color = Color.White, fontSize = 44.sp)
        Text(if (session.restCertified) "✓ 차량 데이터로 휴식 인증 완료" else "쉼터 반경 안 + P단 또는 시동 꺼짐 + 15분을 확인합니다.", color = Color.LightGray)
        Text("휴식 스탬프 ${session.stampCount}개" + if (session.demoSession) " · 데모 세션 기록" else "", color = Color(0xFFCFACFF), fontSize = 22.sp)
        if (session.nap is NapState.Alarming) Text("파워냅 시간이 끝났습니다. 일어나 주세요.", color = Color(0xFFFFD166))
        Text("시동을 유지해야 알람이 울립니다. 앱은 실제로 잤는지 판단하지 않습니다.", color = Color.LightGray)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { onAction(DriveUiAction.StartNap) }, enabled = actionsEnabled && session.parked && !napRunning) { Text("잠깐 잘게 · 15분") }
            Button(onClick = { onAction(DriveUiAction.WakeNow) }, enabled = actionsEnabled && session.parked && napRunning) { Text("지금 깨우기") }
            Button(onClick = { onAction(DriveUiAction.RequestRecovery) }, enabled = actionsEnabled && session.parked && !napRunning) { Text("음성 회복 확인") }
        }
        Text(session.recovery, color = Color.White, fontSize = 18.sp)
        Text("가볍게 스트레칭하고 물을 마시며 충분히 쉬어 가세요.", color = Color.LightGray)
        Text(session.message, color = Color(0xFFFFD166))
    }
}
