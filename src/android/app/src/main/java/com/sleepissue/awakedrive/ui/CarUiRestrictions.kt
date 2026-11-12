package com.sleepissue.awakedrive.ui

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.pm.PackageManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/** 리스너는 화면이 있는 동안만 구독하고 해제한다. Flow의 구독/해제와 같은 생명주기다. */
@Composable
fun rememberCarUiRestricted(): Boolean? {
    val context = LocalContext.current
    var restricted by remember { mutableStateOf<Boolean?>(null) }
    DisposableEffect(context) {
        var car: Car? = null
        var manager: CarUxRestrictionsManager? = null
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            runCatching {
                car = Car.createCar(context)
                manager = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                restricted = manager?.currentCarUxRestrictions?.isRequiresDistractionOptimization
                manager?.registerListener { value -> restricted = value.isRequiresDistractionOptimization }
            }.onFailure { restricted = null }
        }
        onDispose {
            runCatching { manager?.unregisterListener() }
            runCatching { car?.disconnect() }
        }
    }
    return restricted
}
