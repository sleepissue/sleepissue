package com.sleepissue.awakedrive.ui

import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import com.sleepissue.awakedrive.demo.ProductDemoSession
import com.sleepissue.awakedrive.demo.AndroidDemoExecutionFactory
import com.sleepissue.awakedrive.navi.RestAreaRepository
import com.sleepissue.awakedrive.service.MonitoringPipeline
import com.sleepissue.awakedrive.service.RealTripMonitorService
import kotlinx.coroutines.*

/** 화면은 감시 서비스의 기존 파이프라인을 관찰하고, 제품 데모는 별도 세션으로 실행한다. */
open class LiveDriveActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var service by mutableStateOf<RealTripMonitorService?>(null)
    private var binding by mutableStateOf<LiveDriveUiSession?>(null)
    private var bound = false
    private var monitor: Job? = null
    private var pipelineOwner: MonitoringPipeline? = null
    private var pendingCondition: DrivingCondition? = null
    private var demoActive by mutableStateOf(false)
    private lateinit var demo: ProductDemoSession
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as RealTripMonitorService.LocalBinder).service
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 단일 작업 흐름으로 데모 원본 전달과 엔진 계산 순서를 유지한다.
        demo = ProductDemoSession(CoroutineScope(scope.coroutineContext + Dispatchers.Default.limitedParallelism(1)),
            AndroidUiClock(), RestAreaRepository(this).areas, AndroidDemoExecutionFactory(this))
        setContent { MaterialTheme {
            val demoState by demo.state.collectAsState()
            val liveState = binding?.state?.collectAsState()?.value ?: DriveSessionUiState()
            AppNavigation(session = if (demoActive) demoState else liveState,
                onStartMonitoring = ::startMonitoring,
                onExitDemo = { scope.launch { demo.stop(); demoActive = false } },
                onAction = { action ->
                    if (action is DriveUiAction.Condition) pendingCondition = action.value
                    scope.launch { if (demoActive) demo.action(action) else binding?.actions?.dispatch(action) } },
                demoContent = { DemoControls(demo,
                    load = { name, speed -> scope.launch {
                        if (service?.runtime?.running == true) {
                            Toast.makeText(this@LiveDriveActivity, "감시 중에는 데모를 시작할 수 없습니다", Toast.LENGTH_SHORT).show()
                        } else {
                            val json = withContext(Dispatchers.IO) { assets.open("scenarios/$name.json").bufferedReader().use { it.readText() } }
                            withContext(Dispatchers.Default) { demo.load(json, speed) }
                            pendingCondition?.let { demo.action(DriveUiAction.Condition(it)) }
                            demoActive = true
                        }
                    } }, command = { action -> scope.launch { demo.action() } },
                    onExit = { scope.launch {
                        demo.stop()
                        demoActive = false
                    } }) })
        } }
    }
    private fun startMonitoring() {
        if (demoActive) return
        try {
            startForegroundService(Intent(this, RealTripMonitorService::class.java).setAction(RealTripMonitorService.ACTION_START))
        } catch (error: RuntimeException) {
            Toast.makeText(this, "감시 시작 실패: ${error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }
    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, RealTripMonitorService::class.java), connection, BIND_AUTO_CREATE)
        monitor = scope.launch {
            while (isActive) {
                val owner = service
                val input = owner?.input
                val pipeline = owner?.pipeline
                if (binding != null && pipeline !== pipelineOwner) { binding?.close(); binding = null; pipelineOwner = null }
                if (owner != null && input != null && pipeline != null && binding == null) {
                    pipelineOwner = pipeline
                    pendingCondition?.let { pipeline.runner.reportCondition(it == DrivingCondition.VERY_SLEEPY, owner.clock.scenarioTimeMs(), pipeline.manager) }
                    binding = LiveDriveUiSession(owner, input.hub, pipeline, scope).also { it.start() }
                }
                delay(250)
            }
        }
    }
    override fun onStop() {
        monitor?.cancel(); monitor = null
        binding?.close(); binding = null; pipelineOwner = null
        scope.launch { demo.stop() }
        if (bound) unbindService(connection)
        bound = false; service = null
        super.onStop()
    }
    override fun onDestroy() {
        scope.launch { demo.close(); scope.cancel() }
        super.onDestroy()
    }
}
