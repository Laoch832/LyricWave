package dev.amenhancer.module.hook

import dev.amenhancer.module.playback.NextAssetPreloadState as State
import org.junit.Assert.*
import org.junit.Test

class NextAssetWarmupTest {
    private var time = 100L
    private var requests = 0
    private var stops = 0
    private var callback: ((Any?) -> Unit)? = null
    private var state = State.WAITING
    private val warmup = NextAssetWarmup({ time }, { _, _, done -> requests++; callback = done; { stops++ } }, { state = it })
    private val item = Any()
    private val candidate = NextAssetWarmup.Candidate(1, 3, item) // shuffled next, not index+1
    @Test fun `requests near end and hands over identical native object once`() {
        warmup.update(candidate, 70_000, true, true); assertEquals(0, requests)
        warmup.update(candidate, 15_000, true, true); assertEquals(1, requests)
        val asset = Any(); callback!!(asset); assertEquals(State.READY, state)
        assertSame(asset, warmup.take(item, 1, 3) { true }); assertEquals(State.USED, state)
        assertNull(warmup.take(item, 1, 3) { true })
        warmup.update(candidate, 10_000, true, true); assertEquals(1, requests)
    }
    @Test fun `switch off pause and changed queue cancel only unused warmup and ignore late completion`() {
        warmup.update(candidate, 10_000, true, true); val late = callback!!
        warmup.update(candidate, 10_000, false, true); assertEquals(1, stops)
        late(Any()); assertNull(warmup.take(item, 1, 3) { true })
        warmup.update(candidate, 10_000, true, true)
        warmup.update(candidate, 10_000, true, false); assertEquals(2, stops)
        warmup.update(candidate, 10_000, true, true)
        warmup.update(NextAssetWarmup.Candidate(1, 9, Any()), 10_000, true, true)
        assertEquals(3, stops); assertEquals(4, requests)
        warmup.update(NextAssetWarmup.Candidate(9, 10, Any()), 80_000, true, true)
        assertEquals(State.WAITING, state) // cleared resources must never keep a stale READY label
    }
    @Test fun `same song wrong occurrence wrong item and changed shuffle cannot consume result`() {
        warmup.update(candidate, 10_000, true, true); callback!!(Any())
        assertNull(warmup.take(Any(), 1, 3) { true })
        assertNull(warmup.take(item, 1, 9) { true })
        assertNull(warmup.take(item, 8, 3) { true })
        assertNotNull(warmup.take(item, 3, 4) { true }) // native advanced to the prepared occurrence
    }
    @Test fun `expired denied and failed assets fall back without retry loop`() {
        warmup.update(candidate, 10_000, true, true); callback!!(Any())
        time += 90_001
        assertNull(warmup.take(item, 1, 3) { true }); assertEquals(State.WAITING, state)
        warmup.update(candidate, 9_000, true, true); assertEquals(1, requests)
    }
    @Test fun `pending native consumer joins request without cancellation on release`() {
        warmup.update(candidate, 10_000, true, true)
        assertNull(warmup.take(item, 1, 3) { true }); warmup.clear()
        assertEquals(0, stops); callback!!(Any()); assertEquals(State.WAITING, state)
    }
    @Test fun `timeout frees unused request and no next item or repeat one does not preload`() {
        warmup.update(candidate, 10_000, true, true); time += 10_001
        warmup.update(candidate, 9_000, true, true); assertEquals(1, stops); assertEquals(1, requests)
        warmup.update(null, 10_000, true, true)
        warmup.update(NextAssetWarmup.Candidate(1, 1, item), 10_000, true, true)
        assertEquals(1, requests)
    }
    @Test fun `native lookahead before the warmup window prevents redundant fetches`() {
        warmup.nativeRequested(1, 3, item)
        warmup.update(candidate, 50_000, true, true)
        assertEquals(0, requests); assertEquals(State.NATIVE, state)
    }
    @Test fun `expiry is independent of fresh application samples and late completion cannot restore readiness`() {
        warmup.update(candidate, 10_000, true, true)
        time += 10_001; warmup.expire()
        assertEquals(1, stops); assertEquals(State.FALLBACK, state)
        callback!!(Any()); assertEquals(State.FALLBACK, state)
        assertNull(warmup.take(item, 1, 3) { true })
    }
    @Test fun `throwing native acceptance check discards the result and falls back`() {
        warmup.update(candidate, 10_000, true, true); callback!!(Any())
        assertNull(warmup.take(item, 1, 3) { throw IllegalStateException() })
        assertEquals(State.FALLBACK, state)
    }
    @Test fun `native policy denial discards asset and uses native fetch`() {
        warmup.update(candidate, 10_000, true, true); callback!!(Any())
        assertNull(warmup.take(item, 1, 3) { false }); assertEquals(State.FALLBACK, state)
    }
}
