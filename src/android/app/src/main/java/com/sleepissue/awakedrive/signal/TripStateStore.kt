package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.model.TripState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

/** A의 메모리 상태 보관함이다. 정차·누적 계산이나 DB 저장을 수행하지 않는다. */
class TripStateStore(initialState: TripState = TripState()) {
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<TripState> = mutableState.asStateFlow()

    fun replace(nextState: TripState) {
        mutableState.value = nextState
    }

    /** 경합하면 transform이 재실행될 수 있으므로 부수 효과 없는 함수를 전달한다. */
    fun update(transform: (TripState) -> TripState): TripState =
        mutableState.updateAndGet(transform)
}
