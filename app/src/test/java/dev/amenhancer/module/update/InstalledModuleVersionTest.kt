package dev.amenhancer.module.update

import android.content.pm.PackageInfo
import dev.amenhancer.module.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstalledModuleVersionTest {
    @Test fun `an upgraded APK is compared instead of stale loaded hook constants`() {
        val app = RuntimeEnvironment.getApplication()
        val code = BuildConfig.VERSION_CODE + 10L
        val info = PackageInfo().apply { packageName = BuildConfig.APPLICATION_ID; versionName = "9.0.0"; longVersionCode = code }
        shadowOf(app.packageManager).installPackage(info)
        val installed = installedModuleVersion(app)
        assertEquals(code, installed.code); assertEquals("9.0.0", installed.name)
        val release = AppUpdateRelease("v9.0.0", "9.0.0", "", "", false, "", emptyList(),
            UpdateBuild(code, "9.0.0", "LyricWave-9.0.0.apk", "a".repeat(64)), UpdateComparison.NEWER, 1)
        assertFalse(compareInstalledVersion(release, installed.code).updateAvailable)
        assertEquals(UpdateComparison.CURRENT, compareInstalledVersion(release, installed.code).comparison)
        assertEquals(UpdateComparison.AHEAD, compareInstalledVersion(release, code + 1).comparison)
        assertEquals(UpdateComparison.UNKNOWN, compareInstalledVersion(release.copy(comparison = UpdateComparison.UNKNOWN), code).comparison)
    }
}
