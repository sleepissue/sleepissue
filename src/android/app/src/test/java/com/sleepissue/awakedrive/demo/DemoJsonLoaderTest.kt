package com.sleepissue.awakedrive.demo

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sleepissue.awakedrive.engine.model.Gear
import com.sleepissue.awakedrive.engine.model.NaviField
import com.sleepissue.awakedrive.engine.model.ObservationTimeBasis
import com.sleepissue.awakedrive.engine.model.PowerState
import com.sleepissue.awakedrive.engine.model.SignalAvailability
import com.sleepissue.awakedrive.engine.model.SignalField
import com.sleepissue.awakedrive.engine.model.Source
import com.sleepissue.awakedrive.engine.model.TurnSignal
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DemoJsonLoaderTest {
    private val loader = DemoJsonLoader()

    @Test
    fun secondsBecomeExactRelativeMillisecondsAndAllChannelsRemainDemo() {
        val scenario = loaded(validJson)
        assertEquals("minimal", scenario.id)
        assertEquals(1_793_862_000_000L, scenario.startEpochMs)
        assertEquals(listOf(0L, 100L, 200L), scenario.samples.map { it.tMs })
        assertEquals(300L, scenario.durationMs)
        assertEquals(1, scenario.speedSegments.single().factor)
        val sample = scenario.samples[1]
        assertEquals(Source.DEMO, sample.vehicle.source)
        assertEquals(80f, sample.vehicle.speed!!, 0f)
        assertEquals(Gear.D, sample.vehicle.gear)
        assertNull(sample.vehicle.updatedFields)
        assertEquals(Source.DEMO, sample.navi.qualityOf(NaviField.LAT).source)
        assertEquals(SignalAvailability.UNAVAILABLE, sample.navi.qualityOf(NaviField.LAT).availability)
        assertEquals(Source.DEMO, sample.power.quality.source)
        assertEquals(PowerState.UNKNOWN, sample.power.state)
        assertEquals(ObservationTimeBasis.SCENARIO, sample.vehicle.quality.getValue(SignalField.SPEED_KPH).observedTimeBasis)
        assertEquals(100L, sample.vehicle.quality.getValue(SignalField.SPEED_KPH).observedAtMs)
        assertNull(sample.vehicle.quality.getValue(SignalField.SPEED_KPH).receivedAtElapsedMs)
    }

    @Test
    fun explicitNullRemovesValuesWhileMissingRequiredKeyIsRejected() {
        val json = edited {
            sample(0).apply {
                add("steeringDeg", JsonNull.INSTANCE)
                add("speedKph", JsonNull.INSTANCE)
                add("gear", JsonNull.INSTANCE)
                add("turnSignal", JsonNull.INSTANCE)
                add("brake", JsonNull.INSTANCE)
                add("seats", JsonNull.INSTANCE)
                add("cruise", JsonNull.INSTANCE)
            }
        }
        val frame = loaded(json).samples.first().vehicle
        assertNull(frame.speed)
        assertNull(frame.seats)
        assertNull(frame.cruiseActive)
        assertEquals(SignalAvailability.UNAVAILABLE, frame.quality.getValue(SignalField.SPEED_KPH).availability)
        assertFailure(DemoLoadErrorCode.INVALID_SCHEMA, edited { sample(0).remove("speedKph") }, "speedKph")
    }

    @Test
    fun coordinatesArePairedAndUnknownLocationDoesNotBecomeZero() {
        val json = edited {
            sample(0).apply { addProperty("lat", 35.5); addProperty("lon", 129.0) }
            sample(1).apply { add("lat", JsonNull.INSTANCE); add("lon", JsonNull.INSTANCE) }
        }
        val scenario = loaded(json)
        assertEquals(35.5, scenario.samples[0].navi.lat!!, 0.0)
        assertEquals(129.0, scenario.samples[0].navi.lon!!, 0.0)
        assertEquals(SignalAvailability.AVAILABLE, scenario.samples[0].navi.qualityOf(NaviField.LAT).availability)
        assertNull(scenario.samples[1].navi.lat)
        assertNull(scenario.samples[2].navi.lon)
        assertFailure(DemoLoadErrorCode.INVALID_SCHEMA, edited { sample(0).addProperty("lat", 35.5) })
        assertFailure(DemoLoadErrorCode.INVALID_SCHEMA, edited { sample(0).apply { addProperty("lat", 91.0); addProperty("lon", 129.0) } })
    }

    @Test
    fun otherGearBecomesUnavailableAndHazardIsAnExplicitExtension() {
        val scenario = loaded(edited { sample(0).apply { addProperty("gear", "OTHER"); addProperty("turnSignal", "HAZARD"); addProperty("power", "OFF") } })
        val first = scenario.samples.first()
        assertNull(first.vehicle.gear)
        assertEquals(SignalAvailability.UNAVAILABLE, first.vehicle.quality.getValue(SignalField.GEAR).availability)
        assertEquals(TurnSignal.HAZARD, first.vehicle.turnSignal)
        assertEquals(PowerState.OFF, first.power.state)
        assertEquals(SignalAvailability.AVAILABLE, first.power.quality.availability)
    }

    @Test
    fun nestedNullSeatsAndCruiseAreNotChangedToFalseOrZero() {
        val scenario = loaded(edited {
            sample(0).getAsJsonObject("seats").add("frontPassenger", JsonNull.INSTANCE)
            sample(0).getAsJsonObject("cruise").apply { add("active", JsonNull.INSTANCE); add("targetKph", JsonNull.INSTANCE) }
        })
        assertNull(scenario.samples[0].vehicle.seats!!.frontPassenger)
        assertNull(scenario.samples[0].vehicle.cruiseTargetKph)
        assertFalse(scenario.samples[0].vehicle.seats!!.rearLeft!!)
    }

    @Test
    fun realSourceCannotBeForgedByHeaderOrSample() {
        assertFailure(DemoLoadErrorCode.INVALID_SCHEMA, edited { addProperty("source", "REAL") }, "source")
        assertFailure(DemoLoadErrorCode.INVALID_SCHEMA, edited { sample(0).addProperty("source", "REAL") }, "source")
        assertEquals(Source.DEMO, loaded(edited { addProperty("source", "DEMO"); sample(0).addProperty("source", "DEMO") }).samples[0].vehicle.source)
    }

    @Test
    fun malformedJsonDuplicateKeysAndTrailingDocumentsAreRejected() {
        listOf(
            "{", validJson + " {}", validJson.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            validJson.replace("\"speedKph\":80", "\"speedKph\":NaN"),
            validJson.replace("\"speedKph\":80", "\"speedKph\":Infinity"),
            validJson.replace("\"id\":\"minimal\"", "id:'minimal'"),
            validJson.replace("\"id\":\"minimal\"", "\"id\":\"minimal\", // 허용 안 함\n"),
        ).forEach { assertFailure(DemoLoadErrorCode.INVALID_JSON, it) }
    }

    @Test
    fun stringsBooleansUnknownFieldsAndOutOfRangeNumbersAreNotCoerced() {
        assertFailure(DemoLoadErrorCode.INVALID_SCHEMA, edited { sample(0).addProperty("speedKph", "80") })
        assertFailure(DemoLoadErrorCode.INVALID_SCHEMA, edited { sample(0).addProperty("brake", 0) })
        assertFailure(DemoLoadErrorCode.INVALID_SCHEMA, edited { sample(0).addProperty("speedKph", -1) })
        assertFailure(DemoLoadErrorCode.INVALID_SCHEMA, edited { sample(0).addProperty("gear", "X") })
        assertFailure(DemoLoadErrorCode.INVALID_SCHEMA, edited { addProperty("unexpected", 1) })
        assertFailure(DemoLoadErrorCode.INVALID_SCHEMA, edited { sample(0).getAsJsonObject("seats").addProperty("frontPasenger", true) })
        assertFailure(DemoLoadErrorCode.INVALID_SCHEMA, validJson.replace("\"speedKph\":80", "\"speedKph\":1e100"))
        assertFailure(DemoLoadErrorCode.INVALID_SCHEMA, validJson.replace("\"speedKph\":80", "\"speedKph\":-1e-1000"))
    }

    @Test
    fun schemaVersionIdAndSampleCountAreValidated() {
        assertFailure(DemoLoadErrorCode.INVALID_SCHEMA, edited { addProperty("schemaVersion", 2) })
        assertFailure(DemoLoadErrorCode.INVALID_SCHEMA, edited { addProperty("schemaVersion", "1") })
        assertFailure(DemoLoadErrorCode.INVALID_SCHEMA, edited { addProperty("id", "../minimal") })
        assertFailure(DemoLoadErrorCode.INVALID_SCHEMA, edited { getAsJsonArray("samples").apply { remove(2); remove(1) } })
    }

    @Test
    fun duplicateBackwardAndNonZeroFirstTimesHaveTimeErrors() {
        assertFailure(DemoLoadErrorCode.INVALID_TIME, edited { sample(1).addProperty("t", 0) })
        assertFailure(DemoLoadErrorCode.INVALID_TIME, edited { sample(2).addProperty("t", 0.05) })
        assertFailure(DemoLoadErrorCode.INVALID_TIME, edited { sample(0).addProperty("t", 0.01) })
        assertFailure(DemoLoadErrorCode.INVALID_TIME, edited { sample(0).addProperty("t", -0.1) })
    }

    @Test
    fun samplingToleranceIncludes99And101MillisecondsButNot98Or102() {
        assertEquals(99L, loaded(edited { sample(1).addProperty("t", 0.099) }).samples[1].tMs)
        assertEquals(101L, loaded(edited { sample(1).addProperty("t", 0.101) }).samples[1].tMs)
        assertFailure(DemoLoadErrorCode.INVALID_TIME, edited { sample(1).addProperty("t", 0.098) })
        assertFailure(DemoLoadErrorCode.INVALID_TIME, edited { sample(1).addProperty("t", 0.102) })
        assertFailure(DemoLoadErrorCode.INVALID_TIME, edited { sample(1).addProperty("t", 0.1001) })
    }

    @Test
    fun epochAndScenarioEndMustFitLongWithoutRounding() {
        assertFailure(DemoLoadErrorCode.INVALID_TIME, edited { addProperty("startEpochMs", -1) })
        assertFailure(DemoLoadErrorCode.INVALID_TIME, edited { addProperty("startEpochMs", Long.MAX_VALUE) })
        assertFailure(DemoLoadErrorCode.INVALID_TIME, edited { addProperty("startEpochMs", 1.5) })
        assertFailure(DemoLoadErrorCode.INVALID_TIME, validJson.replace("1793862000000", "9223372036854775808"))
    }

    @Test
    fun speedSegmentsUseSecondsWithHalfOpenBoundariesAndExactCoverage() {
        val scenario = loaded(segments("""[{"fromSec":0,"toSec":0.1,"factor":1},{"fromSec":0.1,"toSec":0.2,"factor":10},{"fromSec":0.2,"toSec":0.3,"factor":60}]"""))
        assertEquals(listOf(1, 10, 60), scenario.speedSegments.map { it.factor })
        assertEquals(listOf(0L, 100L, 200L), scenario.speedSegments.map { it.fromMs })
        assertEquals(listOf(100L, 200L, 300L), scenario.speedSegments.map { it.toMs })
        assertEquals(1, loaded(segments("null")).speedSegments.single().factor)
    }

    @Test
    fun invalidFactorGapOverlapReverseAndIncompleteSpeedSegmentsAreDistinctErrors() {
        listOf(
            "[]", "{}", """[{"fromSec":0,"toSec":0.3,"factor":2}]""",
            """[{"fromSec":0,"toSec":0.3,"factor":"60"}]""",
            """[{"fromSec":0.1,"toSec":0.3,"factor":60}]""",
            """[{"fromSec":0,"toSec":0.2,"factor":60},{"fromSec":0.1,"toSec":0.3,"factor":1}]""",
            """[{"fromSec":0,"toSec":0.1,"factor":60},{"fromSec":0.2,"toSec":0.3,"factor":1}]""",
            """[{"fromSec":0,"toSec":0.2,"factor":60}]""",
            """[{"fromSec":0,"toSec":0.4,"factor":60}]""",
            """[{"fromSec":0,"toSec":0,"factor":60}]""",
            """[{"from":0,"to":0.3,"factor":60}]""",
        ).forEach { assertFailure(DemoLoadErrorCode.INVALID_SPEED, segments(it)) }
    }

    @Test
    fun fileLoadDistinguishesMissingAndInvalidUtf8AndDoesNotAcceptPathAsId() = runTest {
        val directory = Files.createTempDirectory("awakedrive-demo-loader-").toFile()
        try {
            assertEquals(DemoLoadErrorCode.FILE_NOT_FOUND, (loader.loadFile(directory.resolve("missing.json")) as DemoLoadResult.Failed).error.code)
            assertEquals(DemoLoadErrorCode.INVALID_SCHEMA, (loader.loadFile(directory.resolve(".env")) as DemoLoadResult.Failed).error.code)
            val file = directory.resolve("minimal.json")
            file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
            assertEquals(DemoLoadErrorCode.INVALID_JSON, (loader.loadFile(file) as DemoLoadResult.Failed).error.code)
            file.writeText(validJson)
            val repository = FileDemoScenarioRepository(directory)
            assertTrue(repository.load("minimal") is DemoLoadResult.Loaded)
            assertEquals(DemoLoadErrorCode.INVALID_SCHEMA, (repository.load("../minimal") as DemoLoadResult.Failed).error.code)
            directory.resolve("different.json").writeText(validJson)
            assertEquals(DemoLoadErrorCode.INVALID_SCHEMA, (repository.load("different") as DemoLoadResult.Failed).error.code)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun loaded(json: String): DemoScenario {
        val result = loader.parse(json)
        assertTrue(result.toString(), result is DemoLoadResult.Loaded)
        return (result as DemoLoadResult.Loaded).scenario
    }

    private fun assertFailure(code: DemoLoadErrorCode, json: String, pathPart: String? = null) {
        val result = loader.parse(json)
        assertTrue(result.toString(), result is DemoLoadResult.Failed)
        val error = (result as DemoLoadResult.Failed).error
        assertEquals(error.toString(), code, error.code)
        pathPart?.let { assertTrue(error.path, error.path.contains(it)) }
    }

    private fun edited(change: JsonObject.() -> Unit): String = JsonParser.parseString(validJson).asJsonObject.apply(change).toString()
    private fun JsonObject.sample(index: Int) = getAsJsonArray("samples")[index].asJsonObject
    private fun segments(value: String) = edited { add("speedSegments", JsonParser.parseString(value)) }

    private val validJson = """{
        "schemaVersion":1,"id":"minimal","startEpochMs":1793862000000,
        "samples":[
          {"t":0,"steeringDeg":1,"speedKph":80,"gear":"D","turnSignal":"OFF","brake":false,"seats":{"driver":true,"frontPassenger":false,"rearLeft":false,"rearCenter":false,"rearRight":false},"cruise":{"active":false,"targetKph":null}},
          {"t":0.1,"steeringDeg":1,"speedKph":80,"gear":"D","turnSignal":"OFF","brake":false,"seats":{"driver":true,"frontPassenger":false,"rearLeft":false,"rearCenter":false,"rearRight":false},"cruise":{"active":false,"targetKph":null}},
          {"t":0.2,"steeringDeg":1,"speedKph":80,"gear":"D","turnSignal":"OFF","brake":false,"seats":{"driver":true,"frontPassenger":false,"rearLeft":false,"rearCenter":false,"rearRight":false},"cruise":{"active":false,"targetKph":null}}
        ]
    }""".trimIndent()
}
