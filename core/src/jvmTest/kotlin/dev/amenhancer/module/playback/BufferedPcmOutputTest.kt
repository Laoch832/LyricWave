package dev.amenhancer.module.playback

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class BufferedPcmOutputTest {
    private class Output : BufferedPcmOutput.Sink {
        var blocked = true
        var clock = 1_000_000L
        val events = mutableListOf<String>()
        val heard = mutableListOf<Byte>()
        override fun write(buffer: ByteBuffer, presentationUs: Long): Boolean {
            if (blocked) return false
            events += "pcm:$presentationUs"
            while(buffer.hasRemaining()) heard += buffer.get()
            return true
        }
        override fun positionUs(sourceEnded: Boolean) = clock
        override fun play() { events += "play" }
        override fun pause() { events += "pause" }
        override fun flush() { events += "flush" }
        override fun reset() { events += "reset" }
        override fun end() { events += "end" }
        override fun pending() = false
    }
    private fun data(value: Int, seconds: Int = 1) = ByteBuffer.wrap(ByteArray(16000 * seconds) { value.toByte() })
    private fun prepared(s: Output): BufferedPcmOutput = BufferedPcmOutput(s).apply {
        stream(81, 1_000_000); configure(BufferedPcmOutput.Format(8000, 1, 2)); play()
        repeat(4) { assertTrue(offer(data(it + 1), 1_000_000L + it * 1_000_000)) }
    }
    @Test fun `fatal native teardown and temporary next output cannot interrupt or replace reserved audio`() {
        val s = Output(); val b = prepared(s)
        val held = b.arm(); assertEquals(81L, held?.track)
        b.pause(); b.flush(); b.reset()
        assertEquals(listOf("play"), s.events)
        b.stream(17, 8_000_000); b.requestEnd()
        assertTrue(b.configure(BufferedPcmOutput.Format(48000, 2, 2)))
        val next = data(9); assertFalse(b.offer(next, 8_000_000)); assertEquals(0, next.position())
        assertTrue(b.nextPcmSeen)
        s.blocked = false; b.pump()
        assertEquals((1..4).flatMap { v -> List(16000) { v.toByte() } }, s.heard)
        assertFalse(s.events.contains("reset")); assertFalse(s.events.contains("end"))
    }
    @Test fun `restored renderer epoch clips duplicates and appends fresh original PCM continuously`() {
        val s = Output(); val b = prepared(s); b.arm()
        b.stream(17, 8_000_000); b.stream(81, 2_000_000)
        assertTrue(b.offer(data(99, 4), 2_000_000)); assertFalse(b.freshOriginalPcm)
        assertTrue(b.offer(data(5), 6_000_000)); assertTrue(b.freshOriginalPcm)
        s.clock = 1_800_000
        assertEquals(800_000L, b.audibleOriginalUs())
        assertEquals(2_800_000L, b.positionUs())
        b.finishHold(); s.blocked = false; b.pump()
        assertEquals((1..5).flatMap { v -> List(16000) { v.toByte() } }, s.heard)
        assertEquals(listOf("pcm:1000000", "pcm:2000000", "pcm:3000000", "pcm:4000000", "pcm:5000000"), s.events.filter { it.startsWith("pcm") })
    }
    @Test fun `pause retains accepted future audio and disable drains before bypass`() {
        val s = Output(); val b = prepared(s)
        b.pause(); b.disable(); s.blocked = false; b.pump(); assertTrue(s.heard.isEmpty())
        val packet = data(5); assertFalse(b.offer(packet, 5_000_000)); assertEquals(0, packet.position())
        b.play(); b.pump(); assertEquals(64000, s.heard.size)
        assertTrue(b.offer(packet, 5_000_000)); assertEquals(80000, s.heard.size)
    }
    @Test fun `seek flushes reserve and natural end waits for accepted PCM`() {
        val s = Output(); val b = prepared(s)
        b.requestEnd(); b.pump(); assertFalse(s.events.contains("end"))
        s.blocked = false; b.pump(); b.pump(); assertEquals(1, s.events.count { it == "end" })
        b.flush(); assertTrue(b.isDrained); assertEquals(0, b.bufferedUs)
        b.reset(); assertEquals("reset", s.events.last())
    }
    @Test fun `disabling during restoration cannot replay thirty seconds already reserved`() {
        val s = Output(); val b = prepared(s); b.arm(); b.stream(81, 2_000_000)
        b.disable(); b.finishHold()
        assertTrue(b.offer(data(99, 4), 2_000_000))
        assertTrue(b.offer(data(5), 6_000_000))
        val pending = data(6); assertFalse(b.offer(pending, 7_000_000))
        s.blocked = false; b.pump(); assertTrue(b.offer(pending, 7_000_000))
        assertEquals((1..6).flatMap { v -> List(16000) { v.toByte() } }, s.heard)
    }

    @Test fun `reenabling during a partial native bypass write retains the exact pending buffer`() {
        val native = Output().apply { blocked = false }
        var original: ByteBuffer? = null
        var calls = 0
        val sink = object : BufferedPcmOutput.Sink by native {
            override fun write(buffer: ByteBuffer, presentationUs: Long): Boolean {
                calls++
                if (calls == 1) {
                    original = buffer
                    repeat(8000) { native.heard += buffer.get() }
                    return false
                }
                if (calls == 2) assertSame(original, buffer)
                return native.write(buffer, presentationUs)
            }
        }
        val b = BufferedPcmOutput(sink)
        b.stream(81, 1_000_000); b.configure(BufferedPcmOutput.Format(8000, 1, 2)); b.play(); b.disable()
        val first = data(1)
        assertFalse(b.offer(first, 1_000_000)); assertTrue(b.nativeWritePending)
        b.enable(); assertTrue(b.offer(first, 1_000_000)); assertFalse(b.nativeWritePending)
        assertTrue(b.offer(data(2), 2_000_000)); b.pump()
        assertEquals(List(16000) { 1.toByte() } + List(16000) { 2.toByte() }, native.heard)
    }

    @Test fun `EOS repeats native drain until processors finish and original tail remains playable during recovery`() {
        val physical = Output().apply { blocked = false }
        var drains = 0
        val sink = object : BufferedPcmOutput.Sink by physical {
            override fun end() { drains++ }
            override fun ended() = drains >= 3
        }
        val b = BufferedPcmOutput(sink)
        b.stream(81, 1_000_000); b.configure(BufferedPcmOutput.Format(8000, 1, 2)); b.play()
        repeat(4) { b.offer(data(it + 1), 1_000_000L + it * 1_000_000) }
        assertNotNull(b.arm()); b.requestEnd(); b.pump()
        assertEquals(1, drains); assertTrue(b.pending)
        b.pump(); assertTrue(b.pending)
        b.pump(); assertFalse(b.pending)
        b.pump(); assertEquals(3, drains)
    }

}
