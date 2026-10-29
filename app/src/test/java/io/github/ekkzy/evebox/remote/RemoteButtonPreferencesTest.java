package io.github.ekkzy.evebox.remote;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import io.github.ekkzy.evebox.remote.RemoteButtonPreferences.Button;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public final class RemoteButtonPreferencesTest {
    @Test public void defaultsOfferKnownPackagesButNeverEnableTheService() {
        MemoryPreferences store = new MemoryPreferences();
        RemoteButtonPreferences.Snapshot value = new RemoteButtonPreferences(store).read();
        assertFalse(value.enabled);
        assertEquals("ru.kinopoisk.tv", value.packageFor(Button.NETFLIX));
        assertEquals("com.vk.tv", value.packageFor(Button.PRIME));
        assertEquals("", value.packageFor(null));
        assertTrue(store.values.isEmpty()); assertEquals(0, store.applies);
    }

    @Test public void choicesAndPassthroughSurviveReopeningWithoutChangingEnableState() {
        MemoryPreferences store = new MemoryPreferences();
        RemoteButtonPreferences prefs = new RemoteButtonPreferences(store);
        assertTrue(prefs.setPackage(Button.NETFLIX, "tv.publisher.Player_2"));
        assertTrue(prefs.setPackage(Button.PRIME, ""));
        RemoteButtonPreferences.Snapshot before = prefs.read();
        prefs.setEnabled(true);
        RemoteButtonPreferences.Snapshot reopened = new RemoteButtonPreferences(store).read();
        assertTrue(reopened.enabled); assertEquals("tv.publisher.Player_2", reopened.netflixPackage);
        assertEquals("", reopened.primePackage);
        assertFalse("Snapshots do not mutate after later writes", before.enabled);
        prefs.setEnabled(false);
        assertEquals(reopened.netflixPackage, prefs.read().netflixPackage);
        assertEquals(reopened.primePackage, prefs.read().primePackage);
    }

    @Test public void invalidTargetsCannotReplaceAnExistingChoiceOrReachStorage() {
        MemoryPreferences store = new MemoryPreferences();
        RemoteButtonPreferences prefs = new RemoteButtonPreferences(store);
        assertTrue(prefs.setPackage(Button.NETFLIX, "pkg.retained"));
        Map<String, Object> before = new HashMap<>(store.values); int writes = store.applies;
        for (String value : new String[]{null, " pkg.other", "pkg.other ", "pkg.other\n", "pkg..other",
                ".pkg.other", "pkg.other.", "1pkg.other", "pkg.2other", "pkg/.Activity", "https://pkg.other",
                "pkg;other", "пакет.пример", "single", "p." + "a".repeat(254)}) {
            assertFalse(String.valueOf(value), prefs.setPackage(Button.NETFLIX, value));
            assertEquals(before, store.values); assertEquals(writes, store.applies);
        }
        assertFalse(prefs.setPackage(null, "pkg.other"));
        assertEquals(before, store.values); assertEquals(writes, store.applies);
        String maximum = "p." + "a".repeat(253);
        assertEquals(255, maximum.length()); assertTrue(prefs.setPackage(Button.NETFLIX, maximum));
        assertEquals(maximum, prefs.read().netflixPackage);
    }

    @Test public void corruptStoredValuesFailClosedAndAreNotSilentlyRewritten() {
        MemoryPreferences store = new MemoryPreferences();
        RemoteButtonPreferences prefs = new RemoteButtonPreferences(store);
        for (Object value : new Object[]{null, 7, true, "pkg/.Activity", "pkg.other\n", "p." + "a".repeat(254)}) {
            store.values.put("netflix_package", value);
            store.values.put("enabled", "true");
            Map<String, Object> before = new HashMap<>(store.values);
            assertEquals("", prefs.read().netflixPackage); assertFalse(prefs.read().enabled);
            assertEquals("com.vk.tv", prefs.read().primePackage);
            assertEquals(before, store.values); assertEquals(0, store.applies);
        }
        store.values.put("prime_package", "");
        assertEquals("", prefs.read().primePackage);
    }

    @Test public void listenerCanReloadChoicesAndIsRemovedWithItsOwner() {
        MemoryPreferences store = new MemoryPreferences();
        RemoteButtonPreferences prefs = new RemoteButtonPreferences(store);
        ArrayList<RemoteButtonPreferences.Snapshot> observed = new ArrayList<>();
        SharedPreferences.OnSharedPreferenceChangeListener listener = (changed, key) -> observed.add(prefs.read());
        prefs.registerListener(listener);
        prefs.setEnabled(true); prefs.setPackage(Button.PRIME, "pkg.newtarget");
        assertEquals(2, observed.size()); assertTrue(observed.get(0).enabled);
        assertEquals("pkg.newtarget", observed.get(1).primePackage);
        prefs.unregisterListener(listener); prefs.setEnabled(false);
        assertEquals(2, observed.size()); assertTrue(store.listeners.isEmpty());
    }

    @Test public void contextConstructorUsesApplicationPrivateRemoteNamespace() {
        MemoryPreferences store = new MemoryPreferences();
        Context app = new ContextWrapper(null) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                assertEquals("evebox_remote_buttons", name); assertEquals(Context.MODE_PRIVATE, mode);
                return store;
            }
        };
        Context activity = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return app; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                throw new AssertionError("Use the application context");
            }
        };
        new RemoteButtonPreferences(activity).setEnabled(true);
        assertEquals(Boolean.TRUE, store.values.get("enabled"));
    }

    private static final class MemoryPreferences implements SharedPreferences {
        final Map<String, Object> values = new LinkedHashMap<>();
        final Set<OnSharedPreferenceChangeListener> listeners = new LinkedHashSet<>();
        int applies;
        @Override public Map<String, ?> getAll() { return new LinkedHashMap<>(values); }
        @Override public String getString(String key, String fallback) { Object v = values.get(key); return v instanceof String ? (String) v : fallback; }
        @SuppressWarnings("unchecked") @Override public Set<String> getStringSet(String key, Set<String> fallback) { Object v = values.get(key); return v instanceof Set ? new HashSet<>((Set<String>) v) : fallback; }
        @Override public int getInt(String key, int fallback) { Object v = values.get(key); return v instanceof Integer ? (Integer) v : fallback; }
        @Override public long getLong(String key, long fallback) { Object v = values.get(key); return v instanceof Long ? (Long) v : fallback; }
        @Override public float getFloat(String key, float fallback) { Object v = values.get(key); return v instanceof Float ? (Float) v : fallback; }
        @Override public boolean getBoolean(String key, boolean fallback) { Object v = values.get(key); return v instanceof Boolean ? (Boolean) v : fallback; }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { listeners.add(listener); }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { listeners.remove(listener); }
        @Override public Editor edit() {
            return new Editor() {
                final Map<String, Object> staged = new LinkedHashMap<>(); final Set<String> removed = new HashSet<>(); boolean clear;
                @Override public Editor putString(String key, String value) { staged.put(key, value); return this; }
                @Override public Editor putStringSet(String key, Set<String> value) { staged.put(key, new HashSet<>(value)); return this; }
                @Override public Editor putInt(String key, int value) { staged.put(key, value); return this; }
                @Override public Editor putLong(String key, long value) { staged.put(key, value); return this; }
                @Override public Editor putFloat(String key, float value) { staged.put(key, value); return this; }
                @Override public Editor putBoolean(String key, boolean value) { staged.put(key, value); return this; }
                @Override public Editor remove(String key) { removed.add(key); return this; }
                @Override public Editor clear() { clear = true; return this; }
                @Override public boolean commit() { apply(); return true; }
                @Override public void apply() {
                    if (clear) values.clear(); for (String key : removed) values.remove(key); values.putAll(staged); applies++;
                    for (String key : staged.keySet()) for (OnSharedPreferenceChangeListener listener : new ArrayList<>(listeners))
                        listener.onSharedPreferenceChanged(MemoryPreferences.this, key);
                }
            };
        }
    }
}
