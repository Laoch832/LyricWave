package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.FileLlmLyricsCache
import dev.amenhancer.module.lyrics.FileLlmLyricsActivityStore
import dev.amenhancer.module.lyrics.LlmActivityKind
import dev.amenhancer.module.lyrics.LlmLyricsClient
import dev.amenhancer.module.lyrics.LlmLyricsConfig
import dev.amenhancer.module.lyrics.LlmLyricsDocument
import dev.amenhancer.module.lyrics.LlmLyricsPhase
import dev.amenhancer.module.lyrics.LlmLyricsStatusSnapshot
import java.util.concurrent.Executor

/** Cache valid completed translations independently from the lifetime of a native player pointer. */
fun createCachedLlmLyricsRuntime(
    cache: FileLlmLyricsCache,
    configuration: () -> LlmLyricsConfig?,
    executor: Executor,
    close: () -> Unit = {},
    client: LlmLyricsClient = LlmLyricsClient(),
    activity: FileLlmLyricsActivityStore? = null,
    translationEnabled: () -> Boolean = { true },
    transliterationEnabled: () -> Boolean = { false },
    transliterate: ((LlmLyricsRequest, () -> Boolean) -> String?)? = null,
    onTransliterationPrepared: (LlmLyricsRequest, String) -> Unit = { _, _ -> },
    discardPending: () -> Unit = {},
    onStatusChanged: (LlmLyricsStatusSnapshot) -> Unit = {},
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
            val metadata = TtmlTimingPolicy.metadataOf(request.originalTtml)
            request.reportStatus(when {
                metadata.hasTranslation -> LlmLyricsPhase.EXISTS
                metadata.language != null && !metadata.isForeign -> LlmLyricsPhase.NOT_NEEDED
                else -> LlmLyricsPhase.UNSUPPORTED
            })
            return@translation null
        }
        if (isCancelled()) return@translation null
        val lookup = cache.lookup(request.appleMusicId, request.originalTtml, request.config)
        lookup.ttml?.let { cached ->
            if (isCancelled()) return@translation null
            event(LlmActivityKind.CACHE_HIT)
            request.reportStatus(LlmLyricsPhase.CACHED)
            return@translation cached
        }
        if (lookup.state == FileLlmLyricsCache.LookupState.SOURCE_CHANGED) event(LlmActivityKind.SOURCE_CHANGED)
        if (lookup.state == FileLlmLyricsCache.LookupState.SETTINGS_CHANGED) event(LlmActivityKind.SETTINGS_CHANGED)
        request.reportStatus(LlmLyricsPhase.GENERATING)
        var callId: String? = null
        val translations = client.translate(
            config = request.config, lines = document.translationLines,
            title = request.title, artist = request.artist, sourceLanguage = document.sourceLanguage,
            onStarted = { callId = activity?.begin(LlmActivityKind.TRANSLATION, request.config,
                request.appleMusicId, request.title, request.artist) },
            onCompleted = { result -> activity?.complete(callId, result) },
            onFailure = { reason, status -> request.reportStatus(llmFailurePhase(reason, status)) },
            isCancelled = isCancelled,
        ) ?: return@translation null
        if (isCancelled()) return@translation null
        val translated = document.withTranslations(translations) ?: run {
            request.reportStatus(LlmLyricsPhase.INVALID_RESPONSE)
            return@translation null
        }
        if (isCancelled()) return@translation null
        // Saving must not depend on a host pointer surviving a settings page or playback visit.
        val saved = cache.write(request.appleMusicId, request.originalTtml, request.config, translated, request.title, request.artist)
        event(if (saved) LlmActivityKind.CACHE_SAVED else LlmActivityKind.CACHE_SAVE_FAILED)
        translated.takeUnless { isCancelled() }
      }
    },
    translationEnabled = translationEnabled,
    transliterationEnabled = transliterationEnabled,
    transliterate = transliterate,
    onTransliterationPrepared = onTransliterationPrepared,
    discardPending = discardPending,
    onStatusChanged = onStatusChanged,
    executor = executor,
    close = close,
    onRejected = { request ->
        runCatching { activity?.event(LlmActivityKind.PLAYER_REJECTED, request.config,
            request.appleMusicId, request.title, request.artist, "player_rejected") }
    },
)

fun llmFailurePhase(reason: String, status: Int?): LlmLyricsPhase = when {
    status == 401 || status == 403 -> LlmLyricsPhase.AUTH_FAILED
    status == 429 -> LlmLyricsPhase.RATE_LIMITED
    reason == "timeout" -> LlmLyricsPhase.TIMED_OUT
    reason == "invalid_response" || reason == "response_limit" -> LlmLyricsPhase.INVALID_RESPONSE
    else -> LlmLyricsPhase.REQUEST_FAILED
}
