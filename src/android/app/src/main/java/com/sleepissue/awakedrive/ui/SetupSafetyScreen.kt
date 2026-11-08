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

private val SafetyPurple = Color(0xFFA87CFF)
private val SafetyMint = Color(0xFF76E9CB)
private val SafetyMuted = Color(0xFFBAB8D2)
private val SafetyCard = Color(0xFF24233F)

@Composable
fun SetupSafetyScreen(
    onNext: () -> Unit = {}
) {
    var seatbeltChecked by remember { mutableStateOf(false) }
    var readyChecked by remember { mutableStateOf(false) }

    val canContinue = seatbeltChecked && readyChecked

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF111329),
                        Color(0xFF28204D),
                        Color(0xFF141429)
                    )
                )
            )
    ) {

        // 배경 이미지
        Image(
            painter = painterResource(R.drawable.drive_bg),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = 0.15f,
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // 상단 로고 및 진행 단계
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
                        color = SafetyPurple,
                        fontSize = 11.sp,
                        letterSpacing = 2.sp
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    SafetyStep("✓", "컨디션 체크", false, true)

                    SafetyStep("2", "안전 확인", true, false)

                    SafetyStep("3", "완료", false, false)
                }
            }

            // 화면 제목
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Text(
                    text = "주행 전 안전 확인",
                    color = Color.White,
                    fontSize = 29.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Spacer(Modifier.height(7.dp))

                Text(
                    text = "안전벨트를 착용하고 운전 준비를 완료했나요?",
                    color = SafetyMuted,
                    fontSize = 16.sp
                )
            }

            // 안전 확인 영역
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = SafetyCard.copy(alpha = 0.94f)
                )
            ) {

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {

                        SafetyCheckCard(
                            icon = "♢",
                            title = "안전벨트 착용 완료",
                            description = "운전 중에는 항상\n안전벨트를 착용하세요.",
                            selected = seatbeltChecked,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                seatbeltChecked = !seatbeltChecked
                            }
                        )

                        SafetyCheckCard(
                            icon = "◉",
                            title = "운전 준비 완료",
                            description = "집중할 수 있는 상태로\n운전할 준비가 되었어요.",
                            selected = readyChecked,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                readyChecked = !readyChecked
                            }
                        )
                    }

                    // 하단 안내
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(15.dp))
                            .background(Color(0xFF302C50))
                            .padding(15.dp)
                    ) {

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {

                            Text(
                                text = "ⓘ",
                                color = SafetyPurple,
                                fontSize = 21.sp
                            )

                            Text(
                                text = "선택한 내용은 운전자의 자가 확인이며,\n실제 차량 신호와 자동으로 연동되지 않습니다.",
                                color = SafetyMuted,
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            )
                        }
                    }
                }
            }

            // 다음 버튼
            Button(
                onClick = onNext,
                enabled = canContinue,
                modifier = Modifier
                    .fillMaxWidth(0.42f)
                    .height(56.dp)
                    .align(Alignment.CenterHorizontally),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SafetyPurple,
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
private fun SafetyStep(
    number: String,
    title: String,
    active: Boolean,
    completed: Boolean
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
                    when {
                        active -> SafetyPurple
                        completed -> Color(0xFF6253A2)
                        else -> Color(0xFF393953)
                    }
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
            color = if (active) Color.White else SafetyMuted,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun SafetyCheckCard(
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
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) {
                    Color(0xFF333552)
                } else {
                    Color(0xFF292842)
                }
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    SafetyMint
                } else {
                    Color(0xFF494562)
                },
                shape = RoundedCornerShape(20.dp)
            )
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        Text(
            text = if (selected) "✓" else icon,
            color = if (selected) SafetyMint else SafetyPurple,
            fontSize = 35.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = title,
            color = Color.White,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = description,
            color = SafetyMuted,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(9.dp))

        Text(
            text = if (selected) "확인 완료" else "눌러서 확인",
            color = if (selected) SafetyMint else SafetyMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}