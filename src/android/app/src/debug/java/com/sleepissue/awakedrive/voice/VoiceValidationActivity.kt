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
import com.sleepissue.awakedrive.cabin.CabinMonitor
import com.sleepissue.awakedrive.engine.check.CheckPolicy
import com.sleepissue.awakedrive.engine.check.CheckVerdict
import com.sleepissue.awakedrive.engine.check.QuestionType
import com.sleepissue.awakedrive.engine.voice.SpeechIntentParser

/** debug APK에서만 포함되는 Pleos TTS·STT·AudioRecord 자동 검증 화면. */
class VoiceValidationActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var ttsOutput: PleosTtsOutput
    private lateinit var recognizer: PleosSpeechRecognizer
    private lateinit var tonePlayer: SignalTonePlayer
    private lateinit var coordinator: VoiceCheckCoordinator
    private lateinit var cabinMonitor: CabinMonitor
    private var pendingPermissionAction: (() -> Unit)? = null
    private var validationRunning = false
    private var cabinTestStarted = false

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
        coordinator = VoiceCheckCoordinator(
            checkManager = CheckManager(AndroidElapsedClock(), CheckPolicy(), SpeechIntentParser()),
            policy = CheckPolicy(),
            recognizer = recognizer,
            tonePlayer = tonePlayer,
        )
        status.post {
            if (intent.getBooleanExtra(EXTRA_SKIP_TTS, false)) {
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
        append("TTS 요청: 잠깐 확인할게요")
        VoiceAgent(
            output = ttsOutput,
            audioFocus = AndroidDuckingAudioFocus(this),
            delay = AndroidDelay(),
        ).speak(
            text = VoiceScripts.DRIVER_CHECK,
            fallback = FallbackCue.CHECK_PROMPT,
            onComplete = {
                append("TTS 완료 또는 대체 음원 종료")
                if (validationRunning) withMicrophonePermission(::testStt)
            },
        )
    }

    private fun testStt() {
        append("신호음 뒤 5초 안에 '네' 또는 '쉴게'라고 말하세요.")
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
        coordinator.cancel()
        cabinMonitor.stop()
        append("진행 중 검증 취소됨")
    }

    private fun append(message: String) {
        runOnUiThread { status.append("$message\n") }
    }

    override fun onDestroy() {
        coordinator.cancel()
        cabinMonitor.close()
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
