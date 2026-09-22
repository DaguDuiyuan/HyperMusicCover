package com.os4.musiccover;

import org.junit.Test;

import static org.junit.Assert.*;

public class CoverCardStyleTest {
    @Test public void oldSettingsKeepFullCoverAndDefaults() {
        CoverCardStyle style = CoverCardStyle.defaults();
        assertEquals(CoverCardStyle.FULL, style.mode);
        assertEquals(240f, style.sizeDp, 0f);
        assertEquals(16f, style.marginDp, 0f);
        assertEquals(0f, style.offsetDp, 0f);
    }

    @Test public void invalidValuesAreFiniteAndBounded() {
        CoverCardStyle style = new CoverCardStyle(8, Float.NaN,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY);
        assertEquals(CoverCardStyle.FULL, style.mode);
        assertEquals(240f, style.sizeDp, 0f);
        assertEquals(16f, style.marginDp, 0f);
        assertEquals(0f, style.offsetDp, 0f);
        style = new CoverCardStyle(CoverCardStyle.CARD, 999f, -10f, -999f);
        assertEquals(420f, style.sizeDp, 0f);
        assertEquals(8f, style.marginDp, 0f);
        assertEquals(-120f, style.offsetDp, 0f);
    }

    @Test public void positionAvoidsClockMediaAndEdgesAtBothExtremes() {
        for (float offset : new float[]{-120f, 120f}) {
            CoverCardStyle style = new CoverCardStyle(CoverCardStyle.CARD, 420f, 48f, offset);
            CoverCardStyle.Rect r = style.place(400f, 850f, 1f, 190f, 640f);
            assertNotNull(r);
            assertTrue(r.x >= 48f);
            assertTrue(r.y >= 238f);
            assertTrue(r.y + r.side <= 592f);
            assertTrue(r.x + r.side <= 352f);
        }
    }

    @Test public void crampedBandHidesCard() {
        CoverCardStyle style = new CoverCardStyle(CoverCardStyle.CARD, 240f, 16f, 0f);
        assertNull(style.place(390f, 800f, 1f, 300f, 400f));
        assertNull(style.place(Float.NaN, 800f, 1f, 100f, 600f));
        assertNull(style.place(390f, 800f, 1f, 100f, Float.NaN));
    }
}
