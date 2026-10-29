package io.github.ekkzy.evebox.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.View;
import java.util.ArrayList;
import java.util.IdentityHashMap;

/** Main-thread owner of interruptible View presentation. Input and focus stay with native Views. */
public final class UiMotion implements AutoCloseable, Choreographer.FrameCallback {
    public enum Style {
        SHELF(1.20f, .185f, .90f, 0, 14),
        CARD(1.05f, .20f, .92f, 6, 14),
        CONTROL(1.05f, .20f, .96f, 0, 6),
        DRAWER(1.07f, .22f, .94f, 0, 8),
        GRID(1.08f, .25f, .96f, 0, 16);
        final float scale, response, damping, lift, depth;
        Style(float scale, float response, float damping, float lift, float depth) {
            this.scale = scale; this.response = response; this.damping = damping;
            this.lift = lift; this.depth = depth;
        }
    }

    public enum Transition {
        SCREEN_ENTER_FORWARD, SCREEN_EXIT_FORWARD, SCREEN_ENTER_BACK, SCREEN_EXIT_BACK,
        DRAWER_ENTER, DRAWER_EXIT
    }
    private enum Lifecycle { PAUSED, VISIBLE, CLOSED }

    private final Context app;
    private final Choreographer frames;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float localUnit;
    // Attached bindings have one explicit owner and remove themselves on detach.
    private final IdentityHashMap<View, Binding> bindings = new IdentityHashMap<>();
    private final ArrayList<Binding> moving = new ArrayList<>();
    private Lifecycle lifecycle = Lifecycle.PAUSED;
    private boolean enabled = true, reduced, observing, posted;
    private float animatorScale = 1;
    private long previousFrame;
    private final ContentObserver policy = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) { applyPolicy(); }
    };

    public UiMotion(Context context) { this(context, 1280); }

    public UiMotion(Context context, float designWidth) {
        mainThread();
        if (!Float.isFinite(designWidth) || designWidth <= 0) throw new IllegalArgumentException("Positive design width required");
        app = context.getApplicationContext();
        localUnit = designWidth / 1920f;
        frames = Choreographer.getInstance();
        readPolicy();
    }

    public void configure(boolean enabled, boolean reducedMotion) {
        mainThread();
        if (lifecycle == Lifecycle.CLOSED) return;
        this.enabled = enabled;
        reduced = reducedMotion;
        applyPolicy();
    }

    public void resume() {
        mainThread();
        if (lifecycle == Lifecycle.CLOSED) return;
        lifecycle = Lifecycle.VISIBLE;
        if (!observing) {
            try {
                app.getContentResolver().registerContentObserver(Settings.Global.getUriFor(
                        Settings.Global.ANIMATOR_DURATION_SCALE), false, policy);
                observing = true;
            } catch (RuntimeException unavailable) { /* Native animator policy is checked on every active frame. */ }
        }
        applyPolicy();
    }

    public void pause() {
        mainThread();
        if (lifecycle == Lifecycle.CLOSED) return;
        lifecycle = Lifecycle.PAUSED;
        unobserve();
        finishAll(true);
    }

    public void focus(View view, boolean focused, Style style) { focus(view, focused, style, style.scale); }

    public void focus(View view, boolean focused, Style style, float maximumScale) {
        mainThread();
        if (lifecycle == Lifecycle.CLOSED) return;
        validateFocus(style, maximumScale);
        Binding binding = bind(view);
        binding.focusOwned = true;
        binding.style = style;
        binding.focusScale = maximumScale;
        binding.channel[0].parameters(style.response, style.damping);
        binding.channel[0].target = focused ? 1 : 0;
        if (!focused) binding.channel[1].target = 0;
        publish(binding);
    }

    public void snapFocus(View view, boolean focused, Style style, float maximumScale) {
        mainThread();
        if (lifecycle == Lifecycle.CLOSED) return;
        validateFocus(style, maximumScale);
        Binding binding = bind(view);
        binding.focusOwned = true;
        binding.style = style;
        binding.focusScale = maximumScale;
        binding.channel[0].parameters(style.response, style.damping);
        binding.channel[0].snap(focused ? 1 : 0);
        publish(binding);
    }

    public void pressed(View view, boolean pressed) {
        mainThread();
        if (lifecycle == Lifecycle.CLOSED) return;
        Binding binding = bind(view);
        binding.focusOwned = true;
        binding.channel[1].target = pressed ? 1 : 0;
        publish(binding);
    }

    public void crossfade(View view, boolean entering) { crossfade(view, entering, null); }

    /** Alpha has a separate owner from the scene board's spatial channels. */
    public void crossfade(View view, boolean entering, Runnable completion) {
        mainThread();
        if (lifecycle == Lifecycle.CLOSED) return;
        Binding binding = bind(view);
        binding.alphaOwned = true;
        if (!binding.alphaStarted && entering) binding.channel[4].snap(0);
        binding.alphaStarted = true;
        binding.channel[4].target = entering ? 1 : 0;
        binding.completion = completion;
        publish(binding);
    }

    public void transition(View view, Transition transition) { transition(view, transition, null); }
    public void transition(View view, Transition transition, Runnable completion) { scene(view, transition, completion, true); }
    public void transitionSpatial(View view, Transition transition) { scene(view, transition, null, false); }

    private void scene(View view, Transition transition, Runnable completion, boolean fade) {
        mainThread();
        if (lifecycle == Lifecycle.CLOSED) return;
        if (transition == null) throw new IllegalArgumentException("Transition required");
        Binding binding = bind(view);
        boolean drawer = transition == Transition.DRAWER_ENTER || transition == Transition.DRAWER_EXIT;
        boolean entering = transition == Transition.DRAWER_ENTER || transition == Transition.SCREEN_ENTER_FORWARD
                || transition == Transition.SCREEN_ENTER_BACK;
        boolean left = transition == Transition.SCREEN_ENTER_BACK || transition == Transition.SCREEN_EXIT_FORWARD;
        View viewport = view.getParent() instanceof View ? (View) view.getParent() : view.getRootView();
        int measured = drawer ? viewport.getHeight() : viewport.getWidth();
        if (measured <= 0) measured = drawer ? app.getResources().getDisplayMetrics().heightPixels
                : app.getResources().getDisplayMetrics().widthPixels;
        float travel = measured * (drawer ? .50f : .055f);
        float hiddenX = drawer ? 0 : (left ? -travel : travel);
        float hiddenY = drawer ? travel : 0;
        // Page text keeps its raster size; only the page position and opacity
        // change. Focused tiles retain their independent scale animation.
        float hiddenScale = 1;
        binding.sceneOwned = true;
        binding.alphaOwned = fade;
        for (int i = 2; i < binding.channel.length; i++) binding.channel[i].parameters(drawer ? .34f : .30f, .96f);
        binding.channel[2].tolerance(travel);
        binding.channel[3].tolerance(travel);
        if (!binding.sceneStarted && entering) {
            binding.channel[2].snap(hiddenX);
            binding.channel[3].snap(hiddenY);
            binding.channel[5].snap(hiddenScale);
        }
        if (fade && !binding.alphaStarted && entering) binding.channel[4].snap(0);
        if (!fade) { binding.channel[4].snap(1); view.setAlpha(binding.baseAlpha); }
        binding.sceneStarted = true;
        binding.alphaStarted |= fade;
        binding.channel[2].target = entering ? 0 : hiddenX;
        binding.channel[3].target = entering ? 0 : hiddenY;
        binding.channel[4].target = !fade || entering ? 1 : 0;
        binding.channel[5].target = entering ? 1 : hiddenScale;
        // Replacing this callback also invalidates the obsolete route cleanup.
        binding.completion = completion;
        publish(binding);
    }

    public boolean isRunning() { mainThread(); return posted; }
    public int trackedViewCount() { mainThread(); return bindings.size(); }
    public int runningChannelCount() {
        mainThread();
        int count = 0;
        for (Binding binding : moving) count += binding.unsettled();
        return count;
    }

    @Override public void doFrame(long frameTimeNanos) {
        posted = false;
        if (!mayMove()) { finishAll(true); return; }
        float seconds = previousFrame == 0 ? 1f / 60f
                : Math.min(.05f, Math.max(0, (frameTimeNanos - previousFrame) / 1_000_000_000f));
        previousFrame = frameTimeNanos;
        ArrayList<Runnable> finished = null;
        for (int i = moving.size() - 1; i >= 0; i--) {
            Binding binding = moving.get(i);
            if (!binding.view.isAttachedToWindow()) { moving.remove(i); continue; }
            for (UiSpring channel : binding.channel) channel.step(seconds);
            binding.draw(true);
            if (binding.unsettled() == 0) {
                moving.remove(i);
                Runnable completion = binding.takeCompletion();
                if (completion != null) {
                    if (finished == null) finished = new ArrayList<>();
                    finished.add(completion);
                }
            }
        }
        if (moving.isEmpty()) previousFrame = 0; else requestFrame();
        if (finished != null) for (Runnable callback : finished) callback.run();
    }

    @Override public void close() {
        mainThread();
        if (lifecycle == Lifecycle.CLOSED) return;
        lifecycle = Lifecycle.CLOSED;
        unobserve();
        cancelFrame();
        moving.clear();
        for (Binding binding : bindings.values()) {
            binding.completion = null;
            binding.view.removeOnAttachStateChangeListener(binding);
            binding.restore();
        }
        bindings.clear();
        main.removeCallbacksAndMessages(null);
    }

    private Binding bind(View view) {
        if (view == null) throw new IllegalArgumentException("View required");
        Binding result = bindings.get(view);
        if (result == null) {
            result = new Binding(view);
            bindings.put(view, result);
            view.addOnAttachStateChangeListener(result);
        }
        return result;
    }

    private void publish(Binding binding) {
        boolean animate = mayMove();
        if (!animate) binding.snap();
        binding.draw(animate);
        moving.remove(binding);
        if (binding.unsettled() == 0) {
            if (moving.isEmpty()) cancelFrame();
            Runnable callback = binding.takeCompletion();
            if (callback != null) callback.run();
        } else if (binding.view.isAttachedToWindow()) {
            moving.add(binding);
            requestFrame();
        }
    }

    private void applyPolicy() {
        readPolicy();
        if (!mayMove()) finishAll(true);
        else for (Binding binding : bindings.values()) binding.draw(true);
    }

    private void finishAll(boolean deliver) {
        cancelFrame();
        moving.clear();
        ArrayList<Runnable> callbacks = new ArrayList<>();
        for (Binding binding : bindings.values()) {
            binding.channel[1].target = 0;
            binding.snap();
            binding.draw(false);
            Runnable completion = binding.takeCompletion();
            if (deliver && completion != null) callbacks.add(completion);
        }
        for (Runnable callback : callbacks) callback.run();
    }

    private boolean mayMove() {
        return lifecycle == Lifecycle.VISIBLE && enabled && !reduced && animatorScale > 0
                && ValueAnimator.areAnimatorsEnabled();
    }

    private void readPolicy() {
        try {
            float scale = Settings.Global.getFloat(app.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1);
            animatorScale = Float.isFinite(scale) && scale >= 0 ? scale : 1;
        } catch (RuntimeException unavailable) { animatorScale = ValueAnimator.areAnimatorsEnabled() ? 1 : 0; }
    }

    private void unobserve() {
        if (!observing) return;
        observing = false;
        try { app.getContentResolver().unregisterContentObserver(policy); }
        catch (RuntimeException unavailable) { }
    }
    private void requestFrame() {
        if (posted || !mayMove()) return;
        posted = true;
        frames.postFrameCallback(this);
    }
    private void cancelFrame() {
        if (posted) frames.removeFrameCallback(this);
        posted = false;
        previousFrame = 0;
    }
    private static void validateFocus(Style style, float maximumScale) {
        if (style == null || !Float.isFinite(maximumScale) || maximumScale < 1 || maximumScale > 1.20f)
            throw new IllegalArgumentException("Focus style and scale in1..1.20 required");
    }
    private static float unit(float value) { return Math.max(0, Math.min(1, value)); }
    private static void mainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Motion belongs to the UI thread");
    }

    private final class Binding implements View.OnAttachStateChangeListener {
        final View view;
        // focus, press, sceneX, sceneY, opacity, sceneScale
        final UiSpring[] channel = {new UiSpring(.20f, .96f, 0), new UiSpring(.135f, 1, 0),
                new UiSpring(.30f, .96f, 0), new UiSpring(.30f, .96f, 0),
                new UiSpring(.27f, 1, 1), new UiSpring(.30f, .96f, 1)};
        final float baseScaleX, baseScaleY, baseX, baseY, baseZ, baseAlpha;
        Style style = Style.CONTROL;
        float focusScale = 1.05f;
        boolean focusOwned, sceneOwned, alphaOwned, sceneStarted, alphaStarted;
        Runnable completion;
        Binding(View view) {
            this.view = view;
            baseScaleX = view.getScaleX(); baseScaleY = view.getScaleY();
            baseX = view.getTranslationX(); baseY = view.getTranslationY();
            baseZ = view.getTranslationZ(); baseAlpha = view.getAlpha();
        }
        int unsettled() {
            int count = 0;
            for (UiSpring spring : channel) if (!spring.settled()) count++;
            return count;
        }
        void snap() { for (UiSpring spring : channel) spring.snap(spring.target); }
        Runnable takeCompletion() { Runnable next = completion; completion = null; return next; }
        void draw(boolean animate) {
            float focus = animate ? unit(channel[0].value) : 0;
            float press = animate ? unit(channel[1].value) : 0;
            foreground(animate ? channel[0].value : channel[0].target);
            if (focusOwned || sceneOwned) {
                float scale = (1 + focus * (focusScale - 1)) * (1 - press * .03f) * channel[5].value;
                view.setScaleX(baseScaleX * scale);
                view.setScaleY(baseScaleY * scale);
                view.setTranslationX(baseX + channel[2].value);
                view.setTranslationY(baseY + channel[3].value - focus * style.lift * localUnit);
                view.setTranslationZ(baseZ + focus * style.depth * localUnit * (1 - press * .35f));
            }
            if (alphaOwned) view.setAlpha(baseAlpha * unit(channel[4].value));
        }
        void foreground(float progress) {
            if (view.getForeground() instanceof ArtworkSheenDrawable) {
                ArtworkSheenDrawable sheen = (ArtworkSheenDrawable) view.getForeground();
                sheen.setFocused(channel[0].target > 0);
                sheen.setFocusProgress(progress);
            }
        }
        void restore() {
            foreground(channel[0].target);
            if (focusOwned || sceneOwned) {
                view.setScaleX(baseScaleX); view.setScaleY(baseScaleY);
                view.setTranslationX(baseX); view.setTranslationY(baseY); view.setTranslationZ(baseZ);
            }
            if (alphaOwned) view.setAlpha(baseAlpha);
        }
        @Override public void onViewAttachedToWindow(View view) {
            if (lifecycle != Lifecycle.CLOSED) publish(this);
        }
        @Override public void onViewDetachedFromWindow(View view) {
            completion = null;
            moving.remove(this);
            bindings.remove(view);
            view.removeOnAttachStateChangeListener(this);
            restore();
            if (moving.isEmpty()) cancelFrame();
        }
    }
}
