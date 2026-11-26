package dev.amenhancer.module.update

import android.app.Activity
import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import dev.amenhancer.module.i18n.ModuleText
import java.io.File
import java.util.concurrent.Executors

/** User entry from a completed transfer, independent of the host and the downloader service. */
class UpdateInstallActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    internal var verifyArchive: (File, UpdateBuild) -> Unit = { apk, build -> UpdateApkVerifier.verify(this, apk, build) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val asset = intent.getLongExtra(EXTRA_ASSET, -1)
        UpdateTransfer.restore(this)
        val ready = asset > 0 && asset == UpdateTransfer.assetId && UpdateTransfer.state.phase == DownloadPhase.VERIFIED
        val apk = UpdateTransfer.file.takeIf { ready }
        val build = UpdateTransfer.build.takeIf { ready }
        val status = TextView(this).apply {
            text = if (apk != null && build != null) ModuleText.UPDATE_READY_TO_INSTALL.text()
                else ModuleText.UPDATE_DOWNLOAD_FAILED.text("asset")
        }
        val install = Button(this).apply {
            text = ModuleText.UPDATE_INSTALL.text()
            visibility = if (apk != null && build != null) View.VISIBLE else View.GONE
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(padding, padding + insets.systemWindowInsetTop, padding, padding + insets.systemWindowInsetBottom)
                insets
            }
            if (build != null) addView(TextView(this@UpdateInstallActivity).apply { text = "LyricWave ${build.versionName}"; textSize = 22f })
            addView(status)
            addView(install)
            addView(Button(this@UpdateInstallActivity).apply {
                text = ModuleText.CLOSE.text()
                setOnClickListener { finish() }
            })
        })
        if (apk != null && build != null) install.setOnClickListener {
            install.isEnabled = false
            status.text = ModuleText.UPDATE_CHECKING.text()
            worker.execute {
                val verified = runCatching { verifyArchive(apk, build) }
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    if (asset != UpdateTransfer.assetId || UpdateTransfer.file != apk ||
                        UpdateTransfer.build != build || UpdateTransfer.state.phase != DownloadPhase.VERIFIED
                    ) {
                        install.visibility = View.GONE
                        status.text = ModuleText.UPDATE_DOWNLOAD_FAILED.text("asset")
                        return@runOnUiThread
                    }
                    install.isEnabled = true
                    if (verified.isFailure) {
                        status.text = ModuleText.UPDATE_DOWNLOAD_FAILED.text(
                            (verified.exceptionOrNull() as? AppUpdateException)?.reason ?: "installer",
                        )
                        return@runOnUiThread
                    }
                    runCatching {
                        val action = if (!packageManager.canRequestPackageInstalls()) {
                            status.text = ModuleText.UPDATE_INSTALL_PERMISSION.text()
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                        } else {
                            status.text = ModuleText.UPDATE_READY_TO_INSTALL.text()
                            val uri = FileProvider.getUriForFile(this, "$packageName.updates", apk)
                            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                .apply { clipData = ClipData.newRawUri("LyricWave update", uri) }
                        }
                        startActivity(action)
                    }.onFailure { status.text = ModuleText.UPDATE_DOWNLOAD_FAILED.text("installer") }
                }
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        internal const val EXTRA_ASSET = "asset_id"
        internal fun pendingIntent(context: Context, asset: Long): PendingIntent {
            val intent = Intent(context, UpdateInstallActivity::class.java)
                .setData(Uri.parse("lyricwave-update:/$asset"))
                .putExtra(EXTRA_ASSET, asset)
            return PendingIntent.getActivity(context, 7102, intent, PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
