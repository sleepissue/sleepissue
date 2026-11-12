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
    onStartMonitoring: () -> Unit = {},
    onOpenDemo: () -> Unit = {},
    restricted: Boolean = true,
    monitoringStatus: DrivingStatus? = null
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
        if (connected || action is DriveUiAction.Condition) onAction?.invoke(action) else feedback = "실행 담당이 연결되지 않았습니다"
    }
    LaunchedEffect(restricted) {
        if (restricted && page != AppPage.DRIVING) {
            page = AppPage.DRIVING
            feedback = "주행 중에는 설정·리포트·데모·휴식 조작이 잠깁니다"
        }
    }
    LaunchedEffect(session.sessionId) { feedback = "" }
    LaunchedEffect(session.restAreaName, session.parked, restricted) {
        if (session.restAreaName != null && session.parked && !restricted) page = AppPage.REST
    }
    LaunchedEffect(settings, session.sessionId) {
        if (connected) onAction?.invoke(DriveUiAction.Preferences(settings))
    }

    LaunchedEffect(restricted) {
        if (restricted && currentPage in setOf(AppPage.SETUP, AppPage.SETUP_SAFETY, AppPage.SETUP_COMPLETE, AppPage.REPORT, AppPage.DEBUG)) currentPage = AppPage.DRIVING
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101837))
    ) {

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (currentPage) {

                AppPage.HOME -> {
                    HomeScreen(
                        onStartDriving = {
                            if (!restricted) currentPage = AppPage.SETUP
                            currentStatus = DrivingStatus.WAITING
                            showAlert = false
                        }
                    )
                }

                AppPage.SETUP -> {
                    FirstRunSetupScreen(
                        onNext = { isSleepy ->

                            // 선택한 컨디션을 화면 간 전달
                            selectedSleepy = isSleepy

                            // 다음 단계: 주행 전 안전 확인
                            currentPage = AppPage.SETUP_SAFETY
                        }
                    )
                }

                AppPage.SETUP_SAFETY -> {
                    SetupSafetyScreen(
                        onNext = {
                            currentPage = AppPage.SETUP_COMPLETE
                        }
                    )
                }

                AppPage.SETUP_COMPLETE -> {
                    SetupCompleteScreen(
                        isSleepy = selectedSleepy ?: false,
                        onStartDriving = {
                            onStartMonitoring()
                            setupCompleted = true
                            currentStatus = DrivingStatus.WAITING
                            showAlert = false
                            currentPage = AppPage.DRIVING
                        }
                    )
                }
                AppPage.SAFETY -> SetupSafetyScreen { settings = settingsStore.read(); page = AppPage.COMPLETE }
                AppPage.COMPLETE -> SetupCompleteScreen(condition = condition ?: DrivingCondition.GOOD,
                    forecast = effectiveForecast, onStartDriving = { onStartMonitoring(); setupCompleted = true; page = AppPage.DRIVING })
                AppPage.DRIVING -> {
                    if (showAlert) {
                        AlertScreen(status = currentStatus)
                    } else {
                        DrivingScreen(
                            status = monitoringStatus ?: currentStatus,
                            fatigueUiState = fatigueUiState
                        )
                    }
                }

                AppPage.REST -> {
                    RestScreen()
                }

                AppPage.REPORT -> {
                    ReportScreen()
                }

                AppPage.DEBUG -> {
                    Column(Modifier.padding(22.dp)) {
                        Text("JSON 데모", color = Color.White, fontSize = 27.sp)
                        Text("정상·딴짓·졸음·대화·보조 주행·출발 피로", color = Color.White)
                        Button(onClick = onOpenDemo, enabled = !restricted) { Text("시나리오 선택·재생") }
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

        // 화면 하단 메뉴
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF19264C))
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {

            NavigationButton(
                text = "홈",
                selected = currentPage == AppPage.HOME,
                modifier = Modifier.weight(1f)
            ) {
                currentPage = AppPage.HOME
            }

            NavigationButton(
                text = "주행",
                selected = currentPage == AppPage.DRIVING,
                modifier = Modifier.weight(1f)
            ) {
                if (setupCompleted) {
                    currentPage = AppPage.DRIVING
                } else {
                    if (!restricted) currentPage = AppPage.SETUP
                }
            }

            NavigationButton(
                text = "휴식",
                selected = currentPage == AppPage.REST,
                modifier = Modifier.weight(1f)
            ) {
                currentPage = AppPage.REST
            }

            NavigationButton(
                text = "리포트",
                selected = currentPage == AppPage.REPORT,
                modifier = Modifier.weight(1f)
            ) {
                if (!restricted) currentPage = AppPage.REPORT
            }

            NavigationButton(
                text = "데모",
                selected = currentPage == AppPage.DEBUG,
                modifier = Modifier.weight(1f)
            ) {
                if (!restricted) currentPage = AppPage.DEBUG
            }
        }
    }
}

fun DrivingCondition.toStartCondition() = when (this) {
    DrivingCondition.GOOD -> StartCondition.FRESH
    DrivingCondition.SLIGHTLY_SLEEPY -> StartCondition.NORMAL
    DrivingCondition.VERY_SLEEPY -> StartCondition.DROWSY
}
