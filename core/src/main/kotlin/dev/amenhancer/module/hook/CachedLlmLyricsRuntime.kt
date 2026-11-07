package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.FileLlmLyricsCache
import dev.amenhancer.module.lyrics.FileLlmLyricsActivityStore
import dev.amenhancer.module.lyrics.LlmActivityKind
import dev.amenhancer.module.lyrics.LlmLyricsClient
import dev.amenhancer.module.lyrics.LlmLyricsConfig
import dev.amenhancer.module.lyrics.LlmLyricsDocument
import java.util.concurrent.Executor

/** Cache valid completed translations independently from the lifetime of a native player pointer. */
fun createCachedLlmLyricsRuntime(
    cache: FileLlmLyricsCache,
    configuration: () -> LlmLyricsConfig?,
    executor: Executor,
    close: () -> Unit = {},
    client: LlmLyricsClient = LlmLyricsClient(),
    activity: FileLlmLyricsActivityStore? = null,
): LlmLyricsRuntime = LlmLyricsRuntime(
    configuration = configuration,
    translate = { request, isCancelled ->
      cache.withTranslation(request.appleMusicId, isCancelled) translation@{
        fun event(kind: LlmActivityKind, reason: String? = null) {
            runCatching { activity?.event(kind, request.config, request.appleMusicId, request.title, request.artist, reason) }
        }
        if (request.appleMusicId <= 0L || !request.config.isConfigured || isCancelled()) return@translation null
        val document = LlmLyricsDocument.prepare(request.originalTtml) ?: run {
            event(LlmActivityKind.SKIPPED, "ineligible")
            return@translation null
        }
        if (isCancelled()) return@translation null
        val lookup = cache.lookup(request.appleMusicId, request.originalTtml, request.config)
        lookup.ttml?.let { cached ->
            if (isCancelled()) return@translation null
            event(LlmActivityKind.CACHE_HIT)
            return@translation cached
        }
        if (lookup.state == FileLlmLyricsCache.LookupState.SOURCE_CHANGED) event(LlmActivityKind.SOURCE_CHANGED)
        if (lookup.state == FileLlmLyricsCache.LookupState.SETTINGS_CHANGED) event(LlmActivityKind.SETTINGS_CHANGED)
        var callId: String? = null
        val translations = client.translate(
            config = request.config, lines = document.translationLines,
            title = request.title, artist = request.artist, sourceLanguage = document.sourceLanguage,
            onStarted = { callId = activity?.begin(LlmActivityKind.TRANSLATION, request.config,
                request.appleMusicId, request.title, request.artist) },
            onCompleted = { result -> activity?.complete(callId, result) },
            isCancelled = isCancelled,
        ) ?: return@translation null
        if (isCancelled()) return@translation null
        val translated = document.withTranslations(translations) ?: return@translation null
        if (isCancelled()) return@translation null
        // Saving must not depend on a host pointer surviving a settings page or playback visit.
        val saved = cache.write(request.appleMusicId, request.originalTtml, request.config, translated, request.title, request.artist)
        event(if (saved) LlmActivityKind.CACHE_SAVED else LlmActivityKind.CACHE_SAVE_FAILED)
        translated.takeUnless { isCancelled() }
      }
    },
    executor = executor,
    close = close,
    onRejected = { request ->
        runCatching { activity?.event(LlmActivityKind.PLAYER_REJECTED, request.config,
            request.appleMusicId, request.title, request.artist, "player_rejected") }
    },
)
