package com.sleepissue.awakedrive.engine.rest

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.Gear
import com.sleepissue.awakedrive.engine.model.GeoPoint
import com.sleepissue.awakedrive.engine.model.InterventionStage
import com.sleepissue.awakedrive.engine.model.PowerState

/**
 * 휴식 인증 판단에 쓰는 한 번의 관측 (앱이 1초쯤마다 넣는다).
 * @param tMs 운행 논리 시각(Clock.scenarioTimeMs). 데모 배속이 그대로 적용된다
 * @param location 내비 현재 위치. 시동이 꺼져 못 받으면 null → 마지막으로 받은 위치를 쓴다
 * @param continuousDriveSec 1번 TripState의 연속 운전 시간. 보상 조건 ①(2시간 운전 뒤)에 쓴다
 * @param stage 1번 개입 단계. 보상 조건 ②(2단계 이상 알림 뒤)에 쓴다
 */
data class RestObservation(
    val tMs: Long,
    val location: GeoPoint?,
    val gear: Gear?,
    val power: PowerState?,
    val continuousDriveSec: Long? = null,
    val stage: InterventionStage? = null,
)

sealed interface RestEvent {
    val sessionId: String
    val area: RestArea

    /** 쉼터 반경 안에서 P단(또는 시동 꺼짐)이 시작됐다 → 15분 타이머·도착 안내·휴식 화면. */
    data class Arrived(override val sessionId: String, override val area: RestArea, val startedAtMs: Long) : RestEvent

    /**
     * 15분 연속이 채워졌다. [certifiedAtMs]는 실제로 15분이 찬 논리 시각이다.
     * 시동을 끈 채로 15분이 지나 켜졌을 때 이 이벤트가 나므로 관측 시각보다 이를 수 있다.
     */
    data class Certified(
        override val sessionId: String,
        override val area: RestArea,
        val startedAtMs: Long,
        val certifiedAtMs: Long,
        val driveSecBeforeRest: Long?,
        val maxStageBeforeRest: InterventionStage,
    ) : RestEvent

    /** 쉼터를 떠났거나 D단으로 바꿨다. */
    data class Left(override val sessionId: String, override val area: RestArea, val certified: Boolean, val atMs: Long) : RestEvent
}

/**
 * [4번] 휴식 자동 인증 (기획서 7-1·7-2·7-3, SRS RES).
 *
 * 인증 조건: 쉼터·휴게소 반경 150 m 안 + P단(또는 시동 꺼짐) + 15분 이상 연속.
 * 운전자가 실제로 쉬었는지·잤는지는 판단하지 않는다. 파워냅도 쉼터에서 P단이면 같은 규칙으로 인증된다.
 *
 * ROS로 치면 콜백 하나짜리 노드다. [observe]에 관측을 넣으면 그 순간 생긴 이벤트 목록을 돌려준다.
 * 시동이 꺼져 관측이 끊겨도, 다시 들어온 관측의 시각 차이로 15분을 판단한다(꺼져 있던 시간 = 휴식 시간).
 *
 * 한계: 상태는 메모리에만 둔다. 절전(Suspend-to-RAM) 복귀는 그대로 이어지지만,
 * 완전 종료 뒤 재부팅에서 이어 가려면 1번 상태 저장(10초 주기)에 이 상태를 붙여야 한다(후속).
 */
class RestCertifier(
    private val areas: List<RestArea>,
    private val config: EngineConfig = EngineConfig(),
) {
    private data class Session(
        val id: String,
        val area: RestArea,
        val startedAtMs: Long,
        val driveSecBeforeRest: Long?,
        val maxStageBeforeRest: InterventionStage,
        val certified: Boolean = false,
    )

    private var session: Session? = null
    private var lastLocation: GeoPoint? = null
    private var lastObservedMs: Long? = null
    // 직전 인증(또는 앱 시작) 뒤 받은 가장 높은 단계 — 보상 조건 ②
    private var maxStageSinceRest = InterventionStage.NORMAL

    /** 지금 쉼터에서 쉬는 중이면 그 쉼터. 화면의 15분 타이머용. */
    val currentArea: RestArea? get() = session?.area
    val currentStartedAtMs: Long? get() = session?.startedAtMs
    val currentCertified: Boolean get() = session?.certified == true

    fun observe(o: RestObservation): List<RestEvent> {
        // 시간이 거꾸로 가는 관측(늦게 도착한 콜백 등)은 버린다.
        if (lastObservedMs?.let { o.tMs < it } == true) return emptyList()
        lastObservedMs = o.tMs
        o.location?.let { lastLocation = it }
        val location = lastLocation
        val stopped = o.power == PowerState.OFF || o.gear == Gear.P
        val events = mutableListOf<RestEvent>()

        session?.let { s ->
            val stillHere = stopped && location != null && within(location, s.area)
            if (!stillHere) {
                events += RestEvent.Left(s.id, s.area, s.certified, o.tMs)
                session = null
            } else if (!s.certified && o.tMs - s.startedAtMs >= config.restCertifyMinMs) {
                session = s.copy(certified = true)
                maxStageSinceRest = InterventionStage.NORMAL
                events += RestEvent.Certified(
                    sessionId = s.id,
                    area = s.area,
                    startedAtMs = s.startedAtMs,
                    certifiedAtMs = s.startedAtMs + config.restCertifyMinMs,
                    driveSecBeforeRest = s.driveSecBeforeRest,
                    maxStageBeforeRest = s.maxStageBeforeRest,
                )
            }
        }

        if (session == null) {
            // 쉬는 중이 아닐 때만 단계를 쌓는다. 인증 직후에도 1번은 회복 확인 전까지 3단계를 유지하므로,
            // 쉬는 동안의 단계를 세면 다음 휴식이 저절로 보상 대상이 되어 버린다.
            o.stage?.let { if (it.level > maxStageSinceRest.level) maxStageSinceRest = it }
            if (stopped && location != null) {
                nearest(location)?.let { area ->
                    val s = Session(
                        id = "${area.id}@${o.tMs}",
                        area = area,
                        startedAtMs = o.tMs,
                        // 멈춘 첫 관측에서는 1번이 아직 연속 운전 시간을 0으로 돌리기 전이다.
                        driveSecBeforeRest = o.continuousDriveSec,
                        maxStageBeforeRest = maxStageSinceRest,
                    )
                    session = s
                    events += RestEvent.Arrived(s.id, area, o.tMs)
                }
            }
        }
        return events
    }

    /** 반경 안에서 가장 가까운 쉼터. 없으면 null. */
    fun nearest(location: GeoPoint): RestArea? =
        areas.asSequence()
            .map { it to GeoMath.distanceM(location, it.location) }
            .filter { it.second <= config.restCertifyRadiusM }
            .minByOrNull { it.second }
            ?.first

    private fun within(location: GeoPoint, area: RestArea) =
        GeoMath.distanceM(location, area.location) <= config.restCertifyRadiusM
}
