package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.model.TripState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 메모리 상태 보관함이다. 정차·누적 규칙은 외부에서 계산하고 DB 저장은 수행하지 않는다. */
class TripStateStore(initialState: TripState = TripState()) {
    private val stateLock = Any()
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<TripState> = mutableState.asStateFlow()

    fun replace(nextState: TripState) = synchronized(stateLock) {
        mutableState.value = nextState
    }

    /** 같은 상태의 갱신을 직렬화한다. transform 안에서 대기·I/O를 실행하지 않는다. */
    fun update(transform: (TripState) -> TripState): TripState =
        transaction { current -> transform(current).let { it to it } }

    /**
     * 운행의 정밀 상태와 공개 상태를 함께 바꾸기 위한 내부 연결점이다.
     * CAS 재시도 없이 transform을 정확히 한 번 호출해 운행 ID가 중복 생성되지 않게 한다.
     * 예외가 발생하면 공개 상태는 변경하지 않는다.
     */
    internal fun <R> transaction(transform: (TripState) -> Pair<TripState, R>): R = synchronized(stateLock) {
        val (next, result) = transform(mutableState.value)
        mutableState.value = next
        result
    }
}
