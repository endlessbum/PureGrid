package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The launcher's settings: the values in res/values/config.xml, with an optional overlay read from
 * {@code config.json} in the app's external files directory
 * ({@code /sdcard/Android/data/com.gathin.tvhome/files/}). Every key in the JSON is optional and an
 * unknown or badly typed key is ignored, so a theme can change the dock and the look without a rebuild:
 * {@code tools/theme.sh themes/<name>} pushes a theme folder there and restarts Home.
 *
 * Read once in {@link MainActivity#onCreate}; nothing here touches the network.
 */
final class Config {
    private static final String TAG = "TvHome.Config";
    static final String FILE_NAME = "config.json";
    static final String WALLPAPER_NAME = "wallpaper.jpg";
    static final String SETTINGS_NAME = "launcher_settings";
    private static final String THEME_REVISION_NAME = "theme_revision";
    private static final String THEME_SIGNATURE_KEY = "theme_signature";

    final List<String> dockApps;
    final List<String> hiddenApps;
    final String liveTvPackage;
    final String liveTvAction;
    final String sourcePanelService;
    final String sourcePanelAction;
    final String inputAction;
    final int columns;
    final boolean animations;
    final boolean advancedEffects;
    final int rowsBelowDock;
    final boolean shamsiDate;
    final boolean moduleCards;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): stiff spring, short fades, no replay. */
    final boolean reducedMotion;
    final String vpnApp;
    /**
     * Broadcast action that switches the VPN app on or off in the background, so the network pill
     * can toggle it without opening the app. Empty: the pill opens the VPN app instead.
     */
    final String vpnToggleAction;
    final boolean showHomeSettingsShortcut;
    /** Background colour behind everything, #RRGGBB; black unless a theme says otherwise. */
    final int backgroundColor;
    /** Time-of-day backdrop, 0 to 100 (config.xml, sky_intensity); 0 keeps the plain background. */
    final int skyIntensity;
    /** Full-screen picture behind the launcher, or null for the plain colour. */
    final File wallpaper;
    /** Whether the overlay file was present and parsed. */
    final boolean overlayApplied;
    /** Content search: rows asked from each search-provider app per query (search_provider_limit). */
    final int searchProviderLimit;
    /** Content search: how long one provider may take to answer, ms (search_provider_timeout_ms). */
    final int searchProviderTimeoutMs;

    private Config(List<String> dockApps, List<String> hiddenApps, String liveTvPackage, String liveTvAction,
                   String sourcePanelService, String sourcePanelAction, String inputAction,
                   int columns, int rowsBelowDock, boolean animations, boolean advancedEffects,
                   boolean shamsiDate, boolean moduleCards, boolean reducedMotion, String vpnApp,
                   String vpnToggleAction, boolean showHomeSettingsShortcut, int backgroundColor, int skyIntensity,
                   File wallpaper, boolean overlayApplied, int searchProviderLimit, int searchProviderTimeoutMs) {
        this.dockApps = Collections.unmodifiableList(dockApps);
        this.hiddenApps = Collections.unmodifiableList(hiddenApps);
        this.liveTvPackage = liveTvPackage;
        this.liveTvAction = liveTvAction;
        this.sourcePanelService = sourcePanelService;
        this.sourcePanelAction = sourcePanelAction;
        this.inputAction = inputAction;
        this.columns = columns;
        this.rowsBelowDock = rowsBelowDock;
        this.animations = animations;
        this.advancedEffects = advancedEffects;
        this.shamsiDate = shamsiDate;
        this.moduleCards = moduleCards;
        this.reducedMotion = reducedMotion;
        this.vpnApp = vpnApp;
        this.vpnToggleAction = vpnToggleAction;
        this.showHomeSettingsShortcut = showHomeSettingsShortcut;
        this.backgroundColor = backgroundColor;
        this.skyIntensity = skyIntensity;
        this.wallpaper = wallpaper;
        this.overlayApplied = overlayApplied;
        this.searchProviderLimit = searchProviderLimit;
        this.searchProviderTimeoutMs = searchProviderTimeoutMs;
    }

    /** The folder the theme tools push into; null when external storage is unavailable. */
    static File dir(Context ctx) {
        return ctx.getExternalFilesDir(null);
    }

    static Config load(Context ctx) {
        Resources res = ctx.getResources();
        List<String> dock = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.dock_apps)));
        List<String> hidden = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.hidden_apps)));
        String liveTv = res.getString(R.string.live_tv_package).trim();
        String liveTvAction = res.getString(R.string.live_tv_action).trim();
        String panelService = res.getString(R.string.source_panel_service).trim();
        String panelAction = res.getString(R.string.source_panel_action).trim();
        String inputAction = res.getString(R.string.input_action).trim();
        int columns = res.getInteger(R.integer.columns);
        int rowsBelow = res.getInteger(R.integer.rows_below_dock);
        boolean animations = res.getBoolean(R.bool.animations);
        boolean advancedEffects = res.getBoolean(R.bool.advanced_effects);
        boolean shamsi = res.getBoolean(R.bool.shamsi_date);
        boolean moduleCards = res.getBoolean(R.bool.module_cards);
        boolean reducedMotion = res.getBoolean(R.bool.reduced_motion);
        String vpn = res.getString(R.string.vpn_app).trim();
        String vpnToggle = res.getString(R.string.vpn_toggle_action).trim();
        boolean homeSettings = res.getBoolean(R.bool.show_home_settings_shortcut);
        int background = 0xFF000000;
        int skyIntensity = res.getInteger(R.integer.sky_intensity);
        int searchLimit = res.getInteger(R.integer.search_provider_limit);
        int searchTimeout = res.getInteger(R.integer.search_provider_timeout_ms);
        boolean applied = false;

        File dir = dir(ctx);
        File file = dir != null ? new File(dir, FILE_NAME) : null;
        JSONObject json = read(file);
        if (json != null) {
            applied = true;
            dock = stringList(json, "dock_apps", dock);
            hidden = stringList(json, "hidden_apps", hidden);
            liveTv = string(json, "live_tv_package", liveTv);
            liveTvAction = string(json, "live_tv_action", liveTvAction);
            panelService = string(json, "source_panel_service", panelService);
            panelAction = string(json, "source_panel_action", panelAction);
            inputAction = string(json, "input_action", inputAction);
            columns = integer(json, "columns", columns, 1, 8);
            rowsBelow = integer(json, "rows_below_dock", rowsBelow, 0, 3);
            animations = bool(json, "animations", animations);
            advancedEffects = bool(json, "advanced_effects", advancedEffects);
            shamsi = bool(json, "shamsi_date", shamsi);
            moduleCards = bool(json, "module_cards", moduleCards);
            reducedMotion = bool(json, "reduced_motion", reducedMotion);
            vpn = string(json, "vpn_app", vpn);
            vpnToggle = string(json, "vpn_toggle_action", vpnToggle);
            if (json.has("vpn_toggle") && !bool(json, "vpn_toggle", true)) {
                // A theme can disable the feature: it forces the field empty.
                vpnToggle = "";
            }
            homeSettings = bool(json, "show_home_settings_shortcut", homeSettings);
            background = color(json, "background_color", background);
            skyIntensity = integer(json, "sky_intensity", skyIntensity, 0, 100);
            searchLimit = integer(json, "search_provider_limit", searchLimit, 1, 40);
            searchTimeout = integer(json, "search_provider_timeout_ms", searchTimeout, 300, 15_000);
        }
        // A theme push or reset invalidates choices made on the TV. On first use, remember the
        // current theme without clearing choices from an older launcher version.
        SharedPreferences choices = ctx.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE);
        syncThemeChoices(choices, dir, file);
        // Dock order edited on the TV (left-bar settings) overrides the theme default.
        List<String> parsed = parseChosenDock(choices.getString("dock_apps", null));
        if (parsed != null) dock = parsed;
        int chosenColumns = choices.getInt("columns", columns);
        // The same clamp as a theme's "columns": the picker on the TV offers 4..7, but a stored
        // value outside that must not silently fall back to the theme default while the same
        // number in config.json would have been accepted.
        if (chosenColumns >= 1 && chosenColumns <= 8) columns = chosenColumns;
        animations = choices.getBoolean("animations", animations);
        advancedEffects = choices.getBoolean("advanced_effects", advancedEffects);
        File wallpaper = dir != null ? new File(dir, WALLPAPER_NAME) : null;
        if (wallpaper != null && !(wallpaper.isFile() && wallpaper.length() > 0)) wallpaper = null;
        // A theme can also switch the wallpaper off while the file is still there.
        if (json != null && json.has("wallpaper") && !bool(json, "wallpaper", true)) wallpaper = null;
        // Toggling only makes sense with a VPN app to toggle.
        if (vpn.isEmpty()) vpnToggle = "";

        return new Config(dock, hidden, liveTv, liveTvAction, panelService, panelAction, inputAction,
                Math.max(1, columns), Math.max(0, rowsBelow), animations, advancedEffects,
                shamsi, moduleCards, reducedMotion, vpn, vpnToggle, homeSettings, background,
                Math.max(0, Math.min(100, skyIntensity)), wallpaper, applied,
                searchLimit, searchTimeout);
    }

    /** True when the theme on disk changed (or first appeared) and the layout choices made on the
     *  TV — columns, animations, advanced effects — must be cleared so they cannot fight the new
     *  theme. {@code seen} is the stored signature (null: first use), {@code signature} the
     *  current one, {@code token} the theme_revision marker ("absent" when the tools never wrote one). */
    static boolean themeChanged(String seen, String signature, String token) {
        if (seen == null) {
            // Existing preferences survive an app upgrade; only an explicit theme push or reset
            // by the updated tools clears them on first use.
            return !"absent".equals(token);
        }
        return !seen.equals(signature);
    }

    /** The dock order edited on the TV ("pkg,pkg") as a list; null when nothing usable is stored. */
    static List<String> parseChosenDock(String chosen) {
        if (chosen == null || chosen.trim().isEmpty()) return null;
        List<String> out = new ArrayList<>();
        for (String p : chosen.split(",")) {
            p = p.trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out.isEmpty() ? null : out;
    }

    private static void syncThemeChoices(SharedPreferences choices, File dir, File config) {
        File revision = dir == null ? null : new File(dir, THEME_REVISION_NAME);
        String token = revisionToken(revision);
        String signature = fileStamp(config) + ":" + token;
        String seen = choices.getString(THEME_SIGNATURE_KEY, null);
        boolean reset = themeChanged(seen, signature, token);
        if (!reset && seen != null) return;   // nothing changed: no writes at all
        // apply(): the reads below and in the rest of load() hit the in-memory cache either way,
        // the disk write can wait.
        SharedPreferences.Editor editor = choices.edit();
        if (reset) editor.remove("columns").remove("animations").remove("advanced_effects");
        editor.putString(THEME_SIGNATURE_KEY, signature).apply();
    }

    /** Size + mtime, plus a short content hash; package-visible for the JVM tests. */
    static String fileStamp(File file) {
        if (file == null || !file.isFile()) return "absent";
        // Size + mtime, plus a short content hash: two edits within the same filesystem-second
        // (external-storage timestamps are coarse) must still reset the on-TV choices.
        long hash = file.length();
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            for (int i = 0; i < n; i++) hash = hash * 31 + (buf[i] & 0xff);
        } catch (Exception ignored) {
        }
        return hash + "@" + file.lastModified();
    }

    /** The theme_revision marker text, or "absent"; package-visible for the JVM tests. */
    static String revisionToken(File file) {
        if (file == null || !file.isFile() || file.length() > 64) return "absent";
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int n = in.read(bytes);
            return n > 0 ? new String(bytes, 0, n, StandardCharsets.US_ASCII) : "absent";
        } catch (Exception ignored) {
            return "absent";
        }
    }

    /** Reads and parses the overlay; any problem means "no overlay", never a crash on the home screen. */
    private static JSONObject read(File file) {
        if (file == null || !file.isFile()) return null;
        if (file.length() > 64 * 1024) {
            Log.w(TAG, "ignoring " + file + ": larger than 64 KB");
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            String text = new String(buf, 0, n, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) text = text.substring(1);   // Windows editors add a BOM
            return new JSONObject(text);
        } catch (Exception e) {
            Log.w(TAG, "ignoring " + file + ": " + e.getMessage());
            return null;
        }
    }

    private static String string(JSONObject o, String key, String fallback) {
        return string(o.opt(key), fallback);
    }

    /** Overlay values are type-checked, not trusted: a wrong type keeps the built-in value.
     *  Package-visible so the JVM tests exercise the same code the launcher runs. */
    static String string(Object v, String fallback) {
        return v instanceof String ? ((String) v).trim() : fallback;
    }

    private static boolean bool(JSONObject o, String key, boolean fallback) {
        return bool(o.opt(key), fallback);
    }

    static boolean bool(Object v, boolean fallback) {
        return v instanceof Boolean ? (Boolean) v : fallback;
    }

    private static int integer(JSONObject o, String key, int fallback, int min, int max) {
        return integer(o.opt(key), fallback, min, max);
    }

    /** Same contract as {@link #string(Object, String)} for numbers, plus the range check that
     *  keeps a theme from asking for 0 columns or a 40-second search timeout. */
    static int integer(Object v, int fallback, int min, int max) {
        if (!(v instanceof Number)) return fallback;
        int i = ((Number) v).intValue();
        return i < min || i > max ? fallback : i;
    }

    private static int color(JSONObject o, String key, int fallback) {
        Object v = o.opt(key);
        if (!(v instanceof String)) return fallback;
        try {
            int c = Color.parseColor(((String) v).trim());
            return c | 0xFF000000;   // always opaque: the window behind is black anyway
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static List<String> stringList(JSONObject o, String key, List<String> fallback) {
        Object v = o.opt(key);
        if (!(v instanceof JSONArray)) return fallback;
        JSONArray a = (JSONArray) v;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            Object item = a.opt(i);
            if (item instanceof String && !((String) item).trim().isEmpty()) out.add(((String) item).trim());
        }
        return out;
    }
}
