package io.github.ekkzy.softlumi.ui;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Own short windowed chirps. A cue is played now or dropped; lifecycle never queues input. */
public final class UiSounds implements AutoCloseable {
    public enum Cue {
        MOVE(1536, 1150, 1020, .18f), SELECT(3072, 760, 1160, .21f), BACK(2304, 900, 620, .16f);
        final int samples;
        final double fromHz, toHz;
        final float gain;
        Cue(int samples, double fromHz, double toHz, float gain) {
            this.samples = samples; this.fromHz = fromHz; this.toHz = toHz; this.gain = gain;
        }
    }
    private enum Lifecycle { HIDDEN, VISIBLE, CLOSED }
    private static final int SAMPLE_RATE = 48_000;
    private static final long MOVE_INTERVAL_MILLIS = 55;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> {
                Thread thread = new Thread(task, "softlumi-cue-preparation");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private Lifecycle lifecycle = Lifecycle.HIDDEN;
    private boolean enabled, exhausted;
    private volatile long epoch;
    private Future<?> preparation;
    private Session session;
    private int recoveries, preparations, loadFailures, rejected;
    private long requested, started;
    private long lastMoveStartedAt = -1;
    private String outcome = "not-requested";

    public UiSounds(Context context) {
        mainThread();
        app = context.getApplicationContext();
        worker.allowCoreThreadTimeOut(true);
    }

    public void configure(boolean value) {
        mainThread();
        if (lifecycle == Lifecycle.CLOSED) return;
        if (value && !enabled) { recoveries = 0; exhausted = false; }
        enabled = value;
        if (value) prepare(); else discard();
    }
    public void resume() {
        mainThread();
        if (lifecycle == Lifecycle.CLOSED) return;
        lifecycle = Lifecycle.VISIBLE;
        recoveries = 0;
        exhausted = false;
        prepare();
    }
    public void pause() {
        mainThread();
        if (lifecycle == Lifecycle.CLOSED) return;
        lifecycle = Lifecycle.HIDDEN;
        discard();
    }
    @Override public void close() {
        mainThread();
        if (lifecycle == Lifecycle.CLOSED) return;
        lifecycle = Lifecycle.CLOSED;
        discard();
        worker.shutdownNow();
    }

    public void move() { play(Cue.MOVE); }
    public void select() { play(Cue.SELECT); }
    public void back() { play(Cue.BACK); }

    public void play(Cue cue) {
        mainThread();
        requested++;
        if (cue == null || !wanted()) {
            outcome = cue == null ? "invalid-cue" : lifecycle == Lifecycle.CLOSED ? "closed"
                    : lifecycle == Lifecycle.HIDDEN ? "paused" : "disabled";
            return;
        }
        if (!isReady()) { outcome = "not-ready"; prepare(); return; }
        long now = SystemClock.uptimeMillis();
        if (cue == Cue.MOVE && lastMoveStartedAt >= 0 && now - lastMoveStartedAt < MOVE_INTERVAL_MILLIS) {
            outcome = "move-throttled";
            return;
        }
        try {
            int stream = session.pool.play(session.sampleIds[cue.ordinal()], cue.gain, cue.gain, 1, 0, 1f);
            if (stream > 0) {
                started++;
                if (cue == Cue.MOVE) lastMoveStartedAt = now;
                outcome = "started-" + cue.name().toLowerCase(java.util.Locale.ROOT);
            } else {
                rejected++;
                failed("play-rejected");
            }
        } catch (RuntimeException unavailable) {
            rejected++;
            failed("play-exception");
        }
    }

    public boolean isReady() {
        mainThread();
        if (!wanted() || session == null) return false;
        for (int sample : session.sampleIds) if (sample <= 0 || !session.loaded.contains(sample)) return false;
        return true;
    }
    public long getPlayRequestCount() { mainThread(); return requested; }
    public long getStartedPlayCount() { mainThread(); return started; }
    /** A native stream ID is load/play evidence, not evidence of an audible HDMI path. */
    public String diagnostics() {
        mainThread();
        return "enabled=" + enabled + ",paused=" + (lifecycle != Lifecycle.VISIBLE) + ",ready=" + isReady()
                + ",preparing=" + (preparation != null) + ",pools=" + preparations + ",loadFailures=" + loadFailures
                + ",requested=" + requested + ",started=" + started + ",rejected=" + rejected + ",last=" + outcome;
    }

    private boolean wanted() { return enabled && lifecycle == Lifecycle.VISIBLE; }
    private boolean current(long token) { return token == epoch && wanted(); }
    private void prepare() {
        if (!wanted() || exhausted || preparation != null || session != null) return;
        long token = ++epoch;
        try {
            preparation = worker.submit(() -> prepareFiles(token));
        } catch (RejectedExecutionException stopped) { failed("preparation-rejected"); }
    }
    private void prepareFiles(long token) {
        try {
            String[] files = new String[Cue.values().length];
            for (Cue cue : Cue.values()) {
                if (token != epoch || Thread.currentThread().isInterrupted()) return;
                byte[] wav = synthesize(cue);
                files[cue.ordinal()] = publishFile(cue, wav);
            }
            if (token == epoch && !Thread.currentThread().isInterrupted()) main.post(() -> load(token, files));
        } catch (CancellationException cancelled) {
            // An invalidated preparation has no UI publication or queued cue.
        } catch (IOException | RuntimeException unavailable) {
            main.post(() -> { if (current(token)) failed("file-preparation-failed"); });
        }
    }
    private void load(long token, String[] files) {
        if (!current(token)) return;
        preparation = null;
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build();
            Session next = new Session(new SoundPool.Builder().setAudioAttributes(attributes).setMaxStreams(2).build());
            session = next;
            preparations++;
            next.pool.setOnLoadCompleteListener((pool, sample, status) -> {
                if (!current(token) || session != next) return;
                if (status != 0) { failed("sample-load-failed"); return; }
                next.loaded.add(sample);
                if (isReady()) {
                    outcome = "ready";
                    if (next.timeout != null) main.removeCallbacks(next.timeout);
                }
            });
            for (Cue cue : Cue.values()) {
                next.sampleIds[cue.ordinal()] = next.pool.load(files[cue.ordinal()], 1);
                if (next.sampleIds[cue.ordinal()] == 0) { failed("sample-load-rejected"); return; }
            }
            next.timeout = () -> { if (current(token) && session == next && !isReady()) failed("sample-load-timeout"); };
            main.postDelayed(next.timeout, 2200);
        } catch (RuntimeException unavailable) { failed("pool-unavailable"); }
    }
    private void failed(String reason) {
        loadFailures++;
        outcome = reason;
        boolean retry = wanted() && recoveries++ == 0;
        discard();
        exhausted = !retry;
        if (retry) main.postDelayed(this::prepare, 250);
    }
    private void discard() {
        epoch++;
        lastMoveStartedAt = -1;
        if (preparation != null) { preparation.cancel(true); preparation = null; }
        worker.getQueue().clear();
        worker.purge();
        main.removeCallbacksAndMessages(null);
        if (session != null) { session.release(); session = null; }
    }

    private String publishFile(Cue cue, byte[] bytes) throws IOException {
        File directory = app.getCacheDir();
        File destination = new File(directory, "softlumi-cue-v2-" + cue.name() + ".wav");
        if (destination.isFile() && destination.length() == bytes.length
                && Arrays.equals(Files.readAllBytes(destination.toPath()), bytes)) return destination.getAbsolutePath();
        File temporary = File.createTempFile("softlumi-cue-v2-", ".wav", directory);
        try {
            Files.write(temporary.toPath(), bytes);
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return destination.getAbsolutePath();
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }

    /** Finite mono PCM: a single linear chirp inside a raised-cosine window, with silent endpoints. */
    static byte[] synthesize(Cue cue) {
        if (cue == null) throw new IllegalArgumentException("Cue required");
        ByteArrayOutputStream wav = new ByteArrayOutputStream(44 + cue.samples * 2);
        ascii(wav, "RIFF"); little(wav, 36 + cue.samples * 2, 4); ascii(wav, "WAVEfmt ");
        little(wav, 16, 4); little(wav, 1, 2); little(wav, 1, 2);
        little(wav, SAMPLE_RATE, 4); little(wav, SAMPLE_RATE * 2, 4);
        little(wav, 2, 2); little(wav, 16, 2); ascii(wav, "data"); little(wav, cue.samples * 2, 4);
        double duration = (cue.samples - 1.0) / SAMPLE_RATE;
        for (int i = 0; i < cue.samples; i++) {
            if ((i & 255) == 0 && Thread.currentThread().isInterrupted()) throw new CancellationException();
            double fraction = i / (cue.samples - 1.0), time = i / (double) SAMPLE_RATE;
            double window = Math.sin(Math.PI * fraction);
            double phase = 2 * Math.PI * (cue.fromHz * time + (cue.toHz - cue.fromHz) * time * time / (2 * duration));
            double pulse = Math.sin(phase) * window * window * (1 - fraction * .25);
            int sample = (int) Math.round(pulse * .44 * Short.MAX_VALUE);
            little(wav, sample, 2);
        }
        return wav.toByteArray();
    }
    private static void ascii(ByteArrayOutputStream output, String value) {
        for (int i = 0; i < value.length(); i++) output.write(value.charAt(i));
    }
    private static void little(ByteArrayOutputStream output, int value, int bytes) {
        for (int i = 0; i < bytes; i++) output.write(value >>> (i * 8) & 255);
    }
    private static void mainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Sound cues belong to the UI thread");
    }
    private static final class Session {
        final SoundPool pool;
        final int[] sampleIds = new int[Cue.values().length];
        final Set<Integer> loaded = new HashSet<>();
        Runnable timeout;
        Session(SoundPool pool) { this.pool = pool; }
        void release() {
            try { pool.setOnLoadCompleteListener(null); } catch (RuntimeException unavailable) { }
            try { pool.release(); } catch (RuntimeException unavailable) { }
            loaded.clear();
        }
    }
}
