package com.sleepissue.awakedrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.sleepissue.awakedrive.engine.model.EvaluationStatus
import com.sleepissue.awakedrive.engine.model.InterventionState
import kotlinx.coroutines.flow.StateFlow

/**
 * 팀원의 InterventionState를 관찰하여
 * 경고 단계에 맞는 AwakeDrive UI를 표시한다.
 *
 * 위험도 계산이나 음성 실행은 담당하지 않는다.
 */
@Composable
fun InterventionConnectedScreen(
    interventionState: StateFlow<InterventionState>,
    modifier: Modifier = Modifier
) {

    // 팀원 코드에서 상태가 변경되면 Compose가 다시 그려짐
    val state by interventionState.collectAsState()

    // 기존에 만든 변환 함수 사용
    val drivingStatus = state.toDrivingStatus()

    Box(
        modifier = modifier.fillMaxSize()
    ) {

        when {

            // 아직 위험 평가가 이루어지지 않았을 때
            state.evaluationStatus == EvaluationStatus.NOT_EVALUATED -> {

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF101837)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {

                    CircularProgressIndicator(
                        color = Color(0xFF8270F4)
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "주행 상태 확인 중",
                        color = Color.White,
                        fontSize = 24.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "아직 위험 평가가 완료되지 않았습니다.",
                        color = Color(0xFFAABBDD),
                        fontSize = 15.sp
                    )
                }
            }

            // 위험 평가가 완료되었고 정상 주행 상태일 때
            drivingStatus == DrivingStatus.NORMAL -> {

                DrivingScreen(
                    status = drivingStatus
                )
            }

            // 주의·확인·휴식 권장 단계
            else -> {

                AlertScreen(
                    status = drivingStatus
                )
            }
        }
    }
}