package dev.amenhancer.module.hook

import org.json.JSONObject

internal fun JSONObject.matchesPlaybackBuild(build: TargetBuild): Boolean {
    val evidence = optJSONObject("verification") ?: return false
    return build.packageName == "com.apple.android.music" && optInt("contractVersion") == 1 &&
        evidence.optString("versionName") == build.versionName &&
        evidence.optLong("versionCode", -1L) == build.versionCode
}
