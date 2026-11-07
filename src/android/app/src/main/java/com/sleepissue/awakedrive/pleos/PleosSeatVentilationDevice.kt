package com.sleepissue.awakedrive.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Seat
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.SeatValue
import android.content.Context
import android.content.pm.PackageManager
import com.sleepissue.awakedrive.climate.SeatVentilationDevice
import com.sleepissue.awakedrive.engine.climate.VentilationCapability
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [4번] Vehicle 2.3.2 Seat API로 운전석(ROW_1_LEFT) 통풍만 읽고 쓴다. 동승석은 건드리지 않는다.
 *
 * 시그니처 근거: Vehicle-2.3.2.aar javap(2026-11-06)
 *  - checkSeatVentilationCapabilities((CapabilityConfig, Map<SeatArea, AreaBounds<Int>>) -> Unit, (Exception) -> Unit)  @CAR_SEATS
 *  - getHvacSeatVentilationState(SeatArea, (Int?) -> Unit, (Exception) -> Unit)  @CAR_SEATS
 *  - setHvacSeatVentilation(SeatArea, Int, (SeatArea, Int) -> Unit, (Exception) -> Unit)  @CONTROL_CAR_SEATS
 * Workspace의 Vehicle Control은 'Not Available Yet'이라 쓰기가 실패하거나 값이 유지되지 않을 수 있다.
 * 그래서 쓰기 성공 콜백을 믿지 않고 DriverVentilationController가 항상 다시 읽어 확인한다.
 */
class PleosSeatVentilationDevice(private val context: Context) : SeatVentilationDevice, AutoCloseable {

    private val driver = SeatArea(SeatValue.ROW_1_LEFT)
    private var vehicle: Vehicle? = null

    private fun seat(): Seat {
        val v = vehicle ?: Vehicle(context.applicationContext).also { it.initialize(); vehicle = it }
        return v.getSeat()
    }

    override suspend fun capability(): VentilationCapability? {
        requirePermission(PERMISSION_READ)
        return suspendCancellableCoroutine { cont ->
            seat().checkSeatVentilationCapabilities({ config: CapabilityConfig, areas: Map<SeatArea, AreaBounds<Int>> ->
                val bounds = areas[driver]
                val access = config.accessType
                val capability = bounds?.let {
                    VentilationCapability(
                        minLevel = it.minValue,
                        maxLevel = it.maxValue,
                        readable = access == AccessType.READ || access == AccessType.READ_WRITE,
                        writable = (access == AccessType.WRITE || access == AccessType.READ_WRITE) && granted(PERMISSION_WRITE),
                    )
                }
                if (cont.isActive) cont.resume(capability)
            }, { e -> if (cont.isActive) cont.resumeWithException(e) })
        }
    }

    override suspend fun read(): Int? {
        requirePermission(PERMISSION_READ)
        return suspendCancellableCoroutine { cont ->
            seat().getHvacSeatVentilationState(driver, { value -> if (cont.isActive) cont.resume(value?.takeIf { it >= 0 }) },
                { e -> if (cont.isActive) cont.resumeWithException(e) })
        }
    }

    override suspend fun write(level: Int) {
        requirePermission(PERMISSION_WRITE)
        suspendCancellableCoroutine { cont ->
            seat().setHvacSeatVentilation(driver, level, { _, _ -> if (cont.isActive) cont.resume(Unit) },
                { e -> if (cont.isActive) cont.resumeWithException(e) })
        }
    }

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun requirePermission(permission: String) {
        if (!granted(permission)) throw SecurityException("$permission 없음")
    }

    override fun close() {
        runCatching { vehicle?.release() }
        vehicle = null
    }

    private companion object {
        const val PERMISSION_READ = "pleos.car.permission.CAR_SEATS"
        const val PERMISSION_WRITE = "pleos.car.permission.CONTROL_CAR_SEATS"
    }
}
