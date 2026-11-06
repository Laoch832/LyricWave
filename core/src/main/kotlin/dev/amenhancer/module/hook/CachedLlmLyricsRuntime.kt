package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.FileLlmLyricsCache
import dev.amenhancer.module.lyrics.LlmLyricsClient
import dev.amenhancer.module.lyrics.LlmLyricsConfig
import dev.amenhancer.module.lyrics.LlmLyricsDocument
import java.util.concurrent.Executor

/** Keeps cache lookup, source revision checks and host-accepted persistence on one path. */
fun createCachedLlmLyricsRuntime(
    cache: FileLlmLyricsCache,
    configuration: () -> LlmLyricsConfig?,
    executor: Executor,
    close: () -> Unit = {},
    client: LlmLyricsClient = LlmLyricsClient(),
): LlmLyricsRuntime = LlmLyricsRuntime(
    configuration = configuration,
    translate = translate@{ request, isCancelled ->
        if (isCancelled()) return@translate null
        // Recheck eligibility on every visit, even when a translation is on disk.
        val document = LlmLyricsDocument.prepare(request.originalTtml) ?: return@translate null
        if (isCancelled()) return@translate null
        // The full original TTML hash includes vocals, timing and translation tracks.
        cache.read(request.appleMusicId, request.originalTtml, request.config)?.let { cached ->
            return@translate cached.takeUnless { isCancelled() }
        }
        val translations = client.translate(
            config = request.config,
            lines = document.translationLines,
            title = request.title,
            artist = request.artist,
            sourceLanguage = document.sourceLanguage,
            isCancelled = isCancelled,
        ) ?: return@translate null
        if (isCancelled()) return@translate null
        document.withTranslations(translations)?.takeUnless { isCancelled() }
    },
    executor = executor,
    close = close,
    onPrepared = { request, translated ->
        // A failed/cancelled/native-rejected result must never replace a saved translation.
        cache.write(request.appleMusicId, request.originalTtml, request.config, translated)
    },
)
