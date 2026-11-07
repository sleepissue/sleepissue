package com.sleepissue.awakedrive.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.*
import ai.pleos.playground.vehicle.constant.area.*
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import ai.pleos.playground.vehicle.listener.*
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.*
import ai.pleos.playground.navi.constants.NaviErrorCode
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.signal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlin.math.abs

/** 공개 SDK 2.3.2/3.0.5 계약만 사용한다. 제어 API·가짜 입력·운행/Room 의존성은 없다. */
class PlaygroundSignalAdapter(private val context: Context, private val clock: Clock,
    val policy: SdkProbePolicy, private val scope: CoroutineScope) {
    val store = SdkSignalStore(clock, policy)
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private val cleanup = mutableListOf<() -> Unit>()
    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null
    private var car: Car? = null
    private var poll: Job? = null
    private val routeGate = SdkRouteGate(clock, policy)
    private val vehicleBus = MutableSharedFlow<VehicleSignalInput>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val naviBus = MutableStateFlow(NaviState(clock.scenarioTimeMs()))
    val vehicleSource = object : VehicleSignalSource { override val samples = vehicleBus.asSharedFlow() }
    val naviSource = object : NaviSignalSource { override val states = naviBus.asStateFlow() }
    private val mutableStatus = MutableStateFlow("미연결")
    val status = mutableStatus.asStateFlow()
    private fun post(action: () -> Unit) { handler.post { if (active) action() } }
    private fun emit(key: String) {
        store.vehicleInput(key)?.let { vehicleBus.tryEmit(it) }
        if (NaviField.entries.any { it.name == key }) naviBus.value = store.naviState()
    }
    private fun permission(key: String, name: String): Boolean {
        val granted = context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
        store.declare(key, name, granted); return granted
    }
    private fun failure(key: String, error: Throwable) = post {
        store.failed(key, if (error is SecurityException) SignalAvailability.PERMISSION_DENIED else SignalAvailability.ERROR,
            "${error.javaClass.simpleName}: ${error.message?.take(180)}")
        emit(key)
    }
    private fun received(key: String, raw: Any?, normalized: Any?, delivery: SdkDelivery,
        nativeMs: Long? = null, note: String = "", availability: SignalAvailability? = null) {
        val receipt = clock.elapsedRealtimeMs()
        post { store.receive(key, raw, normalized, delivery, nativeMs, note, availability, receipt); emit(key) }
    }
    private fun seatReceived(key: String, raw: Boolean?, delivery: SdkDelivery) {
        val receipt = clock.elapsedRealtimeMs()
        post { store.receiveSeatSdkValue(key, raw, delivery, receipt); emit(key) }
    }
    private fun checked(key: String, config: CapabilityConfig, extra: String = "", subscribe: () -> Unit) = post {
        val readable = config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE
        store.capability(key, readable, "${config.accessType} $extra"); emit(key)
        val permitted = store.readings().first { it.key == key }.permissionGranted
        if (readable && permitted) try { subscribe() } catch (e: Exception) { failure(key, e) }
    }
    private fun attempt(key: String, action: () -> Unit) { try { action() } catch (e: Exception) { failure(key, e) } }

    fun start() {
        check(!active); active = true; mutableStatus.value = "연결 요청 · capability 확인 중"
        SignalField.entries.filter { it != SignalField.SEATS }.forEach {
            val p = when (it) {
                SignalField.SPEED_KPH -> "android.car.permission.CAR_SPEED"
                SignalField.GEAR -> "android.car.permission.CAR_POWERTRAIN"
                SignalField.TURN_SIGNAL -> "pleos.car.permission.CAR_LIGHTS"
                SignalField.VENTILATION_LEVEL -> "pleos.car.permission.CAR_SEATS"
                SignalField.CABIN_TEMP_C -> "pleos.car.permission.CAR_CLIMATE"
                else -> "pleos.car.permission.CAR_DRIVE"
            }
            permission(it.name, p)
        }
        listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_CENTER", "ROW_2_RIGHT").forEach {
            permission("SEAT:$it", "pleos.car.permission.CAR_SEATS")
            permission("BELT:$it", "pleos.car.permission.CAR_SEATS")
        }
        NaviField.entries.forEach { permission(it.name, "pleos.car.permission.NAVI_ROUTE") }
        store.readings().forEach { emit(it.key) }
        naviBus.value = store.naviState()
        try { connectVehicle() } catch (e: Exception) {
            store.readings().filter { it.permission.startsWith("pleos.car") && !it.permission.endsWith("NAVI_ROUTE") }
                .forEach { failure(it.key, e) }
        }
        try { connectCar() } catch (e: Exception) {
            listOf("SPEED_KPH", "GEAR").forEach { failure(it, e) }
        }
        try { connectNavi() } catch (e: Exception) { NaviField.entries.forEach { failure(it.name, e) } }
        mutableStatus.value = "연결 요청 완료 · 신호별 지원/오류를 확인하세요"
    }
    private fun connectVehicle() {
        val v = Vehicle(context.applicationContext).also { vehicle = it; it.initialize() }
        val s = v.getSteeringWheel()
        val steering = object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(angle: Float?) = received("STEERING_DEG", angle, null, SdkDelivery.CALLBACK,
                note = "전륜각 °, 좌측 음수. 운전대 회전각 아님 → 엔진 연결 보류", availability = SignalAvailability.UNSUPPORTED)
            override fun onFailed(e: Exception) = failure("STEERING_DEG", e)
        }
        attempt("STEERING_DEG") { s.checkSteeringWheelAngleCapabilities({ c, b -> checked("STEERING_DEG", c, b.toString()) {
            s.getCurrentSteeringWheelAngle({ received("STEERING_DEG", it, null, SdkDelivery.QUERY,
                note = "전륜각 ° → 운전대각 변환 불가", availability = SignalAvailability.UNSUPPORTED) }, { failure("STEERING_DEG", it) })
            cleanup += { s.unregisterSteeringWheelAngle(steering) }; s.registerSteeringWheelAngle(steering)
        } }, { failure("STEERING_DEG", it) }) }
        val t = v.getTurnSignal()
        fun turn(value: TurnSignalValue?) = when (value) {
            TurnSignalValue.OFF -> TurnSignal.OFF; TurnSignalValue.LEFT -> TurnSignal.LEFT
            TurnSignalValue.RIGHT -> TurnSignal.RIGHT; null -> null
        }
        val turn = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(state: TurnSignalValue?) = received("TURN_SIGNAL", state, turn(state), SdkDelivery.CALLBACK)
            override fun onFailed(e: Exception) = failure("TURN_SIGNAL", e)
        }
        attempt("TURN_SIGNAL") { t.checkTurnSignalStateCapabilities({ c -> checked("TURN_SIGNAL", c) {
            t.getCurrentTurnSignalState({ received("TURN_SIGNAL", it, turn(it), SdkDelivery.QUERY) }, { failure("TURN_SIGNAL", it) })
            cleanup += { t.unregisterTurnSignalState(turn) }; t.registerTurnSignalState(turn)
        } }, { failure("TURN_SIGNAL", it) }) }
        val b = v.getBrake()
        val brake = object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(status: Boolean?) = received("BRAKE_ON", status, status, SdkDelivery.CALLBACK)
            override fun onFailed(e: Exception) = failure("BRAKE_ON", e)
        }
        attempt("BRAKE_ON") { b.checkBrakePedalStatusCapabilities({ c -> checked("BRAKE_ON", c) {
            b.getCurrentBrakePedalStatus({ received("BRAKE_ON", it, it, SdkDelivery.QUERY) }, { failure("BRAKE_ON", it) })
            cleanup += { b.unregisterBrakePedalStatus(brake) }; b.registerBrakePedalStatus(brake)
        } }, { failure("BRAKE_ON", it) }) }
        val d = v.getDrivingMode()
        // ACTIVATED만 활성으로 확정한다. OTHER/USER_OVERRIDE 등 모호한 상태는 false로 추측하지 않는다.
        fun cruise(value: CruiseControl?) = when (value) {
            CruiseControl.ACTIVATED -> true
            CruiseControl.ENABLED, CruiseControl.SUSPENDED -> false
            else -> null
        }
        val cruise = object : CruiseControlStateListener {
            override fun onCruiseControlStateUpdated(state: CruiseControl?) = received("CRUISE_ACTIVE", state, cruise(state), SdkDelivery.CALLBACK,
                note = "ACTIVATED=true, ENABLED/SUSPENDED=false, 그 외 미확정 null")
            override fun onFailed(e: Exception) = failure("CRUISE_ACTIVE", e)
        }
        attempt("CRUISE_ACTIVE") { d.checkCruiseControlStateCapabilities({ c -> checked("CRUISE_ACTIVE", c) {
            d.getCruiseControlState({ received("CRUISE_ACTIVE", it, cruise(it), SdkDelivery.QUERY) }, { failure("CRUISE_ACTIVE", it) })
            cleanup += { d.unregisterCruiseControlState(cruise) }; d.registerCruiseControlState(cruise)
        } }, { failure("CRUISE_ACTIVE", it) }) }
        fun kph(speed: Float?) = speed?.takeIf { it.isFinite() && it >= 0 }?.let { UnitConverter.speedKph(it, SpeedUnit.METERS_PER_SECOND) }
        val target = object : CruiseControlTargetSpeedListener {
            override fun onCruiseControlTargetSpeedUpdated(speed: Float?) = received("CRUISE_TARGET_KPH", speed, kph(speed), SdkDelivery.CALLBACK, note = "m/s × 3.6 → km/h")
            override fun onFailed(e: Exception) = failure("CRUISE_TARGET_KPH", e)
        }
        attempt("CRUISE_TARGET_KPH") { d.checkCruiseControlTargetSpeedCapabilities({ c -> checked("CRUISE_TARGET_KPH", c) {
            d.getCruiseControlTargetSpeed({ received("CRUISE_TARGET_KPH", it, kph(it), SdkDelivery.QUERY) }, { failure("CRUISE_TARGET_KPH", it) })
            cleanup += { d.unregisterCruiseControlTargetSpeed(target) }; d.registerCruiseControlTargetSpeed(target)
        } }, { failure("CRUISE_TARGET_KPH", it) }) }
        connectSeats(v)
    }
    private fun connectSeats(v: Vehicle) {
        val seat = v.getSeat()
        val seats = listOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT)
        attempt("SEAT:ROW_1_LEFT") { seat.checkSeatOccupancyCapabilities({ c: CapabilityConfig, areas: List<SeatArea> ->
            seats.forEach { position ->
                val key = "SEAT:${position.name}"; val area = SeatArea(position)
                // 결합 영역을 개별 좌석으로 오인하지 않는다.
                if (area !in areas) post { store.capability(key, false, "개별 영역 미지원"); emit(key) }
                else checked(key, c, position.name) {
                    val listener = object : SeatOccupancyListener {
                        override fun onSeatOccupancyUpdated(occupancy: Boolean?) = seatReceived(key, occupancy, SdkDelivery.CALLBACK)
                        override fun onFailed(e: Exception) = failure(key, e)
                    }
                    seat.getCurrentSeatOccupancy(area, { seatReceived(key, it, SdkDelivery.QUERY) }, { failure(key, it) })
                    cleanup += { seat.unregisterSeatOccupancy(area, listener) }; seat.registerSeatOccupancy(area, listener)
                }
            }
        }, { e -> seats.forEach { failure("SEAT:${it.name}", e) } }) }
        attempt("BELT:ROW_1_LEFT") { seat.checkSeatBeltBuckledCapabilities({ c: CapabilityConfig, areas: List<SeatArea> ->
            seats.forEach { position ->
                val key = "BELT:${position.name}"; val area = SeatArea(position)
                if (area !in areas) post { store.capability(key, false, "개별 영역 미지원"); emit(key) }
                else checked(key, c, position.name) {
                    val listener = object : SeatBeltBuckledStateListener {
                        override fun onSeatBeltBuckledStateUpdated(state: Boolean?) =
                            received(key, state, state, SdkDelivery.CALLBACK, note = "true=체결, false=미체결, null=모름")
                        override fun onFailed(e: Exception) = failure(key, e)
                    }
                    seat.getCurrentSeatBeltBuckledState(area, {
                        received(key, it, it, SdkDelivery.QUERY, note = "true=체결, false=미체결, null=모름")
                    }, { failure(key, it) })
                    cleanup += { seat.unregisterSeatBeltBuckledState(area, listener) }
                    seat.registerSeatBeltBuckledState(area, listener)
                }
            }
        }, { e -> seats.forEach { failure("BELT:${it.name}", e) } }) }
        val driver = SeatArea(SeatValue.ROW_1_LEFT)
        attempt("VENTILATION_LEVEL") { seat.checkSeatVentilationCapabilities({ c, areas ->
            if (driver !in areas) post { store.capability("VENTILATION_LEVEL", false, "운전석 개별 영역 미지원"); emit("VENTILATION_LEVEL") }
            else checked("VENTILATION_LEVEL", c, "ROW_1_LEFT ${areas[driver]}") {
                val listener = object : HvacSeatVentilationListener {
                    override fun onHvacSeatVentilationUpdated(ventilation: Int?) = received("VENTILATION_LEVEL", ventilation, ventilation?.takeIf { it >= 0 }, SdkDelivery.CALLBACK)
                    override fun onFailed(e: Exception) = failure("VENTILATION_LEVEL", e)
                }
                seat.getHvacSeatVentilationState(driver, { received("VENTILATION_LEVEL", it, it?.takeIf { n -> n >= 0 }, SdkDelivery.QUERY) }, { failure("VENTILATION_LEVEL", it) })
                cleanup += { seat.unregisterHvacSeatVentilation(driver, listener) }; seat.registerHvacSeatVentilation(driver, listener)
            }
        }, { failure("VENTILATION_LEVEL", it) }) }
        val h = v.getHVAC()
        attempt("CABIN_TEMP_C") { h.checkHvacTemperatureCurrentCapabilities({ c, areas ->
            if (driver !in areas) post { store.capability("CABIN_TEMP_C", false, "운전석 개별 실내온도 영역 미지원"); emit("CABIN_TEMP_C") }
            else checked("CABIN_TEMP_C", c, "ROW_1_LEFT") {
                val listener = object : HvacTemperatureListener {
                    override fun onHvacTemperatureUpdated(temperature: Float?) = received("CABIN_TEMP_C", temperature, temperature?.takeIf { it.isFinite() }, SdkDelivery.CALLBACK, note = "현재 실내온도 °C (설정온도 아님)")
                    override fun onFailed(e: Exception) = failure("CABIN_TEMP_C", e)
                }
                h.getHvacTemperatureCurrent(driver, { received("CABIN_TEMP_C", it, it?.takeIf { x -> x.isFinite() }, SdkDelivery.QUERY) }, { failure("CABIN_TEMP_C", it) })
                cleanup += { h.unregisterHvacTemperatureCurrent(driver, listener) }; h.registerHvacTemperatureCurrent(driver, listener)
            }
        }, { failure("CABIN_TEMP_C", it) }) }
    }
    private fun connectCar() {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            listOf("SPEED_KPH", "GEAR").forEach { store.capability(it, false, "AAOS 아님"); emit(it) }; return
        }
        car = Car.createCar(context, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready -> post {
            if (!ready) { listOf("SPEED_KPH", "GEAR").forEach { failure(it, IllegalStateException("CarService 연결 해제")) } }
            else {
                val manager = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                mapOf(VehiclePropertyIds.PERF_VEHICLE_SPEED to "SPEED_KPH", VehiclePropertyIds.GEAR_SELECTION to "GEAR").forEach { (id, key) ->
                    attempt(key) {
                        if (context.checkSelfPermission(store.readings().first { it.key == key }.permission) != PackageManager.PERMISSION_GRANTED) {
                            store.failed(key, SignalAvailability.PERMISSION_DENIED, "권한 없음"); emit(key)
                        } else {
                            val config = manager.getCarPropertyConfig(id)
                            if (config == null || config.access and 1 == 0) { store.capability(key, false, "AAOS property 읽기 미지원"); emit(key) }
                            else {
                                store.capability(key, true, "AAOS READ mode=${config.changeMode} maxHz=${config.maxSampleRate}"); emit(key)
                                fun deliver(value: CarPropertyValue<*>, kind: SdkDelivery) {
                                    val raw = if (value.status == CarPropertyValue.STATUS_AVAILABLE) value.value else null
                                    val availability = when (value.status) {
                                        CarPropertyValue.STATUS_AVAILABLE -> null
                                        CarPropertyValue.STATUS_UNAVAILABLE -> SignalAvailability.UNAVAILABLE
                                        else -> SignalAvailability.ERROR
                                    }
                                    val normalized: Any? = if (key == "SPEED_KPH") (raw as? Float)?.takeIf { it.isFinite() }?.let {
                                        UnitConverter.speedKph(abs(it), SpeedUnit.METERS_PER_SECOND)
                                    } else when (raw as? Int) {
                                        VehicleGear.GEAR_PARK -> Gear.P; VehicleGear.GEAR_REVERSE -> Gear.R
                                        VehicleGear.GEAR_NEUTRAL -> Gear.N; VehicleGear.GEAR_DRIVE -> Gear.D; else -> null
                                    }
                                    received(key, raw, normalized, kind, value.timestamp.takeIf { it > 0 }?.div(1_000_000),
                                        if (key == "SPEED_KPH") "부호 있는 m/s → 속력 abs × 3.6 km/h" else "VehicleGear → P/R/N/D", availability)
                                }
                                val callback = object : CarPropertyManager.CarPropertyEventCallback {
                                    override fun onChangeEvent(value: CarPropertyValue<*>) = deliver(value, SdkDelivery.CALLBACK)
                                    override fun onErrorEvent(propId: Int, areaId: Int) = failure(key, IllegalStateException("AAOS property error $propId/$areaId"))
                                }
                                cleanup += { manager.unregisterCallback(callback, id) }
                                if (!manager.registerCallback(callback, id, 1_000f / EngineConfig().signalFrameIntervalMs)) throw IllegalStateException("AAOS 구독 거절")
                                // 최초 값 조회와 구독 이벤트 계수는 분리한다.
                                scope.launch(Dispatchers.IO) {
                                    attempt(key) { deliver(manager.getProperty<Any>(id, 0), SdkDelivery.QUERY) }
                                }
                            }
                        }
                    }
                }
            }
        } }
    }
    private fun connectNavi() {
        if (context.checkSelfPermission("pleos.car.permission.NAVI_ROUTE") != PackageManager.PERMISSION_GRANTED) return
        val n = NaviHelper(context.applicationContext).also { navi = it }
        val listener = object : NaviHelperEventListener {
            override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
                val receipt = clock.elapsedRealtimeMs()
                post {
                    routeGate.update(routeStateInfo.isRouting, receipt)
                    if (!routeStateInfo.isRouting) clearRoute("활성 경로 없음 · SDK의 기본 0을 남은 거리/시간으로 사용하지 않음")
                }
            }
            override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
                val receipt = clock.elapsedRealtimeMs()
                post {
                val x = currentLocationInfo
                val valid = x.latitude.isFinite() && x.latitude in -90.0..90.0 && x.longitude.isFinite() && x.longitude in -180.0..180.0
                store.receive("LAT", x.latitude, x.latitude.takeIf { valid }, SdkDelivery.CALLBACK, receivedMs = receipt)
                store.receive("LON", x.longitude, x.longitude.takeIf { valid }, SdkDelivery.CALLBACK, receivedMs = receipt)
                store.receive("HEADING_DEG", x.heading, if (valid) store.locationHeading(x.latitude, x.longitude, receipt) else null,
                    SdkDelivery.CALLBACK, note = "원시 SDK heading 단위 미확인; 정규화는 최근 1분 위치로 계산", receivedMs = receipt)
                naviBus.value = store.naviState()
                }
            }
            override fun onDestinationInfo(destinationInfo: DestinationInfo) {
                val receipt = clock.elapsedRealtimeMs()
                post {
                val routing = routeGate.acceptsRouteValues()
                store.receive("REMAIN_TIME_SEC", destinationInfo.remainTime, destinationInfo.remainTime.toLong().takeIf { routing && it >= 0 }, SdkDelivery.CALLBACK, note = "초 · 활성 경로 ${if (routing) "확인" else "미확인/없음"}", receivedMs = receipt)
                store.receive("REMAIN_DIST_M", destinationInfo.remainDistance, destinationInfo.remainDistance.toFloat().takeIf { routing && it >= 0 }, SdkDelivery.CALLBACK, note = "m · 활성 경로 ${if (routing) "확인" else "미확인/없음"}", receivedMs = receipt)
                naviBus.value = store.naviState()
                }
            }
            override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
                val receipt = clock.elapsedRealtimeMs()
                post {
                val routing = routeGate.acceptsRouteValues()
                val distance = tbtInfoList.map { it.distance }.filter { it.isFinite() && it >= 0 }.minOrNull()
                store.receive("DIST_TO_NEXT_TURN_M", distance, distance?.takeIf { routing && it.isFinite() && it >= 0 }?.toFloat(),
                    SdkDelivery.CALLBACK, note = "TBT distance m 중 가장 가까운 비음수 거리; 빈 목록은 null", receivedMs = receipt)
                naviBus.value = store.naviState()
                }
            }
            override fun onRouteCancelled() = post { routeGate.clear(); clearRoute("경로 취소") }
            override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = post { routeGate.clear(); clearRoute("도착") }
            override fun onError(errorCode: NaviErrorCode) { NaviField.entries.forEach { failure(it.name, IllegalStateException(errorCode.toString())) } }
        }
        n.addListener(listener); cleanup += { n.removeListener(listener) }
        n.initialize(onConnected = { post {
            NaviField.entries.forEach { store.capability(it.name, true, "NaviHelper 연결/권한 확인; 별도 capability API 없음") }
            poll = scope.launch {
                while (isActive && active) {
                    // 요청 자체는 센서 관측이 아니다. 응답 이벤트에만 수신 시각을 부여한다.
                    attempt("LAT") { n.getCurrentLocationInfo() }
                    try { n.getRouteStateInfo() } catch (e: Exception) {
                        routeGate.clear(); clearRoute("경로 상태 조회 실패")
                        listOf("REMAIN_TIME_SEC", "REMAIN_DIST_M", "DIST_TO_NEXT_TURN_M").forEach { failure(it, e) }
                    }
                    attempt("REMAIN_DIST_M") { n.getDestinationInfo() }
                    attempt("DIST_TO_NEXT_TURN_M") { n.getTBTInfo() }
                    delay(policy.naviPollMs)
                }
            }
        } }, onFailed = { e -> NaviField.entries.forEach { failure(it.name, e) } })
    }
    private fun clearRoute(note: String) {
        listOf("REMAIN_TIME_SEC", "REMAIN_DIST_M", "DIST_TO_NEXT_TURN_M").forEach {
            // 경로 상태 통지는 이 필드의 새 수신/센서 관측이 아니다.
            store.failed(it, SignalAvailability.UNAVAILABLE, note)
        }
        naviBus.value = store.naviState()
    }
    /** main 스레드에서 호출한다. 늦은 연결/조회 콜백은 active 검사로 폐기한다. */
    fun close() {
        active = false; poll?.cancel(); poll = null
        cleanup.asReversed().forEach { runCatching(it) }; cleanup.clear()
        runCatching { navi?.release() }; runCatching { vehicle?.release() }; runCatching { car?.disconnect() }
        navi = null; vehicle = null; car = null
        handler.removeCallbacksAndMessages(null)
        mutableStatus.value = "중지 · 콜백 해제/연결 해제 · 과거 캐시 사용 안 함"
    }
}
