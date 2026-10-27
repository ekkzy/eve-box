package io.github.ekkzy.softlumi.data;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.MatrixCursor;
import android.media.tv.TvContract;
import android.os.CancellationSignal;
import android.os.Looper;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Synthetic cursor fixtures only: no Activity, preferences, real provider query, or launched content. */
@RunWith(AndroidJUnit4.class)
public final class ChannelProbeSessionTest {
    private final Context app = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    private final List<ChannelProbeSession> sessions = new ArrayList<>();
    private final List<ExecutorService> executors = new ArrayList<>();

    @After public void closeSessions() throws Exception {
        onMain(() -> { for (ChannelProbeSession session : sessions) session.close(); });
        for (ExecutorService executor : executors) {
            executor.shutdownNow();
            assertTrue("Fake tasks must not leak test workers", executor.awaitTermination(4, TimeUnit.SECONDS));
        }
    }

    @Test public void onePassPrioritizesOwnersAndKeepsOtherResultsAfterOneLoadFails() throws Exception {
        ChannelRepository.Discovery discovery = discovery("other.z", "ru.ivi.client", "ru.kinopoisk.tv", "com.vk.tv",
                "other.a", "ru.ivi.client", "ru.kinopoisk.tv", "other.a");
        List<Long> loaded = new ArrayList<>(); AtomicBoolean workerOnly = new AtomicBoolean(true);
        ChannelProbeSession session = session(new FakeReader(discovery) {
            @Override public ChannelRepository.Discovery discover(Context context, CancellationSignal signal) {
                workerOnly.set(context == app && Looper.myLooper() != Looper.getMainLooper());
                return discovery;
            }
            @Override public ChannelRepository.Programs load(Context context, ChannelRepository.Channel channel, CancellationSignal signal) {
                workerOnly.set(workerOnly.get() && context == app && Looper.myLooper() != Looper.getMainLooper());
                loaded.add(channel.id);
                if ("com.vk.tv".equals(channel.packageName)) throw new IllegalStateException("synthetic failure");
                return empty(channel);
            }
        }, 2_000, executor());
        CountDownLatch delivered = new CountDownLatch(1); AtomicReference<ChannelProbeSession.Result> result = new AtomicReference<>();
        onMain(() -> assertTrue(session.start(value -> {
            assertSame(Looper.getMainLooper(), Looper.myLooper()); result.set(value); delivered.countDown();
        })));
        await(delivered, "Completed probe callback");
        assertTrue("All source reads use application context off main", workerOnly.get());
        assertEquals(Arrays.asList(3L, 2L, 4L, 5L, 1L, 6L), loaded);
        assertEquals(ChannelProbeSession.Outcome.ERROR, result.get().outcome);
        assertEquals("General diagnostics have no implicit source scope", "", result.get().sourcePackage);
        assertSame(discovery, result.get().discovery); assertEquals(6, result.get().programs.size());
        assertEquals(ChannelRepository.Status.QUERY_FAILED, result.get().programs.get(2).status);
        assertEquals(ChannelRepository.Status.EMPTY, result.get().programs.get(5).status);
        try { result.get().programs.clear(); fail("Result list must be immutable"); }
        catch (UnsupportedOperationException expected) { }
    }

    @Test public void manualSourcePassReadsItsHiddenShelvesWithoutLoadingUnrelatedSources() throws Exception {
        ChannelRepository.Discovery discovery = discovery(false, "other.a", "ru.kinopoisk.tv", "ru.ivi.client", "com.vk.tv",
                "other.b", "other.c", "other.d", "ru.rutube.app", "ru.ivi.client");
        assertTrue("These fixtures are hidden in the system Home", discovery.channels.isEmpty());
        assertEquals("Searchable hidden channels remain available for local selection", 9, discovery.selectableChannels.size());
        AtomicInteger discoveryCalls = new AtomicInteger(); List<Long> loaded = new ArrayList<>();
        ChannelProbeSession session = session(new FakeReader(discovery) {
            @Override public ChannelRepository.Discovery discover(Context context, CancellationSignal signal) {
                discoveryCalls.incrementAndGet(); return discovery;
            }
            @Override public ChannelRepository.Programs load(Context context, ChannelRepository.Channel channel, CancellationSignal signal) {
                loaded.add(channel.id); return empty(channel);
            }
        }, 2_000, executor());
        onMain(() -> {
            for (String invalid : new String[]{null, "", "ru.ivi.client/", " ru.ivi.client", "ru.ivi.client\n"})
                assertFalse("Malformed scope must not schedule a pass", session.startSource(invalid, result -> fail("Invalid package callback")));
        });
        assertEquals(0, discoveryCalls.get());

        ChannelProbeSession.Result ivi = runSource(session, "ru.ivi.client");
        assertEquals(ChannelProbeSession.Outcome.COMPLETE, ivi.outcome);
        assertEquals("ru.ivi.client", ivi.sourcePackage);
        assertEquals("Both Ivi shelves are checked, with no unrelated program query", Arrays.asList(3L, 9L), loaded);
        assertEquals(2, ivi.programs.size());
        for (ChannelRepository.Programs programs : ivi.programs) {
            assertEquals("ru.ivi.client", programs.channel.packageName);
            assertFalse(programs.channel.systemBrowsable);
        }

        loaded.clear();
        ChannelProbeSession.Result rutube = runSource(session, "ru.rutube.app");
        assertEquals("ru.rutube.app", rutube.sourcePackage);
        assertEquals("A source outside the general six-slot sample can be checked directly", Arrays.asList(8L), loaded);
        assertEquals(1, rutube.programs.size());
        assertEquals(2, discoveryCalls.get());
    }

    @Test public void cancellationBeforeWorkerEntryDoesNotLeaveSessionPermanentlyBusy() throws Exception {
        ExecutorService executor = executor(); CountDownLatch occupied = new CountDownLatch(2), release = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) executor.execute(() -> { occupied.countDown(); awaitIgnoringInterrupt(release); });
        await(occupied, "Both test workers are occupied before the pass queues");
        AtomicInteger reads = new AtomicInteger(), oldCallbacks = new AtomicInteger();
        ChannelProbeSession session = session(new FakeReader(discovery()) {
            @Override public ChannelRepository.Discovery discover(Context context, CancellationSignal signal) {
                reads.incrementAndGet(); return super.discover(context, signal);
            }
        }, 2_000, executor);
        try {
            onMain(() -> {
                assertTrue(session.start(result -> oldCallbacks.incrementAndGet()));
                session.cancel();
                assertFalse("Queued cancellation is still owned", session.start(result -> fail("Unexpected early start")));
            });
        } finally { release.countDown(); }
        CountDownLatch completed = new CountDownLatch(1);
        startWhenIdle(session, result -> completed.countDown()); await(completed, "A fresh pass starts after queued cancellation exits");
        assertEquals("The cancelled queued pass never touched its reader", 1, reads.get());
        assertEquals("Cancelled ownership cannot receive a callback", 0, oldCallbacks.get());
    }

    @Test public void timeoutPublishesPartialCountsButRestartWaitsForQueryAndCancellationExit() throws Exception {
        ChannelRepository.Discovery discovery = discovery("ru.kinopoisk.tv", "ru.ivi.client");
        CountDownLatch queryEntered = new CountDownLatch(1), queryRelease = new CountDownLatch(1), queryReturned = new CountDownLatch(1);
        CountDownLatch cancelEntered = new CountDownLatch(1), cancelRelease = new CountDownLatch(1);
        AtomicBoolean firstSlowRead = new AtomicBoolean(true), cancellationOffMain = new AtomicBoolean();
        ChannelProbeSession session = session(new FakeReader(discovery) {
            @Override public ChannelRepository.Programs load(Context context, ChannelRepository.Channel channel, CancellationSignal signal) {
                if ("ru.ivi.client".equals(channel.packageName) && firstSlowRead.getAndSet(false)) {
                    signal.setOnCancelListener(() -> {
                        cancellationOffMain.set(Looper.myLooper() != Looper.getMainLooper());
                        cancelEntered.countDown(); awaitIgnoringInterrupt(cancelRelease);
                    });
                    queryEntered.countDown(); awaitIgnoringInterrupt(queryRelease); queryReturned.countDown();
                }
                return empty(channel);
            }
        }, 1_000, executor());
        AtomicInteger callbacks = new AtomicInteger(); AtomicReference<ChannelProbeSession.Result> timedOut = new AtomicReference<>();
        CountDownLatch delivered = new CountDownLatch(1);
        try {
            onMain(() -> assertTrue(session.start(result -> { callbacks.incrementAndGet(); timedOut.set(result); delivered.countDown(); })));
            await(queryEntered, "The second channel is physically blocked"); await(delivered, "The main deadline still delivers");
            await(cancelEntered, "Cooperative cancellation uses the separate bounded worker");
            assertTrue(cancellationOffMain.get());
            assertEquals(ChannelProbeSession.Outcome.TIMEOUT, timedOut.get().outcome);
            assertSame(discovery, timedOut.get().discovery);
            assertEquals("The already completed channel survives timeout", 1, timedOut.get().programs.size());
            onMain(() -> {
                assertFalse("A timeout cannot create another physical read", session.start(result -> fail("Concurrent query")));
                session.cancel(); session.cancel(); // Repeated lifecycle stops do not queue duplicate cancellation.
            });
            queryRelease.countDown(); await(queryReturned, "The late query finally returns");
            onMain(() -> assertFalse("A blocked cancellation also keeps the session bounded", session.start(result -> fail("Concurrent cancellation"))));
            cancelRelease.countDown();
            CountDownLatch restarted = new CountDownLatch(1); AtomicReference<ChannelProbeSession.Result> next = new AtomicReference<>();
            startWhenIdle(session, result -> { next.set(result); restarted.countDown(); });
            await(restarted, "Fresh generation after both tasks physically exit");
            assertEquals(ChannelProbeSession.Outcome.COMPLETE, next.get().outcome);
            assertEquals(2, next.get().programs.size());
            assertEquals("The late old result cannot overwrite or redeliver", 1, callbacks.get());
            onMain(() -> { session.close(); assertFalse("Close is terminal", session.start(result -> fail("Closed session"))); });
        } finally { queryRelease.countDown(); cancelRelease.countDown(); }
    }

    private ChannelProbeSession session(ChannelProbeSession.Reader reader, long deadline, ExecutorService executor) {
        ChannelProbeSession session = new ChannelProbeSession(app, reader, deadline, executor); sessions.add(session); return session;
    }
    private ExecutorService executor() {
        ExecutorService executor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2));
        executors.add(executor); return executor;
    }
    private static class FakeReader implements ChannelProbeSession.Reader {
        final ChannelRepository.Discovery discovery;
        FakeReader(ChannelRepository.Discovery discovery) { this.discovery = discovery; }
        @Override public ChannelRepository.Discovery discover(Context context, CancellationSignal signal) { return discovery; }
        @Override public ChannelRepository.Programs load(Context context, ChannelRepository.Channel channel, CancellationSignal signal) { return empty(channel); }
    }
    private static ChannelRepository.Discovery discovery(String... packages) {
        return discovery(true, packages);
    }
    private static ChannelRepository.Discovery discovery(boolean systemBrowsable, String... packages) {
        MatrixCursor cursor = new MatrixCursor(ChannelRepository.CHANNEL_PROJECTION);
        for (int i = 0; i < packages.length; i++) cursor.addRow(new Object[]{i + 1L, packages[i], TvContract.Channels.TYPE_PREVIEW, "Fixture", systemBrowsable ? 1 : 0, 1});
        return ChannelRepository.discoverRows(() -> cursor, name -> new ChannelRepository.Owner(name, 100, 1), 0, () -> false);
    }
    private static ChannelRepository.Programs empty(ChannelRepository.Channel channel) {
        return ChannelRepository.loadRows(() -> new MatrixCursor(ChannelRepository.PROGRAM_REQUIRED), channel,
                (owner, uri) -> PublishedContentIntents.TargetKind.UNAVAILABLE, () -> false);
    }
    private static void startWhenIdle(ChannelProbeSession session, ChannelProbeSession.Listener listener) {
        long deadline = SystemClock.uptimeMillis() + 4_000; AtomicBoolean accepted = new AtomicBoolean();
        do {
            onMain(() -> accepted.set(session.start(listener)));
            if (accepted.get()) return;
            SystemClock.sleep(10);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Session never released its physical running guard");
    }
    private static ChannelProbeSession.Result runSource(ChannelProbeSession session, String packageName) throws InterruptedException {
        CountDownLatch delivered = new CountDownLatch(1); AtomicReference<ChannelProbeSession.Result> result = new AtomicReference<>();
        onMain(() -> assertTrue(session.startSource(packageName, value -> { result.set(value); delivered.countDown(); })));
        await(delivered, "Manual source probe callback"); return result.get();
    }
    private static void onMain(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private static void await(CountDownLatch latch, String message) throws InterruptedException { assertTrue(message, latch.await(4, TimeUnit.SECONDS)); }
    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) try { latch.await(); break; } catch (InterruptedException ignored) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
