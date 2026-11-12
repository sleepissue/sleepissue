package com.sleepissue.awakedrive.ui

import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sleepissue.awakedrive.demo.*
import com.sleepissue.awakedrive.engine.model.ResponseIntent
import com.sleepissue.awakedrive.engine.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.sample

/** debug 소스셋에 의존하지 않는 제품 JSON 재생 화면. 화면이 내려가면 재생·음성을 정리한다. */
@OptIn(FlowPreview::class)
class ProductDemoActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: ProductDemoController
    private lateinit var restrictions: DrivingRestrictionGate
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restrictions = DrivingRestrictionGate(this)
        val clock = object : Clock {
            override fun scenarioTimeMs() = SystemClock.elapsedRealtime()
            override fun elapsedRealtimeMs() = SystemClock.elapsedRealtime()
            override fun wallTimeMs() = java.time.Instant.now().toEpochMilli()
        }
        controller = ProductDemoController(this, scope, clock)
        fun action(block: suspend () -> Unit) { scope.launch {
            try { block() } catch (error: Exception) {
                if (error is CancellationException) throw error
                controller.reportFailure(error)
                android.widget.Toast.makeText(this@ProductDemoActivity, "데모 실행 실패: ${error.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        } }
        action { if (!controller.restorePowerOff()) controller.showLastResult() }
        setContent {
            MaterialTheme {
                var selectedSpeed by remember { mutableStateOf(ProductDemoSpeed.ORIGINAL) }
                val locked by restrictions.restricted.collectAsState()
                val playback by remember { controller.playback.sample(200) }.collectAsState(initial = DemoPlaybackState())
                val message by controller.message.collectAsState()
                val trip by remember { controller.trip.sample(200) }.collectAsState(initial = com.sleepissue.awakedrive.engine.model.TripState())
                val frame by remember { controller.frame.sample(200) }.collectAsState(initial = null)
                val navi by remember { controller.navi.sample(200) }.collectAsState(initial = null)
                val button by controller.buttonRequest.collectAsState()
                val version by controller.pipelineVersion.collectAsState()
                val pipeline = remember(version) { controller.pipeline }
                val snapshot = pipeline?.runner?.snapshots?.let { flow ->
                    val initial = remember(pipeline) { flow.value }
                    remember(pipeline) { flow.sample(200) }.collectAsState(initial = initial).value
                }
                val stage = pipeline?.manager?.state?.collectAsState()?.value
                val fatigue = snapshot?.toFatigueUiState() ?: FatigueUiState()
                LaunchedEffect(locked) { if (locked) controller.stop() }
                Column(Modifier.fillMaxSize().background(Color(0xFF101837)).padding(16.dp)) {
                    Text("AwakeDrive · JSON 데모", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    DemoPlaybackBadge(playback)
                    if (locked) Text("주행 제한 중 · 정차 후 데모를 사용할 수 있습니다.", color = Color.White)
                    else Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Text("다음 재생 배속: ${selectedSpeed.label} · 선택 후 시나리오를 누르세요", color = Color.White)
                        Row { ProductDemoSpeed.entries.forEach { speed ->
                            OutlinedButton(onClick = { selectedSpeed = speed }) { Text(speed.label) }
                        } }
                        Text(if (selectedSpeed == ProductDemoSpeed.SIXTY)
                            "60배는 처리 지연·신호 공백이 남은 시험용입니다."
                            else "JSON 구간은 원본 시연 속도를 유지합니다. 전체 10배는 약 3분이며 음성 확인 중에는 1배로 진행합니다.",
                            color = Color.LightGray)
                        Row { ProductDemoController.SCENARIOS.take(3).forEach { (id, title) -> Button(onClick = { action { controller.loadAndStart(id, selectedSpeed) } }) { Text(title) } } }
                        Row { ProductDemoController.SCENARIOS.drop(3).forEach { (id, title) -> Button(onClick = { action { controller.loadAndStart(id, selectedSpeed) } }) { Text(title) } } }
                        Text("${playback.phase} · 시나리오 ${playback.scenarioTimeMs / 1000}초 · ${playback.speedMultiplier ?: 0}배 · 원본 ${playback.deliveredSamples}건", color = Color.White)
                        Row {
                            Button(onClick = { action { controller.pause() } }) { Text("일시정지") }
                            Button(onClick = { action { controller.resume() } }) { Text("재개") }
                            Button(onClick = { action { controller.stop() } }) { Text("중지") }
                            Button(onClick = { action { controller.reset() } }) { Text("초기화") }
                        }
                        Text("$message\n연속 ${trip.continuousDriveSec}초 · 최근24시간 ${trip.last24hDriveSec}초\n" +
                            "단계 ${stage?.stage ?: trip.tripId?.let { trip.stage } ?: "대기"} · 위험도 ${fatigue.currentRisk ?: "미평가"} · 평활 ${fatigue.smoothedRisk ?: "미평가"}\n" +
                            "민감 모드 ${fatigue.departureFatigue ?: "미판정"} · 회전 ${navi?.distToNextTurnM ?: "미수신"}m · 실내 ${frame?.cabinTempC ?: "미수신"}℃\n" +
                            "기어 ${frame?.gear} · 출처 ${frame?.qualityOf(com.sleepissue.awakedrive.engine.model.SignalField.GEAR)?.source}", color = Color.White)
                        if (button != null) Row {
                            Button(onClick = { action { controller.completeButton(ResponseIntent.YES) } }) { Text("대체 응답: 네") }
                            Button(onClick = { action { controller.completeButton(ResponseIntent.REST) } }) { Text("대체 응답: 쉴게") }
                        }
                        Button(onClick = { action { controller.enableSdk() } }) { Text("Virtual Console 입력 우선 켜기") }
                        Text("SDK 우선 입력은 별도 선택입니다. 데모는 실제 내비 경유지·통풍을 변경하지 않습니다.", color = Color.LightGray)
                        Button(onClick = { action { controller.preparePark() } }) { Text("쉼터 P단 준비(모의)") }
                        Button(onClick = { action {
                            controller.savePowerOff()
                            finishAffinity()
                            Process.killProcess(Process.myPid())
                        } }) { Text("시동 끔(저장 후 앱 종료)") }
                        Text("앱을 다시 열면 저장본 복원과 데모 시계 +22분을 적용합니다. 실제 OFF→ON/STR 시험이 아닙니다.", color = Color.LightGray)
                    }
                    Button(onClick = { finish() }) { Text("제품 홈으로") }
                }
            }
        }
    }
    override fun onStop() { super.onStop(); scope.launch { controller.stop() } }
    override fun onDestroy() {
        restrictions.close()
        scope.launch { try { controller.stop() } finally { scope.cancel() } }
        super.onDestroy()
    }
}

@Composable
fun DemoPlaybackBadge(state: DemoPlaybackState) {
    val label = when (state.phase) {
        DemoPhase.PLAYING -> "데모 재생 중"
        DemoPhase.PAUSED -> "데모 일시정지"
        else -> return
    }
    Text(label, color = Color.White, modifier = Modifier.background(Color(0xFF654ABC)).padding(8.dp))
}
