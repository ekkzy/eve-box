package io.github.ekkzy.evebox.data;

import android.content.Context;
import android.database.ContentObserver;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OperationCanceledException;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Activity-owned reader: discovery is shared, programs are read only for the active app. */
public final class RecommendationsSession implements AutoCloseable {
    public static final long QUERY_TIMEOUT_MILLIS = 12_000L;
    public static final long APP_TIMEOUT_MILLIS = 30_000L;
    public static final int MAX_APP_CHANNELS = 32, MAX_APP_ITEMS = 200, MAX_CACHED_APPS = 3;
    public enum Failure { NONE, TIMEOUT, ERROR, CANCELLED, UNAVAILABLE }
    public interface Listener { void onSnapshot(Snapshot snapshot); }

    /** Bounded merged publications; each item keeps its exact original channel for launching. */
    public static final class AppPrograms {
        public final String packageName;
        public final ChannelRepository.Status status;
        public final ChannelRepository.Completeness completeness;
        public final List<ChannelRepository.Program> items;
        public final List<ChannelRepository.Channel> channels;
        public final int loadedChannels, failedChannels, totalChannels;
        private final Map<String, ChannelRepository.Channel> channelsByKey;
        private AppPrograms(AppBatch batch, boolean finished) {
            packageName = batch.packageName;
            channels = Collections.unmodifiableList(new ArrayList<>(batch.channels));
            channelsByKey = new LinkedHashMap<>();
            for (ChannelRepository.Channel channel : channels) channelsByKey.put(channel.key, channel);
            items = Collections.unmodifiableList(mergedItems(batch));
            loadedChannels = batch.slices.size(); failedChannels = batch.failedChannels; totalChannels = channels.size();
            boolean incomplete = !finished || batch.incomplete || failedChannels > 0;
            for (Slice slice : batch.slices.values()) incomplete |= slice.status == ChannelRepository.Status.PARTIAL
                    || slice.completeness != ChannelRepository.Completeness.FULL;
            completeness = batch.capped ? ChannelRepository.Completeness.CAPPED
                    : incomplete ? ChannelRepository.Completeness.UNKNOWN : ChannelRepository.Completeness.FULL;
            status = batch.denied != null ? batch.denied
                    : failedChannels > 0 && loadedChannels == 0 ? ChannelRepository.Status.QUERY_FAILED
                    : completeness != ChannelRepository.Completeness.FULL ? ChannelRepository.Status.PARTIAL
                    : items.isEmpty() ? ChannelRepository.Status.EMPTY : ChannelRepository.Status.READY;
        }
        public ChannelRepository.Channel channelFor(ChannelRepository.Program program) {
            return program != null && packageName.equals(program.packageName) ? channelsByKey.get(program.channelKey) : null;
        }
    }

    public static final class Snapshot {
        public final long revision;
        public final boolean enabled, resumed, observing, discoveryLoading, activeLoading, pendingRefresh, queryRunning;
        public final ChannelRepository.Discovery discovery;
        public final List<String> selectedPackages;
        public final String activePackage;
        public final Map<String, AppPrograms> programsByPackage;
        public final Failure discoveryFailure, activeFailure;
        private Snapshot(RecommendationsSession session) {
            revision = session.revision; enabled = session.enabled; resumed = session.resumed; observing = session.observing;
            discovery = session.discovery; selectedPackages = session.selectedPackages; activePackage = session.activePackage;
            programsByPackage = Collections.unmodifiableMap(new LinkedHashMap<>(session.programCache));
            discoveryLoading = session.wantDiscovery || session.currentRun != null && session.currentRun.kind == Kind.DISCOVERY;
            activeLoading = activePackage != null && (session.wantActive || session.batch != null);
            pendingRefresh = session.wantDiscovery || session.wantActive;
            synchronized (session.lock) { queryRunning = session.inFlight != null; }
            discoveryFailure = session.discoveryFailure; activeFailure = session.activeFailure;
        }
        public AppPrograms activePrograms() { return activePackage == null ? null : programsByPackage.get(activePackage); }
    }

    interface Reader {
        ChannelRepository.Discovery discover(Context app, CancellationSignal signal);
        ChannelRepository.Programs load(Context app, ChannelRepository.Channel channel, CancellationSignal signal);
    }
    interface Observers { boolean start(Runnable channelsChanged, Runnable programsChanged); void stop(); }
    private enum Kind { DISCOVERY, PROGRAMS }
    private final Context app;
    private final Reader reader;
    private final Observers observers;
    private final ExecutorService executor;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final long timeoutMillis, debounceMillis, appTimeoutMillis;
    private final LinkedHashMap<String, AppPrograms> programCache = new LinkedHashMap<>();
    private Listener listener;
    private List<String> selectedPackages = Collections.emptyList();
    private String activePackage;
    private ChannelRepository.Discovery discovery;
    private Failure discoveryFailure = Failure.NONE, activeFailure = Failure.NONE;
    private boolean enabled, resumed, closed, observing, wantDiscovery, wantActive, pumpPosted, emissionPosted;
    private long generation, revision, observerGeneration;
    private Run currentRun, inFlight;
    private AppBatch batch;
    private final Runnable pump = this::pump;
    private final Runnable emission = () -> {
        emissionPosted = false;
        if (resumed && !closed && listener != null) listener.onSnapshot(new Snapshot(this));
    };

    public RecommendationsSession(Context context, Listener listener) {
        this(context, listener, repositoryReader(), null, newExecutor(), QUERY_TIMEOUT_MILLIS, 180L);
    }
    RecommendationsSession(Context context, Listener listener, Reader reader, Observers observers,
            ExecutorService executor, long timeoutMillis, long debounceMillis) {
        this(context, listener, reader, observers, executor, timeoutMillis, debounceMillis, APP_TIMEOUT_MILLIS);
    }
    RecommendationsSession(Context context, Listener listener, Reader reader, Observers observers,
            ExecutorService executor, long timeoutMillis, long debounceMillis, long appTimeoutMillis) {
        app = Objects.requireNonNull(context.getApplicationContext(), "application context");
        this.listener = Objects.requireNonNull(listener, "listener"); this.reader = Objects.requireNonNull(reader, "reader");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.observers = observers == null ? new AndroidObservers(app, main) : observers;
        if (timeoutMillis <= 0 || debounceMillis < 0 || appTimeoutMillis <= 0) throw new IllegalArgumentException("invalid timing");
        this.timeoutMillis = timeoutMillis; this.debounceMillis = debounceMillis; this.appTimeoutMillis = appTimeoutMillis;
    }

    /** Selecting an app includes all its currently eligible channels, including later discoveries. */
    public void configure(boolean enabled, List<String> selectedPackages, String activePackage) {
        requireMain(); if (closed) return;
        List<String> next = LauncherPrefs.normalizeRecommendationPackages(selectedPackages);
        String active = activePackage != null && next.contains(activePackage) ? activePackage : null;
        if (this.enabled == enabled && this.selectedPackages.equals(next) && Objects.equals(this.activePackage, active)) return;
        boolean selectionsChanged = !this.selectedPackages.equals(next), wasEnabled = this.enabled;
        boolean interruptedDiscovery = currentRun != null && currentRun.kind == Kind.DISCOVERY;
        invalidateRun();
        this.enabled = enabled; this.selectedPackages = next; this.activePackage = active;
        programCache.keySet().retainAll(next); activeFailure = Failure.NONE;
        if (!enabled) { wantDiscovery = false; wantActive = false; programCache.clear(); stopObservers(); }
        else {
            wantDiscovery |= !wasEnabled || selectionsChanged || discovery == null || interruptedDiscovery;
            wantActive = active != null;
            if (resumed) startObservers();
        }
        publish(); schedulePump(false);
    }
    public Snapshot snapshot() { requireMain(); return new Snapshot(this); }
    public void resume() {
        requireMain(); if (closed || resumed) return;
        resumed = true; ++generation;
        if (!WatchNextRepository.hasReadPermission(app)) { discovery = null; programCache.clear(); }
        if (enabled) { startObservers(); wantDiscovery = true; wantActive = activePackage != null; }
        publish(); schedulePump(false);
    }
    public void pause() {
        requireMain(); if (closed || !resumed) return;
        resumed = false; invalidateRun(); stopObservers(); clearScheduled(); wantDiscovery = false; wantActive = false; ++revision;
    }
    @Override public void close() {
        requireMain(); if (closed) return;
        resumed = false; closed = true; invalidateRun(); stopObservers(); clearScheduled();
        wantDiscovery = false; wantActive = false; listener = null; programCache.clear(); discovery = null;
        executor.shutdown(); // Preserve the queued cancellation; never wait on main.
    }
    public void refreshDiscovery() {
        requireMain(); if (!canRead()) return;
        wantDiscovery = true; wantActive |= activePackage != null; publish(); schedulePump(false);
    }
    public void refreshActive() {
        requireMain(); if (!canRead() || activePackage == null) return;
        wantActive = true; publish(); schedulePump(false);
    }
    private void changed(boolean channels) {
        requireMain(); if (!canRead()) return;
        if (channels) { invalidateRun(); wantDiscovery = true; wantActive = activePackage != null; }
        else if (activePackage != null) {
            if (currentRun != null && currentRun.kind == Kind.PROGRAMS) invalidateRun();
            wantActive = true;
        } else return;
        publish(); schedulePump(true);
    }
    private void schedulePump(boolean debounce) {
        if (!canRead()) return;
        if (debounce) { main.removeCallbacks(pump); pumpPosted = false; }
        if (!pumpPosted) { pumpPosted = true; main.postDelayed(pump, debounce ? debounceMillis : 0); }
    }
    private void pump() {
        pumpPosted = false;
        if (!canRead() || currentRun != null) return;
        synchronized (lock) { if (inFlight != null) return; }
        if (wantDiscovery) { wantDiscovery = false; batch = null; discoveryFailure = Failure.NONE; begin(Kind.DISCOVERY, null); return; }
        if (batch == null) {
            if (!wantActive || activePackage == null) return;
            wantActive = false;
            RecommendationSources.Source source = findSource(activePackage);
            if (source == null) { programCache.remove(activePackage); activeFailure = Failure.UNAVAILABLE; publish(); return; }
            batch = new AppBatch(activePackage, source.channels, discovery.completeness != ChannelRepository.Completeness.FULL, SystemClock.elapsedRealtime());
            activeFailure = Failure.NONE;
        }
        if (SystemClock.elapsedRealtime() - batch.startedAt >= appTimeoutMillis) {
            batch.capped = true; batch.incomplete = true; finishBatch(Failure.TIMEOUT); publish(); schedulePump(false); return;
        }
        if (batch.nextIndex >= batch.limit) { finishBatch(Failure.NONE); publish(); schedulePump(false); return; }
        begin(Kind.PROGRAMS, batch.channels.get(batch.nextIndex++));
    }
    private void begin(Kind kind, ChannelRepository.Channel channel) {
        long budget = kind == Kind.DISCOVERY ? timeoutMillis : Math.min(timeoutMillis,
                Math.max(1L, appTimeoutMillis - (SystemClock.elapsedRealtime() - batch.startedAt)));
        Run run = new Run(generation, kind, channel, budget); currentRun = run;
        run.job = new FutureTask<>(() -> { execute(run); return null; }); run.timer = () -> timeout(run);
        synchronized (lock) {
            inFlight = run;
            try { executor.execute(run.job); main.postDelayed(run.timer, budget); }
            catch (RejectedExecutionException unavailable) {
                inFlight = null; currentRun = null; run.queryExited = true; fail(kind, channel, Failure.ERROR);
                if (batch != null) { batch.incomplete = true; finishBatch(Failure.ERROR); }
            }
        }
        publish();
    }
    private void execute(Run run) {
        synchronized (lock) {
            if (inFlight != run || run.stopRequested) return;
            run.started = true;
        }
        ChannelRepository.Discovery found = null; ChannelRepository.Programs programs = null; Failure failure = Failure.NONE;
        try {
            if (run.kind == Kind.DISCOVERY) found = reader.discover(app, run.signal);
            else programs = reader.load(app, run.channel, run.signal);
            if (run.kind == Kind.DISCOVERY ? found == null : programs == null) failure = Failure.ERROR;
            if (SystemClock.elapsedRealtime() - run.startedAt >= run.budget) failure = Failure.TIMEOUT;
        } catch (OperationCanceledException cancelled) { failure = Failure.CANCELLED; }
        catch (RuntimeException unavailable) { failure = Failure.ERROR; }
        final ChannelRepository.Discovery resultDiscovery = found;
        final ChannelRepository.Programs resultPrograms = programs; final Failure resultFailure = failure;
        synchronized (lock) {
            run.queryExited = true; releaseLocked(run);
            run.delivery = () -> completed(run, resultDiscovery, resultPrograms, resultFailure);
            main.post(run.delivery);
        }
    }
    private void completed(Run run, ChannelRepository.Discovery found, ChannelRepository.Programs programs, Failure failure) {
        requireMain(); main.removeCallbacks(run.timer);
        synchronized (lock) { run.delivery = null; }
        if (canRead() && currentRun == run && generation == run.generation && !run.stopRequested) {
            currentRun = null;
            if (failure != Failure.NONE) fail(run.kind, run.channel, failure);
            else if (run.kind == Kind.DISCOVERY) acceptDiscovery(found);
            else acceptPrograms(run.channel, programs);
            publish();
        }
        schedulePump(false);
    }
    private void acceptDiscovery(ChannelRepository.Discovery found) {
        discovery = found; discoveryFailure = failure(found.status); batch = null;
        if (discoveryFailure != Failure.NONE) {
            programCache.clear(); wantActive = false; activeFailure = activePackage == null ? Failure.NONE : discoveryFailure; return;
        }
        programCache.entrySet().removeIf(entry -> {
            RecommendationSources.Source source = findSource(entry.getKey());
            return !selectedPackages.contains(entry.getKey()) || source == null || !sameChannels(entry.getValue().channels, source.channels);
        });
        if (activePackage != null) {
            if (findSource(activePackage) == null) { activeFailure = Failure.UNAVAILABLE; wantActive = false; }
            else { activeFailure = Failure.NONE; wantActive = true; }
        }
    }
    private void acceptPrograms(ChannelRepository.Channel channel, ChannelRepository.Programs programs) {
        if (batch == null || !channel.packageName.equals(activePackage)) return;
        if (programs.channel == null || !channel.key.equals(programs.channel.key) || programs.items.size() > ChannelRepository.MAX_ITEMS) {
            fail(Kind.PROGRAMS, channel, Failure.ERROR); return;
        }
        if (programs.status == ChannelRepository.Status.DENIED || programs.status == ChannelRepository.Status.PERMISSION_REQUIRED) {
            // The TV-provider permission covers all apps, not only this channel.
            // Revoke every cached publication before any listener/switch can see it.
            programCache.clear(); discovery = null; discoveryFailure = Failure.ERROR;
            wantDiscovery = false; wantActive = false;
            batch.slices.clear(); batch.failedChannels++; batch.denied = programs.status;
            finishBatch(Failure.ERROR); return;
        }
        if (failure(programs.status) != Failure.NONE) { fail(Kind.PROGRAMS, channel, failure(programs.status)); return; }
        if (!addSlice(batch, channel, programs)) { fail(Kind.PROGRAMS, channel, Failure.ERROR); return; }
        compact(batch);
        // Keep the previous app publication stable until the whole batch finishes.
        if (batch.nextIndex >= batch.limit) finishBatch(Failure.NONE);
    }
    private void timeout(Run run) {
        if (!canRead() || currentRun != run || generation != run.generation) return;
        currentRun = null; requestCancellation(run); fail(run.kind, run.channel, Failure.TIMEOUT); publish(); schedulePump(false);
    }
    private void fail(Kind kind, ChannelRepository.Channel channel, Failure failure) {
        if (kind == Kind.DISCOVERY) {
            discovery = null; discoveryFailure = failure; programCache.clear(); wantActive = false; batch = null;
            activeFailure = activePackage == null ? Failure.NONE : failure;
        } else if (batch != null && channel.packageName.equals(batch.packageName)) {
            batch.failedChannels++;
            if (failure == Failure.TIMEOUT) {
                batch.incomplete = true; batch.capped |= batch.nextIndex < batch.limit; finishBatch(failure);
            } else if (batch.nextIndex >= batch.limit) finishBatch(Failure.NONE);
        }
    }
    private void finishBatch(Failure failure) {
        if (batch == null) return;
        AppPrograms result = new AppPrograms(batch, true);
        activeFailure = failure != Failure.NONE ? failure
                : result.items.isEmpty() && result.failedChannels > 0 ? Failure.ERROR : Failure.NONE;
        cache(result); batch = null;
    }
    private void cache(AppPrograms programs) {
        programCache.remove(programs.packageName); programCache.put(programs.packageName, programs);
        while (programCache.size() > MAX_CACHED_APPS) programCache.remove(programCache.keySet().iterator().next());
    }
    private void invalidateRun() {
        ++generation; batch = null;
        Run run = currentRun; currentRun = null;
        if (run != null) { main.removeCallbacks(run.timer); synchronized (lock) { if (run.delivery != null) main.removeCallbacks(run.delivery); } }
        synchronized (lock) { if (inFlight != null) requestCancellation(inFlight); }
    }
    private void requestCancellation(Run run) {
        synchronized (lock) {
            if (run.stopRequested) return;
            run.stopRequested = true; run.job.cancel(true);
            if (!run.started) run.queryExited = true;
            run.cancelPending = true;
            try {
                executor.execute(() -> {
                    try { run.signal.cancel(); }
                    catch (RuntimeException cancellationFailure) { /* Best effort; never restore ownership. */ }
                    finally {
                        synchronized (lock) { run.cancelPending = false; releaseLocked(run); }
                        main.post(() -> { if (canRead()) { publish(); schedulePump(false); } });
                    }
                });
            } catch (RejectedExecutionException unavailable) { run.cancelPending = false; }
            releaseLocked(run);
        }
    }
    private void releaseLocked(Run run) { if (inFlight == run && run.queryExited && !run.cancelPending) inFlight = null; }
    private RecommendationSources.Source findSource(String packageName) {
        for (RecommendationSources.Source source : RecommendationSources.group(discovery)) if (source.packageName.equals(packageName)) return source;
        return null;
    }
    private static boolean sameChannels(List<ChannelRepository.Channel> first, List<ChannelRepository.Channel> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) if (!first.get(i).key.equals(second.get(i).key)
                || first.get(i).versionCode != second.get(i).versionCode) return false;
        return true;
    }
    private boolean canRead() { return resumed && enabled && !closed; }
    private void publish() { ++revision; if (resumed && !closed && !emissionPosted) { emissionPosted = true; main.post(emission); } }
    private void clearScheduled() { main.removeCallbacks(pump); main.removeCallbacks(emission); pumpPosted = false; emissionPosted = false; }
    private void startObservers() {
        if (observing) return;
        long token = ++observerGeneration;
        observing = observers.start(() -> { if (token == observerGeneration) changed(true); },
                () -> { if (token == observerGeneration) changed(false); });
        if (!observing) ++observerGeneration;
    }
    private void stopObservers() { ++observerGeneration; observers.stop(); observing = false; }
    private static Failure failure(ChannelRepository.Status status) {
        if (status == ChannelRepository.Status.READY || status == ChannelRepository.Status.EMPTY || status == ChannelRepository.Status.PARTIAL) return Failure.NONE;
        return status == ChannelRepository.Status.CANCELLED ? Failure.CANCELLED : Failure.ERROR;
    }

    /** Pure aggregation boundary, also usable by controlled UI fixtures without provider queries. */
    public static AppPrograms aggregatePrograms(String packageName, List<ChannelRepository.Channel> channels,
            List<ChannelRepository.Programs> results, int failedChannels, boolean capped) {
        AppBatch batch = new AppBatch(packageName, channels, false, 0L);
        batch.capped |= capped; batch.failedChannels = Math.max(0, Math.min(batch.limit, failedChannels));
        int visited = 0;
        if (results != null) for (ChannelRepository.Programs result : results) {
            if (++visited > MAX_APP_CHANNELS) { batch.capped = true; break; }
            if (result == null || result.channel == null) { batch.failedChannels++; continue; }
            ChannelRepository.Channel channel = null;
            for (int i = 0; i < batch.limit; i++) if (batch.channels.get(i).key.equals(result.channel.key)) { channel = batch.channels.get(i); break; }
            if (channel == null || failure(result.status) != Failure.NONE || !addSlice(batch, channel, result)) batch.failedChannels++;
            compact(batch);
        }
        batch.incomplete |= batch.slices.size() + batch.failedChannels < batch.limit;
        return new AppPrograms(batch, true);
    }
    private static boolean addSlice(AppBatch batch, ChannelRepository.Channel channel, ChannelRepository.Programs result) {
        if (!batch.packageName.equals(channel.packageName) || result.items.size() > ChannelRepository.MAX_ITEMS) return false;
        if (batch.slices.containsKey(channel.key)) return true;
        List<ChannelRepository.Program> items = new ArrayList<>();
        for (ChannelRepository.Program item : result.items) {
            if (item == null || !channel.key.equals(item.channelKey) || !batch.packageName.equals(item.packageName)
                    || item.intentUri == null || item.intentUri.isEmpty()) return false;
            items.add(item);
        }
        batch.slices.put(channel.key, new Slice(result.status, result.completeness, items)); return true;
    }
    private static List<ChannelRepository.Program> mergedItems(AppBatch batch) {
        List<ChannelRepository.Program> result = new ArrayList<>(); Set<String> identities = new LinkedHashSet<>();
        // Round-robin preserves publisher ordering while giving every loaded shelf a place.
        for (int row = 0; row < ChannelRepository.MAX_ITEMS; row++) {
            for (ChannelRepository.Channel channel : batch.channels) {
                Slice slice = batch.slices.get(channel.key);
                if (slice == null || row >= slice.items.size()) continue;
                ChannelRepository.Program item = slice.items.get(row);
                if (!identities.add(item.intentUri)) continue;
                if (result.size() < MAX_APP_ITEMS) result.add(item); else batch.capped = true;
            }
        }
        return result;
    }
    private static void compact(AppBatch batch) {
        Set<ChannelRepository.Program> retained = Collections.newSetFromMap(new IdentityHashMap<>());
        retained.addAll(mergedItems(batch));
        for (Slice slice : batch.slices.values()) slice.items.removeIf(item -> !retained.contains(item));
    }
    private static final class Slice {
        final ChannelRepository.Status status; final ChannelRepository.Completeness completeness;
        final List<ChannelRepository.Program> items;
        Slice(ChannelRepository.Status status, ChannelRepository.Completeness completeness, List<ChannelRepository.Program> items) {
            this.status = status; this.completeness = completeness; this.items = items;
        }
    }
    private static final class AppBatch {
        final String packageName; final List<ChannelRepository.Channel> channels;
        final LinkedHashMap<String, Slice> slices = new LinkedHashMap<>();
        final long startedAt; final int limit;
        int nextIndex, failedChannels; boolean capped, incomplete; ChannelRepository.Status denied;
        AppBatch(String packageName, List<ChannelRepository.Channel> input, boolean incomplete, long startedAt) {
            this.packageName = packageName; this.incomplete = incomplete; this.startedAt = startedAt;
            List<ChannelRepository.Channel> channels = new ArrayList<>(); Set<String> seen = new LinkedHashSet<>();
            if (input != null) for (ChannelRepository.Channel channel : input) {
                if (channels.size() >= ChannelRepository.MAX_CHANNEL_ROWS) { capped = true; break; }
                if (channel != null && packageName.equals(channel.packageName) && seen.add(channel.key)) channels.add(channel);
            }
            this.channels = Collections.unmodifiableList(channels); limit = Math.min(MAX_APP_CHANNELS, channels.size());
            capped |= channels.size() > limit;
        }
    }
    private static Reader repositoryReader() {
        ChannelRepository repository = new ChannelRepository();
        return new Reader() {
            @Override public ChannelRepository.Discovery discover(Context app, CancellationSignal signal) { return repository.discover(app, signal); }
            @Override public ChannelRepository.Programs load(Context app, ChannelRepository.Channel channel, CancellationSignal signal) { return repository.load(app, channel, signal); }
        };
    }
    private static ExecutorService newExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2), task ->
                new Thread(task, "eve-box-recommendations-" + sequence.incrementAndGet()));
    }
    private static void requireMain() { if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Activity must own this session on main"); }
    private static final class Run {
        final long generation, startedAt = SystemClock.elapsedRealtime(), budget;
        final Kind kind; final ChannelRepository.Channel channel;
        final CancellationSignal signal = new CancellationSignal();
        FutureTask<Void> job; Runnable timer, delivery;
        boolean started, queryExited, stopRequested, cancelPending;
        Run(long generation, Kind kind, ChannelRepository.Channel channel, long budget) {
            this.generation = generation; this.kind = kind; this.channel = channel; this.budget = budget;
        }
    }
    private static final class AndroidObservers implements Observers {
        final Context app; final Handler main; ContentObserver channels, programs;
        AndroidObservers(Context app, Handler main) { this.app = app; this.main = main; }
        @Override public boolean start(Runnable channelsChanged, Runnable programsChanged) {
            stop();
            channels = observer(channelsChanged); programs = observer(programsChanged);
            try {
                app.getContentResolver().registerContentObserver(TvContract.Channels.CONTENT_URI, true, channels);
                app.getContentResolver().registerContentObserver(TvContract.PreviewPrograms.CONTENT_URI, true, programs);
                return true;
            } catch (RuntimeException unavailable) { stop(); return false; }
        }
        @Override public void stop() {
            if (channels != null) try { app.getContentResolver().unregisterContentObserver(channels); } catch (RuntimeException unavailable) { }
            if (programs != null) try { app.getContentResolver().unregisterContentObserver(programs); } catch (RuntimeException unavailable) { }
            channels = null; programs = null;
        }
        private ContentObserver observer(Runnable changed) {
            return new ContentObserver(main) {
                @Override public void onChange(boolean selfChange) { changed.run(); }
                @Override public void onChange(boolean selfChange, Uri uri) { changed.run(); }
            };
        }
    }
}
