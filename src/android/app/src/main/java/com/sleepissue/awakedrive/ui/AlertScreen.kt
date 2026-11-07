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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 경고 화면 UI
// 실제 위험 단계 판단은 팀원 코드에서 담당
@Composable
fun AlertScreen(
    status: DrivingStatus,
    nextRestArea: String = "12km",
    modifier: Modifier = Modifier
) {
    val alertColor = when (status) {
        DrivingStatus.NORMAL -> Color(0xFF60E4BF)
        DrivingStatus.CAUTION -> Color(0xFFFFD166)
        DrivingStatus.CHECK -> Color(0xFFFFA15C)
        DrivingStatus.REST -> Color(0xFFFF6978)
    }

    val title = when (status) {
        DrivingStatus.NORMAL -> "안정적인 주행 중"
        DrivingStatus.CAUTION -> "졸음 주의"
        DrivingStatus.CHECK -> "운전자 확인이 필요해요"
        DrivingStatus.REST -> "휴식이 필요해요!"
    }

    val message = when (status) {
        DrivingStatus.NORMAL ->
            "현재 특별한 경고가 없습니다."

        DrivingStatus.CAUTION ->
            "졸음 위험 징후가 감지되었습니다.\n안전 운전에 주의하세요."

        DrivingStatus.CHECK ->
            "운전자 상태를 확인할 단계입니다.\n음성 안내에 응답해 주세요."

        DrivingStatus.REST ->
            "가까운 안전한 장소에서 휴식하세요."
    }

    val symbol = when (status) {
        DrivingStatus.NORMAL -> "✓"
        DrivingStatus.CAUTION -> "!"
        DrivingStatus.CHECK -> "?"
        DrivingStatus.REST -> "!"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF101837),
                        Color(0xFF342A72)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.82f),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF222F59),
            shadowElevation = 10.dp
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {

                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = alertColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = symbol,
                        color = alertColor,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(
                            horizontal = 24.dp,
                            vertical = 8.dp
                        )
                    )
                }

                Text(
                    text = title,
                    color = alertColor,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = message,
                    color = Color.White,
                    fontSize = 18.sp,
                    lineHeight = 27.sp,
                    textAlign = TextAlign.Center
                )

                if (status == DrivingStatus.CHECK) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF3C4677)
                    ) {
                        Text(
                            text = "🎙  음성 확인 대기",
                            color = Color.White,
                            fontSize = 17.sp,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }

                if (status == DrivingStatus.REST) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF433663)
                    ) {
                        Text(
                            text = "⌖  다음 졸음쉼터  $nextRestArea",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(
                                horizontal = 22.dp,
                                vertical = 15.dp
                            )
                        )
                    }
                }

                Text(
                    text = "AWAKEDRIVE · DEMO",
                    color = Color(0xFF9EADD5),
                    fontSize = 12.sp,
                    letterSpacing = 2.sp
                )
            }
        }
    }
}