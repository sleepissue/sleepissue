package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.performance.WorkProbe

/**
 * 수동 운행은 관측 경로를, 10Hz 수집은 명시적 정책을 받는 주기 경로를 선택한다.
 * 같은 Hub 계약과 단위 정규화를 사용하며 두 세션의 시계·캐시·저장소는 공유하지 않는다.
 */
class VehicleSignalHub private constructor(private val session: SignalHubSession) : SignalHubSession by session {
    constructor(vehicleSource: VehicleSignalSource, clock: Clock, naviSource: NaviSignalSource? = null,
        powerSource: PowerSignalSource? = null) : this(ObservationSignalHub(vehicleSource, clock, naviSource, powerSource))

    constructor(sources: List<HubSourceSet>, clock: Clock, policy: HubPolicy, config: EngineConfig = EngineConfig(),
        probe: WorkProbe = WorkProbe.NONE) :
        this(PeriodicSignalHub(sources, clock, policy, config, probe))

    companion object {
        /** 수동 운행의 명시적 관측 경로다. 실제 시간 타이머·중재 캐시를 만들지 않는다. */
        fun observations(vehicleSource: VehicleSignalSource, clock: Clock, naviSource: NaviSignalSource? = null,
            powerSource: PowerSignalSource? = null) = VehicleSignalHub(vehicleSource, clock, naviSource, powerSource)
    }
}
