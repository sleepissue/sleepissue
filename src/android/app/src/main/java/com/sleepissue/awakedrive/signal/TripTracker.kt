package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.model.PowerEvent
import com.sleepissue.awakedrive.engine.model.SignalAvailability
import com.sleepissue.awakedrive.engine.model.SignalField
import com.sleepissue.awakedrive.engine.model.SignalFrame
import com.sleepissue.awakedrive.engine.model.Source
import com.sleepissue.awakedrive.engine.model.TripState
import com.sleepissue.awakedrive.engine.session.TripObservation
import com.sleepissue.awakedrive.engine.session.TripRuleResult
import com.sleepissue.awakedrive.engine.session.TripRuleState
import com.sleepissue.awakedrive.engine.session.TripRules
import kotlinx.coroutines.flow.StateFlow

/**
 * Hub 관측을 순수 운행 규칙에 연결하고 초 단위 상태를 메모리 보관함에 투영한다.
 * REAL은 Playground SDK·Virtual Console, DEMO는 JSON 재생 입력이다.
 * 출처 전환·실제값 우선권·복원은 자동 결정하지 않는다. 호출자가 세션 출처를 고른다.
 * 별도 코루틴·서비스·저장소를 소유하지 않으며 호출 중 블로킹/I/O를 수행하지 않는다.
 */
class TripTracker(
    private val rules: TripRules,
    private val store: TripStateStore,
    val sessionSource: Source,
    initialRuleState: TripRuleState = TripRuleState(),
) {
    // 이 필드의 모든 접근은 Store의 단일 transaction 안에서만 한다.
    private var ruleState = initialRuleState
    val state: StateFlow<TripState> = store.state

    init {
        store.transaction { current ->
            require(sameOwnedState(current, initialRuleState.trip)) {
                "메모리 운행 상태와 초기 정밀 상태가 다릅니다. 정밀 상태를 명시적으로 주입해 주세요."
            }
            current to Unit
        }
    }

    /**
     * 기어/전원의 필드별 출처가 세션과 맞는 관측만 받는다.
     * 일치하는 출처가 전혀 없으면 null을 반환하며 논리 시각·운행 상태를 바꾸지 않는다.
     * 같은 출처의 미지원·미수신·오래된 값은 미관측으로 넣어 이전 기어를 현재 판단에 재사용하지 않는다.
     * 전원을 함께 넣을 때는 같은 논리 시각의 관측이어야 한다.
     */
    fun accept(frame: SignalFrame, power: PowerEvent? = null): TripRuleResult? {
        val gearQuality = frame.qualityOf(SignalField.GEAR)
        val gearMatches = gearQuality.source == sessionSource
        val matchingPower = power?.takeIf { it.quality.source == sessionSource }
        if (!gearMatches && matchingPower == null) return null
        require(matchingPower == null || matchingPower.tMs == frame.tMs) {
            "프레임과 전원 관측은 같은 논리 시각으로 결합하거나 observePower로 따로 전달해야 합니다."
        }
        val gear = frame.gear.takeIf {
            gearMatches && gearQuality.availability == SignalAvailability.AVAILABLE
        }
        val powerState = matchingPower
            ?.takeIf { it.quality.availability == SignalAvailability.AVAILABLE }
            ?.state
        return applyObservation(TripObservation(frame.tMs, gear, powerState))
    }

    /** 전원만 관측된 ON/RESUME 뒤에는 이전 D/P 기어를 끌어오지 않는다. */
    fun observePower(event: PowerEvent): TripRuleResult? {
        if (event.quality.source != sessionSource) return null
        val power = event.state.takeIf { event.quality.availability == SignalAvailability.AVAILABLE }
        return applyObservation(TripObservation(event.tMs, gear = null, power = power))
    }

    /**
     * 세션 소유자가 Hub의 실패·중지·관측 중단을 알릴 때 호출한다.
     * 출처가 다른 프레임과 구분하며, 입력 중단을 시동 OFF로 꾸미지 않는다.
     * 오래된 관측의 유효기간을 판단하는 타이머는 여기서 만들지 않는다.
     */
    fun markInputUnavailable(tMs: Long): TripRuleResult =
        applyObservation(TripObservation(tMs, gear = null, power = null))

    /** 초 단위 투영으로 잃을 수 있는 밀리초·정차·구간 상태를 함께 제공한다. */
    fun preciseState(): TripRuleState = store.transaction { current ->
        check(sameOwnedState(current, ruleState.trip)) {
            "운행 소유 상태가 Tracker 외부에서 바뀌었습니다. 정밀 상태와 함께 다시 구성해 주세요."
        }
        current to ruleState.copy(trip = mergeExternalState(ruleState.trip, current))
    }

    private fun applyObservation(observation: TripObservation): TripRuleResult =
        store.transaction { current ->
            check(sameOwnedState(current, ruleState.trip)) {
                "운행 소유 상태가 Tracker 외부에서 바뀌었습니다. 정밀 상태와 함께 다시 구성해 주세요."
            }
            val previous = ruleState.copy(trip = mergeExternalState(ruleState.trip, current))
            // transaction은 한 번만 호출하므로 새 운행 ID 생성이 CAS 재시도로 중복되지 않는다.
            val result = rules.reduce(previous, observation)
            ruleState = result.state
            result.state.trip to result
        }

    private fun mergeExternalState(owned: TripState, current: TripState) = owned.copy(
        stage = current.stage,
        baselineRef = current.baselineRef,
        lastLocation = current.lastLocation,
    )

    private fun sameOwnedState(current: TripState, precise: TripState): Boolean =
        mergeExternalState(current, precise) == precise
}
