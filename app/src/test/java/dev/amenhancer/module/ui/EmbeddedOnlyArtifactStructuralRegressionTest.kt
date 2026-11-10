package dev.amenhancer.module.ui
import dev.amenhancer.module.hook.readRefactorComponent

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedOnlyArtifactStructuralRegressionTest {
    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readRefactorComponent()
        ?: error("$relativePath was not found from the unit-test working directory")

    private fun projectPath(relativePath: String): File = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::exists) ?: File(relativePath)

    @Test
    fun `settings stay embedded while the desktop entry only checks activation`() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml")
        val entry = projectFile("app/src/main/java/dev/amenhancer/module/hook/HookEntry.kt")
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")

        assertTrue(manifest.contains("<activity android:name=\".share.MomentsShareActivity\""))
        val launcher = manifest.substringAfter("<activity android:name=\".status.ModuleStatusActivity\"").substringBefore("</activity>")
        assertTrue(launcher.contains("android.intent.category.LAUNCHER"))
        assertTrue(launcher.contains("android.intent.action.MAIN"))
        assertTrue(Regex("android.intent.category.LAUNCHER").findAll(manifest).count() == 1)
        assertTrue(Regex("android.intent.action.MAIN").findAll(manifest).count() == 1)
        assertFalse(manifest.contains(".ui.SettingsActivity"))
        assertFalse(manifest.contains("<activity-alias"))
        assertTrue(manifest.contains("permission.SHARE_MOMENTS_LINK"))
        assertTrue(entry.contains("EmbeddedSettingsHost.install("))
        assertTrue(entry.contains("EmbeddedRuntimeSettingsController("))
        assertTrue(host.contains("Application.ActivityLifecycleCallbacks"))
        assertTrue(host.contains("showSettingsDialog(activity)"))
    }

    @Test fun `WeChat is never an injection target even if an old scope selection remains`() {
        val scope = projectFile("app/src/main/resources/META-INF/xposed/scope.list")
        assertFalse(scope.lineSequence().any { it.trim() == "com.tencent.mm" })
        assertTrue(scope.contains("com.netease.cloudmusic"))
        val entry = projectFile("app/src/main/java/dev/amenhancer/module/hook/HookEntry.kt")
        val moduleLoaded = entry.substringAfter("override fun onModuleLoaded").substringBefore("override fun onPackageReady")
        val denied = moduleLoaded.indexOf("com.tencent.mm")
        assertTrue(denied >= 0)
        assertTrue(denied < moduleLoaded.indexOf("ModernXposedRuntime.attach"))
        assertFalse(entry.contains("WeChatShareBridge"))
        assertTrue(entry.contains("NetEaseShareBridge"))
    }

    @Test
    fun `obsolete standalone settings sources are absent`() {
        listOf(
            "app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt",
            "app/src/main/java/dev/amenhancer/module/ui/LauncherIconController.kt",
            "app/src/main/java/dev/amenhancer/module/ui/CurrentSongIdentityRequester.kt",
            "app/src/main/java/dev/amenhancer/module/ModuleApplication.kt",
            "app/src/main/java/dev/amenhancer/module/XposedServiceSnapshot.kt",
            "app/src/main/java/dev/amenhancer/module/config/ConfigStore.kt",
            "app/src/main/java/dev/amenhancer/module/font/SafFontImporter.kt",
            "app/src/main/java/dev/amenhancer/module/lyrics/CustomLyricsManager.kt",
        ).forEach { path -> assertFalse("obsolete standalone source remains: $path", projectPath(path).isFile) }
    }
}
