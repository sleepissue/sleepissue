package com.sleepissue.awakedrive.session

import com.sleepissue.awakedrive.engine.model.GeoPoint
import com.sleepissue.awakedrive.engine.model.InterventionStage
import com.sleepissue.awakedrive.engine.rest.RestEvent
import com.sleepissue.awakedrive.engine.rest.RewardHistoryEntry
import com.sleepissue.awakedrive.engine.rest.RewardOutcome
import com.sleepissue.awakedrive.engine.rest.RewardRules
import com.sleepissue.awakedrive.engine.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 휴식 인증 기록 한 줄. 보상을 못 받은 인증도 남긴다(리포트·악용 방지 판단에 씀).
 * 개인정보: 위치는 쉼터 좌표만 남기고 차의 실제 경로는 남기지 않는다.
 */
data class RestRecord(
    val sessionId: String,
    val areaId: String,
    val areaName: String,
    val location: GeoPoint,
    val certifiedAtWallMs: Long,
    val driveSecBeforeRest: Long?,
    val maxStageBeforeRest: InterventionStage,
    val outcome: RewardOutcome,
) {
    val rewarded: Boolean get() = outcome == RewardOutcome.AWARDED
}

/** 기록 저장소. 실제는 Room(`data/RestRecordDatabase`), 테스트는 메모리. */
interface RestRecordStore {
    suspend fun all(): List<RestRecord>
    /** 같은 sessionId가 이미 있으면 넣지 않고 false. */
    suspend fun insert(record: RestRecord): Boolean
    suspend fun deleteAll()
    fun observe(): Flow<List<RestRecord>>
}

class InMemoryRestRecordStore : RestRecordStore {
    private val rows = MutableStateFlow<List<RestRecord>>(emptyList())
    override suspend fun all() = rows.value
    override suspend fun insert(record: RestRecord): Boolean {
        if (rows.value.any { it.sessionId == record.sessionId }) return false
        rows.value = rows.value + record
        return true
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
    override fun observe(): Flow<List<RestRecord>> = rows.asStateFlow()
}

/**
 * [4번] RewardLedger — 휴식 인증을 기록하고 스탬프(보상)를 줄지 정한다 (기획서 7-3, SRS RWD).
 * 판단은 엔진 [RewardRules], 이 클래스는 기록을 읽어 넘기고 결과를 저장만 한다.
 */
class RewardLedger(
    private val store: RestRecordStore,
    private val clock: Clock,
    private val rules: RewardRules = RewardRules(),
) {
    private val lock = Mutex()

    /** 휴식 화면의 스탬프 목록. */
    val stamps: Flow<List<RestRecord>> = store.observe().map { list -> list.filter { it.rewarded } }
    val records: Flow<List<RestRecord>> = store.observe()

    /**
     * 같은 휴식을 두 번 넣으면 처음 기록을 그대로 돌려준다.
     * 인증 시각은 날짜 시각으로 남긴다 [가정] — 시동을 껐다 켠 직후 기록하면 실제 15분 시점보다 조금 늦게 찍힌다.
     */
    suspend fun record(certified: RestEvent.Certified): RestRecord = lock.withLock {
        val history = store.all()
        history.firstOrNull { it.sessionId == certified.sessionId }?.let { return@withLock it }
        val wallMs = clock.wallTimeMs()
        val decision = rules.evaluate(
            areaId = certified.area.id,
            location = certified.area.location,
            certifiedAtWallMs = wallMs,
            driveSecBeforeRest = certified.driveSecBeforeRest,
            maxStageBeforeRest = certified.maxStageBeforeRest,
            history = history.map { RewardHistoryEntry(it.areaId, it.location, it.certifiedAtWallMs, it.rewarded) },
        )
        val record = RestRecord(
            sessionId = certified.sessionId,
            areaId = certified.area.id,
            areaName = certified.area.name,
            location = certified.area.location,
            certifiedAtWallMs = wallMs,
            driveSecBeforeRest = certified.driveSecBeforeRest,
            maxStageBeforeRest = certified.maxStageBeforeRest,
            outcome = decision.outcome,
        )
        store.insert(record)
        record
    }

    /** 설정의 '기록 삭제' (기획서 9장 개인정보). */
    suspend fun deleteAll() = store.deleteAll()
}
