package com.sleepissue.awakedrive.ui

import android.content.*
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import com.sleepissue.awakedrive.service.RealTripMonitorService
import kotlinx.coroutines.*

/** 이미 시작한 감시 서비스에 바인딩한다. 화면 종료로 기존 신호 수집·운행 저장을 중지하지 않는다. */
class LiveDriveActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var service by mutableStateOf<RealTripMonitorService?>(null)
    private var binding by mutableStateOf<LiveDriveUiSession?>(null)
    private var bound = false
    private var monitor: Job? = null
    private var inputOwner: com.sleepissue.awakedrive.service.PlaygroundMonitorInput? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as RealTripMonitorService.LocalBinder).service
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme {
            val session = binding
            if (session == null) AppNavigation() else ConnectedDriveNavigation(session.state, session.actions)
        } }
    }
    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, RealTripMonitorService::class.java), connection, BIND_AUTO_CREATE)
        monitor = scope.launch {
            while (isActive) {
                val owner = service
                val input = owner?.input
                if (binding != null && input !== inputOwner) { binding?.close(); binding = null; inputOwner = null }
                if (owner != null && input != null && binding == null) {
                    inputOwner = input
                    binding = LiveDriveUiSession(this@LiveDriveActivity, owner, input.hub, scope).also { it.start() }
                } else if ((owner == null || input == null) && binding != null) {
                    binding?.close(); binding = null
                }
                delay(250)
            }
        }
    }
    override fun onStop() {
        monitor?.cancel(); monitor = null
        binding?.close(); binding = null
        inputOwner = null
        if (bound) unbindService(connection)
        bound = false; service = null
        super.onStop()
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
