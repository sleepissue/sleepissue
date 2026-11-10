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
class PlaygroundSignalAdapter internal constructor(private val context: Context, private val clock: Clock,
    val policy: SdkProbePolicy, private val scope: CoroutineScope,
    private val readFeaturesFactory: (Vehicle) -> VehicleReadFeatures = ::PleosVehicleReadFeatures,
    naviTimingObserver: ((SdkNaviTimingPoint) -> Unit)? = null) {
    private val naviAudit = naviTimingObserver?.let { SdkNaviTimingAudit(clock, it) }
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
    private val stateSubscriptions = java.util.concurrent.ConcurrentHashMap<String, SdkStateSubscription<*>>()
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
    val naviSource = object : NaviSignalSource {
        override val states = naviBus.asStateFlow()
        override val stateReportsOnly = true
    }
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
        if (key == "LAT" || key == "LON") store.clearLocationHistory()
        val availability = if (error is SecurityException) SignalAvailability.PERMISSION_DENIED else SignalAvailability.ERROR
        if (key in sdkRetainedStateKeys) {
            stateSubscriptions[key]?.close()
            store.block(key, availability, "${error.javaClass.simpleName} · 재연결 필요")
        } else store.failed(key, availability, "${error.javaClass.simpleName}: ${error.message?.take(180)}")
        emit(key)
    }
    private fun received(key: String, raw: Any?, normalized: Any?, delivery: SdkDelivery,
        nativeMs: Long? = null, note: String = "", availability: SignalAvailability? = null) {
        val receipt = clock.elapsedRealtimeMs()
        post { if (store.receive(key, raw, normalized, delivery, nativeMs, note, availability, receipt)) emit(key) }
    }
    private fun seatReceived(key: String, raw: Boolean?, delivery: SdkDelivery) {
        val receipt = clock.elapsedRealtimeMs()
        post { if (store.receiveSeatSdkValue(key, raw, delivery, receipt)) emit(key) }
    }
    private fun steeringReceived(raw: Float?, delivery: SdkDelivery) {
        val receipt = clock.elapsedRealtimeMs()
        post { if (store.receiveSteeringSdkValue(raw, delivery, receipt)) emit("STEERING_DEG") }
    }
    private fun boundedIntReceived(key: String, raw: Int?, delivery: SdkDelivery, diagnosticOnly: Boolean = false) {
        val receipt = clock.elapsedRealtimeMs()
        post { if (store.receiveBoundedInt(key, raw, delivery, receipt, diagnosticOnly)) emit(key) }
    }
    private fun checked(key: String, config: CapabilityConfig, extra: String = "", subscribe: () -> Unit) = post {
        checkedRead(key, SdkReadCapability(config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE,
            "${config.accessType} $extra"), subscribe = subscribe)
    }
    private fun checkedRead(key: String, capability: SdkReadCapability, disabledLogKey: String = key, subscribe: () -> Unit) {
        val readable = capability.readable
        store.capability(key, readable, capability.description, disabledLogKey); emit(key)
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
            permission("BELT:$it", "pleos.car.permission.CAR_SEATS")
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
                    .forEach { stateSubscriptions[it.key]?.close(); store.block(it.key, SignalAvailability.PERMISSION_DENIED, "수집 중 권한 상실 · 재연결 필요"); emit(it.key) }
                delay(policy.stateRequeryMs)
            }
        }
    }
    private fun <T> stateSubscription(key: String, normalize: (T?) -> Any?,
        query: ((T?) -> Unit, (Exception) -> Unit) -> Unit, note: String): SdkStateSubscription<T> {
        val delivery = SdkStateDelivery(store, key, normalize, note, ::emit)
        val subscription = SdkStateSubscription(scope, clock, policy, query,
            deliver = { raw, kind, receipt, began, order, valid -> post {
                delivery.deliver(raw, kind, receipt, began, order, valid)
            } },
            failed = { error -> post { delivery.failed(error) } })
        stateSubscriptions[key] = subscription
        if (!active) subscription.close()
        return subscription
    }
    private fun connectVehicle() {
        val v = Vehicle(context.applicationContext).also { vehicle = it; it.initialize() }
        val reads = readFeaturesFactory(v)
        attempt("STEERING_DEG") { reads.steering.check({ capability -> post {
            checkedRead("STEERING_DEG", capability) {
                reads.steering.query({ steeringReceived(it, SdkDelivery.QUERY) }, { failure("STEERING_DEG", it) })
                cleanup += reads.steering.subscribe({ steeringReceived(it, SdkDelivery.CALLBACK) }, { failure("STEERING_DEG", it) })
            }
        } }, { failure("STEERING_DEG", it) }) }
        val t = v.getTurnSignal()
        val turnState = stateSubscription("TURN_SIGNAL", SdkStateNormalization::turnSignal,
            t::getCurrentTurnSignalState, "OFF/LEFT/RIGHT · 현재값 재확인")
        val turn = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(state: TurnSignalValue?) = turnState.callback(state)
            override fun onFailed(e: Exception) = turnState.error(e)
        }
        attempt("TURN_SIGNAL") { t.checkTurnSignalStateCapabilities({ c -> checked("TURN_SIGNAL", c) {
            cleanup += { t.unregisterTurnSignalState(turn) }; t.registerTurnSignalState(turn)
            turnState.start()
        } }, { turnState.error(it) }) }
        val b = v.getBrake()
        val brakeState = stateSubscription<Boolean>("BRAKE_ON", { it }, b::getCurrentBrakePedalStatus,
            "Boolean? 그대로 · false는 유효한 해제 상태")
        val brake = object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(status: Boolean?) = brakeState.callback(status)
            override fun onFailed(e: Exception) = brakeState.error(e)
        }
        attempt("BRAKE_ON") { b.checkBrakePedalStatusCapabilities({ c -> checked("BRAKE_ON", c) {
            cleanup += { b.unregisterBrakePedalStatus(brake) }; b.registerBrakePedalStatus(brake)
            brakeState.start()
        } }, { brakeState.error(it) }) }
        val d = v.getDrivingMode()
        val cruiseState = stateSubscription("CRUISE_ACTIVE", SdkStateNormalization::cruiseActive,
            d::getCruiseControlState, "ACTIVATED=true, ENABLED/SUSPENDED=false, 그 외 미확정 null")
        val cruise = object : CruiseControlStateListener {
            override fun onCruiseControlStateUpdated(state: CruiseControl?) = cruiseState.callback(state)
            override fun onFailed(e: Exception) = cruiseState.error(e)
        }
        attempt("CRUISE_ACTIVE") { d.checkCruiseControlStateCapabilities({ c -> checked("CRUISE_ACTIVE", c) {
            cleanup += { d.unregisterCruiseControlState(cruise) }; d.registerCruiseControlState(cruise)
            cruiseState.start()
        } }, { cruiseState.error(it) }) }
        val targetState = stateSubscription("CRUISE_TARGET_KPH", SdkStateNormalization::targetKph,
            d::getCruiseControlTargetSpeed, "m/s × 3.6 → km/h · 0도 유효")
        val target = object : CruiseControlTargetSpeedListener {
            override fun onCruiseControlTargetSpeedUpdated(speed: Float?) = targetState.callback(speed)
            override fun onFailed(e: Exception) = targetState.error(e)
        }
        attempt("CRUISE_TARGET_KPH") { d.checkCruiseControlTargetSpeedCapabilities({ c -> checked("CRUISE_TARGET_KPH", c) {
            cleanup += { d.unregisterCruiseControlTargetSpeed(target) }; d.registerCruiseControlTargetSpeed(target)
            targetState.start()
        } }, { targetState.error(it) }) }
        connectSeats(v, reads)
    }
    private fun connectSeats(v: Vehicle, reads: VehicleReadFeatures) {
        val seat = v.getSeat()
        val seats = listOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT)
        attemptGroup(seats.map { "SEAT:${it.name}" }) { reads.occupancy.check({ capability, areas ->
            seats.forEach { position ->
                val key = "SEAT:${position.name}"
                // 결합 영역을 개별 좌석으로 오인하지 않는다.
                post {
                    val supported = position in areas
                    checkedRead(key, if (supported) capability else SdkReadCapability(false, "개별 영역 미지원"),
                        disabledLogKey = if (!capability.readable) "SEATS" else key) {
                        reads.occupancy.query(position, { seatReceived(key, it, SdkDelivery.QUERY) }, { failure(key, it) })
                        cleanup += reads.occupancy.subscribe(position, { seatReceived(key, it, SdkDelivery.CALLBACK) }, { failure(key, it) })
                    }
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
        attempt("VENTILATION_LEVEL") { reads.ventilation.check({ capability -> post {
            checkedRead("VENTILATION_LEVEL", capability) {
                val min = requireNotNull(capability.minValue)
                val max = requireNotNull(capability.maxValue)
                require(min >= 0) { "통풍 단계는 비음수여야 합니다." }
                store.bounds("VENTILATION_LEVEL", min, max)
                val state = stateSubscription<Int>("VENTILATION_LEVEL",
                    { SdkStateNormalization.ventilation(it, min, max) }, reads.ventilation::query,
                    "SDK 범위 $min..$max · 실제 통풍 단계, 0=꺼짐")
                cleanup += reads.ventilation.subscribe(state::callback, state::error)
                state.start()
            }
        } }, { failure("VENTILATION_LEVEL", it) }) }
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
                val state = stateSubscription("CABIN_TEMP_C", SdkStateNormalization::cabinTemperature,
                    { success, error -> h.getHvacTemperatureCurrent(driver, success, error) },
                    "현재 실내온도 °C (설정온도 아님)")
                val listener = object : HvacTemperatureListener {
                    override fun onHvacTemperatureUpdated(temperature: Float?) = state.callback(temperature)
                    override fun onFailed(e: Exception) = state.error(e)
                }
                cleanup += { h.unregisterHvacTemperatureCurrent(driver, listener) }; h.registerHvacTemperatureCurrent(driver, listener)
                state.start()
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
                                val delivery = SdkCarDelivery(store, key, ::emit)
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
                                            val accepted = delivery.deliver(raw, normalized, kind,
                                                value.timestamp.takeIf { it > 0 }?.div(1_000_000), receipt, queryStartedMs,
                                                if (key == "SPEED_KPH") "부호 있는 m/s → 속력 abs × 3.6 km/h" else "VehicleGear → P/R/N/D · ON_CHANGE 재확인",
                                                availability)
                                            if (before != null) {
                                                val after = store.quality(key)
                                                gearAudit.applied(auditAttempt, receipt, before, after,
                                                    store.vehicleInput(key)?.gear, accepted && after.stateConfirmedAtElapsedMs == receipt,
                                                    "${kind.name} · 반영 시 현재 세대 확인")
                                            }
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
        val reports = SdkNaviDelivery(store, routeGate) { naviBus.value = store.naviState() }
        val listener = object : NaviHelperEventListener {
            override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
                val receipt = clock.elapsedRealtimeMs()
                naviAudit?.point("CALLBACK", "route", receipt, mode = routeStateInfo.naviMode.toString())
                post {
                    naviAudit?.point("ADAPTER_BEGIN", "route", mode = routeStateInfo.naviMode.toString())
                    reports.route(routeStateInfo, receipt)
                    naviAudit?.point("ADAPTER_END", "route", mode = routeStateInfo.naviMode.toString())
                }
            }
            override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
                val receipt = clock.elapsedRealtimeMs()
                post { reports.location(currentLocationInfo.latitude, currentLocationInfo.longitude,
                    currentLocationInfo.heading.toDouble(), receipt) }
            }
            override fun onDestinationInfo(destinationInfo: DestinationInfo) {
                val receipt = clock.elapsedRealtimeMs()
                naviAudit?.point("CALLBACK", "destination", receipt,
                    seconds = destinationInfo.remainTime, meters = destinationInfo.remainDistance)
                post {
                    naviAudit?.point("ADAPTER_BEGIN", "destination", seconds = destinationInfo.remainTime, meters = destinationInfo.remainDistance)
                    reports.destination(destinationInfo.remainTime, destinationInfo.remainDistance, receipt)
                    naviAudit?.point("ADAPTER_END", "destination", seconds = destinationInfo.remainTime, meters = destinationInfo.remainDistance)
                }
            }
            override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
                val receipt = clock.elapsedRealtimeMs()
                post { reports.turns(tbtInfoList.map { it.distance }, receipt) }
            }
            override fun onRouteCancelled() = post { reports.cancel("경로 취소") }
            override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = post { reports.cancel("도착") }
            override fun onError(errorCode: NaviErrorCode) = post { reports.failed(IllegalStateException(errorCode.toString())) }
        }
        n.addListener(listener); cleanup += { n.removeListener(listener) }
        n.initialize(onConnected = { post {
            NaviField.entries.forEach { store.capability(it.name, true, "NaviHelper 연결/권한 확인; 별도 capability API 없음") }
            poll = scope.launch {
                while (isActive && active) {
                    naviAudit?.point("POLL_BEGIN", "poll")
                    // 요청 자체는 센서 관측이 아니다. 응답 이벤트에만 수신 시각을 부여한다.
                    if (!routeGate.acceptsRouteValues()) clearRoute("활성 경로 상태 미수신/만료")
                    attemptGroup(listOf("LAT", "LON", "HEADING_DEG")) { traceNaviCall(naviAudit, "location") { n.getCurrentLocationInfo() } }
                    try { traceNaviCall(naviAudit, "route") { n.getRouteStateInfo() } } catch (e: Exception) {
                        routeGate.clear(); clearRoute("경로 상태 조회 실패")
                        listOf("REMAIN_TIME_SEC", "REMAIN_DIST_M", "DIST_TO_NEXT_TURN_M").forEach { failure(it, e) }
                    }
                    attemptGroup(listOf("REMAIN_TIME_SEC", "REMAIN_DIST_M")) { traceNaviCall(naviAudit, "destination") { n.getDestinationInfo() } }
                    attempt("DIST_TO_NEXT_TURN_M") { traceNaviCall(naviAudit, "tbt") { n.getTBTInfo() } }
                    naviAudit?.point("TIMER_ARMED", "poll")
                    val sleepStarted = if (naviAudit != null) clock.elapsedRealtimeMs() else -1L
                    delay(policy.naviPollMs)
                    naviAudit?.point("TIMER_RESUMED", "poll", dueAtMs = sleepStarted + policy.naviPollMs)
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
        stateSubscriptions.values.forEach { it.close() }; stateSubscriptions.clear()
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
