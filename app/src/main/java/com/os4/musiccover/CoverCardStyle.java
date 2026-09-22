package com.os4.musiccover;

/** Values shared by the settings page, preview and the SystemUI-drawn cover. */
final class CoverCardStyle {
    static final int FULL = 0;
    static final int CARD = 1;
    static final float DEFAULT_SIZE_DP = 240f;
    static final float MAX_SIZE_DP = 420f;
    static final float DEFAULT_MARGIN_DP = 16f;
    static final float DEFAULT_OFFSET_DP = 0f;

    final int mode;
    final float sizeDp;
    final float marginDp;
    final float offsetDp;

    CoverCardStyle(int mode, float sizeDp, float marginDp, float offsetDp) {
        this.mode = mode == CARD ? CARD : FULL;
        this.sizeDp = finite(sizeDp, 120f, MAX_SIZE_DP, DEFAULT_SIZE_DP);
        this.marginDp = finite(marginDp, 8f, 48f, DEFAULT_MARGIN_DP);
        this.offsetDp = finite(offsetDp, -120f, 120f, DEFAULT_OFFSET_DP);
    }

    static CoverCardStyle defaults() {
        return new CoverCardStyle(FULL, DEFAULT_SIZE_DP, DEFAULT_MARGIN_DP,
                DEFAULT_OFFSET_DP);
    }

    CoverCardStyle with(String key, float value) {
        if ("size".equals(key)) return new CoverCardStyle(mode, value, marginDp, offsetDp);
        if ("margin".equals(key)) return new CoverCardStyle(mode, sizeDp, value, offsetDp);
        if ("offset".equals(key)) return new CoverCardStyle(mode, sizeDp, marginDp, value);
        if ("mode".equals(key)) return new CoverCardStyle(Math.round(value), sizeDp,
                marginDp, offsetDp);
        return this;
    }

    static float finite(float v, float lo, float hi, float fallback) {
        return Float.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : fallback;
    }

    /** The square reserves its full playing size even while the paused artwork scales inward. */
    Rect place(float width, float height, float density, float clockBottom,
               float mediaTop) {
        if (!(width > 0f && height > 0f && density > 0f)
                || !Float.isFinite(width) || !Float.isFinite(height)
                || !Float.isFinite(density)) return null;
        float gap = marginDp * density;
        float top = Math.max(0f, clockBottom > 0f && Float.isFinite(clockBottom)
                ? clockBottom : height * 0.18f) + gap;
        // A stale card rectangle from a previous lock session can put the art over the OEM
        // media card on wake. Wait for this session's measured boundary instead.
        if (!Float.isFinite(mediaTop) || mediaTop <= height / 3f
                || mediaTop > height) return null;
        float bottom = mediaTop - gap;
        float side = Math.min(sizeDp * density,
                Math.min(width - 2f * gap, bottom - top));
        if (side < 96f * density || !Float.isFinite(side)) return null;
        float center = (top + bottom) * 0.5f + offsetDp * density;
        float y = Math.max(top, Math.min(bottom - side, center - side * 0.5f));
        return new Rect((width - side) * 0.5f, y, side);
    }

    static final class Rect {
        final float x, y, side;
        Rect(float x, float y, float side) {
            this.x = x;
            this.y = y;
            this.side = side;
        }
    }
}
