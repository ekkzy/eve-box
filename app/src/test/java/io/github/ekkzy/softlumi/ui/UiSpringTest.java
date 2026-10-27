package io.github.ekkzy.softlumi.ui;

import static org.junit.Assert.*;
import org.junit.Test;

public class UiSpringTest {
    @Test public void focusConvergesAndStopsProducingWork() {
        UiSpring spring = new UiSpring(.22f, .90f, 0);
        spring.target = 1;
        int frames = 0;
        while (spring.step(1 / 60f) && frames++ < 240) { }
        assertTrue("Focus must settle rather than redraw forever", frames < 240);
        assertEquals(1, spring.value, 0); assertEquals(0, spring.velocity, 0);
        assertFalse(spring.step(1 / 60f));
    }

    @Test public void reversalPreservesPresentationAndVelocity() {
        UiSpring spring = new UiSpring(.22f, .90f, 0);
        spring.target = 1;
        for (int i = 0; i < 5; i++) spring.step(1 / 60f);
        float position = spring.value, velocity = spring.velocity;
        assertTrue(position > 0); assertTrue(velocity > 0);
        spring.target = 0;
        assertEquals(position, spring.value, 0); assertEquals(velocity, spring.velocity, 0);
        for (int i = 0; i < 240; i++) spring.step(1 / 60f);
        assertTrue(spring.settled()); assertEquals(0, spring.value, 0);
    }

    @Test public void rapidRetargetsRemainFiniteThenSettle() {
        UiSpring spring = new UiSpring(.22f, .90f, 0);
        for (int i = 0; i < 600; i++) {
            spring.target = (i / 2) % 2;
            spring.step(1 / 120f);
            assertTrue(Float.isFinite(spring.value)); assertTrue(Float.isFinite(spring.velocity));
            assertTrue(Math.abs(spring.value) < 2); assertTrue(Math.abs(spring.velocity) < 100);
        }
        spring.target = 1;
        for (int i = 0; i < 240; i++) spring.step(1 / 60f);
        assertTrue(spring.settled());
    }

    @Test public void longStallUsesBoundedPresentationStep() {
        UiSpring stalled = new UiSpring(.22f, .90f, 0), bounded = new UiSpring(.22f, .90f, 0);
        stalled.target = bounded.target = 1;
        stalled.step(30); bounded.step(.05f);
        assertEquals(bounded.value, stalled.value, 0); assertEquals(bounded.velocity, stalled.velocity, 0);
        for (int i = 0; i < 100; i++) stalled.step(30);
        assertEquals(1, stalled.value, 0); assertTrue(stalled.settled());
    }

    @Test public void pixelTracksRespectTheSameVisibleStoppingBudget() {
        UiSpring progress = new UiSpring(.40f, .96f, 0), pixels = new UiSpring(.40f, .96f, 0);
        progress.target = 1; pixels.target = 560; pixels.tolerance(560);
        for (int i = 0; i < 180; i++) {
            progress.step(1 / 60f); pixels.step(1 / 60f);
            assertEquals(progress.value, pixels.value / 560, .001f);
        }
        assertTrue(progress.settled()); assertTrue(pixels.settled());
        assertEquals(560, pixels.value, 0); assertEquals(0, pixels.velocity, 0);
    }

    @Test public void invalidElapsedTimeDoesNotPoisonState() {
        UiSpring spring = new UiSpring(.22f, .90f, 0); spring.target = 1;
        spring.step(Float.NaN); spring.step(-1); spring.step(Float.POSITIVE_INFINITY);
        assertEquals(0, spring.value, 0); assertEquals(0, spring.velocity, 0);
        assertThrows(IllegalArgumentException.class, () -> new UiSpring(0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> spring.tolerance(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> spring.snap(Float.NaN));
    }

    @Test public void trajectoryDoesNotDependOnFramePartition() {
        for (float damping : new float[] {.55f, 1, 1.8f}) {
            UiSpring whole = new UiSpring(.4f, damping, 0), split = new UiSpring(.4f, damping, 0);
            whole.target = split.target = 1;
            whole.velocity = split.velocity = .5f;
            whole.step(.04f);
            split.step(.01f); split.step(.015f); split.step(.015f);
            assertEquals("Position at the same wall time", whole.value, split.value, .000001f);
            assertEquals("Velocity at the same wall time", whole.velocity, split.velocity, .00001f);
        }
    }

    @Test public void criticallyDampedUnitOscillatorMatchesKnownSolution() {
        UiSpring spring = new UiSpring((float) (2 * Math.PI), 1, 0);
        spring.target = 1;
        spring.step(.05f);
        assertEquals(1 - 1.05 * Math.exp(-.05), spring.value, .0000001);
        assertEquals(.05 * Math.exp(-.05), spring.velocity, .0000001);
    }

    @Test public void heavyDampingReachesTargetWithoutOvershoot() {
        UiSpring spring = new UiSpring(.30f, 1.8f, 0);
        spring.target = 1;
        float previous = 0;
        for (int i = 0; i < 300; i++) {
            spring.step(1 / 120f);
            assertTrue("Overdamped approach is monotonic", spring.value >= previous);
            assertTrue("No overshoot", spring.value <= 1);
            previous = spring.value;
        }
        assertTrue(spring.settled());
    }
}
