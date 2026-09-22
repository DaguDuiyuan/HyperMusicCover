package com.os4.musiccover;

import android.content.Context;
import android.graphics.Bitmap;
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

/** Temporary, non-interactive artwork shared between the OEM thumbnail and the cover. */
final class CoverMorphLayer extends View implements Choreographer.FrameCallback {
    private static CoverMorphLayer sView;

    private final CoverMorphMotion motion = new CoverMorphMotion();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF drawn = new RectF();
    private final Path clip = new Path();
    /** Per-frame scratch, kept rather than allocated on every draw. */
    private final int[] tmpLoc = new int[2];
    private final Rect srcRect = new Rect();
    private Bitmap art;
    private boolean cardMode;
    private CoverMorphMotion.Box thumb, cover;
    private long lastFrame, startedAt;
    private boolean awaitArtworkPush;
    private float fullAlpha;
    private boolean running;

    /**
     * One per window, kept attached and INVISIBLE between morphs. Added at the start of every
     * morph and removed at its end, it asked for a layout of the whole shade window twice per
     * morph; VISIBLE and INVISIBLE only redraw. (Not the 25-35ms first frame of a tap toggle:
     * a two-finger switch morphs the same way and never showed it - see the dt cap in doFrame.)
     */
    private CoverMorphLayer(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setVisibility(INVISIBLE);
    }

    private void reset(Bitmap art, boolean cardMode, CoverMorphMotion.Box thumb,
                       CoverMorphMotion.Box cover, boolean toCover) {
        this.art = art;
        this.cardMode = cardMode;
        this.thumb = thumb;
        this.cover = cover;
        awaitArtworkPush = toCover && !Main.coverModeOn();
        fullAlpha = toCover ? 1f : 0f;
        motion.value = toCover ? 0f : 1f;
        motion.velocity = 0f;
        motion.aim(toCover);
        revealAt = 0L;
        lastFrame = 0L;
    }

    /** Called before the state switch so the source is still at its visible location. */
    static boolean begin(boolean toCover) {
        if (Looper.myLooper() != Looper.getMainLooper() || !Main.coverMorphEligible()) return false;
        // The square card only. The full-screen cover keeps its own transition - the clock
        // squeeze and the wallpaper crossfade - untouched.
        if (!Main.coverMorphCardMode()) {
            cancel();
            return false;
        }
        CoverMorphLayer old = sView;
        if (old != null && old.running) {
            if (old.cardMode == Main.coverMorphCardMode()) {
                old.motion.aim(toCover);
                old.revealAt = 0L;
                old.startedAt = SystemClock.uptimeMillis();
                old.invalidate();
                return true;
            }
            old.finish();
        }
        ViewGroup root = Main.coverMorphRoot();
        Bitmap art = Main.coverMorphSource();
        CoverMorphMotion.Box thumb = Main.coverMorphThumbnail();
        CoverMorphMotion.Box cover = art == null ? null : Main.coverMorphTarget(art);
        if (root == null || art == null || art.isRecycled() || thumb == null || cover == null) {
            return false;
        }
        CoverMorphLayer v = sView;
        if (v == null || v.getParent() != root) {
            if (v != null && v.getParent() instanceof ViewGroup) {
                ((ViewGroup) v.getParent()).removeView(v);
            }
            v = new CoverMorphLayer(root.getContext());
            root.addView(v, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            sView = v;
        }
        // bringToFront lays the parent out again too: only when something has come above it.
        if (root.getChildAt(root.getChildCount() - 1) != v) v.bringToFront();
        v.reset(art, Main.coverMorphCardMode(), thumb, cover, toCover);
        v.setVisibility(VISIBLE);
        v.running = true;
        v.startedAt = SystemClock.uptimeMillis();
        v.setRequestedFrameRate(120f);
        Main.refreshMediaCardForMorph();
        CoverCardLayer.refresh();
        Choreographer.getInstance().postFrameCallback(v);
        return true;
    }

    static boolean active() { return sView != null && sView.running; }

    /** Leaves nothing held and the kept view idle; for a keyguard that is going away. */
    private void release() {
        art = null;
        thumb = cover = null;
    }

    /** How long the OEM thumbnail takes to fade back in under the copy that has landed on it. */
    private static final long THUMB_FADE_MS = 120L;
    /** When the copy came to rest on the thumbnail on the way back; 0 until it has. */
    private long revealAt;

    /**
     * The OEM thumbnail's alpha while a morph owns its pixels. Zero on the way out. On the way
     * back it fades in, so its own shadow comes in with it rather than all at once when this
     * layer goes - but only once the copy has settled on it. Earlier, it showed beside a copy
     * still larger and elsewhere, or as a ring round one dipping below its size in the bounce.
     */
    static float thumbAlpha() {
        CoverMorphLayer v = sView;
        if (v == null || !v.running) return 1f;
        if (v.motion.target != 0f || v.revealAt == 0L) return 0f;
        float r = (SystemClock.uptimeMillis() - v.revealAt) / (float) THUMB_FADE_MS;
        return Math.max(0f, Math.min(1f, r));
    }
    static boolean cardSuppressed() { return active() && sView.cardMode; }

    static void cancel() {
        CoverMorphLayer v = sView;
        if (v == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) v.finish();
        else Main.main().post(new Runnable() { @Override public void run() { v.finish(); } });
    }

    @Override public void doFrame(long nowNs) {
        if (!running) return;
        if (!isAttachedToWindow() || !Main.coverMorphStillEligible()
                || getParent() != Main.coverMorphRoot()) {
            finish();
            return;
        }
        // Stepped by the real gap, up to 50ms. Held to 20ms to hide a slow frame, the copy
        // visibly stopped for it instead, which read worse than the catch-up.
        float dt = lastFrame == 0L ? 1f / 120f
                : Math.min(0.05f, Math.max(0f, (nowNs - lastFrame) / 1e9f));
        lastFrame = nowNs;
        motion.step(dt, Main.sClockResponse);
        CoverMorphMotion.Box liveThumb = Main.coverMorphThumbnail();
        if (liveThumb != null) thumb = liveThumb;
        CoverMorphMotion.Box liveCover = Main.coverMorphTarget(art);
        if (liveCover != null) cover = liveCover;
        boolean artworkReady = !awaitArtworkPush || Main.coverMorphHandoffReady(cardMode)
                || SystemClock.uptimeMillis() - startedAt > 2200L;
        boolean cardReady = !cardMode || motion.target != 1f
                || CoverCardLayer.morphHandoffReady()
                || SystemClock.uptimeMillis() - startedAt > 2200L;
        float desiredAlpha = motion.target == 1f
                ? (motion.atRest() && artworkReady ? 0f : 1f)
                : Math.min(1f, Math.max(0f, (1f - motion.value) / 0.18f));
        fullAlpha += (desiredAlpha - fullAlpha) * Math.min(1f, dt * 20f);
        invalidate();
        if (motion.target == 0f) {
            if (revealAt == 0L && motion.atRest()) revealAt = SystemClock.uptimeMillis();
            // The fade-in is written by the card's own pass; it needs a frame to run in.
            if (revealAt != 0L) Main.refreshMediaCardForMorph();
        }
        ClockCollapse.Phase phase = ClockCollapse.phase();
        boolean clockFlying = motion.target == 1f
                ? phase == ClockCollapse.Phase.ENTER
                : phase == ClockCollapse.Phase.EXIT;
        // Back at the thumbnail, the layer stays until the thumbnail has fully faded in.
        boolean handoffDone = motion.target == 0f ? thumbAlpha() >= 1f
                : artworkReady && cardReady && (cardMode || fullAlpha < 0.01f);
        if (motion.atRest() && handoffDone && (!clockFlying
                || SystemClock.uptimeMillis() - startedAt > 2200L)) {
            finish();
        } else {
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        if (!running || art == null || art.isRecycled()) return;
        float density = getResources().getDisplayMetrics().density;
        CoverMorphMotion.Box box = CoverMorphMotion.frame(thumb, cover, motion.value, density);
        int[] root = tmpLoc;
        getLocationOnScreen(root);
        drawn.set(box.x - root[0], box.y - root[1],
                box.x + box.w - root[0], box.y + box.h - root[1]);
        float p = Math.max(0f, Math.min(1f, motion.value));
        float startRadius = Math.min(14f * density, Math.min(thumb.w, thumb.h) * 0.20f);
        float endRadius = cardMode ? Math.min(20f * density, cover.w * 0.10f) : 0f;
        float radius = startRadius + (endRadius - startRadius) * p;
        float decoration = cardMode ? CoverMorphMotion.cardDecoration(motion.value) : 0f;
        CoverCardLayer.drawShadow(canvas, drawn, radius, density, decoration, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        // The full wallpaper already contains the final sharp band. Hand its pixels over near
        // the end while this moving copy still covers the rest of the journey.
        float alpha = cardMode ? 1f : fullAlpha;
        paint.setAlpha(Math.round(255f * alpha));
        if (paint.getAlpha() == 0) return;
        clip.reset();
        clip.addRoundRect(drawn, radius, radius, Path.Direction.CW);
        int saved = canvas.save();
        canvas.clipPath(clip);
        int side = Math.min(art.getWidth(), art.getHeight());
        float reveal = cardMode ? 0f : p;
        int cropW = Math.round(side + (art.getWidth() - side) * reveal);
        int cropH = Math.round(side + (art.getHeight() - side) * reveal);
        Rect source = srcRect;
        source.set((art.getWidth() - cropW) / 2, (art.getHeight() - cropH) / 2,
                (art.getWidth() + cropW) / 2, (art.getHeight() + cropH) / 2);
        canvas.drawBitmap(art, source, drawn, paint);
        canvas.restoreToCount(saved);
        if (decoration > 0f) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(density);
            paint.setColor(0x40FFFFFF);
            paint.setAlpha(Math.round(64f * decoration));
            canvas.drawRoundRect(drawn, radius, radius, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private void finish() {
        if (!running) return;
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        setRequestedFrameRate(0f);
        // Kept for the next morph; see the constructor.
        if (getVisibility() != INVISIBLE) setVisibility(INVISIBLE);
        release();
        Main.refreshMediaCardForMorph();
        CoverCardLayer.refresh();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (running) finish();
        // A rebuilt keyguard gets a view of its own.
        if (sView == this) sView = null;
    }
}
