package dev.amenhancer.module.island

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Bundle
import dev.amenhancer.module.hook.IslandLyricsSourceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IslandLyricsTest {
    @Test fun `selected lyrics take priority and cannot follow another track`() {
        try {
            IslandLyricsSourceState.observeNative(11, "Native")
            assertEquals("Native", IslandLyricsSourceState.read(11))
            IslandLyricsSourceState.observeSelected(11, "Selected")
            assertEquals("Selected", IslandLyricsSourceState.read(11))
            assertNull(IslandLyricsSourceState.read(22))
            IslandLyricsSourceState.resetFor(22)
            IslandLyricsSourceState.observeNative(22, "Next track")
            assertEquals("Next track", IslandLyricsSourceState.read(22))
            assertNull(IslandLyricsSourceState.read(11))
        } finally {
            IslandLyricsSourceState.resetFor(null)
        }
    }

    @Test fun `line lookup follows seek boundaries and ignores extra lyric tracks`() {
        val timeline = IslandLyricTimeline.parse("""
            <tt xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>
                <p begin="0:01.500" end="3s"><span>First </span><span>line</span><span ttm:role="x-bg">chorus</span></p>
                <p begin="3500ms" end="00:05.00">Second &amp; last</p>
                <p begin="0:05" ttm:role="x-translation">translation</p>
            </div></body></tt>
        """.trimIndent())
        assertNull(timeline.at(1_499))
        assertEquals("First line", timeline.at(1_500))
        assertNull(timeline.at(3_000))
        assertEquals("Second & last", timeline.at(3_500))
        assertEquals("First line", timeline.at(2_000))
        assertNull(timeline.at(5_000))
    }

    @Test fun `one notification changes with the lyric and can be cancelled`() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        fun publish(line: String) = IslandLyricsNotification.update(app, Bundle().apply {
            putString("title", "Song")
            putString("line", line)
        })
        try {
            publish("First line")
            publish("Next line")
            val active = manager.activeNotifications.single()
            assertEquals(IslandLyricsNotification.ID, active.id)
            assertEquals("Next line", active.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals(Notification.VISIBILITY_PRIVATE, active.notification.visibility)
        } finally {
            IslandLyricsNotification.update(app, null)
        }
        assertEquals(0, manager.activeNotifications.size)
    }
}
