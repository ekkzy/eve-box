package io.github.ekkzy.softlumi.data;

import static org.junit.Assert.*;
import android.database.MatrixCursor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real Cursor null/numeric/column-order semantics, with no provider writes or device launches. */
@RunWith(AndroidJUnit4.class)
public final class TvChannelCursorTest {
    private static final String OWNER = "org.example.native.tv";
    private static final ChannelRepository.Channel CHANNEL = new ChannelRepository.Channel(0, OWNER, 1000, 1, 10, "Shelf", "App");

    @Test public void reorderedColumnsReadOwnerVisibilityAndMetadataCorrectly() {
        MatrixCursor cursor = new MatrixCursor(new String[]{"weight", "browsable", "package_name", "title", "searchable", "intent_uri", "channel_id", "_id", "type"});
        cursor.addRow(new Object[]{8L, 1, OWNER, "Title", 1, "example://content/1", 10, 1, 0});
        ChannelRepository.Programs result = load(cursor);
        assertEquals(ChannelRepository.Status.READY, result.status);
        assertEquals(1, result.items.size());
        assertEquals(Long.valueOf(8), result.items.get(0).weight);
        assertEquals(Integer.valueOf(0), result.items.get(0).type);
        assertEquals("", result.items.get(0).posterUri);
        assertTrue(cursor.isClosed());
    }

    @Test public void nullOrMalformedVisibilityNeverUsesPublisherDefault() {
        MatrixCursor cursor = new MatrixCursor(ChannelRepository.PROGRAM_REQUIRED);
        for (Object value : new Object[]{null, "", "1.0", "true", 2})
            cursor.addRow(new Object[]{cursor.getCount() + 1, OWNER, 10, "Title", "example://content", value, 1});
        ChannelRepository.Programs result = load(cursor);
        assertEquals(ChannelRepository.Status.SCHEMA_INCOMPLETE, result.status);
        assertEquals(5, result.stats.unknownVisibility);
        assertEquals(5, result.stats.scanned);
        assertTrue(cursor.isClosed());
    }

    @Test public void genreUsesThePublisherColumnDespiteColumnOrderAndDoesNotDecodeFreeformCommas() {
        MatrixCursor cursor = new MatrixCursor(new String[]{"genre", "title", "_id", "package_name", "channel_id", "intent_uri", "browsable", "searchable"});
        cursor.addRow(new Object[]{" Драма, фантастика / Sci-Fi ", "Title", 1, OWNER, 10, "example://content/1", 1, 1});
        ChannelRepository.Programs result = load(cursor);
        assertEquals(ChannelRepository.Status.READY, result.status);
        assertEquals("Драма, фантастика / Sci-Fi", result.items.get(0).genre);
        assertTrue(cursor.isClosed());
    }

    @Test public void nullOrAbsentGenreKeepsTheCardWithoutInventingAGenre() {
        MatrixCursor nullGenre = new MatrixCursor(new String[]{"_id", "package_name", "channel_id", "title", "intent_uri", "browsable", "searchable", "genre", "type", "short_description", "canonical_genre"});
        nullGenre.addRow(new Object[]{1, OWNER, 10, "Comedy movie", "example://content/1", 1, 1, null, 0, "Drama", "DRAMA"});
        ChannelRepository.Programs withNull = load(nullGenre);
        assertEquals(ChannelRepository.Status.READY, withNull.status);
        assertEquals("", withNull.items.get(0).genre); assertTrue(nullGenre.isClosed());
        MatrixCursor absentGenre = new MatrixCursor(ChannelRepository.PROGRAM_REQUIRED);
        absentGenre.addRow(new Object[]{2, OWNER, 10, "Title", "example://content/2", 1, 1});
        ChannelRepository.Programs absent = load(absentGenre);
        assertEquals(ChannelRepository.Status.READY, absent.status);
        assertEquals("", absent.items.get(0).genre); assertTrue(absentGenre.isClosed());
    }

    @Test public void missingRequiredVisibilityColumnIsSchemaFailureEvenWithNoRows() {
        MatrixCursor cursor = new MatrixCursor(new String[]{"_id", "package_name", "channel_id", "title", "intent_uri", "searchable"});
        ChannelRepository.Programs result = load(cursor);
        assertEquals(ChannelRepository.Status.SCHEMA_INCOMPLETE, result.status);
        assertEquals(ChannelRepository.Completeness.UNKNOWN, result.completeness);
        assertTrue(cursor.isClosed());
    }

    @Test public void discoveryUsesPublishedTypeRatherThanAnAppNameHeuristic() {
        MatrixCursor cursor = new MatrixCursor(ChannelRepository.CHANNEL_PROJECTION);
        cursor.addRow(new Object[]{1, OWNER, "TYPE_PREVIEW", "Videos", 1, 1});
        cursor.addRow(new Object[]{2, OWNER, "TYPE_DVB_T", "Recommendations", 1, 1});
        ChannelRepository.Discovery result = ChannelRepository.discoverRows(() -> cursor,
                pkg -> new ChannelRepository.Owner("App", 1000, 1), 0, () -> false);
        assertEquals(1, result.channels.size());
        assertEquals(2, result.sources.get(0).scannedChannelCount);
        assertEquals(1, result.sources.get(0).rejectedChannelCount);
        assertTrue(cursor.isClosed());
    }

    @Test public void selectableSystemHiddenChannelStillHonorsKnownLockAndUnknownVisibility() {
        MatrixCursor cursor = new MatrixCursor(ChannelRepository.CHANNEL_WITH_LOCKED_PROJECTION);
        cursor.addRow(new Object[]{1, OWNER, "TYPE_PREVIEW", "Local choice", 0, 1, 0});
        cursor.addRow(new Object[]{2, OWNER, "TYPE_PREVIEW", "Locked", 0, 1, 1});
        cursor.addRow(new Object[]{3, OWNER, "TYPE_PREVIEW", "Unknown", null, 1, null});
        ChannelRepository.Discovery result = ChannelRepository.discoverRows(() -> cursor,
                pkg -> new ChannelRepository.Owner("App", 1000, 1), 0, () -> false);
        assertEquals(ChannelRepository.Status.READY, result.status);
        assertTrue(result.channels.isEmpty());
        assertEquals(1, result.selectableChannels.size());
        assertFalse(result.selectableChannels.get(0).systemBrowsable);
        assertEquals(2, result.sources.get(0).browsableOffChannelCount);
        assertEquals(0, result.sources.get(0).searchableOffChannelCount);
        assertEquals(1, result.sources.get(0).lockedChannelCount);
        assertEquals(1, result.stats.unknownVisibility);
        assertTrue(cursor.isClosed());
    }

    private static ChannelRepository.Programs load(MatrixCursor cursor) {
        return ChannelRepository.loadRows(() -> cursor, CHANNEL,
                (owner, uri) -> PublishedContentIntents.TargetKind.VIEW, () -> false);
    }
}
