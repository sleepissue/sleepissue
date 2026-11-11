package com.sleepissue.awakedrive.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.sleepissue.awakedrive.service.RealTripMonitorService

class DrivingPreviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 음성 대답(마이크)과 감시 알림. 거절해도 앱은 뜨고, 음성 확인은 버튼 대체로 간다(3번 CheckPolicy).
        val permissions = listOf(Manifest.permission.RECORD_AUDIO) +
            if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        requestPermissions(permissions.toTypedArray(), 0)

        setContent {
            MaterialTheme {
                AppNavigation(onStartMonitoring = ::startMonitoring)
            }
        }
    }

    /** 운전 시작 = 감시 서비스 시작. 서비스가 신호 수집·판단·개입·4번 휴식을 모두 돌린다. */
    private fun startMonitoring() {
        try {
            startForegroundService(Intent(this, RealTripMonitorService::class.java).setAction(RealTripMonitorService.ACTION_START))
        } catch (e: Exception) {
            Toast.makeText(this, "감시 시작 실패 ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }
}
