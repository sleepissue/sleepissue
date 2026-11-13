package com.sleepissue.awakedrive.demo

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.OffsetDateTime

class ProductScenarioContractTest {
    @Test fun sixOriginalsPreserveEverySignalTimeAndPresetThroughProductLoader() {
        for (id in listOf("normal", "distraction", "drowsy", "talk", "assist", "departure")) {
            val file = File("../../../data/scenarios/$id.json")
            val root = JsonParser.parseString(file.readText()).asJsonObject
            val loaded = DemoJsonLoader().loadFile(file)
            assertTrue("$id: $loaded", loaded is DemoLoadResult.Loaded)
            val scenario = (loaded as DemoLoadResult.Loaded).scenario
            assertEquals(id, scenario.id)
            val teamFormat = root.has("formatVersion")
            val epoch = if (teamFormat) OffsetDateTime.parse(root["startAt"].asString).toInstant().toEpochMilli()
                else root["startEpochMs"].asLong
            assertEquals(epoch, scenario.startEpochMs)
            assertEquals(root["samples"].asJsonArray.size(), scenario.samples.size)
            scenario.samples.forEachIndexed { index, sample ->
                val original = root["samples"].asJsonArray[index].asJsonObject
                val timeMs = if (teamFormat) original["tMs"].asBigDecimal.longValueExact()
                    else original["t"].asBigDecimal.movePointRight(3).longValueExact()
                assertEquals(timeMs, sample.tMs)
                assertEquals(original["steeringDeg"].asFloat, requireNotNull(sample.vehicle.steeringDeg), 0f)
                assertEquals(original["speedKph"].asFloat, sample.vehicle.speed)
                assertEquals(original["distToNextTurnM"].asFloat, sample.navi.distToNextTurnM)
                assertEquals(original["cabinTempC"].asFloat, sample.vehicle.cabinTempC)
            }
            val preset = root.get("tripPreset")?.takeUnless { it.isJsonNull }?.asJsonObject
            if (preset != null) {
                assertEquals(preset["continuousDriveSec"].asLong, scenario.tripPreset?.continuousDriveSec)
                assertEquals(preset["last24hDriveSec"].asLong, scenario.tripPreset?.last24hDriveSec)
            } else assertNull(scenario.tripPreset)
        }
    }
    @Test fun malformedTeamTimeOrUnknownFieldCannotBeSilentlyLost() {
        val minimal = """{"formatVersion":1,"scenarioId":"x","startAt":"2026-11-07T14:00:00+09:00","samples":[{"tMs":0.5}]}"""
        assertTrue(DemoJsonLoader().parse(minimal) is DemoLoadResult.Failed)
        assertTrue(DemoJsonLoader().parse(minimal.replace("0.5", "0").replace("\"tMs\":0", "\"tMs\":0,\"unexpected\":1")) is DemoLoadResult.Failed)
    }
}
