package dev.amenhancer.module.hook

import android.content.Context
import android.os.Looper
import dev.amenhancer.module.config.EmbeddedConfigurationSession
import dev.amenhancer.module.config.EmbeddedContentManager
import dev.amenhancer.module.config.HostPrivateEmbeddedStorage
import dev.amenhancer.module.lyrics.LyricsPlaybackSources
import dev.amenhancer.module.lyrics.source.LyricFetchFailure
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.ModuleSettings
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EmbeddedLyricsPlaybackRuntimeTest {
    @get:Rule val files = TemporaryFolder()
    private val jobs = ArrayDeque<Runnable>()
    private val source = CustomLyricsSources.AM_LYRICS
    private val native = CustomLyricsSources.AM_NATIVE
    private val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="00:00:01.000" end="00:00:02.000">歌</p></div></body></tt>"""

    private fun manager(): EmbeddedContentManager {
        val application = RuntimeEnvironment.getApplication()
        val preferences = application.getSharedPreferences("playback-runtime-test", Context.MODE_PRIVATE)
        return EmbeddedContentManager(EmbeddedConfigurationSession(HostPrivateEmbeddedStorage(preferences, files.root)))
    }

    private fun runtime(manager: EmbeddedContentManager) = EmbeddedLyricsPlaybackRuntime(
        content = manager,
        settings = { ModuleSettings(customLyricsEnabled = true) },
        executor = Executor(jobs::addLast),
        fetch = { id, name ->
            if (id == 42L && name == source) AutoLyricsCandidate(source, ttml) to null
            else null to LyricFetchFailure.NOT_FOUND
        },
    )

    private fun drain() {
        while (jobs.isNotEmpty()) jobs.removeFirst().run()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun switchPublishesOnlyTheCurrentSongAndKeepsPreferenceOnFailure() {
        val manager = manager()
        val ready = mutableListOf<Long>()
        runtime(manager).use { controls ->
            controls.bind({ id, raw -> "$id:$raw" }, { it is String }, ready::add)
            controls.onSongChanged(42)
            controls.select(42, "native", ttml, null, null)
            controls.onSelected(42, native, "native", ttml)

            controls.selectSource(42, source)
            assertEquals(source, controls.snapshot(42).pendingSource)
            controls.onSongChanged(43)
            drain()
            assertNull(manager.sourceCache.preferredSource(42))
            assertNull(manager.sourceCache.readCandidate(42, source))
            assertTrue(ready.all { it == 43L })

            controls.onSongChanged(42)
            controls.selectSource(42, source)
            drain()
            assertEquals(source, manager.sourceCache.preferredSource(42))
            val selected = requireNotNull(controls.select(42, "native", ttml, null, null))
            assertEquals(source, selected.source)
            controls.onSelected(42, source, selected.payload, selected.ttml)

            controls.selectSource(42, CustomLyricsSources.LUNABEAT)
            drain()
            assertEquals(source, manager.sourceCache.preferredSource(42))
            assertNull(controls.snapshot(42).pendingSource)
            assertEquals(source, controls.select(42, "native", ttml, null, null)?.source)

            controls.selectSource(42, LyricsPlaybackSources.AUTOMATIC)
            drain()
            assertNull(manager.sourceCache.preferredSource(42))
        }
    }

    @Test fun offsetIsAppliedToOriginalAndPersistsIndependentlyAcrossSources() {
        val manager = manager()
        runtime(manager).use { controls ->
            controls.bind({ _, raw -> raw }, { it is String }, {})
            controls.onSongChanged(42)
            controls.onSelected(42, native, ttml, ttml)
            controls.withOffset(42, native, ttml, ttml)
            assertTrue(controls.setOffset(42, native, 500))
            assertTrue(controls.withOffset(42, native, ttml, ttml).toString().contains("00:00:01.500"))
            assertTrue(controls.setOffset(42, native, -250))
            assertTrue(controls.withOffset(42, native, ttml, ttml).toString().contains("00:00:00.750"))
            assertEquals(-250, manager().sourceCache.offsetMs(42, native))
            assertEquals(0, manager().sourceCache.offsetMs(42, source))
            assertEquals(ttml, controls.withOffset(42, source, ttml, ttml))
            assertTrue(controls.clearCache())
            assertEquals(-250, manager().sourceCache.offsetMs(42, native))
        }
    }
}
