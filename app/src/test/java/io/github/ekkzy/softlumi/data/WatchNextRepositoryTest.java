package io.github.ekkzy.softlumi.data;

import static org.junit.Assert.*;
import android.database.Cursor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class WatchNextRepositoryTest {
    private static final long NOW = 1800000000000L;
    private static WatchNextRepository.Entry entry(String id, long time, boolean browsable, boolean searchable) {
        return new WatchNextRepository.Entry(id, "Title " + id, "Description", "provider.app",
                "provider://play/" + id, "", 250, 1000, time, browsable, searchable);
    }
    private static WatchNextRepository repository(WatchNextRepository.Source source) {
        return new WatchNextRepository(source, item -> !item.id.equals("uninstalled"), () -> NOW);
    }
    @Test public void deniedProviderIsDistinctFromMissingProvider() {
        assertEquals(WatchNextRepository.Status.DENIED, repository(() -> {
            throw new SecurityException("permission denied");
        }).load().status);
        assertEquals(WatchNextRepository.Status.UNAVAILABLE, repository(() -> {
            throw new WatchNextRepository.ProviderUnavailableException("not installed");
        }).load().status);
    }
    @Test public void nullCursorAndEmptyRowsAreDistinct() {
        assertEquals(WatchNextRepository.Status.UNAVAILABLE, repository(() -> null).load().status);
        assertEquals(WatchNextRepository.Status.EMPTY, repository(Collections::emptyList).load().status);
    }
    @Test public void filtersStaleHiddenNonSearchableAndUnlaunchableRows() {
        List<WatchNextRepository.Entry> fixtures = Arrays.asList(
                entry("stale", NOW - WatchNextRepository.MAX_AGE_MILLIS - 1, true, true),
                entry("hidden", NOW, false, true), entry("private", NOW, true, false),
                entry("uninstalled", NOW, true, true), entry("future", NOW + 600000, true, true),
                entry("valid", NOW - 1000, true, true));
        WatchNextRepository.Result result = repository(() -> fixtures).load();
        assertEquals(WatchNextRepository.Status.ITEMS, result.status);
        assertEquals(1, result.items.size());
        assertEquals("valid", result.items.get(0).id);
        assertEquals(25, result.items.get(0).progressPercent);
    }
    @Test public void sortsByEngagementDeduplicatesAndCapsDisplay() {
        List<WatchNextRepository.Entry> fixtures = new ArrayList<>();
        for (int index = 0; index < 40; index++) fixtures.add(entry("id" + index, NOW - index, true, true));
        fixtures.add(entry("id0", NOW, true, true));
        Collections.reverse(fixtures);
        WatchNextRepository.Result result = repository(() -> fixtures).load();
        assertEquals(24, result.items.size());
        assertEquals("id0", result.items.get(0).id);
        assertEquals("id23", result.items.get(23).id);
    }
    @Test public void unexpectedProviderFailureHasHonestErrorState() {
        WatchNextRepository.Result result = repository(() -> { throw new IllegalStateException("bad cursor"); }).load();
        assertEquals(WatchNextRepository.Status.ERROR, result.status);
        assertTrue(result.items.isEmpty());
    }
    @Test public void oversizedProtocolFieldsAreRejectedBeforeResolvingAnyTarget() {
        List<WatchNextRepository.Entry> rows = Arrays.asList(
                new WatchNextRepository.Entry("uri", "Title", "", "provider.app",
                        "https://provider.invalid/" + "a".repeat(16384), "", 0, 100, NOW, true, true),
                new WatchNextRepository.Entry("id".repeat(257), "Title", "", "provider.app",
                        "provider://play/id", "", 0, 100, NOW, true, true),
                new WatchNextRepository.Entry("owner", "Title", "", "a".repeat(257),
                        "provider://play/id", "", 0, 100, NOW, true, true));
        WatchNextRepository repository = new WatchNextRepository(() -> rows, item -> {
            fail("Clipping a protocol field must never produce a different valid launch target"); return true;
        }, () -> NOW);
        assertEquals(WatchNextRepository.Status.EMPTY, repository.load().status);
        assertEquals("", rows.get(0).intentUri);
        assertEquals("", rows.get(1).id);
        assertEquals("", rows.get(2).packageName);
    }
    @Test public void artworkUriIsExactOrAbsentWhileDisplayTextCanBeShortened() {
        String exact = "https://provider.invalid/poster?id=1";
        WatchNextRepository.Entry valid = new WatchNextRepository.Entry("id", "t".repeat(300), "",
                "provider.app", "provider://play/id", exact, 0, 100, NOW, true, true);
        WatchNextRepository.Entry oversized = new WatchNextRepository.Entry("id", "Title", "",
                "provider.app", "provider://play/id", exact + "a".repeat(4096), 0, 100, NOW, true, true);
        assertEquals(exact, valid.posterUri);
        assertEquals(256, valid.title.length());
        assertEquals("", oversized.posterUri);
    }
    @Test public void progressHandlesUnknownAndOutOfRangeValues() {
        assertEquals(0, new WatchNextRepository.Entry("id", "title", "", "app", "url", "", -1, 100, NOW, true, true).progressPercent);
        assertEquals(100, new WatchNextRepository.Entry("id", "title", "", "app", "url", "", 200, 100, NOW, true, true).progressPercent);
        assertEquals(0, new WatchNextRepository.Entry("id", "title", "", "app", "url", "", 200, 0, NOW, true, true).progressPercent);
        assertFalse(new WatchNextRepository.Entry("id", "title", "", "app", "url", "", -1, 100, NOW, true, true).hasPlaybackProgress);
        assertFalse(new WatchNextRepository.Entry("id", "title", "", "app", "url", "", 200, 0, NOW, true, true).hasPlaybackProgress);
        assertTrue(new WatchNextRepository.Entry("id", "title", "", "app", "url", "", 0, 100, NOW, true, true).hasPlaybackProgress);
    }
    @Test public void continueReasonIsRequiredSeparatelyFromMovieOrEpisodeKind() {
        List<WatchNextRepository.Entry> rows = new ArrayList<>();
        for (WatchNextRepository.WatchType watchType : WatchNextRepository.WatchType.values()) {
            rows.add(typed(watchType.name(), WatchNextRepository.Kind.MOVIE, watchType));
        }
        rows.add(typed("episode", WatchNextRepository.Kind.SHOW, WatchNextRepository.WatchType.CONTINUE));
        rows.add(typed("unknown-kind", WatchNextRepository.Kind.UNKNOWN, WatchNextRepository.WatchType.CONTINUE));
        rows.add(typed("null-type", WatchNextRepository.Kind.SHOW, null));
        WatchNextRepository.Result result = repository(() -> rows).load();
        assertEquals(WatchNextRepository.Status.ITEMS, result.status);
        assertEquals(3, result.items.size());
        for (WatchNextRepository.Entry row : result.items)
            assertEquals(WatchNextRepository.WatchType.CONTINUE, row.watchType);
        assertEquals("CONTINUE", result.items.get(0).id);
        assertEquals("episode", result.items.get(1).id);
        assertEquals("unknown-kind", result.items.get(2).id);
    }
    @Test public void unknownAndOtherWatchReasonsAreNotResolvedAsContinuationTargets() {
        List<WatchNextRepository.Entry> rows = Arrays.asList(
                typed("unknown", WatchNextRepository.Kind.MOVIE, WatchNextRepository.WatchType.UNKNOWN),
                typed("next", WatchNextRepository.Kind.SHOW, WatchNextRepository.WatchType.NEXT),
                typed("new", WatchNextRepository.Kind.SHOW, WatchNextRepository.WatchType.NEW),
                typed("watchlist", WatchNextRepository.Kind.MOVIE, WatchNextRepository.WatchType.WATCHLIST));
        WatchNextRepository repository = new WatchNextRepository(() -> rows, item -> {
            fail("Non-continuation rows must be excluded before resolving their launch target"); return true;
        }, () -> NOW);
        assertEquals(WatchNextRepository.Status.EMPTY, repository.load().status);
    }
    @Test public void watchTypeRecognizesOnlyExplicitProtocolValues() {
        assertEquals(WatchNextRepository.WatchType.CONTINUE, WatchNextRepository.parseWatchType("0"));
        assertEquals(WatchNextRepository.WatchType.NEXT, WatchNextRepository.parseWatchType("1"));
        assertEquals(WatchNextRepository.WatchType.NEW, WatchNextRepository.parseWatchType("2"));
        assertEquals(WatchNextRepository.WatchType.WATCHLIST, WatchNextRepository.parseWatchType(" 3 "));
        for (String raw : new String[]{null, "", " ", "-1", "4", "0.0", "continue", "2147483648"})
            assertEquals("Invalid watch type=" + raw, WatchNextRepository.WatchType.UNKNOWN,
                    WatchNextRepository.parseWatchType(raw));
    }
    private static WatchNextRepository.Entry typed(String id, WatchNextRepository.Kind kind,
            WatchNextRepository.WatchType watchType) {
        return new WatchNextRepository.Entry(id, "Title " + id, "", "provider.app",
                "provider://play/" + id, "", 250, 1000, NOW, true, true, kind, watchType);
    }
    @Test public void onlyExplicitMovieAndSeriesSeasonEpisodeTypesAreClassified() {
        assertEquals(WatchNextRepository.Kind.MOVIE, WatchNextRepository.parseProviderKind("0"));
        for (String type : new String[]{"1", "2", "3", " 3 "})
            assertEquals(type, WatchNextRepository.Kind.SHOW, WatchNextRepository.parseProviderKind(type));
        for (String type : new String[]{null, "", " ", "movie", "Netflix", "1.0", "NaN", "-1", "2147483648"})
            assertEquals("Unknown type=" + type, WatchNextRepository.Kind.UNKNOWN, WatchNextRepository.parseProviderKind(type));
        for (int type = 4; type <= 11; type++)
            assertEquals("Other content type=" + type, WatchNextRepository.Kind.UNKNOWN, WatchNextRepository.parseProviderKind(Integer.toString(type)));
    }
    @Test public void optionalKindPreservesLegacyFixturesAndDoesNotInferFromTitleOrProvider() {
        WatchNextRepository.Entry legacy = new WatchNextRepository.Entry("legacy", "Movie episode", "Series",
                "movie.provider", "provider://movie", "", 0, 0, NOW, true, true);
        assertEquals(WatchNextRepository.Kind.UNKNOWN, legacy.kind);
        WatchNextRepository.Entry nullKind = new WatchNextRepository.Entry("null", "Movie", "", "provider.app",
                "provider://null", "", 0, 0, NOW, true, true, null);
        assertEquals(WatchNextRepository.Kind.UNKNOWN, nullKind.kind);
    }
    @Test public void missingAndNullCursorTypesNeverBecomeMovieZero() {
        assertEquals(WatchNextRepository.Kind.UNKNOWN, WatchNextRepository.readProviderKind(typeCursor(-1, false, "0")));
        assertEquals(WatchNextRepository.Kind.UNKNOWN, WatchNextRepository.readProviderKind(typeCursor(0, true, "0")));
        assertEquals(WatchNextRepository.Kind.MOVIE, WatchNextRepository.readProviderKind(typeCursor(0, false, "0")));
        assertEquals(WatchNextRepository.Kind.SHOW, WatchNextRepository.readProviderKind(typeCursor(0, false, "3")));
    }
    @Test public void classificationRetainsExplicitKindsAndUnknownRowsWithOriginalOrdering() {
        WatchNextRepository.Entry movie = new WatchNextRepository.Entry("movie", "Episode title", "", "provider.app",
                "provider://movie", "", 250, 1000, NOW - 1, true, true, WatchNextRepository.Kind.MOVIE);
        WatchNextRepository.Entry show = new WatchNextRepository.Entry("show", "Movie title", "", "provider.app",
                "provider://show", "", 250, 1000, NOW - 2, true, true, WatchNextRepository.Kind.SHOW);
        WatchNextRepository.Entry unknown = entry("unknown", NOW, true, true);
        WatchNextRepository.Result result = repository(() -> Arrays.asList(movie, show, unknown)).load();
        assertEquals(3, result.items.size());
        assertSame(unknown, result.items.get(0)); assertSame(movie, result.items.get(1)); assertSame(show, result.items.get(2));
        assertEquals(WatchNextRepository.Kind.UNKNOWN, result.items.get(0).kind);
        assertEquals(WatchNextRepository.Kind.MOVIE, result.items.get(1).kind);
        assertEquals(WatchNextRepository.Kind.SHOW, result.items.get(2).kind);
    }
    @Test public void unsupportedOptionalTypeFallsBackOnceWithoutMutatingLegacyProjection() {
        String[] legacy = {"_id", "title"}; List<List<String>> queries = new ArrayList<>();
        assertNull(WatchNextRepository.queryWithOptionalType(projection -> {
            queries.add(Arrays.asList(projection.clone()));
            if (queries.size() == 1) throw new IllegalArgumentException("OEM does not expose type");
            return null;
        }, legacy));
        assertEquals(Arrays.asList("_id", "title", "type"), queries.get(0));
        assertEquals(Arrays.asList("_id", "title"), queries.get(1));
        assertArrayEquals(new String[]{"_id", "title"}, legacy);
    }
    @Test public void successfulTypeQueryAndPermissionDenialNeverRetry() {
        int[] calls = {0};
        WatchNextRepository.queryWithOptionalType(projection -> { calls[0]++; return null; }, new String[]{"_id"});
        assertEquals(1, calls[0]);
        calls[0] = 0;
        try {
            WatchNextRepository.queryWithOptionalType(projection -> { calls[0]++; throw new SecurityException("denied"); }, new String[]{"_id"});
            fail("Permission denial must propagate without a fallback query");
        } catch (SecurityException expected) { assertEquals(1, calls[0]); }
    }
    private static Cursor typeCursor(int columnIndex, boolean nullValue, String raw) {
        return (Cursor) Proxy.newProxyInstance(Cursor.class.getClassLoader(), new Class<?>[]{Cursor.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getColumnIndex": return columnIndex;
                        case "isNull": return nullValue;
                        case "getString":
                            if (columnIndex < 0 || nullValue) throw new AssertionError("No value may be read from a missing/null type");
                            return raw;
                        default: throw new AssertionError("Unexpected cursor method: " + method.getName());
                    }
                });
    }
}
