package com.sleepissue.awakedrive.demo

import com.sleepissue.awakedrive.engine.model.ExecutionFailure
import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.RequestId
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoContractTest {
    @Test
    fun unconnectedPlayerDoesNotPublishFakePlaybackOrSamples() = runTest {
        val player = UnconnectedDemoPlayer()
        val id = RequestId("demo-contract")
        val commands = listOf(
            DemoCommand.Load(id, "normal"),
            DemoCommand.Start(id),
            DemoCommand.Pause(id),
            DemoCommand.Resume(id),
            DemoCommand.QuestionStarted(id),
            DemoCommand.QuestionFinished(id),
            DemoCommand.Stop(id),
            DemoCommand.Reset(id),
        )

        val results = commands.map { player.execute(it) }

        assertTrue(results.all { it.requestId == id })
        assertTrue(results.all { it.status == ExecutionStatus.NOT_CONNECTED })
        assertTrue(results.all { it.failure == ExecutionFailure.NOT_CONNECTED })
        assertEquals(DemoPhase.NOT_LOADED, player.playbackState.value.phase)
        assertNull(player.playbackState.value.speedMultiplier)
        assertNull(player.playbackState.value.scenarioId)
        assertTrue(player.samples.toList().isEmpty())
        assertTrue(player.states.toList().isEmpty())
        assertTrue(player.events.toList().isEmpty())
    }
}
