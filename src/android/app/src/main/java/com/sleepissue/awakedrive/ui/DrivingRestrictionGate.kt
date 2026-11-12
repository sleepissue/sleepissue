package com.sleepissue.awakedrive.ui

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 차량 서비스 확인 전에는 설정·리포트·데모를 잠근다. 실패를 정차로 간주하지 않는다. */
class DrivingRestrictionGate(context: Context) : AutoCloseable {
    private val mutableRestricted = MutableStateFlow(true)
    val restricted = mutableRestricted.asStateFlow()
    private var manager: CarUxRestrictionsManager? = null
    private val car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { car, ready ->
        if (ready) runCatching {
            manager = car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            manager?.registerListener { mutableRestricted.value = it.isRequiresDistractionOptimization }
            mutableRestricted.value = manager?.currentCarUxRestrictions?.isRequiresDistractionOptimization ?: true
        }.onFailure { mutableRestricted.value = true }
        else mutableRestricted.value = true
    }
    override fun close() { runCatching { manager?.unregisterListener() }; car?.disconnect() }
}
