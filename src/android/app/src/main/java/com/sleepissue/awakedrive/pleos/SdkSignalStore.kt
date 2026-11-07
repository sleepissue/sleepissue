package com.sleepissue.awakedrive.pleos

import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.signal.*
import kotlin.math.*

enum class SdkDelivery { QUERY, CALLBACK, REQUERY }
enum class SdkNormalization { UNASSESSED, NORMALIZED, SEMANTIC_UNCONFIRMED, LOSSY_SDK_BOOLEAN, DIAGNOSTIC_ONLY }

/** 원시 진단은 Hub 재발행과 독립적이다. 수신 Hz는 센서 Hz라는 주장을 하지 않는다. */
data class SdkReading(
    val key: String, val permission: String, val permissionGranted: Boolean,
    val support: String = "확인 중", val raw: String = "미수신", val value: Any? = null,
    val availability: SignalAvailability = SignalAvailability.UNAVAILABLE,
    val note: String = "", val receivedMs: Long? = null, val nativeMs: Long? = null,
    val callbacks: Long = 0, val queries: Long = 0,
    val apiReadable: Boolean? = null,
    val normalization: SdkNormalization = SdkNormalization.UNASSESSED,
    val rejectedDeliveries: Long = 0,
    val minValue: Int? = null, val maxValue: Int? = null,
    val confirmedMs: Long? = null, val observations: Long = 0,
)

/** TTL은 이 검증 세션의 명시적 가정이다. 제품 설정으로 사용하지 않는다. */
data class SdkProbePolicy(val ttlMs: Long, val naviPollMs: Long,
    val stateRequeryMs: Long = 1_000, val stateLeaseMs: Long = 3_000) {
    init { require(ttlMs > 0 && naviPollMs > 0 && stateRequeryMs > 0 && stateLeaseMs > stateRequeryMs) }
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
    private val lastStateLog = mutableMapOf<String, SignalAvailability>()
    private val blocked = mutableSetOf<String>()
    /** 구독/연결 오류는 이전 세션 배송으로 복구하지 않는다. 명시적 새 연결만 해제한다. */
    fun block(key: String, availability: SignalAvailability, note: String) {
        blocked += key
        failed(key, availability, note)
    }
    fun beginCollection(key: String) {
        blocked -= key
        val old = data.getValue(key)
        data[key] = old.copy(value = null, receivedMs = null, nativeMs = null, confirmedMs = null,
            availability = if (old.permissionGranted) SignalAvailability.UNAVAILABLE else SignalAvailability.PERMISSION_DENIED)
    }
    private fun appendHistory(text: String) {
        changes.addLast(text)
        while (changes.size > 100) changes.removeFirst()
    }
    private fun recordDisabledState(key: String) {
        val r = data.getValue(key)
        if (lastStateLog[key] == r.availability) return
        lastStateLog[key] = r.availability
        appendHistory("${clock.elapsedRealtimeMs()}ms · STATE · $key · ${r.availability} · 지원 ${r.support} · ${r.note}")
    }
    fun history(): List<String> = changes.toList()
    fun readings(): List<SdkReading> = data.values.toList()
    fun declare(key: String, permission: String, granted: Boolean) {
        data[key] = SdkReading(key, permission, granted,
            availability = if (granted) SignalAvailability.UNAVAILABLE else SignalAvailability.PERMISSION_DENIED)
        if (!granted) recordDisabledState(key)
    }
    fun capability(key: String, readable: Boolean, description: String = "READ") {
        val r = data.getValue(key)
        data[key] = r.copy(support = description, apiReadable = readable,
            value = r.value.takeIf { readable && r.permissionGranted }, availability = when {
            !r.permissionGranted -> SignalAvailability.PERMISSION_DENIED
            !readable -> SignalAvailability.UNSUPPORTED
            else -> if (r.apiReadable == false) SignalAvailability.UNAVAILABLE else r.availability
        })
        recordDisabledState(key)
    }
    fun failed(key: String, availability: SignalAvailability, note: String) {
        val old = data.getValue(key)
        data[key] = old.copy(value = null,
            confirmedMs = null,
            permissionGranted = old.permissionGranted && availability != SignalAvailability.PERMISSION_DENIED,
            availability = if (old.permissionGranted) availability else SignalAvailability.PERMISSION_DENIED, note = note)
        recordDisabledState(key)
    }
    /** SDK 2.3.2는 원시 좌석 상태를 ==1로 축약해 null/UNKNOWN도 false가 될 수 있다. */
    fun receiveSeatSdkValue(key: String, raw: Boolean?, delivery: SdkDelivery, receivedMs: Long) {
        receive(key, raw, null, delivery, note = "SDK 좌석 Boolean은 UNKNOWN/착석/빈 좌석을 복원할 수 없음 · 의미 확인 전 정규화 보류",
            availability = SignalAvailability.UNSUPPORTED, receivedMs = receivedMs, normalization = SdkNormalization.LOSSY_SDK_BOOLEAN)
    }
    fun receiveSteeringSdkValue(raw: Float?, delivery: SdkDelivery, receivedMs: Long) {
        receive("STEERING_DEG", raw, null, delivery,
            note = "SDK 계약은 전륜각 °/좌측 음수. Console 운전대각 표기와 충돌 · 조향비 추정 없이 운전대각 연결 보류",
            availability = SignalAvailability.UNSUPPORTED, receivedMs = receivedMs, normalization = SdkNormalization.SEMANTIC_UNCONFIRMED)
    }
    fun bounds(key: String, min: Int, max: Int) {
        require(min <= max) { "SDK 범위가 역전됐습니다: $min..$max" }
        data[key] = data.getValue(key).copy(minValue = min, maxValue = max)
    }
    fun receiveBoundedInt(key: String, raw: Int?, delivery: SdkDelivery, receivedMs: Long,
        diagnosticOnly: Boolean = false) {
        val r = data.getValue(key)
        val valid = raw != null && r.minValue != null && r.maxValue != null && raw in r.minValue..r.maxValue
        receive(key, raw, raw.takeIf { valid }, delivery, receivedMs = receivedMs,
            availability = if (raw != null && !valid) SignalAvailability.ERROR else null,
            note = "SDK 범위 ${r.minValue}..${r.maxValue}" + if (diagnosticOnly)
                " · 좌석 온열/냉각 별도 속성. VENTILATION_LEVEL로 변환하지 않음" else " · 비음수 통풍 단계, 0=꺼짐",
            normalization = if (diagnosticOnly) SdkNormalization.DIAGNOSTIC_ONLY else SdkNormalization.NORMALIZED)
    }
    fun receive(key: String, raw: Any?, value: Any?, delivery: SdkDelivery,
        nativeMs: Long? = null, note: String = "", availability: SignalAvailability? = null,
        receivedMs: Long = clock.elapsedRealtimeMs(), normalization: SdkNormalization = SdkNormalization.NORMALIZED,
        queryStartedMs: Long? = null) {
        val old = data.getValue(key)
        // 같은/더 오래된 원천의 늦은 배송은 재조회가 확인한 시각을 되돌리지 않는다.
        // 더 새로운 원천 변경은 별개다. 늦게 전달된 실제 D→P 콜백까지 막지 않는다.
        val olderConfirmation = key == "GEAR" && old.confirmedMs?.let { receivedMs < it } == true &&
            (nativeMs == null || old.nativeMs?.let { nativeMs <= it } == true)
        // capability 조회나 늦은 콜백이 권한 거부를 AVAILABLE로 바꾸면 안 된다.
        if (olderConfirmation || key in blocked || !old.permissionGranted || old.apiReadable == false || old.receivedMs?.let { receivedMs < it } == true ||
            key == "GEAR" && nativeMs != null && old.nativeMs?.let { nativeMs < it } == true ||
            delivery == SdkDelivery.REQUERY && queryStartedMs != null && old.receivedMs?.let { it > queryStartedMs } == true) {
            data[key] = old.copy(rejectedDeliveries = old.rejectedDeliveries + 1)
            return
        }
        // 구독 콜백 뒤 늦게 돌아온 최초 조회는 더 최신인 콜백을 덮지 않는다.
        if (delivery == SdkDelivery.QUERY && old.callbacks > 0 && old.receivedMs != null) {
            data[key] = old.copy(queries = old.queries + 1); return
        }
        if (delivery == SdkDelivery.REQUERY && key == "GEAR" && value != null &&
            (availability == null || availability == SignalAvailability.AVAILABLE) && old.value == value &&
            old.nativeMs == nativeMs && old.availability == SignalAvailability.AVAILABLE) {
            // 조회 응답으로 현재 값을 확인했지만 원천 변경/실제 배송/센서 관측 순번은 유지한다.
            data[key] = old.copy(confirmedMs = receivedMs, queries = old.queries + 1)
            return
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
            nativeMs = nativeMs, note = note, normalization = normalization,
            availability = availability ?: if (value == null) SignalAvailability.UNAVAILABLE else SignalAvailability.AVAILABLE,
            callbacks = old.callbacks + if (delivery == SdkDelivery.CALLBACK) 1 else 0,
            queries = old.queries + if (delivery != SdkDelivery.CALLBACK) 1 else 0,
            confirmedMs = receivedMs.takeIf { key == "GEAR" && value != null &&
                (availability == null || availability == SignalAvailability.AVAILABLE) },
            observations = old.observations + if (delivery == SdkDelivery.REQUERY) 0 else 1)
        val updated = data.getValue(key)
        lastStateLog[key] = updated.availability
        if (old.raw != updated.raw || old.value != updated.value || old.availability != updated.availability) {
            appendHistory("${now}ms · REAL/$delivery · $key · ${updated.raw} → ${updated.value ?: "null"} · ${updated.availability} · 원천 시각 ${nativeMs ?: "미제공"}")
        }
    }
    fun callbackHz(key: String): Float? = if (data[key]?.let {
        !it.permissionGranted || it.apiReadable == false || it.availability == SignalAvailability.ERROR ||
            it.callbacks == 0L && (it.support == "확인 중" || it.availability == SignalAvailability.UNSUPPORTED)
    } != false) null else rate(arrivals[key])
    fun nativeHz(key: String): Float? = if (data[key]?.let { !it.permissionGranted || it.apiReadable == false || it.availability == SignalAvailability.ERROR } != false ||
        data[key]?.nativeMs == null || key in badNative) null else rate(nativeTimes[key])
    private fun rate(times: ArrayDeque<Long>?): Float? {
        val now = clock.elapsedRealtimeMs()
        times?.let { while (it.isNotEmpty() && it.first() <= now - config.steeringRateWindowMs) it.removeFirst() }
        return if (now - started < config.steeringRateWindowMs) null else (times?.size ?: 0) * 1_000f / config.steeringRateWindowMs
    }
    fun quality(key: String): SignalQuality {
        val r = data.getValue(key)
        return SignalQuality(r.availability, Source.REAL, r.nativeMs, r.receivedMs,
            observedTimeBasis = if (r.nativeMs == null) ObservationTimeBasis.UNKNOWN else ObservationTimeBasis.ELAPSED_REALTIME,
            observationSequence = r.observations,
            stateConfirmedAtElapsedMs = r.confirmedMs,
            stateLeaseUntilElapsedMs = r.confirmedMs?.let { Math.addExact(it, policy.stateLeaseMs) })
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
