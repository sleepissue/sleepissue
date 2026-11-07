package com.sleepissue.awakedrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val HomeBackground = Color(0xFF101A33)
private val HomeCard = Color(0xFF202D50)
private val HomePurple = Color(0xFF8C7BFF)
private val HomeBlue = Color(0xFF4EA5FF)

@Composable
fun HomeScreen(
    onStartDriving: () -> Unit = {},
    drivingCondition: String = "확인 전",
    drowsinessForecast: String = "정보 없음",
    expectedCautionTime: String = "예측 전"
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        HomeBackground,
                        Color(0xFF223C73),
                        Color(0xFF37316D)
                    )
                )
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {

            // 왼쪽 영역: 앱 소개 및 운전 시작
            Column(
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {

                Column {
                    Text(
                        text = "AwakeDrive",
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "깨어있는 드라이브",
                        color = Color(0xFFBFCFFF),
                        fontSize = 16.sp
                    )
                }

                Column {
                    Text(
                        text = "좋은 컨디션으로\n안전한 드라이브를\n시작해요!",
                        color = Color.White,
                        fontSize = 35.sp,
                        lineHeight = 46.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    Text(
                        text = "출발 전 운전 상태를 확인하고\n졸음 위험에 미리 대비하세요.",
                        color = Color(0xFFC8D6F3),
                        fontSize = 17.sp,
                        lineHeight = 25.sp
                    )
                }

                Button(
                    onClick = onStartDriving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = HomePurple
                    ),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(
                        text = "운전 시작  →",
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            // 오른쪽 영역: 출발 전 상태 카드
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.2f),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = HomeCard
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(22.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "오늘의 운전 컨디션",
                            fontSize = 20.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "●  $drivingCondition",
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF61E1C1)
                        )

                        Text(
                            text = "운전 시작 전 상태를 확인해 주세요.",
                            fontSize = 14.sp,
                            color = Color(0xFFB4C5E8)
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {

                    HomeInfoCard(
                        title = "오늘의 졸음 예보",
                        value = drowsinessForecast,
                        description = "예보 상태",
                        accent = HomeBlue,
                        modifier = Modifier.weight(1f)
                    )

                    HomeInfoCard(
                        title = "예상 주의 시점",
                        value = expectedCautionTime,
                        description = "개인화 예보",
                        accent = HomePurple,
                        modifier = Modifier.weight(1f)
                    )
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF334477)
                    )
                ) {
                    Text(
                        text = "음성 안내는 졸음 위험 감지 시 작동하도록 연동할 예정입니다.",
                        modifier = Modifier.padding(16.dp),
                        color = Color(0xFFDCE6FF),
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeInfoCard(
    title: String,
    value: String,
    description: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = HomeCard
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                color = Color(0xFFD1DDF7),
                fontSize = 15.sp
            )

            Text(
                text = value,
                color = accent,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = description,
                color = Color(0xFF9FB1D5),
                fontSize = 12.sp
            )
        }
    }
}