package com.sleepissue.awakedrive.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

data class DriverUiSettings(
    val microphoneConsent: Boolean = false,
    val ventilationConsent: Boolean = false,
    val passengerChecks: Boolean = true,
    val ageBand: String = "미설정",
    val frequentChecks: Boolean = false,
)

class DriverUiSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("driver-ui-settings", Context.MODE_PRIVATE)
    fun read() = DriverUiSettings(preferences.getBoolean("microphone", false),
        preferences.getBoolean("ventilation", false), preferences.getBoolean("passenger", true),
        preferences.getString("age", "미설정") ?: "미설정", preferences.getBoolean("frequentChecks", false))
    fun write(settings: DriverUiSettings) {
        preferences.edit().putBoolean("microphone", settings.microphoneConsent)
            .putBoolean("ventilation", settings.ventilationConsent).putBoolean("passenger", settings.passengerChecks)
            .putString("age", settings.ageBand).putBoolean("frequentChecks", settings.frequentChecks).apply()
    }
}

@Composable
fun SettingsScreen(
    settings: DriverUiSettings,
    onChange: (DriverUiSettings) -> Unit,
    onDeleteRecords: (() -> Unit)?,
    onContinue: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var microphoneGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context,
        Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphoneGranted = granted
        onChange(settings.copy(microphoneConsent = granted))
    }
    var deleteDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (onContinue == null) "설정" else "첫 실행 · 권한과 동의", color = Color.White, fontSize = 25.sp)
        Text("졸음을 진단하지 않습니다. 차량 기본 경고를 대신하지 않는 위험 신호 알림·대응 보조 서비스입니다.", color = Color(0xFFCFD5EC))
        SettingToggle("마이크 사용 동의", settings.microphoneConsent && microphoneGranted) { checked ->
            if (checked) requestPermission.launch(Manifest.permission.RECORD_AUDIO)
            else onChange(settings.copy(microphoneConsent = false))
        }
        Text(if (microphoneGranted) "마이크 권한 허용됨 · 음성 원본과 인식 문장은 저장하지 않습니다."
            else "마이크 미허용 · 동승자 버튼과 화면 안내를 사용할 수 있습니다.", color = Color.LightGray)
        SettingToggle("운전석 통풍 자동 켜기 동의 (선택)", settings.ventilationConsent) {
            onChange(settings.copy(ventilationConsent = it))
        }
        Text("동의한 경우에만 운전석을 제어하고, 다시 읽어 켜짐을 확인합니다. 휴식 인증 뒤 원래 값으로 되돌립니다.", color = Color.LightGray)
        SettingToggle("동승자 확인 사용", settings.passengerChecks) { onChange(settings.copy(passengerChecks = it)) }
        Text("연령대 (선택)", color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("미설정", "청년", "중년", "고령").forEach { age ->
                FilterChip(selected = settings.ageBand == age, onClick = { onChange(settings.copy(ageBand = age)) }, label = { Text(age) })
            }
        }
        SettingToggle("보조 주행 중 자주 확인 (10분)", settings.frequentChecks) { onChange(settings.copy(frequentChecks = it)) }
        Text("기본은 20분, 자주 확인·위험 맥락은 10분입니다. 위험 맥락의 확인을 늦출 수 없습니다.", color = Color.LightGray)
        OutlinedButton(onClick = { deleteDialog = true }, enabled = onDeleteRecords != null) { Text("연결된 기록 삭제") }
        if (onDeleteRecords == null) Text("기록 저장소 미연결", color = Color.LightGray)
        onContinue?.let { Button(onClick = it) { Text("선택 내용 저장하고 계속") } }
    }
    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false },
        title = { Text("연결된 기록을 삭제할까요?") }, text = { Text("삭제한 기록은 복구할 수 없습니다.") },
        confirmButton = { TextButton(onClick = { deleteDialog = false; onDeleteRecords?.invoke() }) { Text("삭제") } },
        dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("취소") } })
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f).padding(top = 12.dp))
        Switch(checked = checked, onCheckedChange = change)
    }
}
