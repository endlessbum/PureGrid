package tv.puregrid.launcher;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the luma threshold that picks the check-mark ink on the accent swatches. */
public class LauncherSettingsActivityTest {

    @Test
    public void blackIsDarkAndWhiteIsLight() {
        assertFalse(LauncherSettingsActivity.isLight(0xFF000000));
        assertTrue(LauncherSettingsActivity.isLight(0xFFFFFFFF));
    }

    @Test
    public void midGrayIsDark() {
        // 0x80 in every channel: luma 128, below the 168 threshold.
        assertFalse(LauncherSettingsActivity.isLight(0xFF808080));
    }

    @Test
    public void lightGrayIsLight() {
        // 0xB0 in every channel: luma 176, above the 168 threshold.
        assertTrue(LauncherSettingsActivity.isLight(0xFFB0B0B0));
    }

    @Test
    public void thresholdIsExclusiveAt168() {
        // Gray at 0xA8 in every channel: luma exactly 168 -> not light; one step up -> light.
        assertFalse(LauncherSettingsActivity.isLight(0xFFA8A8A8));
        assertTrue(LauncherSettingsActivity.isLight(0xFFA9A9A9));
    }

    @Test
    public void greenWeighsMoreThanRedAndBlue() {
        // r=255 alone: 76 -> dark. g=255 alone: 149 -> dark. b=255 alone: 29 -> dark.
        assertFalse(LauncherSettingsActivity.isLight(0xFFFF0000));
        assertFalse(LauncherSettingsActivity.isLight(0xFF00FF00));
        assertFalse(LauncherSettingsActivity.isLight(0xFF0000FF));
        // Two channels at 255 (yellow): 76 + 149 = 225 -> light.
        assertTrue(LauncherSettingsActivity.isLight(0xFFFFFF00));
    }
}
