package io.github.ekkzy.softlumi.data;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OperationCanceledException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Read-only Watch Next adapter. A denied provider is an ordinary empty-state reason. */
public final class WatchNextRepository {
    public static final String READ_TV_LISTINGS_PERMISSION = "android.permission.READ_TV_LISTINGS";
    public static final int MAX_ITEMS = 24;
    public static final int MAX_ROWS = 256;
    public static final long MAX_AGE_MILLIS = 90L * 24 * 60 * 60 * 1000;
    public enum Status { ITEMS, EMPTY, DENIED, UNAVAILABLE, ERROR }
    public enum Kind { UNKNOWN, MOVIE, SHOW }
    /** The reason a provider placed a program in Watch Next, independent of its content kind. */
    public enum WatchType { UNKNOWN, CONTINUE, NEXT, NEW, WATCHLIST }

    public static final class Entry {
        public final String id;
        public final String title;
        public final String description;
        public final String packageName;
        public final String intentUri;
        public final String posterUri;
        public final int progressPercent;
        public final boolean hasPlaybackProgress;
        public final long lastEngagementTimeMillis;
        public final boolean browsable;
        public final boolean searchable;
        /** Only explicit provider content kinds are classified; this does not infer a watch type. */
        public final Kind kind;
        public final WatchType watchType;

        public Entry(String id, String title, String description, String packageName,
                String intentUri, String posterUri, long playbackPositionMillis,
                long durationMillis, long lastEngagementTimeMillis, boolean browsable,
                boolean searchable) {
            this(id, title, description, packageName, intentUri, posterUri, playbackPositionMillis,
                    durationMillis, lastEngagementTimeMillis, browsable, searchable, Kind.UNKNOWN);
        }

        public Entry(String id, String title, String description, String packageName,
                String intentUri, String posterUri, long playbackPositionMillis,
                long durationMillis, long lastEngagementTimeMillis, boolean browsable,
                boolean searchable, Kind kind) {
            // Existing callers explicitly construct continuation entries. Provider rows always use
            // the full constructor below, so a missing provider type never acquires this default.
            this(id, title, description, packageName, intentUri, posterUri, playbackPositionMillis,
                    durationMillis, lastEngagementTimeMillis, browsable, searchable, kind, WatchType.CONTINUE);
        }

        public Entry(String id, String title, String description, String packageName,
                String intentUri, String posterUri, long playbackPositionMillis,
                long durationMillis, long lastEngagementTimeMillis, boolean browsable,
                boolean searchable, Kind kind, WatchType watchType) {
            this.id = protocolValue(id, 512);
            this.title = bounded(title, 256);
            this.description = bounded(description, 1024);
            this.packageName = protocolValue(packageName, 256);
            this.intentUri = protocolValue(intentUri, 16384);
            this.posterUri = protocolValue(posterUri, 4096);
            this.progressPercent = durationMillis <= 0 ? 0 : (int) Math.round(
                    Math.max(0d, Math.min(1d, playbackPositionMillis / (double) durationMillis)) * 100d);
            this.hasPlaybackProgress = durationMillis > 0 && playbackPositionMillis >= 0;
            this.lastEngagementTimeMillis = lastEngagementTimeMillis;
            this.browsable = browsable;
            this.searchable = searchable;
            this.kind = kind == null ? Kind.UNKNOWN : kind;
            this.watchType = watchType == null ? WatchType.UNKNOWN : watchType;
        }
    }

    public static final class Result {
        public final Status status;
        public final List<Entry> items;
        public final String message;
        private Result(Status status, List<Entry> items, String message) {
            this.status = status;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.message = message;
        }
    }

    @FunctionalInterface public interface Source { List<Entry> read() throws Exception; }
    @FunctionalInterface public interface TargetValidator { boolean canLaunch(Entry entry); }
    @FunctionalInterface public interface Clock { long nowMillis(); }
    @FunctionalInterface interface CursorQuery { Cursor query(String[] projection); }
    public static final class ProviderUnavailableException extends Exception {
        public ProviderUnavailableException(String message) { super(message); }
    }

    private final Source source;
    private final TargetValidator validator;
    private final Clock clock;

    public WatchNextRepository() { this(null, null, System::currentTimeMillis); }
    /** Injectable source and target boundary for fixtures; no Android provider is involved. */
    public WatchNextRepository(Source source, TargetValidator validator, Clock clock) {
        this.source = source;
        this.validator = validator;
        this.clock = clock;
    }

    public Result load(Context context) {
        return classify(() -> readProvider(context), entry -> resolveSafeIntent(context, entry) != null,
                clock.nowMillis());
    }

    public Result load() {
        if (source == null || validator == null)
            return result(Status.UNAVAILABLE, "Watch Next provider unavailable");
        return classify(source, validator, clock.nowMillis());
    }

    private static Result classify(Source source, TargetValidator validator, long now) {
        try {
            List<Entry> rows = source.read();
            if (rows == null) return result(Status.UNAVAILABLE, "Watch Next provider unavailable");
            ArrayList<Entry> items = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int visited = 0;
            for (Entry entry : rows) {
                if (++visited > MAX_ROWS) break;
                if (entry == null || entry.watchType != WatchType.CONTINUE
                        || !entry.browsable || !entry.searchable || entry.id.isEmpty()
                        || entry.title.isEmpty() || entry.packageName.isEmpty() || entry.intentUri.isEmpty()
                        || entry.lastEngagementTimeMillis <= 0
                        || entry.lastEngagementTimeMillis > now + 5 * 60 * 1000L
                        || now - entry.lastEngagementTimeMillis > MAX_AGE_MILLIS
                        || !validator.canLaunch(entry) || !seen.add(entry.packageName + ":" + entry.id)) continue;
                items.add(entry);
            }
            items.sort(Comparator.comparingLong((Entry item) -> item.lastEngagementTimeMillis).reversed()
                    .thenComparing(item -> item.id));
            if (items.size() > MAX_ITEMS) items.subList(MAX_ITEMS, items.size()).clear();
            return new Result(items.isEmpty() ? Status.EMPTY : Status.ITEMS, items,
                    items.isEmpty() ? "No available Watch Next items" : "");
        } catch (SecurityException denied) {
            return result(Status.DENIED, "This device does not allow access to Watch Next");
        } catch (ProviderUnavailableException | IllegalArgumentException | OperationCanceledException unavailable) {
            return result(Status.UNAVAILABLE, "Watch Next provider unavailable");
        } catch (Exception error) {
            return result(Status.ERROR, "Could not load Watch Next");
        }
    }

    private static Result result(Status status, String message) {
        return new Result(status, Collections.emptyList(), message);
    }

    public static boolean isReadPermissionAvailable(Context context) {
        try {
            context.getPackageManager().getPermissionInfo(READ_TV_LISTINGS_PERMISSION, 0);
            return true;
        } catch (PackageManager.NameNotFoundException missing) {
            return false;
        }
    }

    public static boolean hasReadPermission(Context context) {
        return context.checkSelfPermission(READ_TV_LISTINGS_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    private static List<Entry> readProvider(Context context) throws ProviderUnavailableException {
        PackageManager pm = context.getPackageManager();
        if (pm.resolveContentProvider(TvContract.AUTHORITY, 0) == null)
            throw new ProviderUnavailableException("Provider missing");
        // Modern TvProvider silently returns only our own rows without this permission.
        // Report denied access before querying so an empty cursor cannot hide that state.
        if (isReadPermissionAvailable(context) && !hasReadPermission(context))
            throw new SecurityException("TV listings permission required");
        String[] projection = {"_id", "package_name", "title", "short_description", "intent_uri",
                "poster_art_uri", "last_playback_position_millis", "duration_millis",
                "last_engagement_time_utc_millis", "browsable", "searchable",
                TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE};
        CancellationSignal cancellation = new CancellationSignal();
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable cancel = cancellation::cancel;
        // Provider cancellation is cooperative. This repository never creates a thread or polling loop.
        handler.postDelayed(cancel, 5000);
        try (Cursor cursor = queryWithOptionalType(columns -> context.getContentResolver().query(
                TvContract.WatchNextPrograms.CONTENT_URI, columns, null, null,
                "last_engagement_time_utc_millis DESC", cancellation), projection)) {
            if (cursor == null) throw new ProviderUnavailableException("Provider returned no cursor");
            for (String required : new String[]{"_id", "package_name", "title", "intent_uri",
                    "last_engagement_time_utc_millis", "browsable", "searchable",
                    TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE}) {
                if (cursor.getColumnIndex(required) < 0)
                    throw new ProviderUnavailableException("Unsupported Watch Next columns");
            }
            ArrayList<Entry> rows = new ArrayList<>();
            while (rows.size() < MAX_ROWS && !cancellation.isCanceled() && cursor.moveToNext()) {
                rows.add(new Entry(value(cursor, "_id"), value(cursor, "title"),
                        value(cursor, "short_description"), value(cursor, "package_name"),
                        value(cursor, "intent_uri"), safePoster(value(cursor, "poster_art_uri")),
                        optionalPlaybackPosition(cursor), longValue(cursor, "duration_millis"),
                        longValue(cursor, "last_engagement_time_utc_millis"),
                        longValue(cursor, "browsable") == 1, longValue(cursor, "searchable") == 1,
                        readProviderKind(cursor), readProviderWatchType(cursor)));
            }
            if (cancellation.isCanceled()) throw new ProviderUnavailableException("Provider timed out");
            return rows;
        } finally { handler.removeCallbacks(cancel); }
    }

    /** Some OEM providers reject a projection containing the optional content-type column. */
    static Cursor queryWithOptionalType(CursorQuery query, String[] requiredProjection) {
        String[] extended = Arrays.copyOf(requiredProjection, requiredProjection.length + 1);
        extended[requiredProjection.length] = TvContract.WatchNextPrograms.COLUMN_TYPE;
        try { return query.query(extended); }
        catch (IllegalArgumentException unsupportedOptionalColumn) {
            // The caller retains the same CancellationSignal and original timeout for both queries.
            return query.query(requiredProjection);
        }
    }

    /** Cursor strings preserve the distinction between a missing/null type and movie's integer zero. */
    static Kind readProviderKind(Cursor cursor) {
        try { return parseProviderKind(value(cursor, TvContract.WatchNextPrograms.COLUMN_TYPE)); }
        catch (SecurityException | OperationCanceledException unavailable) { throw unavailable; }
        catch (RuntimeException malformedOptionalValue) { return Kind.UNKNOWN; }
    }

    static Kind parseProviderKind(String raw) {
        if (raw == null || raw.trim().isEmpty()) return Kind.UNKNOWN;
        final int type;
        try { type = Integer.parseInt(raw.trim()); }
        catch (NumberFormatException malformed) { return Kind.UNKNOWN; }
        switch (type) {
            case TvContract.WatchNextPrograms.TYPE_MOVIE: return Kind.MOVIE;
            case TvContract.WatchNextPrograms.TYPE_TV_SERIES:
            case TvContract.WatchNextPrograms.TYPE_TV_SEASON:
            case TvContract.WatchNextPrograms.TYPE_TV_EPISODE: return Kind.SHOW;
            default: return Kind.UNKNOWN;
        }
    }

    static WatchType readProviderWatchType(Cursor cursor) {
        try { return parseWatchType(value(cursor, TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE)); }
        catch (SecurityException | OperationCanceledException unavailable) { throw unavailable; }
        catch (RuntimeException malformedType) { return WatchType.UNKNOWN; }
    }

    static WatchType parseWatchType(String raw) {
        if (raw == null || raw.trim().isEmpty()) return WatchType.UNKNOWN;
        final int type;
        try { type = Integer.parseInt(raw.trim()); }
        catch (NumberFormatException malformed) { return WatchType.UNKNOWN; }
        switch (type) {
            case TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE: return WatchType.CONTINUE;
            case TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT: return WatchType.NEXT;
            case TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEW: return WatchType.NEW;
            case TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST: return WatchType.WATCHLIST;
            default: return WatchType.UNKNOWN;
        }
    }

    public static boolean safeLaunch(Context context, Entry entry) {
        Intent intent = resolveSafeIntent(context, entry);
        if (intent == null) return false;
        try {
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException ignored) { return false; }
    }

    private static Intent resolveSafeIntent(Context context, Entry entry) {
        return entry == null ? null : PublishedContentIntents.resolve(context, entry.packageName, entry.intentUri);
    }
    private static String safePoster(String raw) {
        String value = protocolValue(raw, 4096);
        if (value.isEmpty()) return "";
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        return "https".equals(scheme) || "content".equals(scheme)
                || "android.resource".equals(scheme) ? value : "";
    }
    private static String value(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? "" : cursor.getString(index);
    }
    private static long longValue(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? 0 : cursor.getLong(index);
    }
    private static long optionalPlaybackPosition(Cursor cursor) {
        int index = cursor.getColumnIndex("last_playback_position_millis");
        return index < 0 || cursor.isNull(index) ? -1 : cursor.getLong(index);
    }
    private static String bounded(String value, int max) {
        if (value == null) return "";
        String text = value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }
    /** Identifiers and actions must stay exact; clipping can create a different valid target. */
    private static String protocolValue(String value, int max) {
        return value == null || value.length() > max ? "" : value.trim();
    }
}
