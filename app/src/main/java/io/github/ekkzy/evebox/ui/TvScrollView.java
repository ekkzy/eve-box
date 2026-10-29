package io.github.ekkzy.evebox.ui;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Matrix;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ScrollView;

/** Native scrolling that reserves the final, enlarged TV focus outline. */
public final class TvScrollView extends ScrollView implements Choreographer.FrameCallback {
    private final Rect focusEnvelope = new Rect();
    private final Rect focusedBounds = new Rect();
    private final RectF finalFocus = new RectF();
    private final Matrix focusMatrix = new Matrix(), matrixStep = new Matrix();
    private final UiSpring offset = new UiSpring(.255f, .90f, 0);
    private boolean planning, frameScheduled, stoppingNative, revealFocusedTarget, layingOut;
    private long lastFrameNanos;
    private int pageHeight;

    public TvScrollView(Context context) {
        this(context, null);
    }

    public TvScrollView(Context context, AttributeSet attrs) {
        this(context, attrs, android.R.attr.scrollViewStyle);
    }

    public TvScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** Optional fixed-page Home mode; zero keeps ordinary native focus scrolling. */
    public void setPageHeight(int height) {
        if (height < 0) throw new IllegalArgumentException("Page height must be nonnegative");
        if (pageHeight == height) return;
        int destination = frameScheduled ? Math.round(offset.target) : getScrollY();
        stopSpring(); stopNative(); pageHeight = height; revealFocusedTarget = false;
        if (height > 0) {
            destination = pageOffset(destination);
            super.scrollTo(getScrollX(), destination); offset.snap(destination);
        }
    }

    /** OverScroller does not follow the system animator scale on its own. */
    public void setMotionAllowed(boolean allowed) {
        if (isSmoothScrollingEnabled() == allowed) return;
        setSmoothScrollingEnabled(allowed);
        if (!allowed) {
            int destination = pageHeight > 0 ? pageOffset(frameScheduled ? Math.round(offset.target) : getScrollY()) : getScrollY();
            stopSpring();
            // Ordinary scrolling stops at its current offset. Fixed pages settle
            // their requested anchor immediately when motion is disabled.
            stopNative();
            if (pageHeight > 0) { super.scrollTo(getScrollX(), destination); offset.snap(destination); }
        }
    }

    @Override public boolean arrowScroll(int direction) { return plan(() -> super.arrowScroll(direction)); }
    @Override public boolean pageScroll(int direction) { return plan(() -> super.pageScroll(direction)); }
    @Override public boolean fullScroll(int direction) { return plan(() -> super.fullScroll(direction)); }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (pageHeight > 0 && isEnabled()) {
            boolean down = action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    || action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId();
            boolean up = action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    || action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId();
            if (down || up) {
                int from = pageOffset(frameScheduled ? Math.round(offset.target) : getScrollY());
                int destination = pageOffset(from + (down ? pageHeight : -pageHeight));
                if (destination == from) return false;
                smoothScrollToPosition(destination); return true;
            }
        }
        return super.performAccessibilityAction(action, arguments);
    }

    @Override public void requestChildFocus(View child, View focused) {
        plan(() -> { super.requestChildFocus(child, focused); return true; });
    }

    @Override public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
        if (immediate) { stopSpring(); stopNative(); return super.requestChildRectangleOnScreen(child, rectangle, true); }
        return plan(() -> super.requestChildRectangleOnScreen(child, rectangle, true), pageHeight == 0);
    }

    private interface NativeAction { boolean run(); }

    public boolean isScrollAnimating() { return frameScheduled; }

    /** Back/Home uses the same interruptible channel as D-pad focus, never native smoothScrollTo. */
    public void smoothScrollToPosition(int y) {
        if (!isSmoothScrollingEnabled()) { scrollTo(getScrollX(), y); return; }
        plan(() -> { super.scrollTo(getScrollX(), y); return true; }, false);
    }

    /** Native focus search chooses the destination; one spring owns its visible offset. */
    private boolean plan(NativeAction action) {
        return plan(action, true);
    }

    private boolean plan(NativeAction action, boolean revealFocused) {
        if (planning || !isSmoothScrollingEnabled() || !isLaidOut()) return action.run();
        int from = getScrollY(), x = getScrollX();
        float velocity = frameScheduled ? offset.velocity : 0;
        long previousFrame = frameScheduled ? lastFrameNanos : 0;
        stopSpring(); stopNative();
        boolean result;
        int destination;
        planning = true;
        super.setSmoothScrollingEnabled(false);
        try {
            result = action.run();
            // Native focus propagation can finish with a raw/partial descendant
            // rectangle. The final destination must contain the whole focused
            // surface, including its future spring scale, after those requests.
            if (revealFocused) {
                View focused = findFocus();
                if (focused != null && focused != this) {
                    focused.getDrawingRect(focusedBounds);
                    offsetDescendantRectToMyCoords(focused, focusedBounds);
                    int delta = computeScrollDeltaToGetChildRectOnScreen(focusedBounds);
                    if (delta != 0) super.scrollTo(x, getScrollY() + delta);
                }
            }
            destination = pageHeight > 0 ? pageOffset(getScrollY()) : getScrollY();
            super.scrollTo(x, from);
        }
        finally { super.setSmoothScrollingEnabled(true); planning = false; }
        // Both writes occur before drawing. Native callbacks/focus remain synchronous,
        // while repeated keys no longer enter smoothScrollBy's 250 ms jump branch.
        offset.snap(from); offset.target = destination; offset.velocity = velocity;
        revealFocusedTarget = revealFocused;
        lastFrameNanos = previousFrame;
        offset.tolerance(Math.max(1, Math.abs(destination - from)));
        if (!offset.settled()) schedule();
        return result;
    }

    @Override protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        // Destination planning is not a rendered scroll. In particular it must
        // not clear visible artwork or trigger provider loads at the future offset.
        if (!planning) super.onScrollChanged(l, t, oldl, oldt);
    }

    private void stopNative() {
        if (stoppingNative) return;
        stoppingNative = true;
        try { super.fling(0); super.computeScroll(); }
        finally { stoppingNative = false; }
    }
    private void schedule() {
        if (frameScheduled || !isAttachedToWindow()) return;
        frameScheduled = true; Choreographer.getInstance().postFrameCallback(this);
    }
    private void stopSpring() {
        if (frameScheduled) Choreographer.getInstance().removeFrameCallback(this);
        frameScheduled = false; lastFrameNanos = 0;
    }
    @Override public void doFrame(long time) {
        frameScheduled = false;
        if (!isAttachedToWindow() || !isSmoothScrollingEnabled()) { lastFrameNanos = 0; return; }
        float dt = lastFrameNanos == 0 ? 1 / 60f : Math.max(0, (time - lastFrameNanos) / 1e9f);
        lastFrameNanos = time;
        boolean moving = offset.step(dt);
        super.scrollTo(getScrollX(), Math.round(offset.value));
        if (!moving && pageHeight > 0) {
            int destination = pageOffset(Math.round(offset.target));
            offset.snap(destination); super.scrollTo(getScrollX(), destination);
        }
        if (!moving && revealFocusedTarget) moving = revealFinalFocus();
        if (moving) schedule(); else lastFrameNanos = 0;
    }
    @Override public void scrollTo(int x, int y) {
        if (getChildCount() > 0) {
            View content = getChildAt(0);
            x = Math.max(0, Math.min(x, Math.max(0, content.getWidth() - getWidth() + getPaddingLeft() + getPaddingRight())));
            y = Math.max(0, Math.min(y, Math.max(0, content.getHeight() - getHeight() + getPaddingTop() + getPaddingBottom())));
        }
        // ScrollView.onLayout calls scrollTo(currentX,currentY) even when only a
        // descendant drawable requested layout. Preserve motion only if that
        // offset is still legal after any content-size change.
        int page = pageHeight > 0 ? pageOffset(y) : y;
        if (pageHeight > 0 && layingOut && frameScheduled && !planning && !stoppingNative
                && getScrollY() >= 0 && getScrollY() <= maximumScroll()
                && pageOffset(Math.round(offset.target)) == Math.round(offset.target)
                && (y == getScrollY() || page == Math.round(offset.target))) return;
        if (x == getScrollX() && y == getScrollY()
                && (frameScheduled || planning || stoppingNative || page == y)) return;
        if (pageHeight > 0 && !planning && !stoppingNative) y = page;
        if (!planning && !stoppingNative && offset != null) { stopSpring(); stopNative(); }
        super.scrollTo(x, y);
    }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        layingOut = true;
        try { super.onLayout(changed, left, top, right, bottom); }
        finally { layingOut = false; }
    }
    private boolean revealFinalFocus() {
        if (pageHeight > 0) return false;
        View focused = findFocus();
        if (focused == null || focused == this || !focused.isAttachedToWindow()) return false;
        if (!descendantBounds(this, focused, finalFocus, focusMatrix, matrixStep)) return false;
        if (finalFocus.height() > getHeight()) return false;
        int delta = finalFocus.bottom > getHeight() ? (int) Math.ceil(finalFocus.bottom - getHeight())
                : finalFocus.top < 0 ? (int) Math.floor(finalFocus.top) : 0;
        int maximum = getChildCount() == 0 ? 0 : Math.max(0,
                getChildAt(0).getHeight() - getHeight() + getPaddingTop() + getPaddingBottom());
        int destination = Math.max(0, Math.min(maximum, getScrollY() + delta));
        if (destination == getScrollY()) return false;
        offset.snap(getScrollY()); offset.target = destination; offset.tolerance(Math.abs(delta));
        return true;
    }

    /** API 26 equivalent of descendant-global × inverse-viewport-global. */
    static boolean descendantBounds(View viewport, View descendant, RectF bounds, Matrix result, Matrix step) {
        result.reset();
        View child = descendant;
        while (child != viewport) {
            if (!(child.getParent() instanceof View)) return false;
            View parent = (View) child.getParent();
            step.set(child.getMatrix());
            step.postTranslate(child.getLeft() - parent.getScrollX(), child.getTop() - parent.getScrollY());
            result.postConcat(step);
            child = parent;
        }
        bounds.set(0, 0, descendant.getWidth(), descendant.getHeight());
        result.mapRect(bounds);
        return true;
    }
    @Override public void fling(int velocityY) {
        if (pageHeight > 0) {
            if (velocityY == 0) {
                stopSpring(); stopNative(); int destination = pageOffset(getScrollY());
                super.scrollTo(getScrollX(), destination); offset.snap(destination);
            } else {
                int from = pageOffset(frameScheduled ? Math.round(offset.target) : getScrollY());
                smoothScrollToPosition(from + (velocityY > 0 ? pageHeight : -pageHeight));
            }
            return;
        }
        stopSpring();
        if (isSmoothScrollingEnabled()) super.fling(velocityY); else stopNative();
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) stopSpring();
        boolean handled = super.onTouchEvent(event);
        if (pageHeight > 0 && (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) && !frameScheduled)
            smoothScrollToPosition(pageOffset(getScrollY()));
        return handled;
    }
    @Override protected void onDetachedFromWindow() { stopSpring(); super.onDetachedFromWindow(); }

    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        if (rect.isEmpty() || getChildCount() == 0) return 0;
        if (pageHeight > 0) {
            long center = Math.max(0, rect.top + rect.height() / 2L);
            int destination = (int) Math.min(Integer.MAX_VALUE, (center / pageHeight) * pageHeight);
            return pageOffset(destination) - getScrollY();
        }

        // arrowScroll computes its smooth-scroll destination before assigning focus.
        // Reserve half of the maximum 1.06 scale, plus lift/rim clearance, here so
        // that destination already contains the final outline. All values use the
        // same local coordinates as the 1280-wide design board, not display pixels.
        int extra = (int) Math.ceil(rect.height() * 0.03f) + 8;
        focusEnvelope.set(rect);
        focusEnvelope.inset(0, -extra);
        int top = getScrollY(), bottom = top + getHeight(), delta = 0;
        if (focusEnvelope.bottom > bottom && focusEnvelope.top > top) {
            delta = focusEnvelope.height() > getHeight()
                    ? focusEnvelope.top - top : focusEnvelope.bottom - bottom;
        } else if (focusEnvelope.top < top && focusEnvelope.bottom < bottom) {
            delta = focusEnvelope.height() > getHeight()
                    ? focusEnvelope.bottom - bottom : focusEnvelope.top - top;
        }
        // Match scrollTo's padded range. The framework rectangle helper instead
        // caps at child.bottom, dropping the reserved margin near content edges.
        int viewport = Math.max(0, getHeight() - getPaddingTop() - getPaddingBottom());
        int maximum = Math.max(0, getChildAt(0).getHeight() - viewport);
        return Math.max(0, Math.min(maximum, top + delta)) - top;
    }
    private int pageOffset(int y) {
        if (pageHeight == 0) return y;
        long nearest = (Math.max(0, (long) y) + pageHeight / 2L) / pageHeight;
        return (int) (Math.min(nearest, maximumScroll() / pageHeight) * pageHeight);
    }
    private int maximumScroll() {
        return getChildCount() == 0 ? 0 : Math.max(0,
                getChildAt(0).getHeight() - getHeight() + getPaddingTop() + getPaddingBottom());
    }
}
