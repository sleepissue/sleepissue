package com.sleepissue.awakedrive.demo

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
            .map { File(it, "data/scenarios-validation") }
            .firstOrNull { it.isDirectory }
            ?: error("data/scenarios-validation 폴더를 찾을 수 없습니다.")

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

                    // 회전 거리와 실내 온도의 실제 전달 여부 검사
                    val first = scenario.samples.first()

                    assertNotNull("$name 회전 거리 누락", first.navi.distToNextTurnM)
                    assertNotNull("$name 실내 온도 누락", first.vehicle.cabinTempC)

                    scenario.samples.forEachIndexed { index, sample ->
                        assertNotNull(
                            "$name 샘플 $index: 회전 거리 누락",
                            sample.navi.distToNextTurnM
                        )
                        assertNotNull(
                            "$name 샘플 $index: 실내 온도 누락",
                            sample.vehicle.cabinTempC
                        )
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
