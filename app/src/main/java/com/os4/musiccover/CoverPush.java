package com.os4.musiccover;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.HandlerThread;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;

/**
 * Getting the album art into the wallpaper, and keeping the lock wallpaper fit to take it.
 *
 * Split out of Main so that the push pipeline - compose, share the file, tell the wallpaper
 * process, and the video-wallpaper path that bypasses it - reads as one piece. Nothing here
 * decides WHETHER the cover should be on; Main does that and calls in.
 */
final class CoverPush {

    private CoverPush() {
    }

    /** Keyed on the artwork itself, so the app can re-ask as often as it likes for free. */
    private static volatile int sThumbPrint;
    private static volatile byte[] sThumbJpg;

    /**
     * The current artwork, small, as a JPEG. Runs on the receiver's thread - the main one - so
     * it stays deliberately cheap: one downscale and one encode of a picture a few hundred
     * pixels wide, and nothing at all when the artwork has not changed since the last ask.
     */
    static byte[] artThumbnail(Context ctx, int max) {
        Bitmap b = Main.albumArt(ctx);
        if (b == null) return null;
        int print = artPrint(b);
        byte[] cached = sThumbJpg;
        if (cached != null && print != 0 && print == sThumbPrint) return cached;
        try {
            int w = b.getWidth(), h = b.getHeight();
            float k = Math.min(1f, max / (float) Math.max(w, h));
            Bitmap small = k < 1f ? Bitmap.createScaledBitmap(b,
                    Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true) : b;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 88, bos);
            if (small != b) small.recycle();
            byte[] jpg = bos.toByteArray();
            sThumbPrint = print;
            sThumbJpg = jpg;
            return jpg;
        } catch (Throwable t) {
            Xp.log(Main.TAG + "art thumbnail failed: " + t);
            return null;
        }
    }

    /**
     * Hands the album art to the wallpaper process. It cannot read the media session itself, and
     * a full-screen bitmap is far past the binder limit, so send it as a JPEG the size of the
     * screen - already composed here, where we know the screen metrics.
     *
     * Composing costs a full-screen bitmap and a multi-pass blur, so callers go through
     * pushArtAsync() rather than calling this on the main thread.
     */
    private static void pushArtToWallpaper(Context ctx, boolean on) {
        pushArtToWallpaper(ctx, on, on ? Main.albumArt(ctx) : null);
    }

    private static void pushArtToWallpaper(Context ctx, boolean on, Bitmap art) {
        // A live lock wallpaper needs BOTH halves, and returning here after the first was why
        // the card and the clock glass stayed on the video: this view covers the background,
        // but those two sample the wallpaper WINDOW, and the only thing that paints it is the
        // broadcast below. Falling through is what makes the wallpaper process paint it.
        //
        // It costs one extra compose per track change on this path - showVideoCover() composes
        // for the view and the JPEG below composes again. Worth folding into one later; the
        // correctness of having both matters more than the ~100ms.
        if (Main.sVideoWallpaper) showVideoCover(ctx, on, art);
        long t0 = android.os.SystemClock.uptimeMillis();
        Intent out = wallpaperIntent("art");
        out.putExtra("cardmode", Main.sCoverCardStyle.mode == CoverCardStyle.CARD);
        // The blur travels with the cover. A lyricblur broadcast is one shot - dropped, or
        // overtaken on the other side - and nothing else ever corrected it, so a song could play
        // out sharp under its lyrics. This makes every track change an agreement between the two
        // processes, and it also saves the new cover fading in sharp and frosting a beat later.
        //
        // It carries when the answer was decided as well, because this push is built on the
        // worker while the answer is decided on the main thread: a tap out of cover mode and
        // quickly back in has this push holding the answer from before the tap, and - being the
        // slow half, composed before it is sent - it reaches the wallpaper after the lyric switch
        // has said the opposite. The time is what lets that side drop the older of the two
        // instead of taking whichever arrived last. See LockLyrics.putBlurOn.
        LockLyrics.putBlurOn(out);
        // This side's half of the timeline, for `op timing` over there. See sCtTrack.
        out.putExtra("t0", Main.sCtTrack);
        out.putExtra("tskip", sSkipAt);
        out.putExtra("skipdir", sSkipDir);
        out.putExtra("tburst", Main.sCtBurst);
        out.putExtra("skips", Main.sCtSkips);
        out.putExtra("tart", Main.sCtArt);
        out.putExtra("checkms", Main.sCtCheckMs);
        out.putExtra("tries", Main.sCtTries);
        // Only a request. The wallpaper process falls back to the one-frame swap whenever it
        // does not hold both ends of the fade - after its own restart, most of all. Never with
        // the display off: the frames would be composed and uploaded into a screen nobody is
        // looking at, and the clock is not springing either.
        out.putExtra("fade", Main.sFadeWp && Main.screenOn());
        if (!on) {
            CoverCardLayer.clear();
            out.putExtra("off", true);
            ctx.sendBroadcast(out);
            Main.sTrackKey = "";
            // Nothing behind the clock any more, so nothing to take a colour from. The repaint
            // that hands the OEM's own colours back happens with the rest of cover mode.
            Main.sCoverTint = 0;
            // The shade has nothing to reveal any more - unless it is set to keep the last cover.
            // This branch is the single funnel for leaving cover mode - the tap, the dismissed
            // card, the release path and the cover op all end up here - so it is the only place
            // the picture has to be dropped.
            if (!ShadeLayer.keepsArt()) ShadeLayer.setArt(null);
            Xp.log(Main.TAG + "pushart off");
            return;
        }
        if (art == null) { Xp.log(Main.TAG + "pushart: no album art"); return; }
        if (Main.sCoverCardStyle.mode == CoverCardStyle.CARD) CoverCardLayer.publish(art);
        int w = Main.sScreenW, h = Main.sScreenH;
        if (!Main.sVideoWallpaper && Main.sWpComposes) {
            // The source goes over instead of the composed picture, and the wallpaper process
            // composes it for itself. Measured before this, on the path below: composing took
            // 27-94ms and the JPEG 47-185ms, all of it in front of the broadcast, and the other
            // side then spent 14-36ms decoding. The player's artwork is 512x512, so its raw
            // pixels are a 1MB write and no encode at all, and composing it in the wallpaper
            // process also takes that work off SystemUI while the clock is springing.
            Bitmap src = null;
            String shared = null;
            try {
                src = CoverCompose.prepareSource(art, w);
                shared = writeSharedSource(src, w, h, Main.sBias);
            } catch (Throwable t) {
                Xp.log(Main.TAG + "source hand-over failed, sending the composed JPEG instead: " + t);
            }
            if (shared != null) {
                out.putExtra("src", shared);
                out.putExtra("tsent", android.os.SystemClock.uptimeMillis());
                ctx.sendBroadcast(out);
                long sent = android.os.SystemClock.uptimeMillis();
                // Still composed here, but now after the send: the clock's tint and the shade
                // both need the picture, and neither of them is what the user is waiting on.
                // Same source, size and bias as the wallpaper process got, so the same pixels.
                Bitmap full;
                try {
                    full = composeWallpaper(src, w, h, Main.sBias);
                } catch (Throwable t) {
                    Xp.log(Main.TAG + "composeWallpaper failed after the hand-over: " + t);
                    return;
                }
                Main.measureCover(full);
                if (Main.sCoverMode) Main.recolorClock();
                full.recycle();
                // The shade builds its background from the square source and keeps nothing.
                ShadeLayer.setArt(src);
                Xp.log(Main.TAG + "pushart " + w + "x" + h + " bias=" + Main.sBias + " as a "
                        + src.getWidth() + "x" + src.getHeight() + " source, sent in "
                        + (sent - t0) + "ms, own copy composed in "
                        + (android.os.SystemClock.uptimeMillis() - sent) + "ms");
                return;
            }
        }
        Bitmap full;
        try {
            full = composeWallpaper(art, w, h, Main.sBias);
        } catch (Throwable t) {
            // This runs on a HandlerThread, and an uncaught throw on one reaches
            // KillApplicationHandler - so an OutOfMemoryError composing a full-screen cover
            // took SystemUI down with it. The shade's copy is what raises the steady-state
            // heap, which narrows the window rather than creating it; catching is cheap next
            // to a dead process, and the cost of catching is one track without a cover.
            Xp.log(Main.TAG + "composeWallpaper failed, no cover this round: " + t);
            return;
        }
        Main.measureCover(full);
        if (Main.sCoverMode) Main.recolorClock();
        long tc = android.os.SystemClock.uptimeMillis();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        int q = 95;
        byte[] jpg;
        do {
            bos.reset();
            full.compress(Bitmap.CompressFormat.JPEG, q, bos);
            jpg = bos.toByteArray();
            q -= 15;
        } while (jpg.length > 700 * 1024 && q > 25);
        // Everything has finished with it: the measurement, the clock recolour and the JPEG.
        full.recycle();
        // By path, not by value, and that is not an optimisation.
        //
        // Measured on this phone: the largest cover in the library composes to 612KB, the
        // broadcast carrying it fails on the way out with `Binder transaction failure ...
        // error: -28 (No space left on device - Binder buffer full)`, system_server gives up on
        // the send, and Android kills the wallpaper process for a broadcast that could not be
        // delivered. Everything smaller went through, so size is the whole of the difference.
        // The process comes back, restores the previous track's cover from its own disk and
        // never asks for the right one, because as far as it knows it has art - which is the
        // album cover from the track before, on the lock screen, for the whole track.
        //
        // The byte path stays as the fallback: a build whose SELinux refuses the write still
        // has to work, and that is what it worked with before.
        String shared = writeSharedArt(jpg);
        if (shared != null) out.putExtra("file", shared);
        else out.putExtra("jpg", jpg);
        out.putExtra("tsent", android.os.SystemClock.uptimeMillis());
        ctx.sendBroadcast(out);
        // After the send: the shade's background is not what anyone is waiting on.
        ShadeLayer.setArt(art);
        Xp.log(Main.TAG + "pushart " + w + "x" + h + " bias=" + Main.sBias
                + " as " + jpg.length + "B jpeg, draw " + (tc - t0) + "ms encode "
                + (android.os.SystemClock.uptimeMillis() - tc) + "ms");
    }

    /**
     * The cover, for a lock screen whose wallpaper is a video (or any other live one).
     *
     * That case looked unreachable - MIUI builds a KeyguardVideoDepthEngineImpl instead of the
     * image engine, so the GL texture this module replaces never exists - but the video does
     * not live in the wallpaper process either. Traced on device: the engine decodes into two
     * SurfaceTextures that SYSTEMUI supplies, drawn by two full-screen TextureViews that
     * com.miui.keyguard.VideoDepthSurfaceHolder puts in keyguard_background_layer (behind the
     * clock) and keyguard_foreground_layer (the cut-out subject, in front of it).
     *
     * So on this path the wallpaper is already inside our own view tree, and the cover is just
     * a view above the background one. The rule that sent this module into the wallpaper
     * process in the first place - that the clock's liquid glass and the card blur sample the
     * wallpaper WINDOW, so an in-SystemUI cover is never picked up by them - does not hold
     * here, because MIUI is not using that window either. Confirmed on device: the glass
     * refracts a video wallpaper normally.
     *
     * Cheaper than the image path, too: no JPEG round trip and no cross-process broadcast, so
     * the composed bitmap goes straight onto the view.
     */
    private static void showVideoCover(Context ctx, boolean on, Bitmap art) {
        if (!on) {
            Main.detachCover();
            Main.setDepthHidden(false);
            Xp.log(Main.TAG + "video cover off");
            return;
        }
        if (art == null) {
            Xp.log(Main.TAG + "video cover: no album art");
            return;
        }
        long t0 = android.os.SystemClock.uptimeMillis();
        // Composed here, on the worker, exactly as for the image path - same mirror-extend,
        // blur and bias, so the two paths produce the same picture.
        final Bitmap full = composeWallpaper(art, Main.sScreenW, Main.sScreenH, Main.sBias);
        Main.measureCover(full);
        if (Main.sCoverMode) Main.recolorClock();
        final long draw = android.os.SystemClock.uptimeMillis() - t0;
        Main.main().post(new Runnable() {
            @Override
            public void run() {
                try {
                    ViewGroup layer = coverLayer();
                    if (layer == null) {
                        Xp.log(Main.TAG + "keyguard_background_layer not found for the video cover");
                        full.recycle();
                        return;
                    }
                    ImageView iv = Main.sCover;
                    if (iv == null || iv.getParent() != layer) {
                        Main.detachCover();
                        iv = new ImageView(ctx);
                        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        // Added last, so it draws over the video's TextureView. The layer is
                        // ordered, and a TextureView draws in the view hierarchy like any other
                        // view - unlike a SurfaceView, which would punch through whatever we
                        // put above it.
                        layer.addView(iv, new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                        Main.sCover = iv;
                    }
                    iv.setImageBitmap(full);
                    if (Main.sCoverCardStyle.mode == CoverCardStyle.CARD) {
                        CoverCardLayer.attach(layer);
                        CoverCardLayer.style(Main.sCoverCardStyle);
                    }
                    Bitmap old = Main.sCoverBitmap;
                    Main.sCoverBitmap = full;
                    if (old != null && old != full) old.recycle();
                    // The video's own cut-out subject is a second TextureView in the FOREGROUND
                    // layer, i.e. in front of the clock. Left alone it floats over the cover
                    // exactly the way deducted_image_view did on the image path.
                    Main.setDepthHidden(true);
                    guardVideoCover(iv);
                    Xp.log(Main.TAG + "video cover shown " + Main.sScreenW + "x" + Main.sScreenH
                            + " bias=" + Main.sBias + ", draw " + draw + "ms");
                } catch (Throwable t) {
                    Xp.log(Main.TAG + "video cover failed: " + Log.getStackTraceString(t));
                }
            }
        });
    }

    /**
     * Keeps the live-wallpaper cover to the lock screen, and only the lock screen.
     *
     * keyguard_background_layer is not the keyguard's alone: MIUI draws it as the backdrop of
     * the Control Center too, the same way the media card view is shared between the lock
     * screen and the shade. So a full-screen ImageView parked in it turns up behind the
     * Control Center - reported from the device as the cover appearing there while music
     * played, and sharp rather than blurred, which is what says it is a VIEW being drawn and
     * not something sampling the wallpaper.
     *
     * The wallpaper's own TextureViews go the other way: they are hidden because our cover is
     * over them, and that is only true on the lock screen, so off it they are left exactly as
     * MIUI has them - see setVideoSurfacesHidden().
     *
     * A pre-draw listener rather than a one-off, for the reason the depth guard has one: this
     * has to be true on every frame something else draws that layer, and nothing tells us when
     * that is. Every write is conditional and visibility-only, so a frame that needs no change
     * costs a comparison.
     */
    private static void guardVideoCover(final View cover) {
        if (sCoverGuarded == cover && sCoverGuard != null) return;
        releaseCoverGuard();
        sCoverGuard = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                boolean onKeyguard = Main.sCoverMode && Main.onKeyguardNow();
                int want = onKeyguard ? View.VISIBLE : View.INVISIBLE;
                if (cover.getVisibility() != want) cover.setVisibility(want);
                // Our own view is the only thing to write off the lock screen. The wallpaper's
                // TextureViews belong to MIUI there, and handing them back inside a layer the
                // shade is drawing is what put a stray frame of the wallpaper into the first
                // pull-down - setVideoSurfacesHidden() has the measurement.
                if (!onKeyguard) return true;
                View bg = Main.sVideoBg, fg = Main.sVideoFg;
                if (bg != null && bg.getVisibility() != View.INVISIBLE) {
                    bg.setVisibility(View.INVISIBLE);
                }
                if (fg != null && fg.getVisibility() != View.INVISIBLE) {
                    fg.setVisibility(View.INVISIBLE);
                }
                return true;
            }
        };
        cover.getViewTreeObserver().addOnPreDrawListener(sCoverGuard);
        sCoverGuarded = cover;
        Xp.log(Main.TAG + "video cover guard installed");
    }

    /**
     * The live wallpaper's own TextureViews: hidden while the cover is over them, given back
     * when it goes.
     *
     * Giving them back only counts while the lock screen is actually in front.
     * keyguard_background_layer is not the keyguard's alone - the shade draws it as its own
     * backdrop over the desktop, which is how the cover could turn up behind the Control
     * Centre - so writing VISIBLE there off the lock screen leaves a surface on screen that
     * nobody is feeding. Measured on the device: exactly one bad frame, because MIUI sets it
     * straight back the moment that layer is drawn, which is why only the FIRST pull-down
     * after unlocking showed a stray wallpaper frame and the second was clean.
     *
     * So a hand-back we cannot make now is remembered instead, and paid the next time the
     * keyguard is in front (screen on, or a keyguard rebuild). That debt is the safety net the
     * old unconditional restore was: what must never happen is the wallpaper hidden with no
     * cover over it, which is a black lock screen.
     */
    static void setVideoSurfacesHidden(boolean hide, View bg, View fg) {
        if (!hide && !Main.onKeyguardNow()) {
            if (Main.sVideoWallpaper && !Main.sVideoWpOwed) {
                Main.sVideoWpOwed = true;
                Xp.log(Main.TAG + "off the lock screen: leaving the live wallpaper as MIUI left it,"
                        + " hand-back deferred");
            }
            return;
        }
        int vis = hide ? View.INVISIBLE : View.VISIBLE;
        if (bg != null) bg.setVisibility(vis);
        if (fg != null) fg.setVisibility(vis);
        Main.sVideoWpOwed = false;
    }

    static void releaseCoverGuard() {
        View c = sCoverGuarded;
        ViewTreeObserver.OnPreDrawListener g = sCoverGuard;
        sCoverGuarded = null;
        sCoverGuard = null;
        if (c == null || g == null) return;
        try {
            c.getViewTreeObserver().removeOnPreDrawListener(g);
        } catch (Throwable ignored) {
        }
    }

    private static View sCoverGuarded;
    private static ViewTreeObserver.OnPreDrawListener sCoverGuard;

    /** keyguard_background_layer, which sits behind the whole clock stack. */
    static ViewGroup coverLayer() {
        View v = Main.sContainer;
        if (v == null) return null;
        int id = v.getResources().getIdentifier(
                "keyguard_background_layer", "id", "com.android.systemui");
        View layer = id == 0 ? null : v.getRootView().findViewById(id);
        return layer instanceof ViewGroup ? (ViewGroup) layer : null;
    }

    /**
     * The TextureView a live wallpaper is drawn into, in one of the keyguard's layers. It
     * carries no id - it is added in code by VideoDepthSurfaceHolder - so it is found by type,
     * and there is only ever one per layer.
     */
    static View videoSurfaceView(String layerId) {
        View v = Main.sContainer;
        if (v == null) return null;
        int id = v.getResources().getIdentifier(layerId, "id", "com.android.systemui");
        View layer = id == 0 ? null : v.getRootView().findViewById(id);
        if (!(layer instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) layer;
        for (int i = 0; i < g.getChildCount(); i++) {
            if (g.getChildAt(i) instanceof android.view.TextureView) return g.getChildAt(i);
        }
        return null;
    }

    /** One switch to the wallpaper process, for callers outside this file. */
    static void sendToWallpaper(String op, boolean on) {
        sendToWallpaper(op, on, 0L);
    }

    /**
     * The same, with the time the switch was decided at.
     *
     * For an answer that can also travel on a cover push: the push is built on the worker and
     * this is sent from the main thread, so the two can cross, and the one that arrives last is
     * not the one that was decided last. 0 = no time, for a switch that is not one of those.
     */
    static void sendToWallpaper(String op, boolean on, long decidedAt) {
        Context c = Main.sAppCtx;
        if (c == null) return;
        Intent out = wallpaperIntent(op);
        out.putExtra("on", on);
        if (decidedAt > 0L) out.putExtra("blurseq", decidedAt);
        c.sendBroadcast(out);
    }

    static Intent wallpaperIntent(String op) {
        Intent out = new Intent("com.os4.musiccover.WPROBE");
        out.setPackage("com.miui.miwallpaper");
        out.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        out.putExtra("op", op);
        out.putExtra("reload", true);
        // Which kind of lock wallpaper this push is for. The wallpaper process cannot work it
        // out for itself - it only knows which engines it has BUILT, and those are built once
        // per process - and this side re-reads it before every push anyway. See videoPath().
        out.putExtra("video", Main.sVideoWallpaper);
        return out;
    }

    /**
     * Hands the wallpaper process the crossfade that belongs to the current response.
     *
     * It has to be told. Its own copy is a static that starts at 370 and is never persisted,
     * so a wallpaper process that restarts would fade on the number that goes with 0.38 while
     * the clock, the card and everything else moved on the slider's - which is the desync the
     * two of them were moved together to remove, arriving silently instead.
     *
     * Called when the setting changes, and again on every cover entry so a restart is caught
     * before it can be seen.
     */
    static void pushFadeMs(Context ctx) {
        if (ctx == null) return;
        ctx.sendBroadcast(wallpaperIntent("fadems")
                .putExtra("v", (int) Main.fadeMsFor(Main.sClockResponse)));
        Xp.log(Main.TAG + "wallpaper fade = " + Main.fadeMsFor(Main.sClockResponse) + "ms for response "
                + Main.sClockResponse);
    }

    /** Asks the wallpaper process to re-upload the art it already has on disk. */
    static void requestWallpaperReload(Context ctx) {
        ctx.sendBroadcast(wallpaperIntent("reload"));
        Xp.log(Main.TAG + "wallpaper reload requested");
    }

    /**
     * Composes and pushes off the main thread.
     *
     * Getting the RIGHT art is the fiddly part of a track change, not getting art at all.
     * Players fill the session bitmap in asynchronously, so at the instant the metadata arrives
     * there is often nothing there - and the media card thumbnail we fall back to is still the
     * PREVIOUS track (verified on device: the wallpaper came up as the last album while the card
     * already read the new one). So attempts are spaced out, the session is preferred over the
     * card until the last one, and artwork identical to what is already on the wallpaper is read
     * as "not updated yet" rather than accepted.
     *
     * That budget only means anything for a player that HAS a bitmap to fill in. One that
     * publishes only an artwork URI never will, and for it the session is not a source at all -
     * Bilibili hands over an i1.hdslb.com URL and no bitmap, on 2309 of the 2690 session reads
     * in a day's log - so every try spent on the session is spent knowing it comes back empty.
     * Measured before this was understood: 143 video switches waited an average of 1.6s, and 128
     * of them burned all fourteen tries, with the wallpaper sitting on the previous video the
     * whole time, before the card thumbnail was ever consulted once.
     */
    private static final int ART_TRIES = 14;
    /**
     * Checking costs a metadata read, so poll rather than wait in lumps: the player usually
     * fills the session bitmap in within a few hundred ms, and 700ms steps meant a track change
     * that missed by 50ms still cost the full 700. Fourteen tries covers the same ~1.6s window.
     */
    private static final long ART_RETRY_MS = 120L;
    /** When a skip was last asked for, and which way. See the TransportControls hook. */
    private static volatile long sSkipAt;
    private static volatile int sSkipDir;

    /**
     * Someone asked the player to change track. Runs on whatever thread made the call, so it does
     * nothing but write the two fields down.
     */
    static void noteSkip(int dir) {
        sSkipAt = android.os.SystemClock.uptimeMillis();
        sSkipDir = dir;
        if (!Main.sCoverMode || !Main.screenOn()) return;
        // What the queue says is coming. Null for a player that publishes no queue, or a track
        // whose artwork has not been fetched yet - and then this does nothing and the cover waits
        // for the player exactly as it used to.
        final Bitmap art = Prefetch.take(dir);
        if (art == null) return;
        final Context ctx = Main.sAppCtx;
        if (ctx == null) return;
        // Superseding anything in flight, the way a real track change does: this IS the track
        // change, ~0.8s before the player will admit to it.
        final int gen = ++Main.sPushGen;
        Main.sCtTrack = sSkipAt;
        Main.sCtArt = sSkipAt;
        Main.sCtTries = 0;
        Main.sCtCheckMs = 0L;
        Main.worker().post(new Runnable() {
            @Override
            public void run() {
                if (gen != Main.sPushGen) return;
                pushArtToWallpaper(ctx, true, art);
            }
        });
    }

    /** What the wallpaper currently shows, coarsely, so a stale source can be recognised. */
    private static volatile int sArtPrint;
    /**
     * The size of that push, and the track it belonged to, so a WORSE source for the same track
     * can be refused.
     *
     * The fingerprint above cannot see a downgrade: the same cover at another size is a
     * different 8x8 hash, so it reads as a new picture. Measured on bilibili, which publishes an
     * 800x480 copy in its session while the video is in the foreground and later drops it, by
     * which point the media card's thumbnail holds a 144x86 one - the same cover at a fifth of
     * the width, composed into the same 1200x2608 wallpaper as an 8x upscale instead of a 1.5x
     * one. That is a visible loss of sharpness, and it arrives as a "new track", so without this
     * the cover gets worse while the track stays the same.
     */
    private static volatile int sArtW;
    private static volatile int sArtH;
    private static volatile int sArtLong;
    private static volatile String sArtKey = "";
    /**
     * Where a composed cover is handed to the wallpaper process. See writeSharedArt().
     *
     * MIUI's own wallpaper tree: mode 0777 and owned by the wallpaper app, and measured from
     * inside both processes - SystemUI writes and the wallpaper process reads, under Enforcing
     * SELinux and with the file made world-readable.
     */
    static final String SHARE_DIR = "/data/system/theme_magic/users/0/wallpaper";
    static final String SHARE_FILE = "mc_art_shared.jpg";

    /**
     * Puts a composed cover where the wallpaper process can read it, and answers the path - or
     * null when this process cannot write there, which sends the caller back to the broadcast.
     *
     * Readable by everyone on purpose: the two processes do not share a uid.
     */
    private static String writeSharedArt(byte[] jpg) {
        try {
            java.io.File f = new java.io.File(SHARE_DIR, SHARE_FILE);
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            out.write(jpg);
            out.close();
            f.setReadable(true, false);
            return f.getAbsolutePath();
        } catch (Throwable t) {
            Xp.log(Main.TAG + "shared art write failed, carrying it in the broadcast: " + t);
            return null;
        }
    }

    static final String SHARE_SOURCE = "mc_src.raw";

    /** The source, where the wallpaper process can read it. See CoverCompose.writeSource(). */
    private static String writeSharedSource(Bitmap src, int w, int h, float bias)
            throws java.io.IOException {
        java.io.File f = new java.io.File(SHARE_DIR, SHARE_SOURCE);
        CoverCompose.writeSource(f, src, w, h, bias);
        return f.getAbsolutePath();
    }

    static void pushArtAsync(final boolean on, final boolean fresh) {
        final Context ctx = Main.sAppCtx;
        if (ctx == null) return;
        final int gen = ++Main.sPushGen;
        if (!on) {
            sArtPrint = 0;
            sArtW = 0;
            sArtH = 0;
            sArtLong = 0;
            sArtKey = "";
            Main.worker().post(new Runnable() {
                @Override
                public void run() { pushArtToWallpaper(ctx, false, null); }
            });
            return;
        }
        // Checked before every push rather than once per process. The user can send the
        // wallpaper back to "follow home" at any moment - from Settings, a theme, the wallpaper
        // carousel - and that silently removes the keyguard renderer this whole thing hangs off.
        // A cached "already fine" would mean the module never noticed and the user had to go
        // press a repair button, which is exactly what should not be necessary.
        Main.worker().post(new Runnable() {
            @Override
            public void run() {
                // Timed because it is the first thing on this worker and the artwork read is
                // queued behind it: whatever it costs, the track change pays before it starts.
                long t = android.os.SystemClock.uptimeMillis();
                ensureLockWallpaper(ctx);
                Main.sCtCheckMs = android.os.SystemClock.uptimeMillis() - t;
            }
        });
        // Not a track change (a bias tweak, a manual pushart): take whatever is there now.
        tryPushArt(ctx, fresh ? 0 : ART_TRIES - 1, fresh, gen);
    }

    private static void tryPushArt(final Context ctx, final int attempt, final boolean fresh,
                                   final int gen) {
        tryPushArt(ctx, attempt, fresh, gen, false);
    }

    /**
     * allowCard travels with the attempt because the first one is where the session gets judged.
     * When it answers with no bitmap there is nothing left to wait for on that side, and the
     * card thumbnail - which until then was read on the very last try only - can answer from the
     * next one instead. The try budget is untouched, so the worst case is exactly what it was;
     * what changes is that the common case stops spending it. The session is still asked first
     * on every attempt, so a player that does fill its bitmap in late is still picked up.
     */
    private static void tryPushArt(final Context ctx, final int attempt, final boolean fresh,
                                   final int gen, final boolean allowCard) {
        Main.worker().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (gen != Main.sPushGen) {
                    Xp.log(Main.TAG + "art push superseded, dropping it");
                    return;
                }
                boolean last = attempt >= ART_TRIES - 1;
                int[] sessionBits = new int[1];
                Bitmap art = Main.albumArt(ctx, last || allowCard, sessionBits);
                int print = art == null ? 0 : artPrint(art);
                boolean stale = fresh && art != null && sArtPrint != 0 && print == sArtPrint;
                // The same track, and the copy being offered is smaller than the one already on
                // the wallpaper. Only on a fresh push: a non-fresh one is an explicit "hand it
                // over again" - after the wallpaper process restarted it may have nothing at all
                // - and refusing there would leave the lock screen with no cover rather than a
                // soft one. "The same track" is sameTrack()'s question, and it has to be asked as
                // loosely as that: the keys one track arrives under disagree about everything but
                // the package and the title.
                boolean worse = fresh && art != null && sArtPrint != 0 && !stale
                        && sArtLong > 0 && Main.sameTrack(sArtKey, Main.sTrackKey)
                        && Math.max(art.getWidth(), art.getHeight()) < sArtLong;
                if ((art == null || stale || worse) && !last) {
                    // 0 is "a session was there and carried no bitmap", which more tries will not
                    // change. -1 is "there was nothing to ask", which more tries might. Once the
                    // card is in it stays in, so this is worth looking at on any attempt - the
                    // session can turn up late - and declaring it once keeps the line off the log.
                    boolean bare = sessionBits[0] == 0 && !allowCard;
                    if (bare) {
                        Xp.log(Main.TAG + "session carries no bitmap at all, reading the card from here");
                    }
                    Xp.log(Main.TAG + "art " + (art == null ? "not ready"
                                    : stale ? "still the old one" : "smaller than the one up")
                            + ", retrying (" + (attempt + 2) + "/" + ART_TRIES + ")");
                    tryPushArt(ctx, attempt + 1, fresh, gen, allowCard || bare);
                    return;
                }
                if (stale) {
                    // The next track off the same album really does have the same cover.
                    Xp.log(Main.TAG + "same artwork as the last track, wallpaper left alone");
                    return;
                }
                if (worse) {
                    // Waiting the tries out was the point: the session's own copy is often a
                    // moment behind the card's. It did not turn up, and the wallpaper keeps the
                    // bigger copy it already has - which is this same track's cover.
                    Xp.log(Main.TAG + "art " + art.getWidth() + "x" + art.getHeight()
                            + " is smaller than the " + sArtW + "x" + sArtH
                            + " already up for this track, wallpaper left alone");
                    return;
                }
                // Only when something is really going out. A push with no art leaves the
                // wallpaper showing what it already showed, and recording 0 here would claim it
                // was empty and disarm the stale-art check on the next track change.
                if (art != null) {
                    sArtPrint = print;
                    sArtW = art.getWidth();
                    sArtH = art.getHeight();
                    sArtLong = Math.max(sArtW, sArtH);
                    sArtKey = Main.sTrackKey;
                }
                Main.sCtTries = attempt + 1;
                Main.sCtArt = android.os.SystemClock.uptimeMillis();
                pushArtToWallpaper(ctx, true, art);
            }
        }, attempt == 0 ? 0L : ART_RETRY_MS);
    }

    /**
     * The whole cover mechanism rests on the lock screen having a wallpaper of its OWN. MIUI
     * only builds a KeyguardImageEngineImpl - and with it the keyguard renderer whose texture we
     * replace - when the lock screen wallpaper is separate from the home one. Share one between
     * them and the wallpaper process creates a single engine with isLockScreen=false, so there
     * is nothing to hook and the cover silently does nothing at all.
     *
     * This is not hypothetical: the lock wallpaper got reset to "same as home" (dumpsys wallpaper
     * showed an empty "Lock wallpaper state" and mWhich=3), and the module went on reporting
     * cover mode on, art pushed, reload requested - with nowhere to draw it.
     *
     * Copying the current home wallpaper into the lock slot satisfies the requirement without
     * changing what the user sees: the lock screen keeps the same picture, it just owns a copy.
     */
    static boolean ensureLockWallpaper(Context ctx) {
        return ensureLockWallpaper(ctx, false);
    }

    /** When the check below last ran, and what it answered. See ensureLockWallpaper(). */
    private static volatile long sWpCheckedAt;
    private static volatile boolean sWpCheckedAnswer;

    /**
     * How long that answer stands. The check is the first thing on the worker before every push,
     * and the artwork read is queued behind it, so its cost lands in front of every track change:
     * two wallpaper files opened and their headers decoded, MIUI's records parsed for both slots,
     * and half a dozen binder calls - all to notice something the user changes by hand, at most
     * once in a while.
     *
     * A few seconds is the whole point: pressing next repeatedly is exactly when the delay shows,
     * and it is also exactly when nothing about the wallpaper can have changed. Anything slower
     * than this still gets the full check, and a forced one never consults this at all.
     */
    private static final long WP_CHECK_TTL_MS = 5000L;

    @SuppressLint("MissingPermission")
    static boolean ensureLockWallpaper(Context ctx, boolean force) {
        long now = android.os.SystemClock.uptimeMillis();
        if (!force && sWpCheckedAt != 0L && now - sWpCheckedAt < WP_CHECK_TTL_MS) {
            return sWpCheckedAnswer;
        }
        boolean r = checkLockWallpaper(ctx, force);
        sWpCheckedAt = android.os.SystemClock.uptimeMillis();
        sWpCheckedAnswer = r;
        return r;
    }

    // Runs inside com.android.systemui, which holds SET_WALLPAPER and
    // READ_WALLPAPER_INTERNAL. This APK neither has nor needs them - it is a library
    // for someone else's process, and lint has no way to know that.
    @SuppressLint("MissingPermission")
    private static boolean checkLockWallpaper(Context ctx, boolean force) {
        android.app.WallpaperManager wm = (android.app.WallpaperManager)
                ctx.getSystemService(Context.WALLPAPER_SERVICE);
        if (wm == null) return false;
        wallpaperDiag(ctx, wm, force ? "before forced copy" : "before push");
        // Before anything else, and before `force` too - forcing is the same setBitmap and
        // does the same damage. See wallpaperKind(): a live wallpaper on either slot cannot
        // survive being replaced with a still, and this is the only place that could do it.
        String lockKind = wallpaperKind(ctx, "lock");
        boolean live = lockKind != null && !Main.KIND_IMAGE.equals(lockKind);
        Main.sVideoWallpaper = live;
        if (live) {
            // Nothing to repair, and nothing that may be written: setBitmap(FLAG_LOCK) would
            // unbind the live wallpaper for good. The cover still works - it just goes into
            // our own keyguard layer instead of the wallpaper process. See showVideoCover().
            Xp.log(Main.TAG + "the lock screen has a " + lockKind + " wallpaper of its own; leaving "
                    + "the wallpaper alone and drawing the cover in the keyguard layer");
            return false;
        }
        if (lockKind == null) {
            // Nothing of its own on the lock slot, so it is showing the home wallpaper - and
            // if that one is live, the lock screen IS that live wallpaper. Copying "the home
            // wallpaper" would get a still frame of it at best and the stock picture at worst,
            // and either way the animation is gone.
            String homeKind = wallpaperKind(ctx, "home");
            if (homeKind != null && !Main.KIND_IMAGE.equals(homeKind)) {
                Xp.log(Main.TAG + "the lock screen is following a " + homeKind + " home wallpaper. "
                        + "Leaving it alone rather than replacing it with a still of itself.");
                return false;
            }
        }
        if (!force) {
            try {
                android.os.ParcelFileDescriptor lock =
                        wm.getWallpaperFile(android.app.WallpaperManager.FLAG_LOCK);
                if (lock != null) {
                    try {
                        lock.close();
                    } catch (Throwable ignored) {
                    }
                    // The slot being populated is all the COVER needs. What the transition out
                    // of it needs as well is for the picture in there to be the size of the
                    // screen - see fitLockWallpaperToScreen().
                    fitLockWallpaperToScreen(wm);
                    return true;
                }
            } catch (Throwable t) {
                Xp.log(Main.TAG + "cannot read the lock wallpaper slot: " + t);
                return false;
            }
            Xp.log(Main.TAG + "lock screen has no wallpaper of its own - the keyguard "
                    + "wallpaper engine cannot exist, so the cover has nowhere to go. Giving it a "
                    + "copy of the home wallpaper.");
        }
        try {
            Bitmap home = homeWallpaper(wm);
            if (home == null) {
                Xp.log(Main.TAG + "no home wallpaper bitmap to copy");
                return false;
            }
            // At the screen's own size, deliberately. Whatever goes in this slot becomes the
            // texture the keyguard uploads, and every track change then has to rescale the
            // composed cover to it - on both sides. The home wallpaper here is 1579x3432, which
            // cost ~100ms a track for nothing: the picture is only ever shown on this screen.
            Bitmap fitted = home.getWidth() == Main.sScreenW && home.getHeight() == Main.sScreenH
                    ? home : centerCrop(home, Main.sScreenW, Main.sScreenH);
            wm.setBitmap(fitted, null, true, android.app.WallpaperManager.FLAG_LOCK);
            if (fitted != home) fitted.recycle();
            home.recycle();
            Xp.log(Main.TAG + "lock wallpaper set at " + Main.sScreenW + "x" + Main.sScreenH
                    + "; the keyguard engine will be rebuilt");
            wallpaperDiag(ctx, wm, "after copying home into lock");
            return true;
        } catch (Throwable t) {
            Xp.log(Main.TAG + "could not set a lock wallpaper: " + Log.getStackTraceString(t));
            return false;
        }
    }

    /**
     * The size of the last lock wallpaper this tried to re-fit, packed w<<32|h.
     *
     * Not a plain "done" flag: the user can change the wallpaper at any point and the next one
     * deserves its own attempt. What this stops is fighting the same picture over and over -
     * ensureLockWallpaper() runs before every art push, so a slot that cannot be rewritten would
     * otherwise be rewritten once per track change, forever.
     */
    private static volatile long sLockWpTried;

    /**
     * Puts an over-sized lock wallpaper back to the size of the screen, so leaving cover mode
     * can crossfade instead of cutting.
     *
     * Every frame of that crossfade re-uploads the WHOLE keyguard texture, and the texture is
     * the lock wallpaper at ITS OWN pixel size, not the screen's. Measured in the wallpaper
     * process, same phone, same 240ms fade, the only difference being the wallpaper's own
     * dimensions:
     *
     *   1200x2608 (= the screen)  12.5MB/frame  fine
     *   1579x3432 (1.7x)          21MB/frame    the GL thread falls behind far enough that the
     *                                           compositor picks up a half-built frame - the
     *                                           cover drawn small on black, in a corner
     *
     * So WallpaperProbe.fadeTooExpensive() refuses to fade above 1.5x the screen and swaps in
     * one frame instead. That is the right call at that point, but from the outside it reads as
     * "the transition only works if your wallpaper happens to be the right resolution", which is
     * not something anyone should have to know. The extra pixels buy nothing either way: the
     * picture is only ever drawn on this screen, and every draw throws them away. Re-fitting is
     * the same centre-crop ensureLockWallpaper() already applies to the copy it writes itself,
     * so both paths leave the slot in the same shape.
     *
     * Two things it will not do:
     *
     * - Touch a wallpaper whose aspect ratio is not the screen's. A picture that much wider is
     *   being used for something - the parallax a scrolling wallpaper pans through - and
     *   cropping it would change what the user SEES rather than only its resolution. A hard cut
     *   is the smaller harm.
     * - Take a second run at the same picture. See sLockWpTried.
     *
     * Runs on the art worker, off the main thread, and only from the branch where the lock slot
     * already holds a still of its own - live wallpapers returned above, long before this.
     */
    // Runs inside com.android.systemui, which holds SET_WALLPAPER and
    // READ_WALLPAPER_INTERNAL. This APK neither has nor needs them.
    @SuppressLint("MissingPermission")
    private static void fitLockWallpaperToScreen(android.app.WallpaperManager wm) {
        // Not while the wallpaper process sizes its texture to the screen (sTexFit, on by
        // default): that solves the same problem - a 2121x4712 wallpaper making every swap upload
        // 38MB and putting the crossfade over its own threshold - without writing anything. The
        // two must not both run, and if this one does, the user's depth cut-out goes with it:
        // MIUI segments the wallpaper the picker set, and nothing re-analyses a file we wrote.
        if (Main.sTexFit) return;
        int sw = Main.sScreenW, sh = Main.sScreenH;
        if (sw <= 0 || sh <= 0) return;
        int w = 0, h = 0;
        try {
            android.os.ParcelFileDescriptor fd =
                    wm.getWallpaperFile(android.app.WallpaperManager.FLAG_LOCK);
            if (fd == null) return;
            java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd);
            try {
                android.graphics.BitmapFactory.Options b =
                        new android.graphics.BitmapFactory.Options();
                b.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeStream(in, null, b);
                w = b.outWidth;
                h = b.outHeight;
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Xp.log(Main.TAG + "cannot measure the lock wallpaper: " + t);
            return;
        }
        if (w <= 0 || h <= 0) return;
        // The same gate the fade uses, and deliberately the same number: anything this leaves
        // alone is something WallpaperProbe will still agree to fade.
        if ((long) w * h * 2 <= (long) sw * sh * 3) return;
        float times = (float) (w * (double) h / (sw * (double) sh));
        // Aspect, to 1%. 1579x3432 and 1200x2608 are one picture at two resolutions; a panorama
        // is not, and centre-cropping one is a change nobody asked for.
        if (Math.abs((long) w * sh - (long) h * sw) * 100L > (long) h * sw) {
            Xp.log(Main.TAG + "lock wallpaper is " + w + "x" + h + ", " + Main.r2(times)
                    + "x the screen but not its shape - leaving it alone;"
                    + " the cover will swap in one frame rather than fade");
            return;
        }
        long size = ((long) w << 32) | (h & 0xffffffffL);
        if (sLockWpTried == size) return;
        sLockWpTried = size;
        Bitmap src = null, fitted = null;
        try {
            android.os.ParcelFileDescriptor fd =
                    wm.getWallpaperFile(android.app.WallpaperManager.FLAG_LOCK);
            if (fd == null) return;
            java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd);
            try {
                android.graphics.BitmapFactory.Options o =
                        new android.graphics.BitmapFactory.Options();
                // Decoded straight down where a power of two allows it: the full 21MB only ever
                // existed to be thrown away, and this is a phone.
                o.inSampleSize = sampleSizeFor(w, h, sw, sh);
                o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                src = android.graphics.BitmapFactory.decodeStream(in, null, o);
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            if (src == null) {
                Xp.log(Main.TAG + "lock wallpaper could not be decoded for re-fitting");
                return;
            }
            fitted = src.getWidth() == sw && src.getHeight() == sh ? src : fitKeepingColour(src, sw, sh);
            wm.setBitmap(fitted, null, true, android.app.WallpaperManager.FLAG_LOCK);
            Xp.log(Main.TAG + "lock wallpaper was " + w + "x" + h + " (" + Main.r2(times)
                    + "x the screen) - re-fitted to " + sw + "x" + sh
                    + " so leaving cover mode can crossfade instead of cutting");
        } catch (Throwable t) {
            Xp.log(Main.TAG + "could not re-fit the lock wallpaper: " + Log.getStackTraceString(t));
        } finally {
            if (fitted != null && fitted != src) fitted.recycle();
            if (src != null) src.recycle();
        }
    }

    /**
     * centerCrop() with the source's colour space carried through.
     *
     * The shared one draws into `Bitmap.createBitmap(w, h, ARGB_8888)`, and that bitmap is
     * sRGB. Everywhere else in this module that is exactly right - what goes in is album art
     * that was composed here. Here it is the user's own wallpaper, which on this phone can be
     * a Display P3 photo, and drawing P3 into an sRGB bitmap gamut-clips it: saturated reds and
     * greens come back duller, permanently, in a file the user did not ask to have rewritten.
     * The OEM cares too - its upload path calls BitmapUtils.checkColorSpace() on every bitmap.
     *
     * Everything else about the crop is deliberately identical to centerCrop(), because
     * identical is the point: read off the dex, the OEM maps this texture to the screen with
     * AnimImageGLProgram.changeMvpMatrixCrop(), which is setIdentityM() plus a single scale on
     * whichever axis overflows - a centred scale-to-cover with no translation and no crop hint.
     * So the pixels this bakes into the file are the pixels that were already on screen.
     */
    private static Bitmap fitKeepingColour(Bitmap src, int w, int h) {
        Bitmap out = null;
        try {
            android.graphics.ColorSpace cs = src.getColorSpace();
            if (cs != null && !cs.equals(android.graphics.ColorSpace.get(
                    android.graphics.ColorSpace.Named.SRGB))) {
                out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888, src.hasAlpha(), cs);
                Xp.log(Main.TAG + "keeping the wallpaper's colour space: " + cs.getName());
            }
        } catch (Throwable t) {
            // A colour space the framework will not hand back to createBitmap. sRGB it is -
            // the same thing that happened before this method existed.
            Xp.log(Main.TAG + "cannot carry the wallpaper's colour space over: " + t);
        }
        if (out == null) return centerCrop(src, w, h);
        android.graphics.Canvas cv = new android.graphics.Canvas(out);
        float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        float dw = src.getWidth() * scale, dh = src.getHeight() * scale;
        cv.drawBitmap(src, null, new android.graphics.RectF(
                        (w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f),
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    /** The largest power of two that still leaves the picture covering the screen. */
    private static int sampleSizeFor(int w, int h, int sw, int sh) {
        int s = 1;
        while (w / (s * 2) >= sw && h / (s * 2) >= sh) s *= 2;
        return s;
    }

    /**
     * What kind of wallpaper is on a slot - "image", "video", "sensor", "super_wallpaper", or
     * null when nothing here can say.
     *
     * This exists because getWallpaperFile(FLAG_LOCK) cannot tell two very different states
     * apart. It returns null when the lock screen follows the home wallpaper, and it returns
     * null when the lock screen has a LIVE wallpaper of its own, because a live wallpaper has
     * no bitmap file anywhere to hand back. ensureLockWallpaper read that null as the first
     * case and wrote a still copy of the home wallpaper into the lock slot - which is a
     * setBitmap(..., FLAG_LOCK), which unbinds the live wallpaper permanently. A user reported
     * exactly that: set a video lock wallpaper, play one track, and from then on the lock
     * screen is a still of the home wallpaper. Nothing here recorded what it replaced, so
     * leaving cover mode could not put it back either.
     *
     * Three sources, most authoritative first, all three read off the device:
     *
     * 1. MIUI's own record at /data/system/theme_magic/users/<u>/wallpaper/data/{lock,home}.xml.
     *    The root element is the answer - `wallpaper_data which="2" type="image"` - and the
     *    rest of the (38KB) tag is colour palettes. The files are 0777, so this needs no root,
     *    let alone the system uid SystemUI happens to have. Seen carrying last_type="video" on
     *    the home slot here, which is where the vocabulary is confirmed from.
     * 2. Settings.Secure `flag_lock_wallpaper_type` - lock only, and there is no home
     *    equivalent: `desktop_wallpaper_type` is unset in all three namespaces.
     * 3. Settings.Secure `constant_template_editor_info`, the lock screen editor's JSON, whose
     *    homeInfo/lockscreenInfo both carry a `resourceType`. The only source that covers home.
     *
     * Deliberately NOT WallpaperManager.getWallpaperInfo(FLAG_LOCK): on MIUI every wallpaper,
     * still or video, is drawn by the same com.miui.miwallpaper ImageWallpaper component, so
     * it answers "live" for all of them and distinguishes nothing.
     *
     * Unknown reads back as null, and null keeps the old behaviour. Being wrong in the shy
     * direction costs the cover on one phone; being wrong the other way costs someone their
     * wallpaper.
     */
    static String wallpaperKind(Context ctx, String slot) {
        boolean lock = "lock".equals(slot);
        String kind = kindFromMiui(lock);
        String from = "MiuiWallpaperManager";
        if (kind == null) {
            kind = kindFromThemeMagic(slot);
            from = "MIUI's " + slot + ".xml";
        }
        if (kind == null && lock) {
            kind = kindFromSettings(ctx, "flag_lock_wallpaper_type");
            from = "flag_lock_wallpaper_type";
        }
        if (kind == null) {
            kind = kindFromEditorInfo(ctx, lock ? "lockscreenInfo" : "homeInfo");
            from = "constant_template_editor_info";
        }
        // On every track change, so only when the answer moves. Which it does: this is
        // checked before each push precisely because the user can change the wallpaper at
        // any moment, and the interesting moment is the one where it just became a video.
        String last = "lock".equals(slot) ? sLockKindLogged : sHomeKindLogged;
        if (!java.util.Objects.equals(last, kind)) {
            Xp.log(Main.TAG + slot + " wallpaper is " + (kind == null ? "unknown" : kind)
                    + (kind == null ? " (neither MIUI's record nor Settings said)"
                                    : " per " + from));
            if ("lock".equals(slot)) sLockKindLogged = kind;
            else sHomeKindLogged = kind;
        }
        return kind;
    }

    private static String sLockKindLogged = "";
    private static String sHomeKindLogged = "";

    /**
     * MIUI's own answer, and the one to prefer: `MiuiWallpaperManager.getMiuiWallpaperType(which)`
     * is what MIUI asks itself, it returns the same vocabulary the files use ("image", "video",
     * "sensor", "super_wallpaper"), and both it and the SystemUI class holding the instance keep
     * their real names through R8 - so unlike everything below it, this should not need a new
     * heuristic per HyperOS version.
     *
     * `which` is 1 for home and 2 for lock, read off MiuiWallpaperManager's own constants rather
     * than assumed - though they do match WallpaperManager's FLAG_SYSTEM/FLAG_LOCK, and the
     * `which=` attribute in MIUI's XML agrees.
     */
    private static String kindFromMiui(boolean lock) {
        Object mgr = Main.sKgWallpaperMgr;
        if (mgr == null) return null;
        try {
            Object wm = Xp.getObjectField(mgr, "mMiuiWallpaperManager");
            if (wm == null) return null;
            int which = miuiWhich(wm.getClass(), lock);
            Object type = Xp.callMethod(wm, "getMiuiWallpaperType", which);
            String s = type == null ? null : type.toString();
            return s == null || s.isEmpty() ? null : s;
        } catch (Throwable t) {
            Xp.log(Main.TAG + "MiuiWallpaperManager could not say what the "
                    + (lock ? "lock" : "home") + " wallpaper is: " + t);
            return null;
        }
    }

    private static int miuiWhich(Class<?> wmCls, boolean lock) {
        String name = lock ? "MI_WALLPAPER_WHICH_LOCK" : "MI_WALLPAPER_WHICH_HOME";
        try {
            java.lang.reflect.Field f = wmCls.getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable ignored) {
            return lock ? 2 : 1;
        }
    }

    private static String kindFromThemeMagic(String slot) {
        java.io.InputStream in = null;
        try {
            // uid / 100000 is the user id - UserHandle.PER_USER_RANGE, and UserHandle's own
            // accessors for it are all @hide.
            java.io.File f = new java.io.File("/data/system/theme_magic/users/"
                    + (android.os.Process.myUid() / 100000)
                    + "/wallpaper/data/" + slot + ".xml");
            if (!f.canRead()) return null;
            in = new java.io.FileInputStream(f);
            org.xmlpull.v1.XmlPullParser p = android.util.Xml.newPullParser();
            p.setInput(in, null);
            for (int e = p.getEventType(); e != org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
                    e = p.next()) {
                if (e != org.xmlpull.v1.XmlPullParser.START_TAG) continue;
                String type = p.getAttributeValue(null, "type");
                if (type != null && !type.isEmpty()) return type;
            }
        } catch (Throwable t) {
            Xp.log(Main.TAG + "cannot read MIUI's " + slot + " wallpaper record: " + t);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static String kindFromSettings(Context ctx, String key) {
        android.content.ContentResolver cr = ctx.getContentResolver();
        String[] got = new String[3];
        try {
            got[0] = android.provider.Settings.Secure.getString(cr, key);
            got[1] = android.provider.Settings.System.getString(cr, key);
            got[2] = android.provider.Settings.Global.getString(cr, key);
        } catch (Throwable ignored) {
        }
        for (String v : got) {
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    /**
     * The lock screen editor's own record, which is the only one of these that covers the home
     * slot: there is no `desktop_wallpaper_type` key - checked on device, all three namespaces.
     *
     * `constant_template_editor_info` is a single Settings.Secure JSON blob written by
     * com.miui.aod, with the same shape under `homeInfo` and `lockscreenInfo`. Its
     * `resourceType` uses the same vocabulary as the XML's `type` - "image", "video".
     */
    private static String kindFromEditorInfo(Context ctx, String section) {
        try {
            String raw = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(), "constant_template_editor_info");
            if (raw == null || raw.isEmpty()) return null;
            org.json.JSONObject info = new org.json.JSONObject(raw)
                    .optJSONObject(section);
            if (info == null) return null;
            org.json.JSONObject wp = info.optJSONObject("wallpaperInfo");
            if (wp == null) return null;
            String type = wp.optString("resourceType", "");
            return type.isEmpty() ? null : type;
        } catch (Throwable t) {
            Xp.log(Main.TAG + "cannot read " + section + " from the editor info: " + t);
            return null;
        }
    }

    /** [diag] The last report, so a push that changes nothing prints nothing. */
    private static String sWallpaperDiag = "";

    /**
     * [diag] Everything the SystemUI side can see about how the two wallpapers are framed, for
     * the "lock screen and desktop do not line up" report: the screen, both slots' kinds and
     * file sizes, and MIUI's own record of each slot minus its colour palettes. The wallpaper
     * process logs the other half - where its renderers put the picture - as [MCWall] [diag].
     */
    // Runs inside com.android.systemui, which holds READ_WALLPAPER_INTERNAL.
    @SuppressLint("MissingPermission")
    private static void wallpaperDiag(Context ctx, android.app.WallpaperManager wm, String why) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("screen=").append(Main.sScreenW).append('x').append(Main.sScreenH);
            try {
                android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                sb.append(" display=").append(dm.widthPixels).append('x')
                        .append(dm.heightPixels).append('@').append(dm.densityDpi);
            } catch (Throwable ignored) {
            }
            sb.append(" desired=").append(wm.getDesiredMinimumWidth()).append('x')
                    .append(wm.getDesiredMinimumHeight());
            for (String slot : new String[] {"home", "lock"}) {
                int flag = "lock".equals(slot) ? android.app.WallpaperManager.FLAG_LOCK
                                               : android.app.WallpaperManager.FLAG_SYSTEM;
                sb.append("\n  ").append(slot).append(": kind=")
                        .append(wallpaperKind(ctx, slot)).append(" file=");
                try {
                    android.os.ParcelFileDescriptor fd = wm.getWallpaperFile(flag);
                    if (fd == null) {
                        sb.append("none");
                    } else {
                        java.io.InputStream in =
                                new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd);
                        try {
                            android.graphics.BitmapFactory.Options b =
                                    new android.graphics.BitmapFactory.Options();
                            b.inJustDecodeBounds = true;
                            android.graphics.BitmapFactory.decodeStream(in, null, b);
                            sb.append(b.outWidth).append('x').append(b.outHeight);
                        } finally {
                            in.close();
                        }
                    }
                } catch (Throwable t) {
                    sb.append("? (").append(t).append(')');
                }
                try {
                    sb.append(" id=").append(wm.getWallpaperId(flag));
                } catch (Throwable ignored) {
                }
                sb.append("\n  ").append(slot).append(" record: ").append(miuiRecord(slot));
            }
            String report = sb.toString();
            if (report.equals(sWallpaperDiag)) return;
            sWallpaperDiag = report;
            Xp.log(Main.TAG + "[diag] wallpaper (" + why + ")\n  " + report);
        } catch (Throwable t) {
            Xp.log(Main.TAG + "[diag] wallpaper report failed: " + t);
        }
    }

    /** [diag] MIUI's slot record as attributes, the ~100 palette/colour ones left out. */
    private static String miuiRecord(String slot) {
        java.io.InputStream in = null;
        try {
            java.io.File f = new java.io.File("/data/system/theme_magic/users/"
                    + (android.os.Process.myUid() / 100000)
                    + "/wallpaper/data/" + slot + ".xml");
            if (!f.canRead()) return "unreadable";
            in = new java.io.FileInputStream(f);
            org.xmlpull.v1.XmlPullParser p = android.util.Xml.newPullParser();
            p.setInput(in, null);
            for (int e = p.getEventType(); e != org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
                    e = p.next()) {
                if (e != org.xmlpull.v1.XmlPullParser.START_TAG) continue;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < p.getAttributeCount(); i++) {
                    String n = p.getAttributeName(i);
                    if (n.startsWith("palette") || n.startsWith("color")
                            || n.startsWith("allColors") || n.startsWith("partPalette")
                            || n.startsWith("partIsDeep") || n.startsWith("isDeep")) {
                        continue;
                    }
                    sb.append(n).append('=').append(p.getAttributeValue(i)).append(' ');
                }
                return sb.toString().trim();
            }
            return "empty";
        } catch (Throwable t) {
            return "? (" + t + ")";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** The home wallpaper, decoded no larger than it needs to be for this screen. */
    // Runs inside com.android.systemui, which holds SET_WALLPAPER and
    // READ_WALLPAPER_INTERNAL. This APK neither has nor needs them - it is a library
    // for someone else's process, and lint has no way to know that.
    @SuppressLint("MissingPermission")
    private static Bitmap homeWallpaper(android.app.WallpaperManager wm) {
        try {
            android.os.ParcelFileDescriptor sys =
                    wm.getWallpaperFile(android.app.WallpaperManager.FLAG_SYSTEM);
            if (sys != null) {
                java.io.InputStream in =
                        new android.os.ParcelFileDescriptor.AutoCloseInputStream(sys);
                try {
                    android.graphics.BitmapFactory.Options o =
                            new android.graphics.BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    return android.graphics.BitmapFactory.decodeStream(in, null, o);
                } finally {
                    try {
                        in.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            Xp.log(Main.TAG + "reading the home wallpaper file failed: " + t);
        }
        // A live or default home wallpaper has no file; render whatever is showing instead.
        try {
            Drawable d = wm.getDrawable();
            if (d instanceof BitmapDrawable) return ((BitmapDrawable) d).getBitmap();
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Whether the cover has somewhere to go.
     *
     * Two conditions, and they are not the same one. The lock screen needs a wallpaper entry
     * of its own - about the ENTRY, not the picture: a copy of the home wallpaper counts, and
     * is exactly what ensureLockWallpaper() installs. And that wallpaper has to be a still
     * image, because a video or sensor one is drawn by an engine this module has nothing
     * hooked in, and ensureLockWallpaper will not (must not) convert it to a still.
     */
    // Runs inside com.android.systemui, which holds SET_WALLPAPER and
    // READ_WALLPAPER_INTERNAL. This APK neither has nor needs them - it is a library
    // for someone else's process, and lint has no way to know that.
    @SuppressLint("MissingPermission")
    static boolean hasLockWallpaper(Context ctx) {
        try {
            String kind = wallpaperKind(ctx, "lock");
            if (kind != null && !Main.KIND_IMAGE.equals(kind)) return false;
            android.app.WallpaperManager wm = (android.app.WallpaperManager)
                    ctx.getSystemService(Context.WALLPAPER_SERVICE);
            android.os.ParcelFileDescriptor f =
                    wm.getWallpaperFile(android.app.WallpaperManager.FLAG_LOCK);
            if (f == null) return false;
            try {
                f.close();
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Puts the lock screen back to following the home wallpaper. Undoes ensureLockWallpaper. */
    // Runs inside com.android.systemui, which holds SET_WALLPAPER and
    // READ_WALLPAPER_INTERNAL. This APK neither has nor needs them - it is a library
    // for someone else's process, and lint has no way to know that.
    @SuppressLint("MissingPermission")
    static void clearLockWallpaper(Context ctx) {
        try {
            android.app.WallpaperManager wm = (android.app.WallpaperManager)
                    ctx.getSystemService(Context.WALLPAPER_SERVICE);
            wm.clear(android.app.WallpaperManager.FLAG_LOCK);
            Xp.log(Main.TAG + "lock wallpaper cleared, back to following the home one");
        } catch (Throwable t) {
            Xp.log(Main.TAG + "clearLockWallpaper failed: " + t);
        }
    }

    /** Coarse identity of an artwork: enough to tell one cover from another, cheap to take. */
    private static int artPrint(Bitmap b) {
        try {
            Bitmap s = Bitmap.createScaledBitmap(b, 8, 8, true);
            int[] px = new int[64];
            s.getPixels(px, 0, 8, 0, 0, 8, 8);
            s.recycle();
            return java.util.Arrays.hashCode(px);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * See CoverCompose.composeWallpaper(); kept here so the call sites read as they did. In card
     * mode the wallpaper is only the blurred backdrop - the square itself is CoverCardLayer.
     */
    private static Bitmap composeWallpaper(Bitmap src, int w, int h, float bias) {
        return Main.sCoverCardStyle.mode == CoverCardStyle.CARD
                ? CoverCompose.cardBackground(src, w, h)
                : CoverCompose.composeWallpaper(src, w, h, bias);
    }

    /** Fills w x h from the source without distorting it, the way CENTER_CROP would. */
    private static Bitmap centerCrop(Bitmap src, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(out);
        float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        float dw = src.getWidth() * scale, dh = src.getHeight() * scale;
        android.graphics.RectF dst = new android.graphics.RectF(
                (w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);
        cv.drawBitmap(src, null, dst, new android.graphics.Paint(
                android.graphics.Paint.FILTER_BITMAP_FLAG));
        return out;
    }
}
