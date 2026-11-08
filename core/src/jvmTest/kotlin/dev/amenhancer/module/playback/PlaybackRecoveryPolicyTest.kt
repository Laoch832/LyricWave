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
        assertEquals(PlaybackFailure.RENDERER, PlaybackFailurePolicy.classify(1, SocketTimeoutException(), null))
        assertFalse(PlaybackFailurePolicy.recoverable(PlaybackFailure.AUTHORIZATION))
        assertFalse(PlaybackFailurePolicy.recoverable(PlaybackFailure.DRM))
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
    private class DrmSessionException(cause: Throwable) : IOException(cause)
    private class DeniedByServerException : IOException()
    @Test fun `renderer DRM and native runtime faults are classified from their causes`() {
        assertEquals(PlaybackFailure.DRM, PlaybackFailurePolicy.classify(1, DrmSessionException(IOException()), null))
        assertEquals(PlaybackFailure.AUTHORIZATION, PlaybackFailurePolicy.classify(1, DrmSessionException(DeniedByServerException()), null))
        assertEquals(PlaybackFailure.AUTHORIZATION, PlaybackFailurePolicy.classify(1, DrmSessionException(IOException()), 403))
        assertEquals(PlaybackFailure.UNEXPECTED, PlaybackFailurePolicy.classify(2, IllegalStateException(), null))
        assertEquals(PlaybackFailure.REMOTE, PlaybackFailurePolicy.classify(3, null, null))
        for (failure in listOf(PlaybackFailure.DRM, PlaybackFailure.RENDERER, PlaybackFailure.UNEXPECTED, PlaybackFailure.REMOTE))
            assertFalse(PlaybackFailurePolicy.recoverable(failure))
    }
    @Test fun `native authorization component failures cannot become renderer recovery`() {
        for (component in listOf(-42584, -42585, -42587)) {
            val failure = PlaybackFailurePolicy.classify(1, DrmSessionException(IOException()), null, component)
            assertEquals(PlaybackFailure.AUTHORIZATION, failure)
            assertFalse(PlaybackFailurePolicy.recoverable(failure))
        }
    }
    @Test fun `diagnostic contains class identities but no signed URLs or exception messages`() {
        val cause = DrmSessionException(IOException("https://secret.test/song?token=credential"))
        assertEquals("T1:DrmSessionException > IOException", PlaybackFailurePolicy.diagnostic(1, cause))
    }
}
