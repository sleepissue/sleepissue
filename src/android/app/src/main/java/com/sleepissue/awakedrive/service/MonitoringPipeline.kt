package com.sleepissue.awakedrive.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.sleepissue.awakedrive.cabin.PassengerOccupancyResolver
import com.sleepissue.awakedrive.engine.check.CheckPolicy
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.forecast.ForecastEngine
import com.sleepissue.awakedrive.engine.intervention.InterventionPolicy
import com.sleepissue.awakedrive.engine.model.AgeBand
import com.sleepissue.awakedrive.engine.model.ExecutionResult
import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.ExecutionFailure
import com.sleepissue.awakedrive.signal.InterventionInput
import com.sleepissue.awakedrive.ui.DriverUiSettings
import com.sleepissue.awakedrive.ui.DriverUiSettingsStore
import com.sleepissue.awakedrive.engine.forecast.ForecastRecalcTrigger
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.model.Source
import com.sleepissue.awakedrive.engine.model.TripState
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.engine.voice.SpeechIntentParser
import com.sleepissue.awakedrive.fatigue.FatigueAssessmentRunner
import com.sleepissue.awakedrive.fatigue.FatigueBridge
import com.sleepissue.awakedrive.session.ForecastCoordinator
import com.sleepissue.awakedrive.session.RestModule
import com.sleepissue.awakedrive.session.RestRecoveryReset
import com.sleepissue.awakedrive.session.RestWiring
import com.sleepissue.awakedrive.signal.InterventionRouter
import com.sleepissue.awakedrive.signal.PolicyInterventionManager
import com.sleepissue.awakedrive.signal.RequestTimerBasis
import com.sleepissue.awakedrive.signal.SignalHubSession
import com.sleepissue.awakedrive.voice.AndroidDelay
import com.sleepissue.awakedrive.voice.AndroidDuckingAudioFocus
import com.sleepissue.awakedrive.voice.AndroidElapsedClock
import com.sleepissue.awakedrive.voice.CheckManager
import com.sleepissue.awakedrive.voice.FallbackCue
import com.sleepissue.awakedrive.voice.PassengerCheckFlow
import com.sleepissue.awakedrive.voice.PleosSpeechRecognizer
import com.sleepissue.awakedrive.voice.PleosTtsOutput
import com.sleepissue.awakedrive.voice.RawResourceFallbackPlayer
import com.sleepissue.awakedrive.voice.SignalTonePlayer
import com.sleepissue.awakedrive.voice.VoiceAgent
import com.sleepissue.awakedrive.voice.VoiceCheckCoordinator
import com.sleepissue.awakedrive.voice.VoiceCheckPort
import com.sleepissue.awakedrive.voice.VoiceSpeechPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * 감시 서비스가 가진 실제 부품들. [create]가 SDK·음성·Room을 만들고 [AwakeDrivePipeline]으로 잇는다.
 * 화면은 [manager]·[runner]·[rest]·[forecast]의 StateFlow를 구독한다.
 */
class MonitoringPipeline private constructor(
    val manager: PolicyInterventionManager,
    val runner: FatigueAssessmentRunner,
    val rest: RestModule,
    val forecast: ForecastCoordinator,
    val restWiring: RestWiring,
    private val check: VoiceCheckPort,
    private val job: Job,
    private val closeables: List<AutoCloseable>,
) {
    @Volatile private var passengerChecks = false
    private val settingsSequence = AtomicLong()

    suspend fun updateSettings(value: DriverUiSettings) {
        val previousAgeBand = runner.ageBand
        runner.updateAgeBand(when (value.ageBand) {
            "청년" -> AgeBand.TWENTIES
            "중년" -> AgeBand.THIRTIES_FORTIES
            "고령" -> AgeBand.FIFTIES_PLUS
            else -> null
        })
        manager.submit(RequestId("settings-${settingsSequence.incrementAndGet()}"),
            InterventionInput.FrequentChecksChanged(value.frequentChecks))
        passengerChecks = value.passengerChecks
        if (previousAgeBand != runner.ageBand) forecast.recalculate(ForecastRecalcTrigger.CONDITION_INPUT)
    }

    suspend fun respondPassenger(rest: Boolean): ExecutionResult {
        if (!passengerChecks) return ExecutionResult(
            RequestId("passenger-disabled"), ExecutionStatus.FAILED,
            ExecutionFailure.UNAVAILABLE)
        return check.completeWithPassenger(rest)
    }

    suspend fun close() {
        job.cancel()
        manager.stop(RequestId("pipeline-stop"))
        closeables.forEach { runCatching { it.close() } }
        rest.close()
    }

    companion object {
        // shortcut: 마이크 포그라운드 서비스 유형이 없어 앱 화면이 떠 있을 때만 대답을 들을 수 있다(CLAUDE.md 8장).
        //   화면 없이 음성 확인을 하려면 microphone FGS 유형과 FOREGROUND_SERVICE_MICROPHONE을 추가한다.
        suspend fun create(
            context: Context,
            scope: CoroutineScope,
            clock: Clock,
            hub: SignalHubSession,
            trip: StateFlow<TripState>,
            ventilationConsent: StateFlow<Boolean>,
            config: EngineConfig = EngineConfig(),
        ): MonitoringPipeline {
            val ids = AtomicLong()
            fun id(prefix: String) = RequestId("$prefix-${ids.incrementAndGet()}")

            val manager = PolicyInterventionManager(InterventionPolicy(config), config, clock, Source.REAL,
                RequestTimerBasis.ELAPSED_REALTIME) { id("manager") }
            val runner = FatigueAssessmentRunner(FatigueBridge(config))

            val tts = PleosTtsOutput(context, RawResourceFallbackPlayer(context, emptyMap()))
            val agent = VoiceAgent(tts, AndroidDuckingAudioFocus(context), AndroidDelay(), config)
            val speak: suspend (String) -> Unit = { text ->
                suspendCancellableCoroutine { done ->
                    agent.speak(text, FallbackCue.GENERIC_GUIDANCE, onComplete = { if (done.isActive) done.resume(Unit) })
                }
            }
            val recognizer = PleosSpeechRecognizer(context)
            val tone = SignalTonePlayer()
            val policy = CheckPolicy()
            val coordinator = VoiceCheckCoordinator(CheckManager(AndroidElapsedClock(), policy, SpeechIntentParser()),
                policy, recognizer, tone)
            val check = VoiceCheckPort(PassengerCheckFlow(policy, agent, coordinator), agent, coordinator,
                { PassengerOccupancyResolver.resolve(hub.latestFrame.value?.seats, null, speechDetected = false,
                    microphoneAvailable = DriverUiSettingsStore(context).read().microphoneConsent &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) },
                { config.defaultVoiceBaselineMs }) // [가정] 개인 대답 기준선(2차 BSL)은 아직 없어 기본값

            val rest = RestModule(context, scope, clock, config, speak)
            val forecast = ForecastCoordinator(ForecastEngine(clock, config), clock, manager,
                { ForecastCoordinator.inputFrom(trip.value, hub.latestNavi.value).copy(ageBand = runner.ageBand) }, { id("forecast") })
            val reset = RestRecoveryReset({ runner.onRestCertified() }, forecast)
            val router = InterventionRouter(manager, check = check, speech = VoiceSpeechPort(agent), rest = rest.restPort,
                climate = rest.climate, recoveryReset = { reset(it) }, newInputId = { id("router") })

            val pipeline = AwakeDrivePipeline(hub, trip, manager, runner, router, forecast, ventilationConsent, clock, config,
                engineContext = Dispatchers.Default)
            lateinit var wiring: RestWiring
            val job = pipeline.start(scope) {
                wiring = rest.wire(manager, router, hub, { trip.value.continuousDriveSec }, { id("rest") })
            }
            return MonitoringPipeline(manager, runner, rest, forecast, wiring, check, job, listOf(tts, recognizer, tone)).also {
                it.updateSettings(DriverUiSettingsStore(context).read())
            }
        }
    }
}
