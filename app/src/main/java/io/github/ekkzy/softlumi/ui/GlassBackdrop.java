package io.github.ekkzy.softlumi.ui;

import android.os.Looper;
import android.view.View;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Activity-scoped lifecycle for live glass. No bitmap cache, worker or frame timer. */
public final class GlassBackdrop implements AutoCloseable {
    private final Set<GlassDrawable> surfaces = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<GlassScene> scenes = Collections.newSetFromMap(new WeakHashMap<>());
    private boolean active = true, closed;

    /** Host must be outside target, in the same tree. Radius is host-local pixels. */
    public GlassDrawable drawable(View host, GlassScene target, float radius, boolean light, boolean strongFrost) {
        checkMain();
        if (closed) throw new IllegalStateException("GlassBackdrop is closed");
        if (host == null || target == null) throw new IllegalArgumentException("Glass requires a host and a disjoint scene");
        GlassDrawable surface = new GlassDrawable(this, host, target, radius, light, strongFrost);
        if (!closed) { surfaces.add(surface); scenes.add(target); }
        return surface;
    }
    public void pause() { checkMain(); active = false; forEach(false); clearScenes(); }
    public void resume() { checkMain(); if (!closed) { active = true; forEach(true); } }
    /** Drop panel and shared-source GPU lists; an active normal draw recreates them. */
    public void clear() { checkMain(); for (GlassDrawable surface : snapshot()) surface.release(); clearScenes(); }
    @Override public void close() {
        checkMain(); if (closed) return;
        closed = true; active = false;
        for (GlassDrawable surface : snapshot()) surface.dispose();
        clearScenes(); surfaces.clear(); scenes.clear();
    }
    boolean isActive() { return active && !closed; }
    private ArrayList<GlassDrawable> snapshot() { return new ArrayList<>(surfaces); }
    private void forEach(boolean enabled) { for (GlassDrawable surface : snapshot()) surface.setEnabled(enabled); }
    private void clearScenes() { for (GlassScene scene : new ArrayList<>(scenes)) scene.clearSharedBlurCache(); }
    // CPU ownership only: RenderEffect uses platform-managed GPU layers.
    public long getOwnedBitmapBytes() { checkMain(); return 0; }
    public int getPendingWorkCount() { checkMain(); return 0; }
    public int getLiveSurfaceCount() { checkMain(); return surfaces.size(); }
    public int getGpuNodeCount() {
        checkMain(); int count = 0;
        for (GlassDrawable surface : snapshot()) if (surface.hasGpuDisplayList()) count++;
        return count;
    }
    public int getSharedGpuNodeCount() {
        checkMain(); int count = 0;
        for (GlassScene scene : new ArrayList<>(scenes)) count += scene.getSharedBlurNodeCount();
        return count;
    }
    static void checkMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Glass belongs to the main thread");
    }
}
