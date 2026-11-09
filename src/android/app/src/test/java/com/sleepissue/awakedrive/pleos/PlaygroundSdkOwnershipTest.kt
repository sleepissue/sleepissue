package com.sleepissue.awakedrive.pleos

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PlaygroundSdkOwnershipTest {
    @Test fun serviceAndProbeCannotCollectAtTheSameTime() {
        val service = requireNotNull(PlaygroundSdkOwnership.acquire("REAL 운행 감시 서비스"))
        try {
            assertEquals("REAL 운행 감시 서비스", PlaygroundSdkOwnership.currentOwner)
            assertNull(PlaygroundSdkOwnership.acquire("SDK 단독 검증 화면"))
            assertNull(PlaygroundSdkOwnership.acquire("REAL 운행 감시 서비스"))
        } finally { PlaygroundSdkOwnership.release(service) }
        val probe = requireNotNull(PlaygroundSdkOwnership.acquire("SDK 단독 검증 화면"))
        try { assertEquals("SDK 단독 검증 화면", PlaygroundSdkOwnership.currentOwner) }
        finally { PlaygroundSdkOwnership.release(probe) }
        assertNull(PlaygroundSdkOwnership.currentOwner)
    }

    @Test fun staleCleanupCannotReleaseNextOwnerWithSameName() {
        val old = requireNotNull(PlaygroundSdkOwnership.acquire("SDK 소유자"))
        assertTrue(PlaygroundSdkOwnership.release(old))
        val next = requireNotNull(PlaygroundSdkOwnership.acquire("SDK 소유자"))
        try {
            assertFalse(PlaygroundSdkOwnership.release(old))
            assertEquals("SDK 소유자", PlaygroundSdkOwnership.currentOwner)
            assertNull(PlaygroundSdkOwnership.acquire("다른 소유자"))
        } finally { PlaygroundSdkOwnership.release(next) }
    }

    @Test fun simultaneousStartRequestsGrantExactlyOneLease() {
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val granted = mutableListOf<PlaygroundSdkOwnership.Lease>()
        try {
            val results = (1..8).map { request -> executor.submit(Callable {
                check(start.await(5, TimeUnit.SECONDS))
                PlaygroundSdkOwnership.acquire("동시 시작 $request")
            }) }
            start.countDown()
            results.mapNotNullTo(granted) { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, granted.size)
            assertEquals(granted.single().owner, PlaygroundSdkOwnership.currentOwner)
        } finally {
            granted.forEach(PlaygroundSdkOwnership::release)
            executor.shutdownNow()
        }
    }
}
