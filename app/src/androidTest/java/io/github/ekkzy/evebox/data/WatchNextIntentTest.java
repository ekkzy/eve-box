package io.github.ekkzy.evebox.data;

import static org.junit.Assert.*;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Process;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Android-backed security contracts for serialized Watch Next intents. These tests use the real
 * Android URI parser and installed alpha/beta fixture metadata. They never start an activity,
 * grant a permission, change a component, write provider rows, or modify HOME.
 */
@RunWith(AndroidJUnit4.class)
public class WatchNextIntentTest {
    private static final String ALPHA = "io.github.ekkzy.evebox.fixture.alpha";
    private static final String BETA = "io.github.ekkzy.evebox.fixture.beta";
    private static final String CLASS_PREFIX = "io.github.ekkzy.evebox.fixture.";
    private static final ComponentName ALPHA_ACTIVITY = new ComponentName(ALPHA, CLASS_PREFIX + "FixtureActivity");
    private static final ComponentName BETA_ACTIVITY = new ComponentName(BETA, CLASS_PREFIX + "FixtureActivity");
    private static final ComponentName PROTECTED_ACTIVITY = new ComponentName(ALPHA, CLASS_PREFIX + "PermissionActivity");
    private static final ComponentName PRIVATE_ACTIVITY = new ComponentName(ALPHA, CLASS_PREFIX + "PrivateActivity");
    private static final ComponentName DISABLED_ACTIVITY = new ComponentName(ALPHA, CLASS_PREFIX + "DisabledActivity");
    private static final String PROTECTED_PERMISSION = "android.permission.MANAGE_USERS";
    private static final int GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;

    private Context context;
    private Method resolver;

    @Before public void requireInstalledSecurityFixtures() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        resolver = WatchNextRepository.class.getDeclaredMethod("resolveSafeIntent", Context.class,
                WatchNextRepository.Entry.class);
        resolver.setAccessible(true);
        PackageManager packages = context.getPackageManager();
        // Fail visibly when fixture prerequisites are missing; a skipped security test is not evidence.
        assertTrue("Install fixture alpha before this suite", packages.getActivityInfo(ALPHA_ACTIVITY, 0).exported);
        assertTrue("Install fixture beta before this suite", packages.getActivityInfo(BETA_ACTIVITY, 0).exported);
        ActivityInfo protectedInfo = packages.getActivityInfo(PROTECTED_ACTIVITY, 0);
        assertTrue(protectedInfo.exported);
        assertEquals(PROTECTED_PERMISSION, protectedInfo.permission);
        assertEquals("The launcher must not own the fixture's privileged permission",
                PackageManager.PERMISSION_DENIED, context.checkSelfPermission(PROTECTED_PERMISSION));
        assertFalse(packages.getActivityInfo(PRIVATE_ACTIVITY, 0).exported);
        assertFalse(packages.getActivityInfo(DISABLED_ACTIVITY, PackageManager.MATCH_DISABLED_COMPONENTS).enabled);
    }

    @Test public void legitimateOwnerTargetResolvesToItsInstalledActivity() throws Exception {
        Intent safe = resolve(row(ALPHA, encode(ownerIntent(ALPHA_ACTIVITY))));
        assertNotNull("A legitimate owner-bound row must remain usable", safe);
        assertEquals(ALPHA_ACTIVITY, safe.getComponent());
        assertEquals(ALPHA, safe.getPackage());
        assertEquals(Intent.ACTION_VIEW, safe.getAction());
        assertEquals(Uri.parse("https://example.invalid/watch/episode-1"), safe.getData());
    }

    @Test public void explicitComponentCannotCrossTheProviderOwnerPackage() throws Exception {
        assertNull("A row owned by alpha must not launch beta",
                resolve(row(ALPHA, encode(ownerIntent(BETA_ACTIVITY)))));
    }

    @Test public void serializedPackageCannotOverrideTheDeclaredOwner() throws Exception {
        Intent conflicting = ownerIntent(ALPHA_ACTIVITY).setPackage(BETA);
        Intent safe = resolve(row(ALPHA, encode(conflicting)));
        assertNotNull(safe);
        assertEquals(ALPHA_ACTIVITY, safe.getComponent());
        assertEquals(ALPHA, safe.getPackage());
    }

    @Test public void selectorCannotRedirectAnOtherwiseValidOwnerTarget() throws Exception {
        Intent redirected = ownerIntent(ALPHA_ACTIVITY);
        redirected.setSelector(new Intent(Intent.ACTION_MAIN).setComponent(BETA_ACTIVITY));
        String serialized = encode(redirected);
        Intent roundTrip = Intent.parseUri(serialized, Intent.URI_INTENT_SCHEME | Intent.URI_ALLOW_UNSAFE);
        assertNotNull("The malicious selector must actually survive the Android URI fixture", roundTrip.getSelector());
        assertEquals(BETA_ACTIVITY, roundTrip.getSelector().getComponent());
        Intent safe = resolve(row(ALPHA, serialized));
        assertNotNull("Removing a selector should preserve the legitimate explicit alpha target", safe);
        assertNull(safe.getSelector());
        assertEquals(ALPHA_ACTIVITY, safe.getComponent());
    }

    @Test public void unsafeUriFlagsCannotDelegateGrantsOrControlOtherTasks() throws Exception {
        int unsafe = GRANTS | Intent.FLAG_ACTIVITY_FORWARD_RESULT | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
        String serialized = encode(ownerIntent(ALPHA_ACTIVITY).addFlags(unsafe));
        Intent unsafeRoundTrip = Intent.parseUri(serialized, Intent.URI_INTENT_SCHEME | Intent.URI_ALLOW_UNSAFE);
        assertEquals("The URI fixture must contain the flags under test", unsafe,
                unsafeRoundTrip.getFlags() & unsafe);
        Intent safe = resolve(row(ALPHA, serialized));
        assertNotNull(safe);
        assertEquals("Untrusted provider flags must not grant URI access or alter task ownership", 0,
                safe.getFlags() & unsafe);
        assertTrue("An application context needs a new task", (safe.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertNull(safe.getClipData());
    }

    @Test public void contentUriWithoutReadAccessDoesNotAcquireADelegatedGrant() throws Exception {
        Uri inaccessible = Uri.parse("content://io.github.ekkzy.evebox.fixture.no_grant/private/episode");
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkUriPermission(inaccessible,
                Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION));
        Intent request = ownerIntent(ALPHA_ACTIVITY).setData(inaccessible).addFlags(GRANTS);
        Intent safe = resolve(row(ALPHA, encode(request)));
        assertNotNull(safe);
        assertEquals(inaccessible, safe.getData());
        assertEquals("A serialized row must not make the launcher a URI permission deputy", 0,
                safe.getFlags() & GRANTS);
    }

    @Test public void privilegedAndUnrelatedActionsAreRejectedEvenWithAnExportedComponent() throws Exception {
        for (String action : new String[]{Intent.ACTION_INSTALL_PACKAGE, Intent.ACTION_DELETE,
                Intent.ACTION_CALL, Intent.ACTION_SEND}) {
            Intent request = ownerIntent(ALPHA_ACTIVITY).setAction(action);
            assertNull("Unexpected action must not be forwarded: " + action, resolve(row(ALPHA, encode(request))));
        }
    }

    @Test public void fileAndScriptDataAreRejectedBeforeAnyLaunch() throws Exception {
        for (String address : new String[]{"file:///data/user/0/private/item", "javascript:alert(1)",
                "FILE:///sdcard/private-item", "JavaScript:alert(1)"}) {
            assertNull("Unsafe data scheme must not be forwarded: " + address,
                    resolve(row(ALPHA, encode(ownerIntent(ALPHA_ACTIVITY).setData(Uri.parse(address))))));
        }
    }

    @Test public void malformedSerializedIntentFailsClosed() throws Exception {
        assertNull(resolve(row(ALPHA, "intent:#Intent;launchFlags=not-a-number;end")));
        assertNull(resolve(row(ALPHA, "intent:#Intent;component=missing-end")));
    }

    @Test public void oversizedProviderUriCannotBypassTheResolverLimitThroughEntryClipping() throws Exception {
        String raw = encode(ownerIntent(ALPHA_ACTIVITY)
                .setData(Uri.parse("https://example.invalid/content/" + "a".repeat(17000))));
        assertTrue(raw.length() > 16384);
        WatchNextRepository.Entry entry = row(ALPHA, raw);
        assertEquals("Reject the complete provider action instead of changing its target", "", entry.intentUri);
        assertNull(resolve(entry));
    }

    @Test public void staleRowWithRemovedActivityIsNotLaunchable() throws Exception {
        ComponentName removed = new ComponentName(ALPHA, CLASS_PREFIX + "ActivityRemovedInAnEarlierVersion");
        try {
            context.getPackageManager().getActivityInfo(removed, 0);
            fail("The stale-activity fixture must be missing");
        } catch (PackageManager.NameNotFoundException expected) { }
        assertNull("An installed package alone is not sufficient; the actual target must exist",
                resolve(row(ALPHA, encode(ownerIntent(removed)))));
    }

    @Test public void protectedActivityIsRejectedWithoutRequestingPrivilegedPermission() throws Exception {
        assertNull(resolve(row(ALPHA, encode(ownerIntent(PROTECTED_ACTIVITY)))));
    }

    @Test public void nonExportedAndDisabledAliasesAreNotLaunchable() throws Exception {
        assertNull(resolve(row(ALPHA, encode(ownerIntent(PRIVATE_ACTIVITY)))));
        assertNull(resolve(row(ALPHA, encode(ownerIntent(DISABLED_ACTIVITY)))));
    }

    @Test public void permissionRevokedAtStartReturnsFalseWithoutLaunchingAnything() {
        NoLaunchContext guarded = new NoLaunchContext(context, new SecurityException("Fixture permission revoked at start"));
        assertFalse(WatchNextRepository.safeLaunch(guarded, row(ALPHA, encode(ownerIntent(ALPHA_ACTIVITY)))));
        assertNotNull("The real parser/resolver must succeed before the start-time denial", guarded.attempted);
        assertEquals(ALPHA_ACTIVITY, guarded.attempted.getComponent());
    }

    @Test public void activityDisappearingAtStartReturnsFalseWithoutLaunchingAnything() {
        NoLaunchContext guarded = new NoLaunchContext(context, new ActivityNotFoundException("Fixture target disappeared at start"));
        assertFalse(WatchNextRepository.safeLaunch(guarded, row(ALPHA, encode(ownerIntent(ALPHA_ACTIVITY)))));
        assertNotNull(guarded.attempted);
        assertEquals(ALPHA_ACTIVITY, guarded.attempted.getComponent());
    }

    private Intent resolve(WatchNextRepository.Entry entry) throws Exception {
        try { return (Intent) resolver.invoke(null, context, entry); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw failure;
        }
    }
    private static WatchNextRepository.Entry row(String owner, String serialized) {
        return new WatchNextRepository.Entry("security-fixture", "Test-only episode", "", owner,
                serialized, "", 100, 1000, System.currentTimeMillis(), true, true);
    }
    private static Intent ownerIntent(ComponentName component) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid/watch/episode-1"))
                .setComponent(component);
    }
    private static String encode(Intent intent) {
        // Preserve unsafe flags in the hostile test INPUT. Production deliberately parses without
        // URI_ALLOW_UNSAFE, and its output must satisfy the security contracts above.
        return intent.toUri(Intent.URI_INTENT_SCHEME | Intent.URI_ALLOW_UNSAFE);
    }
    private static final class NoLaunchContext extends ContextWrapper {
        private final RuntimeException failure;
        Intent attempted;
        NoLaunchContext(Context base, RuntimeException failure) { super(base); this.failure = failure; }
        @Override public void startActivity(Intent intent) {
            attempted = intent;
            // Never call super: this proves graceful start failure without a device-side launch.
            throw failure;
        }
    }
}
