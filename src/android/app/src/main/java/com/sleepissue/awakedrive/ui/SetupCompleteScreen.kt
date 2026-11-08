package com.sleepissue.awakedrive.ui

import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleepissue.awakedrive.R

private val CompletePurple = Color(0xFFA87CFF)
private val CompleteMint = Color(0xFF76E9CB)
private val CompleteMuted = Color(0xFFBAB8D2)

@Composable
fun SetupCompleteScreen(
    isSleepy: Boolean,
    onStartDriving: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF111329))
    ) {

        Image(
            painter = painterResource(R.drawable.drive_bg),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = 0.35f,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xF5111329),
                            Color(0xDA242049),
                            Color(0xBB111329)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(15.dp)
        ) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {

                Column {
                    Text(
                        text = "AwakeDrive",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold
                    )

                    Text(
                        text = "DRIVE MONITOR",
                        color = CompletePurple,
                        fontSize = 11.sp,
                        letterSpacing = 2.sp
                    )
                }

                Text(
                    text = "✓ 컨디션 체크    ─    ✓ 안전 확인    ─    ③ 완료",
                    color = Color(0xFFDCCFFF),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = "✓",
                color = CompletePurple,
                fontSize = 48.sp,
                fontWeight = FontWeight.ExtraBold
            )

            Text(
                text = "설정 완료",
                color = Color.White,
                fontSize = 31.sp,
                fontWeight = FontWeight.ExtraBold
            )

            Text(
                text = "주행 전 기본 설정을 완료했어요.",
                color = CompleteMuted,
                fontSize = 15.sp
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .weight(1f),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xEA282642)
                )
            ) {

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(22.dp)
                ) {

                    Text(
                        text = if (isSleepy) "☾" else "☺",
                        color = if (isSleepy)
                            CompletePurple
                        else
                            CompleteMint,
                        fontSize = 54.sp
                    )

                    Column(
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {

                        Text(
                            text = "선택한 운전 컨디션",
                            color = CompleteMuted,
                            fontSize = 14.sp
                        )

                        Text(
                            text = if (isSleepy) "졸려요" else "괜찮아요",
                            color = Color.White,
                            fontSize = 27.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = if (isSleepy)
                                "졸음 상태를 선택했어요. 실제 민감 모드 연동은 준비 중입니다."
                            else
                                "현재 컨디션을 선택했어요. 실제 엔진 연동은 준비 중입니다.",
                            color = CompleteMuted,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .background(
                        Color(0xDA302C50),
                        RoundedCornerShape(16.dp)
                    )
                    .padding(14.dp)
            ) {

                Text(
                    text = "ⓘ  주행 화면에서 운전 상태를 확인할 수 있어요.\n" +
                            "엔진 연결 전에는 '판정 대기 중'으로 표시됩니다.",
                    color = Color(0xFFDCD5F2),
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Button(
                onClick = onStartDriving,
                modifier = Modifier
                    .fillMaxWidth(0.48f)
                    .height(55.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CompletePurple
                )
            ) {

                Text(
                    text = "주행 화면으로 이동  ❯",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}