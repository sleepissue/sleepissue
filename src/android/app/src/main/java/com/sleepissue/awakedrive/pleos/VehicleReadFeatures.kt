package com.sleepissue.awakedrive.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.*

/** 세 읽기 API의 경계만 분리한다. 지원 판정·정규화·Hub 입력은 어댑터가 계속 소유한다. */
internal data class SdkReadCapability(val readable: Boolean, val description: String,
    val minValue: Int? = null, val maxValue: Int? = null)

internal interface SdkReadFeature<T> {
    fun check(result: (SdkReadCapability) -> Unit, failed: (Exception) -> Unit)
    fun query(result: (T?) -> Unit, failed: (Exception) -> Unit)
    fun subscribe(changed: (T?) -> Unit, failed: (Exception) -> Unit): () -> Unit
}

internal interface SdkSeatReadFeature {
    fun check(result: (SdkReadCapability, Set<SeatValue>) -> Unit, failed: (Exception) -> Unit)
    fun query(position: SeatValue, result: (Boolean?) -> Unit, failed: (Exception) -> Unit)
    fun subscribe(position: SeatValue, changed: (Boolean?) -> Unit, failed: (Exception) -> Unit): () -> Unit
}

internal interface VehicleReadFeatures {
    val steering: SdkReadFeature<Float>
    val occupancy: SdkSeatReadFeature
    val ventilation: SdkReadFeature<Int>
}

/** 기존 공식 SDK 호출을 그대로 감싼다. capability를 거짓으로 바꾸는 구현은 debug에만 있다. */
internal class PleosVehicleReadFeatures(vehicle: Vehicle) : VehicleReadFeatures {
    private val steeringApi = vehicle.getSteeringWheel()
    // 좌석 API 초기화 실패가 앞선 방향지시등/브레이크 연결까지 막지 않게 기존 호출 순서를 유지한다.
    private val seatApi by lazy { vehicle.getSeat() }
    private fun readable(access: AccessType) = access == AccessType.READ || access == AccessType.READ_WRITE
    override val steering = object : SdkReadFeature<Float> {
        override fun check(result: (SdkReadCapability) -> Unit, failed: (Exception) -> Unit) {
            steeringApi.checkSteeringWheelAngleCapabilities({ c, bounds ->
                result(SdkReadCapability(readable(c.accessType), "${c.accessType} $bounds"))
            }, failed)
        }
        override fun query(result: (Float?) -> Unit, failed: (Exception) -> Unit) =
            steeringApi.getCurrentSteeringWheelAngle(result, failed)
        override fun subscribe(changed: (Float?) -> Unit, failed: (Exception) -> Unit): () -> Unit {
            val listener = object : SteeringWheelAngleListener {
                override fun onSteeringWheelAngleUpdated(angle: Float?) = changed(angle)
                override fun onFailed(e: Exception) = failed(e)
            }
            steeringApi.registerSteeringWheelAngle(listener)
            return { steeringApi.unregisterSteeringWheelAngle(listener) }
        }
    }
    override val occupancy = object : SdkSeatReadFeature {
        override fun check(result: (SdkReadCapability, Set<SeatValue>) -> Unit, failed: (Exception) -> Unit) {
            seatApi.checkSeatOccupancyCapabilities({ c, areas ->
                val individual = SeatValue.entries.filter { SeatArea(it) in areas }.toSet()
                result(SdkReadCapability(readable(c.accessType), c.accessType.toString()), individual)
            }, failed)
        }
        override fun query(position: SeatValue, result: (Boolean?) -> Unit, failed: (Exception) -> Unit) =
            seatApi.getCurrentSeatOccupancy(SeatArea(position), result, failed)
        override fun subscribe(position: SeatValue, changed: (Boolean?) -> Unit, failed: (Exception) -> Unit): () -> Unit {
            val area = SeatArea(position)
            val listener = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(occupancy: Boolean?) = changed(occupancy)
                override fun onFailed(e: Exception) = failed(e)
            }
            seatApi.registerSeatOccupancy(area, listener)
            return { seatApi.unregisterSeatOccupancy(area, listener) }
        }
    }
    override val ventilation = object : SdkReadFeature<Int> {
        private val driver = SeatArea(SeatValue.ROW_1_LEFT)
        override fun check(result: (SdkReadCapability) -> Unit, failed: (Exception) -> Unit) {
            seatApi.checkSeatVentilationCapabilities({ c, areas ->
                val bounds = areas[driver]
                result(SdkReadCapability(readable(c.accessType) && bounds != null,
                    if (bounds == null) "운전석 개별 영역 미지원" else "${c.accessType} ROW_1_LEFT $bounds",
                    bounds?.minValue, bounds?.maxValue))
            }, failed)
        }
        override fun query(result: (Int?) -> Unit, failed: (Exception) -> Unit) =
            seatApi.getHvacSeatVentilationState(driver, result, failed)
        override fun subscribe(changed: (Int?) -> Unit, failed: (Exception) -> Unit): () -> Unit {
            val listener = object : HvacSeatVentilationListener {
                override fun onHvacSeatVentilationUpdated(ventilation: Int?) = changed(ventilation)
                override fun onFailed(e: Exception) = failed(e)
            }
            seatApi.registerHvacSeatVentilation(driver, listener)
            return { seatApi.unregisterHvacSeatVentilation(driver, listener) }
        }
    }
}
