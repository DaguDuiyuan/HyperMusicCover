package com.os4.musiccover;

import org.junit.Test;

import static org.junit.Assert.*;

public class LyricStyleTest {
    private static float[] band(LyricStyle s, float clock, float card, float textPx) {
        float[] out = new float[2];
        return s.writeBand(clock, card, 1f, textPx, out) ? out : null;
    }

    @Test public void defaultsPreserveTheOriginalBandAndTextWidth() {
        LyricStyle s = LyricStyle.DEFAULT;
        assertEquals(25f, s.sizeSp, 0f);
        assertEquals(600, s.weight);
        assertArrayEquals(new float[]{116f, 484f}, band(s, 100f, 500f, 25f), 0f);
        assertEquals(30f, s.sidePx(360, 1f, 25f), 0f);
    }

    @Test public void windowMovesByTrimmingOnlyTheOppositeEdge() {
        LyricStyle base = LyricStyle.DEFAULT;
        assertArrayEquals(new float[]{116f, 484f}, band(base, 100f, 500f, 25f), 0f);
        assertArrayEquals(new float[]{156f, 484f},
                band(base.with("offset", 20f), 100f, 500f, 25f), 0f);
        assertArrayEquals(new float[]{116f, 444f},
                band(base.with("offset", -20f), 100f, 500f, 25f), 0f);
    }

    @Test public void compactBandsAndNarrowScreensStayInsideTheirBounds() {
        LyricStyle s = LyricStyle.DEFAULT.with("gap", 48f).with("offset", 80f)
                .with("side", 64f).with("size", 36f);
        float[] bounds = band(s, 100f, 200f, 36f);
        assertNotNull(bounds);
        assertTrue(bounds[0] >= 100f);
        assertTrue(bounds[1] <= 200f);
        assertTrue(bounds[1] - bounds[0] >= 2.4f * 36f - 0.01f);
        assertNull(band(s, 100f, 180f, 36f));
        assertEquals(0f, s.sidePx(100, 1f, 36f), 0f);
        assertEquals(48f, s.sidePx(240, 1f, 36f), 0f);
    }

    @Test public void invalidAndOutOfRangeSettingsCannotEscapeTheSupportedRange() {
        LyricStyle s = LyricStyle.DEFAULT;
        assertSame(s, s.with("size", Float.NaN));
        assertSame(s, s.with("offset", Float.POSITIVE_INFINITY));
        assertSame(s, s.with("unknown", 42f));
        assertEquals(36f, s.with("size", 100f).sizeSp, 0f);
        assertEquals(-80f, s.with("offset", -1000f).offsetDp, 0f);
        assertEquals(700, s.with("weight", 9999f).weight);
        assertEquals(500, s.with("weight", 549f).weight);
        assertNull(band(s, Float.NaN, 500f, 25f));
    }

    @Test public void onlyTypographyAndSideMarginRequireLayoutRebuild() {
        LyricStyle s = LyricStyle.DEFAULT;
        assertTrue(s.sameLayout(s.with("offset", 20f).with("gap", 30f)));
        assertFalse(s.sameLayout(s.with("side", 40f)));
        assertFalse(s.sameLayout(s.with("size", 30f)));
        assertFalse(s.sameLayout(s.with("weight", 700f)));
    }
}
