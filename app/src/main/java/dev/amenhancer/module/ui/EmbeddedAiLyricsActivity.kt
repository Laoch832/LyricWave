package dev.amenhancer.module.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.amenhancer.module.hook.LlmLyricsCacheUpdates
import dev.amenhancer.module.hook.LlmLyricsRequest
import dev.amenhancer.module.hook.LlmLyricsSourceState
import dev.amenhancer.module.hook.createCachedLlmLyricsRuntime
import dev.amenhancer.module.i18n.ModuleText
import dev.amenhancer.module.lyrics.FileLlmLyricsActivityStore
import dev.amenhancer.module.lyrics.FileLlmLyricsCache
import dev.amenhancer.module.lyrics.LlmActivityKind
import dev.amenhancer.module.lyrics.LlmActivityRecord
import dev.amenhancer.module.lyrics.LlmActivitySnapshot
import dev.amenhancer.module.lyrics.LlmActivityStatus
import dev.amenhancer.module.lyrics.LlmLyricsConfig
import dev.amenhancer.module.model.ModuleSettings
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val aiStatsWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "lyricwave-ai-statistics").apply { isDaemon = true } }
private val aiManualRunning = AtomicBoolean(false)
private val aiTranslateWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "lyricwave-ai-manual-translation").apply { isDaemon = true } }

internal fun EmbeddedSettingsHost.aiActivityStore() =
    FileLlmLyricsActivityStore(File(application.filesDir, FileLlmLyricsActivityStore.DIRECTORY))

internal fun EmbeddedSettingsHost.addAiLyricsSummary(activity: Activity, parent: LinearLayout, onOpen: () -> Unit) {
    addAiActivityPanel(activity, parent, compact = true, onOpen = onOpen)
}

internal fun EmbeddedSettingsHost.renderEmbeddedAiLyricsActivityPage(activity: Activity, parent: LinearLayout, settings: ModuleSettings) {
    addAiActivityPanel(activity, parent, compact = false, settings = settings)
}

/** Lifecycle-bound polling; all journal/cache reads and API calls run away from the UI thread. */
private fun EmbeddedSettingsHost.addAiActivityPanel(
    activity: Activity, parent: LinearLayout, compact: Boolean, onOpen: (() -> Unit)? = null, settings: ModuleSettings? = null,
) {
    val journal = aiActivityStore()
    val cache = FileLlmLyricsCache(File(application.filesDir, FileLlmLyricsCache.DIRECTORY))
    val main = Handler(Looper.getMainLooper())
    val loading = AtomicBoolean(false)
    val translating = aiManualRunning
    var lastBusy = translating.get()
    val cancelled = AtomicBoolean(false)
    var listedEpoch = -1L
    var listedSongs = emptyList<FileLlmLyricsCache.CachedSong>()
    var last: Pair<LlmActivitySnapshot, List<FileLlmLyricsCache.CachedSong>>? = null
    val card = embeddedCard(activity, localizedText(ModuleText.AI_ACTIVITY_TITLE)) {}
    val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    card.addView(body, matchWidthWrapContent())
    parent.addView(card, matchWidthWrapContent())
    if (compact) {
        card.isClickable = true
        card.setOnClickListener { onOpen?.invoke() }
    }
    fun text(value: String, heading: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = if (heading) 16f else 13f
        setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8))
        setEmbeddedTextColor(activity) { colors -> if (heading) colors.onSurface else colors.onSurfaceVariant }
    }
    body.addView(text(localizedText(ModuleText.AI_LOADING)))
    fun alive() = !cancelled.get() && card.isAttachedToWindow && !activity.isFinishing && !activity.isDestroyed
    fun postMessage(key: ModuleText) { main.post { if (alive()) Toast.makeText(activity, localizedText(key), Toast.LENGTH_LONG).show() } }
    fun stamp(value: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
    fun unknown(value: Any?) = value?.toString() ?: "—"
    fun action(label: String, enabled: Boolean = true, click: () -> Unit) {
        body.addView(embeddedActionButton(activity, label, onClick = click).apply {
            isEnabled = enabled; alpha = if (enabled) 1f else 0.55f
        }, matchWidthWrapContent())
    }
    fun translate(id: Long, title: String?, artist: String?, configured: Boolean) {
        if (!translating.compareAndSet(false, true)) return
        last = null
        postMessage(ModuleText.AI_TRANSLATING)
        aiTranslateWorker.execute {
            try {
                val settings = controller.currentSettings()
                val config = LlmLyricsConfig.fromSettings(settings)
                if (!settings.customLyricsEnabled || !settings.llmLyricsEnabled || !config.isConfigured) {
                    postMessage(ModuleText.AI_REQUIRES_CONFIG); return@execute
                }
                val observed = LlmLyricsSourceState.current?.takeIf { it.appleMusicId == id }
                val raw = if (configured) controller.readLyrics(id) ?: observed?.ttml
                    else observed?.ttml ?: controller.readLyrics(id)
                if (raw == null) { postMessage(ModuleText.AI_SOURCE_UNAVAILABLE); return@execute }
                val runtime = createCachedLlmLyricsRuntime(cache, { config }, Executor { it.run() }, activity = journal)
                val request = LlmLyricsRequest(id, raw, config, title ?: observed?.title, artist ?: observed?.artist)
                val result = runtime.translate(request) { Thread.currentThread().isInterrupted }
                val saved = result != null && cache.read(id, raw, config) != null
                if (saved) LlmLyricsCacheUpdates.publish(id)
                postMessage(if (saved) ModuleText.AI_SAVED else ModuleText.AI_NOT_SAVED)
            } catch (_: Exception) { postMessage(ModuleText.AI_STORAGE_ERROR) }
            finally { translating.set(false); main.post { last = null } }
        }
    }
    fun showCache(song: FileLlmLyricsCache.CachedSong) {
        aiStatsWorker.execute {
            val preview = runCatching { cache.preview(song.appleMusicId) }.getOrNull()
            main.post {
                if (!alive()) return@post
                embeddedDialogBuilder(activity).setTitle(song.title ?: "Apple Music ID ${song.appleMusicId}")
                    .setMessage(preview ?: localizedText(ModuleText.AI_PREVIEW_EMPTY))
                    .setPositiveButton(localizedText(ModuleText.CLOSE), null)
                    .setNeutralButton(localizedText(ModuleText.AI_DELETE_CACHE)) { _, _ ->
                        embeddedDialogBuilder(activity).setMessage(localizedText(ModuleText.AI_DELETE_CACHE_CONFIRM))
                            .setNegativeButton(localizedText(ModuleText.CANCEL), null)
                            .setPositiveButton(localizedText(ModuleText.DELETE)) { _, _ ->
                                aiStatsWorker.execute {
                                    val deleted = runCatching { cache.delete(song.appleMusicId) }.getOrDefault(false)
                                    if (deleted) LlmLyricsCacheUpdates.publish(song.appleMusicId, refresh = false)
                                    else postMessage(ModuleText.AI_STORAGE_ERROR)
                                    main.post { last = null }
                                }
                            }.show()
                    }.show()
            }
        }
    }
    fun render(snapshot: LlmActivitySnapshot, songs: List<FileLlmLyricsCache.CachedSong>) {
        body.removeAllViews()
        body.addView(text(localizedText(ModuleText.AI_STAT_CALLS, snapshot.apiCalls, snapshot.successful,
            snapshot.failed, snapshot.cancelled, snapshot.running)))
        body.addView(text(localizedText(ModuleText.AI_STAT_CACHE, songs.size, snapshot.cacheHits, snapshot.cacheSaves)))
        if (!compact) body.addView(text(localizedText(ModuleText.AI_STAT_USAGE, snapshot.reportedTokens, snapshot.callsWithoutUsage)))
        snapshot.records.lastOrNull()?.let { body.addView(text(localizedText(ModuleText.AI_LAST_STATUS, aiActivityLabel(it)))) }
        if (compact) return
        val busy = translating.get()
        val ready = settings != null && settings.customLyricsEnabled && settings.llmLyricsEnabled && LlmLyricsConfig.fromSettings(settings).isConfigured
        if (!ready) body.addView(text(localizedText(ModuleText.AI_REQUIRES_CONFIG)))
        body.addView(text(localizedText(ModuleText.AI_CACHE_RULE)))
        action(localizedText(if (busy) ModuleText.AI_TRANSLATING else ModuleText.AI_TRANSLATE_CURRENT), !busy && ready) {
            val song = controller.currentSongDetails()
            val id = song?.appleMusicId
            if (id == null || id <= 0L) postMessage(ModuleText.AI_SOURCE_UNAVAILABLE)
            else translate(id, song.title, song.artist, configured = false)
        }
        action(localizedText(ModuleText.AI_TRANSLATE_PICK), !busy && ready) {
            aiStatsWorker.execute {
                val entries = runCatching { controller.lyricsEntries().filter { it.enabled } }.getOrDefault(emptyList())
                main.post {
                    if (!alive()) return@post
                    if (entries.isEmpty()) { postMessage(ModuleText.AI_SOURCE_UNAVAILABLE); return@post }
                    embeddedDialogBuilder(activity).setTitle(localizedText(ModuleText.AI_TRANSLATE_PICK))
                        .setItems(entries.map { "${it.displayName} · ${it.appleMusicId}" }.toTypedArray()) { _, index ->
                            val entry = entries[index]
                            translate(entry.appleMusicId, entry.displayName, null, configured = true)
                        }.setNegativeButton(localizedText(ModuleText.CANCEL), null).show()
                }
            }
        }
        body.addView(text(localizedText(ModuleText.AI_CACHE_TITLE), heading = true))
        if (songs.isEmpty()) body.addView(text(localizedText(ModuleText.AI_CACHE_EMPTY)))
        songs.take(20).forEach { song ->
            body.addView(embeddedNavigationRow(activity, song.title ?: "Apple Music ID ${song.appleMusicId}",
                localizedText(ModuleText.AI_CACHE_INFO, song.appleMusicId, song.model, stamp(song.savedAt), (song.bytes + 1023) / 1024),
                 onClick = { showCache(song) }))
        }
        if (songs.size > 20) action("${localizedText(ModuleText.AI_CACHE_TITLE)} (${songs.size})") {
            embeddedDialogBuilder(activity).setTitle(localizedText(ModuleText.AI_CACHE_TITLE))
                .setItems(songs.map { "${it.title ?: it.appleMusicId} · ${it.model}" }.toTypedArray()) { _, i -> showCache(songs[i]) }
                .setNegativeButton(localizedText(ModuleText.CLOSE), null).show()
        }
        body.addView(text(localizedText(ModuleText.AI_HISTORY), heading = true))
        val calls = snapshot.records.filter { it.isApiCall }.takeLast(20).asReversed()
        if (calls.isEmpty()) body.addView(text(localizedText(ModuleText.AI_NO_REQUESTS)))
        calls.forEach { record ->
            body.addView(text(listOfNotNull(if (record.kind == LlmActivityKind.TRANSLATION)
                localizedText(ModuleText.AI_TRANSLATION_CALL) else localizedText(ModuleText.AI_TEST_CALL),
                record.title ?: record.songId?.let { "Apple Music ID $it" }, aiActivityLabel(record)).joinToString(" · "), heading = true))
            body.addView(text(localizedText(ModuleText.AI_RECORD_INFO, stamp(record.startedAt), unknown(record.elapsedMs),
                unknown(record.httpStatus), record.model, record.host, unknown(record.promptTokens),
                unknown(record.completionTokens), unknown(record.totalTokens))))
            record.reason?.let { body.addView(text(aiActivityReason(it, record.httpStatus))) }
        }
        body.addView(text(localizedText(ModuleText.AI_DIAGNOSTICS), heading = true))
        snapshot.records.filterNot { it.isApiCall }.takeLast(10).asReversed().forEach { record ->
            body.addView(text("${stamp(record.startedAt)} · ${record.title ?: record.songId ?: "—"}\n${aiActivityLabel(record)}"))
        }
        action(localizedText(ModuleText.AI_CLEAR_LOG)) {
            embeddedDialogBuilder(activity).setMessage(localizedText(ModuleText.AI_CLEAR_LOG_CONFIRM))
                .setNegativeButton(localizedText(ModuleText.CANCEL), null)
                .setPositiveButton(localizedText(ModuleText.DELETE)) { _, _ ->
                    aiStatsWorker.execute {
                        runCatching { journal.clearCompleted() }.onFailure { postMessage(ModuleText.AI_STORAGE_ERROR) }
                        main.post { last = null }
                    }
                }.show()
        }
        body.addView(text(localizedText(ModuleText.AI_LOG_LOCAL_NOTICE)))
    }
    val poll = object : Runnable {
        override fun run() {
            if (!alive()) return
            if (loading.compareAndSet(false, true)) aiStatsWorker.execute {
                val result = runCatching {
                    val epoch = cache.revision()
                    if (listedEpoch != epoch) { listedSongs = cache.songs(); listedEpoch = epoch }
                    journal.snapshot() to listedSongs
                }
                main.post {
                    loading.set(false)
                    if (!alive()) return@post
                    result.onSuccess { data ->
                        if (last != data || lastBusy != translating.get()) {
                            last = data; lastBusy = translating.get(); render(data.first, data.second)
                        }
                    }.onFailure {
                        body.removeAllViews()
                        body.addView(text(localizedText(ModuleText.AI_STORAGE_ERROR)))
                        if (!compact) action(localizedText(ModuleText.AI_CLEAR_LOG)) {
                            embeddedDialogBuilder(activity).setMessage(localizedText(ModuleText.AI_CLEAR_LOG_CONFIRM))
                                .setNegativeButton(localizedText(ModuleText.CANCEL), null)
                                .setPositiveButton(localizedText(ModuleText.DELETE)) { _, _ ->
                                    aiStatsWorker.execute {
                                        runCatching { journal.clearCompleted() }
                                        main.post { last = null }
                                    }
                                }.show()
                        }
                    }
                }
            }
            main.postDelayed(this, 2000)
        }
    }
    card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) { cancelled.set(false); main.post(poll) }
        override fun onViewDetachedFromWindow(view: View) { cancelled.set(true); main.removeCallbacks(poll) }
    })
    if (card.isAttachedToWindow) main.post(poll)
}

private fun EmbeddedSettingsHost.aiActivityLabel(record: LlmActivityRecord): String = localizedText(when (record.kind) {
    LlmActivityKind.CACHE_HIT -> ModuleText.AI_CACHE_HIT
    LlmActivityKind.CACHE_SAVED -> ModuleText.AI_CACHE_SAVED
    LlmActivityKind.CACHE_SAVE_FAILED -> ModuleText.AI_CACHE_SAVE_FAILED
    LlmActivityKind.SOURCE_CHANGED -> ModuleText.AI_SOURCE_CHANGED
    LlmActivityKind.SETTINGS_CHANGED -> ModuleText.AI_SETTINGS_CHANGED
    LlmActivityKind.SKIPPED -> ModuleText.AI_INELIGIBLE
    LlmActivityKind.PLAYER_REJECTED -> ModuleText.AI_PLAYER_REJECTED
    else -> when (record.status) {
        LlmActivityStatus.RUNNING -> ModuleText.AI_RECORD_RUNNING
        LlmActivityStatus.SUCCESS -> ModuleText.AI_RECORD_SUCCESS
        LlmActivityStatus.FAILED -> ModuleText.AI_RECORD_FAILED
        LlmActivityStatus.CANCELLED -> ModuleText.AI_RECORD_CANCELLED
    }
})

private fun EmbeddedSettingsHost.aiActivityReason(reason: String, httpStatus: Int?): String = when (reason) {
    "http_status" -> when (httpStatus) {
        401, 403 -> localizedText(ModuleText.LLM_LYRICS_TEST_AUTH_FAILED, httpStatus)
        404 -> localizedText(ModuleText.LLM_LYRICS_TEST_NOT_FOUND)
        429 -> localizedText(ModuleText.LLM_LYRICS_TEST_RATE_LIMITED)
        else -> localizedText(ModuleText.LLM_LYRICS_TEST_HTTP_FAILED, httpStatus ?: "—")
    }
    "timeout" -> localizedText(ModuleText.LLM_LYRICS_TEST_TIMEOUT)
    "dns" -> localizedText(ModuleText.LLM_LYRICS_TEST_DNS_FAILED)
    "tls" -> localizedText(ModuleText.LLM_LYRICS_TEST_TLS_FAILED)
    "invalid_response", "response_limit" -> localizedText(ModuleText.LLM_LYRICS_TEST_INVALID_RESPONSE)
    "cancelled", "interrupted" -> localizedText(ModuleText.AI_RECORD_CANCELLED)
    else -> localizedText(ModuleText.LLM_LYRICS_TEST_NETWORK_FAILED)
}
