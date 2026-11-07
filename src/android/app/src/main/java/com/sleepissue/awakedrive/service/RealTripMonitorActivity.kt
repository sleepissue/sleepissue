package com.sleepissue.awakedrive.service

import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.sleepissue.awakedrive.engine.model.SignalField
import kotlinx.coroutines.*

/** 화면은 서비스에 바인딩하여 상태를 읽는다. 화면 종료·회전은 수집의 종료 명령이 아니다. */
open class RealTripMonitorActivity : AppCompatActivity() {
    private val displayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var service: RealTripMonitorService? = null
    private var bound = false
    private var rendering: Job? = null
    private var autoConnectConsumed = false
    private lateinit var statusText: TextView
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as RealTripMonitorService.LocalBinder).service
            if (!autoConnectConsumed && intent.getBooleanExtra("sdkTripAutoConnect", false)) {
                autoConnectConsumed = true; startMonitoring()
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autoConnectConsumed = savedInstanceState?.getBoolean("autoConnectConsumed") ?: false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 16); setBackgroundColor(Color.rgb(16, 25, 35))
        }
        root.addView(label("Playground SDK 운행·Room · 감시 서비스", 23f))
        root.addView(label("REAL = Playground 입력 · 전용 DB awakedrive_sdk_trip.db · 전원 UNKNOWN", 13f))
        val first = LinearLayout(this); val second = LinearLayout(this)
        listOf("권한 확인" to {
            val permissions = mutableListOf("android.car.permission.CAR_SPEED", "android.car.permission.CAR_POWERTRAIN")
            if (Build.VERSION.SDK_INT >= 33) permissions += "android.permission.POST_NOTIFICATIONS"
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 35)
        }, "감시 시작" to { startMonitoring() }, "감시 중지" to { service?.stopMonitoring() },
            "즉시 저장" to { service?.saveNow() }, "진단 저장" to { service?.captureDiagnostics() },
            "돌아가기" to { finish() }).forEachIndexed { i, (name, action) ->
            (if (i < 3) first else second).addView(Button(this).apply {
                text = name; isAllCaps = false; setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, (56 * resources.displayMetrics.density).toInt(), 1f))
        }
        root.addView(first); root.addView(second)
        statusText = label("서비스 연결·Room 복원 중", 15f).apply { setTextIsSelectable(true) }
        root.addView(ScrollView(this).apply { addView(statusText) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }
    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, RealTripMonitorService::class.java), connection, BIND_AUTO_CREATE)
        rendering = displayScope.launch {
            var lastCapture = 0L
            while (isActive) {
                render()
                service?.let { s ->
                    if (intent.getBooleanExtra("sdkTripCapture", false) && s.clock.elapsedRealtimeMs() - lastCapture >= 1_000) {
                        s.captureDiagnostics(); lastCapture = s.clock.elapsedRealtimeMs()
                    }
                }
                delay(500)
            }
        }
    }
    private fun startMonitoring() {
        if (service?.stopping == true) {
            Toast.makeText(this, "중지 저장 중입니다. 완료 뒤 감시 시작을 누르세요", Toast.LENGTH_SHORT).show(); return
        }
        // 알림을 볼 수 있어야 사용자에게 시작·중지가 명확하다. OS상 FGS 필수 권한과는 구별한다.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "권한 확인에서 알림을 허용한 뒤 감시 시작을 누르세요", Toast.LENGTH_LONG).show(); return
        }
        try {
            startForegroundService(Intent(this, RealTripMonitorService::class.java).setAction(RealTripMonitorService.ACTION_START))
        } catch (e: Exception) {
            Toast.makeText(this, "시작 제한 ${e.javaClass.simpleName} · 화면에서 다시 시작하세요", Toast.LENGTH_LONG).show()
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("autoConnectConsumed", autoConnectConsumed); super.onSaveInstanceState(outState)
    }
    override fun onStop() {
        rendering?.cancel()
        if (bound) unbindService(connection)
        bound = false; service = null
        super.onStop()
    }
    override fun onDestroy() { displayScope.cancel(); super.onDestroy() }
    private fun label(value: String, size: Float) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.rgb(231, 241, 248)) }
    private fun render() {
        val s = service ?: return
        val c = s.runtime.session; val t = c.trip.value; val p = c.persistence.state.value; val precise = c.preciseState()
        val q = c.latestFrame?.qualityOf(SignalField.GEAR)
        val reading = s.input?.adapter?.store?.readings()?.find { it.key == "GEAR" }
        val boot = if (MonitorPreference(this).bootPending) "재부팅 뒤 수동 재개 대기 · 저장본 보존" else "앱 종료/강제 종료 후 자동 시작 보장 없음"
        statusText.text = "${s.runtime.status.value}\n${s.input?.adapter?.status?.value ?: "SDK 미연결"}\n" +
            "foreground ${s.foreground} · 수집 시작 ${s.runtime.starts}회 · 활성 저장 타이머 ${s.runtime.activeTimers}개\n" +
            "운행 ID ${t.tripId ?: "시작 전"}\n현재 ${t.driveState} · 기어 ${precise.lastObservation?.gear ?: "UNKNOWN"} · 전원 UNKNOWN\n" +
            "연속 ${t.continuousDriveSec}s (${precise.continuousDriveMs}ms) · 최근 24시간 ${t.last24hDriveSec}s\n" +
            "정차 ${precise.stopStartedAtMs?.let { (precise.lastObservation?.tMs ?: it) - it } ?: 0}ms · 정차 전 ${precise.continuousBeforeStopMs}ms\n" +
            "GEAR ${q?.availability} · 원천 변경 ${q?.observedAtMs} · 실제 수신 ${q?.receivedAtElapsedMs}\n" +
            "재조회 확인 ${q?.stateConfirmedAtElapsedMs} · 유효 끝 ${q?.stateLeaseUntilElapsedMs} · callback ${reading?.callbacks} / query ${reading?.queries}\n" +
            "Room 복원 ${p.restore} · 저장 ${p.operation} · 오류 ${p.failure} · 성공 ${p.successfulSaves}회\n" +
            "저장 논리 ${p.lastSavedScenarioMs} / 날짜 ${p.lastSavedWallMs} / 실제 경과 ${p.lastSavedElapsedMs}\n" +
            "저장 주기 실제 경과 10초 · [가정] V03_RETROACTIVE/EXCLUDE\n${s.diagnostic}\n" +
            "화면 종료/꺼짐은 감시 중지가 아닙니다. 알림 또는 감시 중지 버튼으로 끝내세요.\n$boot\n\n" +
            c.events.value.asReversed().joinToString("\n")
    }
}
