package dev.amenhancer.module.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PcmOutputReserveTest {
    private fun pcm(bytes: Int, value: Byte = 7) = ByteBuffer.wrap(ByteArray(bytes) { value }).order(ByteOrder.LITTLE_ENDIAN)
    @Test fun `holds forward audio for thirty seconds and applies real bounded backpressure`() {
        val q = PcmOutputReserve(48_000, 2, 2, 30)
        val source = pcm(48_000 * 4)
        repeat(30) { source.rewind(); assertTrue(q.offer(source, it * 1_000_000L)) }
        assertEquals(30_000_000L, q.durationUs)
        source.rewind(); assertFalse(q.offer(source, 30_000_000)); assertEquals(0, source.position())
        assertEquals(30_000_000L, q.tailUs)
        q.drain(1) { b, _ -> b.position(b.limit()); true }
        source.rewind(); assertTrue(q.offer(source, 30_000_000)); assertTrue(q.allocatedBytes <= 32 * 1024 * 1024)
    }
    @Test fun `partial native writes keep the same buffer and accurate pending duration`() {
        val q = PcmOutputReserve(48_000, 2, 2, 20)
        val source = pcm(192_000); q.offer(source, 0)
        var pending: ByteBuffer? = null
        q.drain { b, pts -> assertEquals(0L, pts); pending = b; b.position(96_000); false }
        assertEquals(500_000L, q.durationUs)
        q.drain { b, _ -> assertSame(pending, b); assertEquals(ByteOrder.LITTLE_ENDIAN, b.order()); b.position(b.limit()); true }
        assertTrue(q.isEmpty); assertEquals(0L, q.durationUs)
    }
    @Test fun `restarted decoder cannot replay frames already present in the forward reserve`() {
        val q = PcmOutputReserve(48_000, 2, 2, 30)
        assertTrue(q.offer(pcm(192_000, 1), 0))
        assertTrue(q.offer(pcm(192_000, 2), 500_000, q.tailUs))
        assertEquals(1_500_000L, q.durationUs)
        val output = mutableListOf<Byte>()
        q.drain { b, _ -> while (b.hasRemaining()) output.add(b.get()); true }
        assertEquals(192_000, output.count { it == 1.toByte() })
        assertEquals(96_000, output.count { it == 2.toByte() })
    }
    @Test fun `failed offer never advances producer and source memory is not retained`() {
        val q = PcmOutputReserve(48_000, 2, 2, 20, memoryLimit = 8)
        val source = pcm(12); assertFalse(q.offer(source, 0)); assertEquals(0, source.position())
        val small = pcm(8); assertTrue(q.offer(small, 0)); small.put(0, 99)
        q.drain { b, _ -> assertEquals(7.toByte(), b.get(0)); b.position(b.limit()); true }
        q.clear(); assertEquals(0L, q.durationUs); q.release(); assertEquals(0, q.allocatedBytes)
    }
    @Test fun `circular writes preserve order across partial native writes and wrap`() {
        val q = PcmOutputReserve(8000, 1, 2, 20, memoryLimit = 12)
        assertTrue(q.offer(pcm(8, 1), 0))
        q.drain { b, _ -> assertTrue(b.isDirect); b.position(4); false }
        assertTrue(q.offer(pcm(8, 2), 500))
        val output = mutableListOf<Byte>()
        q.drain { b, _ -> while(b.hasRemaining()) output.add(b.get()); true }
        assertEquals(List(4) { 1.toByte() } + List(8) { 2.toByte() }, output)
        assertEquals(12, q.allocatedBytes)
    }
    @Test fun `high resolution audio reports its real memory limited duration`() {
        val q = PcmOutputReserve(192000, 2, 4, 30)
        val packet = pcm(192000 * 8)
        repeat(21) { packet.rewind(); assertTrue(q.offer(packet, it * 1000000L)) }
        packet.rewind(); assertFalse(q.offer(packet, 21000000))
        assertEquals(21000000L, q.durationUs)
        assertTrue(q.capacityBytes < 30L * 192000 * 8)
    }

    @Test fun `invalid frames fail explicitly instead of causing permanent native backpressure`() {
        val q = PcmOutputReserve(48000, 2, 2, 30)
        val broken = pcm(3)
        try { q.offer(broken, 0); fail("Malformed PCM must not wait for queue space forever") }
        catch (_: IllegalArgumentException) { assertEquals(0, broken.position()); assertTrue(q.isEmpty) }
    }

}
