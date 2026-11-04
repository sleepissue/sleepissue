package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.model.NaviState
import com.sleepissue.awakedrive.engine.model.PowerEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/** 수동 진행형 가짜다. 테스트가 emit/fail을 호출할 때만 입력을 전달한다. */
class FakeSignalSource : VehicleSignalSource {
    private sealed interface Event {
        data class Sample(val input: VehicleSignalInput) : Event
        data object Failure : Event
    }

    private val events = MutableSharedFlow<Event>()
    var activeSubscriptions: Int = 0
        private set
    var totalSubscriptions: Int = 0
        private set

    override val samples: Flow<VehicleSignalInput> = flow {
        activeSubscriptions++
        totalSubscriptions++
        try {
            events.collect { event ->
                when (event) {
                    is Event.Sample -> emit(event.input)
                    Event.Failure -> throw IllegalStateException("시험용 입력 실패")
                }
            }
        } finally {
            activeSubscriptions--
        }
    }

    suspend fun emit(input: VehicleSignalInput) = events.emit(Event.Sample(input))
    suspend fun fail() = events.emit(Event.Failure)
}

class FakeNaviSource : NaviSignalSource {
    private val values = MutableSharedFlow<NaviState>()
    override val states: Flow<NaviState> = values
    suspend fun emit(state: NaviState) = values.emit(state)
}

class FakePowerSource : PowerSignalSource {
    private val values = MutableSharedFlow<PowerEvent>()
    override val events: Flow<PowerEvent> = values
    suspend fun emit(event: PowerEvent) = values.emit(event)
}
