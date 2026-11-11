package com.sleepissue.awakedrive.demo

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.signal.*
import com.sleepissue.awakedrive.engine.time.DemoClock
import com.sleepissue.awakedrive.fatigue.FatigueBridge
import com.sleepissue.awakedrive.signal.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DrowsyFullIntegrationTest {

    private fun debugPolicy(): HubPolicy {
        val freshness = SignalFreshnessPolicy(
            1_000L,
            1_000L,
            MissingObservationPolicy.REQUIRE_TIMESTAMP
        )

        return HubPolicy(
            SignalField.entries.associateWith { freshness },
            NaviField.entries.associateWith { freshness },
            freshness,
            SignalFallbackPolicy(
                true,
                0L,
                ObservationTimeBasis.ELAPSED_REALTIME
            ),
            ObservationTimeBasis.SCENARIO,
            HubTickDriver.MANUAL
        )
    }

    @Test
    fun drowsyJsonPassesThroughPlayerHubAndEngine() = runTest {

        val root = generateSequence(
            File(System.getProperty("user.dir", ".")).canonicalFile
        ) { it.parentFile }
            .firstOrNull {
                File(it, "data/scenarios-demo/drowsy.json").isFile
            } ?: error("drowsy.json 파일을 찾지 못했습니다.")

        val result = DemoJsonLoader().loadFile(
            File(root, "data/scenarios-demo/drowsy.json")
        )

        assertTrue(
            "JSON 로딩 실패: $result",
            result is DemoLoadResult.Loaded
        )

        val scenario = (result as DemoLoadResult.Loaded).scenario

        val realTime = DemoTestClock()
        val clock = DemoClock(realTime)

        val player = ScenarioDemoPlayer(
            clock,
            demoTestRepository(scenario),
            backgroundScope,
            automaticTicks = false,
            transportMode = DemoTransportMode.COMPLETE_OBSERVATION
        )

        val hub = VehicleSignalHub(
            listOf(
                HubSourceSet(
                    Source.DEMO,
                    player,
                    player,
                    player,
                    ObservationTimeBasis.SCENARIO,
                    complete = player
                )
            ),
            clock,
            debugPolicy()
        )

        val bridge = FatigueBridge()

        var published = 0
        var evaluated = 0
        var maxRisk: Float? = null

        player.execute(
            DemoCommand.Load(RequestId("load-drowsy"), scenario.id)
        )

        hub.start(backgroundScope)
        runCurrent()

        player.execute(
            DemoCommand.Start(RequestId("start-drowsy"))
        )

        repeat(2000) {

            realTime.realMs += 1_000L

            player.pump(
                workBudgetMs = Long.MAX_VALUE,
                maxSamples = Int.MAX_VALUE,
                afterSample = {
                    runCurrent()

                    if (hub.publishDue()) {
                        val frame = hub.latestFrame.value

                        if (frame != null && frame.speedKph != null) {
                            published++

                            val outputs = bridge.onFrame(
                                frame,
                                hub.latestNavi.value,
                                null
                            )

                            for (output in outputs) {
                                if (output is InterventionInput.Assessment) {
                                    val risk = output.value.riskScore

                                    if (risk != null) {
                                        evaluated++

                                        if (maxRisk == null || risk > maxRisk!!) {
                                            maxRisk = risk
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            )

            if (player.playbackState.value.phase == DemoPhase.COMPLETED) {
                return@repeat
            }
        }

        val delivered = player.playbackSnapshot().deliveredSamples
        val finalPhase = player.playbackState.value.phase

        println("========================================")
        println("AWAKEDRIVE FULL INTEGRATION TEST")
        println("========================================")
        println("JSON 샘플 수: ${scenario.samples.size}")
        println("DemoPlayer 전달 수: $delivered")
        println("SignalHub 유효 프레임 발행 수: $published")
        println("엔진 위험도 판정 횟수: $evaluated")
        println("최대 졸음 위험도: $maxRisk")
        println("최종 재생 상태: $finalPhase")
        println("========================================")

        hub.stop()

        assertEquals(
            "DemoPlayer가 전체 샘플을 전달하지 못했습니다.",
            scenario.samples.size.toLong(),
            delivered.toLong()
        )

        assertTrue(
            "SignalHub에서 유효한 차량 데이터가 발행되지 않았습니다.",
            published > 0
        )

        assertTrue(
            "졸음 엔진에서 유효한 위험도 판정이 발생하지 않았습니다.",
            evaluated > 0
        )
    }
}
