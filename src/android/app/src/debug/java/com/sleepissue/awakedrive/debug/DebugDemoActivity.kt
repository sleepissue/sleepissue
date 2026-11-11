package com.sleepissue.awakedrive.debug

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.sleepissue.awakedrive.ui.DriveUiAction
import com.sleepissue.awakedrive.ui.DriverUiSettingsStore
import com.sleepissue.awakedrive.voice.*
import com.sleepissue.awakedrive.engine.check.CheckPolicy
import com.sleepissue.awakedrive.engine.check.QuestionType
import com.sleepissue.awakedrive.engine.check.CheckVerdict
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.rest.NapState
import com.sleepissue.awakedrive.engine.voice.SpeechIntentParser
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.sleepissue.awakedrive.demo.DemoPhase
import com.sleepissue.awakedrive.demo.DemoTransportMode
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.performance.WorkProbe
import com.sleepissue.awakedrive.performance.WorkStage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** debug에서만 열리는 JSON·배속 검증 화면이다. 실제 마이크·TTS·SDK는 실행하지 않는다. */
class DebugDemoActivity : AppCompatActivity() {
    private var requestedDemoSpeed: Int? = null
    private var intentScenarioConsumed = false
    private var recoveryInFlight = false
    private var recognizer: PleosSpeechRecognizer? = null
    private var recoveryTone: SignalTonePlayer? = null
    private var recoveryCoordinator: VoiceCheckCoordinator? = null
    private var napAlarm: com.sleepissue.awakedrive.session.ToneNapAlarm? = null
    private var wasAlarming = false

    private val probe by lazy {
        if (intent.getStringExtra("demoDetailedTiming") == "off") WorkProbe.NONE else WorkProbe(System::nanoTime)
    }
    private val realClock = object : Clock {
        override fun scenarioTimeMs() = SystemClock.elapsedRealtime()
        override fun elapsedRealtimeMs() = SystemClock.elapsedRealtime()
        override fun wallTimeMs() = java.time.Clock.systemUTC().millis()
    }
    private var workerDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private var executionWorker: DebugDemoWorker? = null
    private var sessionScope: CoroutineScope? = null
    private var controller: DebugDemoController? = null
    private lateinit var summary: TextView
    private lateinit var question: TextView
    private lateinit var finalValues: TextView
    private lateinit var message: TextView
    private lateinit var traceStatus: TextView
    private lateinit var traceSaveButton: Button
    private lateinit var jsonInput: EditText
    private var displayFrozen = false
    private var latestSnapshot: DebugDemoSnapshot? = null
    private var lastRenderedSnapshot: DebugDemoSnapshot? = null
    private var traceStatusText = "trace 저장 대기 · 재생 종료 시 상세 기록 자동 저장"
    private var traceSaveJob: Job? = null
    private var archiveJob: Job? = null
    private var lastArchivedTrace: DebugDemoTrace.CapturedTrace? = null
    private val traceFileLock = Mutex()
    private var allocationSession: Long? = null
    private var allocationStart: Long? = null
    private var replayCpuStartMs: Long? = null
    private var replayStageStart: Map<String, Map<String, Number>> = emptyMap()
    private val runtimeMetadata = mutableMapOf<Long, Map<String, Any?>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedDemoSpeed = intent.getIntExtra("demoSpeed", 0).takeIf { it in setOf(1, 10, 60) }
        traceStatusText = savedInstanceState?.getString("traceStatus") ?: traceStatusText
        val root = column().apply { setBackgroundColor(Color.rgb(16, 25, 35)) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16) + bars.left, dp(8) + bars.top, dp(16) + bars.right, dp(8) + bars.bottom)
            insets
        }
        root.addView(label("DEBUG ONLY · JSON 데모 검증", 23f))
        root.addView(label("독립 메모리 세션 · 기존 운행/Room·Hub·SDK 화면 보존 · 가짜 질문/REAL은 실제 음성/SDK가 아님", 12f))
        root.addView(row(
            "검증 예제 로드" to { loadValidationExample() },
            "졸음 시나리오 로드" to { loadValidationExample("scenarios/drowsy.json") },
            "AwakeDrive UI 보기" to { showAwakeDriveUi() },
            "시작" to { startReplay() },
            "일시정지" to { act { pause() } },
            "재개" to { act { resume() } },
            "중지" to { act { stop() } },
            "초기화" to { act { reset() } },
        ))
        root.addView(row(
            "정상" to { loadValidationExample("scenarios/normal.json") },
            "딴짓" to { loadValidationExample("scenarios/distraction.json") },
            "졸음" to { loadValidationExample("scenarios/drowsy.json") },
            "대화" to { loadValidationExample("scenarios/talk.json") },
            "보조 주행" to { loadValidationExample("scenarios/assist.json") },
            "출발 피로" to { loadValidationExample("scenarios/departure.json") },
        ))
        root.addView(row(
            "파일 배속" to { requestedDemoSpeed = null; message.text = "다음 로드부터 파일 배속을 사용합니다" },
            "1배로 로드" to { requestedDemoSpeed = 1; message.text = "다음 로드부터 1배 · 검증용 배속 변경" },
            "10배로 로드" to { requestedDemoSpeed = 10; message.text = "다음 로드부터 10배 · 검증용 배속 변경" },
            "60배로 로드" to { requestedDemoSpeed = 60; message.text = "다음 로드부터 60배 · 검증용 배속 변경" },
        ))
        root.addView(LinearLayout(this).apply {
            val comparisonAsset = intent.getStringExtra("demoScenarioAsset")?.takeIf {
                it in setOf("demo_timeaxis_60s_1.json", "demo_timeaxis_60s_10.json", "demo_timeaxis_60s_60.json")
            }
            addView(button(if (comparisonAsset == null) "60배 장기 예제 로드" else "동일 60초 비교 구간 로드") {
                loadValidationExample(comparisonAsset ?: "demo_perf_validation.json")
            },
                LinearLayout.LayoutParams(0, -2, 1f))
            traceSaveButton = button("성능 trace 저장") { saveTrace() }
            addView(traceSaveButton, LinearLayout.LayoutParams(0, -2, 1f))
        })
        traceStatus = label(traceStatusText, 12f).apply { setTextIsSelectable(true) }
        root.addView(traceStatus)
        root.addView(row(
            "가짜 REAL 켜기" to { act { setFakeReal(true) } },
            "가짜 REAL 끄기" to { act { setFakeReal(false) } },
            "Hub 표시 고정/재개" to {
                displayFrozen = !displayFrozen
                if (!displayFrozen) { lastRenderedSnapshot = null; latestSnapshot?.let(::render) }
            },
            "운행·Room으로 돌아가기" to { finish() },
        ))
        summary = label("JSON 미로드 · 재생 안 함", 16f)
        root.addView(summary)
        root.addView(label("예제: 상대0~3초=1배 / 3~63초=10배 / 63~363초=60배 · JSON 차속40km/h·좌표35.5,129.0\n[가정] TTL1초·REAL 상실 유지0 · 위치는 DEMO 쌍 우선 · 이 화면은 저장하지 않음", 12f))
        root.addView(row(
            "① 가짜 안내 시작" to { act { beginQuestion() } },
            "② 신호음 끝" to { act { markBeepEnded() } },
            "③ 목소리 시작" to { act { markVoiceStarted() } },
            "④ 판정 종료" to { act { finishQuestion() } },
        ))
        root.addView(row(
            "질문 실패" to { act { failQuestion() } },
            "질문 취소" to { act(WorkStage.UI_CANCEL_DISPATCH) { cancelQuestion() } },
            "중복 안내 시작" to { act { repeatQuestionStart() } },
            "이전 판정 재전송" to { act { repeatPreviousQuestionResult() } },
        ))
        question = label("가짜 질문 IDLE · 실제 음성 연결 미완료", 14f)
        root.addView(question)
        message = label("검증 예제를 로드해 주세요.", 13f)
        root.addView(message)
        val columns = LinearLayout(this)
        val valuesColumn = column()
        valuesColumn.addView(label("Hub 최종 값 · 출처·선택 이유", 16f))
        finalValues = label("Hub 입력 전", 13f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        valuesColumn.addView(finalValues)
        val jsonColumn = column()
        jsonColumn.addView(label("JSON 직접 입력 · 오류 구별 확인", 16f))
        jsonColumn.addView(label("여기에 시나리오 JSON을 붙여 넣고 로드하세요. 검증 예제 로드는 왼쪽 버튼으로 별도 수행합니다.", 12f))
        jsonInput = EditText(this).apply {
            setText(minimalEditorExample())
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(231, 241, 248))
            setHintTextColor(Color.LTGRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 8
            maxLines = 14
            setHorizontallyScrolling(false)
        }
        jsonColumn.addView(jsonInput, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        jsonColumn.addView(button("입력 JSON 로드") { val value = jsonInput.text.toString(); act { loadJson(value) } })
        listOf(valuesColumn, jsonColumn).forEach { col ->
            columns.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(0, -1, 1f))
        }
        root.addView(columns, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    override fun onStart() {
        super.onStart()
        if (sessionScope != null) return
        displayFrozen = false
        lastRenderedSnapshot = null
        runtimeMetadata.clear()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val backend = DebugDemoWorkerBackend.entries.firstOrNull { it.name == intent.getStringExtra("demoWorker") }
            ?: DebugDemoWorkerBackend.DEFAULT_LANE
        val worker = DebugDemoWorker.create(backend)
        executionWorker = worker
        workerDispatcher = worker.dispatcher
        val dispatcher = worker.dispatcher
        // 같은 부모 Job으로 화면 종료를 공유하고, 재생·수신은 화면 그리기와 다른 실행 통로를 쓴다.
        val workerScope = CoroutineScope(scope.coroutineContext + dispatcher)
        val transport = DemoTransportMode.entries.firstOrNull { it.name == intent.getStringExtra("demoTransport") }
            ?: DemoTransportMode.COMPLETE_OBSERVATION
        val session = DebugDemoController(workerScope, realClock, probe = probe, transportMode = transport, workerBackend = backend.name,
            restAreas = com.sleepissue.awakedrive.navi.RestAreaRepository(this).areas)
        sessionScope = scope
        controller = session
        scope.launch {
            session.completedTraces.collect { capture -> archiveTerminalTrace(capture, scope) }
        }
        scope.launch {
            withContext(dispatcher) { session.initialize() }
            val requested = intent.getStringExtra("demoScenarioName")?.takeIf {
                it in setOf("normal", "distraction", "drowsy", "talk", "assist", "departure")
            }
            if (requested != null && !intentScenarioConsumed) {
                intentScenarioConsumed = true
                val json = withContext(Dispatchers.IO) { readScenarioJson("scenarios/$requested.json") }
                withContext(dispatcher) {
                    session.loadJson(json)
                    if (intent.getBooleanExtra("demoAutoStart", false)) session.start()
                }
                if (intent.getBooleanExtra("demoShowUi", false)) showAwakeDriveUi()
            }
            while (isActive && controller === session) {
                val snapshot = session.state.value
                latestSnapshot = snapshot
                render(snapshot)
                val ui = session.presentationState.value
                val alarming = ui.nap is NapState.Alarming && snapshot.power?.state != PowerState.OFF
                if (alarming != wasAlarming) {
                    wasAlarming = alarming
                    if (alarming) {
                        val alarm = napAlarm ?: com.sleepissue.awakedrive.session.ToneNapAlarm(scope).also { napAlarm = it }
                        alarm.start(EngineConfig().napAlarmRampMs, EngineConfig().napAlarmMaxMs)
                    } else napAlarm?.stop()
                }
                if (recoveryInFlight && !ui.parked) {
                    recoveryCoordinator?.cancel()
                    recoveryInFlight = false
                    withContext(dispatcher) { session.endRecoveryVoice(CheckOutcome.CANCELLED) }
                }
                delay(100)
            }
        }
    }

    override fun onStop() {
        recoveryCoordinator?.cancel()
        recognizer?.close(); recognizer = null
        recoveryTone?.close(); recoveryTone = null
        recoveryCoordinator = null
        recoveryInFlight = false
        napAlarm?.stop(); napAlarm = null; wasAlarming = false
        val session = controller
        val scope = sessionScope
        val closingWorker = executionWorker
        val closingDispatcher = workerDispatcher
        executionWorker = null
        controller = null
        sessionScope = null
        if (scope != null && session != null) scope.launch {
            try {
                withContext(closingDispatcher) { session.close() }
                archiveTerminalTrace(session.state.value.terminalTrace, scope)
                archiveJob?.join()
                traceSaveJob?.join()
            } finally {
                val cleanupJob = currentCoroutineContext()[Job]
                val ownedJobs = scope.coroutineContext[Job]?.children?.filter { it !== cleanupJob }?.toList().orEmpty()
                scope.cancel()
                withContext(NonCancellable) {
                    ownedJobs.joinAll()
                    closingWorker?.close()
                    closingWorker?.awaitTermination()
                }
            }
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("traceStatus", traceStatusText)
        super.onSaveInstanceState(outState)
    }


    /**
     * 디버그 통합 리허설 전용 화면.
     * Activity를 전환하지 않으므로 기존 데모 세션을 유지한다.
     */
    private fun showAwakeDriveUi() {
        val session = controller ?: run {
            android.widget.Toast.makeText(
                this,
                "데모 세션이 아직 준비되지 않았습니다.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        val dialog = androidx.activity.ComponentDialog(
            this,
            android.R.style.Theme_Material_Light_NoActionBar
        )

        val composeView =
            androidx.compose.ui.platform.ComposeView(this).apply {
                setContent {
                    DebugFatigueNavigation(state = session.presentationState, onAction = { action ->
                        sessionScope?.launch {
                            withContext(workerDispatcher) { session.uiAction(action) }
                            if (action == DriveUiAction.RequestRecovery) startRecoveryVoice(session)
                        }
                    })
                }
            }

        dialog.setContentView(composeView)
        dialog.show()

        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private fun startRecoveryVoice(session: DebugDemoController) {
        if (recoveryInFlight || !session.presentationState.value.parked) return
        val settings = DriverUiSettingsStore(this).read()
        if (!settings.microphoneConsent || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sessionScope?.launch(workerDispatcher) { session.endRecoveryVoice(CheckOutcome.UNAVAILABLE) }
            Toast.makeText(this, "정차 중 설정에서 마이크 권한·사용 동의를 확인하세요", Toast.LENGTH_LONG).show()
            return
        }
        val config = EngineConfig()
        val voice = recognizer ?: PleosSpeechRecognizer(this).also { recognizer = it }
        val tone = recoveryTone ?: SignalTonePlayer().also { recoveryTone = it }
        val coordinator = recoveryCoordinator ?: VoiceCheckCoordinator(
            CheckManager(AndroidElapsedClock(), CheckPolicy(config), SpeechIntentParser()), CheckPolicy(config), voice, tone
        ).also { recoveryCoordinator = it }
        recoveryInFlight = true
        sessionScope?.launch {
            withContext(workerDispatcher) { session.beginRecoveryVoice() }
            Toast.makeText(this@DebugDemoActivity, "신호음 뒤 '네'라고 말해 주세요", Toast.LENGTH_LONG).show()
            coordinator.start(config.defaultVoiceBaselineMs, QuestionType.DRIVER_CHECK,
                onResult = { result, twoSlow ->
                    val outcome = when (result.verdict) {
                        CheckVerdict.NORMAL -> CheckOutcome.NORMAL
                        CheckVerdict.SLOW -> CheckOutcome.SLOW
                        CheckVerdict.NO_RESPONSE -> CheckOutcome.NO_RESPONSE
                    }
                    // 재확인은 coordinator가 담당한다. 느림 1회 때 1배 고정을 유지한다.
                    if (outcome != CheckOutcome.SLOW || twoSlow) recoveryInFlight = false
                    sessionScope?.launch(workerDispatcher) {
                        if (recoveryInFlight) session.recoveryResult(outcome) else session.endRecoveryVoice(outcome)
                    }
                }, onSttFallbackRequired = {
                    // STT 미지원이어도 PCM 대답 시간 측정이 가능하면 판정까지 기다린다.
                    Toast.makeText(this@DebugDemoActivity, "대답 내용 인식 불가 · 발화 시작 시간으로 확인합니다", Toast.LENGTH_SHORT).show()
                })
        }
    }

    private fun startReplay() {
        allocationSession = controller?.state?.value?.playback?.sessionId
        allocationStart = processAllocatedBytes()
        replayStageStart = probe.snapshot()
        replayCpuStartMs = android.os.Process.getElapsedCpuTime()
        act { start() }
    }

    private fun readScenarioJson(assetName: String): String {
        val json = assets.open(assetName).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val speed = requestedDemoSpeed ?: return json
        val root = com.google.gson.JsonParser.parseString(json).asJsonObject
        val samples = root.getAsJsonArray("samples")
        val duration = samples.last().asJsonObject.get("t").asBigDecimal.add(java.math.BigDecimal("0.1"))
        val segments = com.google.gson.JsonArray()
        segments.add(com.google.gson.JsonObject().apply {
            addProperty("fromSec", 0); addProperty("toSec", duration); addProperty("factor", speed)
        })
        root.add("speedSegments", segments)
        return root.toString()
    }

    private fun loadValidationExample(assetName: String = "demo_validation.json") {
        val scope = sessionScope ?: return
        val session = controller ?: return
        val dispatcher = workerDispatcher
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    readScenarioJson(assetName)
                }
                withContext(dispatcher) { session.loadJson(json) }
                android.util.Log.i(
                    "AwakeDriveLoad",
                    "로드 완료: ${session.state.value.playback.phase}, ${session.state.value.message}"
                )
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                message.text = "JSON 로딩 오류: ${e.javaClass.simpleName}: ${e.message}"
                android.util.Log.e("AwakeDriveDemo", "JSON 로딩 실패", e)
            }
        }
    }

    private fun saveTrace() {
        val scope = sessionScope ?: return
        val session = controller ?: return
        val dispatcher = workerDispatcher
        if (traceSaveJob?.isActive == true) return
        traceSaveButton.isEnabled = false
        setTraceStatus("trace 저장 중 · 스냅샷 확보")
        traceSaveJob = scope.launch {
            try {
                val capture = withContext(dispatcher) { session.captureTrace() }
                val metadata = metadataFor(capture)
                setTraceStatus("trace 저장 중 · 세션${capture.sessionId} 파일 기록")
                traceFileLock.withLock {
                    withContext(Dispatchers.IO) { DebugDemoTrace.write(File(cacheDir, "demo-perf-trace.jsonl"), capture, metadata) }
                }
                setTraceStatus("trace 저장 완료 · cache/demo-perf-trace.jsonl · 세션${capture.sessionId}")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { setTraceStatus("trace 저장 실패 · ${error.javaClass.simpleName} · 다시 저장 가능") }
            finally { traceSaveButton.isEnabled = true }
        }
    }

    private fun archiveTerminalTrace(capture: DebugDemoTrace.CapturedTrace?, scope: CoroutineScope) {
        if (capture == null) return
        if (capture === lastArchivedTrace) return
        lastArchivedTrace = capture
        val metadata = metadataFor(capture, terminal = true)
        val filename = "demo-perf-session-${capture.sessionId}-${capture.capturedElapsedMs}.jsonl"
        val previous = archiveJob
        archiveJob = scope.launch {
            previous?.join()
            setTraceStatus("종료 trace 자동 저장 중 · 세션${capture.sessionId}")
            try {
                traceFileLock.withLock {
                    withContext(Dispatchers.IO) { DebugDemoTrace.write(File(cacheDir, filename), capture, metadata) }
                }
                setTraceStatus("종료 trace 자동 저장 완료 · cache/$filename · 수동 저장 버튼도 사용 가능")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { setTraceStatus("종료 trace 자동 저장 실패 · ${error.javaClass.simpleName} · 성능 trace 저장으로 재시도") }
        }
    }

    private fun metadataFor(capture: DebugDemoTrace.CapturedTrace, terminal: Boolean = false): Map<String, Any?> {
        runtimeMetadata[capture.sessionId]?.let { return it }
        val after = processAllocatedBytes()
        val metadata = linkedMapOf<String, Any?>("allocationScope" to "ANDROID_PROCESS_START_BUTTON_TO_CAPTURE_INCLUDES_UI_AND_PROBES",
            "detailedTimingEnabled" to probe.enabled,
            "processCpuMs" to if (allocationSession == capture.sessionId && replayCpuStartMs != null)
                android.os.Process.getElapsedCpuTime() - replayCpuStartMs!! else null,
            "processAllocatedBytes" to if (allocationSession == capture.sessionId && after != null && allocationStart != null)
                (after - allocationStart!!).takeIf { it >= 0 } else null,
            "performanceStages" to probe.snapshot(),
            "performanceStagesAtReplayStart" to if (allocationSession == capture.sessionId) replayStageStart else emptyMap<String, Map<String, Number>>())
        controller?.presentationState?.value?.takeIf { it.sessionId == capture.sessionId }?.let { ui ->
            metadata["role5Ui"] = mapOf(
                "stage" to ui.intervention?.stage?.name,
                "warnings" to ui.warnings,
                "restCertified" to ui.restCertified, "stampCount" to ui.stampCount,
                "currentTripDriveSec" to ui.currentTripDriveSec,
                "departureFatigue" to ui.fatigue?.baseline?.departureFatigue,
                "assistMode" to ui.fatigue?.assist?.assistMode,
                "recovery" to ui.recovery,
            )
        }
        if (terminal) runtimeMetadata[capture.sessionId] = metadata
        return metadata
    }

    private fun processAllocatedBytes(): Long? = try {
        Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
    } catch (_: RuntimeException) { null }

    private fun setTraceStatus(value: String) {
        traceStatusText = value
        traceStatus.text = value
    }

    private fun act(dispatchStage: WorkStage = WorkStage.UI_COMMAND_DISPATCH, action: suspend DebugDemoController.() -> Unit) {
        val scope = sessionScope ?: return
        val session = controller ?: return
        val dispatcher = workerDispatcher
        scope.launch {
            val dispatched = probe.begin(dispatchStage)
            try { withContext(dispatcher) {
                probe.end(dispatchStage, dispatched)
                session.action()
            } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: IllegalStateException) {
                Toast.makeText(this@DebugDemoActivity, error.message ?: "현재 재생 상태에서 실행할 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun render(snapshot: DebugDemoSnapshot) = probe.measure(WorkStage.UI_RENDER) { renderInternal(snapshot) }

    private fun renderInternal(snapshot: DebugDemoSnapshot) {
        if (snapshot === lastRenderedSnapshot) return
        lastRenderedSnapshot = snapshot
        val p = snapshot.playback
        val actualProcessingMs = snapshot.processingCompletedAtElapsedMs?.let { end ->
            snapshot.replayStartedAtElapsedMs?.let { start -> end - start }
        }
        val achieved = actualProcessingMs?.takeIf { it > 0 }?.let { elapsed ->
            snapshot.durationMs?.toDouble()?.div(elapsed)
        }
        val badge = if (p.phase == DemoPhase.PLAYING) "데모 재생 중" else if (p.phase == DemoPhase.PAUSED) "데모 일시정지" else "데모 재생 안 함"
        summary.text = "$badge · ${snapshot.transportMode} · ${p.phase} · ${p.scenarioId ?: "미로드"} · 세션${p.sessionId}\n" +
            "시나리오 ${p.scenarioTimeMs}/${snapshot.durationMs ?: "—"}ms · 실제 경과 ${p.realElapsedMs}ms(일시정지 포함)\n" +
            "구간 요청 ${p.segmentMultiplier ?: "—"}배 · 질문 반영 목표 ${p.speedMultiplier ?: 0}배 · 가짜 REAL ${if (snapshot.fakeRealEnabled) "켜짐" else "꺼짐"}\n" +
            (if (achieved != null) "실제 처리 완료 ${actualProcessingMs}ms · 전체 달성 ${String.format(java.util.Locale.ROOT, "%.3f", achieved)}배(질문·정지 포함)\n" else "실제 처리 완료 배속은 종료 후 표시\n") +
            "원본 전달 ${p.deliveredSamples} · 마지막t=${p.lastOriginalSampleMs ?: "—"} · ${snapshot.traceSummary}"
        question.text = "가짜 질문 ${snapshot.questionPhase} · ${p.questionRequestId?.value ?: "잠금 없음"}\n" +
            "질문 실제 경과 ${snapshot.questionElapsedMs ?: 0}ms · 판정 ${snapshot.response?.verdict ?: "아직 없음"} · 실제 응답 ${snapshot.response?.latencyMs ?: "없음"}ms\n" +
            "실제 TTS/STT 연결 미완료 · 신호음 끝 이후5초 무응답은 자동 종료 · 목소리 시작 뒤④판정 종료"
        message.text = p.error?.let { "${it.code} · ${it.path} · ${it.message}" }
            ?: p.failureMessage ?: snapshot.message
        // Hub 값만 고정한다. 재생 배지·질문·배속은 중지 뒤에도 과거 표시로 남기지 않는다.
        if (displayFrozen) return
        val frame = snapshot.frame
        val navi = snapshot.navi
        fun quality(q: SignalQuality?) = "${q?.source ?: "—"}/${q?.availability ?: "UNAVAILABLE"}/${q?.selectionReason ?: "—"}"
        fun row(name: String, value: Any?, q: SignalQuality?) = "$name = ${value ?: "null"}\n  ${quality(q)}\n"
        finalValues.text = "Hub ${if (snapshot.diagnostics.running) "수집 중" else "중지"} · 프레임${snapshot.diagnostics.emittedFrames} · 누락deadline${snapshot.diagnostics.skippedDeadlines}\n" +
            "시계 오류 ${snapshot.diagnostics.clockFailure} · t=${frame?.tMs ?: "—"}\n\n" +
            row("차속(km/h)", frame?.speedKph, frame?.qualityOf(SignalField.SPEED_KPH)) +
            row("조향(°)", frame?.steeringDeg, frame?.qualityOf(SignalField.STEERING_DEG)) +
            row("기어", frame?.gear, frame?.qualityOf(SignalField.GEAR)) +
            row("방향지시등", frame?.turnSignal, frame?.qualityOf(SignalField.TURN_SIGNAL)) +
            row("브레이크", frame?.brakeOn, frame?.qualityOf(SignalField.BRAKE_ON)) +
            row("크루즈", frame?.cruiseActive, frame?.qualityOf(SignalField.CRUISE_ACTIVE)) +
            row("좌석", frame?.seats, frame?.qualityOf(SignalField.SEATS)) +
            row("위도(°)", navi?.lat, navi?.qualityOf(NaviField.LAT)) +
            row("경도(°)", navi?.lon, navi?.qualityOf(NaviField.LON)) +
            row("다음회전(m)", navi?.distToNextTurnM, navi?.qualityOf(NaviField.DIST_TO_NEXT_TURN_M)) +
            row("전원", snapshot.power?.state, snapshot.power?.quality)
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.rgb(231, 241, 248)); setPadding(dp(4), dp(4), dp(4), dp(4))
    }
    private fun button(name: String, action: () -> Unit) = Button(this).apply {
        text = name; textSize = 12f; isAllCaps = false; minHeight = dp(44); setOnClickListener { action() }
    }
    private fun row(vararg actions: Pair<String, () -> Unit>) = LinearLayout(this).apply {
        actions.forEach { (name, action) -> addView(button(name, action), LinearLayout.LayoutParams(0, -2, 1f)) }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun minimalEditorExample() = """
        {"schemaVersion":1,"id":"editor_minimal","startEpochMs":1793862000000,
         "samples":[
          {"t":0,"steeringDeg":0,"speedKph":40,"gear":"D","turnSignal":"OFF","brake":false,"seats":null,"cruise":null},
          {"t":0.1,"steeringDeg":1,"speedKph":40,"gear":"D","turnSignal":"OFF","brake":false,"seats":null,"cruise":null}
         ]}
    """.trimIndent()
}
