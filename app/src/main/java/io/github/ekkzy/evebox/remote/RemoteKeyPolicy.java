package io.github.ekkzy.evebox.remote;

import io.github.ekkzy.evebox.remote.RemoteButtonPreferences.Button;

/**
 * Own key-sequence reducer for the observed Mi Box / Xiaomi RC profile.
 * Device facts are documented in the local remote study; no legacy implementation is imported.
 * One service thread owns this instance. It stores two active press identities and no input history.
 */
public final class RemoteKeyPolicy {
    private static final int ACTION_DOWN = 0;
    private static final int ACTION_UP = 1;
    private static final int KEY_NETFLIX = 193;
    private static final int KEY_PRIME = 194;
    private static final int SOURCE_CLASS_BUTTON = 0x00000001;
    private static final int SOURCE_KEYBOARD = 0x00000101;
    private static final int VENDOR_XIAOMI = 0x2717;
    private static final int PRODUCT_REMOTE = 0x32b9;

    public static final class Event {
        public final int action, keyCode, scanCode, source, deviceId, vendorId, productId;
        public final String deviceName;
        public final boolean isVirtual;
        public final int repeatCount;
        public final long downTime;
        public final boolean canceled;

        public Event(int action, int keyCode, int scanCode, int source, int deviceId,
                int vendorId, int productId, String deviceName, boolean isVirtual,
                int repeatCount, long downTime, boolean canceled) {
            this.action = action;
            this.keyCode = keyCode;
            this.scanCode = scanCode;
            this.source = source;
            this.deviceId = deviceId;
            this.vendorId = vendorId;
            this.productId = productId;
            this.deviceName = deviceName;
            this.isVirtual = isVirtual;
            this.repeatCount = repeatCount;
            this.downTime = downTime;
            this.canceled = canceled;
        }
    }

    public interface Launcher {
        /** True only when the selected target was successfully started. */
        boolean launch(Button button);
    }

    private static final class Press {
        final int deviceId, keyCode, scanCode;
        final long downTime;

        Press(Event event) {
            deviceId = event.deviceId;
            keyCode = event.keyCode;
            scanCode = event.scanCode;
            downTime = event.downTime;
        }

        boolean matches(Event event) {
            return deviceId == event.deviceId && keyCode == event.keyCode
                    && scanCode == event.scanCode && downTime == event.downTime;
        }
    }

    private Press netflixPress, primePress;

    public static boolean supportsHost(String model, String device) {
        return "MiTV-AFMU0".equals(model) && "twilight".equals(device);
    }

    /** Cheap rejection before the service asks Android for input-device metadata. */
    public static boolean canMatchKeyCode(int keyCode) {
        return keyCode == KEY_NETFLIX || keyCode == KEY_PRIME;
    }

    public boolean handle(Event event, boolean enabled, boolean supportedHost,
            boolean interactive, Launcher launcher) {
        if (event == null || (event.action != ACTION_DOWN && event.action != ACTION_UP)) return false;
        Button button = buttonFor(event);
        if (button == null) return false;
        Press press = button == Button.NETFLIX ? netflixPress : primePress;
        if (press != null && press.matches(event)) {
            // Finishing a successful DOWN needs only its identity. Device metadata may vanish
            // after disconnect; consuming this UP/repeat cannot start another application.
            if (event.action == ACTION_UP) setPress(button, null);
            return true;
        }
        if (!supportedHost || !validRemote(event)) return false;
        if (event.action != ACTION_DOWN || event.repeatCount != 0 || event.canceled) return false;

        // A new first DOWN supersedes a lost UP. No previous press may consume its later events.
        setPress(button, null);
        if (!enabled || !interactive || launcher == null) return false;
        try {
            if (!launcher.launch(button)) return false;
        } catch (RuntimeException unavailable) {
            return false;
        }
        setPress(button, new Press(event));
        return true;
    }

    /** Call only on service unbind/destruction, not when a mapping or enabled state changes. */
    public void reset() {
        netflixPress = null;
        primePress = null;
    }

    private void setPress(Button button, Press press) {
        if (button == Button.NETFLIX) netflixPress = press;
        else primePress = press;
    }

    private static Button buttonFor(Event event) {
        if (event.keyCode == KEY_NETFLIX && (event.scanCode == 240 || event.scanCode == 416))
            return Button.NETFLIX;
        if (event.keyCode == KEY_PRIME && event.scanCode == 207) return Button.PRIME;
        return null;
    }

    private static boolean validRemote(Event event) {
        return event != null && (event.action == ACTION_DOWN || event.action == ACTION_UP)
                && event.repeatCount >= 0 && event.downTime >= 0 && event.deviceId >= 0 && !event.isVirtual
                && event.vendorId == VENDOR_XIAOMI && event.productId == PRODUCT_REMOTE
                && ("Xiaomi RC Consumer Control".equals(event.deviceName)
                    || "Xiaomi RC Keyboard".equals(event.deviceName))
                && (event.source & SOURCE_CLASS_BUTTON) == SOURCE_CLASS_BUTTON
                && (event.source & SOURCE_KEYBOARD) == SOURCE_KEYBOARD;
    }
}
