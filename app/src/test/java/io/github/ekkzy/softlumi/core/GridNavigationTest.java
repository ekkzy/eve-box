package io.github.ekkzy.softlumi.core;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class GridNavigationTest {
    @Test public void horizontalMovementDoesNotWrapRows() {
        assertEquals(3, GridNavigation.move(3, 10, 4, GridNavigation.Direction.RIGHT));
        assertEquals(4, GridNavigation.move(4, 10, 4, GridNavigation.Direction.LEFT));
        assertEquals(5, GridNavigation.move(4, 10, 4, GridNavigation.Direction.RIGHT));
    }
    @Test public void lastPartialRowUsesLastAvailableItem() {
        assertEquals(9, GridNavigation.move(7, 10, 4, GridNavigation.Direction.DOWN));
        assertEquals(9, GridNavigation.move(9, 10, 4, GridNavigation.Direction.DOWN));
        assertEquals(5, GridNavigation.move(9, 10, 4, GridNavigation.Direction.UP));
    }
    @Test public void clampsRemovedFocusAndHandlesEmptyList() {
        assertEquals(-1, GridNavigation.move(0, 0, 4, GridNavigation.Direction.DOWN));
        assertEquals(2, GridNavigation.move(50, 3, 4, null));
    }
}
