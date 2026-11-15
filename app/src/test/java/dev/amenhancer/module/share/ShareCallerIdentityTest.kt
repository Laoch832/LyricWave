package dev.amenhancer.module.share

import org.junit.Assert.*
import org.junit.Test

class ShareCallerIdentityTest {
    @Test fun `official Android result caller remains trusted without modern evidence`() {
        assertTrue(ShareCallerIdentity.accepts("com.apple.android.music", null, 23456, true))
    }
    @Test fun `missing result relationship can use official initial caller with the exact installed UID`() {
        assertTrue(ShareCallerIdentity.accepts(null, ShareInitialCaller("com.apple.android.music", 23456), 23456, true))
        assertFalse(ShareCallerIdentity.accepts(null, ShareInitialCaller("com.apple.android.music", 23457), 23456, true))
        assertFalse(ShareCallerIdentity.accepts(null, ShareInitialCaller("com.apple.android.music", -1), -1, true))
    }
    @Test fun `modern evidence never accepts unsigned repackaging or a conflicting result caller`() {
        val initial = ShareInitialCaller("com.apple.android.music", 23456)
        assertFalse(ShareCallerIdentity.accepts(null, initial, 23456, false))
        assertFalse(ShareCallerIdentity.accepts("evil.app", initial, 23456, true))
        assertFalse(ShareCallerIdentity.accepts(null, ShareInitialCaller("evil.app", 23456), 23456, true))
        assertFalse(ShareCallerIdentity.accepts(null, null, 23456, true))
    }
}
