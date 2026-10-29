package io.github.ekkzy.evebox.data;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.Process;
import android.os.UserManager;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Blocking, read-only Android TV preview adapter. The session owns its worker and cancellation. */
public final class ChannelRepository {
    public static final int MAX_CHANNEL_ROWS = 256;
    public static final int MAX_PROGRAM_ROWS = 100;
    public static final int MAX_ITEMS = 50;
    public static final int MAX_SELECTED_CHANNELS = 6;
    public enum Status { READY, EMPTY, PARTIAL, PERMISSION_REQUIRED, DENIED, PROVIDER_UNAVAILABLE,
        SCHEMA_INCOMPLETE, QUERY_FAILED, CANCELLED, OWNER_UNAVAILABLE }
    public enum Completeness { FULL, CAPPED, UNKNOWN }

    static final String[] CHANNEL_PROJECTION = {"_id", "package_name", "type", "display_name", "browsable", "searchable"};
    static final String[] CHANNEL_WITH_LOCKED_PROJECTION = {"_id", "package_name", "type", "display_name", "browsable", "searchable", "locked"};
    static final String[] PROGRAM_REQUIRED = {"_id", "package_name", "channel_id", "title", "intent_uri", "browsable", "searchable"};
    static final String[] PROGRAM_PROJECTION = {"_id", "package_name", "channel_id", "title", "intent_uri", "browsable", "searchable",
        "short_description", "poster_art_uri", "weight", "type", "poster_art_aspect_ratio"};
    // Public AndroidX TvContractCompat.PreviewProgramColumns contract; no AndroidX runtime is required.
    static final String[] PROGRAM_WITH_GENRE_PROJECTION = {"_id", "package_name", "channel_id", "title", "intent_uri", "browsable", "searchable",
        "short_description", "poster_art_uri", "weight", "type", "poster_art_aspect_ratio", "genre"};

    public static final class Channel {
        public final long id, userSerial, installEpoch, versionCode;
        public final String key, packageName, displayName, appLabel;
        /** System Home row choice; independent from an explicit local Eve Box selection. */
        public final boolean systemBrowsable;
        public Channel(long userSerial, String packageName, long installEpoch, long versionCode,
                long id, String displayName, String appLabel) {
            this(userSerial, packageName, installEpoch, versionCode, id, displayName, appLabel, true);
        }
        public Channel(long userSerial, String packageName, long installEpoch, long versionCode,
                long id, String displayName, String appLabel, boolean systemBrowsable) {
            this.userSerial = userSerial;
            this.packageName = packageName;
            this.installEpoch = installEpoch;
            this.versionCode = versionCode;
            this.id = id;
            this.displayName = text(displayName, 256);
            this.appLabel = text(appLabel, 256);
            this.systemBrowsable = systemBrowsable;
            this.key = userSerial + "|" + packageName + "|" + installEpoch + "|" + id;
        }
    }

    /** A published recommendation has no fabricated playback position or watch history. */
    public static final class Program {
        public final long id;
        public final String key, channelKey, packageName, title, description, intentUri, posterUri;
        /** Publisher-provided freeform genre; empty when absent, never inferred from type or description. */
        public final String genre;
        public final Long weight;
        public final Integer type, posterAspectRatio;
        private Program(Channel channel, long id, String title, String description, String intentUri,
                String posterUri, Long weight, Integer type, Integer posterAspectRatio) {
            this(channel, id, title, description, intentUri, posterUri, weight, type, posterAspectRatio, "");
        }
        private Program(Channel channel, long id, String title, String description, String intentUri,
                String posterUri, Long weight, Integer type, Integer posterAspectRatio, String genre) {
            this.id = id; this.channelKey = channel.key; this.key = channel.key + "|" + id;
            this.packageName = channel.packageName; this.title = title; this.description = description;
            this.intentUri = intentUri; this.posterUri = posterUri; this.weight = weight;
            this.type = type; this.posterAspectRatio = posterAspectRatio;
            this.genre = genreText(genre);
        }
    }

    /** Counts contain no provider titles, identifiers, URIs, or exception messages. */
    public static final class Stats {
        public final int scanned, eligible, retained, rejected, hidden, locked, unknownVisibility,
                ownerUnavailable, duplicateConflicts, invalidIntents, appOnlyIntents, imageUriPresent;
        public final boolean displayLimited;
        public final long elapsedMillis;
        private Stats(Counters c, int eligible, int retained, int images, long started) {
            this.scanned = c.scanned; this.eligible = eligible; this.retained = retained;
            this.rejected = c.scanned - eligible; this.hidden = c.hidden;
            this.locked = c.locked;
            this.unknownVisibility = c.unknownVisibility; this.ownerUnavailable = c.ownerUnavailable;
            this.duplicateConflicts = c.duplicateConflicts; this.invalidIntents = c.invalidIntents;
            this.appOnlyIntents = c.appOnlyIntents; this.imageUriPresent = images;
            this.displayLimited = eligible > retained;
            this.elapsedMillis = Math.max(0, (System.nanoTime() - started) / 1_000_000L);
        }
    }

    public static final class SourceSummary {
        public final String packageName, appLabel;
        public final long versionCode, installEpoch;
        public final boolean ownerAvailable;
        public final int scannedChannelCount, eligibleChannelCount, hiddenChannelCount, rejectedChannelCount,
                selectableChannelCount, browsableOffChannelCount, searchableOffChannelCount, lockedChannelCount;
        private SourceSummary(SourceCounts source) {
            packageName = source.packageName; ownerAvailable = source.owner != null;
            appLabel = ownerAvailable ? source.owner.appLabel : packageName;
            versionCode = ownerAvailable ? source.owner.versionCode : -1;
            installEpoch = ownerAvailable ? source.owner.installEpoch : -1;
            scannedChannelCount = source.scanned; eligibleChannelCount = source.eligible;
            hiddenChannelCount = source.hidden; rejectedChannelCount = source.scanned - source.eligible;
            selectableChannelCount = source.selectable; browsableOffChannelCount = source.browsableOff;
            searchableOffChannelCount = source.searchableOff; lockedChannelCount = source.locked;
        }
    }

    public static final class Discovery {
        public final Status status;
        public final Completeness completeness;
        public final List<Channel> channels;
        /** Published searchable channels available for explicit local selection; not an enabled list. */
        public final List<Channel> selectableChannels;
        public final List<SourceSummary> sources;
        public final Stats stats;
        private Discovery(Status status, Completeness completeness, List<Channel> channels,
                List<Channel> selectableChannels, List<SourceSummary> sources, Stats stats) {
            this.status = status; this.completeness = completeness;
            this.channels = immutable(channels); this.sources = immutable(sources); this.stats = stats;
            this.selectableChannels = immutable(selectableChannels);
        }
    }

    public static final class Programs {
        public final Status status;
        public final Completeness completeness;
        public final Channel channel;
        public final List<Program> items;
        public final Stats stats;
        private Programs(Status status, Completeness completeness, Channel channel, List<Program> items, Stats stats) {
            this.status = status; this.completeness = completeness; this.channel = channel;
            this.items = immutable(items); this.stats = stats;
        }
    }

    public Discovery discover(Context context, CancellationSignal cancellation) {
        long started = System.nanoTime();
        Context app = application(context);
        CancellationSignal signal = cancellation == null ? new CancellationSignal() : cancellation;
        try {
            checkAccess(app, signal);
            long userSerial = currentUserSerial(app);
            if (userSerial < 0) return discoveryFailure(Status.OWNER_UNAVAILABLE, started);
            return discoverRows(() -> queryChannelsWithOptionalLock(projection -> app.getContentResolver().query(
                    TvContract.Channels.CONTENT_URI, projection, null, null, "_id ASC", signal), signal::isCanceled),
                    packageName -> owner(app, packageName), userSerial, signal::isCanceled);
        } catch (ReadFailure failure) { return discoveryFailure(failure.status, started); }
        catch (OperationCanceledException cancelled) { return discoveryFailure(Status.CANCELLED, started); }
        catch (SecurityException denied) { return discoveryFailure(Status.DENIED, started); }
        catch (RuntimeException failure) { return discoveryFailure(Status.QUERY_FAILED, started); }
    }

    public Programs load(Context context, Channel channel, CancellationSignal cancellation) {
        long started = System.nanoTime();
        Context app = application(context);
        CancellationSignal signal = cancellation == null ? new CancellationSignal() : cancellation;
        try {
            checkAccess(app, signal);
            Owner current = channel == null ? null : owner(app, channel.packageName);
            if (channel == null || current == null || channel.id <= 0
                    || channel.userSerial != currentUserSerial(app)
                    || channel.installEpoch != current.installEpoch)
                return programsFailure(Status.OWNER_UNAVAILABLE, channel, started);
            return loadRows(() -> queryPrograms(app, channel.id, signal), channel,
                    (pkg, uri) -> PublishedContentIntents.classify(app, pkg, uri), signal::isCanceled);
        } catch (ReadFailure failure) { return programsFailure(failure.status, channel, started); }
        catch (OperationCanceledException cancelled) { return programsFailure(Status.CANCELLED, channel, started); }
        catch (SecurityException denied) { return programsFailure(Status.DENIED, channel, started); }
        catch (RuntimeException failure) { return programsFailure(Status.QUERY_FAILED, channel, started); }
    }

    /** Checks the installation epoch and the activity again immediately before dispatch. */
    public boolean launch(Context context, Channel channel, Program program) {
        if (channel == null || program == null || !channel.key.equals(program.channelKey)) return false;
        Context app = application(context);
        try {
            checkAccess(app, new CancellationSignal());
            Owner current = owner(app, channel.packageName);
            return current != null && current.installEpoch == channel.installEpoch
                    && currentUserSerial(app) == channel.userSerial
                    && PublishedContentIntents.launchView(context, program.packageName, program.intentUri);
        } catch (ReadFailure | RuntimeException unavailable) { return false; }
    }

    private static Cursor queryPrograms(Context app, long channelId, CancellationSignal signal) {
        return queryProgramsWithOptionalGenre((projection, order) -> app.getContentResolver().query(
                TvContract.buildPreviewProgramsUriForChannel(channelId), projection, null, null, order, signal),
                signal::isCanceled);
    }

    @FunctionalInterface interface CursorQuery { Cursor query(); }
    @FunctionalInterface interface ProjectionQuery { Cursor query(String[] projection); }
    @FunctionalInterface interface ProgramQuery { Cursor query(String[] projection, String sortOrder); }
    @FunctionalInterface interface OwnerLookup { Owner find(String packageName); }
    @FunctionalInterface interface TargetValidator { PublishedContentIntents.TargetKind classify(String owner, String uri); }
    static final class Owner {
        final String appLabel;
        final long installEpoch, versionCode;
        Owner(String appLabel, long installEpoch, long versionCode) {
            this.appLabel = text(appLabel, 256); this.installEpoch = installEpoch; this.versionCode = versionCode;
        }
    }

    /** OEMs may not expose the optional lock column; required visibility never falls back to defaults. */
    static Cursor queryChannelsWithOptionalLock(ProjectionQuery query, BooleanSupplier cancelled) {
        try { return query.query(CHANNEL_WITH_LOCKED_PROJECTION.clone()); }
        catch (IllegalArgumentException unsupportedOptionalColumn) {
            if (cancelled.getAsBoolean()) throw new OperationCanceledException();
            return query.query(CHANNEL_PROJECTION.clone());
        }
    }

    /** A missing genre column must not remove existing artwork/weight metadata on an OEM provider. */
    static Cursor queryProgramsWithOptionalGenre(ProgramQuery query, BooleanSupplier cancelled) {
        String[][] projections = {PROGRAM_WITH_GENRE_PROJECTION, PROGRAM_PROJECTION, PROGRAM_REQUIRED};
        for (int index = 0; index < projections.length; index++) {
            if (cancelled.getAsBoolean()) throw new OperationCanceledException();
            try { return query.query(projections[index].clone(), index == projections.length - 1 ? null : "weight DESC"); }
            catch (IllegalArgumentException unsupportedOptionalColumn) {
                if (index == projections.length - 1) throw unsupportedOptionalColumn;
            }
        }
        throw new AssertionError("No program projection attempted");
    }

    /** Injectable provider/owner boundary; shares the production Cursor parsing and limits. */
    static Discovery discoverRows(CursorQuery query, OwnerLookup owners, long userSerial, BooleanSupplier cancelled) {
        long started = System.nanoTime(); Counters count = new Counters();
        LinkedHashMap<String, Channel> accepted = new LinkedHashMap<>();
        LinkedHashMap<String, SourceCounts> summaries = new LinkedHashMap<>();
        Map<String, List<String>> seen = new HashMap<>(); Set<String> conflicts = new HashSet<>();
        Completeness completeness = Completeness.UNKNOWN;
        Status failure = null;
        try {
            checkCancelled(cancelled);
            try (Cursor cursor = query.query()) {
                if (cursor == null) throw new ReadFailure(Status.QUERY_FAILED);
                requireColumns(cursor, CHANNEL_PROJECTION);
                completeness = Completeness.CAPPED;
                while (count.scanned < MAX_CHANNEL_ROWS) {
                    checkCancelled(cancelled);
                    if (!cursor.moveToNext()) { completeness = Completeness.FULL; break; }
                    count.scanned++;
                    String pkg = limited(raw(cursor, "package_name"), 256);
                    if (!validPackage(pkg)) continue;
                    SourceCounts summary = summaries.get(pkg);
                    if (summary == null) {
                        summary = new SourceCounts(pkg, owners.find(pkg)); summaries.put(pkg, summary);
                    }
                    summary.scanned++;
                    Long id = number(cursor, "_id");
                    if (id == null || id <= 0) continue;
                    String key = pkg + "|" + id;
                    List<String> fingerprint = fingerprint(cursor, CHANNEL_WITH_LOCKED_PROJECTION);
                    int duplicate = duplicate(key, fingerprint, seen, conflicts, count);
                    if (duplicate != 0) { if (duplicate == 2) accepted.remove(key); continue; }
                    if (!TvContract.Channels.TYPE_PREVIEW.equals(raw(cursor, "type"))) continue;
                    Long browse = number(cursor, "browsable"), search = number(cursor, "searchable");
                    if (Long.valueOf(0).equals(browse)) summary.browsableOff++;
                    if (Long.valueOf(0).equals(search)) summary.searchableOff++;
                    if (Long.valueOf(0).equals(browse) || Long.valueOf(0).equals(search)) {
                        count.hidden++; summary.hidden++;
                    }
                    if (Long.valueOf(0).equals(search)) continue;
                    if ((!Long.valueOf(0).equals(browse) && !Long.valueOf(1).equals(browse))
                            || !Long.valueOf(1).equals(search)) {
                        count.unknownVisibility++; continue;
                    }
                    if (Long.valueOf(1).equals(number(cursor, "locked"))) {
                        count.locked++; summary.locked++; continue;
                    }
                    if (summary.owner == null || summary.owner.installEpoch < 0 || userSerial < 0) {
                        count.ownerUnavailable++; continue;
                    }
                    String name = text(raw(cursor, "display_name"), 256);
                    if (name.isEmpty()) name = summary.owner.appLabel;
                    accepted.put(key, new Channel(userSerial, pkg, summary.owner.installEpoch,
                            summary.owner.versionCode, id, name, summary.owner.appLabel, Long.valueOf(1).equals(browse)));
                }
                checkCancelled(cancelled);
            }
        } catch (ReadFailure unavailable) { failure = unavailable.status; }
        catch (OperationCanceledException cancelledQuery) { failure = Status.CANCELLED; }
        catch (SecurityException denied) { failure = Status.DENIED; }
        catch (IllegalArgumentException unsupported) { failure = Status.SCHEMA_INCOMPLETE; }
        catch (RuntimeException error) { failure = Status.QUERY_FAILED; }
        if (failure != null) { accepted.clear(); completeness = Completeness.UNKNOWN; }
        List<Channel> selectable = new ArrayList<>(accepted.values());
        selectable.sort(Comparator.comparing((Channel c) -> c.packageName).thenComparingLong(c -> c.id));
        List<Channel> channels = new ArrayList<>();
        for (Channel channel : selectable) {
            SourceCounts summary = summaries.get(channel.packageName);
            summary.selectable++;
            if (channel.systemBrowsable) { channels.add(channel); summary.eligible++; }
        }
        List<SourceSummary> sourceRows = new ArrayList<>();
        for (SourceCounts summary : summaries.values()) sourceRows.add(new SourceSummary(summary));
        return new Discovery(failure == null ? classifiedStatus(completeness, selectable.size(), count) : failure,
                completeness, channels, selectable, sourceRows, new Stats(count, channels.size(), channels.size(), 0, started));
    }

    static Programs loadRows(CursorQuery query, Channel channel, TargetValidator validator, BooleanSupplier cancelled) {
        long started = System.nanoTime(); Counters count = new Counters();
        Map<String, List<String>> seen = new HashMap<>(); Set<String> conflicts = new HashSet<>();
        LinkedHashMap<String, Program> accepted = new LinkedHashMap<>();
        Completeness completeness = Completeness.UNKNOWN; Status failure = null;
        try {
            checkCancelled(cancelled);
            if (channel == null) throw new ReadFailure(Status.OWNER_UNAVAILABLE);
            try (Cursor cursor = query.query()) {
                if (cursor == null) throw new ReadFailure(Status.QUERY_FAILED);
                requireColumns(cursor, PROGRAM_REQUIRED);
                completeness = Completeness.CAPPED;
                while (count.scanned < MAX_PROGRAM_ROWS) {
                    checkCancelled(cancelled);
                    if (!cursor.moveToNext()) { completeness = Completeness.FULL; break; }
                    count.scanned++;
                    Long id = number(cursor, "_id");
                    String pkg = limited(raw(cursor, "package_name"), 256);
                    if (id == null || id <= 0 || !channel.packageName.equals(pkg)
                            || !Long.valueOf(channel.id).equals(number(cursor, "channel_id"))) continue;
                    String key = channel.key + "|" + id;
                    int duplicate = duplicate(key, fingerprint(cursor, PROGRAM_WITH_GENRE_PROJECTION), seen, conflicts, count);
                    if (duplicate != 0) { if (duplicate == 2) accepted.remove(key); continue; }
                    Long browse = number(cursor, "browsable"), search = number(cursor, "searchable");
                    if (Long.valueOf(0).equals(browse) || Long.valueOf(0).equals(search)) { count.hidden++; continue; }
                    if (!Long.valueOf(1).equals(browse) || !Long.valueOf(1).equals(search)) { count.unknownVisibility++; continue; }
                    String title = text(raw(cursor, "title"), 256);
                    String intent = limited(raw(cursor, "intent_uri"), 16384);
                    if (title.isEmpty()) continue;
                    if (intent.isEmpty()) { count.invalidIntents++; continue; }
                    PublishedContentIntents.TargetKind target = validator.classify(pkg, intent);
                    checkCancelled(cancelled);
                    if (target == PublishedContentIntents.TargetKind.APP_ONLY) { count.appOnlyIntents++; continue; }
                    if (target != PublishedContentIntents.TargetKind.VIEW) { count.invalidIntents++; continue; }
                    accepted.put(key, new Program(channel, id, title, text(raw(cursor, "short_description"), 1024),
                            intent, poster(raw(cursor, "poster_art_uri")), number(cursor, "weight"),
                            optionalInt(cursor, "type", 0, 11), optionalInt(cursor, "poster_art_aspect_ratio", 0, 6), raw(cursor, "genre")));
                }
                checkCancelled(cancelled);
            }
        } catch (ReadFailure unavailable) { failure = unavailable.status; }
        catch (OperationCanceledException cancelledQuery) { failure = Status.CANCELLED; }
        catch (SecurityException denied) { failure = Status.DENIED; }
        catch (IllegalArgumentException unsupported) { failure = Status.SCHEMA_INCOMPLETE; }
        catch (RuntimeException error) { failure = Status.QUERY_FAILED; }
        if (failure != null) { accepted.clear(); completeness = Completeness.UNKNOWN; }
        List<Program> items = new ArrayList<>(accepted.values());
        items.sort(Comparator.comparing((Program p) -> p.weight, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparingLong(p -> p.id));
        int eligible = items.size(), images = 0;
        for (Program item : items) if (!item.posterUri.isEmpty()) images++;
        if (items.size() > MAX_ITEMS) items = new ArrayList<>(items.subList(0, MAX_ITEMS));
        return new Programs(failure == null ? classifiedStatus(completeness, eligible, count) : failure, completeness,
                channel, items, new Stats(count, eligible, items.size(), images, started));
    }

    // Identical repeated rows collapse; any conflicting version invalidates every copy of its key.
    private static int duplicate(String key, List<String> fingerprint, Map<String, List<String>> seen,
            Set<String> conflicts, Counters count) {
        if (conflicts.contains(key)) return 2;
        List<String> prior = seen.putIfAbsent(key, fingerprint);
        if (prior == null) return 0;
        if (!prior.equals(fingerprint) && conflicts.add(key)) count.duplicateConflicts++;
        return conflicts.contains(key) ? 2 : 1;
    }

    private static List<String> fingerprint(Cursor cursor, String[] columns) {
        List<String> values = new ArrayList<>(columns.length);
        for (String column : columns) {
            String value = raw(cursor, column);
            // Compare the normalized public payload, with the same bounded text/URI policy.
            if ("title".equals(column) || "display_name".equals(column)) values.add(text(value, 256));
            else if ("genre".equals(column)) values.add(genreText(value));
            else if ("short_description".equals(column)) values.add(text(value, 1024));
            else if ("intent_uri".equals(column)) values.add(limited(value, 16384));
            else if ("poster_art_uri".equals(column)) values.add(limited(value, 4096));
            else values.add(limited(value, 256));
        }
        return values;
    }

    private static void checkAccess(Context app, CancellationSignal signal) throws ReadFailure {
        if (signal.isCanceled()) throw new ReadFailure(Status.CANCELLED);
        if (app.getPackageManager().resolveContentProvider(TvContract.AUTHORITY, 0) == null)
            throw new ReadFailure(Status.PROVIDER_UNAVAILABLE);
        if (!WatchNextRepository.isReadPermissionAvailable(app)) throw new ReadFailure(Status.PROVIDER_UNAVAILABLE);
        if (!WatchNextRepository.hasReadPermission(app)) throw new ReadFailure(Status.PERMISSION_REQUIRED);
    }

    private static long currentUserSerial(Context app) {
        UserManager manager = (UserManager) app.getSystemService(Context.USER_SERVICE);
        return manager == null ? -1 : manager.getSerialNumberForUser(Process.myUserHandle());
    }

    private static Owner owner(Context app, String packageName) {
        if (!validPackage(packageName) || app.getPackageName().equals(packageName)) return null;
        PackageManager pm = app.getPackageManager();
        try {
            PackageInfo info = pm.getPackageInfo(packageName, 0);
            if (info.applicationInfo == null || !info.applicationInfo.enabled) return null;
            Intent launch = pm.getLeanbackLaunchIntentForPackage(packageName);
            if (launch == null) launch = pm.getLaunchIntentForPackage(packageName);
            if (launch == null) return null;
            ResolveInfo resolved = pm.resolveActivity(launch, 0);
            ActivityInfo activity = resolved == null ? null : resolved.activityInfo;
            if (activity == null || !activity.enabled || !activity.exported
                    || !packageName.equals(activity.packageName)) return null;
            if (activity.permission != null && app.checkSelfPermission(activity.permission) != PackageManager.PERMISSION_GRANTED) return null;
            CharSequence label = info.applicationInfo.loadLabel(pm);
            return new Owner(label == null ? packageName : label.toString(), info.firstInstallTime,
                    Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode);
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) { return null; }
    }

    static boolean validPackage(String value) {
        return value != null && value.length() <= 256
                && value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    }
    static String text(String raw, int max) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.length() <= max) return value;
        int end = max;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }
    static String limited(String raw, int max) {
        if (raw == null || raw.length() > max) return "";
        return raw.trim();
    }
    private static String genreText(String raw) {
        String value = text(raw, 512);
        return value.codePointCount(0, value.length()) <= 256 ? value : value.substring(0, value.offsetByCodePoints(0, 256));
    }
    static String poster(String raw) {
        String value = limited(raw, 4096);
        if (value.isEmpty()) return "";
        try {
            URI uri = new URI(value); String scheme = uri.getScheme();
            return ("https".equals(scheme) || "content".equals(scheme) || "android.resource".equals(scheme))
                    && uri.getRawAuthority() != null && !uri.getRawAuthority().isEmpty() ? value : "";
        } catch (URISyntaxException malformed) { return ""; }
    }
    private static String raw(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) return "";
        String value = cursor.getString(index); return value == null ? "" : value;
    }
    private static Long number(Cursor cursor, String column) {
        String value = raw(cursor, column).trim();
        if (value.isEmpty() || value.length() > 20) return null;
        try { return Long.valueOf(value); } catch (NumberFormatException malformed) { return null; }
    }
    private static Integer optionalInt(Cursor cursor, String column, int min, int max) {
        Long value = number(cursor, column);
        return value == null || value < min || value > max ? null : value.intValue();
    }
    private static void requireColumns(Cursor cursor, String[] columns) throws ReadFailure {
        for (String column : columns) if (cursor.getColumnIndex(column) < 0) throw new ReadFailure(Status.SCHEMA_INCOMPLETE);
    }
    private static void checkCancelled(BooleanSupplier cancelled) throws ReadFailure {
        if (cancelled.getAsBoolean()) throw new ReadFailure(Status.CANCELLED);
    }
    private static Context application(Context context) {
        Context app = context.getApplicationContext(); return app == null ? context : app;
    }
    private static Status status(Completeness completeness, int count) {
        return completeness == Completeness.CAPPED ? Status.PARTIAL : count == 0 ? Status.EMPTY : Status.READY;
    }
    private static Status classifiedStatus(Completeness completeness, int available, Counters count) {
        if (available == 0 && completeness == Completeness.FULL) {
            if (count.unknownVisibility > 0) return Status.SCHEMA_INCOMPLETE;
            if (count.ownerUnavailable > 0) return Status.OWNER_UNAVAILABLE;
        }
        return status(completeness, available);
    }
    private static Discovery discoveryFailure(Status status, long started) {
        return new Discovery(status, Completeness.UNKNOWN, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                new Stats(new Counters(), 0, 0, 0, started));
    }
    private static Programs programsFailure(Status status, Channel channel, long started) {
        return new Programs(status, Completeness.UNKNOWN, channel, Collections.emptyList(),
                new Stats(new Counters(), 0, 0, 0, started));
    }
    /** A failed shelf can be reported without discarding independent successful shelves. */
    public static Programs failedPrograms(Channel channel, Status status) {
        if (status == Status.READY || status == Status.EMPTY || status == Status.PARTIAL)
            throw new IllegalArgumentException("A failure status is required");
        return programsFailure(status == null ? Status.QUERY_FAILED : status, channel, System.nanoTime());
    }
    private static <T> List<T> immutable(List<T> values) { return Collections.unmodifiableList(new ArrayList<>(values)); }
    private static final class Counters {
        int scanned, hidden, locked, unknownVisibility, ownerUnavailable, duplicateConflicts, invalidIntents, appOnlyIntents;
    }
    private static final class SourceCounts {
        final String packageName; final Owner owner;
        int scanned, eligible, hidden, selectable, browsableOff, searchableOff, locked;
        SourceCounts(String packageName, Owner owner) { this.packageName = packageName; this.owner = owner; }
    }
    private static final class ReadFailure extends Exception {
        final Status status;
        ReadFailure(Status status) { this.status = status; }
    }
}
