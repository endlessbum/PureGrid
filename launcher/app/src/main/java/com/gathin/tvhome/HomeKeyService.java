package com.gathin.tvhome;

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
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {

    /** How long the Home button must be held to count as "get me out of here" and open the system
     *  Home picker instead of the launcher. Short enough to find blind, long enough to never fire
     *  on an ordinary press. */
    private static final long HOME_HOLD_MS = 1200;

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

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        for (int code : settingsKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    private boolean isInputsKey(KeyEvent event) {
        for (int code : inputsScancodes) if (event.getScanCode() == code) return true;
        for (int code : inputsKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    private boolean isVpnKey(KeyEvent event) {
        for (int code : vpnScancodes) if (event.getScanCode() == code) return true;
        for (int code : vpnKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (isSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                startActivitySafe(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return true;
        }
        if (isInputsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                // Bring the launcher up with a "show Inputs" extra: the dropdown is drawn by the
                // activity itself once its top bar is laid out, from the foreground or via
                // onNewIntent. A broadcast alone could not raise a background launcher, and a
                // popup from a background activity does not reliably show.
                Intent i = new Intent().setClassName(getPackageName(), "com.gathin.tvhome.MainActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra("open_inputs", true);
                startActivitySafe(i);
            }
            return true;
        }
        if (isVpnKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                Intent i = new Intent("com.gathin.tvhome.ACTION_TOGGLE_VPN")
                        .setPackage(getPackageName());
                sendBroadcast(i);
            }
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME && event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            // A held Home button is the door out when this launcher itself is broken (a crash loop
            // leaves the TV black, and with the stock launcher disabled there is nothing else to
            // fall back to): it opens the system Home picker instead of this launcher.
            if (event.getEventTime() - event.getDownTime() >= HOME_HOLD_MS) {
                startActivitySafe(new Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            }
            if (!ownsHome()) return false;
            Intent i = new Intent().setClassName(getPackageName(), "com.gathin.tvhome.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra("collapse_all", true);
            startActivitySafe(i);
            // Consumed: the system HOME resolution (chooser when several launchers claim Home)
            // would run the same press a second time.
            return true;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_ALL_APPS) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            // Same guard as KEYCODE_HOME: when the Home preference has moved to another launcher
            // (or no default is set and the chooser must appear), the key belongs to the system.
            if (!ownsHome()) return false;
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivitySafe(home);
        }
        return true;
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
