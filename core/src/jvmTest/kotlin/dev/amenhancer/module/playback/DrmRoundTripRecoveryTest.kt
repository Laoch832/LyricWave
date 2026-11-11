package dev.amenhancer.module.playback

import org.junit.Assert.*
import org.junit.Test

class DrmRoundTripRecoveryTest {
    private open class Native : DrmRoundTripRecovery.Port {
        var live: DrmRoundTripRecovery.Snapshot? = DrmRoundTripRecovery.Snapshot("shuffle-queue", 81, 17, 40000, true)
        val actions = mutableListOf<String>()
        var fresh = false; var cacheSafe = true; var held = true
        var callback: (() -> Unit)? = null
        override fun snapshot() = live
        override fun holdOutput(originalId: Long) = held.also { actions += "hold:$originalId" }
        override fun audiblePositionMs() = 41200L
        override fun select(queueId: Long): Boolean {
            actions += "select:$queueId"; live = live?.copy(current = queueId); callback?.invoke(); return true
        }
        override fun seek(positionMs: Long) { actions += "seek:$positionMs"; callback?.invoke() }
        override fun play() { actions += "play"; callback?.invoke() }
        override fun nextReady() = false
        override fun refilled() = fresh
        override fun clearTemporaryCache(originalId: Long) = cacheSafe.also { actions += "cache:$originalId" }
        override fun finishOutput(result: DrmRoundTripRecovery.Stage) { actions += "finish:$result" }
    }
    @Test fun `uses real next ID and native play then restores audible position without queue edits`() {
        var clock = 0L; val p = Native(); val r = DrmRoundTripRecovery(p) { clock }
        p.callback = r::userAction
        assertTrue(r.start(true, false)); assertEquals(listOf("hold:81", "select:17", "play"), p.actions)
        clock = 1500; r.tick()
        assertEquals(listOf("select:81", "seek:41200", "play"), p.actions.takeLast(3))
        r.tick(); assertEquals(DrmRoundTripRecovery.Stage.REFILL, r.stage)
        p.fresh = true; r.tick(); assertEquals(DrmRoundTripRecovery.Stage.COMPLETE, r.stage)
        assertFalse(r.start(true, false)); assertEquals("finish:COMPLETE", p.actions.last())
    }
    @Test fun `second request failure clears only verified current cache and retries exactly once`() {
        var clock = 0L; val p = Native(); val r = DrmRoundTripRecovery(p) { clock }
        r.start(true, false); clock = 1500; r.tick(); r.fault(true, false)
        assertEquals(2, r.attempts); assertEquals(1, p.actions.count { it == "cache:81" })
        clock = 3000; r.tick(); r.fault(true, false)
        assertEquals(DrmRoundTripRecovery.Stage.FAILED, r.stage)
        assertEquals(2, p.actions.count { it == "select:17" })
    }
    @Test fun `user pause seek queue replacement disable and release invalidate delayed restoration`() {
        for (cancel in 0..3) {
            var clock = 0L; val p = Native(); val r = DrmRoundTripRecovery(p) { clock }; r.start(true, false)
            when (cancel) {
                0 -> r.userAction()
                1 -> r.disable()
                2 -> p.live = p.live?.copy(queue = "new-queue")
                3 -> p.live = p.live?.copy(wantsPlay = false)
            }
            clock = 1500; r.tick()
            assertEquals(DrmRoundTripRecovery.Stage.CANCELLED, r.stage)
            assertEquals(cancel == 1, p.actions.contains("select:81"))
        }
    }
    @Test fun `does not touch authorization errors one item queues or unbuffered output`() {
        val p = Native(); val r = DrmRoundTripRecovery(p) { 0 }
        assertFalse(r.start(true, true)); assertFalse(r.start(false, false)); assertTrue(p.actions.isEmpty())
        p.live = p.live?.copy(next = 81); assertFalse(r.start(true, false)); assertTrue(p.actions.isEmpty())
        p.live = p.live?.copy(next = 17); p.held = false; assertFalse(r.start(true, false))
        assertFalse(p.actions.any { it.startsWith("select") })
    }
    @Test fun `unsafe cache prevents Plan B and total deadline prevents retry storms`() {
        var clock = 0L; val p = Native(); val r = DrmRoundTripRecovery(p) { clock }
        r.start(true, false); clock = 1500; r.tick(); p.cacheSafe = false; r.fault(true, false)
        assertEquals(DrmRoundTripRecovery.Stage.FAILED, r.stage); assertEquals(1, r.attempts)
        clock = 120000; assertTrue(r.start(true, false)); clock += 22000; r.tick()
        assertFalse(r.active); assertEquals(2, p.actions.count { it == "select:17" })
    }
    @Test fun `internal timeout restores original but never overwrites an explicit user selection`() {
        var clock = 0L; val p = Native(); val r = DrmRoundTripRecovery(p) { clock }
        r.start(true, false); clock = 22000; r.tick()
        assertEquals(DrmRoundTripRecovery.Stage.FAILED, r.stage)
        assertEquals(81L, p.live?.current)
        clock = 120000; r.start(true, false); p.live = p.live?.copy(current = 99)
        clock += 22000; r.tick(); assertEquals(99L, p.live?.current)
    }
    @Test fun `native errors release output and cannot leave an active retry loop`() {
        val p = object : Native() { override fun play() { throw IllegalStateException("native") } }
        val r = DrmRoundTripRecovery(p) { 0 }
        assertFalse(r.start(true, false))
        assertEquals(DrmRoundTripRecovery.Stage.FAILED, r.stage)
        assertEquals(81L, p.live?.current)
        assertEquals(1, p.actions.count { it == "finish:FAILED" })
    }

    @Test fun `authorization failure restores selection without another play request`() {
        val p = Native(); val r = DrmRoundTripRecovery(p) { 0 }
        assertTrue(r.start(true, false)); r.fault(true, true)
        assertEquals(81L, p.live?.current)
        assertEquals(1, p.actions.count { it == "play" })
        assertFalse(p.actions.any { it.startsWith("cache:") })
        assertEquals(DrmRoundTripRecovery.Stage.FAILED, r.stage)
    }

    @Test fun `failed recovery discards output before seeking and never adds untracked playback`() {
        var clock = 0L; val p = Native(); val r = DrmRoundTripRecovery(p) { clock }
        r.start(true, false); clock = 22000; r.tick()
        assertEquals(listOf("finish:FAILED", "select:81", "seek:41200"), p.actions.takeLast(3))
        assertEquals(1, p.actions.count { it == "play" })
    }
    @Test fun `temporary next DRM failure returns to tracked original refill`() {
        val p = Native(); val r = DrmRoundTripRecovery(p) { 0 }
        r.start(true, false); r.fault(true, false)
        assertEquals(DrmRoundTripRecovery.Stage.REFILL, r.stage)
        assertEquals(81L, p.live?.current); assertFalse(p.actions.any { it.startsWith("finish") })
        p.fresh = true; r.tick(); assertEquals(DrmRoundTripRecovery.Stage.COMPLETE, r.stage)
    }
    @Test fun `user backward seek cancels at the requested position instead of the old audible position`() {
        val p = Native(); val r = DrmRoundTripRecovery(p) { 0 }
        r.start(true, false); r.userSeek(7000)
        assertEquals(listOf("finish:CANCELLED", "select:81", "seek:7000"), p.actions.takeLast(3))
        assertEquals(1, p.actions.count { it == "play" })
    }
}
