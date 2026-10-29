package io.github.ekkzy.evebox.data;

import android.content.Context;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OperationCanceledException;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One manually requested, read-only diagnostic pass. The Activity owns start/cancel/close on main.
 * Call cancel from onStop and close from onDestroy; neither waits for a provider or calls its cancel
 * listener on main. A deadline stops delivery, not a stuck Binder: restart waits for physical exit.
 */
public final class ChannelProbeSession implements AutoCloseable {
    public static final long DEADLINE_MILLIS = 12_000L;
    public enum Outcome { COMPLETE, TIMEOUT, CANCELLED, ERROR }
    public interface Listener { void onResult(Result result); }

    public static final class Result {
        public final ChannelRepository.Discovery discovery;
        public final List<ChannelRepository.Programs> programs;
        public final Outcome outcome;
        public final long elapsedMillis;
        /** Exact manually requested package, or empty for the general diagnostic pass. */
        public final String sourcePackage;
        private Result(ChannelRepository.Discovery discovery, List<ChannelRepository.Programs> programs,
                Outcome outcome, long elapsedMillis, String sourcePackage) {
            this.discovery = discovery;
            this.programs = Collections.unmodifiableList(new ArrayList<>(programs));
            this.outcome = outcome;
            this.elapsedMillis = elapsedMillis;
            this.sourcePackage = sourcePackage;
        }
    }

    interface Reader {
        ChannelRepository.Discovery discover(Context app, CancellationSignal signal);
        ChannelRepository.Programs load(Context app, ChannelRepository.Channel channel, CancellationSignal signal);
    }

    private final Context app;
    private final Reader reader;
    private final ExecutorService executor;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final long deadlineMillis;
    private final Object lock = new Object();
    private long generation;
    private boolean closed;
    private Run current, inFlight;

    public ChannelProbeSession(Context context) {
        this(context, repositoryReader(), DEADLINE_MILLIS, newExecutor());
    }

    /** Package-private seam for deterministic cancellation/timeout tests; never accesses a provider. */
    ChannelProbeSession(Context context, Reader reader, long deadlineMillis, ExecutorService executor) {
        this.app = Objects.requireNonNull(context.getApplicationContext(), "application context");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.executor = Objects.requireNonNull(executor, "executor");
        if (deadlineMillis <= 0) throw new IllegalArgumentException("positive deadline required");
        this.deadlineMillis = deadlineMillis;
    }

    /** False means closed or a previous query/cancellation has not physically returned yet. */
    public boolean start(Listener listener) {
        return startRun("", listener);
    }

    /** Manual source-only pass. Invalid package names return false without scheduling discovery. */
    public boolean startSource(String packageName, Listener listener) {
        requireMain(); Objects.requireNonNull(listener, "listener");
        if (!ChannelRepository.validPackage(packageName)) return false;
        return startRun(packageName, listener);
    }

    private boolean startRun(String sourcePackage, Listener listener) {
        requireMain(); Objects.requireNonNull(listener, "listener");
        synchronized (lock) {
            if (closed || inFlight != null) return false;
            clearDeliveryLocked(current);
            Run run = new Run(++generation, sourcePackage, listener);
            current = run; inFlight = run;
            run.job = new FutureTask<>(() -> { execute(run); return null; });
            run.timer = () -> timeout(run);
            try {
                executor.execute(run.job);
                main.postDelayed(run.timer, deadlineMillis);
                return true;
            } catch (RejectedExecutionException unavailable) {
                run.queryExited = true; current = null; inFlight = null; run.listener = null;
                return false;
            }
        }
    }

    /** Revokes callback ownership immediately; an explicit cancellation has no terminal callback. */
    public void cancel() {
        requireMain();
        synchronized (lock) {
            ++generation; clearDeliveryLocked(current); current = null;
            if (inFlight != null) requestCancellationLocked(inFlight);
        }
    }

    @Override public void close() {
        requireMain();
        synchronized (lock) {
            if (closed) return;
            closed = true; ++generation; clearDeliveryLocked(current); current = null;
            if (inFlight != null) requestCancellationLocked(inFlight);
            // Let an already queued cancellation run. Never await termination on the UI thread.
            executor.shutdown();
        }
    }

    private void execute(Run run) {
        synchronized (lock) {
            // FutureTask.cancel can win after its runner starts but before this body enters.
            if (inFlight != run || run.stopRequested) return;
            run.started = true;
        }
        Outcome outcome;
        try { outcome = read(run); }
        catch (OperationCanceledException cancelled) { outcome = cancellationOutcome(run); }
        catch (RuntimeException failure) { outcome = Outcome.ERROR; }
        Result result;
        synchronized (lock) {
            run.queryExited = true;
            releaseIfExitedLocked(run);
            if (!ownsDeliveryLocked(run)) return;
            main.removeCallbacks(run.timer);
            result = snapshotLocked(run, outcome);
        }
        // Only counts reach the diagnostic log. No disk/log work holds the lifecycle lock.
        ChannelProbeReport.log(app,result);
        synchronized (lock) {
            if (!ownsDeliveryLocked(run)) return;
            run.delivery = () -> deliver(run, result);
            main.post(run.delivery);
        }
    }

    private Outcome read(Run run) {
        Outcome interrupted = checkpoint(run); if (interrupted != null) return interrupted;
        ChannelRepository.Discovery discovery = reader.discover(app, run.signal);
        interrupted = checkpoint(run); if (interrupted != null) return interrupted;
        if (discovery == null) return Outcome.ERROR;
        synchronized (lock) { if (ownsDeliveryLocked(run)) run.discovery = discovery; }
        if (discovery.status == ChannelRepository.Status.CANCELLED) return Outcome.CANCELLED;
        boolean failed = false;
        for (ChannelRepository.Channel channel : selectChannels(discovery.selectableChannels, run.scopePackage)) {
            interrupted = checkpoint(run); if (interrupted != null) return interrupted;
            ChannelRepository.Programs programs;
            try {
                programs = reader.load(app, channel, run.signal);
                if (programs == null) throw new IllegalStateException("missing channel result");
            } catch (OperationCanceledException cancelled) { throw cancelled; }
            catch (RuntimeException failure) {
                failed = true;
                programs = ChannelRepository.failedPrograms(channel, ChannelRepository.Status.QUERY_FAILED);
            }
            interrupted = checkpoint(run); if (interrupted != null) return interrupted;
            synchronized (lock) { if (ownsDeliveryLocked(run)) run.programs.add(programs); }
            if (programs.status == ChannelRepository.Status.CANCELLED) return Outcome.CANCELLED;
        }
        return failed ? Outcome.ERROR : Outcome.COMPLETE;
    }

    private Outcome checkpoint(Run run) {
        synchronized (lock) {
            if (run.stopRequested || closed || !ownsDeliveryLocked(run))
                return run.timedOut ? Outcome.TIMEOUT : Outcome.CANCELLED;
        }
        if (SystemClock.elapsedRealtime() - run.startedAt >= deadlineMillis) return Outcome.TIMEOUT;
        return run.signal.isCanceled() || Thread.currentThread().isInterrupted() ? Outcome.CANCELLED : null;
    }

    private Outcome cancellationOutcome(Run run) {
        synchronized (lock) {
            return run.timedOut || SystemClock.elapsedRealtime() - run.startedAt >= deadlineMillis
                    ? Outcome.TIMEOUT : Outcome.CANCELLED;
        }
    }

    private void timeout(Run run) {
        requireMain();
        Listener listener; Result result;
        synchronized (lock) {
            if (!ownsDeliveryLocked(run)) return;
            long remaining = deadlineMillis - (SystemClock.elapsedRealtime() - run.startedAt);
            if (remaining > 0) { main.postDelayed(run.timer, remaining); return; }
            result = snapshotLocked(run, Outcome.TIMEOUT); listener = run.listener;
            run.timedOut = true; clearDeliveryLocked(run); current = null; ++generation;
            if (!run.queryExited) requestCancellationLocked(run);
        }
        listener.onResult(result);
    }

    private void deliver(Run run, Result result) {
        requireMain(); Listener listener;
        synchronized (lock) {
            if (!ownsDeliveryLocked(run)) return;
            listener = run.listener; clearDeliveryLocked(run); current = null;
        }
        listener.onResult(result);
    }

    private boolean ownsDeliveryLocked(Run run) {
        return !closed && current == run && generation == run.generation && run.listener != null && !run.stopRequested;
    }

    private Result snapshotLocked(Run run, Outcome outcome) {
        return new Result(run.discovery, run.programs, outcome, Math.max(0, SystemClock.elapsedRealtime() - run.startedAt), run.scopePackage);
    }

    private void clearDeliveryLocked(Run run) {
        if (run == null) return;
        run.listener = null;
        if (run.timer != null) main.removeCallbacks(run.timer);
        if (run.delivery != null) main.removeCallbacks(run.delivery);
    }

    private void requestCancellationLocked(Run run) {
        if (run.stopRequested) return;
        run.stopRequested = true; run.job.cancel(true);
        // A cancelled FutureTask may never enter execute(), so no worker finally will release it.
        if (!run.started) run.queryExited = true;
        run.cancelPending = true;
        try {
            executor.execute(() -> {
                try { run.signal.cancel(); }
                catch (RuntimeException cancellationFailure) {
                    // Cancellation is best effort. Ownership stays revoked and the query must exit.
                } finally {
                    synchronized (lock) { run.cancelPending = false; releaseIfExitedLocked(run); }
                }
            });
        } catch (RejectedExecutionException unavailable) {
            run.cancelPending = false;
        }
        releaseIfExitedLocked(run);
    }

    private void releaseIfExitedLocked(Run run) {
        if (inFlight == run && run.queryExited && !run.cancelPending) inFlight = null;
    }

    /** One channel per owner first, then remaining stable channel ids; never provider-specific I/O. */
    static List<ChannelRepository.Channel> selectChannels(List<ChannelRepository.Channel> channels, String sourcePackage) {
        List<ChannelRepository.Channel> ordered = new ArrayList<>();
        for (ChannelRepository.Channel channel : channels)
            if (sourcePackage.isEmpty() || sourcePackage.equals(channel.packageName)) ordered.add(channel);
        Comparator<ChannelRepository.Channel> stable = Comparator.comparingLong((ChannelRepository.Channel c) -> c.id)
                .thenComparing(c -> c.key);
        ordered.sort(Comparator.comparingInt((ChannelRepository.Channel c) -> ownerPriority(c.packageName))
                .thenComparing(c -> c.packageName).thenComparing(stable));
        List<ChannelRepository.Channel> selected = new ArrayList<>();
        Set<String> owners = new HashSet<>(), keys = new HashSet<>();
        for (ChannelRepository.Channel channel : ordered) {
            if (selected.size() == ChannelRepository.MAX_SELECTED_CHANNELS) break;
            if (owners.add(channel.packageName) && keys.add(channel.key)) selected.add(channel);
        }
        ordered.sort(stable);
        for (ChannelRepository.Channel channel : ordered) {
            if (selected.size() == ChannelRepository.MAX_SELECTED_CHANNELS) break;
            if (keys.add(channel.key)) selected.add(channel);
        }
        return selected;
    }

    private static int ownerPriority(String packageName) {
        if ("ru.kinopoisk.tv".equals(packageName)) return 0;
        if ("ru.ivi.client".equals(packageName)) return 1;
        if ("com.vk.tv".equals(packageName)) return 2;
        return 3;
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
        // The second fixed worker only prevents cooperative cancellation from blocking UI/query exit.
        return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2), task ->
                new Thread(task, "eve-box-channel-probe-" + sequence.incrementAndGet()));
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Activity must own this session on main");
    }

    private static final class Run {
        final long generation, startedAt = SystemClock.elapsedRealtime();
        final String scopePackage;
        final CancellationSignal signal = new CancellationSignal();
        final List<ChannelRepository.Programs> programs = new ArrayList<>();
        Listener listener;
        ChannelRepository.Discovery discovery;
        FutureTask<Void> job;
        Runnable timer, delivery;
        boolean started, queryExited, stopRequested, cancelPending, timedOut;
        Run(long generation, String scopePackage, Listener listener) {
            this.generation = generation; this.scopePackage = scopePackage; this.listener = listener;
        }
    }
}
