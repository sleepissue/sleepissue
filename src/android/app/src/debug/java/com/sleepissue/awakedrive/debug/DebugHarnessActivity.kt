package com.sleepissue.awakedrive.debug

import android.app.Application
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import com.sleepissue.awakedrive.engine.model.CheckContext
import com.sleepissue.awakedrive.engine.model.CheckOutcome
import com.sleepissue.awakedrive.engine.model.DriveState
import com.sleepissue.awakedrive.engine.model.EvaluationStatus
import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.Gear
import com.sleepissue.awakedrive.engine.model.InterventionStage
import com.sleepissue.awakedrive.engine.model.PowerState
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.model.ResponseIntent
import com.sleepissue.awakedrive.engine.model.TransitionReason
import com.sleepissue.awakedrive.signal.CheckRequest
import com.sleepissue.awakedrive.signal.RestAction
import com.sleepissue.awakedrive.signal.RestRequest
import com.sleepissue.awakedrive.signal.SpeechRequest
import com.sleepissue.awakedrive.signal.*
import com.sleepissue.awakedrive.data.TripDatabase
import com.sleepissue.awakedrive.data.RoomTripRepository
import com.sleepissue.awakedrive.engine.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 화면 재생성은 구독만 교체하고 가상 검증 세션은 그대로 보존한다. */
class DebugHarnessViewModel(application: Application) : AndroidViewModel(application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val database = TripDatabase.open(application, "awakedrive_debug_trip.db")
    private val wallClock = java.time.Clock.systemUTC()
    private val storageClock = object : Clock {
        override fun scenarioTimeMs() = 0L // 운행 논리 시각은 Controller가 소유한다.
        override fun elapsedRealtimeMs() = android.os.SystemClock.elapsedRealtime()
        override fun wallTimeMs() = wallClock.millis()
    }
    val controller = DebugHarnessController(scope, RoomTripRepository(database.checkpointDao()), storageClock)
    var scoreDraft = "0"
    var trendingDraft = false

    init {
        scope.launch {
            controller.initialize()
            // 화면 소유 메모리 작업이다. FGS·부팅 실행·백그라운드 주기 보장은 별도 범위다.
            while (isActive) {
                delay(1_000)
                controller.checkAutoSave()
            }
        }
    }

    override fun onCleared() {
        scope.cancel()
        database.close()
    }
}

/** debug 소스에만 존재하는 수동 조작 화면이다. 서비스용 화면·SDK 실행과 분리한다. */
class DebugHarnessActivity : AppCompatActivity() {
    private val model by lazy { ViewModelProvider(this)[DebugHarnessViewModel::class.java] }
    private val controller get() = model.controller
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectionJob: Job? = null
    private lateinit var timeText: TextView
    private lateinit var driveText: TextView
    private lateinit var stageText: TextView
    private lateinit var observationText: TextView
    private lateinit var storageText: TextView
    private lateinit var resumeChecksButton: Button
    private lateinit var requestsList: LinearLayout
    private lateinit var requestsEmpty: TextView
    private lateinit var eventsText: TextView
    private lateinit var eventsScroll: ScrollView
    private lateinit var scoreInput: EditText
    private lateinit var trendingInput: CheckBox
    private val actionButtons = mutableListOf<Button>()
    private var trackActionButtons = true
    private val requestRows = linkedMapOf<RequestId, RequestRow>()
    private var lastEvents: List<DebugEvent> = emptyList()
    private val backgroundColor = Color.rgb(16, 25, 35)
    private val panelColor = Color.rgb(25, 39, 53)
    private val textColor = Color.rgb(236, 242, 247)
    private val mutedColor = Color.rgb(167, 184, 199)
    private val accentColor = Color.rgb(89, 216, 208)
    private val amberColor = Color.rgb(255, 198, 98)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = column().apply {
            setBackgroundColor(backgroundColor)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16) + bars.left, dp(12) + bars.top, dp(16) + bars.right, dp(12) + bars.bottom)
            insets
        }
        root.addView(label("DEBUG ONLY  ·  개발 검증", 12f, amberColor).apply {
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(label("AwakeDrive 수동 검증", 24f).apply {
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(label("가상 입력 전용 · Room 저장·복원 · 음성·내비·차량 SDK 실행 없음", 12f, mutedColor))
        root.addView(button("Hub 가짜 REAL/DEMO 검증") {
            startActivity(Intent(this, DebugHubProbeActivity::class.java))
        }, fullWidth())
        val summary = column().apply {
            background = rounded(panelColor)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        timeText = label("가상 시간 준비 중", 16f, accentColor)
        driveText = label("", 15f)
        stageText = label("", 16f, amberColor)
        observationText = label("", 12f, mutedColor)
        storageText = label("저장소 준비 중", 12f, mutedColor)
        listOf(timeText, driveText, stageText, observationText, storageText).forEach(summary::addView)
        root.addView(summary, fullWidth().apply { topMargin = dp(10); bottomMargin = dp(10) })

        val wide = resources.configuration.screenWidthDp >= 760
        val body = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val controlsScroll = ScrollView(this).apply { isFillViewport = false }
        val controls = column().apply { setPadding(0, 0, dp(if (wide) 12 else 0), dp(8)) }
        buildControls(controls)
        trackActionButtons = false
        controlsScroll.addView(controls)
        val inspection = column()
        buildInspection(inspection)
        if (wide) {
            body.addView(controlsScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.44f))
            body.addView(inspection, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.56f))
        } else {
            body.addView(controlsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.5f))
            body.addView(inspection, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.5f))
        }
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        render(controller.state.value)
    }

    override fun onStart() {
        super.onStart()
        collectionJob = uiScope.launch { controller.state.collect(::render) }
    }

    override fun onStop() {
        model.scoreDraft = scoreInput.text.toString()
        model.trendingDraft = trendingInput.isChecked
        collectionJob?.cancel()
        collectionJob = null
        super.onStop()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun buildControls(parent: LinearLayout) {
        parent.addView(section("입력 · 가상 차량"))
        parent.addView(buttonRow(
            "D 기어" to { act { setGear(Gear.D) } },
            "P 기어" to { act { setGear(Gear.P) } },
        ))
        parent.addView(buttonRow(
            "시동 ON" to { act { setPower(PowerState.ON) } },
            "시동 OFF" to { act { setPower(PowerState.OFF) } },
        ))
        parent.addView(section("가상 시간 진행"))
        parent.addView(buttonRow(
            "+30초" to { act { advanceTime(30_000) } },
            "+1분" to { act { advanceTime(60_000) } },
        ))
        parent.addView(buttonRow(
            "+5분" to { act { advanceTime(300_000) } },
            "+15분" to { act { advanceTime(900_000) } },
        ))
        parent.addView(label("실제 시간은 자동 진행하지 않습니다. 중간 단계·재확인도 기록합니다.", 12f, mutedColor))
        parent.addView(section("Room 저장·복원"))
        parent.addView(buttonRow(
            "즉시 저장" to { act { saveNow() } },
            "저장 데이터 삭제" to { act { deleteSavedData() } },
        ))
        parent.addView(buttonRow(
            "가상 10초 자동 저장" to { act { setSaveTimerBasis(SaveTimerBasis.SCENARIO) } },
            "실제 10초 자동 저장" to { act { setSaveTimerBasis(SaveTimerBasis.ELAPSED_REALTIME) } },
        ))
        resumeChecksButton = button("중단된 확인 다시 요청") { act { resumeInterruptedChecks() } }
        parent.addView(resumeChecksButton, fullWidth())
        parent.addView(label("DB 삭제는 메모리를 유지하고 자동 저장을 중지합니다. 중단된 음성·경유지는 자동 재실행하지 않습니다.", 12f, mutedColor))
        parent.addView(section("위험 판단 결과 주입"))
        scoreInput = EditText(this).apply {
            hint = "위험 점수 0~100"
            contentDescription = "위험 점수 0부터 100"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            setTextColor(textColor)
            setHintTextColor(mutedColor)
            textSize = 16f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = rounded(panelColor)
            setText(model.scoreDraft)
        }
        parent.addView(scoreInput, fullWidth())
        trendingInput = CheckBox(this).apply {
            text = "추세형 (졸음 쪽)"
            setTextColor(textColor)
            buttonTintList = ColorStateList.valueOf(accentColor)
            isChecked = model.trendingDraft
        }
        parent.addView(trendingInput)
        parent.addView(button("위험 점수·추세 적용") {
            val score = scoreInput.text.toString().toFloatOrNull()
            if (score == null || !score.isFinite() || score !in 0f..100f) {
                scoreInput.error = "0~100 사이의 숫자를 입력해 주세요."
            } else {
                scoreInput.error = null
                model.scoreDraft = scoreInput.text.toString()
                model.trendingDraft = trendingInput.isChecked
                act { applyAssessment(score, model.trendingDraft) }
            }
        }, fullWidth())
        parent.addView(section("휴식·회복 결과 주입"))
        parent.addView(button("휴식 인증 결과 주입") { act { certifyRest() } }, fullWidth())
        parent.addView(label("위치·15분 조건의 자동 인증을 대신하는 시험 입력입니다. 인증 뒤 오른쪽 회복 확인에 ‘정상 회복’을 주입하세요.", 12f, mutedColor))
        parent.addView(section("검증 세션"))
        parent.addView(button("전체 테스트 상태 초기화", true) {
            scoreInput.setText("0")
            scoreInput.error = null
            trendingInput.isChecked = false
            model.scoreDraft = "0"
            model.trendingDraft = false
            act { reset() }
        }, fullWidth())
        parent.addView(label("전체 초기화는 메모리만 지우고 DB는 보존하며 자동 저장을 멈춥니다. 즉시 저장·자동 저장 재개는 새 세션으로 DB를 갱신합니다.", 12f, mutedColor))
        parent.addView(label("[가정] 정차 5분 미만 잠정 포함·5분부터 소급 제외·15분 연속 초기화. 최근 24시간은 정차 제외.", 12f, mutedColor).apply {
            setPadding(0, dp(12), 0, dp(12))
        })
    }

    private fun buildInspection(parent: LinearLayout) {
        parent.addView(section("실행 요청 · 가상 처리"))
        parent.addView(label("각 요청의 버튼으로 결과를 직접 넣습니다. 실제 장치 완료를 뜻하지 않습니다.", 12f, mutedColor))
        val requestScroll = ScrollView(this)
        requestsList = column()
        requestsEmpty = label("아직 발생한 요청이 없습니다.", 13f, mutedColor)
        requestsList.addView(requestsEmpty)
        requestScroll.addView(requestsList)
        parent.addView(requestScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.56f))
        parent.addView(section("시간순 이벤트 기록"))
        eventsText = label("", 12f, mutedColor).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        eventsScroll = ScrollView(this).apply {
            background = rounded(panelColor)
            addView(eventsText)
        }
        parent.addView(eventsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.44f))
    }

    private fun render(snapshot: DebugSnapshot) {
        timeText.text = "가상 +${formatTime(snapshot.timeMs / 1_000)}  ·  ${snapshot.gear.name}  ·  시동 ${snapshot.power.name}"
        val trip = snapshot.trip
        driveText.text = "연속 ${formatTime(trip.continuousDriveSec)}  |  최근 24시간 ${formatTime(trip.last24hDriveSec)}"
        stageText.text = "${snapshot.intervention.stage.level}단계 ${stageName(snapshot.intervention.stage)}  ·  ${reasonName(snapshot.intervention.reason)}"
        observationText.text = "운행 ${trip.tripId ?: "시작 전"} · ${driveName(trip.driveState)}\n" +
            "시작 ${trip.runStartMs?.let { "+${formatTime(it / 1_000)}" } ?: "—"} · 정차 ${trip.stopStartMs?.let { "+${formatTime(it / 1_000)}" } ?: "—"} · 판단 ${evaluationName(snapshot.intervention.evaluationStatus)}\n" +
            "정차 전 연속 ${trip.continuousDriveSecBeforeStop?.let(::formatTime) ?: "—"} · 잠정 정차 ${formatTime(trip.provisionalStopSec)}\n" +
            "적용된 점수 ${snapshot.assessmentScore?.let { "${it}점" } ?: "미평가"} · 추세형 ${if (snapshot.assessmentScore == null) "미평가" else if (snapshot.trending) "예" else "아니요"}"
        actionButtons.forEach { it.isEnabled = snapshot.ready }
        resumeChecksButton.isEnabled = snapshot.ready && snapshot.canResumeChecks
        resumeChecksButton.text = snapshot.interruptedCheckDueMs?.takeIf { it > snapshot.timeMs }?.let {
            "중단된 확인 대기 · 가상 +${formatTime(it / 1_000)}부터 재요청"
        } ?: "중단된 확인 다시 요청"
        val storage = snapshot.persistence
        val savedWall = storage.lastSavedWallMs?.let {
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))
        } ?: "없음"
        val savedScenario = storage.lastSavedScenarioMs?.let { "+${formatTime(it / 1_000)}" } ?: "없음"
        storageText.text = "Room ${storageOperationName(storage.operation)} · 복원 ${restoreName(storage.restore)}" +
            (storage.failure?.let { " ($it)" } ?: "") +
            "\n마지막 저장 가상 $savedScenario · 기기 시각 $savedWall · 성공 ${storage.successfulSaves}회" +
            "\n자동 저장 ${if (storage.autoSaveEnabled) "켜짐" else "중지"} · ${if (storage.timerBasis == SaveTimerBasis.SCENARIO) "가상" else "실제 경과"} 10초" +
            if (snapshot.restoredManualInput) " · 저장한 수동 입력 재개(실제 신호 최신성 아님)" else " · 수동 입력"
        renderRequests(snapshot.requests)
        if (snapshot.events != lastEvents) {
            val followEnd = eventsScroll.getChildAt(0)?.let {
                it.height - eventsScroll.height - eventsScroll.scrollY < dp(48)
            } ?: true
            eventsText.text = snapshot.events.joinToString("\n") {
                "#${it.sequence} +${formatTime(it.timeMs / 1_000)}  ${it.message}"
            }
            lastEvents = snapshot.events
            if (followEnd) eventsScroll.post { eventsScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun renderRequests(requests: List<DebugRequest>) {
        val ids = requests.map { it.request.requestId }.toSet()
        requestRows.keys.filter { it !in ids }.forEach { id ->
            requestRows.remove(id)?.let { requestsList.removeView(it.container) }
        }
        requestsEmpty.visibility = if (requests.isEmpty()) View.VISIBLE else View.GONE
        requests.forEach { request ->
            val row = requestRows.getOrPut(request.request.requestId) {
                val card = column().apply {
                    background = rounded(panelColor)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                }
                val title = label("", 14f)
                val detail = label("", 12f, mutedColor)
                val actions = column()
                card.addView(title)
                card.addView(detail)
                card.addView(actions)
                requestsList.addView(card, fullWidth().apply { bottomMargin = dp(6) })
                RequestRow(card, title, detail, actions)
            }
            if (row.previous != request) {
                row.title.text = "${request.label} · ${statusName(request.status)}"
                row.detail.text = "${request.request.requestId.value}\n${request.detail}"
                row.actions.removeAllViews()
                if (request.status == ExecutionStatus.STARTED) addRequestActions(row.actions, request)
                row.previous = request
            }
        }
    }

    private fun addRequestActions(parent: LinearLayout, record: DebugRequest) {
        val request = record.request
        val id = request.requestId
        when (request) {
            is CheckRequest -> when (request.context) {
                CheckContext.WAYPOINT_CONSENT -> {
                    parent.addView(buttonRow(
                        "동의" to { act { completeCheck(id, CheckOutcome.NORMAL, ResponseIntent.YES) } },
                        "거절" to { act { completeCheck(id, CheckOutcome.NORMAL, ResponseIntent.NO) } },
                        "무응답" to { act { completeCheck(id, CheckOutcome.NO_RESPONSE) } },
                    ))
                }
                else -> parent.addView(buttonRow(
                    (if (request.context == CheckContext.RECOVERY) "정상 회복" else "정상") to {
                        act { completeCheck(id, CheckOutcome.NORMAL, if (request.context == CheckContext.RECOVERY) ResponseIntent.REFRESHED else ResponseIntent.OK) }
                    },
                    "느림" to { act { completeCheck(id, CheckOutcome.SLOW) } },
                    "무응답" to { act { completeCheck(id, CheckOutcome.NO_RESPONSE) } },
                ))
            }
            is RestRequest -> if (request.action == RestAction.FIND_AHEAD) {
                parent.addView(buttonRow(
                    "후보 있음" to { act { resolveRest(id, true) } },
                    "후보 없음" to { act { resolveRest(id, false) } },
                    "실패" to { act { completeExecution(id, false) } },
                ))
            } else {
                parent.addView(buttonRow(
                    "가상 완료" to { act { completeExecution(id, true) } },
                    "실패" to { act { completeExecution(id, false) } },
                ))
            }
            is SpeechRequest -> parent.addView(buttonRow(
                "가상 완료" to { act { completeExecution(id, true) } },
                "실패" to { act { completeExecution(id, false) } },
            ))
        }
    }

    private fun act(action: suspend DebugHarnessController.() -> Unit) {
        model.scope.launch {
            try {
                controller.action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Toast.makeText(this@DebugHarnessActivity, error.message ?: "검증 입력을 처리하지 못했습니다.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun label(value: String, size: Float = 14f, color: Int = textColor) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun section(value: String) = label(value, 15f, accentColor).apply {
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(10), 0, dp(6))
    }

    private fun button(value: String, destructive: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = value
        textSize = 13f
        isAllCaps = false
        minHeight = dp(46)
        minimumWidth = 0
        setPadding(dp(6), dp(4), dp(6), dp(4))
        setTextColor(if (destructive) amberColor else textColor)
        backgroundTintList = ColorStateList.valueOf(if (destructive) Color.rgb(69, 46, 33) else Color.rgb(38, 60, 78))
        setOnClickListener { action() }
        if (trackActionButtons) actionButtons += this
    }

    private fun buttonRow(vararg entries: Pair<String, () -> Unit>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        entries.forEachIndexed { index, (title, action) ->
            addView(button(title, action = action), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) leftMargin = dp(6)
            })
        }
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(8).toFloat()
    }

    private fun fullWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun formatTime(seconds: Long) = "%02d:%02d:%02d".format(seconds / 3_600, seconds / 60 % 60, seconds % 60)
    private fun statusName(status: ExecutionStatus) = when (status) {
        ExecutionStatus.NOT_CONNECTED -> "미연결"
        ExecutionStatus.REQUESTED -> "요청됨"
        ExecutionStatus.STARTED -> "가상 진행 중"
        ExecutionStatus.DEFERRED -> "보류"
        ExecutionStatus.SUCCEEDED -> "가상 완료"
        ExecutionStatus.FAILED -> "실패"
        ExecutionStatus.CANCELLED -> "취소됨"
    }
    private fun stageName(stage: InterventionStage) = when (stage) {
        InterventionStage.NORMAL -> "평상"
        InterventionStage.CAUTION -> "주의"
        InterventionStage.CHECK -> "확인"
        InterventionStage.REST_GUIDANCE -> "휴식 유도"
    }
    private fun reasonName(reason: TransitionReason?) = when (reason) {
        TransitionReason.SCORE -> "위험 점수"
        TransitionReason.TIME -> "연속 운전 시간"
        TransitionReason.FORECAST -> "예보·취약 시간대"
        TransitionReason.CHECK_RESULT -> "확인 결과"
        TransitionReason.REST_COMPLETED -> "휴식·회복 완료"
        TransitionReason.SAFETY_EVENT -> "안전 이벤트·확인 요청"
        TransitionReason.USER_REQUEST -> "사용자 요청"
        null -> "전환 없음"
    }
    private fun driveName(state: DriveState) = when (state) {
        DriveState.DRIVING -> "운전 중"
        DriveState.PAUSED -> "정차 중"
        DriveState.UNKNOWN -> "관측 대기"
    }
    private fun evaluationName(status: EvaluationStatus) = when (status) {
        EvaluationStatus.EVALUATED -> "주입된 판단"
        EvaluationStatus.NOT_EVALUATED -> "미평가"
        EvaluationStatus.UNAVAILABLE -> "계산 불가"
    }

    private fun restoreName(value: TripRestoreStatus) = when (value) {
        TripRestoreStatus.NOT_REQUESTED -> "안 함"
        TripRestoreStatus.LOADING -> "읽는 중"
        TripRestoreStatus.EMPTY -> "빈 DB"
        TripRestoreStatus.RESTORED -> "성공"
        TripRestoreStatus.FAILED -> "실패"
    }
    private fun storageOperationName(value: TripStorageOperation) = when (value) {
        TripStorageOperation.NOT_CONNECTED -> "미연결"
        TripStorageOperation.IDLE -> "준비"
        TripStorageOperation.SAVING -> "저장 중"
        TripStorageOperation.SAVED -> "저장 성공"
        TripStorageOperation.SAVE_FAILED -> "저장 실패"
        TripStorageOperation.DELETED -> "삭제 완료"
        TripStorageOperation.DELETE_FAILED -> "삭제 실패"
    }

    private data class RequestRow(
        val container: LinearLayout,
        val title: TextView,
        val detail: TextView,
        val actions: LinearLayout,
        var previous: DebugRequest? = null,
    )
}
