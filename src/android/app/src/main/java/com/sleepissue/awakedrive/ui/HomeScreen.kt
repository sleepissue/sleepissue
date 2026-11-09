package com.sleepissue.awakedrive.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleepissue.awakedrive.R

private val HomeBackground = Color(0xFF10121F)
private val HomeCard = Color(0xFF242338)
private val HomePurple = Color(0xFFAC83FF)
private val HomePurpleDark = Color(0xFF754AC9)
private val HomeBlue = Color(0xFF83B7FF)
private val HomeMuted = Color(0xFFADB0C8)

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
            .background(HomeBackground)
    ) {

        // 기존 주행 배경 이미지를 홈 화면에도 활용
        Image(
            painter = painterResource(id = R.drawable.drive_bg),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            alpha = 0.20f
        )

        // 어두운 네이비 + 보라색 네온 그라데이션
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xF5101220),
                            Color(0xE91A1830),
                            Color(0xED171525)
                        )
                    )
                )
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {

            // 왼쪽: 메인 소개 영역
            Column(
                modifier = Modifier
                    .weight(1.15f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {

                // 앱 이름과 상태 표시
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(HomePurpleDark),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "A",
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 21.sp
                            )
                        }

                        Text(
                            text = "AwakeDrive",
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50.dp))
                            .background(Color(0xFF302942))
                            .padding(
                                horizontal = 13.dp,
                                vertical = 8.dp
                            )
                    ) {
                        Text(
                            text = "●  주행 대기중",
                            color = Color(0xFFD0B5FF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // 메인 문구
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.Center
                ) {

                    Text(
                        text = "안전하고",
                        color = Color.White,
                        fontSize = 31.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(3.dp))

                    Text(
                        text = "졸음 없는 주행을 위해",
                        color = Color.White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(5.dp))

                    Text(
                        text = "AwakeDrive와 함께하세요!",
                        color = HomePurple,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.ExtraBold
                    )

                    Spacer(modifier = Modifier.height(15.dp))

                    Text(
                        text = "운전 패턴을 살피고 위험 신호를 알려주는\n나만의 스마트 드라이빙 파트너",
                        color = HomeMuted,
                        fontSize = 15.sp,
                        lineHeight = 23.sp
                    )
                }

                // 핵심 기능 소개 카드 3개
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(112.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {

                    HomeFeatureCard(
                        icon = "◈",
                        title = "실시간 모니터링",
                        description = "운전 패턴 분석",
                        modifier = Modifier.weight(1f)
                    )

                    HomeFeatureCard(
                        icon = "♧",
                        title = "즉각적인 알림",
                        description = "단계별 경고",
                        modifier = Modifier.weight(1f)
                    )

                    HomeFeatureCard(
                        icon = "☕",
                        title = "휴식 가이드",
                        description = "안전한 휴식",
                        modifier = Modifier.weight(1f)
                    )
                }

                // 주행 시작 버튼
                Button(
                    onClick = onStartDriving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = HomePurpleDark
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        HomePurple,
                                        HomePurpleDark
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "▶  주행 시작하기",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            // 오른쪽: 출발 전 상태 영역
            Column(
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                Text(
                    text = "출발 전 체크",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "안전한 주행을 위한 맞춤 정보",
                    color = HomeMuted,
                    fontSize = 13.sp
                )

                // 운전 컨디션
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.15f),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = HomeCard.copy(alpha = 0.94f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {

                        Text(
                            text = "오늘의 운전 컨디션",
                            color = Color(0xFFE4DFFF),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        Text(
                            text = drivingCondition,
                            color = if (drivingCondition == "확인 전")
                                HomeMuted
                            else
                                Color(0xFF75E8C2),
                            fontSize = 30.sp,
                            fontWeight = FontWeight.ExtraBold
                        )

                        Text(
                            text = "출발 전 운전자 상태를 확인해 주세요.",
                            color = HomeMuted,
                            fontSize = 12.sp
                        )
                    }
                }

                // 졸음 예보 및 주의 시점
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {

                    HomeInfoCard(
                        title = "졸음 위험 예보",
                        value = drowsinessForecast,
                        description = "실시간 분석 정보",
                        accent = HomeBlue,
                        modifier = Modifier.weight(1f)
                    )

                    HomeInfoCard(
                        title = "예상 주의 시점",
                        value = expectedCautionTime,
                        description = "개인화 예측 정보",
                        accent = HomePurple,
                        modifier = Modifier.weight(1f)
                    )
                }

                // 하단 안내
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(15.dp))
                        .background(Color(0xFF312944))
                        .border(
                            width = 1.dp,
                            color = HomePurple.copy(alpha = 0.22f),
                            shape = RoundedCornerShape(15.dp)
                        )
                        .padding(14.dp)
                ) {
                    Text(
                        text = "✦  졸음 위험 감지 시 단계별 안내를 제공합니다.",
                        color = Color(0xFFDCCFFF),
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeFeatureCard(
    icon: String,
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = HomeCard.copy(alpha = 0.94f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            Text(
                text = icon,
                color = HomePurple,
                fontSize = 27.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(7.dp))

            Text(
                text = title,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = description,
                color = HomeMuted,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
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
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = HomeCard.copy(alpha = 0.94f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(15.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            Text(
                text = title,
                color = Color(0xFFDBD7EA),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )

            Text(
                text = value,
                color = accent,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )

            Text(
                text = description,
                color = HomeMuted,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
    }
}