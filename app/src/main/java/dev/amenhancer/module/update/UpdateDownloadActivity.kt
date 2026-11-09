package dev.amenhancer.module.update

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import dev.amenhancer.module.BuildConfig
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.i18n.ModuleText
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

/** Runs under the module UID so APKs stay out of Apple Music's private storage. */
class UpdateDownloadActivity : Activity() {
    private lateinit var session: UpdateDownloadSession
    private lateinit var connections: android.widget.Spinner
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var start: Button
    private lateinit var official: Button
    private lateinit var install: Button
    private lateinit var cancel: Button
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val retained = lastNonConfigurationInstance as? UpdateDownloadSession
        session = retained ?: createSession(applicationContext, intent.getLongExtra("asset_id", -1), intent.getBooleanExtra("official", false))
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        // Insets keep controls outside the gesture/status bars when targeting edge-to-edge Android.
        layout.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(view.paddingLeft, (24 * resources.displayMetrics.density).toInt() + insets.systemWindowInsetTop,
                view.paddingRight, (24 * resources.displayMetrics.density).toInt() + insets.systemWindowInsetBottom)
            insets
        }
        layout.addView(TextView(this).apply { text = ModuleText.UPDATE_DOWNLOADER.text(); textSize = 22f })
        val preferences = getSharedPreferences("lyricwave_download_options", Context.MODE_PRIVATE)
        val choices = listOf(1, 4, 8, 16, 32, 64, 128)
        if (retained == null && intent.getIntExtra("connections", -1) in choices)
            preferences.edit().putInt("connections", intent.getIntExtra("connections", 8)).apply()
        layout.addView(TextView(this).apply { text = ModuleText.UPDATE_CONNECTIONS.text() })
        connections = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@UpdateDownloadActivity, android.R.layout.simple_spinner_dropdown_item, choices)
            setSelection(choices.indexOf(preferences.getInt("connections", 8)).coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    preferences.edit().putInt("connections", choices[position]).apply()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        }
        layout.addView(connections)
        status = TextView(this).apply { textSize = 16f; setTextIsSelectable(true) }; layout.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }; layout.addView(progress)
        start = Button(this).apply { text = ModuleText.UPDATE_START.text(); setOnClickListener { session.download(session.official) } }; layout.addView(start)
        official = Button(this).apply { text = ModuleText.UPDATE_GITHUB_DOWNLOAD.text(); setOnClickListener { session.download(true) } }; layout.addView(official)
        install = Button(this).apply { text = ModuleText.UPDATE_INSTALL.text(); setOnClickListener { installVerified() } }; layout.addView(install)
        cancel = Button(this).apply { text = ModuleText.CLOSE.text(); setOnClickListener { session.close(); finish() } }; layout.addView(cancel)
        setContentView(ScrollView(this).apply { addView(layout) })
        session.listener = ::render
        render()
        if (retained == null) session.prepare()
    }

    private fun render() {
        if (isFinishing || isDestroyed) return
        val state = session.state
        val running = state.phase == DownloadPhase.CHECKING || state.phase == DownloadPhase.DOWNLOADING
        connections.isEnabled = !running
        progress.isIndeterminate = state.phase == DownloadPhase.CHECKING
        progress.progress = if (state.total > 0) (state.bytes * 1000 / state.total).toInt().coerceIn(0, 1000) else 0
        status.text = when (state.phase) {
            DownloadPhase.CHECKING -> ModuleText.UPDATE_CHECKING.text()
            DownloadPhase.READY -> ModuleText.UPDATE_DOWNLOAD_READY.text(state.name)
            DownloadPhase.DOWNLOADING -> ModuleText.UPDATE_PROGRESS.text(
                "%.1f".format(state.bytes / 1048576.0), "%.1f".format(state.total / 1048576.0), state.connections)
            DownloadPhase.VERIFIED -> ModuleText.UPDATE_VERIFIED.text(state.name)
            DownloadPhase.FAILED -> ModuleText.UPDATE_DOWNLOAD_FAILED.text(state.reason)
        }
        start.visibility = if (!running && state.phase != DownloadPhase.VERIFIED) View.VISIBLE else View.GONE
        start.text = ModuleText.UPDATE_START.text()
        official.visibility = if (state.phase == DownloadPhase.FAILED) View.VISIBLE else View.GONE
        install.visibility = if (state.phase == DownloadPhase.VERIFIED) View.VISIBLE else View.GONE
        cancel.text = if (running) ModuleText.UPDATE_CANCEL.text() else ModuleText.CLOSE.text()
    }

    private fun installVerified() {
        val apk = session.state.file ?: return
        runCatching {
            session.verifyArchive(apk) // Recheck before every installation request.
            if (!packageManager.canRequestPackageInstalls()) {
                status.text = ModuleText.UPDATE_INSTALL_PERMISSION.text()
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.updates", apk)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newRawUri("LyricWave update", uri) })
        }.onFailure { status.text = ModuleText.UPDATE_DOWNLOAD_FAILED.text("installer") }
    }

    @Deprecated("Retains only the download session, never an Activity")
    override fun onRetainNonConfigurationInstance(): Any = session
    override fun onDestroy() {
        session.listener = null
        if (!isChangingConfigurations) session.close()
        super.onDestroy()
    }
    companion object {
        internal var createSession: (Context, Long, Boolean) -> UpdateDownloadSession = { context, id, official ->
            UpdateDownloadSession(context, id, official)
        }
    }
}

internal enum class DownloadPhase { CHECKING, READY, DOWNLOADING, VERIFIED, FAILED }
internal data class DownloadState(val phase: DownloadPhase = DownloadPhase.CHECKING, val name: String = "",
    val bytes: Long = 0, val total: Long = 0, val connections: Int = 1, val reason: String = "", val file: File? = null)

internal class UpdateDownloadSession(
    private val context: Context, private val assetId: Long, val official: Boolean,
    private val check: () -> AppUpdateRelease = { AppUpdateClient().check(BuildConfig.VERSION_CODE.toLong(), BuildConfig.APPLICATION_ID) },
    private val fetch: (UpdateAsset, UpdateBuild, File, UpdateDownloadCancellation, Boolean, (UpdateDownloadProgress) -> Unit) -> File =
        { asset, build, directory, cancellation, official, progress ->
            val count = context.getSharedPreferences("lyricwave_download_options", Context.MODE_PRIVATE).getInt("connections", 8).coerceIn(1, 128)
            AppUpdateDownloader(parallelism = count).download(asset, build, directory, cancellation, official, progress)
        },
    private val archiveCheck: ((File, UpdateBuild) -> Unit)? = null,
) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "LyricWave-download").apply { isDaemon = true } }
    private val handler = Handler(Looper.getMainLooper())
    private val cancellation = UpdateDownloadCancellation()
    private var release: AppUpdateRelease? = null
    private var asset: UpdateAsset? = null
    // A backup Activity may overlap the foreground service; never purge its files.
    private val directory = File(context.cacheDir, "updates/session-${java.util.UUID.randomUUID()}")
    private var busy = false
    @Volatile private var closed = false
    @Volatile var state = DownloadState(); private set
    var listener: (() -> Unit)? = null
    private fun publish(next: DownloadState) { handler.post {
        if (!closed && !(next.phase == DownloadPhase.DOWNLOADING && state.phase == DownloadPhase.DOWNLOADING &&
                next.bytes > 0 && next.bytes < state.bytes)) {
            state = next; listener?.invoke()
        }
    } }
    fun prepare() = runTask {
        publish(DownloadState())
        val latest = check()
        val build = latest.build ?: throw AppUpdateException("metadata")
        if (BuildConfig.APPLICATION_ID == ModuleConstants.MODULE_PACKAGE && build.versionCode <= BuildConfig.VERSION_CODE)
            throw AppUpdateException("already_current")
        val chosen = latest.apks.singleOrNull { it.id == assetId && it.name == build.apk } ?: throw AppUpdateException("asset")
        release = latest; asset = chosen
        publish(DownloadState(DownloadPhase.READY, chosen.name, total = chosen.size))
    }
    fun download(useOfficial: Boolean) {
        if (busy || closed) return
        if (release == null || asset == null) { prepare(); return }
        runTask {
            val chosen = requireNotNull(asset); val build = requireNotNull(release?.build)
            directory.mkdirs()
            directory.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
            var lastProgress = 0L
            publish(DownloadState(DownloadPhase.DOWNLOADING, chosen.name, total = chosen.size))
            val file = fetch(chosen, build, directory, cancellation, useOfficial) { current ->
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastProgress >= 150 || current.bytes == current.total || current.bytes == 0L) {
                    lastProgress = now
                    publish(DownloadState(DownloadPhase.DOWNLOADING, chosen.name, current.bytes, current.total, current.connections))
                }
            }
            try { verifyArchive(file) } catch (error: Exception) { file.delete(); throw error }
            publish(DownloadState(DownloadPhase.VERIFIED, chosen.name, chosen.size, chosen.size, file = file))
        }
    }
    fun verifyArchive(file: File) {
        val build = release?.build ?: throw AppUpdateException("metadata")
        archiveCheck?.let { it(file, build); return }
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(file.path,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
            ?: throw AppUpdateException("apk")
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        @Suppress("DEPRECATION")
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val signature = signatures?.singleOrNull() ?: throw AppUpdateException("signature")
        val certificate = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        if (info.packageName != ModuleConstants.MODULE_PACKAGE || code != build.versionCode || info.versionName != build.versionName ||
            certificate != AppUpdateClient.CERTIFICATE_SHA256) throw AppUpdateException("signature")
    }
    private fun runTask(action: () -> Unit) {
        if (busy || closed) return
        busy = true
        worker.execute {
            try { if (cancellation.cancelled) throw CancellationException(); action() }
            catch (_: CancellationException) { }
            catch (error: Exception) { publish(state.copy(phase = DownloadPhase.FAILED,
                reason = (error as? AppUpdateException)?.let { it.reason + (it.httpStatus?.let { code -> " ($code)" } ?: "") } ?: "network")) }
            finally { handler.post { busy = false; if (!closed) listener?.invoke() } }
        }
    }
    fun close() { closed = true; cancellation.cancel(); worker.shutdownNow(); listener = null }
}
