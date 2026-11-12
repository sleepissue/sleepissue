package com.sleepissue.awakedrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class AppPage {
    HOME,
    SETUP,
    SETUP_SAFETY,
    SETUP_COMPLETE,
    DRIVING,
    REST,
    REPORT,
    DEBUG
}

@Composable
fun AppNavigation(
    fatigueUiState: FatigueUiState = FatigueUiState(),
    onStartMonitoring: () -> Unit = {},
    onOpenDemo: () -> Unit = {},
    restricted: Boolean = true,
    monitoringStatus: DrivingStatus? = null
) {

    var currentPage by remember {
        mutableStateOf(AppPage.HOME)
    }

    var currentStatus by remember {
        mutableStateOf(DrivingStatus.WAITING)
    }

    var selectedSleepy by remember {
        mutableStateOf<Boolean?>(null)
    }

    var setupCompleted by remember {
        mutableStateOf(false)
    }

    var showAlert by remember {
        mutableStateOf(false)
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

@Composable
private fun NavigationButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) {
                Color(0xFF8270F4)
            } else {
                Color(0xFF30416F)
            }
        )
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 15.sp
        )
    }
}
