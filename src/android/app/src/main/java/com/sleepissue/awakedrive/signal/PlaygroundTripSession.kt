package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.session.*
import com.sleepissue.awakedrive.engine.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** SDK 세션의 운행 조립기다. 짧은 상태 갱신은 잠금으로 직렬화하고 DB I/O는 잠금 밖에서 실행한다. */
class PlaygroundTripSession(
    repository: TripRepository,
    private val clock: Clock,
    private val config: EngineConfig = EngineConfig(),
    private val newTripId: () -> String = { UUID.randomUUID().toString() },
) {
    // [가정] 기존 수동 검증과 같은 정차 정책. 제품 정책 확정과 구별한다.
    private var policy = TripRulePolicy(StopTimePolicy.V03_RETROACTIVE, ShortStopAccumulationPolicy.EXCLUDE)
    private var store = TripStateStore()
    private var tracker = tracker(TripRuleState())
    val persistence = TripSaveCoordinator(repository, clock, config)
    private val mutableTrip = MutableStateFlow(store.state.value)
    val trip = mutableTrip.asStateFlow()
    private val mutableEvents = MutableStateFlow<List<String>>(emptyList())
    val events = mutableEvents.asStateFlow()
    var restoredSnapshot: TripSnapshot? = null
        private set
    @Volatile var initialized = false
        private set
    @Volatile var generation = 0L
        private set
    @Volatile var connected = false
        private set
    @Volatile private var saveAllowed = false
    private val initializeMutex = Mutex()
    private val saveMutex = Mutex()
    private var publicationSession: Long? = null
    private var publicationSequence: Long? = null
    @Volatile var latestFrame: SignalFrame? = null
        private set

    private fun tracker(precise: TripRuleState) = TripTracker(TripRules(config, policy, newTripId), store, Source.REAL, precise)
    private fun event(text: String) { mutableEvents.update { (it + "${clock.wallTimeMs()} · $text").takeLast(100) } }

    suspend fun initialize() = initializeMutex.withLock {
        if (initialized) return@withLock
        // 소유자가 연결 버튼을 활성화하기 전에 복원을 마친다.
        when (val loaded = persistence.load()) {
            TripLoadResult.Empty -> { saveAllowed = true; event("SDK 전용 DB 비어 있음 · 새 입력 대기") }
            is TripLoadResult.Failed -> event("복원 실패 ${loaded.reason} · DB 보존/자동 저장 중지")
            is TripLoadResult.Loaded -> {
                restoredSnapshot = loaded.snapshot
                event("복원 전 ${describe(loaded.snapshot.preciseState!!)}")
                try {
                    val precise = RealTripPersistence.restore(loaded.snapshot, clock.scenarioTimeMs(), config)
                    policy = requireNotNull(loaded.snapshot.rulePolicy)
                    store = TripStateStore(precise.trip); tracker = tracker(precise)
                    mutableTrip.value = precise.trip
                    persistence.restored(loaded.snapshot); saveAllowed = true
                    event("복원 후 ${describe(precise)} · 현재 기어/전원 미확정, 앱 종료 공백 제외")
                } catch (e: RealTripRestoreException) {
                    persistence.restoredFailure(e.reason); event("복원 적용 실패 ${e.reason} · 원본 보존")
                }
            }
        }
        persistence.configure(SaveTimerBasis.ELAPSED_REALTIME, clock.scenarioTimeMs(), saveAllowed)
        initialized = true
    }

    @Synchronized fun beginInput(): Long {
        check(initialized)
        check(!connected) { "이미 연결된 SDK 세션입니다." }
        connected = true; generation++; publicationSession = null; publicationSequence = null; latestFrame = null
        // 재연결 전까지 관측된 상태를 현재 기어로 가져오지 않는다.
        suspendInput()
        event("SDK 입력 연결 세대 $generation · REAL 전용 · 전원 API 미연결")
        return generation
    }

    /** true이면 상태 전환 저장이 필요하다. 다른 출처/이전 세대는 시각도 전진시키지 않는다. */
    @Synchronized fun accept(token: Long, frame: SignalFrame): Boolean {
        if (!connected || token != generation || frame.qualityOf(SignalField.GEAR).source != Source.REAL) return false
        val previous = tracker.preciseState()
        if (previous.lastObservation?.tMs?.let { frame.tMs < it } == true) return false
        var interrupted = false
        val p = frame.publication
        if (p != null) {
            if (publicationSession != null && publicationSession != p.sessionId) return false
            if (publicationSequence?.let { p.sequence <= it } == true) return false
            if (publicationSequence?.let { p.sequence > it + 1 } == true) {
                suspendInput(); event("Hub 소비 순번 누락 · 마지막 확정 구간에서 관측 중단")
                interrupted = true
            }
            publicationSession = p.sessionId; publicationSequence = p.sequence
        }
        val q = frame.qualityOf(SignalField.GEAR)
        val previousQuality = latestFrame?.qualityOf(SignalField.GEAR)
        val previousLeaseEnd = previousQuality?.stateLeaseUntilElapsedMs
        val newConfirmation = q.stateConfirmedAtElapsedMs
        if (previousQuality?.availability == SignalAvailability.AVAILABLE && previousLeaseEnd != null &&
            newConfirmation != null && newConfirmation > previousLeaseEnd
        ) {
            // 절전 중에는 Hub가 발행하지 않아 순번이 연속일 수 있다. 새 조회 성공은
            // 만료된 이전 lease와의 사이를 증명하지 않으므로 마지막 정상 관측에서 끊는다.
            suspendInput(); interrupted = true
            event("기어 확인 lease 단절 · 관측 공백 제외 · 시동 OFF/휴식으로 해석하지 않음")
        }
        if (frame.gear == null || q.availability != SignalAvailability.AVAILABLE) {
            // 장애 발견 시각까지 이전 D/P가 유효했다고 소급하지 않는다.
            // 마지막 정상 프레임 이후 최대 한 tick은 보수적으로 합산하지 않는다.
            suspendInput()
        }
        tracker.accept(frame) // 전원은 실제 API 미연결: ON/OFF를 생성하지 않는다.
        latestFrame = frame
        val after = tracker.preciseState()
        mutableTrip.value = after.trip
        val changed = interrupted || previous.lastObservation?.gear != after.lastObservation?.gear ||
            previous.trip.driveState != after.trip.driveState || previous.trip.tripId != after.trip.tripId ||
            previous.restResetEmitted != after.restResetEmitted
        if (changed) event("SDK 상태 전환 ${describe(after)} · gear=${after.lastObservation?.gear} · ${q.availability}")
        return changed
    }

    /** 중지 직전 정밀 snapshot은 호출자가 먼저 캡처한다. 종료를 OFF로 저장하지 않는다. */
    @Synchronized fun endInput(token: Long) {
        if (!connected || generation != token) return
        connected = false; generation++; suspendInput(); latestFrame = null
        event("SDK 입력 중지 · 현재 UNKNOWN · 종료를 시동 OFF로 해석하지 않음")
    }

    /** 서비스 종료와 collector가 경합해도 마지막 유효 상태 캡처와 입력 차단은 한 번에 한다. */
    @Synchronized fun finishInputAndCapture(token: Long): TripSnapshot? {
        if (!connected || generation != token) return null
        val snapshot = capture()
        endInput(token)
        return snapshot
    }

    private fun suspendInput() {
        tracker.preciseState().lastObservation?.let { tracker.markInputUnavailable(it.tMs) }
        mutableTrip.value = tracker.state.value
    }

    @Synchronized fun capture(): TripSnapshot? {
        if (!saveAllowed || !connected) return null
        val precise = tracker.preciseState()
        if (precise.lastObservation == null) return null
        return RealTripPersistence.capture(precise, policy, clock)
    }

    suspend fun saveNow(snapshot: TripSnapshot? = capture()): Boolean = saveMutex.withLock {
        if (snapshot == null || !saveAllowed) return@withLock false
        if (persistence.state.value.lastSavedScenarioMs?.let { requireNotNull(snapshot.savedAtScenarioMs) < it } == true) {
            event("늦은 저장 요청 폐기 · 더 최신인 Room checkpoint 보존")
            return@withLock false
        }
        val success = persistence.saveNow(requireNotNull(snapshot.savedAtScenarioMs)) { snapshot }
        event("즉시 저장 ${if (success) "성공" else "실패 ${persistence.state.value.failure}"} · ${describe(snapshot.preciseState!!)}")
        success
    }

    suspend fun autoSave(): Boolean = saveMutex.withLock {
        val snapshot = capture() ?: return@withLock false
        val previousSaves = persistence.state.value.successfulSaves
        val success = persistence.maybeSave(requireNotNull(snapshot.savedAtScenarioMs)) { snapshot }
        if (success || persistence.state.value.operation == TripStorageOperation.SAVE_FAILED) {
            if (success || previousSaves == persistence.state.value.successfulSaves)
                event("실제 경과 10초 저장 ${if (success) "성공" else "실패"} · ${describe(snapshot.preciseState!!)}")
        }
        success
    }

    @Synchronized fun preciseState() = tracker.preciseState()
    private fun describe(s: TripRuleState) = "id=${s.trip.tripId} 운행=${s.trip.driveState} 연속=${s.continuousDriveMs}ms " +
        "정차=${s.stopStartedAtMs?.let { (s.lastObservation?.tMs ?: it) - it } ?: 0}ms 최근24h=${s.trip.last24hDriveSec}s"
}
