package io.github.ekkzy.evebox.remote;

import static org.junit.Assert.*;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** No service activation, system settings mutation, input injection or application launch. */
@RunWith(AndroidJUnit4.class)
public final class RemoteButtonServiceContractTest {
    private static final String OWNER = "io.github.ekkzy.evebox.fixture.remote";
    private Context context;

    @Before public void requireDebugTarget() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("io.github.ekkzy.evebox.debug", context.getPackageName());
        assertEquals("The declared API34 service contract is checked on the owned image", 34, Build.VERSION.SDK_INT);
    }

    @Test public void serviceIsSystemBoundSameProcessAndRequestsOnlyKeyFiltering() throws Exception {
        ComponentName component = new ComponentName(context, RemoteButtonService.class);
        ServiceInfo service = context.getPackageManager().getServiceInfo(component, PackageManager.GET_META_DATA);
        assertTrue(service.exported);
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", service.permission);
        assertEquals("Preference listeners and live status belong to the application process", context.getApplicationInfo().processName, service.processName);
        assertTrue(service.metaData.containsKey(AccessibilityService.SERVICE_META_DATA));
        AccessibilityServiceInfo config = null;
        AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
        assertNotNull(manager);
        for (AccessibilityServiceInfo installed : manager.getInstalledAccessibilityServiceList()) {
            ServiceInfo candidate = installed.getResolveInfo().serviceInfo;
            if (component.equals(new ComponentName(candidate.packageName, candidate.name))) { config = installed; break; }
        }
        assertNotNull("Android discovers the declared service without enabling it", config);
        assertEquals("Do not subscribe to screen/window events", 0, config.eventTypes);
        assertEquals(AccessibilityServiceInfo.FEEDBACK_GENERIC, config.feedbackType);
        assertEquals("No window, gestures, touch exploration or screenshot capabilities",
                AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS, config.getCapabilities());
        assertEquals(AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS, config.flags);
        assertFalse("The service must not claim to be a disability tool merely to suppress a warning", config.isAccessibilityTool());
        assertNotNull(config.loadDescription(context.getPackageManager()));
    }

    @Test public void launcherResolutionRejectsForeignDisabledAndProtectedTargetsAndKeepsAStandardFallback() {
        FakePackages packages = new FakePackages();
        packages.leanback = candidate(OWNER, "TvActivity");
        packages.main = candidate(OWNER, "MainActivity");
        ResolveInfo tv = activity(OWNER, "TvActivity"), main = activity(OWNER, "MainActivity");
        packages.results.put(packages.leanback.getComponent(), tv); packages.results.put(packages.main.getComponent(), main);
        packages.leanback.setAction(Intent.ACTION_VIEW).setData(Uri.parse("content://fixture/private"))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra("unrelated", "fixture");
        packages.leanback.setClipData(ClipData.newPlainText("fixture", "not content"));
        Intent clean = RemoteButtonService.resolveLaunchIntent(packages, OWNER);
        assertNotNull(clean); assertEquals(tv.activityInfo.name, clean.getComponent().getClassName());
        assertEquals(OWNER, clean.getPackage()); assertEquals(Intent.ACTION_MAIN, clean.getAction());
        assertTrue(clean.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER));
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, clean.getFlags());
        assertNull(clean.getData()); assertNull(clean.getSelector()); assertNull(clean.getClipData()); assertNull(clean.getExtras());

        tv.activityInfo.packageName = "foreign.package";
        Intent fallback = RemoteButtonService.resolveLaunchIntent(packages, OWNER);
        assertNotNull("An invalid TV target may use the same owner's standard launcher", fallback);
        assertEquals(main.activityInfo.name, fallback.getComponent().getClassName());
        assertTrue(fallback.hasCategory(Intent.CATEGORY_LAUNCHER));
        packages.main = null;
        assertNull("Foreign activity ownership cannot escape the selected package", RemoteButtonService.resolveLaunchIntent(packages, OWNER));
        tv.activityInfo.packageName = OWNER;
        tv.activityInfo.exported = false;
        assertNull(RemoteButtonService.resolveLaunchIntent(packages, OWNER));
        tv.activityInfo.exported = true; tv.activityInfo.enabled = false;
        assertNull(RemoteButtonService.resolveLaunchIntent(packages, OWNER));
        tv.activityInfo.enabled = true; tv.activityInfo.applicationInfo.enabled = false;
        assertNull(RemoteButtonService.resolveLaunchIntent(packages, OWNER));
        tv.activityInfo.applicationInfo.enabled = true; tv.activityInfo.permission = "fixture.permission.PRIVATE";
        assertNull("Protected activity without our permission is not cached", RemoteButtonService.resolveLaunchIntent(packages, OWNER));
        tv.activityInfo.permission = null; tv.activityInfo.applicationInfo.packageName = "foreign.package";
        assertNull(RemoteButtonService.resolveLaunchIntent(packages, OWNER));
        assertNull("Empty mapping restores native behavior", RemoteButtonService.resolveLaunchIntent(packages, ""));
    }

    @Test public void previousAppKeyFiltersBlockTheNewServiceByExactComponentOnly() {
        assertTrue(RemoteButtonService.hasLegacyRemapper("io.github.ekkzy.softlumi/.remote.RemoteButtonService"));
        assertTrue(RemoteButtonService.hasLegacyRemapper("other.app/.Service:io.github.ekkzy.softlumi/io.github.ekkzy.softlumi.remote.RemoteButtonService"));
        assertTrue(RemoteButtonService.hasLegacyRemapper("io.github.ekkzy.softlumi.debug/io.github.ekkzy.softlumi.remote.RemoteButtonService"));
        assertTrue(RemoteButtonService.hasLegacyRemapper("io.freemytv/.RemoteKeyService"));
        assertFalse(RemoteButtonService.hasLegacyRemapper("io.github.ekkzy.evebox/.remote.RemoteButtonService"));
        assertFalse(RemoteButtonService.hasLegacyRemapper("io.github.ekkzy.softlumi/.remote.RemoteButtonServiceOther"));
        assertFalse(RemoteButtonService.hasLegacyRemapper("other.app/io.github.ekkzy.softlumi.remote.RemoteButtonService"));
        assertFalse(RemoteButtonService.hasLegacyRemapper(null));
    }

    @Test public void enableActionOnlyOpensPublicSettingsAndReportsAnUnavailableController() {
        ComponentName legacy = new ComponentName("io.freemytv", "io.freemytv.RemoteKeyService");
        assertTrue("Android may persist a shortened class name", RemoteButtonService.includesComponent("other.app/.Service:io.freemytv/.RemoteKeyService", legacy));
        assertFalse("A similar service name is not a conflict", RemoteButtonService.includesComponent("io.freemytv/.RemoteKeyServiceOther", legacy));
        assertFalse("A class name in another owner package is not the exact old component", RemoteButtonService.includesComponent("other.app/io.freemytv.RemoteKeyService", legacy));
        assertFalse(RemoteButtonService.includesComponent(null, legacy));
        final Intent[] started = new Intent[1];
        Context receiver = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public void startActivity(Intent intent) { started[0] = new Intent(intent); }
        };
        assertTrue(RemoteButtonService.openSettings(receiver));
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, started[0].getAction());
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, started[0].getFlags());
        assertNull("No OEM component or private enable argument", started[0].getComponent());
        assertNull(started[0].getData()); assertNull(started[0].getExtras());
        Context unavailable = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public void startActivity(Intent intent) { throw new ActivityNotFoundException("Synthetic missing settings"); }
        };
        assertFalse(RemoteButtonService.openSettings(unavailable));
        assertFalse("Reading configuration or constructing intents must not bind the service", RemoteButtonService.isConnected());
    }

    @Test public void packageChangeDuringMappingRefreshInvalidatesTheUnpublishedGeneration() throws Exception {
        // This detached instance never binds, reads preferences or starts the package resolver.
        RemoteButtonService service = new RemoteButtonService();
        BroadcastReceiver receiver = (BroadcastReceiver) field("packageReceiver").get(service);
        AtomicLong generation = (AtomicLong) field("generation").get(service);
        Intent newOwnerAdded = new Intent(Intent.ACTION_PACKAGE_ADDED, Uri.parse("package:fixture.newowner"));
        receiver.onReceive(context, newOwnerAdded);
        assertEquals("Cold resolution has no published owners to filter by", 1, generation.get());

        Constructor<RemoteButtonPreferences.Snapshot> snapshot = RemoteButtonPreferences.Snapshot.class
                .getDeclaredConstructor(boolean.class, String.class, String.class);
        snapshot.setAccessible(true);
        field("configuration").set(service, snapshot.newInstance(true, OWNER, "fixture.second"));
        Field targets = field("targets");
        Constructor<?> cache = targets.getType().getDeclaredConstructor(long.class, Map.class);
        cache.setAccessible(true);
        Map<RemoteButtonPreferences.Button, Intent> intents = new EnumMap<>(RemoteButtonPreferences.Button.class);
        intents.put(RemoteButtonPreferences.Button.NETFLIX, candidate(OWNER, "TvActivity"));
        Object published = cache.newInstance(generation.get(), intents);
        targets.set(service, published);
        receiver.onReceive(context, newOwnerAdded);
        assertEquals("A settled mapping ignores an unrelated package", 1, generation.get());
        assertSame(published, targets.get(service));

        SharedPreferences.OnSharedPreferenceChangeListener listener =
                (SharedPreferences.OnSharedPreferenceChangeListener) field("preferenceListener").get(service);
        listener.onSharedPreferenceChanged(null, "netflix_package");
        assertNotSame("A changed mapping immediately drops the old launch intents", published, targets.get(service));
        long pending = generation.get();
        receiver.onReceive(context, newOwnerAdded);
        assertEquals("The old owner's snapshot must not hide an update of the pending new target", pending + 1, generation.get());
        assertFalse(RemoteButtonService.isConnected());
    }

    private static Field field(String name) throws Exception {
        Field field = RemoteButtonService.class.getDeclaredField(name);
        field.setAccessible(true); return field;
    }

    private static Intent candidate(String owner, String name) {
        return new Intent(Intent.ACTION_MAIN).setPackage(owner).setComponent(new ComponentName(owner, owner + "." + name));
    }
    private static ResolveInfo activity(String owner, String name) {
        ResolveInfo resolved = new ResolveInfo(); ActivityInfo activity = new ActivityInfo();
        activity.packageName = owner; activity.name = owner + "." + name; activity.enabled = true; activity.exported = true;
        activity.applicationInfo = new ApplicationInfo(); activity.applicationInfo.packageName = owner; activity.applicationInfo.enabled = true;
        resolved.activityInfo = activity; return resolved;
    }
    private static final class FakePackages implements RemoteButtonService.LaunchPackages {
        Intent leanback, main;
        final Map<ComponentName, ResolveInfo> results = new HashMap<>();
        @Override public Intent leanback(String owner) { return leanback; }
        @Override public Intent launcher(String owner) { return main; }
        @Override public ResolveInfo resolve(Intent candidate) { return results.get(candidate.getComponent()); }
        @Override public boolean hasPermission(String permission) { return false; }
    }
}
