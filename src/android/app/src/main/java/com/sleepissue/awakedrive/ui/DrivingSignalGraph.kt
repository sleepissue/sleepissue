package com.sleepissue.awakedrive.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val GraphBackground = Color(0xFF1F1D35)
private val GraphPurple = Color(0xFFAC83FF)
private val GraphBlue = Color(0xFF52C8FF)
private val GraphGrid = Color(0xFF45415D)

/**
 * AwakeDrive 실시간 신호 그래프
 *
 * 실제 엔진 데이터가 연결되면 위험도와 조향 기록을 전달받는다.
 * 데이터가 없으면 그래프를 임의로 생성하지 않는다.
 */
@Composable
fun DrivingSignalGraph(
    riskHistory: List<Float> = emptyList(),
    steeringHistory: List<Float> = emptyList(),
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(GraphBackground)
            .border(
                width = 1.dp,
                color = GraphPurple.copy(alpha = 0.25f),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        Text(
            text = "실시간 신호 그래프",
            color = Color.White,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            SignalChart(
                title = "졸음 위험도",
                description = "0 ~ 100점",
                values = riskHistory,
                minValue = 0f,
                maxValue = 100f,
                lineColor = GraphPurple,
                modifier = Modifier.weight(1f)
            )

            SignalChart(
                title = "조향 변화",
                description = "-30° ~ +30°",
                values = steeringHistory,
                minValue = -30f,
                maxValue = 30f,
                lineColor = GraphBlue,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SignalChart(
    title: String,
    description: String,
    values: List<Float>,
    minValue: Float,
    maxValue: Float,
    lineColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {

            Text(
                text = "●  $title",
                color = lineColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = description,
                color = Color(0xFFA8A4BF),
                fontSize = 11.sp
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF151528)),
            contentAlignment = Alignment.Center
        ) {

            Canvas(modifier = Modifier.fillMaxSize()) {

                // 가로 기준선
                for (i in 1..3) {
                    val y = size.height * i / 4f

                    drawLine(
                        color = GraphGrid,
                        start = androidx.compose.ui.geometry.Offset(0f, y),
                        end = androidx.compose.ui.geometry.Offset(
                            size.width,
                            y
                        ),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // 세로 기준선
                for (i in 1..3) {
                    val x = size.width * i / 4f

                    drawLine(
                        color = GraphGrid,
                        start = androidx.compose.ui.geometry.Offset(x, 0f),
                        end = androidx.compose.ui.geometry.Offset(
                            x,
                            size.height
                        ),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // 실제 데이터가 두 개 이상 있을 때만 선을 그림
                if (values.size >= 2) {

                    val path = Path()
                    val range = maxValue - minValue

                    values.forEachIndexed { index, value ->

                        val x = size.width *
                                index / (values.size - 1).toFloat()

                        val normalized = (
                                (value - minValue) / range
                                ).coerceIn(0f, 1f)

                        val y = size.height *
                                (1f - normalized)

                        if (index == 0) {
                            path.moveTo(x, y)
                        } else {
                            path.lineTo(x, y)
                        }
                    }

                    drawPath(
                        path = path,
                        color = lineColor,
                        style = Stroke(
                            width = 3.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    )
                }
            }

            if (values.size < 2) {
                Text(
                    text = "데이터 수신 대기 중",
                    color = Color(0xFF9995B2),
                    fontSize = 13.sp
                )
            }
        }
    }
}