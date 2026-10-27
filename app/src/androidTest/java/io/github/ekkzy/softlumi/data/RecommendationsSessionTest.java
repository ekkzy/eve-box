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
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/** In-memory cursors/observers only: no installed publications, preferences or network. */
@RunWith(AndroidJUnit4.class)
public final class RecommendationsSessionTest {
    private static final String A = "example.provider0", B = "example.provider1";
    private final Context app = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    private final List<Fixture> fixtures = new ArrayList<>();
    @After public void closeSessions() throws Exception {
        onMain(() -> { for (Fixture fixture : fixtures) fixture.session.close(); });
        for (Fixture fixture : fixtures) {
            fixture.executor.shutdownNow();
            assertTrue("No synthetic worker may leak", fixture.executor.awaitTermination(4, TimeUnit.SECONDS));
        }
    }
    @Test public void disabledAndContinueNeverReadProgramsAndIdenticalConfigurationIsNoOp() {
        FakeReader reader = new FakeReader(discovery(100, 1, 2, 101)); Fixture f = fixture(reader, 2_000);
        onMain(f.session::resume); await(f, state -> state.resumed && !state.enabled);
        assertEquals(0, reader.discoveryCalls.get()); assertFalse(f.observers.active);
        onMain(() -> f.session.configure(true, Collections.emptyList(), null));
        await(f, state -> state.discovery != null && settled(state));
        assertTrue(f.observers.active); assertTrue(reader.loads.isEmpty());
        onMain(() -> f.session.configure(true, Arrays.asList(A, B), null));
        await(f, state -> settled(state) && state.selectedPackages.size() == 2);
        onMain(() -> {
            long revision = f.session.snapshot().revision;
            f.session.configure(true, Arrays.asList(A, B), null);
            assertEquals("Identical input must not invalidate Home", revision, f.session.snapshot().revision);
            f.session.configure(true, Arrays.asList(A, B), "not.selected");
            assertEquals("Unselected apps cannot become active", revision, f.session.snapshot().revision);
            for (int i = 0; i < 20; i++) f.observers.programsChanged.run();
        });
        assertTrue("Continue is not inactive-app prefetch", reader.loads.isEmpty());
        onMain(() -> f.session.configure(true, Arrays.asList(A, B), A));
        await(f, state -> settled(state) && state.activePrograms() != null);
        assertEquals(Arrays.asList(key(reader.discovery.get(), 1), key(reader.discovery.get(), 2)), reader.loads);
        onMain(() -> f.session.configure(true, Arrays.asList(A, B), null)); await(f, state -> settled(state) && state.activePackage == null);
        Runnable[] oldObserver = new Runnable[1];
        onMain(() -> { oldObserver[0] = f.observers.channelsChanged; f.session.pause(); f.observers.programsChanged.run(); });
        assertFalse(f.observers.active); assertEquals(2, reader.loads.size());
        onMain(f.session::resume); await(f, state -> settled(state) && state.resumed);
        onMain(() -> { long revision = f.session.snapshot().revision; oldObserver[0].run(); assertEquals(revision, f.session.snapshot().revision); });
        assertTrue(reader.workerOnly.get()); assertEquals(2, reader.loads.size());
    }
    @Test public void changingTheActiveAppDropsLateResultsAndNeverOverlapsTheOldPhysicalRead() throws Exception {
        ChannelRepository.Discovery found = discovery(100, 1, 2, 101); String first = key(found, 1), secondApp = key(found, 101);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        FakeReader reader = new FakeReader(found) {
            @Override public ChannelRepository.Programs load(Context context, ChannelRepository.Channel channel, CancellationSignal signal) {
                record(context, channel); if (channel.id == 1) { entered.countDown(); awaitIgnoringInterrupt(release); } return programs(channel, 3);
            }
        };
        Fixture f = fixture(reader, 2_000);
        try {
            onMain(() -> { f.session.configure(true, Arrays.asList(A, B), A); f.session.resume(); });
            latch(entered, "First app enters its physical read");
            onMain(() -> f.session.configure(true, Arrays.asList(A, B), B));
            await(f, state -> B.equals(state.activePackage) && state.pendingRefresh && state.queryRunning);
            assertEquals(Collections.singletonList(first), reader.loads);
            release.countDown(); await(f, state -> settled(state) && B.equals(state.activePackage) && state.activePrograms() != null);
            assertEquals(Arrays.asList(first, secondApp), reader.loads);
            assertFalse("The cancelled app's later shelves are never read", reader.loads.contains(key(found, 2)));
            for (RecommendationsSession.Snapshot state : f.history) assertFalse("Late publications cannot enter cache", state.programsByPackage.containsKey(A));
        } finally { release.countDown(); }
    }
    @Test public void observerBurstsCoalesceAndOnlyOneFollowUpSurvivesABlockedRead() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), fourth = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger();
        FakeReader reader = new FakeReader(discovery(100, 1)) {
            @Override public ChannelRepository.Programs load(Context context, ChannelRepository.Channel channel, CancellationSignal signal) {
                record(context, channel); int number = count.incrementAndGet();
                if (number == 2) { entered.countDown(); awaitIgnoringInterrupt(release); }
                if (number >= 4) fourth.countDown(); return programs(channel, 3);
            }
        };
        Fixture f = fixture(reader, 2_000);
        try {
            onMain(() -> { f.session.configure(true, Collections.singletonList(A), A); f.session.resume(); });
            await(f, state -> settled(state) && state.activePrograms() != null);
            onMain(() -> { for (int i = 0; i < 30; i++) f.observers.programsChanged.run(); }); latch(entered, "One coalesced refresh starts");
            onMain(() -> { for (int i = 0; i < 30; i++) f.observers.programsChanged.run(); }); assertEquals(2, count.get());
            release.countDown(); await(f, state -> settled(state) && count.get() == 3);
            assertFalse("Settled results do not start a retry loop", fourth.await(350, TimeUnit.MILLISECONDS));
        } finally { release.countDown(); }
    }
    @Test public void timeoutRetainsBusyGuardAndPauseRevokesAllLateCallbacks() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); AtomicBoolean first = new AtomicBoolean(true);
        FakeReader reader = new FakeReader(discovery(100, 1)) {
            @Override public ChannelRepository.Programs load(Context context, ChannelRepository.Channel channel, CancellationSignal signal) {
                record(context, channel); if (first.getAndSet(false)) { entered.countDown(); awaitIgnoringInterrupt(release); } return programs(channel, 3);
            }
        };
        Fixture f = fixture(reader, 500);
        try {
            onMain(() -> { f.session.configure(true, Collections.singletonList(A), A); f.session.resume(); }); latch(entered, "The active read blocks");
            await(f, state -> state.activeFailure == RecommendationsSession.Failure.TIMEOUT && state.queryRunning);
            onMain(() -> { for (int i = 0; i < 20; i++) f.session.refreshActive(); }); assertEquals(1, reader.loads.size());
            onMain(f.session::pause); int callbacks = f.history.size(); release.countDown(); await(f, state -> !state.queryRunning);
            onMain(() -> { f.observers.channelsChanged.run(); f.observers.programsChanged.run(); });
            assertEquals("Stopped listeners receive no stale result or observer event", callbacks, f.history.size());
            assertFalse(f.observers.active); onMain(f.session::resume);
            await(f, state -> settled(state) && state.activePrograms() != null);
            assertEquals(2, reader.loads.size()); assertEquals(2, reader.discoveryCalls.get());
        } finally { release.countDown(); }
    }
    @Test public void enabledAppIncludesNewShelvesAndDropsHiddenOrDisappearedOnFreshDiscovery() {
        FakeReader reader = new FakeReader(discovery(100, 1, 2, 101)); Fixture f = fixture(reader, 2_000);
        onMain(() -> { f.session.configure(true, Arrays.asList(A, B), A); f.session.resume(); });
        await(f, state -> settled(state) && state.activePrograms() != null && state.activePrograms().items.size() == 6);
        assertEquals(2, reader.loads.size());
        reader.discovery.set(discovery(100, 1, 2, 3, 101)); onMain(f.session::refreshDiscovery);
        await(f, state -> settled(state) && state.activePrograms() != null && state.activePrograms().loadedChannels == 3);
        assertEquals(9, f.sessionState().activePrograms().items.size()); assertEquals(5, reader.loads.size());
        reader.discovery.set(discoveryWithHidden(100, 2, 2, 3, 101)); onMain(f.session::refreshDiscovery);
        await(f, state -> settled(state) && state.activePrograms() != null && state.activePrograms().channels.size() == 1);
        RecommendationsSession.AppPrograms visible = f.sessionState().activePrograms();
        assertEquals(3, visible.items.size()); for (ChannelRepository.Program item : visible.items) assertEquals(3L, visible.channelFor(item).id);
        assertFalse("A selected inactive app is not prefetched", reader.loads.contains(key(reader.discovery.get(), 101)));
        reader.discovery.set(discovery(100, 101)); onMain(f.session::refreshDiscovery);
        await(f, state -> settled(state) && state.activeFailure == RecommendationsSession.Failure.UNAVAILABLE);
        assertEquals(A, f.sessionState().activePackage); assertFalse(f.sessionState().programsByPackage.containsKey(A));
        assertEquals(Arrays.asList(A, B), f.sessionState().selectedPackages);
    }
    @Test public void warmAppRemainsPublishedUntilItsSuccessfulOrPartialRefreshFinishes() throws Exception {
        AtomicInteger phase = new AtomicInteger();
        CountDownLatch successEntered = new CountDownLatch(1), successRelease = new CountDownLatch(1);
        CountDownLatch partialEntered = new CountDownLatch(1), partialRelease = new CountDownLatch(1);
        FakeReader reader = new FakeReader(discovery(100, 1, 2)) {
            @Override public ChannelRepository.Programs load(Context context, ChannelRepository.Channel channel, CancellationSignal signal) {
                record(context, channel); int current = phase.get();
                if (current == 2 && channel.id == 1) throw new IllegalStateException("Synthetic first-shelf failure");
                if (channel.id == 2 && current > 0) {
                    (current == 1 ? successEntered : partialEntered).countDown();
                    awaitIgnoringInterrupt(current == 1 ? successRelease : partialRelease);
                }
                return programs(channel, current == 1 ? 2 : 3);
            }
        };
        Fixture f = fixture(reader, 4_000);
        try {
            onMain(() -> { f.session.configure(true, Collections.singletonList(A), A); f.session.resume(); });
            await(f, state -> settled(state) && state.activePrograms() != null);
            RecommendationsSession.AppPrograms initial = f.sessionState().activePrograms();
            assertEquals(6, initial.items.size());

            phase.set(1); int successHistory = f.history.size(); onMain(f.session::refreshActive);
            latch(successEntered, "The refreshed first shelf completes before the second blocks");
            RecommendationsSession.Snapshot refreshing = f.sessionState();
            assertTrue(refreshing.activeLoading); assertSame("Later-shelf cards stay available while refreshing", initial, refreshing.activePrograms());
            List<RecommendationsSession.Snapshot> successSnapshots = new ArrayList<>(f.history);
            for (RecommendationsSession.Snapshot state : successSnapshots.subList(successHistory, successSnapshots.size()))
                assertSame("No callback may temporarily remove a warm app's later-shelf cards", initial, state.activePrograms());
            successRelease.countDown();
            await(f, state -> settled(state) && state.activePrograms() != initial && state.activePrograms().items.size() == 4);
            RecommendationsSession.AppPrograms refreshed = f.sessionState().activePrograms();
            assertEquals(ChannelRepository.Status.READY, refreshed.status); assertEquals(2, refreshed.loadedChannels);

            phase.set(2); int partialHistory = f.history.size(); onMain(f.session::refreshActive);
            latch(partialEntered, "A failed first shelf must not publish before the second completes");
            RecommendationsSession.Snapshot partiallyRefreshing = f.sessionState();
            assertTrue(partiallyRefreshing.activeLoading); assertSame(refreshed, partiallyRefreshing.activePrograms());
            List<RecommendationsSession.Snapshot> partialSnapshots = new ArrayList<>(f.history);
            for (RecommendationsSession.Snapshot state : partialSnapshots.subList(partialHistory, partialSnapshots.size()))
                assertSame("An intermediate error must not erase the warm app publication", refreshed, state.activePrograms());
            partialRelease.countDown();
            await(f, state -> settled(state) && state.activePrograms() != refreshed && state.activePrograms().items.size() == 3);
            RecommendationsSession.AppPrograms partial = f.sessionState().activePrograms();
            assertEquals(ChannelRepository.Status.PARTIAL, partial.status); assertEquals(1, partial.loadedChannels); assertEquals(1, partial.failedChannels);
            for (ChannelRepository.Program item : partial.items) assertEquals(2L, partial.channelFor(item).id);
            assertEquals("Every explicit refresh reads each shelf once", 6, reader.loads.size());
        } finally { successRelease.countDown(); partialRelease.countDown(); }
    }
    @Test public void mixedChannelErrorsPreserveSuccessfulProgramsAndTheirExactLaunchChannels() {
        FakeReader reader = new FakeReader(discovery(100, 1, 2, 3, 101)) {
            @Override public ChannelRepository.Programs load(Context context, ChannelRepository.Channel channel, CancellationSignal signal) {
                record(context, channel); if (channel.id == 2) throw new IllegalStateException("Synthetic provider failure"); return programs(channel, 3);
            }
        };
        Fixture f = fixture(reader, 2_000);
        onMain(() -> { f.session.configure(true, Arrays.asList(A, B), A); f.session.resume(); });
        await(f, state -> settled(state) && state.activePrograms() != null);
        RecommendationsSession.AppPrograms result = f.sessionState().activePrograms();
        assertEquals(6, result.items.size()); assertEquals(2, result.loadedChannels); assertEquals(1, result.failedChannels);
        assertEquals(ChannelRepository.Status.PARTIAL, result.status); assertEquals(RecommendationsSession.Failure.NONE, f.sessionState().activeFailure);
        for (ChannelRepository.Program item : result.items) { assertNotNull(result.channelFor(item)); assertEquals(item.channelKey, result.channelFor(item).key); }
        assertEquals(3, reader.loads.size());
    }
    @Test public void providerTimeoutStopsTheBatchPreservesPartialAndDoesNotRetryLaterShelves() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), third = new CountDownLatch(1);
        FakeReader reader = new FakeReader(discovery(100, 1, 2, 3)) {
            @Override public ChannelRepository.Programs load(Context context, ChannelRepository.Channel channel, CancellationSignal signal) {
                record(context, channel); if (channel.id == 2) { entered.countDown(); awaitIgnoringInterrupt(release); }
                if (channel.id == 3) third.countDown(); return programs(channel, 3);
            }
        };
        Fixture f = fixture(reader, 500);
        try {
            onMain(() -> { f.session.configure(true, Collections.singletonList(A), A); f.session.resume(); }); latch(entered, "Second shelf blocks");
            await(f, state -> state.activeFailure == RecommendationsSession.Failure.TIMEOUT && state.queryRunning);
            assertEquals(3, f.sessionState().activePrograms().items.size()); release.countDown(); await(f, RecommendationsSessionTest::settled);
            assertEquals(2, reader.loads.size()); assertFalse("Timeout must not walk more unavailable shelves", third.await(350, TimeUnit.MILLISECONDS));
            for (ChannelRepository.Program item : f.sessionState().activePrograms().items) assertEquals(1L, f.sessionState().activePrograms().channelFor(item).id);
        } finally { release.countDown(); }
    }
    @Test public void overallAppDeadlineIsShorterThanAQueryAndKeepsEarlierSuccess() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        FakeReader reader = new FakeReader(discovery(100, 1, 2, 3)) {
            @Override public ChannelRepository.Programs load(Context context, ChannelRepository.Channel channel, CancellationSignal signal) {
                record(context, channel); if (channel.id == 2) { entered.countDown(); awaitIgnoringInterrupt(release); } return programs(channel, 3);
            }
        };
        Fixture f = fixture(reader, 2_000, 400);
        try {
            long started = SystemClock.elapsedRealtime();
            onMain(() -> { f.session.configure(true, Collections.singletonList(A), A); f.session.resume(); }); latch(entered, "The app's second query starts");
            await(f, state -> state.activeFailure == RecommendationsSession.Failure.TIMEOUT);
            assertTrue("The app budget must not become one full timeout per shelf", SystemClock.elapsedRealtime() - started < 1_500);
            assertEquals(3, f.sessionState().activePrograms().items.size()); assertEquals(2, reader.loads.size());
        } finally { release.countDown(); }
    }
    @Test public void cacheIsThreeAppsAndReinstallUsesFreshChannelsWithoutLosingPackageConsent() {
        FakeReader reader = new FakeReader(discovery(100, 1, 101, 201, 301, 401, 501)) {
            @Override public ChannelRepository.Programs load(Context context, ChannelRepository.Channel channel, CancellationSignal signal) { record(context, channel); return programs(channel, 50); }
        };
        Fixture f = fixture(reader, 2_000); List<String> packages = Arrays.asList(A, B, "example.provider2", "example.provider3", "example.provider4", "example.provider5");
        onMain(() -> { f.session.configure(true, packages, A); f.session.resume(); }); await(f, state -> settled(state) && state.activePrograms() != null);
        for (int i = 1; i < packages.size(); i++) { String active = packages.get(i); onMain(() -> f.session.configure(true, packages, active));
            await(f, state -> settled(state) && active.equals(state.activePackage) && state.activePrograms() != null); }
        RecommendationsSession.Snapshot full = f.sessionState(); assertEquals(6, full.selectedPackages.size()); assertEquals(3, full.programsByPackage.size());
        int items = 0; for (RecommendationsSession.AppPrograms result : full.programsByPackage.values()) items += result.items.size(); assertEquals(150, items);
        try { full.programsByPackage.clear(); fail("Snapshot cache must be immutable"); } catch (UnsupportedOperationException expected) { }
        reader.discovery.set(discovery(200, 1, 101, 201, 301, 401, 501)); onMain(f.session::refreshDiscovery);
        await(f, state -> settled(state) && state.activePrograms() != null && state.activePrograms().channels.get(0).installEpoch == 200);
        assertEquals(packages, f.sessionState().selectedPackages); assertEquals(7, reader.loads.size()); assertEquals(1, f.sessionState().programsByPackage.size());
        for (ChannelRepository.Program item : f.sessionState().activePrograms().items) assertTrue(item.channelKey.contains("|200|"));
    }
    @Test public void blockedLastQueryDoesNotRetainAllEarlierPublisherPayloads() throws Exception {
        int[] ids = new int[32]; for (int i = 0; i < ids.length; i++) ids[i] = i + 1;
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        FakeReader reader = new FakeReader(discovery(100, ids)) {
            @Override public ChannelRepository.Programs load(Context context, ChannelRepository.Channel channel, CancellationSignal signal) {
                record(context, channel); if (channel.id == 32) { entered.countDown(); awaitIgnoringInterrupt(release); } return programs(channel, 50);
            }
        };
        Fixture f = fixture(reader, 4_000);
        try {
            onMain(() -> { f.session.configure(true, Collections.singletonList(A), A); f.session.resume(); }); latch(entered, "All earlier channel payloads have completed");
            assertEquals(32, reader.loads.size());
            onMain(() -> {
                try {
                    Set<ChannelRepository.Program> retained = Collections.newSetFromMap(new IdentityHashMap<>());
                    Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
                    collectPrograms(field(f.session, "batch"), seen, retained); collectPrograms(field(f.session, "programCache"), seen, retained);
                    assertEquals("Batch plus cache must not retain 31 original fifty-item Programs lists", RecommendationsSession.MAX_APP_ITEMS, retained.size());
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            release.countDown(); await(f, state -> settled(state) && state.activePrograms().loadedChannels == 32);
            assertEquals(200, f.sessionState().activePrograms().items.size());
            assertEquals(ChannelRepository.Completeness.CAPPED, f.sessionState().activePrograms().completeness);
        } finally { release.countDown(); }
    }
    @Test public void permissionRevocationClearsEveryWarmAppBeforeAnImmediateSourceSwitch() {
        for (ChannelRepository.Status denied : Arrays.asList(ChannelRepository.Status.PERMISSION_REQUIRED, ChannelRepository.Status.DENIED)) {
            AtomicReference<ChannelRepository.Status> permission = new AtomicReference<>();
            FakeReader reader = new FakeReader(discovery(100, 1, 101)) {
                @Override public ChannelRepository.Discovery discover(Context context, CancellationSignal signal) {
                    if (permission.get() != null) throw new SecurityException("Synthetic provider permission loss");
                    return super.discover(context, signal);
                }
                @Override public ChannelRepository.Programs load(Context context, ChannelRepository.Channel channel, CancellationSignal signal) {
                    record(context, channel); return permission.get() == null ? programs(channel, 3) : deniedPrograms(channel, permission.get());
                }
            };
            Fixture f = fixture(reader, 2_000);
            onMain(() -> { f.session.configure(true, Arrays.asList(A, B), A); f.session.resume(); });
            await(f, state -> settled(state) && state.activePrograms() != null && state.activePrograms().items.size() == 3);
            onMain(() -> f.session.configure(true, Arrays.asList(A, B), B));
            await(f, state -> settled(state) && B.equals(state.activePackage) && state.activePrograms() != null);
            assertEquals("Both apps have a warm publication cache", 2, f.sessionState().programsByPackage.size());
            permission.set(denied); onMain(() -> f.session.configure(true, Arrays.asList(A, B), A));
            await(f, state -> settled(state) && state.discovery == null && state.activeFailure == RecommendationsSession.Failure.ERROR);
            int afterRevocation = f.history.size();
            onMain(() -> {
                assertFalse("The other app's cached publications were globally revoked", f.session.snapshot().programsByPackage.containsKey(B));
                f.session.configure(true, Arrays.asList(A, B), B);
                assertFalse("The immediate switch cannot publish a warm B snapshot", f.session.snapshot().programsByPackage.containsKey(B));
            });
            await(f, state -> settled(state) && B.equals(state.activePackage) && state.discoveryFailure == RecommendationsSession.Failure.ERROR);
            assertEquals("Permission loss does not erase app-selection consent", Arrays.asList(A, B), f.sessionState().selectedPackages);
            List<RecommendationsSession.Snapshot> delivered = new ArrayList<>(f.history);
            for (RecommendationsSession.Snapshot state : delivered.subList(afterRevocation, delivered.size()))
                for (RecommendationsSession.AppPrograms result : state.programsByPackage.values())
                    assertTrue("No queued callback may restore another app's private publications", result.items.isEmpty());
        }
    }
    private static ChannelRepository.Programs deniedPrograms(ChannelRepository.Channel channel, ChannelRepository.Status status) {
        try {
            Constructor<ChannelRepository.Programs> constructor = ChannelRepository.Programs.class.getDeclaredConstructor(ChannelRepository.Status.class,
                    ChannelRepository.Completeness.class, ChannelRepository.Channel.class, List.class, ChannelRepository.Stats.class);
            constructor.setAccessible(true); return constructor.newInstance(status, ChannelRepository.Completeness.UNKNOWN, channel, Collections.emptyList(), null);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private Fixture fixture(FakeReader reader, long timeout) { return fixture(reader, timeout, RecommendationsSession.APP_TIMEOUT_MILLIS); }
    private Fixture fixture(FakeReader reader, long timeout, long appTimeout) { Fixture value = new Fixture(reader, timeout, appTimeout); fixtures.add(value); return value; }
    private final class Fixture {
        final FakeObservers observers = new FakeObservers();
        final ExecutorService executor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2));
        final List<RecommendationsSession.Snapshot> history = new CopyOnWriteArrayList<>(); final RecommendationsSession session;
        Fixture(FakeReader reader, long timeout, long appTimeout) {
            session = new RecommendationsSession(app, state -> { assertSame(Looper.getMainLooper(), Looper.myLooper()); history.add(state); }, reader, observers, executor, timeout, 40, appTimeout);
        }
        RecommendationsSession.Snapshot sessionState() { AtomicReference<RecommendationsSession.Snapshot> value = new AtomicReference<>(); onMain(() -> value.set(session.snapshot())); return value.get(); }
    }
    private final class FakeObservers implements RecommendationsSession.Observers {
        Runnable channelsChanged = () -> { }, programsChanged = () -> { }; boolean active;
        @Override public boolean start(Runnable channels, Runnable programs) { channelsChanged = channels; programsChanged = programs; active = true; return true; }
        @Override public void stop() { active = false; }
    }
    private class FakeReader implements RecommendationsSession.Reader {
        final AtomicReference<ChannelRepository.Discovery> discovery; final AtomicInteger discoveryCalls = new AtomicInteger();
        final List<String> loads = new CopyOnWriteArrayList<>(); final AtomicBoolean workerOnly = new AtomicBoolean(true);
        FakeReader(ChannelRepository.Discovery value) { discovery = new AtomicReference<>(value); }
        @Override public ChannelRepository.Discovery discover(Context context, CancellationSignal signal) {
            workerOnly.set(workerOnly.get() && context == app && Looper.myLooper() != Looper.getMainLooper()); discoveryCalls.incrementAndGet(); return discovery.get();
        }
        @Override public ChannelRepository.Programs load(Context context, ChannelRepository.Channel channel, CancellationSignal signal) { record(context, channel); return programs(channel, 3); }
        void record(Context context, ChannelRepository.Channel channel) { workerOnly.set(workerOnly.get() && context == app && Looper.myLooper() != Looper.getMainLooper()); loads.add(channel.key); }
    }
    private static ChannelRepository.Discovery discovery(long epoch, int... ids) { return discoveryWithHidden(epoch, -1, ids); }
    private static ChannelRepository.Discovery discoveryWithHidden(long epoch, int hidden, int... ids) {
        MatrixCursor cursor = new MatrixCursor(ChannelRepository.CHANNEL_PROJECTION);
        for (int id : ids) cursor.addRow(new Object[]{(long) id, "example.provider" + id / 100, TvContract.Channels.TYPE_PREVIEW, "Fixture" + id, 0, id == hidden ? 0 : 1});
        return ChannelRepository.discoverRows(() -> cursor, name -> new ChannelRepository.Owner(name, epoch, 1), 0, () -> false);
    }
    private static ChannelRepository.Programs programs(ChannelRepository.Channel channel, int count) {
        MatrixCursor cursor = new MatrixCursor(ChannelRepository.PROGRAM_REQUIRED);
        for (int i = 1; i <= count; i++) cursor.addRow(new Object[]{(long) i, channel.packageName, channel.id, "Fixture " + i,
                "fixture://video/" + channel.id + "/" + i, 1, 1});
        return ChannelRepository.loadRows(() -> cursor, channel, (owner, uri) -> PublishedContentIntents.TargetKind.VIEW, () -> false);
    }
    private static String key(ChannelRepository.Discovery discovery, int id) {
        for (ChannelRepository.Channel channel : discovery.selectableChannels) if (channel.id == id) return channel.key;
        throw new AssertionError("Missing fixture channel " + id);
    }
    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void collectPrograms(Object value, Set<Object> seen, Set<ChannelRepository.Program> programs) throws ReflectiveOperationException {
        if (value == null || !seen.add(value)) return;
        if (value instanceof ChannelRepository.Program) { programs.add((ChannelRepository.Program) value); return; }
        if (value instanceof Iterable<?>) { for (Object child : (Iterable<?>) value) collectPrograms(child, seen, programs); return; }
        if (value instanceof Map<?, ?>) { for (Object child : ((Map<?, ?>) value).values()) collectPrograms(child, seen, programs); return; }
        if (!value.getClass().getName().startsWith("io.github.ekkzy.softlumi.data.")) return;
        for (Field field : value.getClass().getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers())) { field.setAccessible(true); collectPrograms(field.get(value), seen, programs); }
    }
    private static boolean settled(RecommendationsSession.Snapshot state) { return !state.discoveryLoading && !state.activeLoading && !state.pendingRefresh && !state.queryRunning; }
    private static void onMain(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private static void await(Fixture fixture, Predicate<RecommendationsSession.Snapshot> condition) {
        long deadline = SystemClock.uptimeMillis() + 4_000;
        do { if (condition.test(fixture.sessionState())) return; SystemClock.sleep(10); } while (SystemClock.uptimeMillis() < deadline);
        RecommendationsSession.Snapshot state = fixture.sessionState();
        fail("Session did not settle: active=" + state.activePackage + ", discovery=" + state.discoveryLoading + ", loading=" + state.activeLoading + ", busy=" + state.queryRunning);
    }
    private static void latch(CountDownLatch latch, String message) throws InterruptedException { assertTrue(message, latch.await(4, TimeUnit.SECONDS)); }
    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false; while (true) try { latch.await(); break; } catch (InterruptedException ignored) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
