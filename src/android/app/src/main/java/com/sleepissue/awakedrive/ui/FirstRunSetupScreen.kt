package com.sleepissue.awakedrive.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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

private val SetupBackground = Color(0xFF111329)
private val SetupPurple = Color(0xFFA87CFF)
private val SetupMint = Color(0xFF76E9CB)
private val SetupCard = Color(0xFF24233F)
private val SetupMuted = Color(0xFFB5B3CE)

/**
 * 출발 전 운전자 컨디션 설정 화면.
 *
 * onNext에 운전자의 선택을 전달한다.
 * true = 졸려요
 * false = 괜찮아요
 *
 * 엔진 연동은 상위 화면에서 별도로 처리한다.
 */
@Composable
fun FirstRunSetupScreen(
    onNext: (Boolean) -> Unit = {}
) {
    var isSleepy by remember { mutableStateOf<Boolean?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(
                        SetupBackground,
                        Color(0xFF242049),
                        Color(0xFF141429)
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

            // 상단 로고와 진행 단계
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
                        color = SetupPurple,
                        fontSize = 11.sp,
                        letterSpacing = 2.sp
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    SetupStep("1", "컨디션 체크", true)
                    SetupStep("2", "주행 설정", false)
                    SetupStep("3", "완료", false)
                }
            }

            // 안내 문구
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Text(
                    text = "처음이신가요?",
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "더 안전하고 편안한 주행을 위해\n간단한 설정을 진행할게요.",
                    color = SetupMuted,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center
                )
            }

            // 중앙 설정 카드
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = SetupCard
                )
            ) {

                Row(
                    modifier = Modifier.fillMaxSize()
                ) {

                    // 왼쪽 자동차 이미지
                    Box(
                        modifier = Modifier
                            .weight(0.9f)
                            .fillMaxHeight()
                    ) {

                        Image(
                            painter = painterResource(R.drawable.drive_bg),
                            contentDescription = "야간 도로 위 자동차",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            Color.Transparent,
                                            SetupCard.copy(alpha = 0.35f)
                                        )
                                    )
                                )
                        )
                    }

                    // 오른쪽 설정 영역
                    Column(
                        modifier = Modifier
                            .weight(1.6f)
                            .fillMaxHeight()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {

                        Text(
                            text = "⌁  지금 운전 컨디션은 어떤가요?",
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "선택한 상태를 졸음 판단 엔진에 전달할 수 있어요.",
                            color = SetupMuted,
                            fontSize = 13.sp
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {

                            SetupConditionCard(
                                icon = "☺",
                                title = "괜찮아요",
                                description = "집중해서 운전할 수 있어요",
                                selected = isSleepy == false,
                                modifier = Modifier.weight(1f),
                                onClick = { isSleepy = false }
                            )

                            SetupConditionCard(
                                icon = "☾",
                                title = "졸려요",
                                description = "약간 졸린 것 같아요",
                                selected = isSleepy == true,
                                modifier = Modifier.weight(1f),
                                onClick = { isSleepy = true }
                            )
                        }

                        // 선택 결과 안내
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF302C50))
                                .padding(14.dp)
                        ) {

                            val modeText = when (isSleepy) {
                                true -> "민감 모드 요청"
                                false -> "일반 모드 요청"
                                null -> "선택 대기 중"
                            }

                            val description = when (isSleepy) {
                                true ->
                                    "졸음 상태를 엔진에 전달하도록 연동할 예정입니다."

                                false ->
                                    "현재 컨디션을 엔진에 전달하도록 연동할 예정입니다."

                                null ->
                                    "먼저 현재 운전 컨디션을 선택해 주세요."
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {

                                Text(
                                    text = "ⓘ",
                                    color = SetupPurple,
                                    fontSize = 20.sp
                                )

                                Column {

                                    Text(
                                        text = modeText,
                                        color = if (isSleepy == null)
                                            SetupMuted
                                        else
                                            SetupMint,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Text(
                                        text = description,
                                        color = SetupMuted,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 다음 버튼
            Button(
                onClick = {
                    isSleepy?.let(onNext)
                },
                enabled = isSleepy != null,
                modifier = Modifier
                    .fillMaxWidth(0.42f)
                    .height(56.dp)
                    .align(Alignment.CenterHorizontally),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SetupPurple,
                    disabledContainerColor = Color(0xFF4A465D)
                )
            ) {

                Text(
                    text = "다음  ❯",
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun SetupStep(
    number: String,
    title: String,
    active: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {

        Box(
            modifier = Modifier
                .size(29.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    if (active) SetupPurple
                    else Color(0xFF393953)
                ),
            contentAlignment = Alignment.Center
        ) {

            Text(
                text = number,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            text = title,
            color = if (active) Color.White else SetupMuted,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun SetupConditionCard(
    icon: String,
    title: String,
    description: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(19.dp))
            .background(
                if (selected)
                    Color(0xFF333552)
                else
                    Color(0xFF282740)
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected)
                    SetupMint
                else
                    Color(0xFF44415E),
                shape = RoundedCornerShape(19.dp)
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        Text(
            text = icon,
            color = if (selected) SetupMint else SetupPurple,
            fontSize = 30.sp
        )

        Spacer(Modifier.height(9.dp))

        Text(
            text = title,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(5.dp))

        Text(
            text = description,
            color = SetupMuted,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}