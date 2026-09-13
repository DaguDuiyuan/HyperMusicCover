package com.os4.musiccover;

import android.view.View;

import java.lang.reflect.Method;

/**
 * HyperOS's private blur methods on View, wrapped so nothing else has to know they are private.
 *
 * Only the four that the notification cards need are resolved, and only the non-gradient ones.
 * The gradient pair (`setBackgroundGradientBlurParams`, `setMiBackgroundBlurType(2)`) is
 * deliberately absent: the module this is ported from shipped the gradient path, hit a
 * RenderThread SIGSEGV inside `vulkan.adreno.so` under frequent updates on this exact GPU, and
 * removed it permanently in favour of a clipped hard edge. There is no reason to walk back into
 * that for a transition band.
 *
 * Every call is individually guarded and a method that is missing simply does nothing. These are
 * OEM privates with no stability guarantee, and losing one should cost a soft edge on the cards,
 * not the feature.
 */
final class MiBlur {

    private MiBlur() {
    }

    private static final String TAG = "[MCMiBlur] ";

    private static volatile boolean sInited;
    private static Method sMode;
    private static Method sType;
    private static Method sRadius;
    private static Method sPassWindow;

    private static synchronized void init() {
        if (sInited) return;
        sInited = true;
        sMode = find("setMiBackgroundBlurMode", int.class);
        sType = find("setMiBackgroundBlurType", int.class);
        sRadius = find("setMiBackgroundBlurRadius", int.class);
        sPassWindow = find("setPassWindowBlurEnabled", boolean.class);
        Xp.log(TAG + "blur api: mode=" + (sMode != null) + " type=" + (sType != null)
                + " radius=" + (sRadius != null) + " passWindow=" + (sPassWindow != null));
    }

    private static Method find(String name, Class<?> param) {
        try {
            final Method m = View.class.getDeclaredMethod(name, param);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The system's own recipe for a container that blurs what is inside the window rather than
     * what is behind it - copied from `NotificationUtil.applyContainerViewBlur(..., localOnly=true)`.
     *
     * The `passWindow = false` is the whole point and the part that is easy to get wrong. A
     * notification row's default treatment is `setMiViewBlurMode(1)`, which REUSES the
     * window-level blur - and that samples the app behind the window. Setting only that flag on
     * the row does nothing at all; the row has to be given a blur of its own and then told not
     * to pass the window's through.
     */
    static void applyLocalBlur(View v, int radius) {
        init();
        call(sMode, v, 1);
        call(sType, v, 1);
        call(sRadius, v, radius);
        call(sPassWindow, v, false);
    }

    static void clearLocalBlur(View v) {
        init();
        call(sRadius, v, 0);
        call(sMode, v, 0);
    }

    private static void call(Method m, View v, Object arg) {
        if (m == null) return;
        try {
            m.invoke(v, arg);
        } catch (Throwable t) {
            // One line per failure would be one line per notification row per re-bind.
        }
    }
}
