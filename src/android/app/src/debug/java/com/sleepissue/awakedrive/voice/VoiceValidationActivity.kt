package com.sleepissue.awakedrive.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.sleepissue.awakedrive.cabin.CabinMonitor
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.session.ToneNapAlarm
import com.sleepissue.awakedrive.engine.check.CheckPolicy
import com.sleepissue.awakedrive.engine.check.CheckVerdict
import com.sleepissue.awakedrive.engine.check.QuestionType
import com.sleepissue.awakedrive.engine.voice.SpeechIntentParser
import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.GeoPoint
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.signal.RestAreaCandidate
import com.sleepissue.awakedrive.signal.SpeechCue
import com.sleepissue.awakedrive.signal.SpeechRequest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job

/** debug APK에서만 포함되는 Pleos TTS·STT·AudioRecord 자동 검증 화면. */
class VoiceValidationActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var ttsOutput: PleosTtsOutput
    private lateinit var recognizer: PleosSpeechRecognizer
    private lateinit var tonePlayer: SignalTonePlayer
    private lateinit var coordinator: VoiceCheckCoordinator
    private lateinit var cabinMonitor: CabinMonitor
    private lateinit var speechPort: VoiceSpeechPort
    private lateinit var napAlarm: ToneNapAlarm
    private var pendingPermissionAction: (() -> Unit)? = null
    private var validationRunning = false
    private var cabinTestStarted = false
    private var silenceCheck = false
    private var alarmNotice: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            text = "검증 준비됨\n"
            textSize = 18f
            setPadding(24, 24, 24, 24)
        }
        setContentView(buildContent())

        val fallback = RawResourceFallbackPlayer(this, emptyMap())
        ttsOutput = PleosTtsOutput(this, fallback)
        recognizer = PleosSpeechRecognizer(this)
        tonePlayer = SignalTonePlayer()
        cabinMonitor = CabinMonitor(this)
        napAlarm = ToneNapAlarm(lifecycleScope)
        coordinator = VoiceCheckCoordinator(
            checkManager = CheckManager(AndroidElapsedClock(), CheckPolicy(), SpeechIntentParser()),
            policy = CheckPolicy(),
            recognizer = recognizer,
            tonePlayer = tonePlayer,
        )
        speechPort = VoiceSpeechPort(VoiceAgent(
            output = ttsOutput,
            audioFocus = AndroidDuckingAudioFocus(this),
            delay = AndroidDelay(),
        ))
        lifecycleScope.launch {
            speechPort.results.collect { result ->
                append("단계 안내 실행: ${result.status}")
                if (validationRunning && result.status == ExecutionStatus.SUCCEEDED) {
                    append("1단계 자동 TTS 완료")
                    withMicrophonePermission(::testStt)
                }
            }
        }
        status.post {
            if (intent.getBooleanExtra("manual_validation", false)) {
                status.text = "수동 검증 준비됨. 주변을 조용히 한 뒤 원하는 검사를 누르세요.\n"
            } else if (intent.getBooleanExtra(EXTRA_SKIP_TTS, false)) {
                validationRunning = true
                cabinTestStarted = false
                status.text = "TTS 없이 음성 입력 검증을 시작합니다.\n"
                withMicrophonePermission(::testStt)
            } else {
                startAutomaticValidation()
            }
        }
    }

    private fun buildContent(): ScrollView = ScrollView(this).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(button("전체 검증 다시 실행") { startAutomaticValidation() })
            addView(button("진행 중 검증 취소") { cancelTests() })
            addView(button("안내음만 재생") {
                cancelTests()
                testTts()
            })
            addView(button("무응답 검사 · 끝날 때까지 침묵") {
                cancelTests()
                silenceCheck = true
                validationRunning = true
                cabinTestStarted = false
                withMicrophonePermission(::testStt)
            })
            addView(button("10초 말소리 감지만 검사") {
                cancelTests()
                validationRunning = true
                cabinTestStarted = false
                testCabinSpeech()
            })
            addView(button("파워냅 알람 10초 청취") {
                cancelTests()
                append("제품과 같은 알람을 10초 재생합니다. 원하면 검증 취소로 멈추세요.")
                val config = EngineConfig()
                napAlarm.start(config.napAlarmRampMs, 10_000L)
                alarmNotice = lifecycleScope.launch {
                    delay(10_000L)
                    append("알람 10초 재생 구간 종료. 실제 청취 여부는 사용자 확인이 필요합니다.")
                }
            })
            addView(status)
        })
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun startAutomaticValidation() {
        if (validationRunning) {
            append("이미 자동 검증이 진행 중입니다.")
            return
        }
        validationRunning = true
        cabinTestStarted = false
        status.text = "자동 검증을 시작합니다.\n"
        testTts()
    }

    private fun testTts() {
        append("1단계 개입 요청 → 음성 포트 자동 실행")
        lifecycleScope.launch {
            val result = speechPort.start(SpeechRequest(
                requestId = RequestId("voice-validation-${android.os.SystemClock.elapsedRealtime()}"),
                cue = SpeechCue.REST_AREA_AHEAD,
                restArea = RestAreaCandidate(
                    id = "debug-rest-area",
                    name = "앞쪽 졸음쉼터",
                    location = GeoPoint(37.0, 127.0),
                    distanceM = 2_000f,
                ),
            ))
            append("단계 안내 접수: ${result.status}")
        }
    }

    private fun testStt() {
        append(if (silenceCheck) "침묵 검사: 신호음에도 답하지 말고 전체 검증 종료까지 조용히 기다리세요."
            else "신호음 뒤 5초 안에 '네' 또는 '쉴게'라고 말하세요.")
        coordinator.start(
            baselineMs = 1_000L,
            questionType = QuestionType.DRIVER_CHECK,
            onResult = { result, consecutiveSlow ->
                append(
                    "STT 결과: latency=${result.latencyMs}ms, " +
                        "verdict=${result.verdict}, intent=${result.intent}, " +
                        "twoSlow=$consecutiveSlow",
                )
                if (!validationRunning) return@start
                if (result.verdict == CheckVerdict.SLOW && !consecutiveSlow) {
                    append("응답이 느려 30초 뒤 자동으로 한 번 더 확인합니다.")
                } else {
                    testCabinSpeech()
                }
            },
            onSttFallbackRequired = { append("STT 사용 불가: 버튼 대체가 필요합니다.") },
        )
    }

    private fun testCabinSpeech() {
        if (cabinTestStarted) return
        cabinTestStarted = true
        append("10초 동안 말소리 유무를 측정합니다. 원본 음성은 저장하지 않습니다.")
        cabinMonitor.sample(
            onResult = {
                append("말소리 감지 결과: $it")
                finishValidation()
            },
            onError = {
                append("말소리 감지 실패: ${it.message}")
                finishValidation()
            },
        )
    }

    private fun finishValidation() {
        validationRunning = false
        append("전체 음성 검증이 끝났습니다.")
    }

    private fun withMicrophonePermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingPermissionAction = action
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO,
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            append("마이크 권한 허용됨")
            pendingPermissionAction?.invoke()
        } else {
            append("마이크 권한 거절됨: 음성 대신 버튼 확인을 사용해야 합니다.")
            validationRunning = false
        }
        pendingPermissionAction = null
    }

    private fun cancelTests() {
        validationRunning = false
        silenceCheck = false
        alarmNotice?.cancel()
        alarmNotice = null
        coordinator.cancel()
        cabinMonitor.stop()
        napAlarm.stop()
        append("진행 중 검증 취소됨")
    }

    private fun append(message: String) {
        runOnUiThread { status.append("$message\n") }
    }

    override fun onDestroy() {
        coordinator.cancel()
        cabinMonitor.close()
        napAlarm.stop()
        recognizer.close()
        tonePlayer.close()
        ttsOutput.close()
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_RECORD_AUDIO = 1001
        const val EXTRA_SKIP_TTS = "skip_tts"
    }
}
