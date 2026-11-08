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
fun AppNavigation() {

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
                            currentPage = AppPage.SETUP
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
                        DrivingScreen(status = currentStatus)
                    }
                }

                AppPage.REST -> {
                    RestScreen()
                }

                AppPage.REPORT -> {
                    ReportScreen()
                }

                AppPage.DEBUG -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF17254B))
                            .padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {

                        Text(
                            text = "DEMO CONTROL",
                            color = Color.White,
                            fontSize = 27.sp
                        )

                        Text(
                            text = "졸음 경고 UI 단계별 테스트",
                            color = Color(0xFFC5D2F3),
                            fontSize = 17.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {

                            DebugButton(
                                text = "정상",
                                color = Color(0xFF1FAF91),
                                modifier = Modifier.weight(1f)
                            ) {
                                currentStatus = DrivingStatus.NORMAL
                                showAlert = false
                                currentPage = AppPage.DRIVING
                            }

                            DebugButton(
                                text = "1단계 주의",
                                color = Color(0xFFD6A33A),
                                modifier = Modifier.weight(1f)
                            ) {
                                currentStatus = DrivingStatus.CAUTION
                                showAlert = true
                                currentPage = AppPage.DRIVING
                            }

                            DebugButton(
                                text = "2단계 확인",
                                color = Color(0xFFDB8748),
                                modifier = Modifier.weight(1f)
                            ) {
                                currentStatus = DrivingStatus.CHECK
                                showAlert = true
                                currentPage = AppPage.DRIVING
                            }

                            DebugButton(
                                text = "3단계 휴식",
                                color = Color(0xFFDE5968),
                                modifier = Modifier.weight(1f)
                            ) {
                                currentStatus = DrivingStatus.REST
                                showAlert = true
                                currentPage = AppPage.DRIVING
                            }
                        }

                        Text(
                            text = "※ 개발·시연 전용 기능입니다.\n"
                                    + "실제 졸음 감지 및 음성 경고는 아직 연결되지 않았습니다.",
                            color = Color(0xFF9FAFD4),
                            fontSize = 14.sp,
                            lineHeight = 23.sp
                        )
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
                    currentPage = AppPage.SETUP
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
                currentPage = AppPage.REPORT
            }

            NavigationButton(
                text = "테스트",
                selected = currentPage == AppPage.DEBUG,
                modifier = Modifier.weight(1f)
            ) {
                currentPage = AppPage.DEBUG
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

@Composable
private fun DebugButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(54.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color
        ),
        contentPadding = PaddingValues(horizontal = 5.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 14.sp,
            maxLines = 1
        )
    }
}