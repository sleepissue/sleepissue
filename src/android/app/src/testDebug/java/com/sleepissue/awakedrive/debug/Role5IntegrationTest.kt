package com.sleepissue.awakedrive.debug

import com.sleepissue.awakedrive.demo.*
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.rest.*
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.engine.signal.RateMeter
import com.sleepissue.awakedrive.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Role5IntegrationTest {
    private val root = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .first { File(it, "data/scenarios/normal.json").exists() }
    private val clock = object : Clock {
        override fun scenarioTimeMs() = 0L
        override fun elapsedRealtimeMs() = 0L
        override fun wallTimeMs() = 0L
    }
    private val areas = RestAreaCsv.parse(File(root, "data/rest_areas/processed/rest_areas.csv").readText())

    private fun scenario(name: String): DemoScenario {
        val result = DemoJsonLoader().loadFile(File(root, "data/scenarios/$name.json"))
        assertTrue("$name 로드 실패: $result", result is DemoLoadResult.Loaded)
        return (result as DemoLoadResult.Loaded).scenario
    }
    private val rateMeter = RateMeter().apply { start(0, ObservationTimeBasis.SCENARIO) }
    private fun frame(s: DemoScenarioSample): SignalFrame = with(s.vehicle) {
        if (s.tMs == 0L) rateMeter.start(0, ObservationTimeBasis.SCENARIO)
        rateMeter.observe(s.tMs, ObservationTimeBasis.SCENARIO, s.tMs)
        val normalizedQuality = quality + (SignalField.STEERING_DEG to quality.getValue(SignalField.STEERING_DEG)
            .copy(sourceRateInfo = rateMeter.sample(s.tMs)))
        SignalFrame(tMs = s.tMs, steeringDeg = steeringDeg, speedKph = speed, gear = gear,
            turnSignal = turnSignal, brakeOn = brakeOn, cruiseActive = cruiseActive,
            cruiseTargetKph = cruiseTargetKph, seats = seats, ventilationLevel = ventilationLevel,
            cabinTempC = cabinTempC, quality = normalizedQuality)
    }

    @Test fun `정식 6종에 위치 회전거리 온도 통풍 P D OFF RESUME을 포함한다`() {
        for (name in listOf("normal", "distraction", "drowsy", "talk", "assist", "departure")) {
            val s = scenario(name)
            assertEquals(27_100, s.samples.size)
            assertTrue(s.samples.any { it.vehicle.gear == Gear.P })
            assertTrue(s.samples.any { it.vehicle.gear == Gear.D })
            assertTrue(s.samples.any { it.power.state == PowerState.OFF })
            assertTrue(s.samples.any { it.power.state == PowerState.RESUME })
            assertTrue(s.samples.all { it.navi.lat != null && it.navi.lon != null &&
                it.navi.distToNextTurnM != null && it.vehicle.cabinTempC != null && it.vehicle.ventilationLevel != null })
        }
    }

    @Test fun `보조 주행 해제 뒤 10초 고정과 급조향을 보존한다`() {
        val samples = scenario("assist").samples
        val fixed = samples.filter { it.tMs in 1_200_000 until 1_210_000 }
        assertEquals(100, fixed.size)
        assertTrue(fixed.all { it.vehicle.cruiseActive == false && it.vehicle.steeringDeg == 0f })
        assertTrue(samples.first { it.tMs == 1_210_000L }.vehicle.steeringDeg!! >= 6f)
    }

    @Test fun `졸음 신호가 단계와 인증 스탬프까지 이어진다`() {
        val s = scenario("drowsy")
        val session = DemoUiSession(clock, s.startEpochMs, areas)
        val stages = mutableSetOf<InterventionStage>()
        var maxRisk = 0f
        for (sample in s.samples) {
            session.observe(frame(sample), sample.navi, sample.power)
            session.state.intervention?.stage?.let(stages::add)
            maxRisk = maxOf(maxRisk, session.state.fatigue?.fatigue?.riskSmoothed ?: 0f)
        }
        assertTrue("단계=$stages 최대위험도=$maxRisk 최종=${session.state}", stages.containsAll(InterventionStage.entries))
        assertTrue(session.state.restCertified)
        assertEquals(1, session.state.stampCount)
    }

    @Test fun `정상 주행에서 15분 타이머만으로 보상을 만들지 않는다`() {
        val s = scenario("normal")
        val session = DemoUiSession(clock, s.startEpochMs, areas)
        for (sample in s.samples) session.observe(frame(sample), sample.navi, sample.power)
        assertTrue(session.state.restCertified)
        assertEquals(0, session.state.stampCount)
    }

    @Test fun `출발 피로는 첫 D단 뒤 15분10초 부근에 검출된다`() {
        val s = scenario("departure")
        val session = DemoUiSession(clock, s.startEpochMs, areas)
        val firstD = s.samples.first { it.vehicle.gear == Gear.D }.tMs
        var detected: Long? = null
        var firstToday: String? = null
        for (sample in s.samples) {
            session.observe(frame(sample), sample.navi, sample.power)
            if (firstToday == null && session.state.fatigue?.baseline?.today != null) firstToday = "${sample.tMs}: ${session.state.fatigue}"
            if (session.state.fatigue?.baseline?.departureFatigue == true) { detected = sample.tMs; break }
        }
        assertNotNull("처음=$firstToday 최종=${session.state.fatigue}", detected)
        assertTrue("첫 D=$firstD 검출=$detected", detected!! - firstD in 909_900L..910_200L)
    }

    @Test fun `컨디션 세 값이 예보 입력으로 각각 연결된다`() {
        assertEquals(com.sleepissue.awakedrive.engine.forecast.StartCondition.FRESH, DrivingCondition.GOOD.toStartCondition())
        assertEquals(com.sleepissue.awakedrive.engine.forecast.StartCondition.NORMAL, DrivingCondition.SLIGHTLY_SLEEPY.toStartCondition())
        assertEquals(com.sleepissue.awakedrive.engine.forecast.StartCondition.DROWSY, DrivingCondition.VERY_SLEEPY.toStartCondition())
        val session = DemoUiSession(clock, 1_794_059_700_000L, areas)
        session.action(DriveUiAction.Condition(DrivingCondition.VERY_SLEEPY))
        assertNotNull(session.state.forecast)
    }

    @Test fun `파워냅은 P단에서만 시작하고 지금 깨우기로 종료한다`() {
        val s = scenario("normal")
        val session = DemoUiSession(clock, s.startEpochMs, areas)
        val driving = s.samples.first { it.vehicle.gear == Gear.D }
        session.observe(frame(driving), driving.navi, driving.power)
        session.action(DriveUiAction.StartNap)
        assertEquals(NapState.Idle, session.state.nap)
        val parked = s.samples.first { it.tMs >= 1_800_000 }
        session.observe(frame(parked), parked.navi, parked.power)
        session.action(DriveUiAction.StartNap)
        assertTrue(session.state.nap is NapState.Running)
        assertEquals(EngineConfig().napDurationMs, session.state.napRemainingMs)
        session.action(DriveUiAction.WakeNow)
        assertEquals(NapState.Idle, session.state.nap)
        assertFalse(session.state.restCertified)
    }

    @Test fun `동승자 휴식 요청은 다음 관측에 3단계로 연결되고 취소는 위험단계를 지우지 않는다`() {
        val s = scenario("normal")
        val session = DemoUiSession(clock, s.startEpochMs, areas)
        session.action(DriveUiAction.Passenger(true))
        val sample = s.samples.first()
        session.observe(frame(sample), sample.navi, sample.power)
        assertEquals(InterventionStage.REST_GUIDANCE, session.state.intervention?.stage)
        session.action(DriveUiAction.CancelWaypoint)
        assertEquals(InterventionStage.REST_GUIDANCE, session.state.intervention?.stage)
        assertTrue(session.state.waypointMessage.contains("취소"))
    }

    @Test fun `회복 장치 오류를 정상이나 인증으로 바꾸지 않는다`() {
        val session = DemoUiSession(clock, 0, areas)
        session.recoveryResult(CheckOutcome.UNAVAILABLE)
        assertFalse(session.state.restCertified)
        assertTrue(session.state.recovery.contains("정상 회복으로 처리하지"))
    }
}
