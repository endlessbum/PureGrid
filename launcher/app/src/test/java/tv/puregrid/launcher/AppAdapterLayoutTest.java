package tv.puregrid.launcher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Plain JVM tests for the static tile math that sizes the app grid on every TV. The design size
 * is 180x101 dp and the focus zoom is 1.10 (AppAdapter constants); regressions here are invisible
 * without a TV, so the numbers are pinned exactly.
 */
public class AppAdapterLayoutTest {

    private static final float D = 2f;   // the xhdpi density the design values are tuned at

    @Test
    public void degenerateInputsFallBackToDesignWidth() {
        assertEquals(Math.round(AppAdapter.DESIGN_W_DP * D), AppAdapter.tileWidthPx(0, 4, D));
        assertEquals(Math.round(AppAdapter.DESIGN_W_DP * D), AppAdapter.tileWidthPx(-100, 4, D));
        assertEquals(Math.round(AppAdapter.DESIGN_W_DP * D), AppAdapter.tileWidthPx(1920, 0, D));
        assertEquals(Math.round(AppAdapter.DESIGN_W_DP * D), AppAdapter.tileWidthPx(1920, -1, D));
    }

    @Test
    public void wideScreenCapsAtTheDesignWidth() {
        // Enough room per cell: the tile must stay at 180 dp so 4 columns on 1080p look as before.
        assertEquals(Math.round(AppAdapter.DESIGN_W_DP * D), AppAdapter.tileWidthPx(1920, 4, D));
        assertEquals(Math.round(AppAdapter.DESIGN_W_DP * D), AppAdapter.tileWidthPx(4000, 4, D));
        // At density 1 the cap is 180 physical px.
        assertEquals(180, AppAdapter.tileWidthPx(1920, 4, 1f));
    }

    @Test
    public void narrowCellsShrinkButNeverBelow80dp() {
        // cell = 230 px, fit = (230 - 24) / 1.1 = 187: fits under the cap, above the floor.
        assertEquals(187, AppAdapter.tileWidthPx(920, 4, D));
        // cell = 100 px, fit = (100 - 24) / 1.1 = 69: the 80 dp floor wins.
        assertEquals(Math.round(80 * D), AppAdapter.tileWidthPx(400, 4, D));
    }

    @Test
    public void tileHeightKeepsTheDesignAspect() {
        assertEquals(Math.round(180 * AppAdapter.DESIGN_H_DP / AppAdapter.DESIGN_W_DP),
                AppAdapter.tileHeightPx(180));
        // 360 px tile -> 202 px height (360 * 101 / 180 = 202).
        assertEquals(202, AppAdapter.tileHeightPx(360));
    }

    @Test
    public void itemHeightAddsLabelAndPadding() {
        assertEquals(202 + Math.round(45 * D), AppAdapter.itemHeightPx(202, D));
        assertEquals(101 + 45, AppAdapter.itemHeightPx(101, 1f));
    }

    @Test
    public void cornerRadiusIs16dpAtDesignWidthAndScalesBelow() {
        assertEquals(16f * D, AppAdapter.cornerRadius(360, D), 0.001f);
        // Half the design width -> half the radius.
        assertEquals(8f * D, AppAdapter.cornerRadius(180, D), 0.001f);
        // Above the design width it must not grow past 16 dp.
        assertEquals(16f * D, AppAdapter.cornerRadius(720, D), 0.001f);
    }

    @Test
    public void tileWidthNeverExceedsItsCell() {
        // Whatever the inputs, the fitted tile plus the 12 dp gutter must not overflow the cell
        // badly: the fit already subtracts the gutter, so the result is at most the cell itself.
        for (int columns = 3; columns <= 7; columns++) {
            int w = AppAdapter.tileWidthPx(1600, columns, 1.5f);
            assertTrue(w > 0);
            assertTrue(w <= 1600 / columns);
        }
    }
}
