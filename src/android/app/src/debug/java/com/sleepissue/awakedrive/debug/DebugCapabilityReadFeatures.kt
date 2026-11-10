package com.sleepissue.awakedrive.debug

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.SeatValue
import com.sleepissue.awakedrive.engine.model.SignalField
import com.sleepissue.awakedrive.pleos.*

/** 명시적 AndroidTest만 생성한다. 선택 신호의 SDK 경계만 가짜로 바꾸며 프레임은 만들지 않는다. */
internal class DebugCapabilityReadFeatures(val field: SignalField, private val readable: Boolean) {
    private val events = java.util.Collections.synchronizedList(mutableListOf<String>())
    fun calls(): List<String> = synchronized(events) { events.toList() }
    private fun <T> feature(value: T): SdkReadFeature<T> = object : SdkReadFeature<T> {
        override fun check(result: (SdkReadCapability) -> Unit, failed: (Exception) -> Unit) {
            events += "check:$readable"
            result(SdkReadCapability(readable, "DEBUG_FAKE capability=$readable", 0, 3))
        }
        override fun query(result: (T?) -> Unit, failed: (Exception) -> Unit) {
            events += "query"; check(readable) { "capability=false 뒤 getter 호출" }; result(value)
        }
        override fun subscribe(changed: (T?) -> Unit, failed: (Exception) -> Unit): () -> Unit {
            events += "subscribe"; check(readable) { "capability=false 뒤 구독 호출" }
            return { events += "unsubscribe" }
        }
    }
    fun wrap(vehicle: Vehicle): VehicleReadFeatures {
        val actual = PleosVehicleReadFeatures(vehicle)
        return object : VehicleReadFeatures {
            override val steering = if (field == SignalField.STEERING_DEG) feature(0f) else actual.steering
            override val ventilation = if (field == SignalField.VENTILATION_LEVEL) feature(1) else actual.ventilation
            override val occupancy = if (field != SignalField.SEATS) actual.occupancy else object : SdkSeatReadFeature {
                override fun check(result: (SdkReadCapability, Set<SeatValue>) -> Unit, failed: (Exception) -> Unit) {
                    events += "check:$readable"
                    result(SdkReadCapability(readable, "DEBUG_FAKE capability=$readable"), setOf(
                        SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT))
                }
                override fun query(position: SeatValue, result: (Boolean?) -> Unit, failed: (Exception) -> Unit) {
                    events += "query:$position"; check(readable) { "capability=false 뒤 착석 getter 호출" }; result(false)
                }
                override fun subscribe(position: SeatValue, changed: (Boolean?) -> Unit, failed: (Exception) -> Unit): () -> Unit {
                    events += "subscribe:$position"; check(readable) { "capability=false 뒤 착석 구독 호출" }
                    return { events += "unsubscribe:$position" }
                }
            }
        }
    }
}
