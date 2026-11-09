package com.sleepissue.awakedrive.demo

import com.google.gson.JsonParser
import com.sleepissue.awakedrive.engine.model.NaviField
import com.sleepissue.awakedrive.engine.model.SignalAvailability
import com.sleepissue.awakedrive.engine.model.SignalField
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GeneratedScenariosTest {

    @Test
    fun allSixScenariosLoadSuccessfully() {
        val loader = DemoJsonLoader()

        val names = listOf(
            "normal",
            "drowsy",
            "distraction",
            "talk",
            "assist",
            "departure"
        )

        // Gradle 실행 위치: src/android
        val scenarioDirectory = generateSequence(
            File(System.getProperty("user.dir", ".")).canonicalFile
        ) { it.parentFile }
            .map { File(it, "data/scenarios-demo") }
            .firstOrNull { it.isDirectory }
            ?: error("커밋된 data/scenarios-demo 폴더를 찾을 수 없습니다.")

        for (name in names) {
            val file = File(scenarioDirectory, "$name.json")

            assertTrue("$name 파일이 없습니다.", file.isFile)

            when (val result = loader.loadFile(file)) {
                is DemoLoadResult.Loaded -> {
                    val scenario = result.scenario

                    assertEquals(name, scenario.id)
                    assertEquals(18000, scenario.samples.size)
                    assertEquals(0L, scenario.samples.first().tMs)
                    assertEquals(1799900L, scenario.samples.last().tMs)
                    assertEquals(1800000L, scenario.durationMs)

                    val declared = JsonParser.parseString(file.readText()).asJsonObject.getAsJsonArray("samples")
                    val first = scenario.samples.first()
                    // 졸음 시나리오의 필수 두 값과 나머지 시나리오의 선택 필드/null 계약을 함께 확인한다.
                    scenario.samples.forEachIndexed { index, sample ->
                        val raw = declared[index].asJsonObject
                        fun optional(key: String): Float? = raw.get(key)?.takeUnless { it.isJsonNull }?.asFloat
                        val distance = optional("distToNextTurnM")
                        val temperature = optional("cabinTempC")
                        if (name == "drowsy") {
                            assertNotNull("$name 샘플 $index: 회전 거리 누락", distance)
                            assertNotNull("$name 샘플 $index: 실내 온도 누락", temperature)
                        }
                        assertEquals("$name 샘플 $index: 회전 거리 전달", distance, sample.navi.distToNextTurnM)
                        assertEquals("$name 샘플 $index: 실내 온도 전달", temperature, sample.vehicle.cabinTempC)
                        assertEquals(if (distance == null) SignalAvailability.UNAVAILABLE else SignalAvailability.AVAILABLE,
                            sample.navi.qualityOf(NaviField.DIST_TO_NEXT_TURN_M).availability)
                        assertEquals(if (temperature == null) SignalAvailability.UNAVAILABLE else SignalAvailability.AVAILABLE,
                            sample.vehicle.quality.getValue(SignalField.CABIN_TEMP_C).availability)
                    }

                    println(
                        "PASS: $name | " +
                        "회전 거리=${first.navi.distToNextTurnM}m | " +
                        "실내 온도=${first.vehicle.cabinTempC}°C"
                    )
                }

                is DemoLoadResult.Failed -> {
                    fail("$name 로딩 실패: ${result}")
                }
            }
        }
    }
}
