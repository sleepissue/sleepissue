package com.sleepissue.awakedrive.engine.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class DistancePhraseResolverTest {
    private val resolver = DistancePhraseResolver()

    @Test
    fun `2점5킬로미터 미만은 곧으로 안내한다`() {
        assertEquals(DistancePhrase.Soon, resolver.resolve(2_400))
    }

    @Test
    fun `거리는 가장 가까운 5킬로미터 단위로 안내한다`() {
        assertEquals(DistancePhrase.Kilometers(10), resolver.resolve(12_000))
        assertEquals(DistancePhrase.Kilometers(15), resolver.resolve(13_000))
        assertEquals(DistancePhrase.Kilometers(25), resolver.resolve(27_000))
    }
}
