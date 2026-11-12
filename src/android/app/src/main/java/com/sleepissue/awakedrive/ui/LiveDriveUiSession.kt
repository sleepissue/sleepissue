package com.sleepissue.awakedrive.ui

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.forecast.ForecastRecalcTrigger
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.service.MonitoringPipeline
import com.sleepissue.awakedrive.service.RealTripMonitorService
import com.sleepissue.awakedrive.signal.SignalHubSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** 서비스가 소유한 판단·음성·쉼터 상태를 관찰한다. 화면 종료는 서비스 부품을 닫지 않는다. */
class LiveDriveUiSession(
    private val service: RealTripMonitorService,
    private val hub: SignalHubSession,
    private val pipeline: MonitoringPipeline,
    parent: CoroutineScope,
) : AutoCloseable {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val mutableState = MutableStateFlow(DriveSessionUiState())
    val state = mutableState.asStateFlow()
    private var sequence = 0L
    private fun id() = RequestId("live-ui-${++sequence}")
    private fun message(value: String) { mutableState.update { it.copy(message = value) } }
    val actions = DriveUiActionRouter(pipeline.manager, { state.value },
        condition = {
            pipeline.runner.reportCondition(it == DrivingCondition.VERY_SLEEPY, service.clock.scenarioTimeMs(), pipeline.manager)
            pipeline.forecast.recalculate(ForecastRecalcTrigger.CONDITION_INPUT)
        },
        preferences = { pipeline.updateSettings(it) },
        passenger = { pipeline.respondPassenger(it) },
        startNap = { pipeline.restWiring.requestNap() }, wake = { pipeline.rest.nap.wake() },
        recovery = {
            if (!pipeline.manager.resumeInterruptedChecks()) message("진행 중인 회복 확인에 답해 주세요")
        }, deleteRecords = {
            val deleted = service.runtime.session.persistence.delete()
            if (deleted) pipeline.rest.ledger.deleteAll()
            deleted
        }, feedback = ::message, newId = ::id)

    fun start() {
        scope.launch { pipeline.rest.restPort.results.collect { result ->
            val candidate = result.candidate
            if (candidate != null) mutableState.update { it.copy(nextRestArea = RestAreaUiState.Found(
                candidate.name, "%.1fkm".format(candidate.distanceM / 1000f),
                pipeline.rest.repository.areas.firstOrNull { area -> area.id == candidate.id }?.hasToilet)) }
        } }
        scope.launch { pipeline.rest.ledger.records.collect { records -> mutableState.update { it.copy(
            stampCount = records.count { record -> record.rewarded },
            rests = records.map { record -> ReportRestEvent(record.certifiedAtWallMs.toString(), "15분", "${record.areaName} · ${record.outcome}") }) } } }
        scope.launch {
            while (isActive) {
                val trip = service.runtime.session.trip.value
                val rest = pipeline.rest.restMonitor.ui.value
                val precise = service.runtime.session.preciseState()
                val driveSec = trip.runStartMs?.let { start ->
                    precise.driveSegments.sumOf { (it.endMs - maxOf(it.startMs, start)).coerceAtLeast(0) } / 1000
                }
                mutableState.update { it.copy(tMs = service.clock.scenarioTimeMs(), frame = hub.latestFrame.value,
                    trip = trip, currentTripDriveSec = driveSec, fatigue = pipeline.runner.snapshots.value,
                    intervention = pipeline.manager.state.value, forecast = pipeline.forecast.latest.value,
                    restAreaName = rest.areaName, restCertified = rest.certified,
                    restRemainingMs = rest.startedAtMs?.let { start -> (EngineConfig().restCertifyMinMs - (service.clock.scenarioTimeMs() - start)).coerceAtLeast(0) },
                    nap = pipeline.rest.nap.state.value, napRemainingMs = pipeline.rest.nap.remainingMs(), actionsConnected = true,
                    warnings = pipeline.manager.transitions.value.map { transition ->
                        ReportWarningEvent(transition.tMs.toString(), "${transition.to.level}단계", transition.reason.name) }) }
                delay(EngineConfig().signalFrameIntervalMs)
            }
        }
    }
    override fun close() { job.cancel() }
}
