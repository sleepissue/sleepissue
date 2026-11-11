package com.sleepissue.awakedrive.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.sleepissue.awakedrive.climate.*
import com.sleepissue.awakedrive.data.*
import com.sleepissue.awakedrive.engine.check.CheckPolicy
import com.sleepissue.awakedrive.engine.check.OccupancyContext
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.forecast.*
import com.sleepissue.awakedrive.engine.intervention.InterventionPolicy
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.rest.*
import com.sleepissue.awakedrive.engine.voice.SpeechIntentParser
import com.sleepissue.awakedrive.fatigue.*
import com.sleepissue.awakedrive.navi.*
import com.sleepissue.awakedrive.pleos.*
import com.sleepissue.awakedrive.service.RealTripMonitorService
import com.sleepissue.awakedrive.session.*
import com.sleepissue.awakedrive.signal.*
import com.sleepissue.awakedrive.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.resume

/** SDK 신호·운행은 기존 서비스가 소유하고, 이 객체는 화면이 열린 동안의 개입·실행 연결만 소유한다. */
class LiveDriveUiSession(
    private val context: Context,
    private val service: RealTripMonitorService,
    private val hub: SignalHubSession,
    parent: CoroutineScope,
) : AutoCloseable {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val clock = service.clock
    private val config = EngineConfig()
    private var sequence = 0L
    private fun id() = RequestId("live-ui-${++sequence}")
    private var preferences = DriverUiSettingsStore(context).read()
    private var condition = StartCondition.NONE
    private val mutableState = MutableStateFlow(DriveSessionUiState())
    val state = mutableState.asStateFlow()
    private val manager = PolicyInterventionManager(InterventionPolicy(config), config, clock, Source.REAL,
        RequestTimerBasis.ELAPSED_REALTIME, ::id)
    private val bridge = FatigueBridge(config)
    private val runner = FatigueAssessmentRunner(bridge)
    private val areas = RestAreaRepository(context)
    private val database = RestRecordDatabase.open(context)
    private val ledger = RewardLedger(RoomRestRecordStore(database.dao()), clock)
    private var records = emptyList<RestRecord>()
    private val fallback = RawResourceFallbackPlayer(context, emptyMap())
    private val output = PleosTtsOutput(context, fallback)
    private val voice = VoiceAgent(output, AndroidDuckingAudioFocus(context), AndroidDelay())
    private val recognizer = PleosSpeechRecognizer(context)
    private val tone = SignalTonePlayer()
    private val coordinator = VoiceCheckCoordinator(CheckManager(AndroidElapsedClock(), CheckPolicy(config), SpeechIntentParser()),
        CheckPolicy(config), recognizer, tone)
    private val voicePort = VoiceCheckPort(PassengerCheckFlow(CheckPolicy(config), voice, coordinator), voice, coordinator,
        occupancyProvider = {
            val seats = hub.latestFrame.value?.seats
            OccupancyContext(seats?.frontPassenger == true,
                seats?.rearLeft == true || seats?.rearCenter == true || seats?.rearRight == true,
                speechDetected = false, microphoneAvailable = microphoneAvailable())
        }, baselineMsProvider = { config.defaultVoiceBaselineMs })
    private val checkedVoicePort = object : CheckPort by voicePort {
        override suspend fun start(request: CheckRequest): ExecutionResult {
            if (!microphoneAvailable() && request.context !in setOf(CheckContext.DRIVER_ALERTNESS, CheckContext.PASSENGER_ALERTNESS)) {
                message("마이크 권한·동의가 없어 음성 확인을 시작하지 않았습니다")
                return ExecutionResult(request.requestId, ExecutionStatus.FAILED, ExecutionFailure.PERMISSION_DENIED)
            }
            return voicePort.start(request)
        }
    }
    private val device = PleosSeatVentilationDevice(context)
    private val climate = DriverVentilationController(device, PrefsVentilationMemory(context),
        { speak("운전석 통풍을 켜 보세요. 안전한 쉼터에서 쉬어 가세요.") }, scope, config)
    private val naviBridge = PleosNaviBridge(context, config)
    private val restPort = RestAreaRestPort(areas, RestAreaSelector(config), naviBridge, scope)
    private val restMonitor = RestSessionMonitor(RestCertifier(areas.areas, config), ledger, clock, climate, config)
    private val nap = NapSession(NapTimer(config), ToneNapAlarm(scope), ::speak, clock, config)
    private val forecast = ForecastCoordinator(ForecastEngine(clock, config), clock, manager,
        { ForecastCoordinator.inputFrom(service.runtime.session.trip.value, hub.latestNavi.value,
            base = ForecastInput(0, null, 0, emptyList(), condition, ageBand = ageBand())) }, ::id)
    private val router = InterventionRouter(manager, checkedVoicePort, VoiceSpeechPort(voice), restPort, climate,
        recoveryReset = { request ->
            runner.onRestCertified()
            forecast.recalculate(ForecastRecalcTrigger.REST_VERIFIED)
            ExecutionResult(request.requestId, ExecutionStatus.SUCCEEDED)
        }, newInputId = ::id)
    private val restWiring = RestWiring(manager, router, restMonitor, nap, climate, hub, ::speak,
        { service.runtime.session.trip.value.continuousDriveSec }, ::id, config)

    val actions = DriveUiActionRouter(manager, { state.value },
        condition = { value ->
            condition = value.toStartCondition()
            runner.reportCondition(condition == StartCondition.DROWSY, clock.scenarioTimeMs(), manager)
            forecast.recalculate(ForecastRecalcTrigger.CONDITION_INPUT)
        }, preferences = { value -> preferences = value; bridge.ageBand = ageBand() },
        passenger = { rest ->
            val seats = hub.latestFrame.value?.seats
            val present = seats?.frontPassenger == true || seats?.rearLeft == true || seats?.rearCenter == true || seats?.rearRight == true
            if (preferences.passengerChecks && present) voicePort.completeWithPassengerButton(rest)
            else ExecutionResult(id(), ExecutionStatus.FAILED, ExecutionFailure.UNAVAILABLE)
        }, startNap = { restWiring.requestNap() }, wake = { nap.wake() },
        recovery = {
            if (!manager.resumeInterruptedChecks()) message("진행 중인 회복 확인에 음성으로 답해 주세요. 인증 전에는 회복 확인을 완료할 수 없습니다")
        }, deleteRecords = {
            val deleted = service.runtime.session.persistence.delete()
            if (deleted) ledger.deleteAll()
            deleted
        }, feedback = ::message, newId = ::id)

    fun start() {
        router.start(scope)
        restWiring.start(scope)
        scope.launch { ledger.records.collect { records = it } }
        scope.launch { router.foundAreas.collect { result -> mutableState.update { it.copy(nextRestArea = result.toRestAreaUiState()) } } }
        scope.launch { router.checkResults.collect { result ->
            if (result.context == CheckContext.RECOVERY) mutableState.update {
                it.copy(recovery = when (result.outcome) {
                    CheckOutcome.NORMAL -> "회복 확인 정상"
                    CheckOutcome.SLOW -> "회복 응답이 느립니다. 더 쉬어 가세요"
                    CheckOutcome.NO_RESPONSE -> "회복 응답이 없습니다. 더 쉬어 가세요"
                    else -> "회복 확인 미완료"
                })
            }
        } }
        scope.launch {
            manager.start(id())
            manager.submit(id(), InterventionInput.VentilationConsentChanged(preferences.ventilationConsent))
            manager.submit(id(), InterventionInput.CheckFrequencyChanged(preferences.frequentChecks))
            bridge.ageBand = ageBand()
            forecast.recalculate(ForecastRecalcTrigger.TRIP_START)
            launch { runner.run(hub.samples, { hub.latestNavi.value }, { service.runtime.session.trip.value }, manager) }
            launch { forecast.watchDepartureFatigue(runner.snapshots.map { it.baseline.departureFatigue }) }
            while (isActive) {
                val trip = service.runtime.session.trip.value
                manager.submit(id(), InterventionInput.TripChanged(trip))
                manager.submit(id(), InterventionInput.NavigationChanged(hub.latestNavi.value))
                manager.submit(id(), InterventionInput.Tick)
                val rest = restMonitor.ui.value
                val precise = service.runtime.session.preciseState()
                val tripSec = trip.runStartMs?.let { start ->
                    precise.driveSegments.sumOf { (it.endMs - maxOf(it.startMs, start)).coerceAtLeast(0) } / 1_000
                }
                mutableState.update { old -> old.copy(tMs = clock.scenarioTimeMs(), frame = hub.latestFrame.value,
                    trip = trip, currentTripDriveSec = tripSec, fatigue = runner.snapshots.value,
                    intervention = manager.state.value, forecast = forecast.latest.value,
                    restAreaName = rest.areaName, restCertified = rest.certified,
                    restRemainingMs = rest.startedAtMs?.let { (config.restCertifyMinMs - (clock.scenarioTimeMs() - it)).coerceAtLeast(0) },
                    stampCount = records.count { it.rewarded }, nap = nap.state.value, napRemainingMs = nap.remainingMs(),
                    actionsConnected = true,
                    warnings = manager.transitions.value.map { ReportWarningEvent(it.tMs.toString(), "${it.to.level}단계", it.reason.name) },
                    rests = records.map { ReportRestEvent(it.certifiedAtWallMs.toString(), "15분", "${it.areaName} · ${it.outcome}") }) }
                delay(config.signalFrameIntervalMs)
            }
        }
    }
    private fun message(value: String) { mutableState.update { it.copy(message = value, waypointMessage = value) } }
    private fun microphoneAvailable() = preferences.microphoneConsent &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun ageBand() = when (preferences.ageBand) {
        "청년" -> AgeBand.TWENTIES; "중년" -> AgeBand.THIRTIES_FORTIES; "고령" -> AgeBand.FIFTIES_PLUS; else -> null
    }
    private suspend fun speak(text: String) = suspendCancellableCoroutine<Unit> { continuation ->
        voice.speak(text, FallbackCue.GENERIC_GUIDANCE, onComplete = { if (continuation.isActive) continuation.resume(Unit) })
    }
    override fun close() {
        coordinator.cancel(); job.cancel()
        recognizer.close(); tone.close(); output.close(); fallback.close(); device.close(); naviBridge.close(); database.close()
    }
}
