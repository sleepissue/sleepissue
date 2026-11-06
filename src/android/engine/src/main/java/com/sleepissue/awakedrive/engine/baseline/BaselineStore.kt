package com.sleepissue.awakedrive.engine.baseline

import com.sleepissue.awakedrive.engine.config.BaselineConfig
import com.sleepissue.awakedrive.engine.model.BaselineRecord
import com.sleepissue.awakedrive.engine.model.BaselineSource
import com.sleepissue.awakedrive.engine.model.BaselineState
import com.sleepissue.awakedrive.engine.model.SteeringBaseline

/**
 * 기준선 저장소: 기본값·평소의 나·오늘의 나 가운데 지금 비교할 기준을 고르고, 출발 피로와 민감 모드를 정한다.
 * (REQ_BSL_003~007, 평소의 나 중앙값은 REQ_BSL_001)
 *
 * 기준 고르기
 * - 오늘의 나 확정 전: 평소의 나, 깨어 있던 운행 기록이 3회 미만이면 기본값 [해석] (REQ_BSL_003·004 비고)
 * - 유효 구간 15분이 쌓이면 오늘의 나를 확정하고, 그때 평소의 나(없으면 기본값)와 비교해 출발 피로를 판정한다 (REQ_BSL_005)
 * - 민감 모드(출발 피로 또는 '졸려요')면 평소의 나(없으면 기본값)로 고정, 아니면 오늘의 나 (REQ_BSL_006, 기획서 5-1)
 * - 처음 15분 가운데 10% 이상이 조향 원천 5 Hz 미만이었으면 오늘의 나를 비교 기준으로 쓰지 않는다 (REQ_SIG_009) [해석].
 *   흐린 신호로 만든 기준에 졸린 조향이 섞여 '정상'이 되는 것을 막는다
 *
 * 운행 기록(Room)은 [loadHistory]로 넣는다. 이번 운행 상태는 FatigueEngine이 프레임마다 채운다.
 * 결과는 [state]로 읽는다. 값이 바뀔 때만 새 BaselineState가 된다.
 */
class BaselineStore(private val config: BaselineConfig = BaselineConfig()) {

    private var usual: SteeringBaseline? = null
    private var today: SteeringBaseline? = null
    /** 오늘의 나를 만든 15분 동안 조향 원천 갱신이 충분했는지 (REQ_SIG_009). false면 비교 기준으로 쓰지 않음 */
    private var todayUsable = true
    private var departureFatigue = false
    private var reportedSleepy = false
    private var sensitiveSinceMs: Long? = null
    private var driveStartMs: Long? = null

    /** 이번 운행에서 게이트를 통과한 시간(ms). 15분이 되면 오늘의 나를 확정한다 (REQ_BSL_004) */
    var todayGatedMs: Long = 0L
        private set

    /** 지금 기준선·민감 모드 상태 */
    var state: BaselineState = buildState()
        private set

    /** 평소 보조 주행 해제 직후 30초 동안의 큰 수정 횟수 (REQ_AST_007). 기록이 없으면 null → '3회 이상' 기준 */
    var usualHandoverLargeCount: Float? = null
        private set

    /**
     * 지난 운행 기록을 넣어 평소의 나를 계산한다 (REQ_BSL_001·003).
     * 앱을 켤 때, 운행이 끝나 기록이 늘었을 때 부른다. 진행 중인 운행의 오늘의 나는 그대로 둔다.
     */
    fun loadHistory(records: List<BaselineRecord>) {
        usual = usualOf(records, config)
        usualHandoverLargeCount = usualHandoverLargeCountOf(records, config)
        refresh()
    }

    /**
     * 출발 전 컨디션을 넣는다 (REQ_BSL_007). '졸려요'면 [sleepy] = true → 바로 민감 모드.
     * '개운해요'·'보통'이면 false → '졸려요' 때문에 켠 민감 모드를 끈다(출발 피로로 켠 것은 유지).
     * "출발 전에 15분 쉬었다 가는 건 어때요?" 권유는 컨디션을 받는 쪽(음성·화면)이 '졸려요'일 때 한 번 말한다.
     */
    fun reportCondition(sleepy: Boolean, tMs: Long) {
        reportedSleepy = sleepy
        updateSensitive(tMs)
        refresh()
    }

    /** D단 프레임이 들어올 때마다 FatigueEngine이 부른다. 이번 운행의 첫 D단 시각만 출발 시각으로 남긴다. */
    internal fun onDriveFrame(tMs: Long) {
        if (driveStartMs != null) return
        driveStartMs = tMs
        refresh()
    }

    /**
     * 이번 운행의 누적 지표를 넣는다 (REQ_BSL_004·005). FatigueEngine이 게이트 판정마다 부른다.
     * 게이트 통과가 15분 쌓인 순간의 횟수로 오늘의 나를 만들고 출발 피로를 판정한다. 확정 뒤에는 아무것도 하지 않는다.
     *
     * @param nowMs 지금 시각(마지막으로 넣은 프레임 시각). 민감 모드 시작·첫 음성 확인 시각의 기준
     * @param microReliable 처음 15분의 미세 수정을 믿을 수 있는지 (조향 원천이 느렸던 비율 10% 미만, REQ_SIG_009).
     *   false면 출발 피로에서 미세 수정 조건을 세지 않고, 오늘의 나를 기준으로 쓰지 않는다 [해석]
     */
    internal fun updateToday(
        nowMs: Long,
        gatedMs: Long,
        microCount: Int,
        largeCount: Int,
        suddenCount: Int,
        microReliable: Boolean = true,
    ) {
        if (today != null) return
        todayGatedMs = gatedMs
        if (gatedMs < config.todayBaselineGatedMs) return

        val perMinute = MINUTE_MS.toDouble() / gatedMs
        val confirmed = SteeringBaseline(
            microPerMin = (microCount * perMinute).toFloat(),
            largePerMin = (largeCount * perMinute).toFloat(),
        )
        today = confirmed
        todayUsable = microReliable
        departureFatigue = isDepartureFatigue(
            confirmed, suddenCount, comparable(usual ?: config.defaultBaseline, config), config, microReliable,
        )
        updateSensitive(nowMs)
        refresh()
    }

    /** 새 운행(꺼져 있던 시간 2시간 초과, REQ_TRP_008): 오늘의 나·출발 피로·컨디션을 지운다. 평소의 나는 그대로 둔다. */
    internal fun resetTrip() {
        today = null
        todayUsable = true
        departureFatigue = false
        reportedSleepy = false
        sensitiveSinceMs = null
        driveStartMs = null
        todayGatedMs = 0L
        refresh()
    }

    private fun updateSensitive(nowMs: Long) {
        val sensitive = departureFatigue || reportedSleepy
        if (!sensitive) {
            sensitiveSinceMs = null
        } else if (sensitiveSinceMs == null) {
            sensitiveSinceMs = nowMs
        }
    }

    private fun refresh() {
        val next = buildState()
        if (next != state) state = next
    }

    private fun buildState(): BaselineState {
        val usualValue = usual
        val todayValue = today
        val sensitive = departureFatigue || reportedSleepy
        val (source, chosen) = when {
            todayValue != null && todayUsable && !sensitive -> BaselineSource.TODAY to todayValue
            usualValue != null -> BaselineSource.USUAL to usualValue
            else -> BaselineSource.DEFAULT to config.defaultBaseline
        }
        val reference = comparable(chosen, config)
        val since = sensitiveSinceMs
        val start = driveStartMs
        val firstCheckDue = if (since != null && start != null) {
            maxOf(since, start) + config.sensitiveFirstCheckDelayMs
        } else {
            null
        }
        return BaselineState(
            source = source,
            reference = reference,
            today = todayValue,
            departureFatigue = departureFatigue,
            reportedSleepy = reportedSleepy,
            sensitiveSinceMs = since,
            firstCheckDueMs = firstCheckDue,
        )
    }

    companion object {
        private const val MINUTE_MS = 60_000L

        /**
         * 비교에 쓸 기준선: 큰 수정이 [BaselineConfig.minReferenceLargePerMin]보다 작으면 그 값으로 올린다 [제안값].
         * 오늘의 나(state.today)에 저장하는 값은 잰 그대로 두고, 비교할 때만 올린다.
         */
        fun comparable(baseline: SteeringBaseline, config: BaselineConfig = BaselineConfig()): SteeringBaseline =
            if (baseline.largePerMin >= config.minReferenceLargePerMin) {
                baseline
            } else {
                baseline.copy(largePerMin = config.minReferenceLargePerMin)
            }

        /** 비율 경계(−30.0%, 1.50배)에서 Float 오차로 판정이 뒤집히지 않게 하는 여유 */
        private const val RATIO_EPS = 1e-6

        /**
         * 평소의 나 = 깨어 있던(validAwake) 최근 운행 10회의 미세·큰 수정 분당 횟수 중앙값 (REQ_BSL_001).
         * 그런 운행이 3회 미만이면 null → 기본값을 쓴다 (REQ_BSL_003). 짝수 개면 가운데 두 값의 평균.
         * '최근'은 tripId가 큰 쪽이다 [가정: tripId는 운행마다 커진다].
         */
        fun usualOf(records: List<BaselineRecord>, config: BaselineConfig = BaselineConfig()): SteeringBaseline? {
            val recent = records.filter { it.validAwake }.sortedBy { it.tripId }.takeLast(config.usualRecentTrips)
            if (recent.isEmpty() || recent.size < config.minTripsForUsual) return null
            return SteeringBaseline(
                microPerMin = median(recent.map { it.microPerMin }),
                largePerMin = median(recent.map { it.largePerMin }),
            )
        }

        /**
         * 평소 해제 직후 30초 큰 수정 횟수 = 깨어 있던 최근 운행 10회의 handoverLargeCount 중앙값 (REQ_AST_007) [해석].
         * 값이 있는 기록이 하나도 없으면 null.
         */
        fun usualHandoverLargeCountOf(records: List<BaselineRecord>, config: BaselineConfig = BaselineConfig()): Float? {
            val counts = records.filter { it.validAwake }
                .sortedBy { it.tripId }
                .mapNotNull { it.handoverLargeCount }
                .takeLast(config.usualRecentTrips)
            if (counts.isEmpty()) return null
            return median(counts.map { it.toFloat() })
        }

        /**
         * 출발 피로 조건 3개 가운데 몇 개를 만족하는지 (REQ_BSL_005).
         * - 미세 수정이 기준보다 30% 이상 적음 (−30.0% → 만족, −29.9% → 아님). 기준이 0이면 줄어들 수 없으므로 아님
         * - 큰 수정이 기준의 1.5배 이상 (1.50배 → 만족). 기준이 0인데 오늘 큰 수정이 있으면 만족 [해석, f큰과 같음]
         * - 급조향이 1회 이상
         *
         * [microReliable]이 false(조향 원천 갱신이 5 Hz 미만, REQ_SIG_009)면 미세 수정 조건은 세지 않는다.
         * 남은 두 조건을 모두 만족해야 출발 피로가 된다 ('독립된 징후 2개'라는 뜻을 지킴) [해석].
         */
        fun departureCriteriaCount(
            today: SteeringBaseline,
            suddenCount: Int,
            reference: SteeringBaseline,
            config: BaselineConfig = BaselineConfig(),
            microReliable: Boolean = true,
        ): Int {
            val microDropped = microReliable && reference.microPerMin > 0f &&
                1.0 - today.microPerMin.toDouble() / reference.microPerMin >= config.departureMicroDropRatio - RATIO_EPS
            val largeRose = if (reference.largePerMin > 0f) {
                today.largePerMin.toDouble() / reference.largePerMin >= config.departureLargeRiseRatio - RATIO_EPS
            } else {
                today.largePerMin > 0f
            }
            val suddenSeen = suddenCount >= config.departureSuddenMinCount
            return listOf(microDropped, largeRose, suddenSeen).count { it }
        }

        /** 조건 3개 가운데 2개 이상이면 출발 피로 (REQ_BSL_005) */
        fun isDepartureFatigue(
            today: SteeringBaseline,
            suddenCount: Int,
            reference: SteeringBaseline,
            config: BaselineConfig = BaselineConfig(),
            microReliable: Boolean = true,
        ): Boolean =
            departureCriteriaCount(today, suddenCount, reference, config, microReliable) >= config.departureMinCriteria

        private fun median(values: List<Float>): Float {
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) {
                sorted[mid]
            } else {
                ((sorted[mid - 1].toDouble() + sorted[mid]) / 2.0).toFloat()
            }
        }
    }
}
