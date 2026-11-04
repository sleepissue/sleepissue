package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.intervention.InterventionPolicy
import com.sleepissue.awakedrive.engine.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InterventionPersistenceTest {
    private class Owner(scope: TestScope, val clock: FakeClock, prefix: String) {
        val output = mutableListOf<ExecutionRequest>()
        var id = 0
        val manager = PolicyInterventionManager(InterventionPolicy(EngineConfig()), EngineConfig(), clock,
            Source.DEMO, RequestTimerBasis.SCENARIO) { RequestId("$prefix-${++id}") }
        init { scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { manager.requests.collect { output += it } } }
        suspend fun send(input: InterventionInput) = manager.submit(RequestId("command-${++id}"), input)
    }

    @Test fun timeAndForecastCautionHoldAndCertificationReleaseSurviveSerialization() = runTest {
        listOf(false, true).forEach { forecast ->
            val clock = FakeClock()
            val before = Owner(this, clock, "before")
            before.manager.start(RequestId("start"))
            before.send(InterventionInput.Assessment(DrivingAssessment(0, EvaluationStatus.EVALUATED, 0f)))
            before.send(if (forecast) InterventionInput.Forecast(ForecastAssessment(0, EvaluationStatus.EVALUATED, 0))
                else InterventionInput.TripChanged(TripState(continuousDriveSec = 7_200)))
            runCurrent()
            val state = before.manager.checkpoint()
            val decoded = TripSessionCodec.decode(TripSessionCodec.encode(
                TripSessionOwnerState(Gear.P, PowerState.ON, SaveTimerBasis.SCENARIO, state))).intervention
            assertEquals(state, decoded)
            val restored = Owner(this, clock, "after")
            restored.manager.restore(decoded, TripState(stage = InterventionStage.CAUTION, continuousDriveSec = 7_200))
            runCurrent()
            assertTrue(restored.output.isEmpty())
            assertEquals(if (forecast) TransitionReason.FORECAST else TransitionReason.TIME, restored.manager.state.value.reason)
            clock.scenarioMs = 600_000
            restored.send(InterventionInput.Tick)
            assertEquals(InterventionStage.CAUTION, restored.manager.state.value.stage)
            restored.send(InterventionInput.RestCertified(RestCertification("rest-after-reopen", 600_000, Source.DEMO)))
            assertEquals(InterventionStage.NORMAL, restored.manager.state.value.stage)
            assertEquals(TransitionReason.REST_COMPLETED, restored.manager.state.value.reason)
        }
    }

    @Test fun malformedOwnerPayloadAndStageMismatchCannotBeRestored() = runTest {
        val owner = Owner(this, FakeClock(), "initial")
        owner.manager.start(RequestId("start"))
        val saved = owner.manager.checkpoint()
        val payload = TripSessionCodec.encode(TripSessionOwnerState(Gear.P, PowerState.ON, SaveTimerBasis.SCENARIO, saved))
        listOf("bad", payload.dropLast(8), payload + "AAAA").forEach {
            assertThrows(Exception::class.java) { TripSessionCodec.decode(it) }
        }
        val restored = Owner(this, FakeClock(), "restored")
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { restored.manager.restore(saved, TripState(stage = InterventionStage.REST_GUIDANCE)) }
        }
    }
}
