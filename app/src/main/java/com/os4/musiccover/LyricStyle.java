package com.os4.musiccover;

/** User-adjustable values for the module's own lyric view. No OEM classes are involved. */
final class LyricStyle {
    static final LyricStyle DEFAULT = new LyricStyle(0f, 16f, 30f, 25f, 600);

    final float offsetDp;
    final float gapDp;
    final float sideDp;
    final float sizeSp;
    final int weight;

    private LyricStyle(float offsetDp, float gapDp, float sideDp, float sizeSp, int weight) {
        this.offsetDp = offsetDp;
        this.gapDp = gapDp;
        this.sideDp = sideDp;
        this.sizeSp = sizeSp;
        this.weight = weight;
    }

    /** Non-finite input changes nothing; finite input is kept inside the UI's supported range. */
    LyricStyle with(String key, float value) {
        if (!Float.isFinite(value)) return this;
        float offset = offsetDp, gap = gapDp, side = sideDp, size = sizeSp;
        int fontWeight = weight;
        if ("offset".equals(key)) offset = clamp(value, -80f, 80f);
        else if ("gap".equals(key)) gap = clamp(value, 0f, 48f);
        else if ("side".equals(key)) side = clamp(value, 0f, 64f);
        else if ("size".equals(key)) size = clamp(value, 18f, 36f);
        else if ("weight".equals(key)) {
            fontWeight = Math.max(300, Math.min(900, Math.round(value / 100f) * 100));
        } else return this;
        if (offset == offsetDp && gap == gapDp && side == sideDp
                && size == sizeSp && fontWeight == weight) return this;
        return new LyricStyle(offset, gap, side, size, fontWeight);
    }

    boolean sameLayout(LyricStyle other) {
        return other != null && sideDp == other.sideDp && sizeSp == other.sizeSp
                && weight == other.weight;
    }

    /** Safe screen-space clipping band, written into a reusable two-float buffer. */
    boolean writeBand(float clockBottom, float cardTop, float density, float textPx, float[] out) {
        if (!Float.isFinite(clockBottom) || !Float.isFinite(cardTop)
                || !Float.isFinite(density) || !Float.isFinite(textPx)
                || density <= 0f || textPx <= 0f) return false;
        float minimum = 2.4f * textPx;
        float room = cardTop - clockBottom;
        if (room < minimum) return false;
        float gap = Math.min(gapDp * density, (room - minimum) / 2f);
        float top = clockBottom + gap;
        float bottom = cardTop - gap;
        // The old full-height band is retained at zero. Moving its centre trims only the edge
        // opposite the direction of travel, never crossing the clock or the card.
        float shift = Math.min(Math.abs(offsetDp * density), (bottom - top - minimum) / 2f);
        if (offsetDp > 0f) top += 2f * shift;
        else if (offsetDp < 0f) bottom -= 2f * shift;
        out[0] = top;
        out[1] = bottom;
        return true;
    }

    float sidePx(int viewWidth, float density, float textPx) {
        if (viewWidth <= 0 || density <= 0f) return 0f;
        float minTextWidth = Math.min(viewWidth, Math.max(120f * density, 4f * textPx));
        return Math.min(sideDp * density, Math.max(0f, (viewWidth - minTextWidth) / 2f));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
