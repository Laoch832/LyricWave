package dev.amenhancer.module.status

import org.junit.Assert.*
import org.junit.Test

class ActivationStatusTest {
    private val hostUid = 12345
    private fun valid(code: Long = 136, state: String = "UP_TO_DATE", uid: Int = hostUid, pid: Int = 7) = ActivationEvidence(
        connected = true, api = 102, framework = "LSPosed", scope = setOf("com.apple.android.music"),
        targets = listOf(LoadedHost(uid, pid, "com.apple.android.music", state, code)),
    )
    private fun evaluate(value: ActivationEvidence) = ActivationPolicy.evaluate(value, 136, hostUid)
    @Test fun `installed or selected alone never proves activation`() {
        assertEquals(ActivationState.DISCONNECTED, evaluate(ActivationEvidence()))
        assertEquals(ActivationState.SCOPE_MISSING, evaluate(valid().copy(scope = emptySet())))
        assertEquals(ActivationState.WAITING_HOST, evaluate(valid().copy(targets = emptyList())))
        assertEquals(ActivationState.UNVERIFIED, evaluate(valid().copy(targets = null)))
        assertEquals(ActivationState.UNVERIFIED, evaluate(valid().copy(failed = true)))
    }
    @Test fun `only the current users running host with the current module can be active`() {
        assertEquals(ActivationState.ACTIVE, evaluate(valid()))
        assertEquals(ActivationState.UNVERIFIED, evaluate(valid(0)))
        assertEquals(ActivationState.RESTART_REQUIRED, evaluate(valid(135)))
        assertEquals(ActivationState.RESTART_REQUIRED, evaluate(valid(state = "STALE")))
        assertEquals(ActivationState.WAITING_HOST, evaluate(valid(uid = hostUid + 100000)))
        assertEquals(ActivationState.WAITING_HOST, evaluate(valid(pid = 0)))
        assertEquals(ActivationState.WAITING_HOST, ActivationPolicy.evaluate(valid(), 136, null))
        assertEquals(ActivationState.LOAD_FAILED, evaluate(valid(state = "FAILED")))
        assertEquals(ActivationState.RELOADING, evaluate(valid(state = "RELOADING")))
        assertEquals(ActivationState.FRAMEWORK_OLD, evaluate(valid().copy(api = 101)))
    }
    @Test fun `binder death and replacement discard delayed evidence`() {
        val tasks = mutableListOf<() -> Unit>(); val deadlines = mutableListOf<() -> Unit>()
        val monitor = ActivationMonitor({ tasks += it }, { it() }, { _, task -> deadlines += task; { deadlines -= task } })
        var observed = ActivationEvidence()
        val stop = monitor.subscribe { observed = it }
        val old = Any(); monitor.connected(old) { valid() }
        monitor.disconnected(old); tasks.removeAt(0)()
        assertFalse(observed.connected)
        val current = Any(); monitor.connected(current) { valid(135) }
        monitor.disconnected(old); tasks.removeAt(0)()
        assertEquals(ActivationState.RESTART_REQUIRED, evaluate(observed))
        stop(); monitor.disconnected(current)
        assertTrue(observed.connected) // detached Activity is not retained/notified
    }
    @Test fun `blocked framework query times out and cannot resurrect a green status`() {
        val tasks = mutableListOf<() -> Unit>(); val deadlines = mutableListOf<() -> Unit>()
        val monitor = ActivationMonitor({ tasks += it }, { it() }, { _, task -> deadlines += task; { deadlines -= task } })
        monitor.connected(Any()) { valid() }
        assertTrue(monitor.latest.checking)
        deadlines.single()()
        assertEquals(ActivationState.UNVERIFIED, evaluate(monitor.latest))
        tasks.removeAt(0)(); assertEquals(ActivationState.UNVERIFIED, evaluate(monitor.latest))
        monitor.refresh(); tasks.removeAt(0)()
        assertEquals(ActivationState.ACTIVE, evaluate(monitor.latest))
    }
    @Test fun `silent refresh does not flash an active page back to loading`() {
        val tasks = mutableListOf<() -> Unit>()
        val monitor = ActivationMonitor({ tasks += it }, { it() }, { _, _ -> {} })
        monitor.connected(Any()) { valid() }; tasks.removeAt(0)()
        monitor.refresh(silent = true); assertEquals(ActivationState.ACTIVE, evaluate(monitor.latest))
        tasks.removeAt(0)(); assertEquals(ActivationState.ACTIVE, evaluate(monitor.latest))
    }
}
