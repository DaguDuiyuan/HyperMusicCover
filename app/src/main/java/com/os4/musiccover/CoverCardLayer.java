package com.os4.musiccover;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

/** The module's own square artwork. The OEM clock and media card remain above this layer. */
final class CoverCardLayer extends View implements Choreographer.FrameCallback {
    private static CoverCardLayer sView;
    private static volatile Prepared sPending;
    private static volatile int sGeneration;
    private static volatile CoverCardStyle sStyle = CoverCardStyle.defaults();
    private static volatile boolean sPlayingState;

    private static final class Prepared {
        final Bitmap art;
        final Bitmap aodBackdrop;
        final int generation;
        Prepared(Bitmap art, Bitmap aodBackdrop, int generation) {
            this.art = art;
            this.aodBackdrop = aodBackdrop;
            this.generation = generation;
        }
        void recycle() {
            art.recycle();
            if (aodBackdrop != null) aodBackdrop.recycle();
        }
    }

    private Prepared current, previous;
    private long changedAt;
    private long lastFrame;
    private boolean ticking;
    private float opacity;
    private final CardSpring scale = new CardSpring();
    private boolean playing = sPlayingState;
    private boolean exitWithCard;
    private CoverCardStyle style = sStyle;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF square = new RectF();
    private ViewTreeObserver geometryObserver;
    private ViewTreeObserver.OnPreDrawListener geometryListener;
    private boolean lastLockEligible;
    private int lastWidth = -1, lastHeight = -1, lastTop = Integer.MIN_VALUE;
    private float lastClock = Float.NaN, lastMedia = Float.NaN;

    private CoverCardLayer(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
    }

    static void attach(ViewGroup layer) {
        if (layer == null) return;
        final ViewGroup target = layer;
        Main.main().post(new Runnable() {
            @Override public void run() { attachNow(target); }
        });
    }

    private static void attachNow(ViewGroup layer) {
        if (!layer.isAttachedToWindow()) return;
        CoverCardLayer v = sView;
        if (v == null || v.getContext() != layer.getContext()) {
            if (v != null) {
                v.stop();
                if (v.getParent() instanceof ViewGroup) {
                    ((ViewGroup) v.getParent()).removeView(v);
                }
                if (v.previous != null && v.previous != sPending) v.previous.recycle();
                if (v.current != null && v.current != sPending) v.current.recycle();
            }
            v = new CoverCardLayer(layer.getContext());
            v.scale.snap(sPlayingState ? CardSpring.PLAYING : CardSpring.PAUSED);
            sView = v;
        }
        if (v.getParent() != layer) {
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
            layer.addView(v, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        v.bringToFront();
        v.style = sStyle;
        v.playing = sPlayingState;
        v.watchGeometry(layer);
        v.adoptPending();
        v.start();
    }

    private void watchGeometry(ViewGroup layer) {
        if (geometryObserver == layer.getViewTreeObserver() && geometryListener != null) return;
        unwatchGeometry();
        geometryObserver = layer.getViewTreeObserver();
        geometryListener = new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                // This parent is also drawn behind the unlocked notification shade. The card's
                // animation stops when it settles, and ACTION_USER_PRESENT is not guaranteed to
                // arrive before the shade uses this layer. Enforce the lock-screen condition on
                // the parent's draw, even when no card frame has been requested.
                if (!layerVisible()) {
                    lastLockEligible = false;
                    hideOutsideKeyguard("parent pre-draw");
                    return true;
                }
                if (!lastLockEligible) {
                    lastLockEligible = true;
                    start();
                }
                int[] loc = new int[2];
                getLocationOnScreen(loc);
                float clock = ClockCollapse.contentBottomOnScreen();
                float media = Main.coverCardMediaTop();
                if (lastWidth != getWidth() || lastHeight != getHeight() || lastTop != loc[1]
                        || Float.floatToIntBits(lastClock) != Float.floatToIntBits(clock)
                        || Float.floatToIntBits(lastMedia) != Float.floatToIntBits(media)) {
                    lastWidth = getWidth();
                    lastHeight = getHeight();
                    lastTop = loc[1];
                    lastClock = clock;
                    lastMedia = media;
                    start();
                }
                return true;
            }
        };
        geometryObserver.addOnPreDrawListener(geometryListener);
    }

    private void unwatchGeometry() {
        if (geometryObserver != null && geometryListener != null
                && geometryObserver.isAlive()) {
            geometryObserver.removeOnPreDrawListener(geometryListener);
        }
        geometryObserver = null;
        geometryListener = null;
        lastLockEligible = false;
        lastWidth = lastHeight = -1;
    }

    static void style(CoverCardStyle config) {
        sStyle = config;
        CoverCardLayer v = sView;
        if (v != null) {
            v.style = config;
            v.start();
        }
    }

    /** Called on the existing art worker. Never retain the player's bitmap. */
    static void publish(Bitmap source) {
        final int generation = ++sGeneration;
        if (source == null || source.isRecycled()) return;
        Bitmap readable = null, art = null, aodBackdrop = null;
        try {
            readable = source.getConfig() == Bitmap.Config.HARDWARE
                    ? source.copy(Bitmap.Config.ARGB_8888, false) : source;
            int w = readable.getWidth(), h = readable.getHeight();
            int side = Math.min(w, h);
            Rect crop = new Rect((w - side) / 2, (h - side) / 2,
                    (w + side) / 2, (h + side) / 2);
            art = Bitmap.createBitmap(Math.min(512, side), Math.min(512, side),
                    Bitmap.Config.ARGB_8888);
            new Canvas(art).drawBitmap(readable, crop,
                    new Rect(0, 0, art.getWidth(), art.getHeight()),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            // The AOD's own dimming can flatten the wallpaper almost to black. A small copy of
            // the same static blur is drawn over it at low alpha only in the full-screen AOD.
            // Prepare it with the artwork, off the UI thread, then reuse it without animation.
            try {
                int backdropW = 256;
                int backdropH = Math.min(768, Math.max(256,
                        Math.round(backdropW * Main.screenHeight()
                                / (float) Main.screenWidth())));
                aodBackdrop = CoverCompose.cardBackground(readable, backdropW, backdropH);
            } catch (Throwable t) {
                Xp.log("[MCCard] AOD backdrop preparation failed: " + t);
            }
            final Prepared p = new Prepared(art, aodBackdrop, generation);
            art = null;
            aodBackdrop = null;
            final Prepared old = sPending;
            sPending = p;
            Main.main().post(new Runnable() {
                @Override public void run() {
                    CoverCardLayer v = sView;
                    // Decided here, on the thread that adopts: read from the worker, `current`
                    // could become `old` between the check and the recycle, and the next draw
                    // would throw inside SystemUI.
                    if (old != null && old != sPending && (v == null
                            || (old != v.current && old != v.previous))) old.recycle();
                    if (v != null) v.adoptPending();
                }
            });
        } catch (Throwable t) {
            Xp.log("[MCCard] artwork preparation failed: " + t);
        } finally {
            if (readable != null && readable != source) readable.recycle();
            if (art != null) art.recycle();
            if (aodBackdrop != null) aodBackdrop.recycle();
        }
    }

    private static boolean layerVisible() {
        return Main.coverCardVisible() || Main.coverCardBackdropInAod();
    }

    static void clear() {
        ++sGeneration;
        Main.main().post(new Runnable() {
            @Override public void run() {
                CoverCardLayer v = sView;
                if (v != null) v.start();
            }
        });
    }

    static void refresh() {
        CoverCardLayer v = sView;
        if (v != null) {
            v.start();
            v.invalidate();
        }
    }

    static void hideNow() {
        CoverCardLayer v = sView;
        if (v == null) return;
        v.hideImmediately();
    }

    private void hideOutsideKeyguard(String from) {
        if (getVisibility() == VISIBLE) {
            Xp.log("[MCCard] hidden outside keyguard on " + from);
        }
        if (getVisibility() != GONE || ticking || opacity != 0f) hideImmediately();
    }

    private void hideImmediately() {
        stop();
        opacity = 0f;
        exitWithCard = false;
        if (getVisibility() != GONE) setVisibility(GONE);
    }

    static void entering() {
        CoverCardLayer v = sView;
        if (v != null) v.exitWithCard = false;
    }

    static void leaving() {
        CoverCardLayer v = sView;
        if (v != null) v.exitWithCard = v.opacity > 0.05f
                && !LockLyrics.wantsAttached();
    }

    static void playback(boolean on) {
        sPlayingState = on;
        CoverCardLayer v = sView;
        if (v != null) {
            v.playing = on;
            // An invisible card has no scale transition to preserve. Place it at the correct
            // playback size before a later thumbnail morph first asks for its destination.
            if (v.opacity <= 0f && !CoverMorphLayer.cardSuppressed()) {
                v.scale.snap(on ? CardSpring.PLAYING : CardSpring.PAUSED);
            }
            v.start();
        }
    }

    static float renderedScale(ViewGroup layer) {
        CoverCardLayer v = sView;
        return v != null && v.getParent() == layer ? v.scale.value
                : (sPlayingState ? CardSpring.PLAYING : CardSpring.PAUSED);
    }

    /** A hidden card is kept drawing internally until it can take the morph without a jump. */
    static boolean morphHandoffReady() {
        CoverCardLayer v = sView;
        if (v == null || v.current == null || v.getParent() == null) return false;
        float target = v.playing ? CardSpring.PLAYING : CardSpring.PAUSED;
        return v.opacity >= 0.995f && v.scale.atRest(target);
    }

    private void adoptPending() {
        Prepared p = sPending;
        if (p == null || p == current || p.generation != sGeneration) return;
        if (previous != null) previous.recycle();
        previous = current;
        current = p;
        changedAt = SystemClock.uptimeMillis();
        start();
    }

    private void start() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post(new Runnable() { @Override public void run() { start(); } });
            return;
        }
        if (ticking) return;
        ticking = true;
        lastFrame = 0L;
        // A temporary vote for panels that honour it. The spring is sampled on every VSYNC,
        // and the vote is removed as soon as it settles; some SystemUI windows still cap at 60Hz.
        setRequestedFrameRate(120f);
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void stop() {
        if (ticking) Choreographer.getInstance().removeFrameCallback(this);
        if (ticking) setRequestedFrameRate(0f);
        ticking = false;
        lastFrame = 0L;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // A detached/re-attached keyguard layer gets a new ViewTreeObserver. Keep the guard even
        // when SystemUI reuses this same card view for the shade.
        if (getParent() instanceof ViewGroup) watchGeometry((ViewGroup) getParent());
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        unwatchGeometry();
        super.onDetachedFromWindow();
    }

    @Override public void doFrame(long nowNs) {
        if (!ticking || !isAttachedToWindow()) { stop(); return; }
        float dt = lastFrame == 0L ? 1f / 60f
                : Math.min(0.05f, Math.max(0f, (nowNs - lastFrame) / 1e9f));
        lastFrame = nowNs;
        ClockCollapse.Phase phase = ClockCollapse.phase();
        boolean inAod = Main.coverCardInAod();
        boolean visible = style.mode == CoverCardStyle.CARD && Main.coverCardVisible();
        // 0.2.2 can keep lyrics in the full-screen AOD. The selected lyric page owns this space.
        boolean lyrics = LockLyrics.wantsAttached();
        float target = visible && current != null
                && (phase == ClockCollapse.Phase.EXIT ? exitWithCard : !lyrics)
                ? (inAod ? 1f : Main.cardProgress()) : 0f;
        float response = Math.max(0.18f, Main.sClockResponse);
        if ((inAod && lyrics) || phase == ClockCollapse.Phase.ENTER
                || phase == ClockCollapse.Phase.EXIT) {
            opacity = target;
        } else {
            opacity += (target - opacity) * Math.min(1f, dt * 3f / response);
        }
        if (Math.abs(opacity - target) < 0.002f) opacity = target;
        float scaleTarget = playing ? CardSpring.PLAYING : CardSpring.PAUSED;
        if (phase == ClockCollapse.Phase.AOD) scale.snap(scaleTarget);
        else scale.step(scaleTarget, dt);
        if (previous != null && (phase == ClockCollapse.Phase.AOD
                || SystemClock.uptimeMillis() - changedAt > 600L)) {
            previous.recycle();
            previous = null;
        }
        boolean backdrop = Main.coverCardBackdropInAod() && current != null
                && current.aodBackdrop != null;
        int visibility = opacity > 0f || target > 0f || backdrop ? VISIBLE : GONE;
        if (getVisibility() != visibility) setVisibility(visibility);
        if (visibility == VISIBLE) invalidate();
        boolean settling = Math.abs(target - opacity) > 0.001f
                || (phase != ClockCollapse.Phase.AOD && !scale.atRest(scaleTarget))
                || previous != null;
        if (settling) {
            Choreographer.getInstance().postFrameCallback(this);
        } else {
            stop();
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        // Last line of defence if SystemUI replaced the parent's observer during a transition.
        if (!layerVisible()) {
            lastLockEligible = false;
            hideOutsideKeyguard("card draw");
            return;
        }
        if (current == null) return;
        if (Main.coverCardBackdropInAod() && current.aodBackdrop != null) {
            paint.setShader(null);
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha(64);
            canvas.drawBitmap(current.aodBackdrop, null,
                    new RectF(0f, 0f, getWidth(), getHeight()), paint);
        }
        // A doze frame may draw before the queued animation frame clears a stale lock-screen
        // opacity. The lyric page must never show the square underneath it in the full AOD.
        if (opacity <= 0f || CoverMorphLayer.cardSuppressed()
                || (Main.coverCardInAod() && LockLyrics.wantsAttached())) return;
        float density = getResources().getDisplayMetrics().density;
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        CoverCardStyle.Rect r = style.place(getWidth(), getHeight(), density,
                ClockCollapse.contentBottomOnScreen() - loc[1],
                Main.coverCardMediaTop() - loc[1]);
        if (r == null) return;
        CoverMorphMotion.Box actual = CoverMorphMotion.cardSquare(r.x, r.y,
                r.side, scale.value);
        float scaled = actual.w;
        square.set(actual.x, actual.y, actual.x + actual.w, actual.y + actual.h);
        float radius = Math.min(20f * density, scaled * 0.10f);
        drawShadow(canvas, square, radius, density, opacity, paint);
        int save = canvas.save();
        Path clip = new Path();
        clip.addRoundRect(square, radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
        drawArt(canvas, previous, 1f);
        drawArt(canvas, current, fadeFraction());
        canvas.restoreToCount(save);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density);
        paint.setColor(0x40FFFFFF);
        paint.setAlpha(Math.round(opacity * 64f));
        canvas.drawRoundRect(square, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /**
     * A soft drop shadow under the square, shared with CoverMorphLayer so the hand-over does not
     * change it. Blurred and pulled in from the edges: an unblurred copy offset downwards reads
     * as a dark slab along the bottom edge rather than as a shadow.
     */
    static void drawShadow(Canvas canvas, RectF box, float radius, float density,
                           float strength, Paint paint) {
        if (strength <= 0f) return;
        float blur = 16f * density;
        float inset = 6f * density;
        float dy = 8f * density;
        if (sShadowBlur == null || sShadowBlurPx != blur) {
            sShadowBlur = new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL);
            sShadowBlurPx = blur;
        }
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF000000);
        paint.setAlpha(Math.round(strength * 90f));
        paint.setMaskFilter(sShadowBlur);
        canvas.drawRoundRect(box.left + inset, box.top + inset + dy,
                box.right - inset, box.bottom - inset + dy, radius, radius, paint);
        paint.setMaskFilter(null);
    }

    private static BlurMaskFilter sShadowBlur;
    private static float sShadowBlurPx;

    private float fadeFraction() {
        if (previous == null) return 1f;
        float f = Math.min(1f, (SystemClock.uptimeMillis() - changedAt) / 600f);
        return f * f * (3f - 2f * f);
    }

    private void drawArt(Canvas canvas, Prepared p, float fraction) {
        if (p == null || fraction <= 0f) return;
        paint.setShader(null);
        paint.setColor(0xFFFFFFFF);
        paint.setAlpha(Math.round(255f * opacity * fraction));
        canvas.drawBitmap(p.art, null, square, paint);
    }

}
