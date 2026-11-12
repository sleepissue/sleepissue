package com.sleepissue.awakedrive.ui

import android.Manifest
import android.content.*
import android.os.IBinder
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import com.sleepissue.awakedrive.demo.ProductDemoController
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.sleepissue.awakedrive.service.RealTripMonitorService

class DrivingPreviewActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var restrictions: DrivingRestrictionGate
    private var service: RealTripMonitorService? = null
    private var bound = false
    private var fatigue by mutableStateOf(FatigueUiState())
    private var status by mutableStateOf<DrivingStatus?>(null)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { service = (binder as RealTripMonitorService.LocalBinder).service }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restrictions = DrivingRestrictionGate(this)
        if (getSharedPreferences(ProductDemoController.RESTART_PREFS, MODE_PRIVATE).getBoolean("pending", false))
            startActivity(Intent(this, ProductDemoActivity::class.java))
        scope.launch {
            while (isActive) {
                service?.pipeline?.let { fatigue = it.runner.snapshots.value.toFatigueUiState(); status = it.manager.state.value.toDrivingStatus() }
                delay(200)
            }
        }
        // 음성 대답(마이크)과 감시 알림. 거절해도 앱은 뜨고, 음성 확인은 버튼 대체로 간다(3번 CheckPolicy).
        val permissions = listOf(Manifest.permission.RECORD_AUDIO, "android.car.permission.CAR_SPEED", "android.car.permission.CAR_POWERTRAIN") +
            if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        requestPermissions(permissions.toTypedArray(), 0)

        setContent {
            MaterialTheme {
                val restricted by restrictions.restricted.collectAsState()
                AppNavigation(fatigueUiState = fatigue, onStartMonitoring = ::startMonitoring,
                    onOpenDemo = { startActivity(Intent(this, ProductDemoActivity::class.java)) }, restricted = restricted, monitoringStatus = status)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!bound) bound = bindService(Intent(this, RealTripMonitorService::class.java), connection, 0)
    }
    override fun onDestroy() {
        if (bound) unbindService(connection)
        restrictions.close(); scope.cancel()
        super.onDestroy()
    }

    /** 운전 시작 = 감시 서비스 시작. 서비스가 신호 수집·판단·개입·4번 휴식을 모두 돌린다. */
    private fun startMonitoring() {
        try {
            startForegroundService(Intent(this, RealTripMonitorService::class.java).setAction(RealTripMonitorService.ACTION_START))
            if (!bound) bound = bindService(Intent(this, RealTripMonitorService::class.java), connection, BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Toast.makeText(this, "감시 시작 실패 ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }
}
