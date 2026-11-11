package dev.amenhancer.module.playback

import java.nio.ByteBuffer

/** Looper-confined forward audio path. The adapter still owns AudioSink configuration and lifecycle. */
class BufferedPcmOutput(private val sink: Sink, private val seconds: Int = 30) {
    interface Sink {
        fun write(buffer: ByteBuffer, presentationUs: Long): Boolean
        fun positionUs(sourceEnded: Boolean = false): Long?
        fun play()
        fun pause()
        fun flush()
        fun reset()
        fun end()
        fun pending(): Boolean
        fun ended(): Boolean = !pending()
    }
    data class Format(val rate: Int, val channels: Int, val bytesPerSample: Int)
    data class Hold(val track: Long, val audibleUs: Long, val reservedUntilUs: Long)
    private var reserve: PcmOutputReserve? = null
    private var format: Format? = null
    private var inputTrack: Long? = null
    private var inputOffsetUs = 0L
    private var correctionUs = 0L
    private var playing = false
    private var endRequested = false
    private var endSent = false
    private var accepting = true
    private var resumeTailUs: Long? = null
    private var nativePending: ByteBuffer? = null
    private var nativePendingPts = 0L
    val nativeWritePending: Boolean get() = nativePending != null
    var hold: Hold? = null; private set
    var freshOriginalPcm = false; private set
    var nextPcmSeen = false; private set
    val pending: Boolean get() = nativeWritePending || reserve?.isEmpty == false || sink.pending() || (endRequested && !sink.ended())
    val bufferedUs: Long get() = reserve?.durationUs ?: 0
    val isDrained: Boolean get() = reserve?.isEmpty != false

    /** Called before each renderer-side event, using its actual ProtectedSampleStream and offset. */
    fun stream(track: Long?, offsetUs: Long) {
        inputTrack = track; inputOffsetUs = offsetUs
        hold?.takeIf { it.track == track }?.let {
            // The reconstructed renderer has a new epoch; keep the existing physical output epoch.
            correctionUs = savedOffsetUs - offsetUs
        }
    }
    private var savedOffsetUs = 0L

    /** False means the adapter must terminate the bridge and reconfigure through the native path. */
    fun configure(value: Format): Boolean {
        if (hold != null) return inputTrack != hold?.track || value == format
        if (value == format) return true
        check(isDrained) { "Cannot replace PCM format while accepted audio is pending" }
        reserve?.release()
        format = value
        reserve = PcmOutputReserve(value.rate, value.channels, value.bytesPerSample, seconds)
        return true
    }
    /** Finish a pre-existing native write before copying any new producer buffers into our reserve. */
    fun adoptNativeWrite(buffer: ByteBuffer, presentationUs: Long) {
        check(isDrained && nativePending == null)
        nativePending = buffer; nativePendingPts = presentationUs
    }
    private fun direct(buffer: ByteBuffer, pts: Long): Boolean {
        val existing = nativePending
        check(existing == null || existing === buffer) { "Native PCM write buffer identity changed" }
        val at = if (existing != null) nativePendingPts else pts
        val accepted = sink.write(buffer, at)
        if (accepted) nativePending = null else { nativePending = buffer; nativePendingPts = at }
        return accepted
    }
    fun offer(buffer: ByteBuffer, rawPresentationUs: Long): Boolean {
        if (nativeWritePending) return direct(buffer, rawPresentationUs + correctionUs)
        val held = hold
        if (held != null && inputTrack != held.track) {
            nextPcmSeen = true
            return false // Native backpressure: never play or fast-decode the temporary next song.
        }
        val q = reserve ?: return direct(buffer, rawPresentationUs)
        if (!accepting && q.isEmpty && resumeTailUs == null) return direct(buffer, rawPresentationUs + correctionUs)
        if (!accepting && resumeTailUs == null) return false // Disable drains already accepted audio before native bypass.
        val pts = rawPresentationUs + correctionUs
        val before = q.tailUs
        val bytesBefore = q.queuedBytes
        if (held != null && before != null && pts > before + 1000) return false
        val accepted = q.offer(buffer, pts, held?.let { q.tailUs } ?: resumeTailUs)
        if (accepted && resumeTailUs?.let { (q.tailUs ?: 0) > it } == true) resumeTailUs = null
        if (accepted && q.queuedBytes > bytesBefore) { endRequested = false; endSent = false }
        if (accepted && held != null && q.tailUs != before && q.tailUs?.let { it > held.reservedUntilUs } == true) freshOriginalPcm = true
        return accepted
    }
    /** Arm before native fatal teardown, while the real output track is still alive. */
    fun arm(): Hold? {
        if (!playing || !accepting || hold != null || bufferedUs < 2_000_000) return null
        val track = inputTrack?.takeIf { it > 0 } ?: return null
        val position = positionUs()?.takeIf { it >= 0 } ?: return null
        val tail = reserve?.tailUs ?: return null
        if (position < inputOffsetUs || tail <= position + correctionUs) return null
        savedOffsetUs = inputOffsetUs + correctionUs
        return Hold(track, position - inputOffsetUs, tail).also {
            hold = it; freshOriginalPcm = false; nextPcmSeen = false
        }
    }
    /** Native reset/pause/end are deferred only for a confirmed, bounded recovery transaction. */
    fun reset() { if (hold != null) return; reserve?.release(); correctionUs = 0; resumeTailUs = null; nativePending = null; endRequested = false; endSent = false; sink.reset(); playing = false }
    fun flush() { if (hold != null) return; reserve?.clear(); correctionUs = 0; resumeTailUs = null; nativePending = null; endRequested = false; endSent = false; sink.flush() }
    /** Attach to an already configured/playing sink without replaying native DSP configuration. */
    fun adoptPlayingState(value: Boolean) { playing = value }
    fun play() { playing = true; sink.play() }
    fun pause() { if (hold != null) return; playing = false; sink.pause() }
    fun resetNextPcmSignal() { nextPcmSeen = false }
    fun requestEnd() { if (hold == null || inputTrack == hold?.track) endRequested = true }
    fun pump() {
        if (!playing) return
        reserve?.drain { buffer, pts -> sink.write(buffer, pts) }
        if (!accepting && isDrained && resumeTailUs == null) reserve?.release()
        if (endRequested && isDrained && !nativeWritePending && (!endSent || !sink.ended())) { sink.end(); endSent = true }
    }
    /** Position uses the physical output clock, never the amount decoded ahead. */
    fun positionUs(sourceEnded: Boolean = false): Long? = sink.positionUs(sourceEnded && isDrained && !nativeWritePending)?.let { physical ->
        val held = hold
        if (held != null && inputTrack != held.track) inputOffsetUs
        else physical - correctionUs
    }
    fun audibleOriginalUs(): Long? = sink.positionUs()?.let { it - savedOffsetUs }
    fun finishHold() {
        if (hold != null && !freshOriginalPcm) resumeTailUs = reserve?.tailUs
        hold = null
    }
    /** Terminate a failed bridge without discarding the native renderer's DSP configuration. */
    fun abortOutput() {
        hold = null; resumeTailUs = null
        pause(); flush(); reserve?.release()
    }
    fun disable() { accepting = false; if (isDrained && resumeTailUs == null) reserve?.release() }
    fun enable() { accepting = true }
    fun release() { hold = null; nativePending = null; playing = false; reserve?.release(); reserve = null; sink.reset() }
}
