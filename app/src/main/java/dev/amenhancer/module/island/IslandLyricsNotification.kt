package dev.amenhancer.module.island

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.R
import dev.amenhancer.module.i18n.ModuleText
import org.json.JSONObject

internal object IslandLyricsNotification {
    const val METHOD = "island_lyrics"
    const val ID = 7106
    const val CHANNEL = "lyricwave_island_lyrics"
    private const val EXPIRES_MS = 18_000L

    @SuppressLint("MissingPermission")
    fun update(context: Context, input: Bundle?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val title = input?.getString("title")?.trim()?.take(120)
        val line = input?.getString("line")?.trim()?.take(180)
        if (title.isNullOrEmpty() || line.isNullOrEmpty()) {
            manager.cancel(ID)
            return
        }
        if (!manager.areNotificationsEnabled() || (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) return

        manager.createNotificationChannel(NotificationChannel(CHANNEL, ModuleText.ISLAND_LYRICS.text(),
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_module)
            .setContentTitle(title)
            .setContentText(line)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setStyle(Notification.BigTextStyle().bigText(line))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setTimeoutAfter(EXPIRES_MS)
        context.packageManager.getLaunchIntentForPackage(ModuleConstants.TARGET_PACKAGE)?.let { launch ->
            builder.setContentIntent(PendingIntent.getActivity(context, ID, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        if (Build.BRAND.equals("Xiaomi", true) || Build.BRAND.equals("Redmi", true) || Build.BRAND.equals("POCO", true)) {
            builder.addExtras(xiaomiFocus(context, title, line))
        }
        if (Build.VERSION.SDK_INT >= 36) {
            builder.addExtras(Bundle().apply { putBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, true) })
                .setShortCriticalText(line.take(24))
        }
        manager.notify(ID, builder.build())
    }

    // Minimal HyperOS focus notification payload, based on HyperLyrics Enhanced's public implementation.
    private fun xiaomiFocus(context: Context, title: String, line: String): Bundle {
        val iconKey = "miui.focus.pic_note"
        fun pic() = JSONObject().put("type", 1).put("pic", iconKey)
        val island = JSONObject()
            .put("bigIslandArea", JSONObject()
                .put("imageTextInfoLeft", JSONObject().put("type", 1).put("picInfo", pic()))
                .put("textInfo", JSONObject().put("title", line)))
            .put("smallIslandArea", JSONObject().put("combinePicInfo", JSONObject().put("picInfo", pic())))
        val payload = JSONObject().put("param_v2", JSONObject()
            .put("islandFirstFloat", false).put("updatable", true).put("reopen", "reopen")
            .put("isShowNotification", true).put("param_island", island)
            .put("baseInfo", JSONObject().put("type", 2).put("title", title).put("content", line)))
        return Bundle().apply {
            putBoolean("mFocusNotification", true)
            putString("miui.focus.param", payload.toString())
            putBundle("miui.focus.pics", Bundle().apply {
                putParcelable(iconKey, Icon.createWithResource(context, R.drawable.ic_module))
            })
        }
    }
}
