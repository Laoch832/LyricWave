package dev.amenhancer.module.ui

import android.app.Activity
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import dev.amenhancer.module.hook.LlmLyricsCacheUpdates
import dev.amenhancer.module.i18n.ModuleText
import dev.amenhancer.module.lyrics.FileLlmLyricsCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun EmbeddedSettingsHost.EmbeddedAiCacheLibrary(activity: Activity, query: String, active: Boolean) {
    val cache = remember { FileLlmLyricsCache(File(application.filesDir, FileLlmLyricsCache.DIRECTORY)) }
    val scope = rememberCoroutineScope()
    val currentActive by rememberUpdatedState(active)
    var songs by remember { mutableStateOf<List<FileLlmLyricsCache.CachedSong>?>(null) }
    var shown by remember(query) { mutableIntStateOf(20) }
    var opening by remember { mutableStateOf(false) }
    LaunchedEffect(cache, active) {
        var seen: Long? = null
        while (active) {
            val revision = cache.revision()
            if (revision != seen) {
                val loaded = withContext(Dispatchers.IO) { runCatching(cache::songs).getOrDefault(emptyList()) }
                songs = loaded
                seen = revision
            }
            delay(800)
        }
    }

    val cachedSongs = songs.orEmpty()
    val search = query.trim()
    val matches = remember(cachedSongs, search) {
        cachedSongs.filter { song ->
            search.isEmpty() || listOfNotNull(song.title, song.artist, song.appleMusicId.toString())
                .any { it.contains(search, ignoreCase = true) }
        }
    }
    EmbeddedSettingsSection(
        title = localizedText(ModuleText.AI_CACHE_LIBRARY),
        summary = localizedText(ModuleText.SONG_COUNT, cachedSongs.size),
        endAction = {
            EmbeddedSettingsInfo(localizedText(ModuleText.AI_CACHE_LIBRARY)) {
                Text(localizedText(ModuleText.AI_CACHE_LIBRARY_HELP))
            }
        },
    )
    EmbeddedSettingsGroup {
        if (matches.isEmpty()) {
            EmbeddedSettingsNotice(localizedText(when {
                songs == null -> ModuleText.AI_LOADING
                cachedSongs.isEmpty() -> ModuleText.AI_CACHE_EMPTY
                else -> ModuleText.NO_SEARCH_RESULTS
            }))
        }
        matches.take(shown).forEach { song ->
            val title = song.title ?: "Apple Music ID ${song.appleMusicId}"
            val details = buildList {
                song.artist?.let(::add)
                if (song.hasTranslation) add(localizedText(ModuleText.AI_CACHE_TRANSLATION))
                if (song.hasTransliteration) add(localizedText(ModuleText.AI_CACHE_TRANSLITERATION))
            }.joinToString(" · ")
            EmbeddedSettingLink(title, details.takeIf(String::isNotBlank), EmbeddedSvgIcon.Translation, enabled = !opening) {
                opening = true
                scope.launch {
                    try {
                        val previews = withContext(Dispatchers.IO) {
                            runCatching {
                                listOfNotNull(
                                    cache.preview(song.appleMusicId)?.let { ModuleText.AI_CACHE_TRANSLATION to it },
                                    cache.transliterationPreview(song.appleMusicId)?.let { ModuleText.AI_CACHE_TRANSLITERATION to it },
                                )
                            }.getOrDefault(emptyList())
                        }
                        if (!currentActive || activity.isFinishing || activity.isDestroyed) return@launch
                        showEmbeddedMiuixDialog(activity, title) { dismiss ->
                            if (previews.isEmpty()) Text(localizedText(ModuleText.AI_PREVIEW_EMPTY))
                            previews.forEach { (kind, content) ->
                                Text(localizedText(kind), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                Text(content)
                            }
                            EmbeddedDialogActions(localizedText(ModuleText.CLOSE), dismiss,
                                localizedText(ModuleText.AI_DELETE_CACHE)) {
                                dismiss()
                                showEmbeddedMiuixDialog(activity, localizedText(ModuleText.AI_DELETE_CACHE_CONFIRM)) { close ->
                                    EmbeddedDialogActions(localizedText(ModuleText.CANCEL), close, localizedText(ModuleText.DELETE)) {
                                        close()
                                        scope.launch {
                                            val removed = withContext(Dispatchers.IO) { runCatching { cache.delete(song.appleMusicId) }.getOrDefault(false) }
                                            if (removed) {
                                                songs = songs.orEmpty().filterNot { it.appleMusicId == song.appleMusicId }
                                                LlmLyricsCacheUpdates.publish(song.appleMusicId, refresh = false)
                                            } else {
                                                Toast.makeText(activity, localizedText(ModuleText.AI_STORAGE_ERROR), Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        opening = false
                    }
                }
            }
        }
        if (matches.size > shown) EmbeddedSettingLink(localizedText(ModuleText.LOAD_MORE)) { shown += 20 }
    }
}
