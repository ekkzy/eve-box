package io.github.ekkzy.evebox.data;

import static org.junit.Assert.*;

import android.database.MatrixCursor;
import android.media.tv.TvContract;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real Android cursor semantics; no system provider rows or permission grants are changed. */
@RunWith(AndroidJUnit4.class)
public final class WatchNextCursorTest {
    private static final String WATCH_TYPE = TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE;

    @Test public void watchReasonAndContentKindReadTheirOwnColumnsInAnyOrder() {
        try (MatrixCursor cursor = new MatrixCursor(new String[]{WATCH_TYPE, "title", "type"})) {
            cursor.addRow(new Object[]{TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST, "Fixture", TvContract.WatchNextPrograms.TYPE_MOVIE});
            cursor.addRow(new Object[]{TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE, "Fixture", TvContract.WatchNextPrograms.TYPE_TV_EPISODE});
            assertTrue(cursor.moveToFirst());
            assertEquals(WatchNextRepository.Kind.MOVIE, WatchNextRepository.readProviderKind(cursor));
            assertEquals(WatchNextRepository.WatchType.WATCHLIST, WatchNextRepository.readProviderWatchType(cursor));
            assertTrue(cursor.moveToNext());
            assertEquals(WatchNextRepository.Kind.SHOW, WatchNextRepository.readProviderKind(cursor));
            assertEquals(WatchNextRepository.WatchType.CONTINUE, WatchNextRepository.readProviderWatchType(cursor));
        }
    }

    @Test public void missingNullOrMalformedWatchReasonCannotBecomeContinueZero() {
        try (MatrixCursor cursor = new MatrixCursor(new String[]{"type"})) {
            cursor.addRow(new Object[]{TvContract.WatchNextPrograms.TYPE_MOVIE});
            assertTrue(cursor.moveToFirst());
            assertEquals(WatchNextRepository.Kind.MOVIE, WatchNextRepository.readProviderKind(cursor));
            assertEquals(WatchNextRepository.WatchType.UNKNOWN, WatchNextRepository.readProviderWatchType(cursor));
        }
        try (MatrixCursor cursor = new MatrixCursor(new String[]{"type", WATCH_TYPE})) {
            for (Object invalid : new Object[]{null, "", "garbage", "0.5", -1, 4, Long.MAX_VALUE}) {
                cursor.addRow(new Object[]{TvContract.WatchNextPrograms.TYPE_MOVIE, invalid});
            }
            while (cursor.moveToNext()) {
                assertEquals(WatchNextRepository.WatchType.UNKNOWN, WatchNextRepository.readProviderWatchType(cursor));
            }
            assertEquals(7, cursor.getCount());
        }
    }

    @Test public void optionalContentKindFallbackRetainsTheRequiredWatchReason() {
        String[] required = {"_id", WATCH_TYPE};
        int[] calls = {0};
        try (MatrixCursor cursor = new MatrixCursor(required)) {
            cursor.addRow(new Object[]{1, TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE});
            assertSame(cursor, WatchNextRepository.queryWithOptionalType(projection -> {
                calls[0]++;
                List<String> columns = Arrays.asList(projection);
                assertTrue("Watch reason remains required on both query attempts", columns.contains(WATCH_TYPE));
                if (columns.contains("type")) throw new IllegalArgumentException("Fixture rejects optional content kind");
                assertEquals(Arrays.asList(required), columns);
                return cursor;
            }, required));
            assertEquals(2, calls[0]);
            assertTrue(cursor.moveToFirst());
            assertEquals(WatchNextRepository.Kind.UNKNOWN, WatchNextRepository.readProviderKind(cursor));
            assertEquals(WatchNextRepository.WatchType.CONTINUE, WatchNextRepository.readProviderWatchType(cursor));
        }
    }
}
