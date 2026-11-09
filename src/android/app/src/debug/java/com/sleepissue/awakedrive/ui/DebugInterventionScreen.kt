package com.sleepissue.awakedrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.sleepissue.awakedrive.debug.DebugHarnessController
import com.sleepissue.awakedrive.engine.model.EvaluationStatus

/**
 * 개발용 시뮬레이터의 경고 단계와 주행 시간을
 * AwakeDrive UI에 연결하는 화면.
 */
@Composable
fun DebugInterventionScreen(
    controller: DebugHarnessController
) {

    val snapshot by controller.state.collectAsState()

    val intervention = snapshot.intervention

    val drivingStatus = intervention.toDrivingStatus()

    val drivingTime = formatDrivingTime(
        snapshot.trip.continuousDriveSec
    )

    when {

        intervention.evaluationStatus ==
                EvaluationStatus.NOT_EVALUATED -> {

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

                Spacer(
                    modifier = Modifier.height(20.dp)
                )

                Text(
                    text = "주행 상태 확인 중",
                    color = Color.White,
                    fontSize = 24.sp
                )

                Spacer(
                    modifier = Modifier.height(10.dp)
                )

                Text(
                    text = "아직 위험 평가가 완료되지 않았습니다.",
                    color = Color(0xFFAABBDD),
                    fontSize = 15.sp
                )
            }
        }

        drivingStatus == DrivingStatus.NORMAL -> {

            DrivingScreen(
                status = drivingStatus,
                drivingTime = drivingTime
            )
        }

        else -> {

            AlertScreen(
                status = drivingStatus
            )
        }
    }
}