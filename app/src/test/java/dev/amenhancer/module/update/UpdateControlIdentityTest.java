package dev.amenhancer.module.update;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import java.security.cert.CertificateFactory;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.shadows.ShadowPendingIntent;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Model the kernel's incoming identity: framework stubs alone don't enforce UID/package ownership. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = {UpdateControlIdentityTest.CallerBinder.class,
    UpdateControlIdentityTest.OwnedNotifications.class, UpdateControlIdentityTest.OwnedPendingIntents.class})
public class UpdateControlIdentityTest {
    static final int APPLE_UID = 23456;
    static int uid;
    @Implements(Binder.class) public static class CallerBinder extends ShadowBinder {
        @Implementation protected static int getCallingUid() { return uid; }
        @Implementation protected static long clearCallingIdentity() { int old = uid; uid = Process.myUid(); return old; }
        @Implementation protected static void restoreCallingIdentity(long token) { uid = (int) token; }
    }
    static void requireModuleIdentity() {
        if (Binder.getCallingUid() != Process.myUid()) throw new SecurityException("Creator package does not belong to caller UID");
    }
    @Implements(NotificationManager.class) public static class OwnedNotifications extends ShadowNotificationManager {
        @Implementation protected boolean areNotificationsEnabled() { requireModuleIdentity(); return true; }
    }
    @Implements(PendingIntent.class) public static class OwnedPendingIntents extends ShadowPendingIntent {
        @Implementation protected static PendingIntent getForegroundService(Context c, int code, Intent i, int flags) {
            requireModuleIdentity(); return ShadowPendingIntent.getForegroundService(c, code, i, flags);
        }
        @Implementation protected static PendingIntent getActivity(Context c, int code, Intent i, int flags) {
            requireModuleIdentity(); return ShadowPendingIntent.getActivity(c, code, i, flags);
        }
    }
    private UpdateControlProvider provider;
    @Before public void prepare() throws Exception {
        uid = Process.myUid();
        Context app = RuntimeEnvironment.getApplication();
        PackageInfo apple = new PackageInfo(); apple.packageName = "com.apple.android.music";
        apple.applicationInfo = new ApplicationInfo(); apple.applicationInfo.packageName = apple.packageName;
        apple.applicationInfo.uid = APPLE_UID;
        apple.signingInfo = new SigningInfo();
        byte[] cert = CertificateFactory.getInstance("X.509").generateCertificate(
            getClass().getResourceAsStream("/certificates/apple-music.pem")).getEncoded();
        shadowOf(apple.signingInfo).setSignatures(new Signature[]{new Signature(cert)});
        shadowOf(app.getPackageManager()).installPackage(apple);
        shadowOf(app.getPackageManager()).setPackagesForUid(APPLE_UID, apple.packageName);
        provider = Robolectric.buildContentProvider(UpdateControlProvider.class).create().get();
        uid = APPLE_UID;
    }
    @Test public void signedAppleCallerCanReadStatusAndCreateModuleActions() {
        Bundle status = provider.call("status", null, null);
        assertTrue(status.getBoolean("notifications"));
        assertEquals(APPLE_UID, Binder.getCallingUid());
        Bundle input = new Bundle(); input.putLong("asset_id", 42); input.putInt("connections", 128);
        PendingIntent transfer = provider.call("begin", null, input).getParcelable("action");
        assertNotNull(transfer); assertEquals(RuntimeEnvironment.getApplication().getPackageName(), transfer.getCreatorPackage());
        assertNotNull(provider.call("notifications", null, null).getParcelable("action"));
        assertEquals(APPLE_UID, Binder.getCallingUid());
    }
    @Test public void shareLaunchTicketAlsoUsesModuleOwnership() {
        Context app = RuntimeEnvironment.getApplication();
        android.content.pm.ResolveInfo launch = new android.content.pm.ResolveInfo();
        launch.activityInfo = new android.content.pm.ActivityInfo();
        launch.activityInfo.packageName = "com.netease.cloudmusic"; launch.activityInfo.name = "MainActivity";
        launch.activityInfo.applicationInfo = new ApplicationInfo();
        launch.activityInfo.applicationInfo.packageName = launch.activityInfo.packageName;
        shadowOf(app.getPackageManager()).addResolveInfoForIntent(new Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER).setPackage(launch.activityInfo.packageName), launch);
        assertNotNull(provider.call("share_prepare", null, null).getParcelable("action"));
        assertEquals(APPLE_UID, Binder.getCallingUid());
    }
    @Test public void correctPackageNameWithAnUntrustedCertificateIsRejected() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        PackageInfo apple = app.getPackageManager().getPackageInfo("com.apple.android.music", android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
        shadowOf(apple.signingInfo).setSignatures(new Signature[]{new Signature(new byte[]{1, 2, 3})});
        shadowOf(app.getPackageManager()).installPackage(apple);
        assertThrows(SecurityException.class, () -> provider.call("status", null, null));
        assertEquals(APPLE_UID, Binder.getCallingUid());
    }
    @Test public void untrustedUidIsDeniedBeforeAnyPrivilegedAction() {
        uid = 44555;
        assertThrows(SecurityException.class, () -> provider.call("begin", null, new Bundle()));
        assertEquals(44555, Binder.getCallingUid());
    }
    @Test public void identityIsRestoredWhenAnAuthorizedCommandFails() {
        assertThrows(IllegalArgumentException.class, () -> provider.call("unknown", null, null));
        assertEquals(APPLE_UID, Binder.getCallingUid());
    }
}
