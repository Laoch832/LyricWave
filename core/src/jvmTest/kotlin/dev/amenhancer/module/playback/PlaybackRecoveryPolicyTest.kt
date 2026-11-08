package dev.amenhancer.module.playback

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class PlaybackRecoveryPolicyTest {
    @Test fun `only confirmed network source faults are recoverable`() {
        assertEquals(PlaybackFailure.NETWORK, PlaybackFailurePolicy.classify(0, IOException(SocketTimeoutException()), null))
        assertEquals(PlaybackFailure.HTTP_TRANSIENT, PlaybackFailurePolicy.classify(0, IOException(), 503))
        for (status in listOf(401, 403)) assertEquals(PlaybackFailure.AUTHORIZATION, PlaybackFailurePolicy.classify(0, SocketTimeoutException(), status))
        assertEquals(PlaybackFailure.OTHER, PlaybackFailurePolicy.classify(0, IOException(), 404))
        assertEquals(PlaybackFailure.DRM_OR_RENDERER, PlaybackFailurePolicy.classify(1, SocketTimeoutException(), null))
        assertFalse(PlaybackFailurePolicy.recoverable(PlaybackFailure.AUTHORIZATION))
        assertFalse(PlaybackFailurePolicy.recoverable(PlaybackFailure.DRM_OR_RENDERER))
    }
    @Test fun `automatic attempts never loop and explicit attempts respect cooldown`() {
        var clock = 0L
        val budget = PlaybackRetryBudget({ clock })
        assertTrue(budget.claim("queue:item", true))
        clock = 20_000
        assertFalse(budget.claim("queue:item", true))
        assertTrue(budget.claim("queue:item", false))
        assertFalse(budget.claim("queue:item", false))
        clock += 10_000
        assertTrue(budget.claim("queue:next", true))
    }
}
