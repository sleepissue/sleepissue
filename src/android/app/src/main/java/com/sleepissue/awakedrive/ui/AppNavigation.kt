package com.sleepissue.awakedrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleepissue.awakedrive.engine.forecast.*
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.time.Clock

private enum class AppPage { HOME, PERMISSIONS, SETUP, SAFETY, COMPLETE, DRIVING, REST, REPORT, SETTINGS, DEBUG }

/** 시각은 한 곳에서 주입한다. 예보는 날짜 시각, 타이머는 세션 논리 시각을 쓴다. */
class AndroidUiClock : Clock {
    private val wall = java.time.Clock.systemUTC()
    override fun scenarioTimeMs() = wall.millis()
    override fun wallTimeMs() = wall.millis()
    override fun elapsedRealtimeMs() = android.os.SystemClock.elapsedRealtime()
}

@Composable
fun AppNavigation(
    fatigueUiState: FatigueUiState = FatigueUiState(),
    interventionState: InterventionState? = null,
    continuousDriveSec: Long? = null,
    steeringDeg: Float? = null,
    restAreaState: RestAreaUiState = RestAreaUiState.NotSearched,
    forecast: ForecastResult? = null,
    session: DriveSessionUiState = DriveSessionUiState(),
    onAction: ((DriveUiAction) -> Unit)? = null,
    clock: Clock? = null,
    onStartMonitoring: () -> Unit = {},
    demoContent: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val settingsStore = remember { DriverUiSettingsStore(context) }
    var settings by remember { mutableStateOf(settingsStore.read()) }
    val firstRun = remember { context.getSharedPreferences("driver-ui-settings", 0) }
    var page by rememberSaveable { mutableStateOf(AppPage.HOME) }
    var condition by rememberSaveable { mutableStateOf<DrivingCondition?>(null) }
    var setupCompleted by rememberSaveable { mutableStateOf(false) }
    var localForecast by remember { mutableStateOf<ForecastResult?>(null) }
    var feedback by remember { mutableStateOf("") }
    val systemClock = remember { AndroidUiClock() }
    val activeClock = clock ?: systemClock
    val platformRestricted = rememberCarUiRestricted()
    val restricted = platformRestricted == true || session.frame?.let {
        it.gear != Gear.P || it.speedKph != 0f
    } == true
    val fatigue = session.fatigue?.toFatigueUiState() ?: fatigueUiState
    val intervention = session.intervention ?: interventionState
    val status = intervention?.toDrivingStatus() ?: DrivingStatus.WAITING
    val effectiveForecast = session.forecast ?: forecast ?: localForecast
    val forecastUi = effectiveForecast.toForecastUiState()
    val nextArea = if (session.actionsConnected) session.nextRestArea else restAreaState
    val connected = session.actionsConnected && onAction != null
    fun request(action: DriveUiAction) {
        if (connected) onAction?.invoke(action) else feedback = "실행 담당이 연결되지 않았습니다"
    }
    LaunchedEffect(restricted) {
        if (restricted && page != AppPage.DRIVING) {
            page = AppPage.DRIVING
            feedback = "주행 중에는 설정·리포트·데모·휴식 조작이 잠깁니다"
        }
    }
    LaunchedEffect(session.sessionId) { feedback = "" }
    LaunchedEffect(settings, session.sessionId) {
        if (connected) onAction?.invoke(DriveUiAction.Preferences(settings))
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF101837))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("진단 아님 · 차량 기본 경고를 대신하지 않습니다", color = Color(0xFFCED5EC), fontSize = 12.sp)
            if (session.demoPlaying) Text("데모 재생 중", color = Color(0xFFFFD166), fontSize = 14.sp)
            else if (session.demoSession) Text("데모 검증 세션 · 정지", color = Color.LightGray, fontSize = 12.sp)
        }
        if (session.message.isNotBlank()) Text(session.message, color = Color.LightGray, modifier = Modifier.padding(horizontal = 16.dp))
        if (feedback.isNotBlank()) Text(feedback, color = Color(0xFFFFD166), modifier = Modifier.padding(horizontal = 16.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (page) {
                AppPage.HOME -> HomeScreen(
                    onStartDriving = {
                        page = if (restricted) AppPage.DRIVING else if (!firstRun.getBoolean("onboarding", false)) AppPage.PERMISSIONS else AppPage.SETUP
                    },
                    drivingCondition = condition?.label() ?: "확인 전",
                    drowsinessForecast = effectiveForecast?.ttsMessage ?: "컨디션 선택 뒤 예보합니다",
                    expectedCautionTime = forecastUi.expectedCautionTime,
                )
                AppPage.PERMISSIONS -> SettingsScreen(settings, { settings = it; settingsStore.write(it) },
                    if (connected) ({ request(DriveUiAction.DeleteRecords) }) else null,
                    onContinue = { firstRun.edit().putBoolean("onboarding", true).apply(); page = AppPage.SETUP })
                AppPage.SETUP -> FirstRunSetupScreen { value ->
                    condition = value
                    request(DriveUiAction.Condition(value))
                    localForecast = ForecastEngine(activeClock).calculate(ForecastInput(
                        ((continuousDriveSec ?: session.trip.continuousDriveSec) / 60).toInt(), null,
                        (session.trip.last24hDriveSec / 60).toInt(), emptyList(), value.toStartCondition()),
                        ForecastRecalcTrigger.CONDITION_INPUT)
                    page = AppPage.SAFETY
                }
                AppPage.SAFETY -> SetupSafetyScreen { page = AppPage.COMPLETE }
                AppPage.COMPLETE -> SetupCompleteScreen(condition = condition ?: DrivingCondition.GOOD,
                    forecast = effectiveForecast, onStartDriving = { onStartMonitoring(); setupCompleted = true; page = AppPage.DRIVING })
                AppPage.DRIVING -> {
                    if (status == DrivingStatus.CHECK || status == DrivingStatus.REST) {
                        AlertScreen(status = status, nextRestArea = nextArea.description(),
                            onPassengerCheck = { request(DriveUiAction.Passenger(true)) },
                            onPassengerOkay = { request(DriveUiAction.Passenger(false)) },
                            onCancelRest = { request(DriveUiAction.CancelWaypoint) },
                            actionsEnabled = connected, passengerEnabled = settings.passengerChecks,
                            ventilationText = session.ventilationText, waypointMessage = session.waypointMessage,
                            demo = session.demoSession)
                    } else Column {
                        if (status == DrivingStatus.CAUTION) Text("1단계 · 졸음 주의 — 가까운 안전한 곳에서 쉬어 가세요",
                            color = Color(0xFFFFD166), modifier = Modifier.fillMaxWidth().background(Color(0xFF453C30)).padding(12.dp))
                        DrivingScreen(status = status, fatigueUiState = fatigue,
                            steeringDeg = session.frame?.steeringDeg ?: steeringDeg,
                            restAreaState = nextArea,
                            drivingTime = (continuousDriveSec ?: session.trip.continuousDriveSec.takeIf { session.frame != null })
                                ?.let { "%d시간 %02d분".format(it / 3600, (it % 3600) / 60) } ?: "측정 전",
                            sampleTimeMs = session.tMs, speedKph = session.frame?.speedKph,
                            metrics = session.fatigue?.fatigue, frame = session.frame)
                    }
                }
                AppPage.REST -> RestScreen(session, connected, { request(it) })
                AppPage.REPORT -> ReportScreen(session.toReportUiState())
                AppPage.SETTINGS -> SettingsScreen(settings, { settings = it; settingsStore.write(it) },
                    if (connected) ({ request(DriveUiAction.DeleteRecords) }) else null)
                AppPage.DEBUG -> if (demoContent != null) demoContent() else Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("데모 시나리오", color = Color.White, fontSize = 25.sp)
                    Text("개발 검증 화면의 정상·딴짓·졸음·대화·보조 주행·출발 피로 선택과 1·10·60배 재생을 사용하세요.", color = Color.LightGray)
                    Text(session.message, color = Color.White)
                    Text("가상 경유지 제안 상태: " + session.waypointMessage, color = Color.LightGray)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("홈" to AppPage.HOME, "주행" to AppPage.DRIVING, "휴식" to AppPage.REST,
                "리포트" to AppPage.REPORT, "설정" to AppPage.SETTINGS, "데모" to AppPage.DEBUG).forEach { (label, target) ->
                val enabled = !restricted || target == AppPage.DRIVING
                Button(onClick = {
                    page = if (target == AppPage.DRIVING && !setupCompleted && !restricted) AppPage.SETUP else target
                }, enabled = enabled, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
                    Text(label, maxLines = 1)
                }
            }
        }
    }
}

fun DrivingCondition.toStartCondition() = when (this) {
    DrivingCondition.GOOD -> StartCondition.FRESH
    DrivingCondition.SLIGHTLY_SLEEPY -> StartCondition.NORMAL
    DrivingCondition.VERY_SLEEPY -> StartCondition.DROWSY
}
fun DrivingCondition.label() = when (this) {
    DrivingCondition.GOOD -> "개운해요"
    DrivingCondition.SLIGHTLY_SLEEPY -> "보통"
    DrivingCondition.VERY_SLEEPY -> "졸려요"
}
fun RestAreaUiState.description() = when (this) {
    is RestAreaUiState.Found -> "$name · $distance · " + when (hasToilet) {
        true -> "화장실 있음"
        false -> "화장실 없음"
        null -> "화장실 정보 없음"
    }
    RestAreaUiState.Loading -> "검색 중"
    RestAreaUiState.NotFound -> "조건에 맞는 앞쪽 쉼터 없음"
    RestAreaUiState.Error -> "쉼터 검색 실패"
    RestAreaUiState.NotSearched -> "검색 전"
}
