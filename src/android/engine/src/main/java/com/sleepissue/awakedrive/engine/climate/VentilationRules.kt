package com.sleepissue.awakedrive.engine.climate

import com.sleepissue.awakedrive.engine.config.EngineConfig

// [4번] 운전석 통풍 규칙 — 기획서 6장 '통풍 규칙', SRS CLM.
// 이 파일은 "무엇을 할지"만 정한다. 실제 SDK 호출·다시 읽기·대기는 app/climate/DriverVentilationController가 한다.

/**
 * 차가 알려 준 운전석 통풍 지원 범위 (SDK `checkSeatVentilationCapabilities`의 AreaBounds·AccessType).
 * @param minLevel 꺼짐에 해당하는 단계 (보통 0)
 */
data class VentilationCapability(
    val minLevel: Int,
    val maxLevel: Int,
    val readable: Boolean,
    val writable: Boolean,
) {
    init {
        require(minLevel <= maxLevel) { "통풍 최소 단계는 최대 단계 이하여야 합니다." }
    }
}

sealed interface VentilationPlan {
    /** 사전 동의 없음 → 아무것도 하지 않는다. */
    data object NoConsent : VentilationPlan

    /** 운전석 통풍을 읽을 수 없다 → 켰는지 확인할 방법이 없으므로 안내도 하지 않는다. */
    data object NotSupported : VentilationPlan

    /** 이미 목표 이상으로 켜져 있다 → 바꾸지 않고, 되돌릴 값도 저장하지 않는다. */
    data class AlreadyOn(val currentLevel: Int) : VentilationPlan

    /**
     * 목표 단계로 바꾼다. [priorLevel]은 휴식 인증 뒤 되돌릴 값이다.
     * @param writable false면 SDK로 바꿀 수 없으니 바로 "통풍을 켜 보세요" 안내로 간다
     */
    data class Raise(val priorLevel: Int, val targetLevel: Int, val writable: Boolean) : VentilationPlan
}

object VentilationRules {

    fun planEnable(
        consent: Boolean,
        capability: VentilationCapability?,
        currentLevel: Int?,
        config: EngineConfig = EngineConfig(),
    ): VentilationPlan {
        if (!consent) return VentilationPlan.NoConsent
        if (capability == null || !capability.readable || currentLevel == null) return VentilationPlan.NotSupported
        val target = targetLevel(capability, config)
        if (target <= capability.minLevel) return VentilationPlan.NotSupported // 켤 단계가 없음
        if (currentLevel >= target) return VentilationPlan.AlreadyOn(currentLevel)
        return VentilationPlan.Raise(currentLevel, target, capability.writable)
    }

    /** 목표 단계: 설정값을 차의 범위 안으로 줄인다. */
    fun targetLevel(capability: VentilationCapability, config: EngineConfig = EngineConfig()): Int =
        config.ventilationTargetLevel.coerceIn(capability.minLevel, capability.maxLevel)

    /** SDK로 바꾼 뒤 다시 읽은 값이 목표와 같아야 반영으로 본다. [가정] 단계를 정확히 맞춰야 함 */
    fun isApplied(targetLevel: Int, readBack: Int?): Boolean = readBack == targetLevel

    /** "통풍을 켜 보세요" 뒤에는 운전자가 몇 단계로 켜든, 바꾸기 전보다 높아지면 켠 것으로 본다. */
    fun isManuallyRaised(priorLevel: Int, readBack: Int?): Boolean = readBack != null && readBack > priorLevel

    /** 되돌리기: 앱이 바꾼 적이 있고 지금 값이 그때와 다르면 바꾸기 전 값으로. */
    fun restoreTarget(savedPriorLevel: Int?, currentLevel: Int?): Int? =
        savedPriorLevel?.takeIf { it != currentLevel }
}
