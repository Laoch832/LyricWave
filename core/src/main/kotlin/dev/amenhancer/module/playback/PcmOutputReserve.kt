package dev.amenhancer.module.playback

import java.nio.ByteBuffer
import java.util.ArrayDeque
import kotlin.math.ceil

/** Forward decoded PCM, confined to the audio Looper. No disk, keys or encoded media. */
class PcmOutputReserve(
    val sampleRate: Int,
    val channels: Int,
    val bytesPerSample: Int,
    seconds: Int,
    memoryLimit: Int = 32 * 1024 * 1024,
) {
    val frameBytes: Int get() = channels * bytesPerSample
    init {
        require(sampleRate in 8_000..192_000 && channels in 1..8 && bytesPerSample in 2..4)
        require(seconds == 20 || seconds == 30)
        require(memoryLimit >= frameBytes)
    }
    val capacityBytes: Long = minOf(sampleRate.toLong() * frameBytes * seconds, memoryLimit.toLong()) / frameBytes * frameBytes
    private var storage: ByteBuffer? = null
    private data class Packet(val data: ByteBuffer, val atUs: Long)
    private val packets = ArrayDeque<Packet>()
    private var writeAt = 0
    var queuedBytes: Long = 0; private set
    var tailUs: Long? = null; private set
    val durationUs: Long get() = queuedBytes / frameBytes * 1_000_000L / sampleRate
    val allocatedBytes: Int get() = storage?.capacity() ?: 0
    val isEmpty: Boolean get() = packets.isEmpty()

    /** False is backpressure: it leaves the producer untouched. Memory and packet count are bounded. */
    fun offer(source: ByteBuffer, presentationUs: Long, discardBeforeUs: Long? = null): Boolean {
        val bytes = source.remaining()
        require(bytes % frameBytes == 0 && presentationUs >= 0) { "Invalid PCM frame alignment or timestamp" }
        val skip = if (discardBeforeUs != null && discardBeforeUs > presentationUs) {
            val frames = ceil((discardBeforeUs - presentationUs).toDouble() * sampleRate / 1_000_000.0).toLong()
            minOf(bytes.toLong() / frameBytes, frames).toInt() * frameBytes
        } else 0
        val count = bytes - skip
        if (count == 0) { source.position(source.limit()); return true }
        if (queuedBytes + count > capacityBytes || packets.size > 4094) return false
        val ring = storage ?: ByteBuffer.allocateDirect(capacityBytes.toInt()).also { storage = it }
        val copy = source.duplicate().apply { position(source.position() + skip) }
        val start = presentationUs + skip.toLong() / frameBytes * 1_000_000L / sampleRate
        var left = count
        while (left > 0) {
            val size = minOf(left, ring.capacity() - writeAt)
            val view = ring.duplicate().apply { position(writeAt); limit(writeAt + size) }.slice().order(source.order())
            copy.limit(copy.position() + size)
            view.put(copy); view.flip()
            packets.add(Packet(view, start + (count - left).toLong() / frameBytes * 1_000_000L / sampleRate))
            writeAt = (writeAt + size) % ring.capacity()
            left -= size
        }
        queuedBytes += count
        tailUs = start + count.toLong() / frameBytes * 1_000_000L / sampleRate
        source.position(source.limit())
        return true
    }

    /** A false native write may consume part of a packet. Retry exactly that ByteBuffer and PTS. */
    fun drain(maxPackets: Int = 8, write: (ByteBuffer, Long) -> Boolean) {
        repeat(maxPackets) {
            val packet = packets.peek() ?: return
            val before = packet.data.remaining()
            val accepted = write(packet.data, packet.atUs)
            val consumed = before - packet.data.remaining()
            check(consumed >= 0 && consumed % frameBytes == 0) { "Invalid native PCM consumption" }
            queuedBytes -= consumed
            if (!accepted) return
            check(!packet.data.hasRemaining()) { "Native sink acknowledged unconsumed PCM" }
            packets.remove()
        }
    }
    fun clear() { packets.clear(); queuedBytes = 0; tailUs = null; writeAt = 0 }
    fun release() { clear(); storage = null }
}
