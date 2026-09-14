package com.controllertouch.app;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.webkit.WebView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Injects real android.view.MotionEvents directly into a WebView via
 * dispatchTouchEvent(), instead of the browser-extension approach of
 * constructing a DOM TouchEvent in JavaScript. This is the whole reason
 * this app exists instead of just using the extension:
 *
 *  - It never goes through Chromium's browser-chrome input layer at all
 *    (there is no tab strip, no back/forward gesture surface here), so
 *    the shoulder-button-reserved-for-navigation problem simply doesn't
 *    apply — there's no browser chrome to reserve buttons for.
 *  - The events entering the WebView's renderer come from Android's real
 *    view dispatch system rather than page-level JavaScript, which is a
 *    fundamentally different, more "real" input path than
 *    document.dispatchEvent(new TouchEvent(...)) — though whether the
 *    resulting DOM event reports isTrusted=true is a WebView/Chromium
 *    implementation detail this class can't control or guarantee.
 *
 * Genuine simultaneous multi-touch (e.g. a stick held down AND a button
 * pressed at the same instant) requires ONE MotionEvent describing ALL
 * currently-active pointers together, using ACTION_POINTER_DOWN/UP with
 * an encoded pointer index for whichever finger just changed — Android
 * does not support expressing "two independent simultaneous touches" as
 * two separate single-pointer event streams. This class tracks all
 * active touches and rebuilds the combined multi-pointer event on every
 * change, so a held stick and a pressed button coexist correctly.
 */
public class TouchInjector {

    private final WebView webView;
    private final long downTime = SystemClock.uptimeMillis();

    // Insertion-ordered so pointer index 0 stays stable-ish across calls;
    // order only matters for which index a given identifier ends up at
    // within a single combined event, not for correctness.
    private final Map<Integer, PointF> activePointers = new LinkedHashMap<>();

    public TouchInjector(WebView webView) {
        this.webView = webView;
    }

    private static class PointF {
        float x, y;
        PointF(float x, float y) { this.x = x; this.y = y; }
    }

    /** Starts a new touch at (x, y) under the given pointer id. */
    public void dispatchDown(int pointerId, float x, float y) {
        boolean isFirst = activePointers.isEmpty();
        activePointers.put(pointerId, new PointF(x, y));
        int action = isFirst
                ? MotionEvent.ACTION_DOWN
                : (MotionEvent.ACTION_POINTER_DOWN | (indexOf(pointerId) << MotionEvent.ACTION_POINTER_INDEX_SHIFT));
        dispatchCombined(action);
    }

    /** Updates the live position of an already-down touch. */
    public void dispatchMove(int pointerId, float x, float y) {
        PointF p = activePointers.get(pointerId);
        if (p == null) return; // move without a matching down — ignore rather than desync state
        p.x = x;
        p.y = y;
        dispatchCombined(MotionEvent.ACTION_MOVE);
    }

    /** Ends a touch. */
    public void dispatchUp(int pointerId, float x, float y) {
        PointF p = activePointers.get(pointerId);
        if (p == null) return;
        p.x = x;
        p.y = y;
        boolean isLast = activePointers.size() == 1;
        int action = isLast
                ? MotionEvent.ACTION_UP
                : (MotionEvent.ACTION_POINTER_UP | (indexOf(pointerId) << MotionEvent.ACTION_POINTER_INDEX_SHIFT));
        dispatchCombined(action);
        activePointers.remove(pointerId);
    }

    private int indexOf(int pointerId) {
        int i = 0;
        for (Integer id : activePointers.keySet()) {
            if (id == pointerId) return i;
            i++;
        }
        return 0;
    }

    /**
     * Builds one MotionEvent describing every currently-active pointer and
     * dispatches it. This is what makes simultaneous multi-touch (stick +
     * button at once) actually work, instead of two events silently
     * fighting over which one the WebView treats as "the" touch.
     */
    private void dispatchCombined(int action) {
        int count = activePointers.size();
        if (count == 0) return;

        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];

        int i = 0;
        for (Map.Entry<Integer, PointF> entry : activePointers.entrySet()) {
            MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
            pp.id = entry.getKey();
            pp.toolType = MotionEvent.TOOL_TYPE_FINGER;
            props[i] = pp;

            MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
            pc.x = entry.getValue().x;
            pc.y = entry.getValue().y;
            pc.pressure = 1f;
            pc.size = 1f;
            coords[i] = pc;
            i++;
        }

        long eventTime = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                count,
                props,
                coords,
                0, // metaState
                0, // buttonState
                1f, 1f, // xPrecision, yPrecision
                0, // deviceId
                0, // edgeFlags
                android.view.InputDevice.SOURCE_TOUCHSCREEN,
                0 // flags
        );

        // dispatchTouchEvent must be called on the UI thread; callers
        // (MainActivity's input handlers) are already on it since they're
        // driven by onKeyDown/onGenericMotionEvent, which are UI-thread
        // callbacks themselves.
        webView.dispatchTouchEvent(event);
        event.recycle();
    }

    /** True if any touch is currently down — useful for debug/status display. */
    public boolean hasActiveTouches() {
        return !activePointers.isEmpty();
    }

    public List<Integer> activePointerIds() {
        return new ArrayList<>(activePointers.keySet());
    }
}
