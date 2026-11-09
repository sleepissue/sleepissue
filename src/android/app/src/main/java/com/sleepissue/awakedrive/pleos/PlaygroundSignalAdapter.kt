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
    val gearAudit = GearCollectionAudit(clock, scheduler = "IO_FIXED_DELAY")
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    private var vehicleBootstrap: Job? = null
    private var started = false
    @Volatile private var carGeneration = 0L
    @Volatile private var carReady = false
    private var gearPoll: Job? = null
    private var permissionPoll: Job? = null
    private val carCleanup = mutableListOf<() -> Unit>()
    private val cleanup = mutableListOf<() -> Unit>()
    @Volatile private var vehicle: Vehicle? = null
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
    private fun steeringReceived(raw: Float?, delivery: SdkDelivery) {
        val receipt = clock.elapsedRealtimeMs()
        post { store.receiveSteeringSdkValue(raw, delivery, receipt); emit("STEERING_DEG") }
    }
    private fun boundedIntReceived(key: String, raw: Int?, delivery: SdkDelivery, diagnosticOnly: Boolean = false) {
        val receipt = clock.elapsedRealtimeMs()
        post { store.receiveBoundedInt(key, raw, delivery, receipt, diagnosticOnly); emit(key) }
    }
    private fun checked(key: String, config: CapabilityConfig, extra: String = "", subscribe: () -> Unit) = post {
        val readable = config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE
        store.capability(key, readable, "${config.accessType} $extra"); emit(key)
        val permitted = store.readings().first { it.key == key }.permissionGranted
        if (readable && permitted) try { subscribe() } catch (e: Exception) { failure(key, e) }
    }
    private fun attempt(key: String, action: () -> Unit) { try { action() } catch (e: Exception) { failure(key, e) } }
    private fun attemptGroup(keys: List<String>, action: () -> Unit) {
        try { action() } catch (e: Exception) { keys.forEach { failure(it, e) } }
    }

    fun start() {
        check(!started) { "어댑터는 한 세션에서 한 번만 시작합니다. 재연결은 새 인스턴스를 사용하세요." }
        started = true; active = true; mutableStatus.value = "연결 요청 · capability 확인 중"
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
        }
        permission("SEAT_TEMPERATURE:ROW_1_LEFT", "pleos.car.permission.CAR_SEATS")
        NaviField.entries.forEach { permission(it.name, "pleos.car.permission.NAVI_ROUTE") }
        store.readings().forEach { emit(it.key) }
        naviBus.value = store.naviState()
        // Vehicle 2.3.2의 capability 확인에는 Kotlin reflection 비용이 있다.
        // 기기 ANR에서 이 호출이 main을 막는 것을 확인해 초기 확인만 작업 스레드로 분리한다.
        val vehicleKeys = store.readings().filter { it.permission.startsWith("pleos.car") && !it.permission.endsWith("NAVI_ROUTE") }.map { it.key }
        vehicleBootstrap = scope.launch(Dispatchers.IO) {
            try { connectVehicle() }
            catch (e: Exception) { vehicleKeys.forEach { failure(it, e) } }
            finally { if (!active) runCatching { vehicle?.release() } }
        }
        try { connectCar() } catch (e: Exception) {
            listOf("SPEED_KPH", "GEAR").forEach { failure(it, e) }
        }
        try { connectNavi() } catch (e: Exception) { NaviField.entries.forEach { failure(it.name, e) } }
        mutableStatus.value = "연결 요청 완료 · 신호별 지원/오류를 확인하세요"
        permissionPoll = scope.launch {
            while (isActive && active) {
                store.readings().filter { it.permissionGranted && context.checkSelfPermission(it.permission) != PackageManager.PERMISSION_GRANTED }
                    .forEach { store.block(it.key, SignalAvailability.PERMISSION_DENIED, "수집 중 권한 상실 · 재연결 필요"); emit(it.key) }
                delay(policy.stateRequeryMs)
            }
        }
    }
    private fun connectVehicle() {
        val v = Vehicle(context.applicationContext).also { vehicle = it; it.initialize() }
        val s = v.getSteeringWheel()
        val steering = object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(angle: Float?) = steeringReceived(angle, SdkDelivery.CALLBACK)
            override fun onFailed(e: Exception) = failure("STEERING_DEG", e)
        }
        attempt("STEERING_DEG") { s.checkSteeringWheelAngleCapabilities({ c, b -> checked("STEERING_DEG", c, b.toString()) {
            s.getCurrentSteeringWheelAngle({ steeringReceived(it, SdkDelivery.QUERY) }, { failure("STEERING_DEG", it) })
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
        attemptGroup(seats.map { "SEAT:${it.name}" }) { seat.checkSeatOccupancyCapabilities({ c: CapabilityConfig, areas: List<SeatArea> ->
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
        val driver = SeatArea(SeatValue.ROW_1_LEFT)
        attempt("VENTILATION_LEVEL") { seat.checkSeatVentilationCapabilities({ c, areas ->
            if (driver !in areas) post { store.capability("VENTILATION_LEVEL", false, "운전석 개별 영역 미지원"); emit("VENTILATION_LEVEL") }
            else checked("VENTILATION_LEVEL", c, "ROW_1_LEFT ${areas[driver]}") {
                val bounds = areas.getValue(driver)
                require(bounds.minValue >= 0) { "통풍 단계는 비음수여야 합니다." }
                store.bounds("VENTILATION_LEVEL", bounds.minValue, bounds.maxValue)
                val listener = object : HvacSeatVentilationListener {
                    override fun onHvacSeatVentilationUpdated(ventilation: Int?) = boundedIntReceived("VENTILATION_LEVEL", ventilation, SdkDelivery.CALLBACK)
                    override fun onFailed(e: Exception) = failure("VENTILATION_LEVEL", e)
                }
                seat.getHvacSeatVentilationState(driver, { boundedIntReceived("VENTILATION_LEVEL", it, SdkDelivery.QUERY) }, { failure("VENTILATION_LEVEL", it) })
                cleanup += { seat.unregisterHvacSeatVentilation(driver, listener) }; seat.registerHvacSeatVentilation(driver, listener)
            }
        }, { failure("VENTILATION_LEVEL", it) }) }
        // Console의 V1/V3는 이 온열/냉각 속성이다. 별도 통풍 단계로 바꿔 쓰지 않는다.
        val temperatureKey = "SEAT_TEMPERATURE:ROW_1_LEFT"
        attempt(temperatureKey) { seat.checkSeatTemperatureCapabilities({ c, areas ->
            if (driver !in areas) post { store.capability(temperatureKey, false, "운전석 개별 영역 미지원"); emit(temperatureKey) }
            else checked(temperatureKey, c, "ROW_1_LEFT ${areas[driver]} · 별도 온열/냉각 진단") {
                val bounds = areas.getValue(driver)
                store.bounds(temperatureKey, bounds.minValue, bounds.maxValue)
                val listener = object : HvacSeatTemperatureListener {
                    override fun onHvacSeatTemperatureUpdated(temperature: Int?) =
                        boundedIntReceived(temperatureKey, temperature, SdkDelivery.CALLBACK, diagnosticOnly = true)
                    override fun onFailed(e: Exception) = failure(temperatureKey, e)
                }
                seat.getHvacSeatTemperature(driver, { boundedIntReceived(temperatureKey, it, SdkDelivery.QUERY, diagnosticOnly = true) }, { failure(temperatureKey, it) })
                cleanup += { seat.unregisterHvacSeatTemperature(driver, listener) }; seat.registerHvacSeatTemperature(driver, listener)
            }
        }, { failure(temperatureKey, it) }) }
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
            if (!ready) {
                carReady = false; carGeneration++; gearPoll?.cancel(); gearPoll = null
                carCleanup.asReversed().forEach { runCatching(it) }; carCleanup.clear()
                listOf("SPEED_KPH", "GEAR").forEach { store.block(it, SignalAvailability.ERROR, "CarService 연결 해제"); emit(it) }
            }
            else {
                if (carReady) return@post
                carReady = true
                val generation = ++carGeneration
                fun current() = active && carReady && carGeneration == generation
                val manager = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                mapOf(VehiclePropertyIds.PERF_VEHICLE_SPEED to "SPEED_KPH", VehiclePropertyIds.GEAR_SELECTION to "GEAR").forEach { (id, key) ->
                    attempt(key) {
                        store.beginCollection(key)
                        if (context.checkSelfPermission(store.readings().first { it.key == key }.permission) != PackageManager.PERMISSION_GRANTED) {
                            store.failed(key, SignalAvailability.PERMISSION_DENIED, "권한 없음"); emit(key)
                        } else {
                            val config = manager.getCarPropertyConfig(id)
                            if (config == null || config.access and 1 == 0) { store.capability(key, false, "AAOS property 읽기 미지원"); emit(key) }
                            else {
                                store.capability(key, true, "AAOS READ mode=${config.changeMode} maxHz=${config.maxSampleRate}"); emit(key)
                                fun deliver(value: CarPropertyValue<*>, kind: SdkDelivery, queryStartedMs: Long? = null,
                                    auditAttempt: GearQueryAttempt? = null) {
                                    val receipt = clock.elapsedRealtimeMs()
                                    if (auditAttempt != null) gearAudit.responded(auditAttempt, receipt)
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
                                    post {
                                        if (current()) {
                                            val before = if (key == "GEAR") store.quality(key) else null
                                            store.receive(key, raw, normalized, kind, value.timestamp.takeIf { it > 0 }?.div(1_000_000),
                                                if (key == "SPEED_KPH") "부호 있는 m/s → 속력 abs × 3.6 km/h" else "VehicleGear → P/R/N/D · ON_CHANGE 재확인",
                                                availability, receipt, queryStartedMs = queryStartedMs)
                                            if (before != null) {
                                                val after = store.quality(key)
                                                gearAudit.applied(auditAttempt, receipt, before, after,
                                                    store.vehicleInput(key)?.gear, after.stateConfirmedAtElapsedMs == receipt,
                                                    "${kind.name} · 반영 시 현재 세대 확인")
                                            }
                                            emit(key)
                                        }
                                    }
                                }
                                fun carFailure(error: Throwable, auditAttempt: GearQueryAttempt? = null) {
                                    val responseMs = clock.elapsedRealtimeMs()
                                    if (auditAttempt != null) gearAudit.responded(auditAttempt, responseMs, error = true)
                                    post {
                                    if (current()) {
                                        val before = if (key == "GEAR") store.quality(key) else null
                                        store.block(key, if (error is SecurityException) SignalAvailability.PERMISSION_DENIED else SignalAvailability.ERROR,
                                            "${error.javaClass.simpleName} · 구독/조회 실패, 재연결 필요")
                                        if (before != null) gearAudit.applied(auditAttempt, responseMs, before, store.quality(key),
                                            null, false, error.javaClass.simpleName)
                                        emit(key)
                                    }
                                    }
                                }
                                val callback = object : CarPropertyManager.CarPropertyEventCallback {
                                    override fun onChangeEvent(value: CarPropertyValue<*>) = deliver(value, SdkDelivery.CALLBACK)
                                    override fun onErrorEvent(propId: Int, areaId: Int) = carFailure(IllegalStateException("AAOS property error $propId/$areaId"))
                                }
                                carCleanup += { manager.unregisterCallback(callback, id) }
                                if (!manager.registerCallback(callback, id, 1_000f / EngineConfig().signalFrameIntervalMs)) {
                                    carFailure(IllegalStateException("AAOS 구독 거절")); return@attempt
                                }
                                // 최초 값 조회와 구독 이벤트 계수는 분리한다.
                                val initialAudit = if (key == "GEAR") gearAudit.resumed(gearAudit.schedule(generation,
                                    SdkDelivery.QUERY, clock.elapsedRealtimeMs())) else null
                                scope.launch(Dispatchers.IO) {
                                    val attempt = initialAudit?.let { gearAudit.ioStarted(it) }
                                    try { deliver(manager.getProperty<Any>(id, 0), SdkDelivery.QUERY, auditAttempt = attempt) }
                                    catch (e: Exception) { carFailure(e, attempt) }
                                }
                                if (key == "GEAR") gearPoll = launchGearRequeryLoop(scope, clock,
                                    policy.stateRequeryMs, generation, gearAudit, ::current) { attempt, queryStarted ->
                                    try { deliver(manager.getProperty<Any>(id, 0), SdkDelivery.REQUERY, queryStarted, attempt) }
                                    catch (e: Exception) { carFailure(e, attempt) }
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
                    if (!routeGate.acceptsRouteValues()) clearRoute("활성 경로 상태 미수신/만료")
                    attemptGroup(listOf("LAT", "LON", "HEADING_DEG")) { n.getCurrentLocationInfo() }
                    try { n.getRouteStateInfo() } catch (e: Exception) {
                        routeGate.clear(); clearRoute("경로 상태 조회 실패")
                        listOf("REMAIN_TIME_SEC", "REMAIN_DIST_M", "DIST_TO_NEXT_TURN_M").forEach { failure(it, e) }
                    }
                    attemptGroup(listOf("REMAIN_TIME_SEC", "REMAIN_DIST_M")) { n.getDestinationInfo() }
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
        active = false; carReady = false; carGeneration++
        vehicleBootstrap?.cancel(); gearPoll?.cancel(); permissionPoll?.cancel(); poll?.cancel(); poll = null
        carCleanup.asReversed().forEach { runCatching(it) }; carCleanup.clear()
        store.readings().forEach { store.block(it.key, SignalAvailability.UNAVAILABLE, "SDK 세션 종료") }
        cleanup.asReversed().forEach { runCatching(it) }; cleanup.clear()
        runCatching { navi?.release() }; runCatching { vehicle?.release() }; runCatching { car?.disconnect() }
        navi = null; vehicle = null; car = null
        handler.removeCallbacksAndMessages(null)
        mutableStatus.value = "중지 · 콜백 해제/연결 해제 · 과거 캐시 사용 안 함"
    }
}
