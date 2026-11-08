package dev.amenhancer.module.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.MomentsLinkContract
import dev.amenhancer.module.MomentsSongMetadata
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class EmbeddedSharePanelTest {
    @Test fun `native host share panel appears without launching the module Activity`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val relay = Intent(Intent.ACTION_SEND).setComponent(ComponentName(ModuleConstants.MODULE_PACKAGE, "dev.amenhancer.module.share.MomentsShareActivity"))
            .putExtra(Intent.EXTRA_TEXT, "https://music.apple.com/tr/song/123").putExtra(MomentsLinkContract.TITLE, "Title")
            .putExtra(MomentsLinkContract.ARTIST, "Artist").putExtra(MomentsLinkContract.CHINA_STOREFRONT, true)
        val chooser = Intent.createChooser(Intent(Intent.ACTION_SEND), "Share")
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(relay))
        assertTrue(EmbeddedShareRuntime.showPanel(activity, chooser) { MomentsSongMetadata("Title", "Artist", "https://is1-ssl.mzstatic.com/image/thumb/cover/100x100bb.jpg") })
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        assertNull(shadowOf(activity).nextStartedActivity)
        dialog.dismiss(); controller.pause().stop().destroy()
    }
    @Test fun `ordinary native sharing is left unchanged`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        assertFalse(EmbeddedShareRuntime.showPanel(controller.get(), Intent.createChooser(Intent(Intent.ACTION_SEND), "Share")))
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        controller.pause().stop().destroy()
    }
}
