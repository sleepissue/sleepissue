package com.sleepissue.awakedrive.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleepissue.awakedrive.R

// 개발용 시연 상태
// 실제 졸음 판단 엔진과는 아직 연결되지 않았음
private val DEMO_STATUS = DrivingStatus.WAITING

enum class DrivingStatus {
    WAITING,
    NORMAL,
    CAUTION,
    CHECK,
    REST
}

private val BgStart = Color(0xFF101837)
private val BgEnd = Color(0xFF342A72)
private val CardBg = Color(0xFF222F59)
private val PrimaryText = Color(0xFFF7F8FF)
private val SecondaryText = Color(0xFFBAC8ED)
private val Mint = Color(0xFF60E4BF)
private val Purple = Color(0xFF997CFF)

private fun drivingStatusColor(status: DrivingStatus): Color =
    when (status) {
        DrivingStatus.WAITING -> Color(0xFFADB0C8)
        DrivingStatus.NORMAL -> Mint
        DrivingStatus.CAUTION -> Color(0xFFFFD166)
        DrivingStatus.CHECK -> Color(0xFFFFA15C)
        DrivingStatus.REST -> Color(0xFFFF6978)
    }

private fun drivingStatusTitle(status: DrivingStatus): String =
    when (status) {
        DrivingStatus.WAITING -> "판정 대기 중"
        DrivingStatus.NORMAL -> "안정적인 주행 중"
        DrivingStatus.CAUTION -> "졸음 주의"
        DrivingStatus.CHECK -> "운전자 확인 필요"
        DrivingStatus.REST -> "휴식을 권장합니다"
    }

private fun drivingStatusMessage(status: DrivingStatus): String =
    when (status) {
        DrivingStatus.WAITING ->
            "운전 상태 분석을 기다리고 있습니다."

        DrivingStatus.NORMAL ->
            "현재 특별한 경고가 없습니다."

        DrivingStatus.CAUTION ->
            "졸음 위험 징후가 감지되었습니다."

        DrivingStatus.CHECK ->
            "운전자 상태 확인이 필요한 단계입니다."

        DrivingStatus.REST ->
            "안전한 장소에서 휴식하세요."
    }

@Composable
fun DrivingScreen(
    status: DrivingStatus = DEMO_STATUS,
    drivingTime: String = "측정 전",
    restAreaState: RestAreaUiState = RestAreaUiState.NotSearched,
    drivingMode: String = "일반 주행",
    fatigueUiState: FatigueUiState = FatigueUiState(),
    steeringDeg: Float? = null,
    sampleTimeMs: Long? = null,
    speedKph: Float? = null,
    frame: com.sleepissue.awakedrive.engine.model.SignalFrame? = null,
    demo: Boolean = false,
    metrics: com.sleepissue.awakedrive.engine.model.FatigueState? = null
) {
    val measurementStatus = fatigueUiState.measurementStatus

// 경고 단계가 있으면 기존 경고 표시를 유지한다.
// 아직 경고 단계가 없다면 엔진 측정 상태를 표시한다.
    val hasWarning = status != DrivingStatus.WAITING

    val displayTitle = if (hasWarning) {
        drivingStatusTitle(status)
    } else {
        FatigueUiFormatter.statusText(fatigueUiState)
    }

    val displayMessage = if (hasWarning) {
        drivingStatusMessage(status)
    } else {
        when (measurementStatus) {
            MeasurementStatus.WAITING ->
                "운전 상태 분석을 기다리고 있습니다."

            MeasurementStatus.GATE_WAIT ->
                "현재 주행 조건에서는 위험도를 측정할 수 없습니다."

            MeasurementStatus.ASSIST ->
                "보조 주행 중에는 조향 위험도 판정이 제외됩니다."

            MeasurementStatus.VALID ->
                "운전 상태를 분석하고 있습니다."
        }
    }

    val accent = if (hasWarning) {
        drivingStatusColor(status)
    } else {
        when (measurementStatus) {
            MeasurementStatus.WAITING,
            MeasurementStatus.GATE_WAIT -> Color(0xFFADB0C8)

            MeasurementStatus.ASSIST -> Purple
            MeasurementStatus.VALID -> Mint
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(BgStart, BgEnd)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // 상단 앱 정보
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "AwakeDrive",
                        color = PrimaryText,
                        fontSize = 27.sp,
                        fontWeight = FontWeight.ExtraBold
                    )

                    Text(
                        text = "DRIVE MONITOR",
                        color = SecondaryText,
                        fontSize = 11.sp,
                        letterSpacing = 2.sp
                    )
                }

                Surface(
                    shape = RoundedCornerShape(50.dp),
                    color = Color(0xFF263865)
                ) {
                    Text(
                        text = if (demo) "●  모니터링 화면 · DEMO" else if (frame != null) "●  차량 신호 모니터링" else "●  신호 연결 대기",
                        color = Mint,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(
                            horizontal = 16.dp,
                            vertical = 10.dp
                        )
                    )
                }
            }

            // 중앙 영역
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {

                // 왼쪽: 도로 이미지 + 실시간 신호 그래프
                Column(
                    modifier = Modifier
                        .weight(1.5f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {

                    // 상단: 기존 자동차·도로 이미지
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(22.dp))
                    ) {

                        Image(
                            painter = painterResource(
                                id = R.drawable.drive_bg
                            ),
                            contentDescription = "자동차와 도로 배경",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )

                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(14.dp),
                            color = Color(0xCC17214A),
                            shape = RoundedCornerShape(50.dp)
                        ) {
                            Text(
                                text = "◉  " + when {
                                    fatigueUiState.assistMode == true -> "보조 주행 · 조향 판정 제외"
                                    fatigueUiState.departureFatigue == true -> "민감 모드"
                                    else -> drivingMode
                                },
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(
                                    horizontal = 17.dp,
                                    vertical = 10.dp
                                )
                            )
                        }
                    }

                    // 하단: 실시간 그래프
                    val liveRiskHistory = remember { mutableStateListOf<Float>() }
                    val liveSteeringHistory = remember { mutableStateListOf<Float>() }
                    val speedHistory = remember { mutableStateListOf<Float>() }
                    val microHistory = remember { mutableStateListOf<Float>() }
                    val largeHistory = remember { mutableStateListOf<Float>() }
                    val suddenHistory = remember { mutableStateListOf<Float>() }
                    LaunchedEffect(sampleTimeMs) {
                        fun append(list: MutableList<Float>, value: Float?) {
                            if (value == null) return
                            list.add(value)
                            if (list.size > 60) list.removeAt(0)
                        }
                        append(speedHistory, speedKph)
                        append(microHistory, metrics?.takeIf { it.valid }?.microPerMin)
                        append(largeHistory, metrics?.takeIf { it.valid }?.largePerMin)
                        append(suddenHistory, metrics?.takeIf { it.valid }?.suddenCount5m?.toFloat())
                    }

                    LaunchedEffect(sampleTimeMs, steeringDeg) {
                        steeringDeg?.let { angle ->
                            liveSteeringHistory.add(angle.coerceIn(-30f, 30f))
                            if (liveSteeringHistory.size > 60) {
                                liveSteeringHistory.removeAt(0)
                            }
                        }
                    }

                    LaunchedEffect(sampleTimeMs, fatigueUiState.currentRisk) {
                        fatigueUiState.currentRisk?.let { risk ->
                            liveRiskHistory.add(risk.coerceIn(0f, 100f))
                            if (liveRiskHistory.size > 60) {
                                liveRiskHistory.removeAt(0)
                            }
                        }
                    }

                    frame?.let { signal ->
                        fun source(field: com.sleepissue.awakedrive.engine.model.SignalField) =
                            signal.qualityOf(field).source?.name ?: "미수신"
                        val seats = signal.seats
                        Text("차속 ${signal.speedKph ?: "미수신"} km/h · 기어 ${signal.gear ?: "미수신"} · 방향지시등 ${signal.turnSignal ?: "미수신"} · 브레이크 ${signal.brakeOn ?: "미수신"}",
                            color = SecondaryText, fontSize = 12.sp)
                        Text("착석: 운전석 ${seats?.driver ?: "미수신"} · 동승석 ${seats?.frontPassenger ?: "미수신"}", color = SecondaryText, fontSize = 12.sp)
                        Text("차속 ${source(com.sleepissue.awakedrive.engine.model.SignalField.SPEED_KPH)} · 기어 ${source(com.sleepissue.awakedrive.engine.model.SignalField.GEAR)} · 조향 ${source(com.sleepissue.awakedrive.engine.model.SignalField.STEERING_DEG)} · 착석 ${source(com.sleepissue.awakedrive.engine.model.SignalField.SEATS)}",
                            color = SecondaryText, fontSize = 11.sp)
                    }
                    DrivingSignalGraph(
                        riskHistory = liveRiskHistory,
                        steeringHistory = liveSteeringHistory,
                        speedHistory = speedHistory, microHistory = microHistory,
                        largeHistory = largeHistory, suddenHistory = suddenHistory,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                    )
                }

                // 오른쪽: 상태 및 주행 정보
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1.1f),
                        color = CardBg,
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "현재 주행 상태",
                                color = SecondaryText,
                                fontSize = 15.sp
                            )

                            Spacer(Modifier.height(12.dp))

                            Text(
                                text = "●  $displayTitle",
                                color = accent,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 30.sp
                            )

                            Spacer(Modifier.height(10.dp))

                            Text(
                                text = displayMessage,
                                color = PrimaryText,
                                fontSize = 15.sp,
                                lineHeight = 22.sp
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        color = CardBg,
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "연속 운전 시간",
                                    color = SecondaryText,
                                    fontSize = 13.sp
                                )

                                Spacer(Modifier.height(8.dp))

                                Text(
                                    text = drivingTime,
                                    color = PrimaryText,
                                    fontSize = 21.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "다음 졸음쉼터",
                                    color = SecondaryText,
                                    fontSize = 13.sp
                                )

                                Spacer(Modifier.height(8.dp))

                                when (val area = restAreaState) {

                                    is RestAreaUiState.Found -> {

                                        Column {

                                            Text(
                                                text = area.name,
                                                color = Color.White,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )

                                            Spacer(
                                                modifier = Modifier.height(4.dp)
                                            )

                                            Text(
                                                text = area.distance,
                                                color = Purple,
                                                fontSize = 23.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    else -> {

                                        Text(
                                            text = when (area) {
                                                RestAreaUiState.NotSearched -> "검색 전"
                                                RestAreaUiState.Loading -> "검색 중..."
                                                RestAreaUiState.NotFound -> "검색 결과 없음"
                                                RestAreaUiState.Error -> "정보 확인 불가"
                                                is RestAreaUiState.Found -> error("이미 처리된 상태")
                                            },
                                            color = Purple,
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.8f),
                        color = Color(0xFF443582),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "◖))  음성 경고",
                                color = PrimaryText,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(Modifier.height(6.dp))

                            Text(
                                text = "위험 감지 시 음성 안내 예정",
                                color = SecondaryText,
                                fontSize = 13.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(
    showBackground = true,
    widthDp = 960,
    heightDp = 540
)
@Composable
fun DrivingScreenPreview() {
    MaterialTheme {
        DrivingScreen()
    }
}