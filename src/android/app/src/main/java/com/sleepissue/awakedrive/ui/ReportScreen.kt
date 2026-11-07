package com.sleepissue.awakedrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ReportBg = Color(0xFF101837)
private val ReportCard = Color(0xFF25335F)
private val ReportPurple = Color(0xFFA58BFF)
private val ReportMint = Color(0xFF62DFBD)
private val ReportSub = Color(0xFFC0CCEB)

@Composable
fun ReportScreen(
    report: ReportUiState = ReportUiState(),
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(
                        ReportBg,
                        Color(0xFF263C77),
                        Color(0xFF493579)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 상단
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "주행 리포트",
                        color = Color.White,
                        fontSize = 29.sp,
                        fontWeight = FontWeight.ExtraBold
                    )

                    Text(
                        text = "DRIVE REPORT · DEMO",
                        color = ReportSub,
                        fontSize = 12.sp,
                        letterSpacing = 1.5.sp
                    )
                }

                Surface(
                    color = Color(0xFF304478),
                    shape = RoundedCornerShape(50.dp)
                ) {
                    Text(
                        text = "●  주행 종료 후 확인",
                        color = ReportMint,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(
                            horizontal = 17.dp,
                            vertical = 11.dp
                        )
                    )
                }
            }

            // 상단 요약 카드
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.75f),
                horizontalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                ReportSummaryCard(
                    title = "총 운전 시간",
                    value = report.totalDrivingTime ?: "집계 전",
                    accent = Color(0xFF78BAFF),
                    modifier = Modifier.weight(1f)
                )

                ReportSummaryCard(
                    title = "경고 발생",
                    value = report.warningCount?.let { "${it}회" } ?: "집계 전",
                    accent = Color(0xFFFFC477),
                    modifier = Modifier.weight(1f)
                )

                ReportSummaryCard(
                    title = "휴식 기록",
                    value = report.restCount?.let { "${it}회" } ?: "집계 전",
                    accent = ReportMint,
                    modifier = Modifier.weight(1f)
                )
            }

            // 하단 상세 카드
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.25f),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .weight(1.15f)
                        .fillMaxHeight(),
                    color = ReportCard,
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "경고 발생 기록",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )

                        if (report.warningEvents.isEmpty()) {

                            Text(
                                text = "아직 기록된 경고가 없습니다.",
                                color = ReportSub,
                                fontSize = 16.sp
                            )

                        } else {

                            report.warningEvents.forEach { event ->

                                ReportEventRow(
                                    time = event.time,
                                    message = "${event.stage} · ${event.description}",
                                    color = Color(0xFFFFD166)
                                )
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    color = ReportCard,
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "휴식 및 회복 기록",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )

                        if (report.restEvents.isEmpty()) {

                            Text(
                                text = "아직 기록된 휴식이 없습니다.",
                                color = ReportSub,
                                fontSize = 16.sp
                            )

                        } else {

                            report.restEvents.forEach { event ->

                                Text(
                                    text = "●  ${event.description} · ${event.time}",
                                    color = ReportMint,
                                    fontSize = 16.sp
                                )

                                event.duration?.let { duration ->

                                    Text(
                                        text = "◷  휴식 시간 $duration",
                                        color = ReportSub,
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportSummaryCard(
    title: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxHeight(),
        color = ReportCard,
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(17.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                color = ReportSub,
                fontSize = 15.sp
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = value,
                color = accent,
                fontSize = 27.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ReportEventRow(
    time: String,
    message: String,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "●",
            color = color,
            fontSize = 15.sp
        )

        Text(
            text = time,
            color = ReportSub,
            fontSize = 14.sp
        )

        Text(
            text = message,
            color = Color.White,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}