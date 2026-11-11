package com.sleepissue.awakedrive.demo

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.sleepissue.awakedrive.engine.demo.DemoSpeedSegment
import com.sleepissue.awakedrive.engine.time.DemoClock
import com.sleepissue.awakedrive.engine.model.Gear
import com.sleepissue.awakedrive.engine.model.NaviField
import com.sleepissue.awakedrive.engine.model.NaviState
import com.sleepissue.awakedrive.engine.model.ObservationTimeBasis
import com.sleepissue.awakedrive.engine.model.PowerEvent
import com.sleepissue.awakedrive.engine.model.PowerState
import com.sleepissue.awakedrive.engine.model.SeatOccupancy
import com.sleepissue.awakedrive.engine.model.SignalAvailability
import com.sleepissue.awakedrive.engine.model.SignalField
import com.sleepissue.awakedrive.engine.model.SignalQuality
import com.sleepissue.awakedrive.engine.model.Source
import com.sleepissue.awakedrive.engine.model.TurnSignal
import com.sleepissue.awakedrive.signal.VehicleSignalInput
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** 문법·필드·시간·배속 검사를 모두 통과한 파일만 재생에 전달한다. */
class DemoJsonLoader {
    fun loadFile(file: File): DemoLoadResult = try {
        if (file.extension != "json" || file.canonicalFile.extension != "json") {
            failed(DemoLoadErrorCode.INVALID_SCHEMA, file.name, ".json 확장자의 시나리오를 선택해 주세요.")
        } else if (!file.isFile) {
            failed(DemoLoadErrorCode.FILE_NOT_FOUND, file.name, "시나리오 파일이 없습니다.")
        } else {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            parse(decoder.decode(ByteBuffer.wrap(file.readBytes())).toString())
        }
    } catch (_: CharacterCodingException) {
        failed(DemoLoadErrorCode.INVALID_JSON, file.name, "UTF-8 JSON 파일이어야 합니다.")
    } catch (_: IOException) {
        failed(DemoLoadErrorCode.IO_ERROR, file.name, "시나리오 파일을 읽지 못했습니다.")
    } catch (_: SecurityException) {
        failed(DemoLoadErrorCode.IO_ERROR, file.name, "시나리오 파일 읽기 권한이 없습니다.")
    }

    fun parse(json: String): DemoLoadResult {
        val root = try {
            JsonReader(StringReader(json)).use { reader ->
                reader.strictness = Strictness.STRICT
                val value = readStrict(reader, 0)
                if (reader.peek() != JsonToken.END_DOCUMENT) {
                    throw Invalid(DemoLoadErrorCode.INVALID_JSON, "$", "JSON 뒤에 다른 값이 있습니다.")
                }
                value
            }
        } catch (error: Invalid) {
            return DemoLoadResult.Failed(error.detail)
        } catch (_: IOException) {
            return failed(DemoLoadErrorCode.INVALID_JSON, "$", "JSON 문법이 올바르지 않습니다.")
        } catch (_: IllegalArgumentException) {
            return failed(DemoLoadErrorCode.INVALID_JSON, "$", "JSON 값이 올바르지 않습니다.")
        } catch (_: IllegalStateException) {
            return failed(DemoLoadErrorCode.INVALID_JSON, "$", "JSON 문법이 올바르지 않습니다.")
        }
        return try {
            DemoLoadResult.Loaded(validate(root))
        } catch (error: Invalid) {
            DemoLoadResult.Failed(error.detail)
        }
    }

    private fun validate(element: JsonElement): DemoScenario {
        val root = objectAt(element, "$", setOf("schemaVersion", "id", "startEpochMs", "source", "speedSegments", "samples"))
        if (longAt(required(root, "schemaVersion", "$"), "$.schemaVersion") != 1L) {
            invalid("$.schemaVersion", "지원하는 스키마 버전은 1입니다.")
        }
        val id = stringAt(required(root, "id", "$"), "$.id")
        if (!id.matches(Regex("[A-Za-z0-9_-]+"))) invalid("$.id", "id는 영문·숫자·밑줄·하이픈을 사용합니다.")
        source(root, "$")
        val epoch = longAt(required(root, "startEpochMs", "$"), "$.startEpochMs", DemoLoadErrorCode.INVALID_TIME)
        if (epoch < 0) time("$.startEpochMs", "시작 epoch ms는 0 이상이어야 합니다.")
        val rawSamples = required(root, "samples", "$")
        if (!rawSamples.isJsonArray || rawSamples.asJsonArray.size() < 2) {
            invalid("$.samples", "샘플은 최소 2개여야 합니다.")
        }
        val samples = rawSamples.asJsonArray.mapIndexed { index, raw -> sample(raw, index) }
        if (samples.first().tMs != 0L) time("$.samples[0].t", "첫 샘플은 상대 시각 0초여야 합니다.")
        samples.zipWithNext().forEachIndexed { index, (before, after) ->
            val delta = after.tMs - before.tMs
            if (delta <= 0) time("$.samples[${index + 1}].t", "샘플 시각은 중복 없이 증가해야 합니다.")
            if (delta !in 99L..101L) time("$.samples[${index + 1}].t", "샘플 간격은 100ms ±1ms여야 합니다.")
        }
        val duration = try {
            Math.addExact(samples.last().tMs, DEMO_SAMPLE_INTERVAL_MS).also { Math.addExact(epoch, it) }
        } catch (_: ArithmeticException) {
            time("$.samples", "시나리오 종료 시각이 Long 범위를 넘습니다.")
        }
        if (duration > DemoClock.MAX_DURATION_MS) time("$.samples", "시나리오 길이가 지원 범위를 넘습니다.")
        val segments = segments(root.get("speedSegments"), duration)
        return DemoScenario(id, epoch, samples.toList(), segments)
    }

    private fun sample(element: JsonElement, index: Int): DemoScenarioSample {
        val path = "$.samples[$index]"
        val fields = setOf("t", "steeringDeg", "speedKph", "gear", "turnSignal", "brake", "seats", "cruise", "lat", "lon", "power", "source", "distToNextTurnM", "cabinTempC", "ventilationLevel")
        val value = objectAt(element, path, fields)
        val tMs = seconds(required(value, "t", path), "$path.t", DemoLoadErrorCode.INVALID_TIME)
        source(value, path)
        val steering = floatOrNull(required(value, "steeringDeg", path), "$path.steeringDeg")
        val speed = floatOrNull(required(value, "speedKph", path), "$path.speedKph", nonNegative = true)
        val gearText = nullableString(required(value, "gear", path), "$path.gear")
        val gear = if (gearText == null || gearText == "OTHER") null else enumAt<Gear>(gearText, "$path.gear")
        val turnText = nullableString(required(value, "turnSignal", path), "$path.turnSignal")
        val turn = turnText?.let { enumAt<TurnSignal>(it, "$path.turnSignal") }
        val brake = booleanOrNull(required(value, "brake", path), "$path.brake")
        val seats = required(value, "seats", path).let { raw ->
            if (raw.isJsonNull) null else {
                val names = setOf("driver", "frontPassenger", "rearLeft", "rearCenter", "rearRight")
                val seat = objectAt(raw, "$path.seats", names)
                fun seatValue(name: String) = booleanOrNull(required(seat, name, "$path.seats"), "$path.seats.$name")
                SeatOccupancy(seatValue("driver"), seatValue("frontPassenger"), seatValue("rearLeft"), seatValue("rearCenter"), seatValue("rearRight"))
            }
        }
        val cruise = required(value, "cruise", path)
        var active: Boolean? = null
        var target: Float? = null
        if (!cruise.isJsonNull) {
            val control = objectAt(cruise, "$path.cruise", setOf("active", "targetKph"))
            active = booleanOrNull(required(control, "active", "$path.cruise"), "$path.cruise.active")
            target = floatOrNull(required(control, "targetKph", "$path.cruise"), "$path.cruise.targetKph", nonNegative = true)
        }
        val distToNextTurnM = value.get("distToNextTurnM")?.let {
            floatOrNull(it, "$path.distToNextTurnM", nonNegative = true)
        }
        val cabinTempC = value.get("cabinTempC")?.let {
            floatOrNull(it, "$path.cabinTempC")
        }
        val ventilationLevel = value.get("ventilationLevel")?.takeUnless { it.isJsonNull }?.let {
            val level = longAt(it, "$path.ventilationLevel", DemoLoadErrorCode.INVALID_SCHEMA)
            if (level !in 0..Int.MAX_VALUE.toLong()) invalid("$path.ventilationLevel", "통풍 단계는 0 이상의 정수여야 합니다.")
            level.toInt()
        }
        val lat = doubleOrNull(value.get("lat"), "$path.lat", -90.0..90.0)
        val lon = doubleOrNull(value.get("lon"), "$path.lon", -180.0..180.0)
        if ((lat == null) != (lon == null)) invalid(path, "lat/lon은 유효한 한 쌍 또는 둘 다 null이어야 합니다.")
        val powerText = value.get("power")?.let { nullableString(it, "$path.power") }
        val power = powerText?.let { enumAt<PowerState>(it, "$path.power") } ?: PowerState.UNKNOWN
        fun quality(available: Boolean) = SignalQuality(
            availability = if (available) SignalAvailability.AVAILABLE else SignalAvailability.UNAVAILABLE,
            source = Source.DEMO,
            observedAtMs = tMs,
            observedTimeBasis = ObservationTimeBasis.SCENARIO,
        )
        val availableFields = mapOf(
            SignalField.STEERING_DEG to (steering != null), SignalField.SPEED_KPH to (speed != null),
            SignalField.GEAR to (gear != null), SignalField.TURN_SIGNAL to (turn != null),
            SignalField.BRAKE_ON to (brake != null), SignalField.SEATS to (seats != null),
            SignalField.CRUISE_ACTIVE to (active != null), SignalField.CRUISE_TARGET_KPH to (target != null),
            SignalField.CABIN_TEMP_C to (cabinTempC != null),
            SignalField.VENTILATION_LEVEL to (ventilationLevel != null),
        )
        val vehicle = VehicleSignalInput(
            tMs = tMs, source = Source.DEMO, steeringDeg = steering, speed = speed,
            gear = gear, turnSignal = turn, brakeOn = brake, seats = seats,
            cruiseActive = active, cruiseTargetKph = target, cabinTempC = cabinTempC, ventilationLevel = ventilationLevel,
            quality = SignalField.entries.associateWith { quality(availableFields[it] == true) },
        )
        val navi = NaviState(
            tMs = tMs,
            lat = lat,
            lon = lon,
            distToNextTurnM = distToNextTurnM,
            quality = NaviField.entries.associateWith {
                quality(when (it) {
                    NaviField.LAT -> lat != null
                    NaviField.LON -> lon != null
                    NaviField.DIST_TO_NEXT_TURN_M -> distToNextTurnM != null
                    else -> false
                })
            }
        )
        return DemoScenarioSample(tMs, vehicle, navi, PowerEvent(tMs, power, quality(power != PowerState.UNKNOWN)))
    }

    private fun segments(raw: JsonElement?, duration: Long): List<DemoSpeedSegment> {
        if (raw == null || raw.isJsonNull) return listOf(DemoSpeedSegment(0, duration, 1))
        if (!raw.isJsonArray || raw.asJsonArray.isEmpty) speed("$.speedSegments", "배속 구간은 비어 있지 않은 배열이어야 합니다.")
        var expectedFrom = 0L
        val result = raw.asJsonArray.mapIndexed { index, element ->
            val path = "$.speedSegments[$index]"
            val value = objectAt(element, path, setOf("fromSec", "toSec", "factor"), DemoLoadErrorCode.INVALID_SPEED)
            fun field(name: String) = value.get(name) ?: speed("$path.$name", "필수 배속 필드가 없습니다.")
            val from = seconds(field("fromSec"), "$path.fromSec", DemoLoadErrorCode.INVALID_SPEED)
            val to = seconds(field("toSec"), "$path.toSec", DemoLoadErrorCode.INVALID_SPEED)
            val factor = longAt(field("factor"), "$path.factor", DemoLoadErrorCode.INVALID_SPEED)
            if (factor !in listOf(1L, 10L, 60L)) speed("$path.factor", "배속은 1·10·60 중 하나여야 합니다.")
            if (from != expectedFrom || to <= from || to > duration) {
                speed(path, "구간은 0초부터 종료까지 빈틈·중첩 없이 순서대로 덮어야 합니다.")
            }
            expectedFrom = to
            DemoSpeedSegment(from, to, factor.toInt())
        }
        if (expectedFrom != duration) speed("$.speedSegments", "마지막 구간의 끝은 시나리오 종료 시각이어야 합니다.")
        return result
    }

    private fun source(value: JsonObject, path: String) {
        value.get("source")?.let { if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString || it.asString != "DEMO") invalid("$path.source", "JSON 출처는 DEMO만 허용합니다.") }
    }

    private fun objectAt(value: JsonElement, path: String, allowed: Set<String>, code: DemoLoadErrorCode = DemoLoadErrorCode.INVALID_SCHEMA): JsonObject {
        if (!value.isJsonObject) throw Invalid(code, path, "객체여야 합니다.")
        value.asJsonObject.keySet().firstOrNull { it !in allowed }?.let { throw Invalid(code, "$path.$it", "정의하지 않은 필드입니다.") }
        return value.asJsonObject
    }

    private fun required(value: JsonObject, name: String, path: String): JsonElement =
        value.get(name) ?: invalid("$path.$name", "필수 필드가 없습니다. 결측은 null로 명시합니다.")

    private fun stringAt(value: JsonElement, path: String): String {
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) invalid(path, "문자열이어야 합니다.")
        return value.asString
    }

    private fun nullableString(value: JsonElement, path: String): String? = if (value.isJsonNull) null else stringAt(value, path)

    private inline fun <reified T : Enum<T>> enumAt(value: String, path: String): T =
        enumValues<T>().firstOrNull { it.name == value } ?: invalid(path, "지원하지 않는 열거 값입니다.")

    private fun booleanOrNull(value: JsonElement, path: String): Boolean? {
        if (value.isJsonNull) return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) invalid(path, "Boolean 또는 null이어야 합니다.")
        return value.asBoolean
    }

    private fun number(value: JsonElement, path: String, code: DemoLoadErrorCode = DemoLoadErrorCode.INVALID_SCHEMA): BigDecimal {
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) throw Invalid(code, path, "유한한 숫자여야 합니다.")
        return try { value.asBigDecimal } catch (_: NumberFormatException) { throw Invalid(code, path, "유한한 숫자여야 합니다.") }
    }

    private fun longAt(value: JsonElement, path: String, code: DemoLoadErrorCode = DemoLoadErrorCode.INVALID_SCHEMA): Long =
        try { number(value, path, code).longValueExact() } catch (_: ArithmeticException) { throw Invalid(code, path, "Long 범위의 정수여야 합니다.") }

    private fun seconds(value: JsonElement, path: String, code: DemoLoadErrorCode): Long {
        val ms = try { number(value, path, code).movePointRight(3).longValueExact() } catch (_: ArithmeticException) { throw Invalid(code, path, "초는 정수 ms로 표현할 수 있어야 합니다.") }
        if (ms < 0) throw Invalid(code, path, "시각은 0 이상이어야 합니다.")
        return ms
    }

    private fun floatOrNull(value: JsonElement, path: String, nonNegative: Boolean = false): Float? {
        if (value.isJsonNull) return null
        val exact = number(value, path)
        val result = exact.toFloat()
        val limit = BigDecimal.valueOf(Float.MAX_VALUE.toDouble())
        if (!result.isFinite() || exact.abs() > limit || nonNegative && exact.signum() < 0) {
            invalid(path, "유한한${if (nonNegative) " 0 이상의" else ""} 값이어야 합니다.")
        }
        return result
    }

    private fun doubleOrNull(value: JsonElement?, path: String, range: ClosedFloatingPointRange<Double>): Double? {
        if (value == null || value.isJsonNull) return null
        val exact = number(value, path)
        val result = exact.toDouble()
        if (!result.isFinite() || exact < BigDecimal.valueOf(range.start) || exact > BigDecimal.valueOf(range.endInclusive)) {
            invalid(path, "좌표가 허용 범위를 벗어났습니다.")
        }
        return result
    }

    /** Gson의 관대한 tree 파서를 사용하지 않고 중복 키까지 검사한다. */
    private fun readStrict(reader: JsonReader, depth: Int): JsonElement {
        if (depth > 32) throw Invalid(DemoLoadErrorCode.INVALID_JSON, reader.path, "JSON 중첩이 너무 깊습니다.")
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (has(name)) throw Invalid(DemoLoadErrorCode.INVALID_JSON, reader.path, "중복된 키입니다.")
                    add(name, readStrict(reader, depth + 1))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                reader.beginArray()
                while (reader.hasNext()) add(readStrict(reader, depth + 1))
                reader.endArray()
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> JsonPrimitive(reader.nextString().toBigDecimal())
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
            else -> throw Invalid(DemoLoadErrorCode.INVALID_JSON, reader.path, "JSON 값이 필요합니다.")
        }
    }

    private class Invalid(val detail: DemoLoadError) : RuntimeException() {
        constructor(code: DemoLoadErrorCode, path: String, message: String) : this(DemoLoadError(code, path, message))
    }

    private fun invalid(path: String, message: String): Nothing = throw Invalid(DemoLoadErrorCode.INVALID_SCHEMA, path, message)
    private fun time(path: String, message: String): Nothing = throw Invalid(DemoLoadErrorCode.INVALID_TIME, path, message)
    private fun speed(path: String, message: String): Nothing = throw Invalid(DemoLoadErrorCode.INVALID_SPEED, path, message)
    private fun failed(code: DemoLoadErrorCode, path: String, message: String) = DemoLoadResult.Failed(DemoLoadError(code, path, message))
}

/** scenarioId는 경로가 아니다. 지정 디렉터리의 같은 id.json만 읽는다. */
class FileDemoScenarioRepository(
    private val directory: File,
    private val loader: DemoJsonLoader = DemoJsonLoader(),
) : DemoScenarioRepository {
    override suspend fun load(scenarioId: String): DemoLoadResult {
        if (!scenarioId.matches(Regex("[A-Za-z0-9_-]+"))) return DemoLoadResult.Failed(
            DemoLoadError(DemoLoadErrorCode.INVALID_SCHEMA, "$.id", "시나리오 식별자가 올바르지 않습니다."),
        )
        val file = File(directory, "$scenarioId.json")
        try {
            if (file.canonicalFile.parentFile != directory.canonicalFile) return DemoLoadResult.Failed(
                DemoLoadError(DemoLoadErrorCode.INVALID_SCHEMA, "$.id", "지정한 시나리오 폴더 안의 파일만 읽습니다."),
            )
        } catch (_: IOException) {
            return DemoLoadResult.Failed(DemoLoadError(DemoLoadErrorCode.IO_ERROR, file.name, "시나리오 파일을 확인하지 못했습니다."))
        } catch (_: SecurityException) {
            return DemoLoadResult.Failed(DemoLoadError(DemoLoadErrorCode.IO_ERROR, file.name, "시나리오 파일 읽기 권한이 없습니다."))
        }
        val result = loader.loadFile(file)
        return if (result is DemoLoadResult.Loaded && result.scenario.id != scenarioId) DemoLoadResult.Failed(
            DemoLoadError(DemoLoadErrorCode.INVALID_SCHEMA, "$.id", "파일명과 시나리오 id가 다릅니다."),
        ) else result
    }
}
