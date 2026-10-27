package io.github.ekkzy.softlumi.remote;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Device-local button choices. These values and the accessibility grant are not portable backups. */
public final class RemoteButtonPreferences {
    public static final String FILE_NAME = "softlumi_remote_buttons";
    public static final String DEFAULT_NETFLIX_PACKAGE = "ru.kinopoisk.tv";
    public static final String DEFAULT_PRIME_PACKAGE = "com.vk.tv";
    private static final String ENABLED = "enabled";
    private static final String NETFLIX_PACKAGE = "netflix_package";
    private static final String PRIME_PACKAGE = "prime_package";
    private static final int MAX_PACKAGE_LENGTH = 255;
    private static final Pattern PACKAGE_NAME = Pattern.compile(
            "[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");

    public enum Button { NETFLIX, PRIME }

    public static final class Snapshot {
        public final boolean enabled;
        public final String netflixPackage;
        public final String primePackage;

        private Snapshot(boolean enabled, String netflixPackage, String primePackage) {
            this.enabled = enabled;
            this.netflixPackage = netflixPackage;
            this.primePackage = primePackage;
        }

        public String packageFor(Button button) {
            if (button == Button.NETFLIX) return netflixPackage;
            if (button == Button.PRIME) return primePackage;
            return "";
        }
    }

    private final SharedPreferences preferences;

    public RemoteButtonPreferences(Context context) {
        Objects.requireNonNull(context, "context");
        Context app = context.getApplicationContext();
        preferences = (app == null ? context : app).getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    RemoteButtonPreferences(SharedPreferences preferences) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
    }

    public Snapshot read() {
        Map<String, ?> values = preferences.getAll();
        return new Snapshot(Boolean.TRUE.equals(values.get(ENABLED)),
                readPackage(values, NETFLIX_PACKAGE, DEFAULT_NETFLIX_PACKAGE),
                readPackage(values, PRIME_PACKAGE, DEFAULT_PRIME_PACKAGE));
    }

    public void setEnabled(boolean enabled) {
        preferences.edit().putBoolean(ENABLED, enabled).apply();
    }

    /** Empty restores the remote's original behavior; invalid input never changes an existing choice. */
    public boolean setPackage(Button button, String packageName) {
        if (button == null || !validTarget(packageName)) return false;
        String key = button == Button.NETFLIX ? NETFLIX_PACKAGE : PRIME_PACKAGE;
        preferences.edit().putString(key, packageName).apply();
        return true;
    }

    /** The caller owns a strong listener reference and unregisters it with its lifecycle. */
    public void registerListener(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        preferences.registerOnSharedPreferenceChangeListener(Objects.requireNonNull(listener, "listener"));
    }

    public void unregisterListener(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        preferences.unregisterOnSharedPreferenceChangeListener(Objects.requireNonNull(listener, "listener"));
    }

    private static String readPackage(Map<String, ?> values, String key, String fallback) {
        if (!values.containsKey(key)) return fallback;
        Object value = values.get(key);
        return value instanceof String && validTarget((String) value) ? (String) value : "";
    }

    private static boolean validTarget(String value) {
        return value != null && value.length() <= MAX_PACKAGE_LENGTH
                && (value.isEmpty() || "android".equals(value) || PACKAGE_NAME.matcher(value).matches());
    }
}
