package com.sleepissue.awakedrive.debug

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.signal.SourceRate
import com.sleepissue.awakedrive.signal.*
import kotlinx.coroutines.*

class DebugHubProbeViewModel : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val controller = DebugHubProbeController(scope)
    init { scope.launch { controller.initialize() } }
    fun act(action: suspend DebugHubProbeController.() -> Unit, failed: (String) -> Unit) {
        scope.launch {
            try { controller.action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failed(error.message ?: "Hub 시험 입력 실패") }
        }
    }
    override fun onCleared() { scope.cancel() }
}

/** 기존 운행 화면에서 열고 뒤로 돌아가는 독립 시험 패널이다. 모든 입력은 가짜다. */
class DebugHubProbeActivity : AppCompatActivity() {
    private val model by lazy { ViewModelProvider(this)[DebugHubProbeViewModel::class.java] }
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var subscription: Job? = null
    private lateinit var summary: TextView
    private lateinit var sources: TextView
    private lateinit var fields: TextView
    private lateinit var demoCoordinates: CheckBox
    private val actions = mutableListOf<Button>()
    private val inputEditors = mutableMapOf<Source, Pair<EditText, EditText>>()
    private val textColor = Color.rgb(236, 242, 247)
    private val accent = Color.rgb(89, 216, 208)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = column().apply { setBackgroundColor(Color.rgb(16, 25, 35)) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(14) + bars.left, dp(12) + bars.top, dp(14) + bars.right, dp(12) + bars.bottom)
            insets
        }
        root.addView(label("DEBUG ONLY · 가짜 REAL 시험 입력 · SDK 미연결", 16f, Color.rgb(255, 198, 98)))
        root.addView(label("VehicleSignalHub 주기·품질·중재", 22f))
        root.addView(label("Hub 전용 가상 시계 · 운행/개입/Room 세션과 분리 · 실제 센서 성능 시험 아님", 12f))
        summary = label("Hub 준비 중", 15f, accent); root.addView(summary)
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val controls = column()
        controls.addView(button("운행·Room 검증으로 돌아가기") { finish() })
        controls.addView(label("[가정] 관측/수신 TTL 1,000ms · 경계 포함\nREAL 유지 0ms · 최신 DEMO 즉시 대체", 12f))
        Source.entries.forEach { source ->
            controls.addView(label(if (source == Source.REAL) "가짜 REAL 입력" else "가짜 DEMO 입력", 16f, accent))
            controls.addView(row(
                "${source.name} 시작" to { act { setInputEnabled(source, true) } },
                "${source.name} 중단" to { act { setInputEnabled(source, false) } },
            ))
            val steering = editor("${source.name} 조향 °", if (source == Source.REAL) "1" else "8")
            val speed = editor("${source.name} 차속 km/h", if (source == Source.REAL) "80" else "40")
            inputEditors[source] = steering to speed
            controls.addView(label("${source.name} 조향 (°)", 12f)); controls.addView(steering)
            controls.addView(label("${source.name} 차속 (km/h)", 12f)); controls.addView(speed)
            controls.addView(button("${source.name} 값 적용") {
                val angle = steering.text.toString().toFloatOrNull(); val kph = speed.text.toString().toFloatOrNull()
                if (angle == null || !angle.isFinite() || kph == null || !kph.isFinite() || kph < 0) {
                    speed.error = "조향은 유한한 숫자, 차속은 0 이상을 입력하세요."
                } else act { setValues(source, angle, kph) }
            })
        }
        controls.addView(label("값 적용 후 다음 원천 관측부터 반영됩니다.", 12f))
        controls.addView(row("REAL 2Hz" to { act { setRealRate(2) } }, "REAL 5Hz" to { act { setRealRate(5) } }, "REAL 10Hz" to { act { setRealRate(10) } }))
        controls.addView(button("REAL 관측만 일시정지") { act { pauseRealObservations() } })
        controls.addView(label("구독은 유지하되 관측을 멈춰 STALE을 확인합니다. REAL 시작으로 관측을 재개합니다.", 12f))
        controls.addView(row("REAL 미지원" to { act { setRealAvailability(SignalAvailability.UNSUPPORTED) } },
            "REAL 권한 거부" to { act { setRealAvailability(SignalAvailability.PERMISSION_DENIED) } },
            "REAL 오류" to { act { setRealAvailability(SignalAvailability.ERROR) } }))
        controls.addView(button("REAL 정상 입력") { act { setRealAvailability(SignalAvailability.AVAILABLE) } })
        demoCoordinates = CheckBox(this).apply {
            text = "DEMO 좌표 사용 (35.5, 129.0)"; setTextColor(textColor); isChecked = true
            setOnClickListener { act { setDemoLocation(isChecked) } }
        }
        controls.addView(demoCoordinates)
        controls.addView(label("REAL 좌표 (37.5, 127.0) · REAL/DEMO 다음 회전 500/800m", 12f))
        controls.addView(row("Hub +100ms" to { act { advance(100) } }, "Hub +1초" to { act { advance(1_000) } }, "Hub +10초" to { act { advance(10_000) } }))
        controls.addView(row("Hub 중지" to { act { stopHub() } }, "Hub 재시작" to { act { restartHub() } }, "Hub 시험 초기화" to {
            inputEditors.forEach { (source, editors) ->
                editors.first.setText(if (source == Source.REAL) "1" else "8")
                editors.second.setText(if (source == Source.REAL) "80" else "40")
            }
            act { reset() }
        }))
        val controlsScroll = ScrollView(this).apply { addView(controls); isFillViewport = false }
        val inspection = column()
        sources = label("", 13f)
        fields = label("", 12f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        inspection.addView(sources); inspection.addView(fields)
        val output = ScrollView(this).apply { addView(inspection) }
        body.addView(controlsScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.44f))
        body.addView(output, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.56f))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root); ViewCompat.requestApplyInsets(root)
    }
    override fun onStart() {
        super.onStart()
        subscription = uiScope.launch { model.controller.state.collect(::render) }
    }
    override fun onStop() { subscription?.cancel(); super.onStop() }
    override fun onDestroy() { uiScope.cancel(); super.onDestroy() }
    private fun act(action: suspend DebugHubProbeController.() -> Unit) = model.act(action) {
        Toast.makeText(this, it, Toast.LENGTH_LONG).show()
    }
    private fun rate(value: SourceRate) = value.hz?.let { "%.2fHz".format(it) } ?: "미확정(${value.status})"
    private fun render(snapshot: HubProbeSnapshot) {
        actions.forEach { it.isEnabled = snapshot.ready }
        demoCoordinates.isChecked = snapshot.demoLocation
        val d = snapshot.diagnostics
        val f = snapshot.frame
        val n = snapshot.navi
        fun short(field: SignalField) = f?.qualityOf(field)?.let { "${it.source}/${it.availability}" } ?: "발행 전"
        summary.text = "Hub 가상 ${snapshot.nowMs}ms · ${if (d.running) "동작" else "중지"} · 세션 ${d.sessionId}\n" +
            "발행 ${d.emittedFrames}회 / ${rate(d.publicationRate)} · 누락 deadline ${d.skippedDeadlines} · 시계 오류 ${d.clockFailure}\n" +
            "최종 조향 ${f?.steeringDeg ?: "null"}° ${short(SignalField.STEERING_DEG)} · 차속 ${f?.speedKph ?: "null"}km/h ${short(SignalField.SPEED_KPH)}\n" +
            "최종 좌표 (${n?.lat ?: "null"}, ${n?.lon ?: "null"}) · ${n?.qualityOf(NaviField.LAT)?.source}/${n?.qualityOf(NaviField.LON)?.source} · ${n?.qualityOf(NaviField.LAT)?.selectionReason}"
        sources.text = Source.entries.joinToString("\n") { source ->
            val input = d.inputs[source]; val settings = snapshot.inputs[source]
            val rate = input?.steeringRate
            "가짜 $source · ${input?.collection?.vehicle} · 설정 ${settings?.hz}Hz / 관측 일시정지 ${settings?.paused}\n" +
                "적용 조향 ${settings?.steering}° / 차속 ${settings?.speed}km/h\n" +
                "원천 콜백 ${input?.vehicleCallbacks} · 고유 관측 ${rate?.acceptedObservations} · ${rate?.let(::rate)} / ${rate?.timeBasis}\n" +
                "5Hz 미만 ${rate?.belowMinimum ?: "미확정"} · 조향 입력 ${input?.vehicleQuality?.get(SignalField.STEERING_DEG)?.availability}"
        } + "\n\n최종 값 · 출처 · 품질 · 선택 이유\n"
        fun line(name: String, value: Any?, q: SignalQuality): String {
            val observedAge = q.observedAtMs?.let { snapshot.nowMs - it }
            val receivedAge = q.receivedAtElapsedMs?.let { snapshot.nowMs - it }
            return "$name = ${value ?: "null"} | ${q.source ?: "없음"} ${q.availability} | ${q.selectionReason}\n" +
                "관측 ${q.observedAtMs ?: "없음"}ms/${q.observedTimeBasis} · 수신 ${q.receivedAtElapsedMs ?: "없음"}ms\n" +
                "관측 경과 ${observedAge ?: "불명"}ms · 수신 경과 ${receivedAge ?: "불명"}ms · 원천 번호 ${q.observationSequence ?: "없음"}\n"
        }
        fields.text = buildString {
            val frame = snapshot.frame
            if (frame == null) append("발행 전/중지 · 과거 캐시 없음\n")
            else SignalField.entries.forEach { append(line(it.name, frame.valueOf(it), frame.qualityOf(it))) }
            snapshot.navi?.let { navi -> NaviField.entries.forEach { append(line(it.name, navi.valueOf(it), navi.qualityOf(it))) } }
            snapshot.power?.let { append(line("POWER", it.state, it.quality)) }
        }
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(5), 0, dp(8), 0) }
    private fun label(text: String, size: Float, color: Int = textColor) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); setPadding(0, dp(4), 0, dp(4))
    }
    private fun editor(description: String, initial: String) = EditText(this).apply {
        contentDescription = description; hint = description; setSingleLine(); setText(initial); setTextColor(textColor)
        setHintTextColor(Color.LTGRAY); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
    }
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 12f; setTextColor(textColor)
        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(38, 60, 78))
        setOnClickListener { action() }; actions += this
    }
    private fun row(vararg values: Pair<String, () -> Unit>) = LinearLayout(this).apply {
        values.forEach { (name, action) -> addView(button(name, action), LinearLayout.LayoutParams(0, dp(49), 1f)) }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
