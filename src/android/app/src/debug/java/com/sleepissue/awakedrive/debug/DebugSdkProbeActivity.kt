package com.sleepissue.awakedrive.debug

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.app.ActivityCompat
import android.content.pm.PackageManager
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.signal.*
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.pleos.*
import com.sleepissue.awakedrive.signal.*
import kotlinx.coroutines.*

/** 실제 SDK만 연결하는 화면이다. Tracker·Store·Room·가짜 입력을 생성하지 않는다. */
class DebugSdkProbeActivity : AppCompatActivity() {
    private val clock = object : Clock {
        override fun scenarioTimeMs() = SystemClock.elapsedRealtime()
        override fun elapsedRealtimeMs() = SystemClock.elapsedRealtime()
        override fun wallTimeMs() = java.time.Clock.systemUTC().millis()
    }
    private val config = EngineConfig()
    // [가정] 화면 검증용 3초 TTL. 제품 TTL·이벤트형 신호 유지 정책은 미확정이다.
    private val probePolicy = SdkProbePolicy(ttlMs = 3_000, naviPollMs = config.naviStateIntervalMs)
    private var session: CoroutineScope? = null
    private var adapter: PlaygroundSignalAdapter? = null
    private var hub: VehicleSignalHub? = null
    private lateinit var summary: TextView
    private lateinit var history: TextView
    private val rows = linkedMapOf<String, TextView>()
    private var sessions = 0
    private var frozen = false
    private var previousHistory = emptyList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = column().apply { setBackgroundColor(Color.rgb(16, 25, 35)) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16) + bars.left, dp(8) + bars.top, dp(16) + bars.right, dp(8) + bars.bottom); insets
        }
        root.addView(label("DEBUG ONLY · Playground SDK 실시간 검증", 23f))
        root.addView(label("실제 Playground SDK/AAOS 입력만 사용 · Source.REAL · 수동 운행/Room과 분리", 13f))
        val controls = LinearLayout(this)
        listOf("차속 권한" to {
            ActivityCompat.requestPermissions(this, arrayOf("android.car.permission.CAR_SPEED"), 34)
        }, "연결" to { connect() }, "중지" to { stop() }, "재연결" to { stop(); connect() },
            "표시 고정/재개" to { render(); frozen = !frozen },
            "수신 기록" to { showHistory() },
            "운행·Room으로 돌아가기" to { finish() }).forEach { (name, action) ->
            controls.addView(Button(this).apply { text = name; isAllCaps = false; setOnClickListener { action() } }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        root.addView(controls)
        summary = label("미연결 · 연결 버튼을 누르세요", 14f); root.addView(summary)
        root.addView(label("[가정] TTL 3초(경계 포함), 원천 시각 없으면 수신 경과로 평가 · DEMO 대체 없음\n수신 Hz: 최근 10초 콜백 / 원천 Hz: 제공된 센서 시각만 사용 / Hub: 100ms 발행\n전륜각은 원시값만 확인하며 엔진 운전대각에 연결하지 않습니다. 변경 시에만 알리는 신호도 3초 후 STALE입니다.", 12f))
        root.addView(label("주행 중 OS가 화면을 가릴 수 있습니다. P/0으로 돌아와 오른쪽 아래 실제 SDK 변경 기록을 확인하세요.", 12f))
        val columns = LinearLayout(this)
        val vehicle = column(); val navi = column()
        val keys = SignalField.entries.map { it.name } +
            listOf("SEAT:ROW_1_LEFT", "SEAT:ROW_1_RIGHT", "SEAT:ROW_2_LEFT", "SEAT:ROW_2_CENTER", "SEAT:ROW_2_RIGHT") + NaviField.entries.map { it.name }
        keys.forEachIndexed { i, key ->
            val text = label("$key · 미연결", 14f).apply { setPadding(dp(6), dp(8), dp(6), dp(8)); setTextIsSelectable(true) }
            rows[key] = text; (if (i < 10) vehicle else navi).addView(text)
        }
        history = label("SDK 변경 기록 없음 · 수동/가짜 입력 기록이 아닙니다.", 13f)
        navi.addView(history)
        listOf(vehicle, navi).forEach { col -> columns.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)) }
        root.addView(columns, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root); ViewCompat.requestApplyInsets(root)
    }
    private fun connect() {
        if (session != null) return
        frozen = false
        previousHistory = emptyList()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { session = it }
        sessions++
        val a = PlaygroundSignalAdapter(applicationContext, clock, probePolicy, scope).also { adapter = it }
        val freshness = SignalFreshnessPolicy(probePolicy.ttlMs, probePolicy.ttlMs, MissingObservationPolicy.USE_RECEIPT_AGE)
        val h = VehicleSignalHub(listOf(HubSourceSet(Source.REAL, a.vehicleSource, a.naviSource,
            rateTimeBasis = ObservationTimeBasis.UNKNOWN)), clock,
            HubPolicy(SignalField.entries.associateWith { freshness }, NaviField.entries.associateWith { freshness }, freshness,
                SignalFallbackPolicy(false, 0, ObservationTimeBasis.ELAPSED_REALTIME), ObservationTimeBasis.ELAPSED_REALTIME, HubTickDriver.AUTOMATIC), config).also { hub = it }
        scope.launch {
            // 진단 TextView 갱신·SDK main 콜백이 100ms 발행을 직접 막지 않게 한다.
            // 부모 Job은 공유하므로 화면 중지 시 두 dispatcher의 작업이 함께 취소된다.
            h.start(CoroutineScope(scope.coroutineContext + Dispatchers.Default))
            a.start()
            while (isActive) { if (!frozen) render(); delay(500) }
        }
    }
    private fun stop() {
        adapter?.let {
            previousHistory = it.store.history()
            history.text = "이전 SDK 세션 변경 기록 (수집 종료)\n" + previousHistory.asReversed().joinToString("\n")
        }
        adapter?.close(); session?.cancel(); session = null; adapter = null; hub = null
        summary.text = "중지 · 세션 $sessions · 콜백/조회/연결 해제 · Hub 발행 중지"
        rows.forEach { (key, view) -> view.text = "$key · 중지 · 최종 값 null / 캐시 폐기" }
    }
    override fun onStop() { stop(); super.onStop() }
    private fun showHistory() {
        val snapshot = adapter?.store?.history() ?: previousHistory
        val text = label(snapshot.asReversed().joinToString("\n\n").ifEmpty { "아직 SDK 수신 기록이 없습니다." }, 15f)
            .apply { setTextColor(Color.DKGRAY); setPadding(dp(12), dp(8), dp(12), dp(8)); setTextIsSelectable(true) }
        AlertDialog.Builder(this).setTitle("실제 SDK 변경 기록 · 캡처 시점 · 최신순")
            .setView(ScrollView(this).apply { addView(text) }).setPositiveButton("닫기", null).show()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 34) {
            summary.text = if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
                "차속 권한 허용 · 연결 또는 재연결을 누르세요" else "차속 권한 거부 · 해당 신호는 null/PERMISSION_DENIED"
        }
    }
    private fun render() {
        val a = adapter ?: return; val h = hub ?: return; val now = clock.elapsedRealtimeMs()
        val d = h.diagnostics.value
        val frame = h.latestFrame.value; val navi = h.latestNavi.value
        fun hz(value: Float?) = value?.let { "%.2f".format(it) } ?: "알 수 없음/측정 전"
        summary.text = "${a.status.value} · 세션 $sessions · targetSdk ${applicationInfo.targetSdkVersion}\nHub 발행 ${d.emittedFrames}회 / ${hz(d.publicationRate.hz)}Hz · deadline 누락 ${d.skippedDeadlines} · 시계 오류 ${d.clockFailure}"
        a.store.readings().forEach { r ->
            val field = runCatching { SignalField.valueOf(r.key) }.getOrNull()
            val nf = runCatching { NaviField.valueOf(r.key) }.getOrNull()
            val q = field?.let { frame?.qualityOf(it) } ?: nf?.let { navi?.qualityOf(it) }
            val finalValue = if (field != null) frame?.valueOf(field) else if (nf != null) navi?.valueOf(nf) else
                r.value.takeIf { r.availability == SignalAvailability.AVAILABLE && r.receivedMs?.let { now - it <= probePolicy.ttlMs } == true }
            val availability = q?.availability ?: if (r.availability == SignalAvailability.AVAILABLE && r.receivedMs?.let { now - it > probePolicy.ttlMs } == true) SignalAvailability.STALE else r.availability
            rows[r.key]?.text = "${r.key} · 지원 ${r.support}\n권한 ${if (r.permissionGranted) "허용" else "거부"} · ${r.permission}\n" +
                "SDK 원시 ${r.raw} → 정규화 ${r.value ?: "null"}\n최종 ${finalValue ?: "null"} · REAL · $availability · ${q?.selectionReason ?: "개별 좌석"}\n" +
                "수신 경과 ${r.receivedMs?.let { now - it } ?: "미수신"}ms · 센서 시각 ${r.nativeMs?.let { "${it}ms/ELAPSED" } ?: "제공 안 됨"}\n" +
                "콜백 ${r.callbacks} / 최초 조회 ${r.queries} · 수신 ${hz(a.store.callbackHz(r.key))}Hz · 원천 ${hz(a.store.nativeHz(r.key))}Hz\n${r.note}"
        }
        val sq = frame?.qualityOf(SignalField.SEATS)
        rows["SEATS"]?.text = "SEATS · Hub 좌석 묶음\n최종 ${frame?.seats ?: "null"}\n${sq?.source ?: "REAL"} · ${sq?.availability ?: "UNAVAILABLE"} · ${sq?.selectionReason}\n" +
            "좌석별 원시값은 오른쪽 개별 행에서 확인합니다.\n묶음은 가장 오래된 좌석 수신 시각을 사용해 오래된 착석 근거를 연장하지 않습니다."
        history.text = "최근 실제 SDK 변경 기록 · 최대 100건 · 최신순\n" + a.store.history().asReversed().joinToString("\n")
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setTextColor(Color.rgb(231, 241, 248)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
