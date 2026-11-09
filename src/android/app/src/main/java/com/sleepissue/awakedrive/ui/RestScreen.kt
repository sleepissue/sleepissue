package com.sleepissue.awakedrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val REST_SECONDS = 15 * 60

private val RestBackground = Color(0xFF101837)
private val RestCard = Color(0xFF25335F)
private val RestPurple = Color(0xFF9B83FF)
private val RestMint = Color(0xFF64E0C2)

@Composable
fun RestScreen() {

    var remainingSeconds by remember {
        mutableIntStateOf(REST_SECONDS)
    }

    var isRunning by remember {
        mutableStateOf(false)
    }

    var isNapMode by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(isRunning) {
        while (isRunning && remainingSeconds > 0) {
            delay(1000L)
            if (isRunning && remainingSeconds > 0) {
                remainingSeconds--
            }
        }

        if (remainingSeconds == 0) {
            isRunning = false
        }
    }

    val minutes = remainingSeconds / 60
    val seconds = remainingSeconds % 60

    val formattedTime = "%02d:%02d".format(
        minutes,
        seconds
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(
                        RestBackground,
                        Color(0xFF293F7C),
                        Color(0xFF493478)
                    )
                )
            )
    ) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            Text(
                text = "AwakeDrive",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold
            )

            Text(
                text = "REST & RECOVERY",
                color = Color(0xFFC5D1F5),
                fontSize = 12.sp,
                letterSpacing = 2.sp
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {

                // 왼쪽: 15분 휴식 타이머
                Surface(
                    modifier = Modifier
                        .weight(1.2f)
                        .fillMaxHeight(),
                    color = RestCard,
                    shape = RoundedCornerShape(26.dp)
                ) {

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceEvenly
                    ) {

                        Text(
                            text = if (isNapMode) {
                                "파워냅 시간이에요"
                            } else {
                                "잠시 쉬어 가세요"
                            },
                            color = Color.White,
                            fontSize = 25.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(180.dp)
                        ) {

                            CircularProgressIndicator(
                                progress = {
                                    remainingSeconds.toFloat() /
                                            REST_SECONDS.toFloat()
                                },
                                modifier = Modifier.fillMaxSize(),
                                color = RestPurple,
                                trackColor = Color(0xFF475682),
                                strokeWidth = 10.dp
                            )

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {

                                Text(
                                    text = formattedTime,
                                    color = Color.White,
                                    fontSize = 39.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Text(
                                    text = "남은 시간",
                                    color = Color(0xFFBAC9EB),
                                    fontSize = 14.sp
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {

                            Button(
                                onClick = {
                                    if (remainingSeconds == 0) {
                                        remainingSeconds = REST_SECONDS
                                    }
                                    isRunning = !isRunning
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = RestPurple
                                ),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(
                                    text = if (isRunning) {
                                        "일시정지"
                                    } else {
                                        "휴식 시작"
                                    }
                                )
                            }

                            Button(
                                onClick = {
                                    isRunning = false
                                    remainingSeconds = REST_SECONDS
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF465480)
                                ),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("초기화")
                            }
                        }
                    }
                }

                // 오른쪽: 휴식 정보와 파워냅
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        color = RestCard,
                        shape = RoundedCornerShape(22.dp)
                    ) {

                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.Center
                        ) {

                            Text(
                                text = "☕  휴식 안내",
                                color = RestMint,
                                fontSize = 23.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(Modifier.height(12.dp))

                            Text(
                                text = "안전하게 정차한 뒤 충분히 쉬어 주세요.",
                                color = Color.White,
                                fontSize = 16.sp,
                                lineHeight = 24.sp
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        color = RestCard,
                        shape = RoundedCornerShape(22.dp)
                    ) {

                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.Center
                        ) {

                            Text(
                                text = "☾  파워냅",
                                color = RestPurple,
                                fontSize = 23.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(Modifier.height(10.dp))

                            Text(
                                text = "15분 동안 짧게 눈을 붙여 보세요.",
                                color = Color.White,
                                fontSize = 15.sp
                            )

                            Spacer(Modifier.height(14.dp))

                            Button(
                                onClick = {
                                    isRunning = false
                                    isNapMode = !isNapMode
                                    remainingSeconds = REST_SECONDS
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF55449A)
                                )
                            ) {
                                Text(
                                    if (isNapMode) {
                                        "일반 휴식으로 전환"
                                    } else {
                                        "파워냅 모드 선택"
                                    }
                                )
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF354476),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = "DEMO · 실제 차량 정차 확인 및 휴식 인증은 미연결",
                            color = Color(0xFFCED9F4),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}