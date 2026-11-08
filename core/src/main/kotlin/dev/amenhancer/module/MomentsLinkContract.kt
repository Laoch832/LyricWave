package dev.amenhancer.module

import java.net.URI

/** Restricted, public-data-only IPC to NetEase Cloud Music. No hooks run in WeChat. */
object MomentsLinkContract {
    const val INTERMEDIARY_PACKAGE = "com.netease.cloudmusic"
    const val OPEN = "dev.amenhancer.module.action.OPEN_MOMENTS_LINK"
    const val PERMISSION = "dev.amenhancer.module.permission.SHARE_MOMENTS_LINK"
    const val URL = "music_url"
    const val TITLE = "music_title"
    const val RESULT_OPENED = -1
    const val RESULT_NOT_READY = 2
    const val RESULT_UNSUPPORTED = 3
    const val RESULT_INVALID = 4
    fun extract(text: String): String? = Regex("https://music\\.apple\\.com/[^\\s<>\\\"]+", RegexOption.IGNORE_CASE)
        .find(text.take(16384))?.value?.trimEnd('.', ',', ')', '，', '。')?.let(::validate)
    fun validate(value: String?): String? = value?.takeIf { it.length in 1..4096 }?.let {
        runCatching {
            val uri = URI(it)
            it.takeIf { uri.scheme.equals("https", true) && uri.host.equals("music.apple.com", true) &&
                uri.rawUserInfo == null && uri.port in listOf(-1, 443) && !uri.rawPath.isNullOrBlank() }
        }.getOrNull()
    }
}
