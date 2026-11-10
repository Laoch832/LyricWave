package dev.amenhancer.module.status

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.amenhancer.module.BuildConfig
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.R
import dev.amenhancer.module.i18n.ModuleText

/** A small launcher dashboard: framework evidence, never root-file or installed-package guesses. */
class ModuleStatusActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var unsubscribe: (() -> Unit)? = null
    private lateinit var hero: LinearLayout
    private lateinit var mark: ImageView
    private lateinit var state: TextView
    private lateinit var detail: TextView
    private lateinit var framework: TextView
    private lateinit var host: TextView
    private lateinit var loaded: TextView
    private lateinit var scope: TextView
    private lateinit var refresh: Button
    private lateinit var open: Button
    private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val ink get() = Color.parseColor(if (dark) "#F0EDF8" else "#24222B")
    private val muted get() = Color.parseColor(if (dark) "#B8B2C5" else "#6C6578")
    private val surface get() = Color.parseColor(if (dark) "#20202B" else "#FFFFFF")
    private val accent get() = Color.parseColor(if (dark) "#B4C5FF" else "#2556A3")
    internal var monitorOverride: ActivationMonitor? = null
    private val monitor get() = monitorOverride ?: ModuleActivation.monitor
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun shape(color: Int, radius: Int = 24) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val backgroundColor = Color.parseColor(if (dark) "#0D0E12" else "#ECE9FC")
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(124))
        }
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) })
        heading.addView(text(ModuleText.STATUS_HOME.text(), 35f))
        page.addView(heading, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(22) })
        hero = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        mark = ImageView(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        hero.addView(mark, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(14) })
        val statusContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        state = text(ModuleText.STATUS_CHECKING.text(), 20f, bold = true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        detail = text("", 14f, muted)
        statusContent.addView(state); statusContent.addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        hero.addView(statusContent, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(hero)
        fun section(title: ModuleText): LinearLayout {
            page.addView(text(title.text(), 14f, accent), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(26); bottomMargin = dp(10); marginStart = dp(8)
            })
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; background = shape(surface, 22); clipToOutline = true
                page.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }
        fun row(group: LinearLayout, label: ModuleText, icon: Int): TextView {
            if (group.childCount > 0) group.addView(View(this).apply { setBackgroundColor(backgroundColor) }, LinearLayout.LayoutParams(-1, dp(1)))
            val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(14)) }
            line.addView(ImageView(this).apply {
                setImageResource(icon); setColorFilter(ink); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) })
            val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            content.addView(text(label.text(), 16f))
            val value = text(ModuleText.STATUS_UNAVAILABLE.text(), 14f, muted).apply { setTextIsSelectable(true) }
            content.addView(value, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            line.addView(content, LinearLayout.LayoutParams(0, -2, 1f)); group.addView(line)
            return value
        }
        val versions = section(ModuleText.STATUS_VERSION_INFO)
        row(versions, ModuleText.STATUS_MODULE_VERSION, R.drawable.ic_status_version).text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        host = row(versions, ModuleText.STATUS_HOST, R.drawable.ic_status_host)
        framework = row(versions, ModuleText.STATUS_FRAMEWORK, R.drawable.ic_status_framework)
        val runtime = section(ModuleText.STATUS_RUNTIME_INFO)
        scope = row(runtime, ModuleText.STATUS_SCOPE, R.drawable.ic_status_verified)
        loaded = row(runtime, ModuleText.STATUS_HOST_LOAD, R.drawable.ic_status_version)
        page.addView(text(ModuleText.STATUS_FOOTNOTE.text(), 12f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20); marginStart = dp(8); marginEnd = dp(8) })
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(page) }
        val root = android.widget.FrameLayout(this).apply { setBackgroundColor(backgroundColor); addView(scroll, android.widget.FrameLayout.LayoutParams(-1, -1)) }
        val actions = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(7), dp(7), dp(7), dp(7)); elevation = dp(8).toFloat()
            background = shape(Color.parseColor(if (dark) "#2A2B35" else "#F8F6FF"), 36).apply {
                setStroke(dp(1), Color.parseColor(if (dark) "#4B4A5C" else "#FFFFFF"))
            }
        }
        open = Button(this).apply {
            text = ModuleText.STATUS_OPEN_HOST.text(); isAllCaps = false; textSize = 14f; maxLines = 2; minHeight = dp(50)
            setPadding(dp(12), dp(12), dp(12), dp(12)); setTextColor(accent)
            background = shape(Color.parseColor(if (dark) "#34426C" else "#DCE3FA"), 30)
            setOnClickListener { openHost() }
        }
        refresh = Button(this).apply {
            text = ModuleText.STATUS_REFRESH.text(); isAllCaps = false; textSize = 14f; maxLines = 2; minHeight = dp(50)
            setPadding(dp(12), dp(12), dp(12), dp(12)); setTextColor(ink)
            background = shape(Color.TRANSPARENT, 30); setOnClickListener { monitor.refresh() }
        }
        actions.addView(open, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        actions.addView(refresh, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(dp(24), 0, dp(24), dp(16))
        })
        actions.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val bottom = actions.height + dp(42)
            if (page.paddingBottom != bottom) page.setPadding(dp(20), dp(28), dp(20), bottom)
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root)
        render(ActivationEvidence(checking = true))
    }
    private val poll = object : Runnable {
        override fun run() { monitor.refresh(silent = true); main.postDelayed(this, 3000) }
    }
    override fun onStart() {
        super.onStart()
        unsubscribe = monitor.subscribe(::render)
        monitor.refresh(); main.postDelayed(poll, 3000)
    }
    override fun onStop() {
        main.removeCallbacks(poll); unsubscribe?.invoke(); unsubscribe = null
        super.onStop()
    }
    private fun hostInfo() = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(ModuleConstants.TARGET_PACKAGE, 0)
    }.getOrNull()
    private fun render(evidence: ActivationEvidence) {
        val installed = hostInfo()
        val hostUid = installed?.applicationInfo?.uid
        val status = ActivationPolicy.evaluate(evidence, BuildConfig.VERSION_CODE.toLong(), hostUid)
        val pair = when (status) {
            ActivationState.ACTIVE -> ModuleText.STATUS_ACTIVE to ModuleText.STATUS_ACTIVE_DETAIL
            ActivationState.DISCONNECTED -> ModuleText.STATUS_DISCONNECTED to ModuleText.STATUS_DISCONNECTED_DETAIL
            ActivationState.UNVERIFIED -> ModuleText.STATUS_UNKNOWN to ModuleText.STATUS_UNKNOWN_DETAIL
            ActivationState.FRAMEWORK_OLD -> ModuleText.STATUS_FRAMEWORK_OLD to ModuleText.STATUS_FRAMEWORK_OLD_DETAIL
            ActivationState.SCOPE_MISSING -> ModuleText.STATUS_SCOPE_MISSING to ModuleText.STATUS_SCOPE_DETAIL
            ActivationState.WAITING_HOST -> ModuleText.STATUS_WAITING to ModuleText.STATUS_WAITING_DETAIL
            ActivationState.RESTART_REQUIRED -> ModuleText.STATUS_RESTART to ModuleText.STATUS_RESTART_DETAIL
            ActivationState.RELOADING -> ModuleText.STATUS_RELOADING to ModuleText.STATUS_RELOADING_DETAIL
            ActivationState.LOAD_FAILED -> ModuleText.STATUS_LOAD_FAILED to ModuleText.STATUS_LOAD_FAILED_DETAIL
            ActivationState.CHECKING -> ModuleText.STATUS_CHECKING to ModuleText.STATUS_WAITING_DETAIL
        }
        val title = pair.first.text()
        val description = when (status) {
            ActivationState.CHECKING -> ""
            ActivationState.ACTIVE -> "LyricWave ${BuildConfig.VERSION_NAME}"
            else -> pair.second.text()
        }
        if (state.text.toString() != title) state.text = title
        if (detail.text.toString() != description) detail.text = description
        mark.setImageResource(if (status == ActivationState.ACTIVE) R.drawable.ic_status_verified else R.drawable.ic_status_outline)
        hero.background = shape(Color.parseColor(if (status == ActivationState.ACTIVE) {
            if (dark) "#234574" else "#729DF9"
        } else if (dark) "#2A2E42" else "#DBDFFA"))
        mark.setColorFilter(ink)
        framework.text = evidence.framework?.let { "$it · API ${evidence.api ?: "—"}" } ?: ModuleText.STATUS_UNAVAILABLE.text()
        host.text = installed?.let {
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
            "${it.versionName?.takeIf(String::isNotBlank) ?: ModuleText.STATUS_UNAVAILABLE.text()} ($code)"
        } ?: ModuleText.STATUS_NOT_INSTALLED.text()
        val native = evidence.targets?.filter { it.process == ModuleConstants.TARGET_PACKAGE && it.uid == hostUid && it.pid > 0 }
        loaded.text = native?.takeIf { it.isNotEmpty() }?.map { it.moduleVersion }?.distinct()?.joinToString { code ->
            if (code == BuildConfig.VERSION_CODE.toLong()) "${BuildConfig.VERSION_NAME} ($code)" else "versionCode $code"
        }
            ?: ModuleText.STATUS_NO_RUNNING_HOST.text()
        scope.text = when {
            evidence.scope == null -> ModuleText.STATUS_UNAVAILABLE.text()
            ModuleConstants.TARGET_PACKAGE in evidence.scope -> ModuleText.STATUS_SCOPE_ON.text()
            else -> ModuleText.STATUS_SCOPE_OFF.text()
        }
        refresh.isEnabled = !evidence.checking
        refresh.alpha = if (refresh.isEnabled) 1f else 0.55f
        open.isEnabled = installed != null
        open.alpha = if (open.isEnabled) 1f else 0.5f
    }
    private fun openHost() {
        runCatching {
            val intent = packageManager.getLaunchIntentForPackage(ModuleConstants.TARGET_PACKAGE) ?: error("Host launcher unavailable")
            startActivity(intent)
        }.onFailure { Toast.makeText(this, ModuleText.STATUS_OPEN_FAILED.text(), Toast.LENGTH_SHORT).show() }
    }
}
