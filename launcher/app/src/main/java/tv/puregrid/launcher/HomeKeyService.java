package tv.puregrid.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sends the remote's Home button to this launcher on remotes that emit KEYCODE_ALL_APPS for Home
 * (Xiaomi sticks and boxes). Android TV 9 routes that key only to the system-image launcher, so once
 * the stock launcher is disabled the button does nothing. Every other key passes through untouched.
 *
 * The interception is conditional: the keys are only taken while this launcher is the one that owns
 * Home, and holding the Home button opens the system Home picker instead — so a launcher that has
 * crashed (the stock one is disabled by the install script) is never the only door out.
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services tv.puregrid.launcher/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {

    /** How long the Home button must be held to count as "get me out of here" and open the system
     *  Home picker instead of the launcher. Short enough to find blind, long enough to never fire
     *  on an ordinary press. */
    private static final long HOME_HOLD_MS = 1200;

    /** Pure key decision, no Android calls: what the service should do with a key event. The
     *  package-visible shape lets the JVM tests drive the exact logic the service runs. */
    static final int PASS = 0;           // not ours: the system handles the key
    static final int SETTINGS = 1;       // consumed; effect on a clean ACTION_UP
    static final int INPUTS = 2;
    static final int VPN = 3;
    static final int HOME = 4;           // short Home press while this launcher owns Home
    static final int HOME_PICKER = 5;    // held Home: the door out of a broken launcher
    static final int ALL_APPS_HOME = 6;  // KEYCODE_ALL_APPS treated as Home

    /** The button decision for one key event, before any side effect. The group tables are indexed
     *  {@code {settings, inputs, vpn}}; a group matches when the event's keycode or scancode is in
     *  its table (settings also matches KEYCODE_SETTINGS itself). SETTINGS/INPUTS/VPN are consumed
     *  on every action — the effect itself fires in onKeyEvent on a clean ACTION_UP. HOME is only
     *  acted on there too: a held press opens the system Home picker, and while another launcher
     *  owns the Home preference (or none is set and the chooser must appear) the key is passed
     *  through untouched, on a clean press as well. ALL_APPS is consumed on every action except a
     *  clean press while another launcher owns Home. */
    static int decide(int keyCode, int scanCode, int action, boolean canceled,
                      long downTime, long eventTime, boolean ownsHome,
                      int[][] scancodes, int[][] keycodes) {
        for (int code : keycodes[0]) if (keyCode == code) return SETTINGS;
        for (int code : scancodes[0]) if (scanCode == code) return SETTINGS;
        if (keyCode == KeyEvent.KEYCODE_SETTINGS) return SETTINGS;
        for (int code : keycodes[1]) if (keyCode == code) return INPUTS;
        for (int code : scancodes[1]) if (scanCode == code) return INPUTS;
        for (int code : keycodes[2]) if (keyCode == code) return VPN;
        for (int code : scancodes[2]) if (scanCode == code) return VPN;
        if (keyCode == KeyEvent.KEYCODE_HOME) {
            if (action != KeyEvent.ACTION_UP || canceled) return PASS;
            if (eventTime - downTime >= HOME_HOLD_MS) return HOME_PICKER;
            return ownsHome ? HOME : PASS;
        }
        if (keyCode != KeyEvent.KEYCODE_ALL_APPS) return PASS;
        if (action == KeyEvent.ACTION_UP && !canceled && !ownsHome) return PASS;
        return ALL_APPS_HOME;
    }

    /** Remote buttons that should open Settings (config.xml, settings_key_scancodes and settings_key_keycodes). */
    private int[] settingsScancodes = new int[0];
    private int[] settingsKeycodes = new int[0];
    /** Remote buttons that should open the Inputs (Вход) dropdown (config.xml). */
    private int[] inputsScancodes = new int[0];
    private int[] inputsKeycodes = new int[0];
    /** Remote buttons that toggle the VPN (config.xml). */
    private int[] vpnScancodes = new int[0];
    private int[] vpnKeycodes = new int[0];

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // Each group degrades on its own: a missing resource must not silence the other keys.
        try {
            settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
            settingsKeycodes = getResources().getIntArray(R.array.settings_key_keycodes);
        } catch (Throwable t) {
            android.util.Log.e("PureGridKeys", "settings key config unreadable", t);
        }
        try {
            inputsScancodes = getResources().getIntArray(R.array.inputs_key_scancodes);
            inputsKeycodes = getResources().getIntArray(R.array.inputs_key_keycodes);
        } catch (Throwable t) {
            android.util.Log.e("PureGridKeys", "inputs key config unreadable", t);
        }
        try {
            vpnScancodes = getResources().getIntArray(R.array.vpn_key_scancodes);
            vpnKeycodes = getResources().getIntArray(R.array.vpn_key_keycodes);
        } catch (Throwable t) {
            android.util.Log.e("PureGridKeys", "vpn key config unreadable", t);
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int what = decide(event.getKeyCode(), event.getScanCode(), event.getAction(), event.isCanceled(),
                event.getDownTime(), event.getEventTime(), ownsHome(),
                new int[][]{settingsScancodes, inputsScancodes, vpnScancodes},
                new int[][]{settingsKeycodes, inputsKeycodes, vpnKeycodes});
        boolean up = event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled();
        switch (what) {
            case SETTINGS:
                if (up) startActivitySafe(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            case INPUTS:
                if (up) {
                    // Bring the launcher up with a "show Inputs" extra: the dropdown is drawn by the
                    // activity itself once its top bar is laid out, from the foreground or via
                    // onNewIntent. A broadcast alone could not raise a background launcher, and a
                    // popup from a background activity does not reliably show.
                    Intent i = new Intent().setClassName(getPackageName(), "tv.puregrid.launcher.MainActivity")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            .putExtra("open_inputs", true);
                    startActivitySafe(i);
                }
                return true;
            case VPN:
                if (up) {
                    Intent i = new Intent("tv.puregrid.launcher.ACTION_TOGGLE_VPN")
                            .setPackage(getPackageName());
                    sendBroadcast(i);
                }
                return true;
            case HOME_PICKER:
                // A held Home button is the door out when this launcher itself is broken (a crash loop
                // leaves the TV black, and with the stock launcher disabled there is nothing else to
                // fall back to): it opens the system Home picker instead of this launcher.
                startActivitySafe(new Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            case HOME:
                Intent i = new Intent().setClassName(getPackageName(), "tv.puregrid.launcher.MainActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra("collapse_all", true);
                startActivitySafe(i);
                // Consumed: the system HOME resolution (chooser when several launchers claim Home)
                // would run the same press a second time.
                return true;
            case ALL_APPS_HOME:
                if (up) {
                    Intent home = new Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_HOME)
                            .setPackage(getPackageName())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    startActivitySafe(home);
                }
                return true;
            default:
                return false;
        }
    }

    /** True while this launcher is the one the system resolves Home to. Consuming Home while some
     *  other launcher owns the preference would steal the key from it — and from the chooser that
     *  appears when no default is set, which is exactly what a user with a dead launcher needs. */
    private boolean ownsHome() {
        try {
            android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            return ri != null && ri.activityInfo != null
                    && getPackageName().equals(ri.activityInfo.packageName);
        } catch (Throwable t) {
            return false;   // when the resolver cannot answer, yielding is the safe side
        }
    }

    /** A background activity launch from a service can be blocked on some firmwares (Android 10+
     *  background start limits, vendor tweaks); a swallowed launch must at least be explainable
     *  from logcat instead of the button just doing "sometimes nothing". */
    private void startActivitySafe(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            android.util.Log.w("PureGridKeys", "startActivity failed: " + i, e);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
