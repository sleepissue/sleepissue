package com.sleepissue.awakedrive.demo

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.forecast.*
import com.sleepissue.awakedrive.engine.intervention.*
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.rest.*
import com.sleepissue.awakedrive.engine.session.*
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.fatigue.FatigueBridge
import com.sleepissue.awakedrive.signal.*
import com.sleepissue.awakedrive.signal.TripStateStore
import com.sleepissue.awakedrive.signal.TripTracker
import com.sleepissue.awakedrive.ui.*
import java.util.Locale

/** 데모 입력도 기존 판단·인증·보상 규칙을 거친다. SDK 경유지 실행 성공을 만들지 않는다. */
class DemoUiSession(
    private val clock: Clock,
    private val epochMs: Long,
    private val areas: List<RestArea>,
    private val config: EngineConfig = EngineConfig(),
    private val sessionId: Long = 0,
    private val managedExecution: Boolean = false,
) {
    private val bridge = FatigueBridge(config)
    private val store = TripStateStore()
    private val tracker = TripTracker(TripRules(config, TripRulePolicy(
        StopTimePolicy.V03_RETROACTIVE, ShortStopAccumulationPolicy.EXCLUDE,
    )) { "demo-ui-$sessionId" }, store, Source.DEMO)
    private val policy = InterventionPolicy(config)
    private var policyState = InterventionPolicyState()
    private val certifier = RestCertifier(areas, config)
    private val heading = HeadingEstimator(config)
    private val nap = NapTimer(config)
    private val rewards = RewardRules(config)
    private val history = mutableListOf<RewardHistoryEntry>()
    private var condition = StartCondition.NONE
    private var lastForecastMinute: Long? = null
    private var restStartedAtMs: Long? = null
    private var confirmation: CheckResult? = null
    private var recovered = false
    private val executionInputs = mutableListOf<InterventionInput>()
    var navigation: NaviState? = null
        private set
    private var frequentChecks = false
    private var assistedAnchorMs: Long? = null
    private val forecastClock = object : Clock {
        override fun scenarioTimeMs() = epochMs + state.tMs
        override fun wallTimeMs() = scenarioTimeMs()
        override fun elapsedRealtimeMs() = clock.elapsedRealtimeMs()
    }
    var state = DriveSessionUiState(sessionId = sessionId, demoSession = true, actionsConnected = true)
        private set

    fun observe(frame: SignalFrame, navi: NaviState?, power: PowerEvent?) {
        // 새 로드 시 이 객체 자체를 교체한다. 같은 세션의 시각 역행은 숨기지 않는다.
        tracker.accept(frame, power?.takeIf { it.tMs == frame.tMs })
        val moving = frame.gear == Gear.D && frame.speedKph?.let { it > 0f } == true
        navigation = navi?.let {
            if (it.lat == null || it.lon == null) heading.reset() else heading.observe(it)
            heading.fill(it)
        } ?: run { heading.reset(); null }
        val outputs = bridge.onFrame(frame.copy(tMs = epochMs + frame.tMs), navigation, tracker.state.value)
        val fatigue = bridge.snapshot
        val f = fatigue.fatigue
        if (moving && fatigue.assist.assistMode) {
            if (assistedAnchorMs == null) assistedAnchorMs = frame.tMs
        } else assistedAnchorMs = null
        val interval = if (frequentChecks || fatigue.baseline.departureFatigue || fatigue.baseline.reportedSleepy || fatigue.vulnerableTime == true)
            config.assistedRiskCheckIntervalMs else config.assistedCheckIntervalMs
        val regularCheckDue = assistedAnchorMs?.let { frame.tMs - it >= interval } == true
        if (regularCheckDue) assistedAnchorMs = frame.tMs
        val checkEvent = outputs.filterIsInstance<com.sleepissue.awakedrive.signal.InterventionInput.Safety>().any {
            it.value.type == SafetyEventType.RAPID_STEERING || it.value.type == SafetyEventType.HANDOVER_UNSAFE
        }
        if (managedExecution) {
            executionInputs += InterventionInput.TripChanged(tracker.state.value)
            executionInputs += InterventionInput.NavigationChanged(navigation)
            outputs.forEach { input ->
                when (input) {
                    is InterventionInput.Assessment -> executionInputs += input.copy(value = input.value.copy(
                        tMs = input.value.tMs - epochMs,
                        assistedDriving = moving && input.value.assistedDriving == true,
                        operationGapSec = if (moving) input.value.operationGapSec else null))
                    is InterventionInput.Safety -> if (moving) executionInputs += input.copy(
                        value = input.value.copy(tMs = input.value.tMs - epochMs, source = Source.DEMO))
                    else -> executionInputs += input
                }
            }
        }
        val next = if (managedExecution) null else policy.evaluate(policyState, InterventionSnapshot(
            tMs = frame.tMs,
            evaluation = if (f?.valid == true) EvaluationStatus.EVALUATED else EvaluationStatus.UNAVAILABLE,
            score = f?.riskSmoothed,
            vulnerableTime = fatigue.vulnerableTime == true,
            continuousDriveSec = tracker.state.value.continuousDriveSec,
            trending = fatigue.pattern.patternType == PatternType.TREND,
            sensitive = fatigue.baseline.departureFatigue || fatigue.baseline.reportedSleepy,
            safetyBypass = fatigue.pattern.safetyBypass,
            // 재생 종료의 무효화·정차는 운전 중 확인 요청의 근거가 아니다.
            checkRequired = moving && (checkEvent || regularCheckDue || fatigue.assist.handoverAbnormal ||
                fatigue.assist.assistMode && fatigue.assist.opGapSec >= config.assistedOperationGapSec),
            confirmation = confirmation,
            restCertified = state.restCertified,
            recoveredAfterCertifiedRest = recovered,
        ))
        if (next != null) policyState = next.state
        confirmation = null
        recovered = false
        val precise = tracker.preciseState()
        val tripDriveSec = precise.trip.runStartMs?.let { start ->
            precise.driveSegments.sumOf { (it.endMs - maxOf(it.startMs, start)).coerceAtLeast(0) } / 1_000
        }
        state = state.copy(tMs = frame.tMs, frame = frame, trip = tracker.state.value, currentTripDriveSec = tripDriveSec,
            fatigue = fatigue, intervention = if (managedExecution) state.intervention else policyState.publicState)
        next?.transition?.let { transition ->
            state = state.copy(warnings = state.warnings + ReportWarningEvent(
                "%02d:%02d".format(frame.tMs / 60_000, frame.tMs / 1000 % 60),
                "${transition.to.level}단계", "${transition.reason} · 위험도 ${f?.riskSmoothed ?: "미측정"}"))
        }
        val location = navigation?.let { n -> n.lat?.let { lat -> n.lon?.let { GeoPoint(lat, it) } } }
        certifier.observe(RestObservation(frame.tMs, location, frame.gear, power?.state,
            tracker.state.value.continuousDriveSec, state.intervention?.stage ?: InterventionStage.NORMAL)).forEach { event ->
            when (event) {
                is RestEvent.Arrived -> {
                    restStartedAtMs = event.startedAtMs
                    state = state.copy(restAreaName = event.area.name, restCertified = false)
                }
                is RestEvent.Certified -> {
                    if (managedExecution) executionInputs += InterventionInput.RestCertified(
                        RestCertification(event.sessionId, event.certifiedAtMs, Source.DEMO))
                    val wall = epochMs + event.certifiedAtMs
                    val decision = rewards.evaluate(event.area.id, event.area.location, wall,
                        event.driveSecBeforeRest, event.maxStageBeforeRest, history)
                    history += RewardHistoryEntry(event.area.id, event.area.location, wall, decision.awarded)
                    state = state.copy(restCertified = true, stampCount = history.count { it.rewarded },
                        recovery = "휴식 인증 완료 · 출발 전 음성 회복 확인이 필요합니다",
                        rests = state.rests + ReportRestEvent("%02d:%02d".format(event.certifiedAtMs / 60_000, event.certifiedAtMs / 1000 % 60),
                            "15분", "${event.area.name} · ${decision.outcome}"))
                }
                is RestEvent.Left -> {
                    restStartedAtMs = null
                    state = state.copy(restAreaName = null, restRemainingMs = null)
                }
            }
        }
        val napEvent = nap.tick(frame.tMs, frame.gear)
        state = state.copy(
            restRemainingMs = restStartedAtMs?.let { (config.restCertifyMinMs - (frame.tMs - it)).coerceAtLeast(0) },
            nap = nap.state, napRemainingMs = nap.remainingMs(frame.tMs),
            recovery = if (napEvent is NapEvent.Ended && napEvent.recoveryCheck) "파워냅 종료 · 회복 확인이 필요합니다" else state.recovery,
        )
        if (frame.tMs / 60_000 != lastForecastMinute) {
            lastForecastMinute = frame.tMs / 60_000
            recalculate(ForecastRecalcTrigger.TRIP_START)
            navigation?.let { currentNavi ->
                state = state.copy(nextRestArea = when (val result = RestAreaSelector(config).select(currentNavi, areas)) {
                    is RestAreaSelection.Selected -> RestAreaUiState.Found(result.area.name,
                        String.format(Locale.KOREA, "%.1fkm · 직선거리", result.distanceM / 1000f), result.area.hasToilet)
                    is RestAreaSelection.NotFound -> RestAreaUiState.NotFound
                })
            }
        }
    }

    fun action(action: DriveUiAction) {
        when (action) {
            is DriveUiAction.VoiceResponse -> state = state.copy(message = "음성 실행이 연결된 세션에서만 응답할 수 있습니다")
            is DriveUiAction.Preferences -> {
                frequentChecks = action.value.frequentChecks
                bridge.ageBand = when (action.value.ageBand) {
                "청년" -> AgeBand.TWENTIES
                "중년" -> AgeBand.THIRTIES_FORTIES
                "고령" -> AgeBand.FIFTIES_PLUS
                else -> null
                }
            }
            is DriveUiAction.Condition -> {
                condition = when (action.value) {
                    DrivingCondition.GOOD -> StartCondition.FRESH
                    DrivingCondition.SLIGHTLY_SLEEPY -> StartCondition.NORMAL
                    DrivingCondition.VERY_SLEEPY -> StartCondition.DROWSY
                }
                val inputs = bridge.reportCondition(condition == StartCondition.DROWSY, epochMs + state.tMs)
                if (managedExecution) executionInputs += inputs
                recalculate(ForecastRecalcTrigger.CONDITION_INPUT)
            }
            is DriveUiAction.Passenger -> {
                confirmation = CheckResult(RequestId("demo-passenger-${state.tMs}"), CheckContext.PASSENGER_ALERTNESS,
                    CheckOutcome.NORMAL, intent = if (action.rest) ResponseIntent.REST else ResponseIntent.OK,
                    channel = ResponseChannel.PASSENGER_BUTTON)
                state = state.copy(message = if (action.rest) "동승자: 쉬어 가요" else "동승자: 괜찮아요")
            }
            DriveUiAction.CancelWaypoint -> state = state.copy(
                waypointMessage = "자동 경유지 제안 취소됨 · 위험 단계는 유지됩니다",
                message = "데모 경유지 제안을 취소했습니다. 실제 내비 요청은 실행하지 않았습니다",
            )
            DriveUiAction.StartNap -> {
                if (!state.parked) { state = state.copy(message = "차속 0·P단 확인 뒤 시작할 수 있습니다"); return }
                val started = nap.start(state.tMs, state.frame?.gear, state.restAreaName != null)
                state = state.copy(nap = nap.state, napRemainingMs = nap.remainingMs(state.tMs),
                    message = if (started is NapStart.Started) "시동을 유지하세요. 실제 수면 여부는 판단하지 않습니다" else "이미 파워냅이 진행 중입니다")
            }
            DriveUiAction.WakeNow -> {
                nap.wake()
                state = state.copy(nap = nap.state, napRemainingMs = null, recovery = "기상 완료 · 회복 확인이 필요합니다")
            }
            DriveUiAction.RequestRecovery -> state = state.copy(recovery = "음성 회복 확인 요청 중")
            DriveUiAction.DeleteRecords -> {
                history.clear()
                state = state.copy(stampCount = 0, message = "이 데모 세션의 보상 기록을 삭제했습니다")
            }
        }
    }

    fun drainExecutionInputs(): List<InterventionInput> = executionInputs.toList().also { executionInputs.clear() }

    fun executionState(value: InterventionState, transitions: List<StageTransition>) {
        state = state.copy(intervention = value, warnings = transitions.map {
            ReportWarningEvent("%02d:%02d".format(it.tMs / 60_000, it.tMs / 1000 % 60),
                "${it.to.level}단계", it.reason.name)
        })
    }

    fun voiceButton(context: CheckContext?) { state = state.copy(voiceButtonContext = context) }

    fun feedback(message: String, waypoint: Boolean = false) {
        state = if (waypoint) state.copy(waypointMessage = message) else state.copy(message = message)
    }

    fun resetAfterRecovery() { bridge.onRestCertified(); recalculate(ForecastRecalcTrigger.REST_VERIFIED) }

    fun checkResult(result: CheckResult) { confirmation = result }

    fun recoveryResult(outcome: CheckOutcome) {
        recovered = state.restCertified && outcome == CheckOutcome.NORMAL
        state = state.copy(recovery = when (outcome) {
            CheckOutcome.NORMAL -> if (state.restCertified) "회복 확인 정상 · 휴식 인증 완료" else "회복 확인 정상 · 휴식 인증은 별도 조건입니다"
            CheckOutcome.SLOW -> "응답이 느립니다. 더 쉬어 가세요"
            CheckOutcome.NO_RESPONSE -> "응답이 없습니다. 더 쉬어 가세요"
            else -> "음성 확인을 사용할 수 없습니다. 정상 회복으로 처리하지 않았습니다"
        })
    }

    private fun recalculate(trigger: ForecastRecalcTrigger) {
        state = state.copy(forecast = ForecastEngine(forecastClock, config).calculate(ForecastInput(
            (state.trip.continuousDriveSec / 60).toInt(), null,
            (state.trip.last24hDriveSec / 60).toInt(), emptyList(), condition,
            state.fatigue?.baseline?.departureFatigue == true,
        ), trigger))
    }
}
