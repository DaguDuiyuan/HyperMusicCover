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
    /** Per-frame scratch, kept rather than allocated on every draw. */
    private final Path clipPath = new Path();
    private final int[] tmpLoc = new int[2];
    private ViewTreeObserver geometryObserver;
    private ViewTreeObserver.OnPreDrawListener geometryListener;
    private boolean lastLockEligible;
    private int lastWidth = -1, lastHeight = -1, lastTop = Integer.MIN_VALUE;
    private float lastClock = Float.NaN, lastMedia = Float.NaN;

    private final Wash wash;

    private CoverCardLayer(Context context) {
        super(context);
        wash = new Wash(context);
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
                if (v.wash.getParent() instanceof ViewGroup) {
                    ((ViewGroup) v.wash.getParent()).removeView(v.wash);
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
        // Beside keyguard_root_view rather than inside it: the doze zooms that view to 0.95 and
        // its bounds clip after the zoom, so nothing under it reaches the screen edge - a
        // counter-scaled wash in the layer was measured still cut to 95%. Just below it keeps
        // the same stacking the layer gave. The layer is the fallback, zoom and all.
        ViewGroup host = layer;
        View below = v;
        View zoomed = zoomedRoot(layer);
        if (zoomed != null && zoomed.getParent() instanceof ViewGroup) {
            host = (ViewGroup) zoomed.getParent();
            below = zoomed;
        }
        if (v.wash.getParent() != host) {
            if (v.wash.getParent() instanceof ViewGroup) {
                ((ViewGroup) v.wash.getParent()).removeView(v.wash);
            }
            host.addView(v.wash, host.indexOfChild(below), new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        v.style = sStyle;
        v.playing = sPlayingState;
        v.watchGeometry(layer);
        v.adoptPending();
        v.start();
    }

    /** keyguard_root_view, the view the doze zooms, found upwards from the layer. */
    private static View zoomedRoot(View from) {
        int id = from.getResources().getIdentifier("keyguard_root_view", "id",
                "com.android.systemui");
        if (id == 0) return null;
        for (Object p = from; p instanceof View; p = ((View) p).getParent()) {
            if (((View) p).getId() == id) return (View) p;
        }
        return null;
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
                // Every frame of the window while the wash is up: the doze zoom it has to undo
                // animates, and nothing else of ours is drawing frames through it.
                if (wash.getVisibility() == VISIBLE) wash.fit();
                int[] loc = tmpLoc;
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
            // The pre-draw guard runs on every frame of the shade window, lock screen or not.
            // Only the square needs it, so the full-screen cover does not pay for it.
            if (config.mode == CoverCardStyle.CARD) {
                if (v.getParent() instanceof ViewGroup) v.watchGeometry((ViewGroup) v.getParent());
                v.start();
            } else {
                v.unwatchGeometry();
                v.hideImmediately();
            }
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
                    if (v == null) return;
                    // The first art has nothing to keep pace with. A swap waits for the
                    // wallpaper to start its own - see releaseHeld() - with a ceiling in case
                    // that word never comes.
                    Main.main().removeCallbacks(ADOPT_HELD);
                    if (v.current == null) v.adoptPending();
                    else Main.main().postDelayed(ADOPT_HELD, HOLD_MAX_MS);
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

    /** Longer than the 70-160ms the wallpaper was measured behind by, short of feeling stuck. */
    private static final long HOLD_MAX_MS = 400L;

    private static final Runnable ADOPT_HELD = new Runnable() {
        @Override public void run() {
            CoverCardLayer v = sView;
            if (v != null) v.adoptPending();
        }
    };

    /**
     * The blurred background under the square has started changing - the wallpaper process
     * says so (op wpart), or the video path put it up itself - so the square turns over with it.
     */
    static void releaseHeld() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Main.main().post(new Runnable() { @Override public void run() { releaseHeld(); } });
            return;
        }
        Main.main().removeCallbacks(ADOPT_HELD);
        ADOPT_HELD.run();
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
        wash.hideNow();
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

    /** For `op cardstate`. */
    static String describe() {
        CoverCardLayer v = sView;
        if (v == null) return "view=none";
        // The ancestors' scale and whether each one clips its children: the AOD wash is drawn
        // past this view's bounds, which only reaches the screen edge if nothing clips it.
        StringBuilder chain = new StringBuilder();
        for (Object p = v.getParent(); p instanceof ViewGroup; p = ((ViewGroup) p).getParent()) {
            ViewGroup g = (ViewGroup) p;
            String id = "?";
            try {
                if (g.getId() != View.NO_ID) id = g.getResources().getResourceEntryName(g.getId());
            } catch (Throwable ignored) {
            }
            chain.append(' ').append(id).append("[s=").append(g.getScaleX())
                    .append(g.getClipChildren() ? ",clip" : "").append(']');
        }
        Wash w = v.wash;
        int[] at = new int[2];
        w.getLocationOnScreen(at);
        chain.append(" wash[vis=").append(w.getVisibility() == VISIBLE)
                .append(' ').append(w.getWidth()).append('x').append(w.getHeight())
                .append(" s=").append(w.getScaleX()).append('/').append(w.getScaleY())
                .append(" pivot=").append(w.getPivotX()).append(',').append(w.getPivotY())
                .append(" at=").append(at[0]).append(',').append(at[1])
                .append(" screen=").append(Main.screenWidth()).append('x')
                .append(Main.screenHeight()).append(']');
        return "view.playing=" + v.playing + " scale=" + v.scale.value
                + " ticking=" + v.ticking + " opacity=" + v.opacity
                + " attached=" + v.isAttachedToWindow() + " chain=" + chain;
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
        if (style.mode == CoverCardStyle.CARD && getParent() instanceof ViewGroup) {
            watchGeometry((ViewGroup) getParent());
        }
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        unwatchGeometry();
        super.onDetachedFromWindow();
    }

    /** Where the square is drawn, eased towards placeNow(); NaN until it has a place. */
    private float drawX = Float.NaN, drawY, drawSide;
    /** The last place the lit lock screen gave it - what the doze keeps. */
    private CoverCardStyle.Rect lockPlace;

    /** The square's place from the live clock and media card, in this view's coordinates. */
    private CoverCardStyle.Rect placeNow() {
        int[] loc = tmpLoc;
        getLocationOnScreen(loc);
        return style.place(getWidth(), getHeight(), getResources().getDisplayMetrics().density,
                ClockCollapse.contentBottomOnScreen() - loc[1],
                Main.coverCardMediaTop() - loc[1]);
    }

    /**
     * Moves the drawn place one frame towards where the square belongs.
     *
     * Through the doze it keeps the lock screen's place: asked live, the media card's AOD stand-in
     * (70% of the screen) re-centred it, and it jumped 63px on the way in and back 11px on the
     * wake, while everything around it slid with the doze zoom. It sits inside that zoom, so
     * holding still in its own coordinates is exactly sliding with it. Any other change of
     * place - the wake landing, a rebuilt media card - is eased at the 缩放动画阻尼 response
     * instead of cut. A square that is not showing yet just takes its place.
     *
     * @return whether it is still on its way
     */
    private boolean followPlace(ClockCollapse.Phase phase, float dt, float response,
                                float target) {
        CoverCardStyle.Rect goal;
        if (phase == ClockCollapse.Phase.AOD) {
            goal = lockPlace != null ? lockPlace : placeNow();
        } else {
            goal = placeNow();
            if (goal != null) lockPlace = goal;
        }
        if (goal == null) return false;
        if (Float.isNaN(drawX) || opacity <= 0f) {
            drawX = goal.x;
            drawY = goal.y;
            drawSide = goal.side;
            return false;
        }
        float k = Math.min(1f, dt * 3f / response);
        drawX += (goal.x - drawX) * k;
        drawY += (goal.y - drawY) * k;
        drawSide += (goal.side - drawSide) * k;
        boolean moving = Math.abs(goal.x - drawX) > 0.5f || Math.abs(goal.y - drawY) > 0.5f
                || Math.abs(goal.side - drawSide) > 0.5f;
        if (!moving) {
            drawX = goal.x;
            drawY = goal.y;
            drawSide = goal.side;
        }
        return moving;
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
        // At the response the app's 缩放动画阻尼 sets, so the card keeps time with the clock,
        // the wallpaper and the morph instead of running on a clock of its own.
        else scale.step(scaleTarget, dt, Main.sClockResponse);
        boolean placing = followPlace(phase, dt, response, target);
        if (previous != null && (phase == ClockCollapse.Phase.AOD
                || SystemClock.uptimeMillis() - changedAt > TRACK_FADE_MS)) {
            previous.recycle();
            previous = null;
        }
        boolean backdrop = Main.coverCardBackdropInAod() && current != null
                && current.aodBackdrop != null;
        wash.show(backdrop);
        int visibility = opacity > 0f || target > 0f ? VISIBLE : GONE;
        if (getVisibility() != visibility) setVisibility(visibility);
        if (visibility == VISIBLE) invalidate();
        boolean settling = Math.abs(target - opacity) > 0.001f
                || (phase != ClockCollapse.Phase.AOD && !scale.atRest(scaleTarget))
                || previous != null || placing;
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
        // A doze frame may draw before the queued animation frame clears a stale lock-screen
        // opacity. The lyric page must never show the square underneath it in the full AOD.
        if (opacity <= 0f || CoverMorphLayer.cardSuppressed()
                || (Main.coverCardInAod() && LockLyrics.wantsAttached())) return;
        float density = getResources().getDisplayMetrics().density;
        if (Float.isNaN(drawX)) return;
        CoverMorphMotion.Box actual = CoverMorphMotion.cardSquare(drawX, drawY,
                drawSide, scale.value);
        float scaled = actual.w;
        square.set(actual.x, actual.y, actual.x + actual.w, actual.y + actual.h);
        float radius = Math.min(20f * density, scaled * 0.10f);
        drawShadow(canvas, square, radius, density, opacity, paint);
        int save = canvas.save();
        clipPath.reset();
        clipPath.addRoundRect(square, radius, radius, Path.Direction.CW);
        canvas.clipPath(clipPath);
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

    /**
     * The full-screen AOD's faint colour wash, as a view of its own beside the square.
     *
     * The doze scales keyguard_root_view to 0.95 (`op cardstate` lists the chain). Drawn inside
     * it, the wash came out 5% short and framed by the dark wallpaper, and neither drawing past
     * the card layer's bounds nor scaling this view back out by the inverse of the zoom got past
     * that: the second one was measured landing on the full screen at (0,0) and still cut to
     * 95%. So it lives beside keyguard_root_view, just below it (see attachNow), and fit() is
     * left to undo whatever zoom is still above it - normally none. The zoom itself is right and
     * stays: the square rides it with the rest of the lock screen.
     */
    private static final class Wash extends View {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final int[] loc = new int[2];
        private final RectF bounds = new RectF();

        Wash(Context context) {
            super(context);
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setVisibility(GONE);
        }

        /**
         * In over the doze's own dimming and out over the wallpaper's brightening. Put up and
         * taken down in one frame it flashed: brighter for a moment going into the AOD, while the
         * wallpaper had not dimmed yet, and darker for ~100ms coming out, while it was still dim.
         */
        private static final long IN_MS = 500L, OUT_MS = 250L;
        private boolean want;
        private float from;
        private long changedAt;

        /** 0..1, how much of the wash is up right now. */
        private float level() {
            float t = Math.min(1f, (SystemClock.uptimeMillis() - changedAt)
                    / (float) (want ? IN_MS : OUT_MS));
            float e = t * t * (3f - 2f * t);
            return from + ((want ? 1f : 0f) - from) * e;
        }

        void show(boolean on) {
            if (on != want) {
                from = getVisibility() == VISIBLE ? level() : 0f;
                want = on;
                changedAt = SystemClock.uptimeMillis();
            }
            if (on && getVisibility() != VISIBLE) setVisibility(VISIBLE);
            if (getVisibility() == VISIBLE) {
                if (on) fit();
                invalidate();
            }
        }

        /** Unlocked, or anywhere else the wash has no business: gone at once. */
        void hideNow() {
            want = false;
            from = 0f;
            if (getVisibility() != GONE) setVisibility(GONE);
        }

        /** Scales this view so that its bounds land on the whole display. */
        void fit() {
            if (!(getParent() instanceof View) || getWidth() <= 0 || getHeight() <= 0) return;
            View parent = (View) getParent();
            float sx = 1f, sy = 1f;
            for (Object p = parent; p instanceof View; p = ((View) p).getParent()) {
                sx *= ((View) p).getScaleX();
                sy *= ((View) p).getScaleY();
            }
            int sw = Main.screenWidth(), sh = Main.screenHeight();
            if (sw <= 0 || sh <= 0 || !(sx > 0f) || !(sy > 0f)) return;
            parent.getLocationOnScreen(loc);
            fitAxis(true, loc[0], sx, sw / (getWidth() * sx));
            fitAxis(false, loc[1], sy, sh / (getHeight() * sy));
        }

        /**
         * On screen, x in this view lands at origin + s * (pivot + (x - pivot) * k). Solving for
         * x = 0 landing on 0 gives the pivot; k is what makes the far edge land on the far edge.
         */
        private void fitAxis(boolean x, float origin, float s, float k) {
            float pivot = 0f;
            if (Math.abs(1f - k) > 1e-4f) pivot = -origin / (s * (1f - k));
            else k = 1f;
            if (x) {
                if (getScaleX() != k) setScaleX(k);
                if (getPivotX() != pivot) setPivotX(pivot);
            } else {
                if (getScaleY() != k) setScaleY(k);
                if (getPivotY() != pivot) setPivotY(pivot);
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            CoverCardLayer v = sView;
            Prepared p = v == null ? null : v.current;
            float level = level();
            boolean moving = SystemClock.uptimeMillis() - changedAt < (want ? IN_MS : OUT_MS);
            if (!want && !moving) {
                // Faded all the way out: off the draw list until it is wanted again.
                post(new Runnable() {
                    @Override public void run() { if (!want) setVisibility(GONE); }
                });
                return;
            }
            if (moving) postInvalidateOnAnimation();
            if (p == null || p.aodBackdrop == null || p.aodBackdrop.isRecycled()) return;
            paint.setAlpha(Math.round(64f * level));
            bounds.set(0f, 0f, getWidth(), getHeight());
            canvas.drawBitmap(p.aodBackdrop, null, bounds, paint);
        }
    }

    /**
     * The same crossfade the wallpaper runs under it on a track change - WallpaperProbe's
     * sTrackFadeMs and its cubic ease-out - so the square and its blurred backdrop turn over
     * together instead of the art lagging the background by 400ms.
     */
    private static final long TRACK_FADE_MS = 180L;

    private float fadeFraction() {
        if (previous == null) return 1f;
        float t = Math.min(1f, (SystemClock.uptimeMillis() - changedAt) / (float) TRACK_FADE_MS);
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }

    private void drawArt(Canvas canvas, Prepared p, float fraction) {
        if (p == null || fraction <= 0f) return;
        paint.setShader(null);
        paint.setColor(0xFFFFFFFF);
        paint.setAlpha(Math.round(255f * opacity * fraction));
        canvas.drawBitmap(p.art, null, square, paint);
    }

}
