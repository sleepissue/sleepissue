package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.model.EvaluationStatus
import com.sleepissue.awakedrive.engine.model.ExecutionFailure
import com.sleepissue.awakedrive.engine.model.ExecutionResult
import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.InterventionStage
import com.sleepissue.awakedrive.engine.model.RequestId
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InterventionContractTest {
    @Test
    fun unconnectedManagerNeverTreatsInputsOrSuccessfulCallbacksAsEvaluation() = runTest {
        val manager = UnconnectedInterventionManager()
        val id = RequestId("unconnected-1")
        val results = listOf(
            manager.start(id),
            manager.submit(id, InterventionInput.Assessment(DrivingAssessment(tMs = 1_000))),
            manager.onResult(ExecutionResult(id, ExecutionStatus.SUCCEEDED)),
            manager.cancel(id),
            manager.stop(id),
        )

        assertTrue(results.all { it.requestId == id })
        assertTrue(results.all { it.status == ExecutionStatus.NOT_CONNECTED })
        assertTrue(results.all { it.failure == ExecutionFailure.NOT_CONNECTED })
        assertEquals(EvaluationStatus.NOT_EVALUATED, manager.state.value.evaluationStatus)
        assertEquals(InterventionStage.NORMAL, manager.state.value.stage)
        assertTrue(manager.requests.toList().isEmpty())
    }
}
