package io.github.ekkzy.softlumi.remote;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import java.lang.ref.WeakReference;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Same-process, explicit-opt-in filtering for the supported remote's two application buttons. */
public final class RemoteButtonService extends AccessibilityService {
    private static final String TAG = "EveBoxRemote";
    private static final ComponentName LEGACY_SERVICE = new ComponentName("io.freemytv", "io.freemytv.RemoteKeyService");
    private static volatile WeakReference<RemoteButtonService> current = new WeakReference<>(null);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final RemoteKeyPolicy policy = new RemoteKeyPolicy();
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean connected;
    private volatile Targets targets = Targets.EMPTY;
    private volatile RemoteButtonPreferences.Snapshot configuration;
    private boolean legacyConflict = true, receiverRegistered, observerRegistered, observersReady;
    private Context app;
    private PowerManager power;
    private RemoteButtonPreferences preferences;
    private ThreadPoolExecutor resolver;
    private Future<?> pendingResolution;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (store, key) -> invalidateAndRefresh();
    private final ContentObserver settingsObserver = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) {
            legacyConflict = legacyState(app) != LegacyState.CLEAR;
            invalidateAndRefresh();
        }
    };
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            Uri data = intent == null ? null : intent.getData();
            String name = data == null ? null : data.getSchemeSpecificPart();
            if (name == null || name.isEmpty()) return;
            RemoteButtonPreferences.Snapshot snapshot = configuration;
            // Until this generation publishes, configuration may still name the old owners.
            if (snapshot == null || targets.generation != generation.get()
                    || snapshot.netflixPackage.equals(name) || snapshot.primePackage.equals(name)) invalidateAndRefresh();
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        if (connected) disconnect();
        app = getApplicationContext(); power = app.getSystemService(PowerManager.class);
        preferences = new RemoteButtonPreferences(app);
        resolver = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), runnable -> {
            Thread worker = new Thread(runnable, "eve-box-remote-targets"); worker.setDaemon(true); return worker;
        }, new ThreadPoolExecutor.AbortPolicy());
        connected = true; current = new WeakReference<>(this);
        preferences.registerListener(preferenceListener);
        observersReady = registerObservers();
        legacyConflict = legacyState(app) != LegacyState.CLEAR;
        invalidateAndRefresh();
    }
    private boolean registerObservers() {
        try {
            app.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, settingsObserver);
            observerRegistered = true;
            app.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, settingsObserver);
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED); filter.addAction(Intent.ACTION_PACKAGE_CHANGED); filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addDataScheme("package");
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else app.registerReceiver(packageReceiver, filter);
            receiverRegistered = true;
            return true;
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "Remote configuration observers unavailable");
            return false;
        }
    }

    /** Invalidates immediately, including a preference callback delivered away from main. */
    private void invalidateAndRefresh() {
        long ticket = generation.incrementAndGet(); targets = Targets.EMPTY;
        Runnable refresh = () -> {
            if (!connected || ticket != generation.get() || preferences == null) return;
            if (pendingResolution != null) pendingResolution.cancel(true);
            resolver.getQueue().clear();
            RemoteButtonPreferences preferenceSource = preferences;
            Context application = app;
            boolean mayResolve = !legacyConflict && observersReady && supportsHost();
            try {
                pendingResolution = resolver.submit(() -> {
                    // A cold SharedPreferences load must not block the key/lifecycle thread.
                    RemoteButtonPreferences.Snapshot snapshot = preferenceSource.read();
                    EnumMap<RemoteButtonPreferences.Button, Intent> prepared = new EnumMap<>(RemoteButtonPreferences.Button.class);
                    if (snapshot.enabled && mayResolve) {
                        AndroidPackages packages = new AndroidPackages(application);
                        for (RemoteButtonPreferences.Button button : RemoteButtonPreferences.Button.values()) {
                            if (Thread.currentThread().isInterrupted() || ticket != generation.get()) return;
                            Intent launch = resolveLaunchIntent(packages, snapshot.packageFor(button));
                            if (launch != null) prepared.put(button, launch);
                        }
                    }
                    Targets complete = new Targets(ticket, prepared);
                    main.post(() -> {
                        if (!connected || ticket != generation.get()) return;
                        configuration = snapshot;
                        if (snapshot.enabled && !legacyConflict && observersReady) targets = complete;
                    });
                });
            } catch (RejectedExecutionException unavailable) { Log.w(TAG, "Remote target resolver unavailable"); }
        };
        if (Looper.myLooper() == main.getLooper()) refresh.run(); else main.post(refresh);
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (event == null || !RemoteKeyPolicy.canMatchKeyCode(event.getKeyCode())) return false;
        InputDevice device;
        try { device = event.getDevice(); } catch (RuntimeException unavailable) { device = null; }
        RemoteKeyPolicy.Event sample = new RemoteKeyPolicy.Event(event.getAction(), event.getKeyCode(), event.getScanCode(),
                event.getSource(), event.getDeviceId(), device == null ? 0 : device.getVendorId(), device == null ? 0 : device.getProductId(),
                device == null ? "" : device.getName(), device == null || device.isVirtual(), event.getRepeatCount(), event.getDownTime(), event.isCanceled());
        RemoteButtonPreferences.Snapshot snapshot = configuration;
        boolean interactive;
        try { interactive = power != null && power.isInteractive(); } catch (RuntimeException unavailable) { interactive = false; }
        // Policy completes a consumed pair before checking these gates or device metadata.
        return policy.handle(sample, connected && snapshot != null && snapshot.enabled && !legacyConflict && observersReady,
                supportsHost(), interactive, this::launchCached);
    }
    private boolean launchCached(RemoteButtonPreferences.Button button) {
        Targets ready = targets;
        if (ready.generation != generation.get()) return false;
        Intent intent = ready.copy(button);
        if (intent == null) return false;
        try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true; }
        catch (ActivityNotFoundException | SecurityException unavailable) {
            targets = Targets.EMPTY; main.post(this::invalidateAndRefresh); return false;
        }
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* No window events are requested or read. */ }
    @Override public void onInterrupt() { /* No feedback or delayed key action; preserve owned UP. */ }
    @Override public boolean onUnbind(Intent intent) { disconnect(); return super.onUnbind(intent); }
    @Override public void onDestroy() { disconnect(); super.onDestroy(); }
    private void disconnect() {
        connected = false; generation.incrementAndGet(); targets = Targets.EMPTY; configuration = null;
        if (current.get() == this) current = new WeakReference<>(null);
        if (preferences != null) preferences.unregisterListener(preferenceListener);
        if (app != null && receiverRegistered) {
            try { app.unregisterReceiver(packageReceiver); }
            catch (RuntimeException unavailable) { Log.w(TAG, "Remote package observer cleanup unavailable"); }
        }
        if (app != null && observerRegistered) {
            try { app.getContentResolver().unregisterContentObserver(settingsObserver); }
            catch (RuntimeException unavailable) { Log.w(TAG, "Remote settings observer cleanup unavailable"); }
        }
        receiverRegistered = false; observerRegistered = false; observersReady = false; legacyConflict = true;
        if (pendingResolution != null) pendingResolution.cancel(true);
        if (resolver != null) { resolver.getQueue().clear(); resolver.shutdownNow(); }
        main.removeCallbacksAndMessages(null); policy.reset();
        preferences = null; pendingResolution = null; resolver = null; power = null;
    }

    /** Android's enabled-service configuration, separate from the app opt-in switch. */
    public static boolean isEnabled(Context context) {
        Context app = application(context);
        return includesComponent(readEnabledSetting(app), new ComponentName(app.getPackageName(), RemoteButtonService.class.getName()));
    }
    public static boolean isConnected() { RemoteButtonService service = current.get(); return service != null && service.connected; }
    public static boolean supportsHost() { return RemoteKeyPolicy.supportsHost(Build.MODEL, Build.DEVICE); }
    public static boolean isLegacyRemapperEnabled(Context context) { return legacyState(application(context)) == LegacyState.ACTIVE; }
    public static boolean openSettings(Context context) {
        try { application(context).startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true; }
        catch (ActivityNotFoundException | SecurityException unavailable) { return false; }
    }
    private enum LegacyState { CLEAR, ACTIVE, UNKNOWN }
    private static LegacyState legacyState(Context context) {
        String enabled = readEnabledSetting(context);
        if (enabled == null) return LegacyState.UNKNOWN;
        return includesComponent(enabled, LEGACY_SERVICE) ? LegacyState.ACTIVE : LegacyState.CLEAR;
    }
    /** Read configuration, not the asynchronously updated set of bound service connections. */
    private static String readEnabledSetting(Context context) {
        if (context == null) return null;
        try {
            if (Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return "";
            String enabled = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabled == null ? "" : enabled.length() <= 65536 ? enabled : null;
        } catch (RuntimeException unavailable) { return null; }
    }
    static boolean includesComponent(String setting, ComponentName component) {
        if (setting == null || component == null || setting.length() > 65536) return false;
        for (String flattened : setting.split(":")) if (component.equals(ComponentName.unflattenFromString(flattened))) return true;
        return false;
    }
    private static Context application(Context context) { Context app = context.getApplicationContext(); return app == null ? context : app; }

    /** The standard launcher resolver admits only an enabled exported activity owned by the chosen package. */
    interface LaunchPackages {
        Intent leanback(String packageName);
        Intent launcher(String packageName);
        ResolveInfo resolve(Intent candidate);
        boolean hasPermission(String permission);
    }
    static Intent resolveLaunchIntent(LaunchPackages packages, String owner) {
        if (owner == null || owner.isEmpty() || owner.length() > 255) return null;
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            try {
                Intent candidate = Intent.CATEGORY_LEANBACK_LAUNCHER.equals(category) ? packages.leanback(owner) : packages.launcher(owner);
                if (candidate == null || candidate.getPackage() != null && !owner.equals(candidate.getPackage())
                        || candidate.getComponent() != null && !owner.equals(candidate.getComponent().getPackageName())) continue;
                ResolveInfo resolved = packages.resolve(candidate);
                ActivityInfo activity = resolved == null ? null : resolved.activityInfo;
                if (activity == null || !owner.equals(activity.packageName) || activity.name == null || activity.name.isEmpty()
                        || !activity.exported || !activity.enabled || activity.applicationInfo == null || !activity.applicationInfo.enabled
                        || !owner.equals(activity.applicationInfo.packageName)
                        || activity.permission != null && !activity.permission.isEmpty() && !packages.hasPermission(activity.permission)) continue;
                return new Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(owner)
                        .setComponent(new ComponentName(owner, activity.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            } catch (RuntimeException unavailable) { /* A removed package may still have a standard fallback. */ }
        }
        return null;
    }
    private static final class AndroidPackages implements LaunchPackages {
        private final Context app;
        private final PackageManager packages;
        AndroidPackages(Context app) { this.app = app; packages = app.getPackageManager(); }
        @Override public Intent leanback(String name) { return packages.getLeanbackLaunchIntentForPackage(name); }
        @Override public Intent launcher(String name) { return packages.getLaunchIntentForPackage(name); }
        @Override public ResolveInfo resolve(Intent candidate) { return packages.resolveActivity(candidate, 0); }
        @Override public boolean hasPermission(String permission) { return app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    }
    private static final class Targets {
        static final Targets EMPTY = new Targets(-1, new EnumMap<>(RemoteButtonPreferences.Button.class));
        final long generation;
        private final EnumMap<RemoteButtonPreferences.Button, Intent> intents = new EnumMap<>(RemoteButtonPreferences.Button.class);
        Targets(long generation, Map<RemoteButtonPreferences.Button, Intent> prepared) {
            this.generation = generation;
            for (Map.Entry<RemoteButtonPreferences.Button, Intent> item : prepared.entrySet()) intents.put(item.getKey(), new Intent(item.getValue()));
        }
        Intent copy(RemoteButtonPreferences.Button button) { Intent intent = intents.get(button); return intent == null ? null : new Intent(intent); }
    }
}
