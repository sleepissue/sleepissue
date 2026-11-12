package com.sleepissue.awakedrive.fatigue

import com.sleepissue.awakedrive.demo.DemoJsonLoader
import com.sleepissue.awakedrive.demo.DemoLoadResult
import com.sleepissue.awakedrive.engine.model.SignalFrame
import com.sleepissue.awakedrive.signal.InterventionInput
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DrowsyScenarioIntegrationTest {

    @Test
    fun drowsyJsonProducesFatigueAssessments() {

        val root = generateSequence(
            File(System.getProperty("user.dir", ".")).canonicalFile
        ) { it.parentFile }
            .firstOrNull {
                File(it, "data/scenarios-demo/drowsy.json").isFile
            } ?: error("drowsy.json 파일을 찾지 못했습니다.")

        val file = File(root, "data/scenarios-demo/drowsy.json")

        val result = DemoJsonLoader().loadFile(file)

        assertTrue(
            "JSON 로딩 실패: $result",
            result is DemoLoadResult.Loaded
        )

        val scenario = (result as DemoLoadResult.Loaded).scenario

        assertEquals(18000, scenario.samples.size)

        val bridge = FatigueBridge()

        var processed = 0
        var evaluated = 0
        var firstRisk: Float? = null
        var maxRisk: Float? = null
        var lastRisk: Float? = null

        for (sample in scenario.samples) {

            val vehicle = sample.vehicle

            val frame = SignalFrame(
                tMs = sample.tMs,
                steeringDeg = vehicle.steeringDeg,
                speedKph = vehicle.speed,
                gear = vehicle.gear,
                turnSignal = vehicle.turnSignal,
                brakeOn = vehicle.brakeOn,
                cruiseActive = vehicle.cruiseActive,
                cruiseTargetKph = vehicle.cruiseTargetKph,
                seats = vehicle.seats,
                ventilationLevel = vehicle.ventilationLevel,
                cabinTempC = vehicle.cabinTempC,
                quality = vehicle.quality
            )

            val outputs = bridge.onFrame(
                frame,
                sample.navi,
                null
            )

            for (output in outputs) {
                if (output is InterventionInput.Assessment) {

                    val score = output.value.riskScore

                    if (score != null) {
                        evaluated++

                        if (firstRisk == null) {
                            firstRisk = score
                        }

                        if (maxRisk == null || score > maxRisk) {
                            maxRisk = score
                        }

                        lastRisk = score
                    }
                }
            }

            processed++
        }

        println("========================================")
        println("DROWSY SCENARIO ENGINE TEST")
        println("========================================")
        println("시나리오: ${scenario.id}")
        println("처리한 샘플: $processed")
        println("유효 위험도 판정 횟수: $evaluated")
        println("최초 위험도: $firstRisk")
        println("최대 위험도: $maxRisk")
        println("마지막 위험도: $lastRisk")
        println("========================================")

        assertEquals(18000, processed)
        assertTrue("유효한 위험도 판정이 없습니다.", evaluated > 0)
        assertNotNull("위험도 계산 결과가 없습니다.", maxRisk)
    }
}
