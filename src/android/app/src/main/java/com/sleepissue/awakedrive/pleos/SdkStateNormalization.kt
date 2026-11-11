package com.sleepissue.awakedrive.pleos

import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import com.sleepissue.awakedrive.engine.model.TurnSignal
import com.sleepissue.awakedrive.signal.SpeedUnit
import com.sleepissue.awakedrive.signal.UnitConverter

internal object SdkStateNormalization {
    fun turnSignal(value: TurnSignalValue?) = when (value) {
        TurnSignalValue.OFF -> TurnSignal.OFF
        TurnSignalValue.LEFT -> TurnSignal.LEFT
        TurnSignalValue.RIGHT -> TurnSignal.RIGHT
        null -> null
    }
    // SDK enum을 그대로 판독한다. Console의 Ready/Enabled만으로 활성으로 추정하지 않는다.
    fun cruiseActive(value: CruiseControl?) = when (value) {
        CruiseControl.ACTIVATED -> true
        CruiseControl.ENABLED, CruiseControl.SUSPENDED -> false
        else -> null
    }
    fun targetKph(value: Float?) = value?.takeIf { it.isFinite() && it >= 0 }?.let {
        UnitConverter.speedKph(it, SpeedUnit.METERS_PER_SECOND)
    }
    fun cabinTemperature(value: Float?) = value?.takeIf { it.isFinite() }
    fun ventilation(value: Int?, min: Int, max: Int) = value?.takeIf { min >= 0 && it in min..max }
}
