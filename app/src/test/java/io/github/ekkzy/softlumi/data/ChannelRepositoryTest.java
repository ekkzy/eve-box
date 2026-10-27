package io.github.ekkzy.softlumi.data;

import static org.junit.Assert.*;
import android.database.Cursor;
import android.os.OperationCanceledException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/** Boundary fixtures exercise the real reader without querying any installed app or provider. */
public final class ChannelRepositoryTest {
    private static final String OWNER = "org.example.unlisted.tv";
    private static final ChannelRepository.Channel CHANNEL = new ChannelRepository.Channel(7, OWNER, 1000, 9, 10, "Shelf", "Unlisted");
    private static final ChannelRepository.Owner INSTALLED = new ChannelRepository.Owner("Unlisted", 1000, 9);
    private static final ChannelRepository.TargetValidator VIEW = (owner, uri) -> PublishedContentIntents.TargetKind.VIEW;

    @Test public void arbitraryOwnerDiscoveryDoesNotDependOnKnownServicePackages() {
        FakeCursor cursor = channels(channel(10, OWNER, 1, 1), channel(20, "another.publisher.tv", 0, 1));
        ChannelRepository.Discovery result = discover(cursor);
        assertEquals(ChannelRepository.Status.READY, result.status);
        assertEquals(1, result.channels.size());
        assertEquals(2, result.selectableChannels.size());
        assertEquals("7|" + OWNER + "|1000|10", result.channels.get(0).key);
        assertEquals(2, result.sources.size());
        assertEquals(1, result.sources.get(1).hiddenChannelCount);
        assertEquals(0, result.sources.get(1).eligibleChannelCount);
        assertEquals(1, result.sources.get(1).rejectedChannelCount);
        assertEquals(1, result.sources.get(1).selectableChannelCount);
        assertEquals(1, result.sources.get(1).browsableOffChannelCount);
        assertEquals(0, result.sources.get(1).searchableOffChannelCount);
        assertTrue(cursor.closed);
    }

    @Test public void unresolvedOwnerRemainsAnHonestSourceSummaryWithoutDisplayableChannel() {
        FakeCursor cursor = channels(channel(10, OWNER, 1, 1));
        ChannelRepository.Discovery result = ChannelRepository.discoverRows(() -> cursor.value, pkg -> null, 7, () -> false);
        assertEquals(0, result.channels.size());
        assertEquals(ChannelRepository.Status.OWNER_UNAVAILABLE, result.status);
        assertFalse(result.sources.get(0).ownerAvailable);
        assertEquals(-1, result.sources.get(0).versionCode);
        assertEquals(1, result.stats.ownerUnavailable);
    }

    @Test public void nullCursorMissingColumnsAndPermissionDenialAreNotEmpty() {
        assertEquals(ChannelRepository.Status.QUERY_FAILED,
                ChannelRepository.discoverRows(() -> null, pkg -> INSTALLED, 7, () -> false).status);
        FakeCursor missing = new FakeCursor(new String[]{"_id", "package_name"}, Collections.emptyList());
        assertEquals(ChannelRepository.Status.SCHEMA_INCOMPLETE, discover(missing).status);
        assertTrue(missing.closed);
        assertEquals(ChannelRepository.Status.DENIED,
                ChannelRepository.discoverRows(() -> { throw new SecurityException(); }, pkg -> INSTALLED, 7, () -> false).status);
        assertEquals(ChannelRepository.Status.EMPTY, discover(channels()).status);
    }

    @Test public void onlyKnownBrowsableSearchablePreviewRowsAreEligible() {
        Object[] broadcast = channel(40, OWNER, 1, 1); broadcast[2] = "TYPE_DVB_T";
        ChannelRepository.Discovery result = discover(channels(channel(10, OWNER, null, 1),
                channel(20, OWNER, 1, null), channel(30, OWNER, 1, 0), broadcast));
        assertTrue(result.channels.isEmpty());
        assertTrue(result.selectableChannels.isEmpty());
        assertEquals(ChannelRepository.Status.SCHEMA_INCOMPLETE, result.status);
        assertEquals(2, result.stats.unknownVisibility);
        assertEquals(1, result.stats.hidden);
        assertEquals(4, result.stats.rejected);
    }

    @Test public void scanAtCapNeverClaimsEofOrMakesAnExtraRead() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= 257; i++) rows.add(channel(i, OWNER, 1, 1));
        FakeCursor cursor = new FakeCursor(ChannelRepository.CHANNEL_PROJECTION, rows);
        ChannelRepository.Discovery result = discover(cursor);
        assertEquals(ChannelRepository.Completeness.CAPPED, result.completeness);
        assertEquals(ChannelRepository.Status.PARTIAL, result.status);
        assertEquals(256, cursor.moves);
        assertEquals(256, result.stats.scanned);
        assertTrue(cursor.closed);
        rows = new ArrayList<>(rows.subList(0, 255));
        result = discover(new FakeCursor(ChannelRepository.CHANNEL_PROJECTION, rows));
        assertEquals(ChannelRepository.Completeness.FULL, result.completeness);
    }

    @Test public void conflictingChannelCopiesAreBothDiscardedEvenWhenOneIsHidden() {
        FakeCursor cursor = channels(channel(10, OWNER, 1, 1), channel(10, OWNER, 0, 1));
        ChannelRepository.Discovery result = discover(cursor);
        assertTrue(result.channels.isEmpty());
        assertTrue(result.selectableChannels.isEmpty());
        assertEquals(1, result.stats.duplicateConflicts);
        assertEquals(2, result.sources.get(0).rejectedChannelCount);
    }

    @Test public void unknownWeightsSortAfterKnownAndEqualWeightsHaveStableIds() {
        ChannelRepository.Programs result = load(programs(program(102, null), program(103, -1L), program(101, -1L)), VIEW);
        assertEquals(Arrays.asList(101L, 103L, 102L), ids(result));
        assertNull(result.items.get(2).weight);
    }

    @Test public void globallyUnselectedSearchableChannelIsAvailableOnlyAsALocalChoice() {
        ChannelRepository.Discovery result = discover(channels(channel(10, OWNER, 0, 1)));
        assertEquals(ChannelRepository.Status.READY, result.status);
        assertEquals(ChannelRepository.Completeness.FULL, result.completeness);
        assertTrue("System-visible compatibility list stays empty", result.channels.isEmpty());
        assertEquals(1, result.selectableChannels.size());
        ChannelRepository.Channel selectable = result.selectableChannels.get(0);
        assertFalse(selectable.systemBrowsable);
        assertEquals(CHANNEL.key, selectable.key);
        assertEquals(1, result.sources.get(0).selectableChannelCount);
        assertEquals(0, result.sources.get(0).eligibleChannelCount);
    }

    @Test public void searchableZeroIsNotSelectableEvenWhenSystemRowIsVisible() {
        ChannelRepository.Discovery result = discover(channels(channel(10, OWNER, 1, 0), channel(11, OWNER, 0, 0)));
        assertTrue(result.selectableChannels.isEmpty());
        assertEquals(2, result.sources.get(0).searchableOffChannelCount);
        assertEquals(1, result.sources.get(0).browsableOffChannelCount);
        assertEquals(2, result.sources.get(0).hiddenChannelCount);
    }

    @Test public void validSelectableRowSurvivesAnIndependentUnknownVisibilityRow() {
        ChannelRepository.Discovery result = discover(channels(channel(10, OWNER, 0, 1), channel(11, OWNER, null, 1)));
        assertEquals(ChannelRepository.Status.READY, result.status);
        assertEquals(1, result.selectableChannels.size());
        assertEquals(1, result.stats.unknownVisibility);
        assertEquals(ChannelRepository.Completeness.FULL, result.completeness);
    }

    @Test public void localSelectionDoesNotReviveIndividuallyHiddenPrograms() {
        ChannelRepository.Channel selected = new ChannelRepository.Channel(7, OWNER, 1000, 9, 10, "Shelf", "App", false);
        Object[] hidden = program(101, 1L); hidden[5] = 0;
        FakeCursor cursor = programs(hidden, program(102, 1L));
        ChannelRepository.Programs result = ChannelRepository.loadRows(() -> cursor.value, selected, VIEW, () -> false);
        assertEquals(Collections.singletonList(102L), ids(result));
        assertEquals(1, result.stats.hidden);
    }

    @Test public void optionalKnownLockIsExcludedAndCannotBeMistakenForSelectionConsent() {
        Object[] locked = Arrays.copyOf(channel(10, OWNER, 0, 1), 7); locked[6] = 1;
        FakeCursor cursor = new FakeCursor(ChannelRepository.CHANNEL_WITH_LOCKED_PROJECTION, Collections.singletonList(locked));
        ChannelRepository.Discovery result = discover(cursor);
        assertTrue(result.selectableChannels.isEmpty());
        assertEquals(1, result.stats.locked);
        assertEquals(1, result.sources.get(0).lockedChannelCount);
    }

    @Test public void optionalLockProjectionRetriesOnlyOnceAndKeepsBothRequiredVisibilityColumns() {
        List<List<String>> projections = new ArrayList<>();
        ChannelRepository.queryChannelsWithOptionalLock(projection -> {
            projections.add(Arrays.asList(projection.clone()));
            if (projections.size() == 1) throw new IllegalArgumentException("Optional lock unsupported");
            assertTrue(Arrays.asList(projection).contains("browsable"));
            assertTrue(Arrays.asList(projection).contains("searchable"));
            return null;
        }, () -> false);
        assertEquals(2, projections.size());
        assertTrue(projections.get(0).contains("locked"));
        assertFalse(projections.get(1).contains("locked"));
        int[] calls = {0};
        try {
            ChannelRepository.queryChannelsWithOptionalLock(projection -> { calls[0]++; throw new SecurityException(); }, () -> false);
            fail("Access denial must not retry a different projection");
        } catch (SecurityException expected) { assertEquals(1, calls[0]); }
    }

    @Test public void displayLimitIsIndependentFromFullScanAndEligibleCount() {
        for (int count : new int[]{50, 51}) {
            List<Object[]> rows = new ArrayList<>();
            for (int i = 1; i <= count; i++) rows.add(program(i, (long) i));
            ChannelRepository.Programs result = load(new FakeCursor(ChannelRepository.PROGRAM_PROJECTION, rows), VIEW);
            assertEquals(ChannelRepository.Completeness.FULL, result.completeness);
            assertEquals(count, result.stats.eligible);
            assertEquals(50, result.stats.retained); assertEquals(50, result.items.size());
            assertEquals(0, result.stats.rejected);
            assertEquals(count > 50, result.stats.displayLimited);
            assertEquals(count, result.items.get(0).id);
            assertEquals(count - 49, result.items.get(49).id);
        }
    }

    @Test public void thirtyPublishedCardsRemainAvailableInPublisherWeightOrder() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= 30; i++) rows.add(program(i, (long) i));
        ChannelRepository.Programs result = load(new FakeCursor(ChannelRepository.PROGRAM_PROJECTION, rows), VIEW);
        assertEquals(ChannelRepository.Status.READY, result.status);
        assertEquals(ChannelRepository.Completeness.FULL, result.completeness);
        assertEquals(30, result.stats.scanned); assertEquals(30, result.stats.eligible);
        assertEquals(30, result.stats.retained); assertEquals(30, result.items.size());
        assertFalse(result.stats.displayLimited);
        for (int index = 0; index < 30; index++) {
            ChannelRepository.Program item = result.items.get(index); long expectedId = 30L - index;
            assertEquals(expectedId, item.id); assertEquals(Long.valueOf(expectedId), item.weight);
            assertEquals("Title " + expectedId, item.title);
        }
    }

    @Test public void cappedEmptyProgramsCannotClaimACompleteEmptySource() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= 101; i++) { Object[] row = program(i, 1L); row[5] = 0; rows.add(row); }
        FakeCursor cursor = new FakeCursor(ChannelRepository.PROGRAM_PROJECTION, rows);
        ChannelRepository.Programs result = load(cursor, VIEW);
        assertEquals(ChannelRepository.Status.PARTIAL, result.status);
        assertEquals(ChannelRepository.Completeness.CAPPED, result.completeness);
        assertTrue(result.items.isEmpty());
        assertEquals(100, result.stats.scanned);
        assertEquals(100, cursor.moves);
    }

    @Test public void conflictingProgramCopiesStayExcludedAfterAThirdCopy() {
        Object[] changed = program(101, 1L); changed[3] = "Different content";
        ChannelRepository.Programs result = load(programs(program(101, 1L), changed, program(101, 1L), program(102, 1L)), VIEW);
        assertEquals(Collections.singletonList(102L), ids(result));
        assertEquals(1, result.stats.duplicateConflicts);
        assertEquals(3, result.stats.rejected);
    }

    @Test public void identicalCopiesCollapseWithoutInflatingEligibleCount() {
        ChannelRepository.Programs result = load(programs(program(101, 1L), program(101, 1L)), VIEW);
        assertEquals(1, result.stats.eligible);
        assertEquals(1, result.stats.rejected);
        assertEquals(0, result.stats.duplicateConflicts);
    }

    @Test public void ownerAndChannelMismatchesAreFilteredBeforeIntentResolution() {
        Object[] wrongOwner = program(101, 1L); wrongOwner[1] = "another.publisher.tv";
        Object[] wrongChannel = program(102, 1L); wrongChannel[2] = 11;
        ChannelRepository.Programs result = load(programs(wrongOwner, wrongChannel), (owner, uri) -> {
            fail("An unrelated row must not reach the intent resolver"); return null;
        });
        assertTrue(result.items.isEmpty());
    }

    @Test public void appOnlyIsNotRetainedAsAContentCard() {
        ChannelRepository.Programs result = load(programs(program(101, 1L)),
                (owner, uri) -> PublishedContentIntents.TargetKind.APP_ONLY);
        assertTrue(result.items.isEmpty());
        assertEquals(1, result.stats.appOnlyIntents);
    }

    @Test public void hugeUrisAreRejectedWithoutTruncationAndPosterFailureKeepsContent() {
        Object[] hugeIntent = program(101, 1L); hugeIntent[4] = "v".repeat(16385);
        Object[] hugePoster = program(102, 1L); hugePoster[8] = "https://example.test/" + "v".repeat(4096);
        int[] resolutions = {0};
        ChannelRepository.Programs result = load(programs(hugeIntent, hugePoster), (owner, uri) -> {
            resolutions[0]++; assertTrue(uri.length() < 16384); return PublishedContentIntents.TargetKind.VIEW;
        });
        assertEquals(1, resolutions[0]);
        assertEquals(Collections.singletonList(102L), ids(result));
        assertEquals("", result.items.get(0).posterUri);
        assertEquals(1, result.stats.invalidIntents);
    }

    @Test public void textLimitNeverSplitsASurrogatePairAndUnknownTypeIsNotMovieZero() {
        Object[] row = program(101, 1L); row[3] = "a".repeat(255) + "\uD83D\uDE80"; row[10] = null;
        ChannelRepository.Program item = load(programs(row), VIEW).items.get(0);
        assertEquals(255, item.title.length());
        assertNull(item.type);
    }

    @Test public void missingPublisherGenreNeverUsesTitleDescriptionOrTypeAsAReplacement() {
        Object[] row = program(101, 1L); row[3] = "Science fiction film"; row[7] = "Comedy and drama";
        assertEquals("", load(programs(row), VIEW).items.get(0).genre);
        List<Object[]> rows = new ArrayList<>();
        for (Object genre : new Object[]{null, "", "  \t "}) {
            Object[] value = Arrays.copyOf(program(101 + rows.size(), 1L), 13); value[12] = genre; rows.add(value);
        }
        ChannelRepository.Programs result = load(new FakeCursor(ChannelRepository.PROGRAM_WITH_GENRE_PROJECTION, rows), VIEW);
        assertEquals(ChannelRepository.Status.READY, result.status); assertEquals(3, result.items.size());
        for (ChannelRepository.Program item : result.items) assertEquals("", item.genre);
    }

    @Test public void publisherGenreKeepsLocalizedTextAndHasACodePointLimit() {
        Object[] localized = Arrays.copyOf(program(101, 1L), 13); localized[12] = "  Драма, научная фантастика / Sci-Fi  ";
        Object[] longGenre = Arrays.copyOf(program(102, 1L), 13); longGenre[12] = "Ж".repeat(255) + "\uD83D\uDE80" + "extra";
        ChannelRepository.Programs result = load(new FakeCursor(ChannelRepository.PROGRAM_WITH_GENRE_PROJECTION,
                Arrays.asList(localized, longGenre)), VIEW);
        assertEquals("Драма, научная фантастика / Sci-Fi", result.items.get(0).genre);
        String genre = result.items.get(1).genre;
        assertEquals(256, genre.codePointCount(0, genre.length()));
        assertEquals(257, genre.length()); assertTrue(genre.endsWith("\uD83D\uDE80"));
    }

    @Test public void conflictingGenreCopiesDiscardBothPrograms() {
        Object[] first = Arrays.copyOf(program(101, 1L), 13); first[12] = "Драма";
        Object[] second = first.clone(); second[12] = "Комедия";
        ChannelRepository.Programs result = load(new FakeCursor(ChannelRepository.PROGRAM_WITH_GENRE_PROJECTION,
                Arrays.asList(first, second)), VIEW);
        assertTrue(result.items.isEmpty()); assertEquals(1, result.stats.duplicateConflicts);
    }

    @Test public void unsupportedGenreFallsBackWithoutDroppingExistingArtworkAndWeight() {
        FakeCursor cursor = programs(program(101, 9L));
        List<List<String>> requested = new ArrayList<>(); List<String> sorts = new ArrayList<>();
        Cursor result = ChannelRepository.queryProgramsWithOptionalGenre((projection, sort) -> {
            requested.add(Arrays.asList(projection)); sorts.add(sort);
            if (requested.size() == 1) throw new IllegalArgumentException("Unknown genre column");
            return cursor.value;
        }, () -> false);
        assertSame(cursor.value, result); assertEquals(2, requested.size());
        assertTrue(requested.get(0).contains("genre")); assertFalse(requested.get(1).contains("genre"));
        assertTrue(requested.get(1).containsAll(Arrays.asList("poster_art_uri", "weight", "browsable", "searchable")));
        assertEquals(Arrays.asList("weight DESC", "weight DESC"), sorts);
        ChannelRepository.Program item = ChannelRepository.loadRows(() -> result, CHANNEL, VIEW, () -> false).items.get(0);
        assertEquals("", item.genre); assertEquals(Long.valueOf(9), item.weight);
        assertEquals("https://example.test/101.png", item.posterUri); assertTrue(cursor.closed);
    }

    @Test public void metadataFallbackStaysBoundedAndDoesNotRetryDeniedOrCancelledReads() {
        int[] calls = {0};
        ChannelRepository.queryProgramsWithOptionalGenre((projection, sort) -> {
            calls[0]++;
            if (calls[0] < 3) throw new IllegalArgumentException("Optional metadata unavailable");
            assertArrayEquals(ChannelRepository.PROGRAM_REQUIRED, projection); assertNull(sort); return null;
        }, () -> false);
        assertEquals(3, calls[0]);
        calls[0] = 0;
        try {
            ChannelRepository.queryProgramsWithOptionalGenre((projection, sort) -> { calls[0]++; throw new SecurityException(); }, () -> false);
            fail("Permission denial is not a projection retry");
        } catch (SecurityException expected) { assertEquals(1, calls[0]); }
        calls[0] = 0; AtomicBoolean cancelled = new AtomicBoolean();
        try {
            ChannelRepository.queryProgramsWithOptionalGenre((projection, sort) -> {
                calls[0]++; cancelled.set(true); throw new IllegalArgumentException("Unknown genre");
            }, cancelled::get);
            fail("Cancellation must stop before a reduced query");
        } catch (OperationCanceledException expected) { assertEquals(1, calls[0]); }
    }

    @Test public void cancellationDuringValidationClosesCursorAndDiscardsPartialItems() {
        AtomicBoolean cancelled = new AtomicBoolean();
        FakeCursor cursor = programs(program(101, 1L), program(102, 1L));
        ChannelRepository.Programs result = ChannelRepository.loadRows(() -> cursor.value, CHANNEL, (owner, uri) -> {
            cancelled.set(true); return PublishedContentIntents.TargetKind.VIEW;
        }, cancelled::get);
        assertEquals(ChannelRepository.Status.CANCELLED, result.status);
        assertEquals(ChannelRepository.Completeness.UNKNOWN, result.completeness);
        assertTrue(result.items.isEmpty()); assertTrue(cursor.closed);
    }

    @Test public void returnedCollectionsCannotBeMutatedByTheUi() {
        ChannelRepository.Programs result = load(programs(program(101, 1L)), VIEW);
        try { result.items.clear(); fail("Snapshot must be immutable"); }
        catch (UnsupportedOperationException expected) { }
    }

    private static ChannelRepository.Discovery discover(FakeCursor cursor) {
        return ChannelRepository.discoverRows(() -> cursor.value, pkg -> INSTALLED, 7, () -> false);
    }
    private static ChannelRepository.Programs load(FakeCursor cursor, ChannelRepository.TargetValidator validator) {
        return ChannelRepository.loadRows(() -> cursor.value, CHANNEL, validator, () -> false);
    }
    private static List<Long> ids(ChannelRepository.Programs result) {
        List<Long> ids = new ArrayList<>(); for (ChannelRepository.Program item : result.items) ids.add(item.id); return ids;
    }
    private static Object[] channel(long id, String pkg, Object browsable, Object searchable) {
        return new Object[]{id, pkg, "TYPE_PREVIEW", "Shelf", browsable, searchable};
    }
    private static Object[] program(long id, Long weight) {
        return new Object[]{id, OWNER, 10, "Title " + id, "example://play/" + id, 1, 1,
                "Description", "https://example.test/" + id + ".png", weight, 0, 0};
    }
    private static FakeCursor channels(Object[]... rows) { return new FakeCursor(ChannelRepository.CHANNEL_PROJECTION, Arrays.asList(rows)); }
    private static FakeCursor programs(Object[]... rows) { return new FakeCursor(ChannelRepository.PROGRAM_PROJECTION, Arrays.asList(rows)); }
    private static final class FakeCursor {
        final Cursor value; int position = -1, moves; boolean closed;
        FakeCursor(String[] columns, List<Object[]> rows) {
            value = (Cursor) Proxy.newProxyInstance(Cursor.class.getClassLoader(), new Class<?>[]{Cursor.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getColumnIndex": return Arrays.asList(columns).indexOf(args[0]);
                    case "moveToNext": moves++; return ++position < rows.size();
                    case "isNull": return rows.get(position)[(int) args[0]] == null;
                    case "getString": Object raw = rows.get(position)[(int) args[0]]; return raw == null ? null : raw.toString();
                    case "close": closed = true; return null;
                    default: throw new AssertionError("Unexpected Cursor operation: " + method.getName());
                }
            });
        }
    }
}
