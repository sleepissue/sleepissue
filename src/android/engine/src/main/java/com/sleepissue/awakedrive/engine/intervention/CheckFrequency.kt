package com.sleepissue.awakedrive.engine.intervention

import com.sleepissue.awakedrive.engine.config.EngineConfig

/** 자주 확인은 기존 위험 맥락 간격을 사용한다. 사용자 선택으로 위험 맥락의 확인을 늦추지 않는다. */
fun assistedCheckIntervalMs(config: EngineConfig, frequent: Boolean, riskContext: Boolean): Long =
    if (frequent || riskContext) config.assistedRiskCheckIntervalMs else config.assistedCheckIntervalMs
