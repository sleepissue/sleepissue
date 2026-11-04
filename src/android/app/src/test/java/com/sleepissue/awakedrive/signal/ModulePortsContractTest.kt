package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.model.CheckContext
import com.sleepissue.awakedrive.engine.model.CheckOutcome
import com.sleepissue.awakedrive.engine.model.CheckResult
import com.sleepissue.awakedrive.engine.model.ExecutionFailure
import com.sleepissue.awakedrive.engine.model.ExecutionResult
import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.model.TripSnapshot
import com.sleepissue.awakedrive.engine.model.TripState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModulePortsContractTest {
    @Test
    fun deferredCheckPublishesActualStartBeforeFinalResultAndCancellationIsSeparate() = runTest {
        val port = FakeCheckPort()
        val id = RequestId("deferred-check")
        val cancelledId = RequestId("cancelled-check")
        val timeline = mutableListOf<Pair<RequestId, String>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            port.executionResults.collect { timeline += it.requestId to it.status.name }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            port.results.collect { timeline += it.requestId to it.outcome.name }
        }

        val accepted = port.start(CheckRequest(id, CheckContext.DRIVER_ALERTNESS))
        assertEquals(ExecutionStatus.REQUESTED, accepted.status)
        port.progress(ExecutionResult(id, ExecutionStatus.DEFERRED))
        assertEquals(listOf(id to "DEFERRED"), timeline)
        port.progress(ExecutionResult(id, ExecutionStatus.STARTED))
        port.complete(CheckResult(id, CheckContext.DRIVER_ALERTNESS, CheckOutcome.NORMAL))

        port.start(CheckRequest(cancelledId, CheckContext.RECOVERY))
        port.progress(ExecutionResult(cancelledId, ExecutionStatus.DEFERRED))
        val cancelled = port.cancel(cancelledId)
        port.progress(cancelled)
        port.complete(CheckResult(cancelledId, CheckContext.RECOVERY, CheckOutcome.CANCELLED))

        assertEquals(listOf("DEFERRED", "STARTED", "NORMAL"),
            timeline.filter { it.first == id }.map { it.second })
        assertEquals(listOf("DEFERRED", "CANCELLED", "CANCELLED"),
            timeline.filter { it.first == cancelledId }.map { it.second })
        assertEquals(cancelledId, cancelled.requestId)
        assertEquals(ExecutionStatus.CANCELLED, cancelled.status)
    }

    @Test
    fun checkRequestResultAndCancellationKeepTheSameId() = runTest {
        val port = FakeCheckPort()
        val id = RequestId("check-1")
        val observed = mutableListOf<CheckResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            port.results.collect { observed += it }
        }

        val accepted = port.start(CheckRequest(id, CheckContext.DRIVER_ALERTNESS))
        port.complete(CheckResult(id, CheckContext.DRIVER_ALERTNESS, CheckOutcome.NO_RESPONSE))
        val cancelled = port.cancel(id)

        assertEquals(ExecutionStatus.REQUESTED, accepted.status)
        assertEquals(CheckOutcome.NO_RESPONSE, observed.single().outcome)
        assertEquals(id, observed.single().requestId)
        assertEquals(id, cancelled.requestId)
        assertEquals(ExecutionStatus.CANCELLED, cancelled.status)
        assertEquals(listOf(id), port.recorder.cancellations)
    }

    @Test
    fun deviceFailureAndHumanNoResponseAreDifferentResults() = runTest {
        val port = FakeCheckPort()
        val id = RequestId("permission-denied")
        port.recorder.startStatus = ExecutionStatus.FAILED
        port.recorder.startFailure = ExecutionFailure.PERMISSION_DENIED

        val result = port.start(CheckRequest(id, CheckContext.RECOVERY))

        assertEquals(ExecutionStatus.FAILED, result.status)
        assertEquals(ExecutionFailure.PERMISSION_DENIED, result.failure)
        assertEquals(id, result.requestId)
    }

    @Test
    fun waypointRequestAcceptanceDoesNotClaimNavigationSucceeded() = runTest {
        val port = FakeRestPort()
        val id = RequestId("waypoint-1")
        val observed = mutableListOf<RestResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            port.results.collect { observed += it }
        }

        val accepted = port.start(RestRequest(id, RestAction.ADD_WAYPOINT, candidateId = "rest-4"))
        port.complete(RestResult(ExecutionResult(id, ExecutionStatus.FAILED, ExecutionFailure.ERROR)))
        val cancelled = port.cancel(id)

        assertEquals(ExecutionStatus.REQUESTED, accepted.status)
        assertEquals(ExecutionStatus.FAILED, observed.single().execution.status)
        assertEquals(id, observed.single().execution.requestId)
        assertEquals(ExecutionStatus.CANCELLED, cancelled.status)
    }

    @Test
    fun repositoryDistinguishesEmptyLoadedAndFailureWithoutLosingData() = runTest {
        val repository = FakeTripRepository()
        val snapshot = TripSnapshot(TripState(tripId = "saved-trip"), savedAtWallMs = 1_000)
        assertEquals(TripLoadResult.Empty, repository.load())
        assertEquals(RepositoryResult.Succeeded, repository.save(snapshot))

        repository.failure = RepositoryFailure.UNAVAILABLE
        assertEquals(TripLoadResult.Failed(RepositoryFailure.UNAVAILABLE), repository.load())
        assertTrue(repository.delete() is RepositoryResult.Failed)
        assertTrue(repository.save(snapshot.copy(savedAtWallMs = 2_000)) is RepositoryResult.Failed)

        repository.failure = null
        assertEquals(TripLoadResult.Loaded(snapshot), repository.load())
        assertEquals(RepositoryResult.Succeeded, repository.delete())
        assertEquals(TripLoadResult.Empty, repository.load())
    }
}
