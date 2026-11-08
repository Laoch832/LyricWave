@file:Suppress("NewApi")
package dev.amenhancer.module.hook

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsHdrRenderingTest {
    @Test fun `glyph raster carries real gainmap and reuses bounded buffers`() {
        val text = TextView(RuntimeEnvironment.getApplication()).apply { layout(0, 0, 200, 80) }
        val destination = Canvas(Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888))
        val pool = HdrGlyphBuffers()
        val frame = pool.frame(text, destination, 2f)!!
        frame.canvas.drawColor(Color.WHITE)
        assertTrue(frame.bitmap.hasGainmap())
        assertArrayEquals(floatArrayOf(2f, 2f, 2f), frame.bitmap.gainmap!!.ratioMax, 0.001f)
        assertEquals(2f, frame.bitmap.gainmap!!.displayRatioForFullHdr, 0.001f)
        frame.submit()
        val reused = pool.frame(text, destination, 4f)!!
        assertSame(frame.bitmap, reused.bitmap)
        assertEquals(Color.TRANSPARENT, reused.bitmap.getPixel(0, 0))
        assertArrayEquals(floatArrayOf(4f, 4f, 4f), reused.bitmap.gainmap!!.ratioMax, 0.001f)
        assertNull(pool.frame(text, destination, Float.NaN))
        text.layout(0, 0, 5000, 5000)
        assertNull(pool.frame(text, destination, 2f))
    }
    class CountingActivity : Activity() {
        var changes = 0
        override fun onWindowAttributesChanged(params: android.view.WindowManager.LayoutParams) {
            super.onWindowAttributesChanged(params); changes++
        }
    }
    @Test fun `unchanged HDR polling does not keep relaying window attributes`() {
        val activity = Robolectric.buildActivity(CountingActivity::class.java).setup().get()
        val owner = LyricsHdrWindow(activity.window)
        owner.apply(true, 2f, false)
        val changes = activity.changes
        repeat(10) { owner.apply(true, 2f, false) }
        assertEquals(changes, activity.changes)
        owner.close()
    }
    @Test fun `flashbang exit restores original brightness without changing global settings`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val window = activity.window
        window.attributes = window.attributes.apply { screenBrightness = 0.35f }
        val owner = LyricsHdrWindow(window)
        owner.apply(false, 2f, true)
        assertEquals(1f, window.attributes.screenBrightness, 0.001f)
        owner.apply(false, 2f, false)
        assertEquals(0.35f, window.attributes.screenBrightness, 0.001f)
        owner.apply(false, 2f, true)
        window.attributes = window.attributes.apply { screenBrightness = 0.6f }
        owner.close()
        assertEquals(0.6f, window.attributes.screenBrightness, 0.001f)
    }
}
