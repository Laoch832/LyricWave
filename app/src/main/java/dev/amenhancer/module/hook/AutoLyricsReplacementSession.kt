package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.LlmLyricsClient
import dev.amenhancer.module.i18n.ModuleText

import dev.amenhancer.module.lyrics.source.LunabeatClient
import dev.amenhancer.module.lyrics.source.HttpLyricTransport
import dev.amenhancer.module.lyrics.source.AutoLyricsSourceResolver
import dev.amenhancer.module.lyrics.source.AmllTtmlClient
import dev.amenhancer.module.lyrics.FileAmLyricsIndexCache
import java.util.concurrent.ScheduledThreadPoolExecutor
import dev.amenhancer.module.lyrics.source.AmLyricsClient
import android.app.Application
import dev.amenhancer.module.config.EmbeddedContentManager
import dev.amenhancer.module.lyrics.TtmlInputPolicy
import dev.amenhancer.module.lyrics.LyricsPlaybackSources
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.ModuleSettings
import dev.amenhancer.module.lyrics.FileLlmLyricsActivityStore
import dev.amenhancer.module.lyrics.FileLlmLyricsCache
import dev.amenhancer.module.lyrics.LlmLyricsConfig
import dev.amenhancer.module.lyrics.LlmLyricsPhase
import dev.amenhancer.module.lyrics.LlmLyricsDocument
import dev.amenhancer.module.lyrics.LlmTransliterationDocument
import dev.amenhancer.module.lyrics.source.LunabeatCatalogCache
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal fun createAutoLyricsRuntime(
    application: Application,
    content: EmbeddedContentManager,
    lunabeatCatalogCache: LunabeatCatalogCache,
    configuration: () -> ModuleSettings,
): AutoLyricsRuntime {
    val sourceStore = content.sourceCache
    val failures = ThreadLocal<dev.amenhancer.module.lyrics.source.LyricFetchFailure?>()
    val lyricTransport = HttpLyricTransport(
        connectTimeoutMs = 4_000,
        readTimeoutMs = 8_000,
        maxResponseBytes = TtmlInputPolicy.MAX_TTML_BYTES,
        onFailure = { failures.set(it) },
    )
    val indexTransport = HttpLyricTransport(
        connectTimeoutMs = 4_000,
        readTimeoutMs = 8_000,
        maxResponseBytes = LunabeatClient.INDEX_MAX_BYTES,
        onFailure = { failures.set(it) },
    )
    val lunabeat = LunabeatClient(
        indexTransport = indexTransport,
        lyricsTransport = lyricTransport,
        cache = lunabeatCatalogCache,
        onFailure = { failures.set(it) },
    )
    val customSourceBaseUrl = ModuleSettings.normalizeCustomLyricsSourceBaseUrl(configuration().customLyricsSourceBaseUrl).takeIf(String::isNotEmpty)
    val indexScheduler = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "lyricwave-amlyrics-index").apply { isDaemon = true } }
    val amLyricsIndexCache = FileAmLyricsIndexCache(
        directory = File(application.filesDir, "ampp-amlyrics"), transport = indexTransport,
        indexUrls = { AmLyricsClient(indexTransport, customSourceBaseUrl).indexUrls() }, executor = indexScheduler,
    )
    val amll = AmllTtmlClient(lyricTransport, customSourceBaseUrl, onFailure = { failures.set(it) })
    val amLyrics = AmLyricsClient(lyricTransport, customSourceBaseUrl, amLyricsIndexCache, onFailure = { failures.set(it) })
    val resolver = AutoLyricsSourceResolver.fixed(
        amll = amll,
        amLyrics = amLyrics,
        lunabeat = lunabeat,
    )
    val cache = object : AutoLyricsCache {
        override fun readCandidate(appleMusicId: Long, source: String): AutoLyricsCandidate? =
            sourceStore.readCandidate(appleMusicId, source)
        override fun writeCandidateIfCurrent(appleMusicId: Long, candidate: AutoLyricsCandidate, generation: Long): Boolean =
            sourceStore.writeCandidate(appleMusicId, candidate, generation)
        override fun generation(): Long = sourceStore.generation()
    }
    val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        // Keep only one queued task; a newer song must replace stale work.
        ArrayBlockingQueue(1),
        { runnable -> Thread(runnable, "ampp-auto-lyrics").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )
    val refreshExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { runnable -> Thread(runnable, "ampp-lyrics-refresh").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    refreshExecutor.execute { sourceStore.prepare() }
    val llmExecutor = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4),
        { task -> Thread(task, "ampp-llm-lyrics").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    val llmClient = LlmLyricsClient()
    val llmCache = FileLlmLyricsCache(File(application.filesDir, FileLlmLyricsCache.DIRECTORY))
    LyricsPlaybackRuntimeRegistry.attachLlmStatus(llmExecutor)
    val llm = createCachedLlmLyricsRuntime(
        client = llmClient,
        cache = llmCache,
        activity = FileLlmLyricsActivityStore(File(application.filesDir, FileLlmLyricsActivityStore.DIRECTORY)),
        configuration = {
            configuration().let { settings ->
                if (settings.llmLyricsTransliterationEnabled || (settings.customLyricsEnabled && settings.llmLyricsEnabled)) {
                    LlmLyricsConfig.fromSettings(settings)
                } else null
            }
        },
        executor = Executor { task ->
            llmExecutor.execute(task)
        },
        close = { llmExecutor.shutdownNow(); LyricsPlaybackRuntimeRegistry.detachLlmStatus(llmExecutor) },
        translationEnabled = {
            configuration().let { it.customLyricsEnabled && it.llmLyricsEnabled }
        },
        transliterationEnabled = { configuration().llmLyricsTransliterationEnabled },
        transliterate = transliterate@{ request, isCancelled ->
            if (isCancelled()) return@transliterate null
            val document = LlmTransliterationDocument.prepare(request.originalTtml) ?: run {
                request.reportStatus(if (LlmTransliterationDocument.hasPronunciation(request.originalTtml))
                    LlmLyricsPhase.EXISTS else LlmLyricsPhase.UNSUPPORTED)
                return@transliterate null
            }
            if (document.isLatinOnly) {
                request.reportStatus(LlmLyricsPhase.NOT_NEEDED)
                return@transliterate null
            }
            if (isCancelled()) return@transliterate null
            val journal = FileLlmLyricsActivityStore(File(application.filesDir, FileLlmLyricsActivityStore.DIRECTORY))
            llmCache.read(request.appleMusicId, request.originalTtml, request.config, kind = "transliteration")
                ?.let { cached ->
                    if (isCancelled()) return@transliterate null
                    journal.event(dev.amenhancer.module.lyrics.LlmActivityKind.CACHE_HIT, request.config, request.appleMusicId, request.title, request.artist)
                    request.reportStatus(LlmLyricsPhase.CACHED)
                    return@transliterate cached
                }
            var callId: String? = null
            request.reportStatus(LlmLyricsPhase.GENERATING)
            llmClient.transliterate(
                config = request.config,
                document = document,
                onStarted = { callId = journal.begin(dev.amenhancer.module.lyrics.LlmActivityKind.TRANSLITERATION,
                    request.config, request.appleMusicId, request.title, request.artist) },
                onCompleted = { result -> journal.complete(callId, result) },
                title = request.title,
                artist = request.artist,
                onFailure = { reason, status -> request.reportStatus(llmFailurePhase(reason, status)) },
                isCancelled = isCancelled,
            )?.takeUnless { isCancelled() }?.also { ttml ->
                llmCache.write(request.appleMusicId, request.originalTtml, request.config, ttml,
                    title = request.title, artist = request.artist, kind = "transliteration")
            }
        },
        discardPending = { llmExecutor.queue.clear() },
        onStatusChanged = { LyricsPlaybackRuntimeRegistry.publishLlmStatus(llmExecutor, it) },
    )
    val playbackExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "ampp-lyrics-controls").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    val playback = EmbeddedLyricsPlaybackRuntime(
        content = content,
        settings = configuration,
        executor = playbackExecutor,
        fetch = { id, source ->
            failures.remove()
            val result = resolver.fetchSelected(id, source) {
                failures.set(dev.amenhancer.module.lyrics.source.LyricFetchFailure.INVALID)
            }
            result to failures.get().also { failures.remove() }
        },
    )
    LyricsPlaybackRuntimeRegistry.attach(playback)
    return AutoLyricsRuntime(
        resolver = resolver,
        cache = cache,
        executor = executor,
        llm = llm,
        playback = playback,
        refreshExecutor = Executor { task ->
            refreshExecutor.queue.clear()
            refreshExecutor.execute(task)
        },
        closeRefresh = {
            refreshExecutor.shutdownNow()
            indexScheduler.shutdownNow()
            playbackExecutor.shutdownNow()
            executor.shutdownNow()
            playback.close()
        },
        refreshSong = refresh@{ appleMusicId, isCancelled ->
            val source = playback.snapshot(appleMusicId).activeSource?.takeIf {
                it == CustomLyricsSources.AMLL || it == CustomLyricsSources.AM_LYRICS || it == CustomLyricsSources.LUNABEAT
            } ?: return@refresh null
            val previous = sourceStore.entryFor(appleMusicId, source) ?: return@refresh null
            if (!previous.enabled || isCancelled()) return@refresh null
            val epoch = sourceStore.generation()
            val candidate = resolver.fetchSelected(appleMusicId, source) ?: return@refresh null
            val current = playback.snapshot(appleMusicId)
            if (isCancelled() || !current.isCurrentSong || current.activeSource != source ||
                (current.preferredSource == LyricsPlaybackSources.AUTOMATIC && !TtmlTimingPolicy.isWord(candidate.ttml)) ||
                sourceStore.entryFor(appleMusicId, source) != previous ||
                !sourceStore.writeCandidate(appleMusicId, candidate, epoch)
            ) return@refresh null
            sourceStore.entryFor(appleMusicId, source)?.takeIf { it.sha256 != previous.sha256 }
        },
    )
}
