package com.sleepissue.awakedrive.demo

import com.sleepissue.awakedrive.engine.demo.DemoSpeedSegment
import com.sleepissue.awakedrive.engine.model.NaviState
import com.sleepissue.awakedrive.engine.model.PowerEvent
import com.sleepissue.awakedrive.signal.VehicleSignalInput

/** 스키마 v1의 10 Hz 주기다. 파일 시간은 초, 메모리 시간은 상대 ms다. */
const val DEMO_SAMPLE_INTERVAL_MS: Long = 100L

/** SDK의 세 Source 인터페이스는 유지하고 debug 조립에서만 내부 인계 경로를 고른다. */
enum class DemoTransportMode { SEPARATE_FLOWS, COMPLETE_OBSERVATION }

/** 검증된 완전 관측이다. 부분 갱신이나 실제 차량 입력으로 해석하지 않는다. */
data class DemoScenarioSample(
    val tMs: Long,
    val vehicle: VehicleSignalInput,
    val navi: NaviState,
    val power: PowerEvent,
)

data class DemoScenario(
    val id: String,
    val startEpochMs: Long,
    val samples: List<DemoScenarioSample>,
    val speedSegments: List<DemoSpeedSegment>,
) {
    /** 마지막 관측도 한 주기 유지한다. 배속 구간은 [0, durationMs)를 덮는다. */
    val durationMs: Long get() = Math.addExact(samples.last().tMs, DEMO_SAMPLE_INTERVAL_MS)
}

enum class DemoLoadErrorCode {
    FILE_NOT_FOUND, IO_ERROR, INVALID_JSON, INVALID_SCHEMA, INVALID_TIME, INVALID_SPEED,
}

/** path는 JSON 위치 또는 파일명이며, 오류 원문이나 파일 내용을 로그에 남기지 않는다. */
data class DemoLoadError(val code: DemoLoadErrorCode, val path: String, val message: String)

sealed interface DemoLoadResult {
    data class Loaded(val scenario: DemoScenario) : DemoLoadResult
    data class Failed(val error: DemoLoadError) : DemoLoadResult
}

/** assets·선택한 파일·시험 자료는 같은 검증 로더를 거친다. */
fun interface DemoScenarioRepository {
    suspend fun load(scenarioId: String): DemoLoadResult
}
