package com.sleepissue.awakedrive.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sleepissue.awakedrive.demo.ProductDemoSession
import com.sleepissue.awakedrive.demo.DemoPhase

@Composable
fun DemoControls(session: ProductDemoSession, load: (String, Int) -> Unit, command: (suspend ProductDemoSession.() -> Unit) -> Unit,
    onExit: () -> Unit = {},
) {
    var selected by remember { mutableStateOf("normal") }
    var speed by remember { mutableIntStateOf(60) }
    val playback by session.playback.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("데모 시나리오", color = Color.White)
        OutlinedButton(onClick = onExit) { Text("데모 종료 · 실제 모드로 돌아가기") }
        listOf(listOf("normal" to "정상", "distraction" to "딴짓", "drowsy" to "졸음"),
            listOf("talk" to "대화", "assist" to "보조 주행", "departure" to "출발 피로")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { (id, label) ->
                FilterChip(selected == id, { selected = id }, label = { Text(label) })
            } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(1, 10, 60).forEach { factor ->
            FilterChip(speed == factor, { speed = factor }, label = { Text("${factor}배") })
        } }
        Text("${playback.scenarioId ?: "선택 전"} · ${playback.phase} · ${playback.scenarioTimeMs / 1000}초", color = Color.LightGray)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ load(selected, speed) }, enabled = playback.phase != DemoPhase.PLAYING) { Text("불러오기") }
            Button({ command { start() } }, enabled = playback.phase == DemoPhase.READY) { Text("재생") }
            Button({ command { pause() } }, enabled = playback.phase == DemoPhase.PLAYING) { Text("일시정지") }
            Button({ command { resume() } }, enabled = playback.phase == DemoPhase.PAUSED) { Text("계속") }
            Button({ command { stop() } }) { Text("중지") }
        }
        Text("차량 신호를 재생하며 음성 안내와 내비 경유지 요청을 실행합니다. 데모 보상은 실제 기록과 분리됩니다.", color = Color.LightGray)
    }
}
