package io.github.ekkzy.evebox.remote;

import static org.junit.Assert.*;

import io.github.ekkzy.evebox.remote.RemoteButtonPreferences.Button;
import io.github.ekkzy.evebox.remote.RemoteKeyPolicy.Event;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class RemoteKeyPolicyTest {
    private final RemoteKeyPolicy policy = new RemoteKeyPolicy();
    private final List<Button> launches = new ArrayList<>();
    private final RemoteKeyPolicy.Launcher success = button -> { launches.add(button); return true; };

    @Test public void onlyExactHostAndTwoCandidateKeyCodesAreSupported() {
        assertTrue(RemoteKeyPolicy.supportsHost("MiTV-AFMU0", "twilight"));
        for (String[] host : new String[][]{{"MiTV-AFMU0", "other"}, {"other", "twilight"},
                {"mitv-afmu0", "twilight"}, {null, "twilight"}, {"MiTV-AFMU0", null}})
            assertFalse(RemoteKeyPolicy.supportsHost(host[0], host[1]));
        assertTrue(RemoteKeyPolicy.canMatchKeyCode(193)); assertTrue(RemoteKeyPolicy.canMatchKeyCode(194));
        for (int key : new int[]{3, 4, 19, 20, 21, 22, 23, 24, 25, 26, 66, 79, 85, 164, 219, 223, 224})
            assertFalse("Reserved/system key=" + key, RemoteKeyPolicy.canMatchKeyCode(key));
    }

    @Test public void bothObservedNetflixScansAndPrimeOpenOnceIncludingMixedJoystickSource() {
        assertTrue(handle(new Input().event()));
        assertTrue(handle(new Input().action(1).event()));
        assertTrue(handle(new Input().scan(416).name("Xiaomi RC Keyboard").time(200).event()));
        assertTrue(handle(new Input().key(194).scan(207).source(0x01000111).time(300).event()));
        assertEquals(Arrays.asList(Button.NETFLIX, Button.NETFLIX, Button.PRIME), launches);
    }

    @Test public void repeatsDuplicateDownAndMatchingUpCannotLaunchTwice() {
        assertTrue(handle(new Input().event()));
        assertTrue(handle(new Input().event()));
        for (int repeat = 1; repeat <= 8; repeat++) assertTrue(handle(new Input().repeat(repeat).event()));
        assertTrue(handle(new Input().action(1).event()));
        assertFalse(handle(new Input().action(1).event()));
        assertFalse(handle(new Input().repeat(9).event()));
        assertEquals(Arrays.asList(Button.NETFLIX), launches);
    }

    @Test public void ownedSequenceSurvivesDisableConflictSleepAndTargetChange() {
        assertTrue(handle(new Input().event()));
        RemoteKeyPolicy.Launcher changedTarget = button -> { throw new AssertionError("Owned events never resolve the new target"); };
        assertTrue(policy.handle(new Input().repeat(1).event(), false, true, false, changedTarget));
        assertTrue(policy.handle(new Input().action(1).event(), false, false, false, null));
        assertEquals(1, launches.size());
        assertFalse(policy.handle(new Input().time(200).event(), false, true, true, success));
    }

    @Test public void vanishedDeviceMetadataStillBalancesOwnedUpButNeverStartsAnUnownedPress() {
        assertTrue(handle(new Input().event()));
        Input unknown = new Input().vendor(0).product(0).name("").virtual(true).source(0);
        assertTrue(policy.handle(unknown.repeat(1).event(), false, true, false, null));
        assertTrue(policy.handle(unknown.action(1).event(), false, true, false, null));
        assertFalse(handle(unknown.event()));
        assertFalse(handle(unknown.action(0).repeat(0).time(200).event()));
        assertEquals(Arrays.asList(Button.NETFLIX), launches);
    }

    @Test public void malformedOrDifferentHardwareAndReservedKeysArePassedThrough() {
        Input[] invalid = {
                new Input().vendor(0), new Input().product(0), new Input().name("Game Controller"),
                new Input().name(null), new Input().virtual(true), new Input().source(0),
                new Input().source(1), new Input().source(0x100), new Input().source(0x01000010),
                new Input().device(-1), new Input().scan(207), new Input().scan(0),
                new Input().key(194).scan(240), new Input().repeat(-1), new Input().time(-1),
                new Input().action(2), new Input().canceled(true)
        };
        for (Input input : invalid) assertFalse(handle(input.event()));
        for (int key : new int[]{3, 4, 19, 20, 21, 22, 23, 24, 25, 26, 66, 79, 85, 164, 219, 223, 224})
            assertFalse("Reserved/system key=" + key, handle(new Input().key(key).event()));
        assertFalse(handle(null)); assertTrue(launches.isEmpty());
    }

    @Test public void unownedRepeatsAndUpNeverLaunchEvenWithValidHardware() {
        assertFalse(handle(new Input().repeat(1).event()));
        assertFalse(handle(new Input().action(1).event()));
        assertFalse(policy.handle(new Input().event(), true, false, true, success));
        assertFalse(policy.handle(new Input().event(), true, true, false, success));
        assertFalse(policy.handle(new Input().event(), false, true, true, success));
        assertTrue(launches.isEmpty());
    }

    @Test public void failingOrThrowingLaunchLeavesTheWholeOriginalSequenceUntouched() {
        assertFalse(policy.handle(new Input().event(), true, true, true, button -> false));
        assertFalse(handle(new Input().repeat(1).event()));
        assertFalse(handle(new Input().action(1).event()));
        assertFalse(policy.handle(new Input().time(200).event(), true, true, true,
                button -> { throw new IllegalStateException("Target removed before launch"); }));
        assertFalse(handle(new Input().time(200).action(1).event()));
        assertFalse(policy.handle(new Input().time(300).event(), true, true, true, null));
        assertTrue(launches.isEmpty());
    }

    @Test public void anotherRawIdentityCannotReleaseOrBorrowTheOwnedSequence() {
        assertTrue(handle(new Input().event()));
        for (Input input : new Input[]{new Input().device(8), new Input().scan(416),
                new Input().time(101), new Input().key(194).scan(207)}) {
            assertFalse(handle(input.action(1).event()));
            assertFalse(handle(input.action(0).repeat(1).event()));
        }
        assertTrue(handle(new Input().repeat(2).event()));
        assertTrue(handle(new Input().action(1).event()));
        assertEquals(1, launches.size());
    }

    @Test public void twoButtonsHaveIndependentBoundedPressOwnership() {
        assertTrue(handle(new Input().event()));
        assertTrue(handle(new Input().key(194).scan(207).event()));
        assertTrue(handle(new Input().repeat(1).event()));
        assertTrue(handle(new Input().key(194).scan(207).repeat(1).event()));
        assertTrue(handle(new Input().action(1).event()));
        assertTrue(handle(new Input().key(194).scan(207).action(1).event()));
        assertEquals(Arrays.asList(Button.NETFLIX, Button.PRIME), launches);
    }

    @Test public void newFirstDownReplacesLostUpIncludingWhenNewLaunchFails() {
        assertTrue(handle(new Input().event()));
        assertTrue(handle(new Input().time(200).event()));
        assertFalse(handle(new Input().action(1).event()));
        assertTrue(handle(new Input().time(200).repeat(1).event()));
        assertFalse(policy.handle(new Input().time(300).event(), true, true, true, button -> false));
        assertFalse(handle(new Input().time(200).action(1).event()));
        assertFalse(handle(new Input().time(300).action(1).event()));
        assertEquals(2, launches.size());
    }

    @Test public void canceledOwnedUpAndLifecycleResetReleaseThePress() {
        assertTrue(handle(new Input().event()));
        assertTrue(handle(new Input().action(1).canceled(true).event()));
        assertFalse(handle(new Input().repeat(1).event()));
        assertTrue(handle(new Input().time(200).event()));
        assertTrue(handle(new Input().key(194).scan(207).time(300).event()));
        policy.reset();
        assertFalse(handle(new Input().time(200).action(1).event()));
        assertFalse(handle(new Input().key(194).scan(207).time(300).action(1).event()));
        assertEquals(3, launches.size());
    }

    private boolean handle(Event event) { return policy.handle(event, true, true, true, success); }

    private static final class Input {
        int action, key = 193, scan = 240, source = 0x101, device = 7, vendor = 0x2717, product = 0x32b9, repeat;
        long time = 100;
        String name = "Xiaomi RC Consumer Control";
        boolean virtual, canceled;
        Input action(int value) { action = value; return this; }
        Input key(int value) { key = value; return this; }
        Input scan(int value) { scan = value; return this; }
        Input source(int value) { source = value; return this; }
        Input device(int value) { device = value; return this; }
        Input vendor(int value) { vendor = value; return this; }
        Input product(int value) { product = value; return this; }
        Input name(String value) { name = value; return this; }
        Input virtual(boolean value) { virtual = value; return this; }
        Input repeat(int value) { repeat = value; return this; }
        Input time(long value) { time = value; return this; }
        Input canceled(boolean value) { canceled = value; return this; }
        Event event() { return new Event(action, key, scan, source, device, vendor, product, name, virtual, repeat, time, canceled); }
    }
}
