package com.sleepissue.awakedrive.demo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.sleepissue.awakedrive.cabin.PassengerOccupancyResolver
import com.sleepissue.awakedrive.engine.check.CheckPolicy
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.rest.RestAreaSelector
import com.sleepissue.awakedrive.engine.voice.SpeechIntentParser
import com.sleepissue.awakedrive.navi.RestAreaRepository
import com.sleepissue.awakedrive.navi.RestAreaRestPort
import com.sleepissue.awakedrive.pleos.PleosNaviBridge
import com.sleepissue.awakedrive.session.ToneNapAlarm
import com.sleepissue.awakedrive.signal.*
import com.sleepissue.awakedrive.ui.DriveSessionUiState
import com.sleepissue.awakedrive.ui.DriverUiSettingsStore
import com.sleepissue.awakedrive.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.resume

/** 기존 ON_DEVICE 음성·내비 래퍼를 사용한다. 실제 운행/보상 DB와 통풍 제어는 연결하지 않는다. */
class AndroidDemoExecutionFactory(private val context: Context) : DemoExecutionFactory {
    override suspend fun create(scope: CoroutineScope, current: () -> DriveSessionUiState): DemoExecutionPorts =
        withContext(Dispatchers.Main.immediate) {
            val config = EngineConfig()
            val job = SupervisorJob(scope.coroutineContext[Job])
            val audioScope = CoroutineScope(scope.coroutineContext + job + Dispatchers.Main.immediate)
            val tts = PleosTtsOutput(context, RawResourceFallbackPlayer(context, emptyMap()))
            val agent = VoiceAgent(tts, AndroidDuckingAudioFocus(context), AndroidDelay(), config)
            val recognizer = PleosSpeechRecognizer(context)
            val tone = SignalTonePlayer()
            val policy = CheckPolicy(config)
            val coordinator = VoiceCheckCoordinator(CheckManager(AndroidElapsedClock(), policy, SpeechIntentParser()),
                policy, recognizer, tone, config = config)
            val settings = DriverUiSettingsStore(context)
            fun microphoneAllowed() = settings.read().microphoneConsent &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val voice = VoiceCheckPort(PassengerCheckFlow(policy, agent, coordinator), agent, coordinator,
                { PassengerOccupancyResolver.resolve(current().frame?.seats, null, speechDetected = false,
                    microphoneAvailable = microphoneAllowed()) }, { config.defaultVoiceBaselineMs })
            val progress = MutableSharedFlow<ExecutionResult>(extraBufferCapacity = 16)
            val manualResults = MutableSharedFlow<CheckResult>(extraBufferCapacity = 16)
            val buttons = MutableSharedFlow<CheckRequest>(extraBufferCapacity = 4)
            var manual: CheckRequest? = null
            audioScope.launch(start = CoroutineStart.UNDISPATCHED) {
                voice.buttonRequests.collect {
                    // 음성 대신 버튼 질문을 화면에 제시하는 시작이다.
                    progress.emit(ExecutionResult(it.requestId, ExecutionStatus.STARTED))
                    buttons.emit(CheckRequest(it.requestId, it.context))
                }
            }
            val check = object : CheckPort {
                override val executionResults = merge(voice.executionResults, progress)
                override val results = merge(voice.results, manualResults)
                override suspend fun start(request: CheckRequest): ExecutionResult = withContext(Dispatchers.Main.immediate) {
                    if (microphoneAllowed()) voice.start(request) else {
                        manual = request
                        progress.emit(ExecutionResult(request.requestId, ExecutionStatus.STARTED))
                        buttons.emit(request)
                        ExecutionResult(request.requestId, ExecutionStatus.REQUESTED)
                    }
                }
                override suspend fun cancel(requestId: RequestId): ExecutionResult = withContext(Dispatchers.Main.immediate) {
                    if (manual?.requestId == requestId) {
                        manual = null
                        ExecutionResult(requestId, ExecutionStatus.CANCELLED).also { progress.emit(it) }
                    } else voice.cancel(requestId)
                }
            }
            val speechPort = VoiceSpeechPort(agent)
            val speech = object : SpeechPort by speechPort {
                override suspend fun start(request: SpeechRequest) = withContext(Dispatchers.Main.immediate) { speechPort.start(request) }
                override suspend fun cancel(requestId: RequestId) = withContext(Dispatchers.Main.immediate) { speechPort.cancel(requestId) }
            }
            val repository = RestAreaRepository(context)
            val bridge = PleosNaviBridge(context, config)
            val rest = RestAreaRestPort(repository, RestAreaSelector(config), bridge, audioScope)
            val alarm = ToneNapAlarm(audioScope)
            DemoExecutionPorts(check, speech, rest, buttons,
                respond = { id, intent -> withContext(Dispatchers.Main.immediate) {
                    val pending = manual?.takeIf { it.requestId == id }
                    if (pending != null) {
                        manual = null
                        manualResults.emit(CheckResult(id, pending.context, CheckOutcome.NORMAL,
                            intent = intent, channel = ResponseChannel.DRIVER_BUTTON))
                        ExecutionResult(id, ExecutionStatus.SUCCEEDED)
                    } else voice.completeWithButton(id, intent, passengerButton = false)
                } },
                alarm = { active -> if (active) alarm.start(config.napAlarmRampMs, config.napAlarmMaxMs) else alarm.stop() },
                close = { withContext(Dispatchers.Main.immediate) {
                    coordinator.cancel(); alarm.stop(); job.cancelAndJoin()
                    bridge.close(); tone.close(); recognizer.close(); tts.close()
                } }, waypointFailure = { area -> withContext(Dispatchers.Main.immediate) {
                    val target = area?.let { "${it.name}${if (it.distanceM > 0) "까지 약 ${it.distanceM.toInt() / 1000}킬로미터" else ""}" } ?: "앞쪽 쉼터"
                    agent.speak("경유지 추가를 확인하지 못했어요. $target 쪽에서 안전하게 쉬어 가세요. 내비게이션에서 확인해 주세요.", FallbackCue.REST_WARNING)
                } }, napStarted = { atRestArea ->
                    audioScope.launch {
                        if (!atRestArea) suspendCancellableCoroutine<Unit> { done ->
                            agent.speak(VoiceScripts.NAP_SAFETY_CHECK, FallbackCue.GENERIC_GUIDANCE,
                                onComplete = { if (done.isActive) done.resume(Unit) })
                        }
                        agent.speak(VoiceScripts.NAP_START, FallbackCue.GENERIC_GUIDANCE)
                    }
                })
        }
}
