package io.github.ekkzy.evebox.core;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class FavoriteOrderTest {
    @Test public void keepsFirstOccurrenceAndEightSlots() {
        assertEquals(Arrays.asList("a", "b", "c", "d", "e", "f", "g", "h"),
                FavoriteOrder.normalize(Arrays.asList(" a ", "b", "a", "", null,
                        "c", "d", "e", "f", "g", "h", "i")));
    }
    @Test public void fullListDoesNotEvictOnAdd() {
        List<String> ids = Arrays.asList("a", "b", "c", "d", "e", "f", "g", "h");
        assertEquals(ids, FavoriteOrder.add(ids, "new"));
        assertEquals(ids, FavoriteOrder.add(ids, "b"));
    }
    @Test public void removalAndMovePreserveRelativeOrder() {
        List<String> ids = Arrays.asList("a", "b", "c", "d");
        assertEquals(Arrays.asList("a", "c", "d"), FavoriteOrder.remove(ids, "b"));
        assertEquals(Arrays.asList("c", "a", "b", "d"), FavoriteOrder.move(ids, "c", -1));
        assertEquals(Arrays.asList("a", "c", "d", "b"), FavoriteOrder.move(ids, "b", 99));
        assertEquals(ids, FavoriteOrder.move(ids, "missing", 2));
    }
    @Test public void snapshotsDoNotFollowSourceMutations() {
        List<String> input = new ArrayList<>(Arrays.asList("a"));
        List<String> result = FavoriteOrder.normalize(input);
        input.add("b");
        assertEquals(Arrays.asList("a"), result);
        try { result.add("c"); fail("Snapshot must be immutable"); }
        catch (UnsupportedOperationException expected) { }
    }
    @Test public void rejectsLineBreaksThatWouldCorruptPersistence() {
        assertEquals(Arrays.asList("valid"), FavoriteOrder.normalize(Arrays.asList("a\nb", "valid")));
    }
}
