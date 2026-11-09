package dev.amenhancer.module.update

import org.junit.Test
import org.junit.Assert.*

class UpdateTransferRateTest {
    @Test fun `rate and remaining time follow received bytes rather than connection count`() {
        val sampler = UpdateTransferRateSampler()
        assertNull(sampler.sample(0, 8192, 100).bytesPerSecond)
        assertEquals(UpdateTransferRate(2048, 3), sampler.sample(2048, 8192, 1100))
        assertEquals(0L, sampler.sample(8192, 8192, 2100).remainingSeconds!!)
    }
    @Test fun `stalls retries and a reversed clock discard stale estimates`() {
        val sampler = UpdateTransferRateSampler()
        sampler.sample(0, 10000, 100)
        assertNotNull(sampler.sample(1000, 10000, 1100).remainingSeconds)
        assertNull(sampler.sample(1000, 10000, 5100).bytesPerSecond)
        sampler.sample(2000, 10000, 6100)
        assertNull(sampler.sample(0, 10000, 7100).remainingSeconds)
        assertNull(sampler.sample(100, 10000, 500).remainingSeconds)
    }
    @Test fun `unknown length never invents an ETA`() {
        val sampler = UpdateTransferRateSampler()
        sampler.sample(0, 0, 0)
        assertEquals(UpdateTransferRate(1000, null), sampler.sample(1000, 0, 1000))
    }
}
