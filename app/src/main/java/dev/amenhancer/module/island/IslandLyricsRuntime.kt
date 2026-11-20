package dev.amenhancer.module.island

import android.app.Application
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import dev.amenhancer.module.BuildConfig
import dev.amenhancer.module.hook.CurrentSongIdentityCache
import dev.amenhancer.module.hook.HookRegistrationScope
import dev.amenhancer.module.hook.IslandLyricsSourceState
import dev.amenhancer.module.hook.ModernMethodHook
import dev.amenhancer.module.hook.ModernXposedRuntime
import java.lang.ref.WeakReference

/** Uses Apple Music's media clock and the already selected timed lyrics. */
internal object IslandLyricsRuntime {
    @Volatile private var session: Session? = null

    fun install(application: Application, song: CurrentSongIdentityCache, enabled: Boolean) {
        val scope = HookRegistrationScope()
        val runtime = Session(application, song, enabled)
        try {
            val type = MediaSession::class.java
            check(ModernXposedRuntime.hookMethod(type.getMethod("setPlaybackState", PlaybackState::class.java),
                object : ModernMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable == null) runtime.state(param.thisObject as? MediaSession, param.args[0] as? PlaybackState)
                    }
                }, scope))
            check(ModernXposedRuntime.hookMethod(type.getMethod("release"), object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runtime.release(param.thisObject as? MediaSession)
                }
            }, scope))
            check(ModernXposedRuntime.hookMethod(type.getMethod("setActive", Boolean::class.javaPrimitiveType), object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable == null && param.args.firstOrNull() == false) {
                        runtime.release(param.thisObject as? MediaSession)
                    }
                }
            }, scope))
            scope.activate()
            session = runtime
        } catch (error: Throwable) {
            scope.close()
            runtime.close()
            ModernXposedRuntime.log("Island lyrics media clock unavailable", error)
        }
    }

    fun setEnabled(enabled: Boolean) { session?.setEnabled(enabled) }

    private class Session(application: Application, private val song: CurrentSongIdentityCache, enabled: Boolean) {
        private val resolver = application.contentResolver
        private val provider = Uri.parse("content://${BuildConfig.APPLICATION_ID}.update-control")
        private val thread = HandlerThread("LyricWave-island").apply { start() }
        private val worker = Handler(thread.looper)
        private var enabled = enabled
        private var owner = WeakReference<MediaSession>(null)
        private var playback: PlaybackState? = null
        private var playbackTrackId: Long? = null
        private var sourceTtml: String? = null
        private var timeline: IslandLyricTimeline? = null
        private var published: Pair<String, String>? = null
        private var lastSentAt = 0L
        private val refresh = Runnable { refresh() }
        private val songSubscription = song.addListener { worker.post(refresh) }

        fun setEnabled(value: Boolean) {
            worker.post {
                enabled = value
                refresh()
            }
        }

        fun state(media: MediaSession?, value: PlaybackState?) {
            if (media == null) return
            val trackId = song.current()?.details?.appleMusicId
            worker.post {
                if (owner.get() != null && owner.get() !== media && value?.state != PlaybackState.STATE_PLAYING) return@post
                owner = WeakReference(media)
                playback = value
                playbackTrackId = trackId
                refresh()
            }
        }

        fun release(media: MediaSession?) {
            worker.post {
                if (media != null && owner.get() === media) {
                    owner.clear()
                    playback = null
                    refresh()
                }
            }
        }

        fun close() {
            songSubscription.close()
            worker.removeCallbacksAndMessages(null)
            thread.quitSafely()
        }

        private fun refresh() {
            worker.removeCallbacks(refresh)
            val state = playback
            if (!enabled || state == null || owner.get() == null || state.state != PlaybackState.STATE_PLAYING) {
                publish(null)
                sourceTtml = null
                timeline = null
                return
            }
            val details = song.current()?.details
            if (details?.appleMusicId != playbackTrackId) {
                publish(null)
                return
            }
            val document = details?.appleMusicId?.let(IslandLyricsSourceState::read)
            if (document != sourceTtml) {
                sourceTtml = document
                timeline = sourceTtml?.let(IslandLyricTimeline::parse)
            }
            val elapsed = state.lastPositionUpdateTime.takeIf { it > 0 }
                ?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0) } ?: 0
            val speed = state.playbackSpeed.takeIf(Float::isFinite)?.coerceIn(0f, 4f) ?: 0f
            val position = state.position + (elapsed * speed).toLong()
            val title = details?.title
            val line = timeline?.at(position)?.takeIf { state.position >= 0 }
            publish(if (title.isNullOrBlank() || line.isNullOrBlank()) null else title to line)
            worker.postDelayed(refresh, 400)
        }

        private fun publish(value: Pair<String, String>?) {
            val now = SystemClock.elapsedRealtime()
            if (value == published && (value == null || now - lastSentAt < 10_000)) return
            if (now - lastSentAt < 500 && value != null) return
            lastSentAt = now
            val result = runCatching {
                resolver.call(provider, IslandLyricsNotification.METHOD, null, value?.let { (title, line) ->
                    Bundle().apply {
                        putString("title", title.take(120))
                        putString("line", line.take(180))
                    }
                })
            }
            if (result.getOrNull() != null) published = value
        }
    }
}
