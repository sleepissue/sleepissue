package com.sleepissue.awakedrive.pleos

import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.signal.*
import kotlin.math.*

enum class SdkDelivery { QUERY, CALLBACK }

/** 원시 진단은 Hub 재발행과 독립적이다. 수신 Hz는 센서 Hz라는 주장을 하지 않는다. */
data class SdkReading(
    val key: String, val permission: String, val permissionGranted: Boolean,
    val support: String = "확인 중", val raw: String = "미수신", val value: Any? = null,
    val availability: SignalAvailability = SignalAvailability.UNAVAILABLE,
    val note: String = "", val receivedMs: Long? = null, val nativeMs: Long? = null,
    val callbacks: Long = 0, val queries: Long = 0,
)

/** TTL은 이 검증 세션의 명시적 가정이다. 제품 설정으로 사용하지 않는다. */
data class SdkProbePolicy(val ttlMs: Long, val naviPollMs: Long) {
    init { require(ttlMs > 0 && naviPollMs > 0) }
}

class SdkSignalStore(private val clock: Clock, val policy: SdkProbePolicy,
    private val config: EngineConfig = EngineConfig()) {
    private val data = linkedMapOf<String, SdkReading>()
    private val arrivals = mutableMapOf<String, ArrayDeque<Long>>()
    private val nativeTimes = mutableMapOf<String, ArrayDeque<Long>>()
    private val lastNative = mutableMapOf<String, Long>()
    private val badNative = mutableSetOf<String>()
    private val started = clock.elapsedRealtimeMs()
    private val fixes = ArrayDeque<Triple<Long, Double, Double>>()
    private val changes = ArrayDeque<String>()
    fun history(): List<String> = changes.toList()
    fun readings(): List<SdkReading> = data.values.toList()
    fun declare(key: String, permission: String, granted: Boolean) {
        data[key] = SdkReading(key, permission, granted,
            availability = if (granted) SignalAvailability.UNAVAILABLE else SignalAvailability.PERMISSION_DENIED)
    }
    fun capability(key: String, readable: Boolean, description: String = "READ") {
        val r = data.getValue(key)
        data[key] = r.copy(support = description, availability = when {
            !r.permissionGranted -> SignalAvailability.PERMISSION_DENIED
            !readable -> SignalAvailability.UNSUPPORTED
            else -> SignalAvailability.UNAVAILABLE
        })
    }
    fun failed(key: String, availability: SignalAvailability, note: String) {
        val old = data.getValue(key)
        data[key] = old.copy(value = null,
            availability = if (old.permissionGranted) availability else SignalAvailability.PERMISSION_DENIED, note = note)
    }
    /** SDK 2.3.2는 원시 좌석 상태를 ==1로 축약해 null/UNKNOWN도 false가 될 수 있다. */
    fun receiveSeatSdkValue(key: String, raw: Boolean?, delivery: SdkDelivery, receivedMs: Long) {
        receive(key, raw, null, delivery, note = "SDK 좌석 Boolean은 UNKNOWN/착석/빈 좌석을 복원할 수 없음 · 의미 확인 전 정규화 보류",
            availability = SignalAvailability.UNSUPPORTED, receivedMs = receivedMs)
    }
    fun receive(key: String, raw: Any?, value: Any?, delivery: SdkDelivery,
        nativeMs: Long? = null, note: String = "", availability: SignalAvailability? = null,
        receivedMs: Long = clock.elapsedRealtimeMs()) {
        val old = data.getValue(key)
        // capability 조회나 늦은 콜백이 권한 거부를 AVAILABLE로 바꾸면 안 된다.
        if (!old.permissionGranted) return
        // 구독 콜백 뒤 늦게 돌아온 최초 조회는 더 최신인 콜백을 덮지 않는다.
        if (delivery == SdkDelivery.QUERY && old.callbacks > 0) {
            data[key] = old.copy(queries = old.queries + 1); return
        }
        val now = receivedMs
        if (delivery == SdkDelivery.CALLBACK) {
            arrivals.getOrPut(key) { ArrayDeque() }.addLast(now)
            if (nativeMs != null) {
                val previous = lastNative[key]
                if (nativeMs > now || previous != null && nativeMs < previous) badNative += key
                if (nativeMs <= now && (previous == null || nativeMs > previous)) {
                    nativeTimes.getOrPut(key) { ArrayDeque() }.addLast(nativeMs)
                }
                lastNative[key] = nativeMs
            }
            // 표시 고정 중에도 10초 창 밖의 자료가 계속 쌓이지 않게 한다.
            val lower = clock.elapsedRealtimeMs() - config.steeringRateWindowMs
            listOfNotNull(arrivals[key], nativeTimes[key]).forEach { times ->
                while (times.isNotEmpty() && times.first() <= lower) times.removeFirst()
            }
        }
        data[key] = old.copy(raw = raw?.toString() ?: "null", value = value, receivedMs = now,
            nativeMs = nativeMs, note = note,
            availability = availability ?: if (value == null) SignalAvailability.UNAVAILABLE else SignalAvailability.AVAILABLE,
            callbacks = old.callbacks + if (delivery == SdkDelivery.CALLBACK) 1 else 0,
            queries = old.queries + if (delivery == SdkDelivery.QUERY) 1 else 0)
        val updated = data.getValue(key)
        if (old.raw != updated.raw || old.value != updated.value || old.availability != updated.availability) {
            changes.addLast("${now}ms · REAL/$delivery · $key · ${updated.raw} → ${updated.value ?: "null"} · ${updated.availability} · 원천 시각 ${nativeMs ?: "미제공"}")
            while (changes.size > 100) changes.removeFirst()
        }
    }
    fun callbackHz(key: String): Float? = if (data[key]?.let {
        !it.permissionGranted || it.availability == SignalAvailability.ERROR ||
            it.callbacks == 0L && (it.support == "확인 중" || it.availability == SignalAvailability.UNSUPPORTED)
    } != false) null else rate(arrivals[key])
    fun nativeHz(key: String): Float? = if (data[key]?.nativeMs == null || key in badNative) null else rate(nativeTimes[key])
    private fun rate(times: ArrayDeque<Long>?): Float? {
        val now = clock.elapsedRealtimeMs()
        times?.let { while (it.isNotEmpty() && it.first() <= now - config.steeringRateWindowMs) it.removeFirst() }
        return if (now - started < config.steeringRateWindowMs) null else (times?.size ?: 0) * 1_000f / config.steeringRateWindowMs
    }
    fun quality(key: String): SignalQuality {
        val r = data.getValue(key)
        return SignalQuality(r.availability, Source.REAL, r.nativeMs, r.receivedMs,
            observedTimeBasis = if (r.nativeMs == null) ObservationTimeBasis.UNKNOWN else ObservationTimeBasis.ELAPSED_REALTIME,
            observationSequence = r.callbacks + r.queries)
    }

    /** capability가 있고 TTL 안에 받은 좌석별 안전벨트 값만 반환한다. */
    fun seatBelts(): SeatBeltState? {
        val beltRows = data.values.filter { it.key.startsWith("BELT:") && it.value is Boolean }
        fun belt(name: String): Boolean? = beltRows.find { it.key == "BELT:$name" }?.let {
            (it.value as? Boolean).takeIf { _ ->
                it.availability == SignalAvailability.AVAILABLE &&
                    it.receivedMs?.let { ms -> clock.elapsedRealtimeMs() - ms <= policy.ttlMs } == true
            }
        }
        if (beltRows.isEmpty()) return null
        return SeatBeltState(
            driver = belt("ROW_1_LEFT"),
            frontPassenger = belt("ROW_1_RIGHT"),
            rearLeft = belt("ROW_2_LEFT"),
            rearCenter = belt("ROW_2_CENTER"),
            rearRight = belt("ROW_2_RIGHT"),
        )
    }
    fun vehicleInput(key: String): VehicleSignalInput? {
        val field = runCatching { SignalField.valueOf(key) }.getOrNull()
            ?: if (key.startsWith("SEAT:")) SignalField.SEATS else return null
        var q = quality(key)
        val r = data.getValue(key)
        val seatRows = data.values.filter { it.key.startsWith("SEAT:") && it.value != null }
        fun seat(name: String) = seatRows.find { it.key == "SEAT:$name" }?.let {
            (it.value as? Boolean).takeIf { _ -> it.receivedMs?.let { ms -> clock.elapsedRealtimeMs() - ms <= policy.ttlMs } == true }
        }
        val seats = if (field == SignalField.SEATS && seatRows.isNotEmpty()) SeatOccupancy(
            seat("ROW_1_LEFT"), seat("ROW_1_RIGHT"), seat("ROW_2_LEFT"), seat("ROW_2_CENTER"), seat("ROW_2_RIGHT")) else null
        if (seats != null) q = SignalQuality(SignalAvailability.AVAILABLE, Source.REAL,
            receivedAtElapsedMs = seatRows.mapNotNull { it.receivedMs }.minOrNull())
        return VehicleSignalInput(clock.scenarioTimeMs(), Source.REAL,
            steeringDeg = null, // SDK 전륜각을 운전대 회전각으로 간주하지 않는다.
            speed = if (field == SignalField.SPEED_KPH) r.value as? Float else null,
            gear = if (field == SignalField.GEAR) r.value as? Gear else null,
            turnSignal = if (field == SignalField.TURN_SIGNAL) r.value as? TurnSignal else null,
            brakeOn = if (field == SignalField.BRAKE_ON) r.value as? Boolean else null,
            cruiseActive = if (field == SignalField.CRUISE_ACTIVE) r.value as? Boolean else null,
            cruiseTargetKph = if (field == SignalField.CRUISE_TARGET_KPH) r.value as? Float else null,
            seats = seats,
            ventilationLevel = if (field == SignalField.VENTILATION_LEVEL) r.value as? Int else null,
            cabinTempC = if (field == SignalField.CABIN_TEMP_C) r.value as? Float else null,
            quality = mapOf(field to q), updatedFields = setOf(field))
    }
    fun naviState(): NaviState {
        fun v(f: NaviField) = data[f.name]?.value.takeIf { data[f.name]?.availability == SignalAvailability.AVAILABLE }
        return NaviState(clock.scenarioTimeMs(), v(NaviField.LAT) as? Double, v(NaviField.LON) as? Double,
            v(NaviField.HEADING_DEG) as? Float, v(NaviField.REMAIN_TIME_SEC) as? Long,
            v(NaviField.REMAIN_DIST_M) as? Float, v(NaviField.DIST_TO_NEXT_TURN_M) as? Float,
            NaviField.entries.associateWith { quality(it.name) })
    }
    /** SDK heading의 단위는 미확인이다. MSG-14에 따라 최근 1분 위치로 방위를 계산한다. */
    fun locationHeading(lat: Double, lon: Double, receivedAtMs: Long = clock.elapsedRealtimeMs()): Float? {
        val now = receivedAtMs
        while (fixes.isNotEmpty() && fixes.first().first < now - config.headingWindowMs) fixes.removeFirst()
        val origin = fixes.firstOrNull()
        fixes.addLast(Triple(now, lat, lon))
        if (origin == null || origin.second == lat && origin.third == lon) return null
        val a = Math.toRadians(origin.second); val b = Math.toRadians(lat)
        val d = Math.toRadians(lon - origin.third)
        return ((Math.toDegrees(atan2(sin(d) * cos(b), cos(a) * sin(b) - sin(a) * cos(b) * cos(d))) + 360) % 360).toFloat()
    }
}
