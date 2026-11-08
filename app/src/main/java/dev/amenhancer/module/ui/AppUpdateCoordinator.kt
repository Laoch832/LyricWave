package dev.amenhancer.module.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.amenhancer.module.BuildConfig
import dev.amenhancer.module.i18n.ModuleText
import dev.amenhancer.module.update.*
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal data class AppUpdateSnapshot(val release: AppUpdateRelease? = null, val checking: Boolean = false,
    val completed: Long = 0, val failureHttp: Int? = null, val failed: Boolean = false)

/** Per host-process coordinator: one check in flight, private bounded cache, foreground-only triggers. */
internal object AppUpdateCoordinator {
    private val mutable = MutableStateFlow(AppUpdateSnapshot())
    val state = mutable.asStateFlow()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "LyricWave-updates").apply { isDaemon = true } }
    private var inFlight = false
    private var loaded = false
    fun foreground(context: Context, enabled: Boolean) { if (enabled) request(context, false) }
    @Synchronized fun request(context: Context, manual: Boolean) {
        if (inFlight) return
        val app = context.applicationContext
        inFlight = true
        worker.execute {
            val preferences = app.getSharedPreferences("lyricwave_update_cache_v1", Context.MODE_PRIVATE)
            try {
                if (!loaded) {
                    loaded = true
                    val saved = preferences.getString("responses", null)
                    if (saved != null && saved.length <= AppUpdateClient.MAX_VERSION_BYTES + 2 * AppUpdateClient.MAX_METADATA_BYTES + 4096) {
                        runCatching {
                            val responses = JSONObject(saved)
                            val cached = AppUpdateClient(read = { url, max ->
                                responses.getString(url).toByteArray(Charsets.UTF_8).also {
                                    if (it.size > max) throw AppUpdateException("size")
                                }
                            }, clock = { preferences.getLong("success", 0) })
                                .check(BuildConfig.VERSION_CODE.toLong(), BuildConfig.APPLICATION_ID)
                            mutable.value = mutable.value.copy(release = cached)
                        }
                    }
                }
                val now = System.currentTimeMillis()
                if (!manual && !UpdateCheckSchedule.due(now, preferences.getLong("attempt", 0), preferences.getLong("success", 0)))
                    return@execute
                preferences.edit().putLong("attempt", now).apply()
                mutable.value = mutable.value.copy(checking = true, failed = false, failureHttp = null)
                val captured = JSONObject()
                val transport = AppUpdateHttpTransport()
                val result = runCatching { AppUpdateClient(read = { url, max ->
                    transport.get(url, max).also { captured.put(url, it.toString(Charsets.UTF_8)) }
                }, clock = { now }).check(BuildConfig.VERSION_CODE.toLong(), BuildConfig.APPLICATION_ID) }
                result.onSuccess { release ->
                    preferences.edit().putString("responses", captured.toString()).putLong("success", now).apply()
                    mutable.value = AppUpdateSnapshot(release, completed = mutable.value.completed + 1)
                    if (!manual && release.comparison == UpdateComparison.NEWER && preferences.getString("noticed", "") != release.tag) {
                        preferences.edit().putString("noticed", release.tag).apply()
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(app, ModuleText.UPDATE_AUTO_NOTICE.text(release.tag), Toast.LENGTH_LONG).show()
                        }
                    }
                }.onFailure { error ->
                    mutable.value = mutable.value.copy(checking = false, completed = mutable.value.completed + 1,
                        failed = true, failureHttp = (error as? AppUpdateException)?.httpStatus)
                }
            } finally { synchronized(this) { inFlight = false } }
        }
    }
}
