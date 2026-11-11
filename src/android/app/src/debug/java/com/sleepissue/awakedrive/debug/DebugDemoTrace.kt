package com.sleepissue.awakedrive.debug

import com.google.gson.GsonBuilder
import com.sleepissue.awakedrive.demo.DemoPhase
import com.sleepissue.awakedrive.demo.DemoScenarioSample
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.performance.WorkProbe
import com.sleepissue.awakedrive.performance.WorkStage
import java.io.File
import java.util.ArrayDeque

internal data class DebugTraceRow(
    val snapshot: DebugDemoSnapshot,
    val capturedElapsedMs: Long,
    val currentScenarioMs: Long,
    val classification: String,
    val unexpectedNullMask: Int,
)

/** 매 tick은 계수만 갱신한다. 상세행과 JSON 변환은 각각 실제100ms와 IO 경로에서 만든다. */
class DebugDemoTrace(
    private val probe: WorkProbe = WorkProbe.NONE,
    private val performanceSnapshot: () -> Map<String, Map<String, Number>> = probe::snapshot,
) {
    private data class SessionCounters(
        val sessionId: Long,
        var scenarioId: String?,
        val firstCapturedElapsedMs: Long,
        var lastCapturedElapsedMs: Long,
        var finalPhase: DemoPhase,
        var finalScenarioMs: Long,
        var totalTicks: Long = 0,
        var playingTicks: Long = 0,
        var unexpectedNullTicks: Long = 0,
        var allSignalNullTicks: Long = 0,
        var unexpectedNullPublishedFrames: Long = 0,
        var allSignalNullPublishedFrames: Long = 0,
        var lastPublicationSequence: Long? = null,
    )

    /** 행·요약을 동결한 값이다. records()/write()는 Controller 잠금 밖의 IO 통로에서 실행한다. */
    class CapturedTrace internal constructor(
        val sessionId: Long,
        val capturedElapsedMs: Long,
        private val header: Map<String, Any?>,
        private val capturedRows: List<DebugTraceRow>,
    ) {
        fun records(metadata: Map<String, Any?> = emptyMap()): List<Map<String, Any?>> = buildList {
            add(if (metadata.isEmpty()) header else header + ("runtimeMetadata" to metadata))
            capturedRows.forEach { add(it.asMap()) }
        }
    }

    private val rows = ArrayDeque<DebugTraceRow>()
    private val sessions = linkedMapOf<Long, SessionCounters>()
    private var totalTicks = 0L
    private var playingTicks = 0L
    private var unexpectedNullTicks = 0L
    private var allSignalNullTicks = 0L
    private var previousClassification: String? = null
    private var previousQuestionId: RequestId? = null
    private var previousQuestionPhase: DebugDemoQuestionPhase? = null
    private var previousSpeed: Int? = null
    private var previousSession: Long? = null
    private var previousHubRunning: Boolean? = null
    private var lastCapturedAt: Long? = null

    fun record(snapshot: DebugDemoSnapshot, elapsedMs: Long, scenarioMs: Long, original: DemoScenarioSample?) =
        probe.measure(WorkStage.TRACE_RECORD) { recordInternal(snapshot, elapsedMs, scenarioMs, original) }

    private fun recordInternal(snapshot: DebugDemoSnapshot, elapsedMs: Long, scenarioMs: Long, original: DemoScenarioSample?) {
        totalTicks++
        val p = snapshot.playback
        val session = sessions.getOrPut(p.sessionId) {
            SessionCounters(p.sessionId, p.scenarioId, elapsedMs, elapsedMs, p.phase, scenarioMs)
        }
        session.scenarioId = p.scenarioId ?: session.scenarioId
        session.lastCapturedElapsedMs = elapsedMs
        session.finalPhase = p.phase
        session.finalScenarioMs = scenarioMs
        session.totalTicks++
        if (p.phase == DemoPhase.PLAYING) { playingTicks++; session.playingTicks++ }
        val frame = snapshot.frame
        val navi = snapshot.navi
        var unexpected = 0
        if (p.phase == DemoPhase.PLAYING && snapshot.diagnostics.emittedFrames > 0 && original != null) {
            if (original.vehicle.speed != null && frame?.speedKph == null) unexpected = unexpected or 1
            if (original.vehicle.steeringDeg != null && frame?.steeringDeg == null) unexpected = unexpected or 2
            if (original.vehicle.gear != null && frame?.gear == null) unexpected = unexpected or 4
            if (original.navi.lat != null && navi?.lat == null) unexpected = unexpected or 8
            if (original.navi.lon != null && navi?.lon == null) unexpected = unexpected or 16
        }
        if (unexpected != 0) { unexpectedNullTicks++; session.unexpectedNullTicks++ }
        val allNull = unexpected == 31
        if (allNull) { allSignalNullTicks++; session.allSignalNullTicks++ }
        // 같은 발행값을 여러 tick에서 읽은 수와 실제 null 발행 수를 구분한다.
        val publication = frame?.publication?.sequence
        if (publication != null && publication != session.lastPublicationSequence) {
            if (unexpected != 0) session.unexpectedNullPublishedFrames++
            if (allNull) session.allSignalNullPublishedFrames++
            session.lastPublicationSequence = publication
        }
        val classification = when {
            p.phase != DemoPhase.PLAYING -> p.phase.name
            snapshot.diagnostics.emittedFrames == 0L || original == null -> "STARTUP"
            allNull -> "PLAYING_ALL_EXPECTED_SIGNALS_NULL"
            unexpected != 0 -> "PLAYING_UNEXPECTED_NULL"
            else -> "PLAYING_VALUES_AVAILABLE"
        }
        val unchanged = classification == previousClassification && p.questionRequestId == previousQuestionId &&
            snapshot.questionPhase == previousQuestionPhase && p.speedMultiplier == previousSpeed && p.sessionId == previousSession &&
            snapshot.diagnostics.running == previousHubRunning
        if (lastCapturedAt?.let { elapsedMs - it < 100 } == true && unchanged) return
        // Controller의 계측 갱신과 상세 표집 주기가 어긋나도 저장하는 행에는 단계별 계측을 남긴다.
        // 상세행을 생략한 tick에서는 Map을 만들지 않는다.
        val stages = snapshot.performanceStages.ifEmpty { performanceSnapshot() }
        // 종료 캡처를 다시 행에 넣으면 이전 세션 전체를 연쇄 보관하게 된다.
        val detailSnapshot = if (snapshot.terminalTrace == null && stages === snapshot.performanceStages) snapshot
            else snapshot.copy(terminalTrace = null, performanceStages = stages)
        rows.addLast(DebugTraceRow(detailSnapshot, elapsedMs, scenarioMs, classification, unexpected))
        previousClassification = classification; previousQuestionId = p.questionRequestId
        previousQuestionPhase = snapshot.questionPhase; previousSpeed = p.speedMultiplier; previousSession = p.sessionId
        previousHubRunning = snapshot.diagnostics.running
        lastCapturedAt = elapsedMs
        while (rows.size > 2_400 || rows.firstOrNull()?.let { elapsedMs - it.capturedElapsedMs > 120_000 } == true) rows.removeFirst()
    }

    fun summary(): String = "tick$totalTicks · 예상외null $unexpectedNullTicks · 전체null $allSignalNullTicks · 상세${rows.size} · PLAYING$playingTicks"

    /** 소유 잠금 안에서는 불변 행 참조와 계수만 복사한다. 상세 Map 계산은 하지 않는다. */
    fun capture(sessionId: Long? = null): CapturedTrace {
        val selected = sessions.values.filter { sessionId == null || it.sessionId == sessionId }
        val capturedRows = rows.filter { sessionId == null || it.snapshot.playback.sessionId == sessionId }
        val header = linkedMapOf<String, Any?>("type" to "summary",
            "tickSemantics" to "CONTROLLER_OBSERVATIONS_INCLUDING_AFTER_DELIVERY_PUBLICATIONS",
            "totalTicks" to selected.sumOf { it.totalTicks }, "playingTicks" to selected.sumOf { it.playingTicks },
            "unexpectedNullTicks" to selected.sumOf { it.unexpectedNullTicks },
            "allSignalNullTicks" to selected.sumOf { it.allSignalNullTicks },
            "sessionSummaries" to selected.map { session -> linkedMapOf(
                "sessionId" to session.sessionId, "scenarioId" to session.scenarioId,
                "firstCapturedElapsedMs" to session.firstCapturedElapsedMs,
                "lastCapturedElapsedMs" to session.lastCapturedElapsedMs,
                "finalPhase" to session.finalPhase.name, "finalScenarioMs" to session.finalScenarioMs,
                "totalTicks" to session.totalTicks, "playingTicks" to session.playingTicks,
                "unexpectedNullTicks" to session.unexpectedNullTicks,
                "allSignalNullTicks" to session.allSignalNullTicks,
                "unexpectedNullPublishedFrames" to session.unexpectedNullPublishedFrames,
                "allSignalNullPublishedFrames" to session.allSignalNullPublishedFrames,
            ) },
            "detailedRows" to capturedRows.size, "detailIntervalRealMs" to 100, "retentionRealMs" to 120_000,
            "scope" to "DEBUG_JSON_AND_FAKE_REAL_ONLY")
        return CapturedTrace(sessionId ?: selected.lastOrNull()?.sessionId ?: 0,
            selected.lastOrNull()?.lastCapturedElapsedMs ?: 0, header, capturedRows)
    }

    /** 기존 호출자 호환용이다. 재생 화면은 capture() 뒤 IO에서 records()를 호출한다. */
    fun export(): List<Map<String, Any?>> = capture().records()

    companion object {
        fun write(file: File, capture: CapturedTrace, metadata: Map<String, Any?> = emptyMap()) =
            write(file, capture.records(metadata))

        /** 임시 파일을 완성한 뒤 바꾼다. 실패하면 기존 완료 trace를 보존한다. */
        fun write(file: File, records: List<Map<String, Any?>>) {
            val gson = GsonBuilder().serializeNulls().create()
            val temporary = File.createTempFile(".${file.name}.writing-", ".jsonl", file.absoluteFile.parentFile)
            try {
                temporary.bufferedWriter(Charsets.UTF_8).use { out -> records.forEach { out.appendLine(gson.toJson(it)) } }
                check(temporary.renameTo(file)) { "trace 파일 교체 실패" }
            } finally { temporary.delete() }
        }
    }
}

private fun DebugTraceRow.asMap(): Map<String, Any?> {
    val s = snapshot
    val p = s.playback
    fun quality(q: SignalQuality?): Map<String, Any?> {
        val observedAge = q?.observedAtMs?.let { at -> when (q.observedTimeBasis) {
            ObservationTimeBasis.SCENARIO -> currentScenarioMs - at
            ObservationTimeBasis.ELAPSED_REALTIME -> capturedElapsedMs - at
            else -> null
        } }
        return linkedMapOf("availability" to q?.availability?.name, "source" to q?.source?.name,
            "observedAtMs" to q?.observedAtMs, "observedTimeBasis" to q?.observedTimeBasis?.name,
            "observedAgeMs" to observedAge, "receivedAtElapsedMs" to q?.receivedAtElapsedMs,
            "receiptAgeRealMs" to q?.receivedAtElapsedMs?.let { capturedElapsedMs - it },
            "sourceRateHz" to q?.sourceRateHz, "sourceRateInfo" to q?.sourceRateInfo,
            "selectionReason" to q?.selectionReason?.name, "observationSequence" to q?.observationSequence)
    }
    fun fields(v: Map<SignalField, SignalQuality>, n: Map<NaviField, SignalQuality>, power: SignalQuality?) = linkedMapOf(
        "SPEED_KPH" to quality(v[SignalField.SPEED_KPH]),
        "STEERING_DEG" to quality(v[SignalField.STEERING_DEG]),
        "GEAR" to quality(v[SignalField.GEAR]),
        "LAT" to quality(n[NaviField.LAT]), "LON" to quality(n[NaviField.LON]),
        "POWER" to quality(power),
    )
    val inputs = Source.entries.associate { source ->
        val input = s.diagnostics.inputs[source]
        source.name to linkedMapOf("vehicleCallbacks" to input?.vehicleCallbacks,
            "steeringRate" to input?.steeringRate,
            "collection" to linkedMapOf("vehicle" to input?.collection?.vehicle?.name,
                "navi" to input?.collection?.navi?.name, "power" to input?.collection?.power?.name),
            "quality" to fields(input?.vehicleQuality.orEmpty(), input?.naviQuality.orEmpty(), input?.powerQuality))
    }
    return linkedMapOf("type" to "sample", "classification" to classification,
        "capturedElapsedMs" to capturedElapsedMs, "scenarioNowMs" to currentScenarioMs,
        "playbackScenarioMs" to p.scenarioTimeMs, "playbackRealElapsedMs" to p.realElapsedMs,
        "timeAxisMode" to "SOURCE_HANDOFF_CAPPED",
        "targetScenarioMs" to p.scenarioTimeMs,
        "handoffCompletedScenarioMs" to p.lastOriginalSampleMs,
        "replayStartedAtElapsedMs" to s.replayStartedAtElapsedMs,
        "processingCompletedAtElapsedMs" to s.processingCompletedAtElapsedMs,
        "actualProcessingElapsedMs" to s.replayStartedAtElapsedMs?.let { start ->
            (s.processingCompletedAtElapsedMs ?: capturedElapsedMs) - start },
        "transportMode" to s.transportMode.name, "sourcePreparation" to s.sourcePreparation, "workerBackend" to s.workerBackend, "detailedTimingEnabled" to s.detailedTimingEnabled, "scenarioId" to p.scenarioId, "scenarioDurationMs" to s.durationMs,
        "phase" to p.phase.name, "playing" to (p.phase == DemoPhase.PLAYING), "sessionId" to p.sessionId,
        "segmentMultiplier" to p.segmentMultiplier, "speedMultiplier" to p.speedMultiplier,
        "questionId" to p.questionRequestId?.value, "questionPhase" to s.questionPhase.name,
        "questionOutcome" to p.questionOutcome?.name, "deliveredSamples" to p.deliveredSamples,
        "lastOriginalSampleMs" to p.lastOriginalSampleMs, "lastDeliveryElapsedMs" to p.lastDeliveryElapsedMs,
        "pumpTargetMs" to p.pumpTargetMs, "pumpFinishedScenarioMs" to p.pumpFinishedScenarioMs,
        "deliveryAudit" to s.deliveryAudit, "performanceStages" to s.performanceStages,
        "originalLagScenarioMs" to p.lastOriginalSampleMs?.let { currentScenarioMs - it },
        "hubFrameTimeMs" to s.frame?.tMs, "hubPublishedAtElapsedMs" to s.frame?.publication?.publishedAtElapsedMs,
        "hubNextPublicationAtMs" to s.diagnostics.nextPublicationAtMs,
            "hubPublicationTimeBasis" to s.diagnostics.publicationTimeBasis.name,
            "hubRunning" to s.diagnostics.running, "hubClockFailure" to s.diagnostics.clockFailure,
        "hubEmittedFrames" to s.diagnostics.emittedFrames, "hubSkippedDeadlines" to s.diagnostics.skippedDeadlines,
        "unexpectedNullFields" to nullFieldNames(unexpectedNullMask), "inputs" to inputs,
        "finalValues" to linkedMapOf("speedKph" to s.frame?.speedKph, "steeringDeg" to s.frame?.steeringDeg,
            "gear" to s.frame?.gear?.name, "lat" to s.navi?.lat, "lon" to s.navi?.lon,
            "power" to s.power?.state?.name),
        "finalQuality" to fields(s.frame?.quality.orEmpty(), s.navi?.quality.orEmpty(), s.power?.quality))
}

private fun nullFieldNames(mask: Int): List<String> = buildList {
    if (mask and 1 != 0) add("SPEED_KPH")
    if (mask and 2 != 0) add("STEERING_DEG")
    if (mask and 4 != 0) add("GEAR")
    if (mask and 8 != 0) add("LAT")
    if (mask and 16 != 0) add("LON")
}
