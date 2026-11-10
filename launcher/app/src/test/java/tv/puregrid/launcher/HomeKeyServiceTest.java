package tv.puregrid.launcher;

import org.junit.Test;

import android.view.KeyEvent;

import static org.junit.Assert.assertEquals;

/**
 * Plain JVM tests for the key decision the HomeKeyService makes: which remote button maps to
 * which action, and when the key is passed through to the system. The KeyEvent constants are
 * compile-time inlined, so android.jar is never called. The empty tables are the service's
 * start-up state before the config arrays load.
 */
public class HomeKeyServiceTest {

    private static final int[][] NO_SCANCODES = {{}, {}, {}};
    private static final int[][] NO_KEYCODES = {{}, {}, {}};

    private static final int DOWN = KeyEvent.ACTION_DOWN;
    private static final int UP = KeyEvent.ACTION_UP;

    private static int decide(int keyCode, int action, boolean canceled, long holdMs, boolean ownsHome) {
        return HomeKeyService.decide(keyCode, 0, action, canceled, 0, holdMs, ownsHome,
                NO_SCANCODES, NO_KEYCODES);
    }

    // ---- group matching ---------------------------------------------------------------------------

    @Test
    public void settingsMatchesKeycodeAndScancodeTables() {
        int[][] keycodes = {{701}, {}, {}};
        int[][] scancodes = {{150}, {}, {}};
        assertEquals(HomeKeyService.SETTINGS,
                HomeKeyService.decide(701, 0, UP, false, 0, 0, true, scancodes, keycodes));
        assertEquals(HomeKeyService.SETTINGS,
                HomeKeyService.decide(999, 150, UP, false, 0, 0, true, scancodes, keycodes));
    }

    @Test
    public void settingsMatchesTheStockSettingsKeyWithoutTables() {
        assertEquals(HomeKeyService.SETTINGS,
                decide(KeyEvent.KEYCODE_SETTINGS, UP, false, 0, true));
    }

    @Test
    public void inputsAndVpnMatchTheirTables() {
        int[][] keycodes = {{}, {702}, {703}};
        int[][] scancodes = {{}, {151}, {152}};
        assertEquals(HomeKeyService.INPUTS,
                HomeKeyService.decide(702, 0, UP, false, 0, 0, true, scancodes, keycodes));
        assertEquals(HomeKeyService.VPN,
                HomeKeyService.decide(999, 152, UP, false, 0, 0, true, scancodes, keycodes));
    }

    @Test
    public void settingsGroupIsCheckedBeforeInputsAndVpn() {
        // A code listed in two tables resolves to the first group, as in the service's if-chain.
        int[][] keycodes = {{}, {701}, {701}};
        assertEquals(HomeKeyService.INPUTS,
                HomeKeyService.decide(701, 0, UP, false, 0, 0, true, NO_SCANCODES, keycodes));
    }

    // ---- Home -------------------------------------------------------------------------------------

    @Test
    public void shortHomePressStartsTheLauncherWhenItOwnsHome() {
        assertEquals(HomeKeyService.HOME, decide(KeyEvent.KEYCODE_HOME, UP, false, 100, true));
    }

    @Test
    public void heldHomeOpensTheSystemPickerEvenWithoutOwningHome() {
        // The hold is the door out of a broken launcher: it must work when this launcher is the
        // Home, and the boundary itself is inclusive.
        assertEquals(HomeKeyService.HOME_PICKER, decide(KeyEvent.KEYCODE_HOME, UP, false, 1200, true));
        assertEquals(HomeKeyService.HOME_PICKER, decide(KeyEvent.KEYCODE_HOME, UP, false, 5000, false));
    }

    @Test
    public void homeIsPassedThroughWhenAnotherLauncherOwnsHome() {
        assertEquals(HomeKeyService.PASS, decide(KeyEvent.KEYCODE_HOME, UP, false, 100, false));
    }

    @Test
    public void canceledHomePressIsPassedThrough() {
        assertEquals(HomeKeyService.PASS, decide(KeyEvent.KEYCODE_HOME, UP, true, 100, true));
    }

    @Test
    public void homeDownIsPassedThrough() {
        // The service only acts on a finished press; the DOWN event belongs to the system.
        assertEquals(HomeKeyService.PASS, decide(KeyEvent.KEYCODE_HOME, DOWN, false, 0, true));
    }

    // ---- KEYCODE_ALL_APPS (the Home key on Xiaomi remotes) ----------------------------------------

    @Test
    public void allAppsIsConsumedAsHomeWhenThisLauncherOwnsHome() {
        assertEquals(HomeKeyService.ALL_APPS_HOME, decide(KeyEvent.KEYCODE_ALL_APPS, UP, false, 0, true));
        // Consumed on DOWN too, exactly like the service's unconditional return-true.
        assertEquals(HomeKeyService.ALL_APPS_HOME, decide(KeyEvent.KEYCODE_ALL_APPS, DOWN, false, 0, true));
    }

    @Test
    public void allAppsCleanPressIsPassedThroughWhenAnotherLauncherOwnsHome() {
        assertEquals(HomeKeyService.PASS, decide(KeyEvent.KEYCODE_ALL_APPS, UP, false, 0, false));
        // But a canceled or non-UP event is still consumed, matching the original control flow.
        assertEquals(HomeKeyService.ALL_APPS_HOME, decide(KeyEvent.KEYCODE_ALL_APPS, UP, true, 0, false));
    }

    // ---- everything else --------------------------------------------------------------------------

    @Test
    public void unknownKeysArePassedThrough() {
        assertEquals(HomeKeyService.PASS, decide(KeyEvent.KEYCODE_VOLUME_UP, UP, false, 0, true));
        assertEquals(HomeKeyService.PASS, decide(KeyEvent.KEYCODE_BACK, DOWN, false, 0, false));
    }
}
