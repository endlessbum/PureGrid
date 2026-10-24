package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.BitmapFactory;
import android.provider.ContactsContract;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentUris;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    private static final int LAUNCHER_SETTINGS_REQUEST = 42;
    /** Amber for the network icon while the TV is connected but has no internet access. */
    private static final int WARN_COLOR = 0xFFFF9F0A;
    /** The pill's focused text colour (res/color/pill_text); the icon follows it while focused. */
    private static final int PILL_INK_FOCUSED = 0xFF111111;
    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private boolean rtlAppGrid;
    private static final long MINUTE_MS = 60_000L;
    private String liveTvPkg;
    private String liveTvAction;
    private String inputAction;
    private String sourcePanelService;
    private String sourcePanelAction;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private View ambient;
    private TextView heroName;
    private int ambientColor = -1;
    /** Fixed background accent from Launcher settings; 0 keeps sampling each app's colour. */
    private int fixedAccent;
    private ValueAnimator ambientAnimator;
    private boolean heroShown;
    /** Top of the app title in root coordinates; measured from the settled dock, NaN until then. */
    private float heroY = Float.NaN;
    /** Whether the top-right Google account button is hidden (Launcher settings). */
    private boolean accountButtonHidden;
    private boolean animations;
    private boolean advancedEffects;
    private TextView greeting;
    private boolean greetingEnabled;

    // ---- weather slide ----------------------------------------------------------------------------

    /** The seconds-in-a-minute marks a weather window starts at: :18, :38 and :58. The :58 one
     *  begins two seconds before the minute flips, so the flip happens while the clock is off
     *  screen and is never seen. */
    private static final int[] WEATHER_AT_SECONDS = {18, 38, 58};
    private FrameLayout clockSlot;
    private View clockFace;
    private View weatherFace;
    private TextView weatherTemp;
    private ImageView weatherIcon;
    /** The condition word rides in this clipping box: its left edge is the slot's left edge, so
     *  the word emerges and disappears through exactly the same line the slide does. */
    private FrameLayout weatherWordBox;
    private TextView weatherCondition;
    private boolean weatherEnabled;
    private boolean weatherShowing;
    private boolean weatherOffline;
    private Weather.Reading weather;
    /** When the last fetch left, in elapsed-realtime ms: a resume storm must not flood the API. */
    private long lastWeatherFetchAt;
    private final java.util.concurrent.ExecutorService weatherExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    /** One executor for the activity's lifetime: updateAccount and the cleaner used to build a new
     *  one per call, and a single-thread executor never retires its own thread, so every resume
     *  parked one more thread until the process died. */
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final Runnable weatherWindow = this::openWeatherWindow;
    private final Runnable weatherClose = this::closeWeatherWindow;
    private final Runnable weatherFetch = this::fetchWeather;
    private View btnInputs;
    private TextView btnWifi;
    private View btnClear;
    private AccountAvatarView btnAccount;
    private View btnSearch;
    // Left-bar dock editor: two stacked buttons (settings gear + eye) revealed by navigating left
    // from the first dock tile. Editor state lives in the fields below.
    private LinearLayout leftButtons;
    private ImageButton leftSettingsBtn;
    private ImageButton leftHideBtn;
    private ImageButton leftApplyBtn;
    private ImageButton leftDeleteBtn;
    private boolean leftButtonsVisible = false;
    private int leftButtonsFocus = 0;   // 0 none, 1 settings, 2 hide, 3 apply; delete sits outside the bar and is not counted
    /** Vertical centre of the dock bar row in root coordinates; the left buttons centre on it. */
    private int leftBarCenter = 0;
    private int leftBtnSizePx = 0;
    private int leftGapPx = 0;
    private boolean hideBelow = false;
    private boolean editMode = false;
    /** True while a long OK has "grabbed" a dock app in the editor and LEFT/RIGHT re-order it. */
    private boolean moveMode = false;
    private ImageButton leftArrow, rightArrow;
    private int arrowSizePx = 0;
    private RecyclerView topOverlay;
    private SimpleAdapter topAdapter;
    private int topSelected = 0;
    private boolean editFocusTop = true;
    private boolean topActive = false;
    private int editSlot = 0;
    private String editCandidate = null;
    private TextView wifiTooltip;
    private String wifiNetworkName = "";
    /** A second tooltip line under the network name, e.g. "Connected without access". */
    private String wifiStatus = "";
    private Runnable wifiAnimRunnable;
    private boolean wifiAnimating;
    private Runnable ethAnimRunnable;
    private boolean ethAnimating;
    /** The date's normal text size in pixels, before it is shrunk to fit the top bar. */
    private float dateBaseSizePx;
    private TextView shamsiDate;
    private boolean shamsi;
    /** Optional time-of-day backdrop (config.xml, sky_intensity); 0 keeps it hidden. */
    private SkyView sky;
    private String vpnApp;
    /** Broadcast that switches the VPN app on or off in the background (config.xml, vpn_toggle_action). */
    private String vpnToggleAction;
    /** Whether a VPN app is installed; null until first checked, cleared when packages change. */
    private Boolean vpnInstalledCache;
    /** One waiting "focus the tile after the scroll settles" listener, so an old one cannot fire later. */
    private RecyclerView.OnScrollListener pendingFocus;
    /** Posted "focus tile once the instant reset has laid out" runner, for cancelPendingFocus. */
    private Runnable restFocusRunner;
    /** One waiting "dock parked" check: corrects the scroll to exactly 0 once the parking settles. */
    private RecyclerView.OnScrollListener dockParking;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private String lastFocusedKey;
    private float density;
    /** Module cards (res/values/config.xml, module_cards): discovered on resume, shown only after the owner's Show. */
    private boolean moduleCards;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): stiff spring, short fades, no replay. */
    private boolean reducedMotion;
    /** hidden_apps as the adapter sees them (config.xml or a theme's config.json): a hidden app gets no card either. */
    private Set<String> hiddenApps = Collections.emptySet();
    /** The dock order as stored: the config's at start; applyEdit keeps it in step with the prefs,
     *  so exitEditMode can restore it without re-reading config.json from disk on the UI thread. */
    private List<String> storedDock = Collections.emptyList();
    private List<Cards.Card> cards = Collections.emptyList();
    /** The grid has been laid out and focused since the last resume; the card prompt waits for this. */
    private boolean tilesReady;
    private Dialog cardPrompt;
    /** Set when a decision could not be saved: no more prompts until the next resume, instead of asking in a loop. */
    private boolean promptsPaused;
    /** Rebuild the tiles on resume: Home was fully hidden by an app, or Home was pressed. Not after a side panel such as Wi-Fi settings. */
    private boolean rebuildOnResume = true;
    /** The Inputs or Module cards dropdown while one is open, so onPause can close it. */
    private PopupWindow openPopup;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities caps) {
            // Validation arrives after the network does: refresh so "connected, no internet" clears.
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities caps) {
            // A VPN can stay up while its traffic stops: the badge follows validation too.
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_PACKAGE_REMOVED.equals(action)
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            // The VPN badge depends on whether a VPN app is installed, so re-read that too.
            vpnInstalledCache = null;
            handler.post(MainActivity.this::updateWifiLabel);
            if (Intent.ACTION_PACKAGE_ADDED.equals(action) || Intent.ACTION_PACKAGE_REMOVED.equals(action) || Intent.ACTION_PACKAGE_CHANGED.equals(action)) {
                reloadApps(false);
            } else if ("com.gathin.tvhome.ACTION_REFRESH_APPS".equals(action)) {
                // Refresh request from WorkManager.
                reloadApps(false);
            }
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            sky.update();
            handler.postDelayed(this, MINUTE_MS);
        }
    };
    /** The boot-time network refreshes: re-posted while the connection settles, dropped in onPause. */
    private final Runnable wifiRetry = this::updateWifiLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Keeps the screen on while the launcher itself is in front. onPause clears the flag (the
        // system may sleep while something else is on screen) and onResume takes it back, so it
        // follows the visible/not-visible cycle rather than the process's life. Это держит
        // Android-экран, но не вендорский MediaTek AutoSleep — если ТВ гаснет раньше, причина в
        // Energy Saver TV Settings.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        // Schedule periodic app list refresh (every 6 hours) via WorkManager.
        androidx.work.PeriodicWorkRequest refreshRequest = new androidx.work.PeriodicWorkRequest.Builder(
                RefreshAppsWorker.class, java.util.concurrent.TimeUnit.HOURS.toMillis(6), java.util.concurrent.TimeUnit.MILLISECONDS)
                .addTag("refresh-apps")
                .build();
        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "refresh-apps", androidx.work.ExistingPeriodicWorkPolicy.KEEP, refreshRequest);
        density = getResources().getDisplayMetrics().density;
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        Config cfg = Config.load(this);
        // Provide the dock order to DockOrder for proper reordering.
        DockOrder.setDockApps(cfg.dockApps);
        COLUMNS = cfg.columns;
        animations = cfg.animations;
        advancedEffects = cfg.animations && cfg.advancedEffects;
        reducedMotion = cfg.reducedMotion;
        liveTvPkg = cfg.liveTvPackage;
        liveTvAction = cfg.liveTvAction;
        inputAction = cfg.inputAction;
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;

        root = findViewById(R.id.root);
        root.setBackgroundColor(cfg.backgroundColor);
        if (cfg.wallpaper != null) showWallpaper(cfg.wallpaper);
        sky = findViewById(R.id.sky);
        sky.setIntensity(cfg.skyIntensity);
        ambient = findViewById(R.id.ambient);
        // The glow is one bitmap, so its alpha can be applied while drawing it. Otherwise every frame of
        // a fade renders it into a full-screen offscreen buffer first.
        ambient.forceHasOverlappingRendering(false);
        heroName = findViewById(R.id.heroName);
        greeting = findViewById(R.id.greeting);
        greetingEnabled = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getBoolean("greeting_enabled", true);
        greeting.setVisibility(greetingEnabled ? View.VISIBLE : View.GONE);
        clockSlot = findViewById(R.id.clockSlot);
        clockFace = findViewById(R.id.clockFace);
        weatherFace = findViewById(R.id.weatherFace);
        weatherTemp = findViewById(R.id.weatherTemp);
        weatherIcon = findViewById(R.id.weatherIcon);
        // The condition word is an overlay under the clock row, not a layout child: a child there
        // would grow the left block and drag the top-right buttons down with it three times a minute.
        weatherWordBox = new FrameLayout(this);
        weatherWordBox.setClipChildren(true);
        weatherWordBox.setVisibility(View.INVISIBLE);
        weatherCondition = new TextView(this);
        weatherCondition.setTextColor(0xA6FFFFFF);
        weatherCondition.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        weatherCondition.setTypeface(getResources().getFont(R.font.outfit_regular));
        weatherCondition.setLetterSpacing(0.14f);
        weatherCondition.setAllCaps(true);
        weatherCondition.setSingleLine(true);
        weatherWordBox.addView(weatherCondition, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(weatherWordBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        weatherEnabled = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getBoolean("weather_enabled", true);
        // Freeze the slot to the room the widest clock text needs ("88:88" — a narrower moment
        // like "9:50" would freeze a short slot and later clip "12:50" to "12:5"), so neither
        // the clock nor the slide can ever resize the top bar row.
        clockSlot.post(() -> {
            if (clockSlot.getWidth() == 0 || clockSlot.getHeight() == 0) return;
            TextView clockView = (TextView) findViewById(R.id.clock);
            float widest = clockView.getPaint().measureText("88:88");
            ViewGroup.LayoutParams lp = clockSlot.getLayoutParams();
            lp.width = Math.max(clockFace.getWidth(),
                    (int) Math.ceil(widest + (clockFace.getWidth() - clockView.getWidth())));
            lp.height = clockSlot.getHeight();
            clockSlot.setLayoutParams(lp);
            weatherFace.setVisibility(View.INVISIBLE);
        });
        btnWifi = findViewById(R.id.btnWifi);
        wifiTooltip = new TextView(this);
        wifiTooltip.setTextColor(Color.WHITE);
        wifiTooltip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        wifiTooltip.setGravity(Gravity.CENTER);
        wifiTooltip.setMaxWidth((int) (300 * density));
        wifiTooltip.setAlpha(0f);
        wifiTooltip.setTranslationY(8f * density);
        root.addView(wifiTooltip, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shamsiDate = findViewById(R.id.shamsiDate);
        shamsi = cfg.shamsiDate;
        TextView date = findViewById(R.id.date);
        if (shamsi) {
            shamsiDate.setVisibility(View.VISIBLE);
            date.setTextSize(11f);
            date.setPadding(0, (int) (4 * density), 0, 0);
        }
        dateBaseSizePx = date.getTextSize();
        vpnApp = cfg.vpnApp;
        vpnToggleAction = cfg.vpnToggleAction;
        moduleCards = cfg.moduleCards;
        hiddenApps = new HashSet<>(cfg.hiddenApps);
        storedDock = new ArrayList<>(cfg.dockApps);
        hideBelow = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getBoolean("hide_below", false);
        fixedAccent = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getInt("accent_color", 0);

        grid = findViewById(R.id.grid);
        rtlAppGrid = getResources().getBoolean(R.bool.rtl_app_grid);
        // Mirror only the app grid; the header and other locales keep their own layouts.
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS) {
            @Override
            protected boolean isLayoutRTL() {
                return rtlAppGrid;
            }
        });
        // The grid itself can hold focus: during optimal Home resets the focus is parked here,
        // because with no focused view at all Android's restoreDefaultFocus would hand the
        // focus to the first top-bar button and light it up.
        grid.setFocusable(true);
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.addItemDecoration(new DockDecoration(COLUMNS, density));
        if (animations) {
            int enterAnim = reducedMotion ? R.anim.tile_enter_reduced : R.anim.tile_enter;
            float enterDelay = reducedMotion ? 0f : 0.06f;
            grid.setLayoutAnimation(new LayoutAnimationController(
                    AnimationUtils.loadAnimation(this, enterAnim), enterDelay));
        }
        adapter = new AppAdapter(this, this, COLUMNS, cfg);
        grid.setAdapter(adapter);
        adapter.setHideBelow(hideBelow);

        // Left-bar dock editor buttons (settings gear + eye), stacked; revealed by navigating left
        // from the first dock tile. Created in code so no layout XML changes are needed.
        leftButtons = new LinearLayout(this);
        leftButtons.setOrientation(LinearLayout.VERTICAL);
        leftButtons.setGravity(Gravity.CENTER);
        leftButtons.setVisibility(View.GONE);
        int btnSize = (int) (42 * density);
        int gap = (int) (6 * density);
        leftBtnSizePx = btnSize;
        leftGapPx = gap;
        leftSettingsBtn = new ImageButton(this);
        leftSettingsBtn.setImageResource(R.drawable.ic_settings);
        leftSettingsBtn.setBackgroundResource(R.drawable.pill_bg_round);
        leftSettingsBtn.setFocusable(true);
        leftSettingsBtn.setContentDescription(getString(R.string.dock_editor_settings));
        LinearLayout.LayoutParams lpS = new LinearLayout.LayoutParams(btnSize, btnSize);
        lpS.bottomMargin = gap;
        leftButtons.addView(leftSettingsBtn, lpS);
        leftHideBtn = new ImageButton(this);
        leftHideBtn.setImageResource(R.drawable.ic_eye_off);
        leftHideBtn.setBackgroundResource(R.drawable.pill_bg_round);
        leftHideBtn.setFocusable(true);
        leftHideBtn.setContentDescription(getString(R.string.dock_editor_hide_below));
        leftButtons.addView(leftHideBtn, new LinearLayout.LayoutParams(btnSize, btnSize));
        leftApplyBtn = new ImageButton(this);
        leftApplyBtn.setImageResource(R.drawable.ic_check);
        leftApplyBtn.setBackgroundResource(R.drawable.pill_bg_round);
        leftApplyBtn.setFocusable(true);
        leftApplyBtn.setVisibility(View.GONE);
        leftApplyBtn.setContentDescription(getString(R.string.dock_editor_apply));
        leftButtons.addView(leftApplyBtn, new LinearLayout.LayoutParams(btnSize, btnSize));
        leftDeleteBtn = new ImageButton(this);
        leftDeleteBtn.setImageResource(R.drawable.ic_trash);
        leftDeleteBtn.setBackgroundResource(R.drawable.pill_bg_round);
        leftDeleteBtn.setFocusable(true);
        leftDeleteBtn.setVisibility(View.GONE);
        leftDeleteBtn.setContentDescription(getString(R.string.dock_editor_delete));
        FrameLayout.LayoutParams flpD = new FrameLayout.LayoutParams(btnSize, btnSize);
        flpD.leftMargin = 0;
        flpD.topMargin = 0;
        root.addView(leftDeleteBtn, flpD);
        // Direction hints shown while a dock app is grabbed for re-ordering (long OK).
        arrowSizePx = (int) (48 * density);
        leftArrow = new ImageButton(this);
        leftArrow.setImageResource(R.drawable.ic_chevron_left);
        leftArrow.setBackground(null);
        leftArrow.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        leftArrow.setFocusable(false);
        leftArrow.setClickable(false);
        leftArrow.setVisibility(View.GONE);
        leftArrow.setContentDescription(getString(R.string.move_left));
        rightArrow = new ImageButton(this);
        rightArrow.setImageResource(R.drawable.ic_chevron_right);
        rightArrow.setBackground(null);
        rightArrow.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        rightArrow.setFocusable(false);
        rightArrow.setClickable(false);
        rightArrow.setVisibility(View.GONE);
        rightArrow.setContentDescription(getString(R.string.move_right));
        android.content.res.ColorStateList arrowTint = getColorStateList(R.color.pill_text);
        leftArrow.setImageTintList(arrowTint);
        rightArrow.setImageTintList(arrowTint);
        root.addView(leftArrow, new FrameLayout.LayoutParams(arrowSizePx, arrowSizePx));
        root.addView(rightArrow, new FrameLayout.LayoutParams(arrowSizePx, arrowSizePx));
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.leftMargin = (int) (12 * density);
        root.addView(leftButtons, flp);
        // Recompute after every layout pass: the dock's real position is only known then.
        root.getViewTreeObserver().addOnGlobalLayoutListener(this::centerLeftButtons);
        root.getViewTreeObserver().addOnGlobalLayoutListener(this::positionDeleteButton);
        root.getViewTreeObserver().addOnGlobalLayoutListener(this::positionMoveArrows);
        // Keep the app title pinned above the dock even if focus arrived before the dock was padded.
        root.getViewTreeObserver().addOnGlobalLayoutListener(this::pinHero);

        leftSettingsBtn.setOnClickListener(v -> enterEditMode());
        leftHideBtn.setOnClickListener(v -> toggleHideBelow());
        leftApplyBtn.setOnClickListener(v -> applyEdit());
        // Focused like the top-bar buttons: white disc + dark icon, so the glyph never blends in.
        android.content.res.ColorStateList iconTint = getColorStateList(R.color.pill_text);
        leftSettingsBtn.setImageTintList(iconTint);
        leftHideBtn.setImageTintList(iconTint);
        leftApplyBtn.setImageTintList(iconTint);
        leftDeleteBtn.setImageTintList(iconTint);
        leftSettingsBtn.setOnFocusChangeListener((v, f) -> { if (f) leftButtonsFocus = 1; });
        leftHideBtn.setOnFocusChangeListener((v, f) -> { if (f) leftButtonsFocus = 2; });
        leftApplyBtn.setOnFocusChangeListener((v, f) -> { if (f) leftButtonsFocus = 3; });

        View.OnKeyListener btnKeys = (v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (v == leftSettingsBtn) {
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) { leftHideBtn.requestFocus(); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    leftButtonsVisible = false; leftButtons.setVisibility(View.GONE); leftButtonsFocus = 0;
                    focusTile(0); return true;
                }
                return false;
            }
            if (v == leftHideBtn) {
                if (code == KeyEvent.KEYCODE_DPAD_UP) { leftSettingsBtn.requestFocus(); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    leftButtonsVisible = false; leftButtons.setVisibility(View.GONE); leftButtonsFocus = 0;
                    focusTile(0); return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) { toggleHideBelow(); return true; }
                return false;
            }
            if (v == leftApplyBtn) {
                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) { applyEdit(); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    editFocusTop = false;
                    focusTile(editSlot, true);
                    grid.post(MainActivity.this::scaleEditSlot);
                    return true;
                }
                return false;
            }
            if (v == leftDeleteBtn) {
                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                    deleteEditSlot();
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    leftDeleteBtn.setVisibility(View.GONE);
                    editFocusTop = false;
                    focusTile(editSlot, true);
                    grid.post(MainActivity.this::scaleEditSlot);
                    return true;
                }
                return false;
            }
            return false;
        };
        leftSettingsBtn.setOnKeyListener(btnKeys);
        leftHideBtn.setOnKeyListener(btnKeys);
        leftApplyBtn.setOnKeyListener(btnKeys);
        leftDeleteBtn.setOnKeyListener(btnKeys);

        // tvOS layout: push the dock row towards the bottom of the screen, leaving room for
        // rows_below_dock grid rows under it; the rest of the grid stays below until you press Down.
        int rowsBelowDock = cfg.rowsBelowDock;
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            // Item height follows the full width (not the centred padding) so the dock row height
            // cannot oscillate while the padding below is being recomputed.
            int fullInner = r - l;
            int itemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(AppAdapter.tileWidthPx(fullInner, COLUMNS, density)), density);
            int dockRow = itemH + (int) ((6 + 12) * density);   // item height plus DockDecoration offsets
            int gridRows = itemH * rowsBelowDock;
            int want = Math.max(0, h - dockRow - gridRows - (int) (30 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
            applyDockCentering();
            // A padding change re-lays the tiles out without a scroll; keep the glow on its tile
            // and the title over the dock where it has just moved.
            grid.post(this::pinHero);
        });

        // Centre a short dock (fewer than COLUMNS apps) by adding symmetric side padding. Skipped in
        // the editor: the "+" slots already fill the row, so it must stay full width.
        grid.post(this::applyDockCentering);

        btnInputs = findViewById(R.id.btnInputs);
        btnClear = findViewById(R.id.btnClear);
        btnSearch = findViewById(R.id.btnSearch);
        View btnSettings = findViewById(R.id.btnSettings);
        btnAccount = findViewById(R.id.btnAccount);
        applyTopBarVisibility();
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnWifi.setText("");
        btnWifi.setOnFocusChangeListener((v, hasFocus) -> {
            wifiTooltip.setText(wifiTooltipText());
            positionWifiTooltip();
            wifiTooltip.animate().cancel();
            if (hasFocus) {
                wifiTooltip.setTranslationY(-8f * density);
                wifiTooltip.animate().alpha(1f).translationY(0f).setDuration(180).start();
            } else {
                wifiTooltip.animate().alpha(0f).translationY(8f * density).setDuration(180).start();
            }
        });
        btnWifi.setOnLongClickListener(v -> {
            open(systemIntent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        btnSettings.setOnClickListener(v -> showSettingsMenu());
        btnClear.setOnClickListener(v -> clearBackgroundProcesses());
        btnSearch.setOnClickListener(v -> startActivity(new Intent(this, SearchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        // The account pill opens the system's accounts screen (the TV settings' "Accounts" entry);
        // systemIntent falls back to Xiaomi's own settings on China firmware.
        btnAccount.setOnClickListener(v -> open(systemIntent(Settings.ACTION_SYNC_SETTINGS)));

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        boolean rtlMenuBar = getResources().getBoolean(R.bool.rtl_menu_bar);
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Back to the dock tile Up came from, not wherever the focus finder lands.
                focusTile(lastFocused % COLUMNS);
                return true;
            }
            int firstButton = btnInputs.getVisibility() == View.VISIBLE ? R.id.btnInputs : R.id.btnWifi;
            // The account button took the bar's far end that used to be Settings'. When hidden,
            // the end is Settings again.
            int accountEnd = accountButtonHidden ? R.id.btnSettings : R.id.btnAccount;
            int leftButton = rtlMenuBar ? accountEnd : firstButton;
            int rightButton = rtlMenuBar ? firstButton : accountEnd;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == leftButton;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == rightButton;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnClear.setOnKeyListener(pillKeys);
        btnSearch.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);
        btnAccount.setOnKeyListener(pillKeys);
        // The top buttons keep their size: they already show focus by turning white, and a pill
        // that grows breaks the row of equal buttons in the bar.

        // No GlassView – nothing to hide when focus leaves tiles.
        // (Removed global focus listener that referenced GlassView.)

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        if (cm != null) {
            cm.registerDefaultNetworkCallback(networkCallback);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), vpnCallback);
        }
        // At boot the network appears after Home is shown; refresh a few times so the icon is correct.
        handler.postDelayed(wifiRetry, 500);
        handler.postDelayed(wifiRetry, 2000);
        handler.postDelayed(wifiRetry, 5000);
    }

    /**
     * A theme's full-screen picture behind everything. Decoded once, downsampled to the screen so a 4K
     * JPEG costs a 1080p bitmap at most (about 8 MB), which is the price of a wallpaper on a 1 GB TV.
     */
    private void showWallpaper(File file) {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
            int sample = 1;
            while (opts.outWidth / (sample * 2) >= dm.widthPixels && opts.outHeight / (sample * 2) >= dm.heightPixels) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;   // no alpha needed, half the memory
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
            if (bmp == null) return;
            android.widget.ImageView wall = new android.widget.ImageView(this);
            wall.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            wall.setImageBitmap(bmp);
            root.addView(wall, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Exception | OutOfMemoryError e) {
            // A bad or huge picture must never take the home screen down; the plain colour stays.
        }
    }

    /**
     * Home pressed while Home is already in front: singleTask delivers it here, then onResume runs.
     * Back to the top with the first dock tile focused, like the stock launcher. Back from an app
     * never comes this way, so returning to the app you left from is unchanged (#118).
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        // Home always collapses whatever is open (dock editor, popups, left buttons).
        boolean hadOverlay = editMode || leftButtonsVisible || openPopup != null;
        collapseAll();
        // Already on the first tile (and the grid holds focus): do nothing, so the home screen
        // does not flicker/rebuild. Top-bar buttons also send focus to the first tile.
        if (!hadOverlay && lastFocused == 0 && grid.hasFocus()) return;
        // The grid is about to jump to the top; the glow must not live on through the jump at
        // the old place, so it is taken down first and comes back on the first dock tile.
        
        lastFocused = 0;
        lastFocusedKey = adapter.getItemCount() > 0 ? adapter.keyAt(0) : null;
        focusTile(0, true);
    }

    /** Closes the dock editor, any popup and the left buttons; Home then lands on the first tile. */
    private void collapseAll() {
        if (editMode) exitEditMode();
        if (openPopup != null) {
            openPopup.dismiss();
            openPopup = null;
        }
        leftButtonsVisible = false;
        leftButtonsFocus = 0;
        if (leftButtons != null) leftButtons.setVisibility(View.GONE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageFilter);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Take the keep-screen-on flag again: onPause cleared it so the system could sleep while
        // something else was in front. Without this, every return from an app left the launcher
        // without it, and the next screen timeout put the TV into standby with the launcher
        // itself on screen — dark, and answering only the power button.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        promptsPaused = false;
        // A VPN installed while Home was in the background changes the badge: re-read it.
        vpnInstalledCache = null;
        if (rebuildOnResume) {
            tilesReady = false;
            reloadApps(true);
        }
        rebuildOnResume = false;
        // The greeting choice may have changed in settings; re-read it so the row follows the
        // pref on every return, even on a build where the recreate() hand-off does not relaunch.
        boolean greetOn = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getBoolean("greeting_enabled", true);
        if (greetOn != greetingEnabled) {
            greetingEnabled = greetOn;
            greeting.setVisibility(greetOn ? View.VISIBLE : View.GONE);
        }
        // The weather choice follows the same pattern as the greeting's.
        weatherEnabled = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getBoolean("weather_enabled", true);
        if (weatherEnabled) {
            fetchWeather();
            scheduleNextWeatherWindow();
        }
        updateGreeting();
        updateWifiLabel();
        updateAccount();
        applyTopBarVisibility();
        handler.postDelayed(wifiRetry, 1000);
        handler.postDelayed(wifiRetry, 3000);
        updateInputsButton();
        sky.update();
        handler.postDelayed(minuteTick, MINUTE_MS);
    }

    /** Shows or hides the top-right buttons according to the choices in Launcher settings. */
    private void applyTopBarVisibility() {
        if (btnAccount == null || btnClear == null || btnSearch == null) return;
        accountButtonHidden = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getBoolean("account_button_hidden", false);
        btnAccount.setVisibility(accountButtonHidden ? View.GONE : View.VISIBLE);
        btnClear.setVisibility(getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getBoolean("clear_button_hidden", false) ? View.GONE : View.VISIBLE);
        btnSearch.setVisibility(getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getBoolean("search_button_hidden", false) ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == LAUNCHER_SETTINGS_REQUEST && resultCode == RESULT_OK) recreate();
    }

    /** Rebuilds the tiles (and the module cards), then puts focus back where it was. */
    private void reloadApps(boolean animate) {
        // Capture the app before rebinding can move focus and overwrite its key.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean restoreFocus = animate || grid.hasFocus();
        if (restoreFocus) {
            // An old "focus after scroll" request must not fire over the fresh one.
            cancelPendingFocus();
            cancelDockParking();
            heroShown = false;
            heroName.setAlpha(0f);
            ambient.setAlpha(0f);
        }
        reloadTiles();
        // The enter animation slides the tiles up from below; while focus is being restored the
        // glow would sit at the exact spot while the tiles are still travelling, so it is skipped
        // whenever the glow is about to be pinned on a tile again.
        if (animate && animations && !reducedMotion && !restoreFocus) grid.scheduleLayoutAnimation();

        if (restoreFocus) {
            // Follow the same app, or the nearest remaining slot when it was removed.
            restoreFocus();
        } else {
            // A package change must not steal focus from a top button or the Inputs popup.
            int pos = adapter.positionOf(returnKey);
            lastFocused = Math.max(0, Math.min(pos >= 0 ? pos : returnSlot, adapter.getItemCount() - 1));
            lastFocusedKey = adapter.keyAt(lastFocused);
            if (pos < 0) hideHero();
            // A newly installed app may have brought a card with it.
            tilesReady = true;
            showCardPrompt();
        }
    }

    /**
     * Rebuilds the tiles; the module cards the owner chose to show stand in for their app's tile.
     * Every step into the card code runs under CardFeature: if it fails, the tiles are the ordinary
     * ones and cards stay off for this process.
     */
    private void reloadTiles() {
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final List<Cards.Card> none = Collections.emptyList();
        cards = moduleCards ? CardFeature.run(() -> Cards.discover(this, hiddenApps, Cards.load(Cards.storeFile(this))), none) : none;
        adapter.reload(cards.isEmpty() ? none : CardFeature.run(() -> Cards.shown(cards), none));
        lastFocusedKey = returnKey;
        lastFocused = returnSlot;
        // A changed dock (add/remove/apply) must re-centre the bar even when the grid's bounds did not move.
        grid.post(this::applyDockCentering);
    }

    /**
     * Return to the app we left from, or the same slot if that app is gone. Always refocus: after
     * reload() the view that holds focus may no longer show the app it had.
     */
    private void restoreFocus() {
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            focusTile(pos >= 0 ? pos : returnSlot);
            tilesReady = true;
            showCardPrompt();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Release the KEEP_SCREEN_ON flag so the TV can sleep when the launcher is not visible.
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        stopAmbientAnimation();
        heroName.animate().cancel();
        ambient.animate().cancel();
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(wifiRetry);
        // The weather windows belong to a visible screen; coming back reschedules them in onResume.
        handler.removeCallbacks(weatherWindow);
        handler.removeCallbacks(weatherClose);
        handler.removeCallbacks(weatherFetch);
        if (weatherShowing) {
            weatherShowing = false;
            clockFace.animate().cancel();
            weatherFace.animate().cancel();
            clockFace.setTranslationX(0f);
            weatherFace.setTranslationX(0f);
            weatherFace.setVisibility(View.INVISIBLE);
            weatherCondition.animate().cancel();
            weatherWordBox.setVisibility(View.INVISIBLE);
        }
        // Nothing of ours stays on screen over another app; an undecided prompt simply returns next time.
        if (cardPrompt != null) cardPrompt.dismiss();
        if (openPopup != null) openPopup.dismiss();
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(packageReceiver);
        rebuildOnResume = true;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) showCardPrompt();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
            cm.unregisterNetworkCallback(vpnCallback);
        }
        stopWifiAnimation();
        stopEthAnimation();
        // Retire the executors with the activity: recreate() builds new ones, and the old threads
        // would otherwise sit parked until the process died.
        backgroundExecutor.shutdown();
        weatherExecutor.shutdown();
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere unless we are in a sub‑mode.
        if (moveMode) {
            exitMoveMode();
            return;
        }
        if (editMode) {
            exitEditMode();
            return;
        }
        if (leftButtonsVisible) {
            // Collapse left‑hand shortcut bar.
            leftButtonsVisible = false;
            leftButtons.setVisibility(View.GONE);
            leftButtonsFocus = 0;
            return;
        }
        // If none of the above apply, let the system handle it (it will just stay on Home).
    }

    /**
     * The remote's Settings button.
     *
     * The stock Google TV launcher used to open the system settings from that button, and with
     * the stock launcher disabled the button did nothing at all: the key reached this activity
     * and no one answered it. The Home-key accessibility service can do the same job, but it is
     * off by default and only sees keys while it is enabled, so the launcher answers the key
     * itself.
     *
     * KEYCODE_SETTINGS is answered here. KEYCODE_MENU is only taken when nothing else wanted
     * it, because some remotes send that for the same button and some apps use it for their own
     * menu.
     *
     * Note that a vendor receiver may take the key before the launcher sees it: on the MediaTek
     * firmware the Settings button is caught by the system process (com.mediatek.wwtv.tvcenter)
     * and never reaches the home screen at all. The accessibility service sees keys earlier, so
     * on such a TV the button works through it (HOME_KEY_SERVICE=1 build-and-install.sh).
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_HOME && event.getAction() == KeyEvent.ACTION_DOWN
                && (editMode || leftButtonsVisible || openPopup != null)) {
            collapseAll();
            
            lastFocused = 0;
            lastFocusedKey = adapter.getItemCount() > 0 ? adapter.keyAt(0) : null;
            focusTile(0, true);
            return true;
        }
        if (code == KeyEvent.KEYCODE_SETTINGS) {
            if (event.getAction() == KeyEvent.ACTION_UP) open(systemIntent(Settings.ACTION_SETTINGS));
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU && event.getAction() == KeyEvent.ACTION_UP) {
            open(systemIntent(Settings.ACTION_SETTINGS));
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // ---- glass highlight -------------------------------------------------------------------------

    /** Bounds of the glow as {x, y, width, height} in root coordinates, for this card and column. */

    /** Moves the glow to these bounds without touching its animation state or alpha. */

    /**
     * Pins the glow to the card that holds focus right now, at its true current position. Called
     * on every scroll and after any layout change, so the glow can neither trail the tile nor
     * linger somewhere else in the launcher.
     */

    @Override
    public void onTileFocused(int position, View card) {
        // The tile motion itself (each tile's own smooth focus scale) lives in AppAdapter, next to
        // the scale it ends on; this side only deals with where the grid and the top shelf go.
        cancelDockParking();
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        // In the dock editor there is no glass glow and no hero caption behind the overlay bar.
        if (editMode) {
            
            hideHero();
            return;
        }
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);

        View item = (View) card.getParent();
        if (position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            // The dock's resting place is the top of the grid (the padding below holds it against the
            // bottom of the screen), so the scroll goes to a known zero. Computing a delta from the
            // current scroll instead made every move along the row start a new scroll, and the whole
            // screen jumped as the icons moved.
            int dy = -grid.computeVerticalScrollOffset();
            boolean scroll = Math.abs(dy) > (int) (2 * density);
            showHero(position, scroll ? r.top - dy : r.top);
            if (!scroll) return;
            if (!animations || reducedMotion) {
                grid.scrollBy(0, dy);
                attachDockVerifier();
                return;
            }
            if (grid.getScrollState() == RecyclerView.SCROLL_STATE_IDLE) {
                // Idle start: one glide from a settled state lands exactly on 0.
                applyDockPark();
            } else {
                // A correction begun while another scroll is still in flight can stop short of
                // 0 and leave the dock hanging above or below its resting place. Wait for the
                // settle and glide from there instead, so the bar never visibly re-snaps.
                attachDockVerifier();
            }
        } else {
            hideHero();
            // Scroll just enough so the whole glass (not only the tile) is visible.
            float pad = 6f * density;        // the room the glow needs around the card
            Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, reducedMotion);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    /** Glides the grid so its scroll offset is exactly 0 (the dock's resting place); idle callers only. */
    private void applyDockPark() {
        int left = -grid.computeVerticalScrollOffset();
        if (Math.abs(left) <= (int) (2 * density)) return;
        if (!animations || reducedMotion) grid.scrollBy(0, left);
        else grid.smoothScrollBy(0, left);
        attachDockVerifier();
    }

    /**
     * One idle check that finishes the dock parking: if anything left the offset short of 0,
     * glide the rest of the way from the settled state, and keep watching until it truly rests.
     */
    private void attachDockVerifier() {
        cancelDockParking();
        grid.addOnScrollListener(dockParking = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                dockParking = null;
                int left = -grid.computeVerticalScrollOffset();
                if (Math.abs(left) <= (int) (2 * density)) return;
                if (!animations || reducedMotion) grid.scrollBy(0, left);
                else grid.smoothScrollBy(0, left);
                attachDockVerifier();
            }
        });
    }

    /**
     * Pins the selected app's name to one spot above the dock.
     *
     * The dock rests at the bottom of the grid, but the padding that puts it there is computed in
     * a layout listener after the first layout pass. When the system restored focus during that
     * first pass, onTileFocused measured the dock at the top of the grid; after the dock moved
     * down the title stayed at the top and collided with the clock. Deriving the title from the
     * grid's settled geometry, after every layout, keeps it above the bar instead of following
     * a stale tile.
     */
    private void pinHero() {
        if (grid == null || heroName == null) return;
        if (grid.getHeight() == 0 || heroName.getHeight() == 0) return;
        // The dock's resting place is fixed by the grid's padding: the first row starts at the
        // padding, then the decoration gap, then the tile's own inset (DockDecoration, item_app).
        // Deriving it from the grid instead of from the focused tile keeps the title still while
        // the dock glides into place after a cold start.
        Rect g = new Rect(0, 0, grid.getWidth(), grid.getHeight());
        root.offsetDescendantRectToMyCoords(grid, g);
        float cardTop = g.top + grid.getPaddingTop() + 6 * density + 8 * density;
        heroY = cardTop - 14 * density - 26 * density - heroName.getHeight();
        if (heroShown) heroName.setY(heroY);
    }

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = fixedAccent != 0 ? fixedAccent : adapter.ambientColorAt(position);
        // Prefer the measured anchor. The fallback covers the very first call, before the dock has
        // been laid out; pinHero corrects it as soon as that layout pass has run.
        float y = Float.isNaN(heroY)
                ? dockTopInRoot - 14 * density - 26 * density - heroName.getHeight()
                : heroY;
        boolean changed = !label.contentEquals(heroName.getText());

        if (!animations || reducedMotion) {
            // Snap the shelf instead of sliding and fading it: each of those frames repaints the
            // full-screen panel, and this switch exists for the GPUs that cannot afford it.
            heroName.animate().cancel();
            ambient.animate().cancel();
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.setAlpha(1f);
            setAmbient(color);
            if (!animations) {
                ambient.setAlpha(1f);
                heroShown = true;
            } else if (!heroShown) {
                ambient.setAlpha(0f);
                ambient.animate().alpha(1f).setDuration(160).start();
                heroShown = true;
            }
            return;
        }

        if (!heroShown) {
            stopAmbientAnimation();
            heroName.animate().cancel();
            ambient.animate().cancel();
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(260).start();
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(600).start();
            heroShown = true;
        } else if (changed) {
            if (advancedEffects) heroName.animate().cancel();
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(12 * density);
                heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            if (advancedEffects) {
                transitionAmbient(color);
            } else if (color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            }
        } else {
            heroName.setY(y);
        }
    }

    /**
     * The glow used to be a full-screen radial GradientDrawable. On Android 10 that drawable is
     * drawn on the CPU, so fading it for half a second redrew 1920×1080 every frame and the
     * highlight looked like it was running at a few frames per second. A small bitmap stretched
     * over the screen is one texture. It is painted once in white and tinted with the app's colour,
     * so changing apps neither repaints it on the CPU (about 11 ms) nor uploads it again.
     */
    private void setAmbient(int color) {
        if (color == ambientColor && ambient.getBackground() != null) return;
        ambientColor = color;
        BitmapDrawable glow = (BitmapDrawable) ambient.getBackground();
        if (glow == null) {
            int w = 480;
            int h = 270;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setShader(new RadialGradient(
                    w * 0.32f, h * 0.42f, w * 0.62f,
                    new int[]{0x5CFFFFFF, 0x26FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            glow = new BitmapDrawable(getResources(), bmp);
            glow.setGravity(Gravity.FILL);
            ambient.setBackground(glow);
        }
        // The default tint mode, SRC_IN, keeps the mask's alpha and takes the colour from the tint.
        glow.setTint(0xFF000000 | color);
    }

    private void transitionAmbient(int color) {
        // A rapid focus change continues from the colour actually displayed on screen.
        stopAmbientAnimation();
        ambient.animate().cancel();
        if (color == ambientColor) {
            ambient.animate().alpha(1f).setDuration(180).start();
            return;
        }
        ambient.animate().alpha(1f).setDuration(180).start();
        ambientAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), ambientColor, color);
        ambientAnimator.setDuration(360);
        ambientAnimator.addUpdateListener(animation -> setAmbient((int) animation.getAnimatedValue()));
        ambientAnimator.start();
    }

    private void stopAmbientAnimation() {
        if (ambientAnimator != null) {
            ambientAnimator.cancel();
            ambientAnimator = null;
        }
    }

    private void hideHero() {
        if (!heroShown) return;
        stopAmbientAnimation();
        heroName.animate().cancel();
        ambient.animate().cancel();
        heroShown = false;
        if (animations) {
            heroName.animate().alpha(0f).setDuration(reducedMotion ? 100 : 160).start();
            ambient.animate().alpha(0f).setDuration(reducedMotion ? 140 : 300).start();
        } else {
            heroName.setAlpha(0f);
            ambient.setAlpha(0f);
        }
    }


    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public void onTileLongPress(int position) {
        if (!editMode) return;
        // Only real dock apps can be re-ordered; a "+" slot has nothing to grab.
        if (position < 0 || position >= adapter.getFavourites().size()) return;
        editSlot = position;
        enterMoveMode();
    }

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        // Horizontal: swallow the key at a row's ends so focus never wraps to the next row or
        // escapes up into the top bar. Mid-row moves fall through to the default focus search.
        if (editMode) {
            if (moveMode) {
                // The tile is grabbed: LEFT/RIGHT shuffle it through the dock, OK drops it.
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { moveDock(-1); return true; }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { moveDock(1); return true; }
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    exitMoveMode();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    exitMoveMode();
                    return true;
                }
                return true;
            }
            // In edit mode the dock tiles are the slot picker.
            if (!editFocusTop && position < COLUMNS) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    // OK also moves into the candidate bar, just like UP.
                    exitMoveMode();
                    if (leftDeleteBtn != null) leftDeleteBtn.setVisibility(View.GONE);
                    editFocusTop = true;
                    topActive = true;
                    topSelected = 0;
                    if (topAdapter != null) topAdapter.setSelected(0);
                    if (topOverlay != null) {
                        topOverlay.scrollToPosition(0);
                        topOverlay.requestFocus();
                    }
                    grid.post(this::scaleEditSlot);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (editSlot == 0) {
                        if (leftDeleteBtn != null) leftDeleteBtn.setVisibility(View.GONE);
                        if (leftApplyBtn != null) leftApplyBtn.requestFocus();
                        grid.post(this::scaleEditSlot);
                        return true;
                    }
                    editSlot = Math.max(0, editSlot - 1);
                    if (leftDeleteBtn != null) {
                        leftDeleteBtn.setVisibility(View.GONE);
                        positionDeleteButton();
                    }
                    focusTile(editSlot);
                    grid.post(this::scaleEditSlot);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    editSlot = Math.min(COLUMNS - 1, editSlot + 1);
                    if (leftDeleteBtn != null) {
                        leftDeleteBtn.setVisibility(View.GONE);
                        positionDeleteButton();
                    }
                    focusTile(editSlot);
                    grid.post(this::scaleEditSlot);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    // Reveal the Delete button; press DOWN again to focus it, then OK to delete.
                    if (leftDeleteBtn != null && editSlot < adapter.getFavourites().size()) {
                        if (leftDeleteBtn.getVisibility() != View.VISIBLE) {
                            // Position deterministically before showing, so it never flashes at the old slot.
                            positionLeftButtons();
                            positionDeleteButton();
                            leftDeleteBtn.setVisibility(View.VISIBLE);
                        } else if (!leftDeleteBtn.isFocused()) {
                            leftDeleteBtn.requestFocus();
                        }
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (leftDeleteBtn != null) leftDeleteBtn.setVisibility(View.GONE);
                    editFocusTop = true;
                    topActive = true;
                    topSelected = 0;
                    if (topAdapter != null) topAdapter.setSelected(0);
                    if (topOverlay != null) {
                        topOverlay.scrollToPosition(0);
                        topOverlay.requestFocus();
                    }
                    grid.post(this::scaleEditSlot);
                    return true;
                }
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) return true;
            return false;
        }
        // Reveal the left-bar editor buttons by navigating left from the first dock tile.
        if (!editMode && position == 0 && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT)) {
            if (!leftButtonsVisible) {
                leftButtonsVisible = true;
                leftButtons.setVisibility(View.VISIBLE);
                leftButtonsFocus = 0;
                return true;
            }
            if (leftButtonsFocus == 0) {
                leftSettingsBtn.requestFocus();
                leftButtonsFocus = 1;
                return true;
            }
            return true;
        }
        if (!editMode && leftButtonsVisible && leftButtonsFocus > 0 && (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            leftButtonsVisible = false;
            leftButtons.setVisibility(View.GONE);
            leftButtonsFocus = 0;
            focusTile(0);
            return true;
        }
        if (!editMode && leftButtonsVisible && leftButtonsFocus == 0 && (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            leftButtonsVisible = false;
            leftButtons.setVisibility(View.GONE);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return GridNavigation.atHorizontalEdge(position, count, COLUMNS,
                    rtlAppGrid, keyCode == KeyEvent.KEYCODE_DPAD_LEFT);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return false;          // dock row: let focus move to the top bar
            target = position - COLUMNS;
            // Up from the first row under the dock: the bar must already be at its resting place,
            // never glide there. Snap instantly (as Home does) and focus the same column, so the
            // jump reads as a plain focus move instead of a catch-up scroll.
            if (target < COLUMNS) {
                focusTile(target, true);
                return true;
            }
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    /** Supersedes an older "focus that tile after the scroll settles" request. */
    /** Drops the parked-dock correction, its tile lost focus before it could finish. */
    private void cancelDockParking() {
        if (dockParking != null) {
            grid.removeOnScrollListener(dockParking);
            dockParking = null;
        }
    }

    private void cancelPendingFocus() {
        if (pendingFocus != null) {
            grid.removeOnScrollListener(pendingFocus);
            pendingFocus = null;
        }
        if (restFocusRunner != null) {
            grid.removeCallbacks(restFocusRunner);
            restFocusRunner = null;
        }
        cancelDockParking();
    }

    /**
     * Focuses the grid itself rather than nothing: with the default FOCUS_AFTER_DESCENDANTS a
     * plain requestFocus lands back on the already-focused card, which then detaches mid scroll
     * and lets restoreDefaultFocus light up a top-bar button ("Вход").
     */
    private void parkFocusOnGrid() {
        int focusability = grid.getDescendantFocusability();
        grid.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        grid.requestFocus();
        grid.setDescendantFocusability(focusability);
    }

    /**
     * Waits (reposting, so no glide is started here) until the grid rests and the tile exists,
     * then puts the focus on it. Only for already-positioned grids such as the instant reset.
     */
    private void focusWhenRest(final int pos) {
        restFocusRunner = new Runnable() {
            @Override
            public void run() {
                if (restFocusRunner == null) return;   // superseded by another request
                if (pos >= adapter.getItemCount()) {
                    restFocusRunner = null;
                    return;
                }
                if (grid.getScrollState() != RecyclerView.SCROLL_STATE_IDLE || grid.isComputingLayout()) {
                    grid.post(this);
                    return;
                }
                RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(pos);
                if (h == null) {
                    grid.post(this);
                    return;
                }
                restFocusRunner = null;
                h.itemView.findViewById(R.id.card).requestFocus();
            }
        };
        grid.post(restFocusRunner);
    }

    private void focusTile(int position) {
        focusTile(position, false);
    }

    /**
     * Focuses a tile. The Home reset ({@code instant}) travels to a known resting place — tile 0
     * with the scroll offset exactly 0 — and must get there with no movement at all: every glide
     * there, however direct, still read on screen as a catch-up run at the end.
     */
    private void focusTile(int position, boolean instant) {
        if (adapter.getItemCount() == 0) {
            btnWifi.requestFocus();
            return;
        }
        // Any older "focus tile X after the scroll settles" request is superseded by this one;
        // letting it fire later would plant the glow on a random app.
        cancelPendingFocus();
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        if (instant) {
            // Put the glow down and park the focus on the grid itself, so nothing detaches
            // mid-reset and drops the focus on a top-bar button (the "Вход" flash).
            
            parkFocusOnGrid();
            // Set the resting place with a plain layout pass: an instant realignment to offset
            // 0, where the dock band rests. The focus (and with it the glow) land afterwards,
            // once the grid is idle there.
            grid.stopScroll();
            ((GridLayoutManager) grid.getLayoutManager()).scrollToPositionWithOffset(0, 0);
            focusWhenRest(pos);
            return;
        }
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: put the glow down and park the focus on the grid itself (no visible
        // highlight) before the grid moves. Leaving the screen with no focused view would make
        // Android's restoreDefaultFocus light up a top-bar button ("Вход") mid-scroll; the
        // requested tile re-takes focus once the scroll settles (or the next layout pass lays it out).
        
        parkFocusOnGrid();
        if (!animations) {
            grid.scrollToPosition(pos);
            grid.post(() -> {
                RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(pos);
                if (h != null) h.itemView.findViewById(R.id.card).requestFocus();
            });
            return;
        }
        grid.addOnScrollListener(pendingFocus = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                pendingFocus = null;
                // Only the tile that was asked for may take focus; taking whatever child happens
                // to be first on screen would plant the glow on a random app.
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                if (h == null) return;
                h.itemView.findViewById(R.id.card).requestFocus();
            }
        });
        // Ride toward the top as one glide to exactly zero. smoothScrollToPosition would stop
        // wherever it aligns the row and race the parking correction in onTileFocused; gliding
        // straight to offset 0 leaves nothing to correct and the dock lands in one motion.
        grid.smoothScrollBy(0, -grid.computeVerticalScrollOffset());
        // The idle listener above ends the run; this fallback covers the rare case where no
        // scroll event ever fires (already at the top but the tile is not laid out yet) and
        // re-posts until the grid is both idle and laid out, so focus lands at rest.
        grid.post(new Runnable() {
            @Override
            public void run() {
                if (pendingFocus == null) return;   // superseded or already taken over
                if (grid.getScrollState() != RecyclerView.SCROLL_STATE_IDLE || grid.isComputingLayout()) {
                    grid.post(this);
                    return;
                }
                RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(pos);
                if (h == null) {
                    grid.post(this);
                    return;
                }
                grid.removeOnScrollListener(pendingFocus);
                pendingFocus = null;
                h.itemView.findViewById(R.id.card).requestFocus();
            }
        });
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateGreeting() {
        // The weekday and date lines are not part of the greeting: they must keep updating
        // whatever the greeting toggle does, so the early return covers the greeting only.
        if (greetingEnabled) {
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            int res;
            if (hour < 5) res = R.string.good_night;
            else if (hour < 12) res = R.string.good_morning;
            else if (hour < 17) res = R.string.good_afternoon;
            else if (hour < 22) res = R.string.good_evening;
            else res = R.string.good_night;
            greeting.setText(res);
            alignGreeting();
        }
        if (shamsi) shamsiDate.setText(Shamsi.today());
        updateDate();
    }

    // ---- weather slide --------------------------------------------------------------------------

    /** Puts the reading on the slide: the temperature on the clock-sized line, the word under it.
     *  Without a connection the crossed cloud takes the temperature's place and the word hides. */
    private void showWeatherText() {
        boolean off = weatherOffline || weather == null;
        weatherTemp.setVisibility(off ? View.GONE : View.VISIBLE);
        weatherIcon.setVisibility(off ? View.VISIBLE : View.GONE);
        if (off || weather == null) {
            weatherCondition.setText("");
            return;
        }
        int t = weather.temperature;
        weatherTemp.setText((t > 0 ? "+" : t < 0 ? "\u2212" : "") + Math.abs(t) + "\u00B0");
        weatherCondition.setText(weather.conditionRes != 0 ? getString(weather.conditionRes) : "");
    }

    /** Debug-only log line: silent in the release installs, where the debuggable flag reads false. */
    private void debugLog(String message) {
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            Log.d("PureGrid", message);
        }
    }

    /** Fetches a reading off the UI thread; working data holds for half an hour, an offline state
     *  retries every minute so the crossed cloud gives way as soon as the route is back. */
    private void fetchWeather() {
        if (!weatherEnabled) return;
        if (SystemClock.elapsedRealtime() - lastWeatherFetchAt < 60_000L) return;   // resume storms
        lastWeatherFetchAt = SystemClock.elapsedRealtime();
        weatherExecutor.execute(() -> {
            Weather.Reading r = Weather.fetch(getApplicationContext());
            boolean off = r == null && Weather.offline(getApplicationContext());
            handler.post(() -> {
                weather = r;
                weatherOffline = off;
                if (weatherShowing) showWeatherText();
                handler.removeCallbacks(weatherFetch);
                handler.postDelayed(weatherFetch,
                        r != null ? 30 * 60_000L : off ? 60_000L : 5 * 60_000L);
            });
        });
    }

    /** Schedules the next weather window: the first of :18, :38, :58 that is still ahead. */
    private void scheduleNextWeatherWindow() {
        handler.removeCallbacks(weatherWindow);
        Calendar now = Calendar.getInstance();
        long into = now.get(Calendar.SECOND) * 1000L + now.get(Calendar.MILLISECOND);
        long delay = -1L;
        for (int at : WEATHER_AT_SECONDS) {
            long d = at * 1000L - into;
            if (d >= 0 && (delay < 0 || d < delay)) delay = d;
        }
        if (delay < 0) delay = 60_000L - into + WEATHER_AT_SECONDS[0] * 1000L;
        handler.postDelayed(weatherWindow, delay + 15);   // a touch past the mark, never before it
    }

    /** The window opens with a reading or, without a connection, with the crossed cloud; without
     *  either it waits for the next mark. */
    private void openWeatherWindow() {
        debugLog("weather window: enabled=" + weatherEnabled
                + " reading=" + (weather != null ? weather.temperature + "deg" : "null")
                + " offline=" + weatherOffline);
        if (!weatherEnabled || (weather == null && !weatherOffline)) {
            scheduleNextWeatherWindow();
            return;
        }
        showWeatherText();
        weatherShowing = true;
        slideClockSlot(true);
        handler.postDelayed(weatherClose, 5000L);
    }

    private void closeWeatherWindow() {
        weatherShowing = false;
        slideClockSlot(false);
        scheduleNextWeatherWindow();
    }

    /**
     * The clock gives way to the weather travelling left to right: the clock leaves through the
     * right edge while the slide (and the word under it) comes in from the left. The return
     * retraces exactly that path — the slide and the word go back out to the left, and the clock
     * rolls home from the right edge it left through. The minute flip happens while the clock is
     * off screen, so it is never seen. The whole showing, transition included, is five seconds
     * from the moment the clock gives way.
     */
    private void slideClockSlot(boolean toWeather) {
        float w = clockSlot.getWidth();
        if (w <= 0f) {
            // Not laid out yet (the very first frames): swap in place, without motion.
            clockFace.setTranslationX(0f);
            weatherFace.setTranslationX(0f);
            weatherFace.setVisibility(toWeather ? View.VISIBLE : View.INVISIBLE);
            weatherWordBox.setVisibility(toWeather ? View.VISIBLE : View.INVISIBLE);
            return;
        }
        View in = toWeather ? weatherFace : clockFace;
        View out = toWeather ? clockFace : weatherFace;
        // The arrival comes from the left; the return comes from the right, retracing the exit.
        float inFrom = toWeather ? -w : w;
        float outTo = toWeather ? w : -w;
        in.animate().cancel();
        out.animate().cancel();
        weatherCondition.animate().cancel();
        in.setTranslationX(inFrom);
        if (in == weatherFace) weatherFace.setVisibility(View.VISIBLE);
        in.animate().translationX(0f)
                .setDuration(450L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        out.animate().translationX(outTo)
                .setDuration(450L)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (out == weatherFace) weatherFace.setVisibility(View.INVISIBLE);
                })
                .start();
        // The word under the row rides in a clipping box whose left edge is the slot's left
        // edge: it emerges through exactly the same line the slide does, and on the way back
        // disappears into it — never popping in or out earlier than the slide. Settled, its
        // first letter sits exactly over the clock's first digit (the greeting's alignment).
        TextView clockView = (TextView) findViewById(R.id.clock);
        weatherCondition.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int wordW = weatherCondition.getMeasuredWidth(), wordH = weatherCondition.getMeasuredHeight();
        weatherCondition.layout(0, 0, wordW, wordH);
        View row = (View) clockSlot.getParent();
        Rect r = new Rect(0, 0, row.getWidth(), row.getHeight());
        root.offsetDescendantRectToMyCoords(row, r);
        int settle = 0;
        int clockInk = inkLeft(clockView), wordInk = inkLeft(weatherCondition);
        if (clockInk >= 0 && wordInk >= 0) settle = clockInk - wordInk;
        int boxTop = r.bottom + (int) (4f * density);
        weatherWordBox.layout(r.left, boxTop, r.left + row.getWidth(), boxTop + wordH);
        float travel = Math.max(w, wordW + Math.abs(settle) + 2f);
        if (toWeather) {
            // No word when the crossed cloud is up: the icon alone is the message.
            if (weatherOffline || weather == null) {
                weatherWordBox.setVisibility(View.INVISIBLE);
            } else {
                weatherCondition.setTranslationX(settle - travel);
                weatherWordBox.setVisibility(View.VISIBLE);
                weatherCondition.animate().translationX(settle)
                        .setDuration(450L)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            }
        } else {
            weatherCondition.animate().translationX(settle - travel)
                    .setDuration(450L)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> weatherWordBox.setVisibility(View.INVISIBLE))
                    .start();
        }
    }

    /**
     * Puts the greeting's first letter exactly over the clock's first digit.
     *
     * The two texts are different type in different sizes, so their side bearings differ: even
     * with the same layout box, the greeting's capital "Д" starts a few pixels inside its box
     * while the clock's digit starts almost at its own, and the word looked indented under the
     * time. The offset is therefore measured from the ink itself - where the first character
     * really starts being drawn - and the greeting is moved by the difference, so the ink lines
     * up and not the boxes.
     */
    private void alignGreeting() {
        // The Hebrew layout centres the greeting above the clock on purpose, so it is left alone
        // there; this is the layout where the greeting sits over the clock.
        if (getResources().getBoolean(R.bool.rtl_menu_bar)) return;
        final TextView clock = (TextView) findViewById(R.id.clock);
        // The texts change (the greeting with the hour, the clock every minute), so this runs
        // again once the new glyphs are laid out.
        greeting.post(() -> {
            int g = inkLeft(greeting);
            int c = inkLeft(clock);
            if (g < 0 || c < 0) return;
            greeting.setTranslationX(c - g);
        });
    }

    /**
     * The x of the first pixel the text draws, relative to its own left edge, or -1 when it
     * draws nothing. The layout box is not enough: a capital letter starts a little inside it,
     * and by a different amount in every font and size.
     */
    private static int inkLeft(TextView text) {
        Layout layout = text.getLayout();
        CharSequence value = text.getText();
        if (layout == null || value == null || layout.getLineCount() == 0) return -1;
        int start = layout.getLineStart(0);
        int end = layout.getLineEnd(0);
        // A leading space has no ink of its own: the first letter is the first drawn character.
        int i = start;
        while (i < end && Character.isWhitespace(value.charAt(i))) i++;
        if (i >= end) return -1;
        // The bounds of a single character, measured from its own origin: that is the side
        // bearing, the gap the font leaves before the letter is actually drawn.
        char[] chars = value.toString().toCharArray();
        Rect bounds = new Rect();
        text.getPaint().getTextBounds(chars, i, 1, bounds);
        return bounds.left;
    }

    /**
     * The date, in the TV's own format, shrunk to the room the top bar leaves. The header shares its row
     * with the three pills, and a long localized date ("ВТОРНИК, 20 ОКТЯБРЯ") would otherwise be cut off
     * with an ellipsis, so the text shrinks instead.
     */
    private void updateDate() {
        TextView date = (TextView) findViewById(R.id.date);
        TextView weekday = (TextView) findViewById(R.id.weekday);
        String pattern = getString(R.string.date_format);
        SimpleDateFormat format;
        try {
            format = new SimpleDateFormat(pattern, Locale.getDefault());
        } catch (IllegalArgumentException e) {
            return;   // a broken pattern would throw every minute; leave the date as it is
        }
        // The view's own textAllCaps does the uppercasing, so the measured text matches what is drawn.
        date.setText(format.format(new Date()));
        // The weekday goes on its own line above the day and the month.
        weekday.setText(weekdayName());
        // Size for the widest date the format can produce, so the size does not jump from day to day.
        date.post(() -> {
            float room = date.getWidth();
            if (room <= 0) return;
            TextPaint paint = date.getPaint();
            float size = dateBaseSizePx;
            String widest = widestDate(format, paint);
            if (widest == null) return;
            // Letter spacing adds a gap per character on top of the measured width.
            float perChar = date.getLetterSpacing() * size;
            float wanted = paint.measureText(widest) + perChar * widest.length();
            if (wanted > room && wanted > 0) {
                float scaled = Math.max(9f, size * room / wanted);
                date.setTextSize(TypedValue.COMPLEX_UNIT_PX, scaled);
            }
        });
    }

    /**
     * The weekday in the TV's own language ("ВТОРНИК", "MONDAY"). The date format is localized, but
     * the weekday is not always the first field in it (Turkish and Chinese put it last), so it is
     * asked for on its own.
     */
    private String weekdayName() {
        try {
            return new SimpleDateFormat("EEEE", Locale.getDefault()).format(new Date());
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** The longest date the format can produce: every month name with the longest day number. Each
     *  month is set explicitly and sampled at its own longest day — advancing by adding a month to
     *  a 31-day date silently skipped the short ones (February, April, June, September and November
     *  were never measured at all, and September is the widest month name in Russian). */
    private String widestDate(SimpleDateFormat format, TextPaint paint) {
        String widest = null;
        float width = 0;
        Calendar cal = Calendar.getInstance();
        for (int i = 0; i < 12; i++) {
            cal.set(Calendar.DAY_OF_MONTH, 1);
            cal.set(Calendar.MONTH, i);
            cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH));
            String candidate = format.format(cal.getTime());
            float w = paint.measureText(candidate);
            if (widest == null || w > width) {
                widest = candidate;
                width = w;
            }
        }
        return widest;
    }

    /** Kills background processes of all other apps and reports freed memory. */
    private void clearBackgroundProcesses() {
        // Hand the focus to the first dock app at once: disabling the cleaned-edge button while it
        // holds the focus drops that focus, and once the cleaner app is gone the window focus
        // restore would otherwise hand a top-bar button ("Вход") the focus.
        focusTile(0);
        // On Digma there is a system cleaner app that can actually free cache.
        try {
            if (getPackageManager().getPackageInfo("com.smartdevice.dailymanage", 0) != null) {
                Intent i = getPackageManager().getLaunchIntentForPackage("com.smartdevice.dailymanage");
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    Toast.makeText(this, R.string.cleaning_started, Toast.LENGTH_SHORT).show();
                    btnClear.setEnabled(false);
                    handler.postDelayed(() -> btnClear.setEnabled(true), 7000);
                    return;
                }
            }
        } catch (Exception ignored) {
        }
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;
        btnClear.setEnabled(false);
        btnClear.animate().scaleX(0.9f).scaleY(0.9f).setDuration(120).withEndAction(() ->
                btnClear.animate().scaleX(1f).scaleY(1f).setDuration(120).start()).start();
        handler.postDelayed(() -> btnClear.setEnabled(true), 3000);
        final long availBefore;
        ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(before);
        availBefore = before.availMem;
        backgroundExecutor.execute(() -> {
            boolean denied = false;
            try {
                for (ApplicationInfo app : getPackageManager().getInstalledApplications(0)) {
                    if (app.packageName.equals(getPackageName())) continue;
                    try {
                        am.killBackgroundProcesses(app.packageName);
                    } catch (SecurityException e) {
                        denied = true;
                    } catch (Exception ignored) {
                    }
                }
            } catch (SecurityException e) {
                denied = true;
            }
            ActivityManager.MemoryInfo after = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(after);
            final boolean deniedFinal = denied;
            final long availAfter = after.availMem;
            handler.post(() -> {
                if (deniedFinal && availAfter <= availBefore) {
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                    return;
                }
                // No freed-MB figure: availMem moves on its own while the loop runs, so a number
                // said more about the system than about the clean. A plain confirmation instead.
                Toast.makeText(this, R.string.cleaned, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void startWifiAnimation() {
        if (wifiAnimating) return;
        wifiAnimating = true;
        final int[] frames = {R.drawable.ic_wifi_1, R.drawable.ic_wifi_2, R.drawable.ic_wifi};
        wifiAnimRunnable = new Runnable() {
            int i = 0;
            @Override
            public void run() {
                if (!wifiAnimating) return;
                btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(frames[i % frames.length], 0, 0, 0);
                i++;
                handler.postDelayed(this, 400);
            }
        };
        handler.post(wifiAnimRunnable);
    }

    private void stopWifiAnimation() {
        wifiAnimating = false;
        if (wifiAnimRunnable != null) handler.removeCallbacks(wifiAnimRunnable);
        wifiAnimRunnable = null;
    }

    private void startEthAnimation() {
        if (ethAnimating) return;
        ethAnimating = true;
        final int[] frames = {R.drawable.ic_ethernet_connecting, R.drawable.ic_ethernet};
        ethAnimRunnable = new Runnable() {
            int i = 0;
            @Override
            public void run() {
                if (!ethAnimating) return;
                btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(frames[i % frames.length], 0, 0, 0);
                i++;
                handler.postDelayed(this, 500);
            }
        };
        handler.post(ethAnimRunnable);
    }

    private void stopEthAnimation() {
        ethAnimating = false;
        if (ethAnimRunnable != null) handler.removeCallbacks(ethAnimRunnable);
        ethAnimRunnable = null;
    }

    private void positionWifiTooltip() {
        if (wifiTooltip == null || btnWifi == null) return;
        int[] loc = new int[2];
        btnWifi.getLocationInWindow(loc);
        int[] rootLoc = new int[2];
        root.getLocationInWindow(rootLoc);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) wifiTooltip.getLayoutParams();
        wifiTooltip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int tooltipW = wifiTooltip.getMeasuredWidth();
        int x = loc[0] - rootLoc[0] + (btnWifi.getWidth() - tooltipW) / 2;
        // Keep a long name (and its state line) inside the screen even at the right edge of the bar.
        int edge = (int) (8 * density);
        if (root.getWidth() > 0) x = Math.max(edge, Math.min(x, root.getWidth() - tooltipW - edge));
        lp.leftMargin = x;
        lp.topMargin = loc[1] - rootLoc[1] + btnWifi.getHeight() + (int) (4 * density);
        wifiTooltip.setLayoutParams(lp);
    }

    /**
     * Refreshes the account pill off the main thread: the signed-in Google account (if any) and,
     * when reachable in the contacts provider, its avatar. Everything local — no network, no
     * dialogs, nothing the user has to confirm. Without GET_ACCOUNTS the pill keeps drawing the
     * mono "G".
     */
    private void updateAccount() {
        if (btnAccount == null) return;
        backgroundExecutor.execute(() -> {
            String name = null;
            Bitmap photo = null;
            try {
                Account[] accounts = AccountManager.get(this).getAccountsByType("com.google");
                if (accounts.length > 0) {
                    name = accounts[0].name;
                    photo = readAccountAvatar(accounts[0]);
                }
            } catch (Exception ignored) {
            }
            final String nameFinal = name;
            final Bitmap photoFinal = photo;
            handler.post(() -> {
                if (btnAccount == null) return;
                btnAccount.setAccount(photoFinal, nameFinal);
            });
        });
    }

    /** The account's own avatar from the contacts provider, or null. Never touches other raw contacts of the account. */
    private Bitmap readAccountAvatar(Account acct) {
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) return null;
        try {
            // Only the account's own profile row: any other raw contact could surface a synced
            // friend's face instead of the user's. TVs rarely sync contacts, so this is usually empty
            // and the pill falls back to the account's letter.
            Cursor c = getContentResolver().query(
                    Uri.withAppendedPath(ContactsContract.Profile.CONTENT_URI, "rawContacts"),
                    new String[]{ContactsContract.RawContacts._ID},
                    ContactsContract.RawContacts.ACCOUNT_NAME + "=? AND "
                            + ContactsContract.RawContacts.ACCOUNT_TYPE + "=?",
                    new String[]{acct.name, "com.google"}, null);
            if (c == null) return null;
            int rawId = c.moveToFirst() ? c.getInt(0) : 0;
            c.close();
            if (rawId == 0) return null;
            int cid = contactId(rawId);
            if (cid == 0) return null;
            InputStream in = getContentResolver().openInputStream(ContentUris.withAppendedId(
                    ContactsContract.Contacts.CONTENT_URI, cid).buildUpon()
                    .appendPath(ContactsContract.Contacts.Photo.CONTENT_DIRECTORY).build());
            Bitmap b = in != null ? BitmapFactory.decodeStream(in) : null;
            if (in != null) in.close();
            return b;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** The contact row a raw contact belongs to, in a form the photo URI accepts, else 0. */
    private int contactId(int rawId) {
        Cursor c = getContentResolver().query(
                ContactsContract.RawContacts.CONTENT_URI,
                new String[]{ContactsContract.RawContacts.CONTACT_ID},
                ContactsContract.RawContacts._ID + "=?",
                new String[]{String.valueOf(rawId)}, null);
        int id = 0;
        if (c != null) {
            if (c.moveToFirst()) id = c.getInt(0);
            c.close();
        }
        return id;
    }

    /**
     * Shows the network name (or connection type), tints the icon amber when the connection has no
     * internet access, and paints the VPN capsule green, amber (blocked) or white (off).
     */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false, vpnBlocked = false;
        int icon = R.drawable.ic_wifi;
        boolean warnIcon = false;
        boolean named = true;
        String status = "";
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, ethernet = false;
            boolean wifiValidated = false, ethernetValidated = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                boolean wifiCaps = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                boolean ethCaps = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                // A cable or Wi-Fi counts as connected even when the network has no internet: that
                // is exactly the "connected without access" state the pill reports.
                if (wifiCaps || ethCaps) online = true;
                else if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                else online = true;
                if (ethCaps) {
                    ethernet = true;
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        ethernetValidated = true;
                    }
                }
                if (wifiCaps) {
                    wifi = true;
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        wifiValidated = true;
                    }
                }
            }
            // Connected means a cable or Wi-Fi is up; access means at least one path actually reaches
            // the internet. Android does not validate VPN networks against the internet, so a VPN is
            // reported as blocked when the connection under it has no validated access.
            boolean connected = wifi || ethernet;
            boolean access = wifiValidated || ethernetValidated;
            boolean noAccess = connected && !access;
            vpnBlocked = vpn && !access;
            if (vpnBlocked) status = getString(R.string.vpn_traffic_blocked);
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
                vpnBlocked = false;
                status = "";
                icon = R.drawable.ic_wifi_off;
                stopWifiAnimation();
                stopEthAnimation();
            } else if (noAccess) {
                // The connection is there but nothing reaches the internet: keep the connection's
                // own icon and tint it amber instead of animating it as if it were still connecting.
                stopWifiAnimation();
                stopEthAnimation();
                if (ethernet) {
                    text = getString(R.string.ethernet);
                    icon = R.drawable.ic_ethernet;
                    // A cable has no network name: the tooltip under the pill is just the state.
                    named = false;
                } else {
                    String ssid = readableWifiName();
                    if (ssid != null) text = ssid;
                    icon = wifiSignalIcon();
                }
                warnIcon = true;
                status = getString(R.string.connected_no_access)
                        + (status.isEmpty() ? "" : "\n" + status);
            } else if (ethernet && (ethernetValidated || !wifiValidated)) {
                text = getString(R.string.ethernet);
                if (!ethernetValidated) {
                    icon = R.drawable.ic_ethernet;
                    startEthAnimation();
                } else {
                    stopEthAnimation();
                    icon = R.drawable.ic_ethernet;
                }
                stopWifiAnimation();
            } else if (wifi) {
                if (!wifiValidated) {
                    icon = R.drawable.ic_wifi;
                    startWifiAnimation();
                } else {
                    stopWifiAnimation();
                    stopEthAnimation();
                    String ssid = readableWifiName();
                    if (ssid != null) text = ssid;
                    icon = wifiSignalIcon();
                }
            }
        } catch (Exception ignored) {
        }
        // With a VPN app installed, a VPN that is down matters too: show a white "VPN off" capsule
        // then. Without a VPN app at all the pill shows only the connection.
        boolean vpnDown = !vpn && online && vpnInstalled();
        wifiNetworkName = named ? text : "";
        wifiStatus = status;
        TextPaint paint = btnWifi.getPaint();
        // The pill's own width is capped, so the text has to be cut here: the TextView has no ellipsize
        // of its own, which would otherwise cut the badge (a ReplacementSpan) away instead of the name.
        float room = btnWifi.getMaxWidth() - btnWifi.getCompoundPaddingLeft() - btnWifi.getCompoundPaddingRight()
                - 2 * density;
        btnWifi.setText("");
        if (vpn || vpnDown) {
            // Network name, then the VPN capsule: green with an open lock while the VPN is up, amber
            // with an open lock while its traffic is blocked, and white with a closed lock while it is
            // down. The word is always "VPN"; the lock and the colour say which state it is in.
            int state = vpn ? (vpnBlocked ? VpnBadge.BLOCKED : VpnBadge.UP) : VpnBadge.OFF;
            Drawable lock = getResources().getDrawable(vpn ? R.drawable.ic_lock_open : R.drawable.ic_lock, getTheme());
            String badge = getString(R.string.vpn);
            VpnBadge span = new VpnBadge(lock, state, density);
            float badgeW = span.getSize(paint, badge, 0, badge.length(), null);
            float nameW = paint.measureText(text);
            float space = paint.measureText(" ");
            text = TextUtils.ellipsize(text, paint, Math.max(0, room - space - badgeW),
                    TextUtils.TruncateAt.END).toString();
            // Keep a zero-width space so the ReplacementSpan is a partial span (as in the original
            // layout); a span covering the whole text is not drawn by TextView.
            SpannableString label = new SpannableString("\u200B" + badge);
            label.setSpan(span, 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            btnWifi.setText(label);
        } else {
            // button stays compact; network name is shown in the tooltip
        }
        btnWifi.setCompoundDrawableTintList(pillIconTint(warnIcon));
        btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        // The state can change while the pill is focused (validation arrives, a VPN comes up).
        if (btnWifi.hasFocus()) wifiTooltip.setText(wifiTooltipText());
    }

    /** The tooltip under the network pill: the network name, then any state line. */
    private String wifiTooltipText() {
        String tip = wifiNetworkName;
        if (tip.isEmpty() && wifiStatus.isEmpty()) tip = getString(R.string.wifi);
        if (wifiStatus.isEmpty()) return tip;
        return tip.isEmpty() ? wifiStatus : tip + "\n" + wifiStatus;
    }

    /** White icons normally (dark while the pill is focused), amber while there is no internet access. */
    private ColorStateList pillIconTint(boolean warn) {
        return new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[0]},
                new int[]{PILL_INK_FOCUSED, warn ? WARN_COLOR : Color.WHITE});
    }

    /** The Wi-Fi network name when Android lets us read it, otherwise null. */
    private String readableWifiName() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
        String ssid = info != null ? info.getSSID() : null;
        if (ssid == null) return null;
        ssid = ssid.replace("\"", "").trim();
        boolean readable = !ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x");
        if (readable) return ssid;
        // Android 10 hides the SSID ("<unknown ssid>") while location services are off, and the Xiaomi
        // firmware keeps switching the services back off on every restart of the home app. When the
        // shell pre-granted WRITE_SECURE_SETTINGS, switch them back on and re-read the name once. Only
        // fires while the mode is 0, so it can't loop. FINE location (GPS) triggers a Google consent
        // dialog, so stay on the network-only "battery saving" level: it still exposes the SSID.
        int locationMode = 0;
        try {
            locationMode = Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE, 0);
        } catch (Exception ignored) {
        }
        if (locationMode == 0
                && checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                        == PackageManager.PERMISSION_GRANTED) {
            try {
                Settings.Secure.putInt(getContentResolver(), Settings.Secure.LOCATION_MODE,
                        Settings.Secure.LOCATION_MODE_BATTERY_SAVING);
                handler.postDelayed(this::updateWifiLabel, 2500);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** The Wi-Fi icon with the current signal level, for the connected (not connecting) state. */
    private int wifiSignalIcon() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
        int level = 3;
        if (info != null) {
            try {
                level = WifiManager.calculateSignalLevel(info.getRssi(), 4);
            } catch (Exception ignored) {
            }
        }
        if (level <= 1) return R.drawable.ic_wifi_1;
        if (level == 2) return R.drawable.ic_wifi_2;
        return R.drawable.ic_wifi;
    }

    /**
     * Whether the configured VPN app is installed. With no VPN app the network pill does not show a
     * "VPN off" capsule at all; once the user installs one the badge appears as before.
     */
    private boolean vpnInstalled() {
        if (vpnInstalledCache != null) return vpnInstalledCache;
        boolean installed = false;
        if (!vpnApp.isEmpty()) {
            PackageManager pm = getPackageManager();
            try {
                pm.getPackageInfo(vpnApp, 0);
                installed = true;
            } catch (Exception ignored) {
            }
            if (!installed) {
                // Android 11+ may hide a package the launcher does not otherwise see; its VpnService
                // still identifies it as long as the manifest <queries> covers android.net.VpnService.
                try {
                    for (ResolveInfo r : pm.queryIntentServices(new Intent("android.net.VpnService"), 0)) {
                        if (r.serviceInfo != null && vpnApp.equals(r.serviceInfo.packageName)) {
                            installed = true;
                            break;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        vpnInstalledCache = installed;
        return installed;
    }

    /**
     * The network pill. With a VPN app configured and one that can be toggled in the background, the
     * pill switches the VPN on and off without opening the app; otherwise it opens the VPN app, and
     * with no VPN app at all it opens Wi-Fi settings. Hold the pill for Wi-Fi settings either way.
     */
    private void openNetworkPill() {
        if (vpnApp.isEmpty()) {
            open(systemIntent(Settings.ACTION_WIFI_SETTINGS));
            return;
        }
        if (toggleVpn()) return;
        // No background control on this VPN app: open it as before.
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(vpnApp);
        open(i != null ? i : systemIntent(Settings.ACTION_WIFI_SETTINGS));
    }

    /**
     * Switches the VPN on or off in the background and reports whether that worked. v2RayTun answers
     * the same broadcast its home-screen widget answers, and handles the up/down state itself, so the
     * app never comes to the foreground. Returns false when the app cannot be toggled this way.
     */
    private boolean toggleVpn() {
        String action = vpnToggleAction;
        if (action.isEmpty()) return false;
        PackageManager pm = getPackageManager();
        Intent query = new Intent(action);
        ComponentName target = null;
        for (ResolveInfo r : pm.queryBroadcastReceivers(query, 0)) {
            // Only the configured VPN app may be asked to toggle: another app answering the same
            // broadcast must not be triggered by this press.
            if (!vpnApp.equals(r.activityInfo.packageName)) continue;
            target = new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
            break;
        }
        if (target == null) return false;
        // Send it to that component, so no other app can pick it up.
        Intent send = new Intent(action).setComponent(target);
        try {
            sendBroadcast(send);
        } catch (Exception e) {
            return false;
        }
        // The VPN takes a moment to come up; the badge follows the network callbacks.
        handler.postDelayed(this::updateWifiLabel, 1200);
        handler.postDelayed(this::updateWifiLabel, 4000);
        return true;
    }


    /**
     * China-market MIUI TV firmware answers no standard Settings intents at all; open Xiaomi's own
     * settings app (com.xiaomi.mitv.settings) there, so the Settings and Wi-Fi pills keep working.
     * On firmware with a standard settings handler the behaviour is unchanged.
     */
    private Intent systemIntent(String standardAction) {
        Intent i = new Intent(standardAction);
        if (i.resolveActivity(getPackageManager()) != null) return i;
        i = new Intent().setComponent(new ComponentName(
                "com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity"));
        if (i.resolveActivity(getPackageManager()) != null) return i;
        return new Intent(standardAction);   // nothing found: let open() show the can't-open toast
    }

    /** Hides the Inputs pill when it would have nothing to list: streaming boxes have no HDMI-in, AV or tuner. */
    private void updateInputsButton() {
        boolean any = liveTvIntent() != null || !sourcePanelService.isEmpty();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput()) {
                    any = true;
                    break;
                }
            }
        }
        btnInputs.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9). */
    private void openSourcePanel() {
        Intent i = new Intent(sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(sourcePanelService);
        if (c != null) i.setComponent(c);
        try {
            startService(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Switches to a passthrough input (HDMI, AV). The standard way is ACTION_VIEW on its channel URI,
     *  which the Live TV app handles; where nothing takes that, the configured `input_action` is sent
     *  with the same URI instead (XGIMI's HDMI player only answers com.xgimi.action.hdmiPlayer). */
    private void openInput(String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
            return;
        } catch (ActivityNotFoundException e) {
            if (inputAction.isEmpty()) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        open(new Intent(inputAction).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = glassPopup(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    if (tunerId == null || (hw && !tunerIsHw) || (hw == tunerIsHw && info.getId().compareTo(tunerId) < 0)) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                    }
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> openInput(id));
            }
        }
        // Nothing to list where there is no TvInputManager: the TV's own source panel switches inputs.
        if (rows.getChildCount() == 0 && !sourcePanelService.isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, this::openSourcePanel);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            liveTv = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, panel.getMeasuredWidth()));

        int alignment = getResources().getBoolean(R.bool.rtl_menu_bar) ? Gravity.END : Gravity.START;
        dropIn(popup, panel, anchor, alignment);
        rows.post(() -> rows.getChildAt(0).requestFocus());
    }

    private PopupWindow glassPopup(View panel, int width) {
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
        });
        openPopup = popup;
        return popup;
    }

    /** Drop in: fade and unfold from the top edge, hanging under the anchor pill. */
    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }

    // ---- launcher settings and module cards -------------------------------------------------------

    /** Keep a normal Settings click for the TV; the launcher's choices live behind hold OK. */
    private void showSettingsMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (200 * density));
        // System settings open first: press OK again to drill straight into them from page one.
        addSettingsMenuRow(rows, popup, R.string.system_settings, () -> open(systemIntent(Settings.ACTION_SETTINGS)));
        addSettingsMenuRow(rows, popup, R.string.launcher_settings, () -> startActivityForResult(
                new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST));
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (200 * density), panel.getMeasuredWidth()));
        dropIn(popup, panel, anchor, Gravity.END);
        // The popup's first layout takes the focus for its own root; asking for the first row
        // before that pass would be overwritten and the owner's first OK would only move focus.
        rows.post(() -> rows.getChildAt(0).requestFocus());
    }

    private void addSettingsMenuRow(LinearLayout rows, PopupWindow popup, int title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        // The OK-up after opening the menu must not activate its first row.
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            boolean activate = pressed[0];
            pressed[0] = false;
            if (activate) {
                popup.dismiss();
                action.run();
            }
            return true;
        });
        rows.addView(row);
    }

    /**
     * Asks about the next card that has no decision for its current signing key. One prompt at a
     * time, only over a laid out grid with window focus (not under a popup or another app), and
     * the next one follows as soon as this one is answered. Nothing is shown until the owner says Show.
     */
    private void showCardPrompt() {
        if (!moduleCards || !tilesReady || promptsPaused || cardPrompt != null || !hasWindowFocus() || isFinishing()) return;
        if (cards.isEmpty() || !CardFeature.working()) return;
        CardFeature.run(() -> {
            showCardPromptNow();
            return null;
        }, null);
    }

    private void showCardPromptNow() {
        final Cards.Card card = Cards.firstPending(cards);
        if (card == null) return;

        View panel = getLayoutInflater().inflate(R.layout.dialog_card_prompt, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.card_prompt_title, card.appLabel));
        ((TextView) panel.findViewById(R.id.body)).setText(card.state == Cards.State.RESIGNED
                ? getString(R.string.card_prompt_resigned, card.appLabel)
                : card.state == Cards.State.RELABELLED
                ? getString(R.string.card_prompt_relabelled, card.appLabel)
                : getString(R.string.card_prompt_body, card.appLabel));
        TextView names = panel.findViewById(R.id.names);
        if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) names.setVisibility(View.GONE);
        else names.setText(getString(R.string.card_prompt_names, card.label, card.appLabel));
        ((TextView) panel.findViewById(R.id.identity)).setText(
                getString(R.string.card_prompt_identity, card.pkg, Cards.shortDigest(card.digest)));
        TextView keep = panel.findViewById(R.id.btnKeep);
        TextView show = panel.findViewById(R.id.btnShow);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (440 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        // One decision per prompt: the buttons and Back (cancel) all end here.
        final boolean[] decided = {false};
        View.OnClickListener choose = v -> {
            if (decided[0]) return;
            decided[0] = true;
            cardPrompt = null;
            dialog.dismiss();
            decideCard(card, v.getId() == R.id.btnShow);   // guards its own card calls; the tile reload stays outside
            restoreFocus();   // the tiles changed under the prompt; this also chains the next prompt
        };
        keep.setOnClickListener(choose);
        show.setOnClickListener(choose);
        dialog.setOnCancelListener(d -> choose.onClick(keep));
        dialog.setOnDismissListener(d -> {
            if (cardPrompt == dialog) cardPrompt = null;
        });
        // Left and Right move between the two buttons; Up and Down stay put; the ends don't wrap.
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == R.id.btnKeep;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnShow;
        };
        keep.setOnKeyListener(keys);
        show.setOnKeyListener(keys);
        cardPrompt = dialog;
        dialog.show();
        keep.requestFocus();
    }

    /** Writes the owner's decision and rebuilds the tiles; focus is left where it is. */
    private void decideCard(Cards.Card card, boolean show) {
        boolean saved = CardFeature.run(() -> Cards.decide(Cards.storeFile(this), card, show), false);
        if (!saved) {
            Toast.makeText(this, R.string.card_not_saved, Toast.LENGTH_SHORT).show();
            promptsPaused = true;
        }
        reloadTiles();
    }

    private void toggleHideBelow() {
        hideBelow = !hideBelow;
        getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).edit().putBoolean("hide_below", hideBelow).apply();
        leftHideBtn.setImageResource(hideBelow ? R.drawable.ic_eye : R.drawable.ic_eye_off);
        adapter.setHideBelow(hideBelow);
    }

    private void enterEditMode() {
        exitMoveMode();
        leftButtonsVisible = false;
        leftButtons.setVisibility(View.VISIBLE);
        leftSettingsBtn.setVisibility(View.GONE);
        leftHideBtn.setVisibility(View.GONE);
        leftApplyBtn.setVisibility(View.VISIBLE);
        if (leftDeleteBtn != null) leftDeleteBtn.setVisibility(View.GONE);
        leftButtonsFocus = 3;
        editMode = true;
        editFocusTop = false;
        topActive = false;
        topSelected = 0;
        editSlot = 0;
        editCandidate = null;
        hideHero();
        
        adapter.editing = true;
        adapter.setHideBelow(true);
        reloadTiles();
        applyDockCentering();
        positionLeftButtons();
        buildTopOverlay();
        if (topOverlay != null) {
            topOverlay.setVisibility(View.VISIBLE);
        }
        grid.post(() -> {
            focusTile(0, true);
            scaleEditSlot();
        });
    }

    private void applyEdit() {
        List<String> favs = adapter.getFavourites();
        getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).edit()
                .putString("dock_apps", TextUtils.join(",", favs)).apply();
        storedDock = new ArrayList<>(favs);
        exitEditMode();
    }

    private void deleteEditSlot() {
        int real = adapter.getFavourites().size();
        // Nothing to delete on an empty "+" slot.
        if (editSlot < 0 || editSlot >= real) {
            if (leftDeleteBtn != null) { leftDeleteBtn.setVisibility(View.GONE); positionLeftButtons(); }
            return;
        }
        adapter.removeDockApp(editSlot);
        if (leftDeleteBtn != null) { leftDeleteBtn.setVisibility(View.GONE); positionLeftButtons(); }
        applyDockCentering();
        int count = adapter.getItemCount();
        if (editSlot >= count) editSlot = Math.max(0, count - 1);
        // Stay in the editor: hand focus to the tile that took the slot (an app or the new "+").
        grid.requestFocus();
        focusEditSlot();
    }

    /** Focuses the dock slot being configured, after the grid has laid its (possibly new) tiles out. */
    private void focusEditSlot() { focusEditSlot(2); }

    private void focusEditSlot(int tries) {
        grid.post(() -> {
            RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(editSlot);
            if (h != null) {
                h.itemView.findViewById(R.id.card).requestFocus();
                scaleEditSlot();
            } else if (tries > 0) {
                focusEditSlot(tries - 1);
            }
        });
    }

    /** Grabs the slot's app: LEFT/RIGHT now shuffle it through the dock until OK drops it. */
    private void enterMoveMode() {
        if (!editMode) return;
        if (editSlot < 0 || editSlot >= adapter.getFavourites().size()) return;
        moveMode = true;
        if (leftDeleteBtn != null) leftDeleteBtn.setVisibility(View.GONE);
        updateMoveArrows();
    }

    private void exitMoveMode() {
        moveMode = false;
        if (leftArrow != null) leftArrow.setVisibility(View.GONE);
        if (rightArrow != null) rightArrow.setVisibility(View.GONE);
    }

    /** Moves the grabbed app one slot and keeps the card (and the direction hints) with it. */
    private void moveDock(int delta) {
        int to = editSlot + delta;
        if (!adapter.moveDockApp(editSlot, to)) return;
        editSlot = to;
        focusEditSlot();
        updateMoveArrows();
    }

    /** Shows a chevron only on the sides the grabbed app can still move to. */
    private void updateMoveArrows() {
        if (leftArrow == null || rightArrow == null) return;
        int real = adapter.getFavourites().size();
        boolean active = editMode && moveMode && editSlot >= 0 && editSlot < real;
        if (!active) {
            leftArrow.setVisibility(View.GONE);
            rightArrow.setVisibility(View.GONE);
            return;
        }
        leftArrow.setVisibility(editSlot > 0 ? View.VISIBLE : View.GONE);
        rightArrow.setVisibility(editSlot < real - 1 ? View.VISIBLE : View.GONE);
        positionMoveArrows();
    }

    /** Places the chevrons in the gaps on either side of the grabbed card, clear of every tile. */
    private void positionMoveArrows() {
        if (!moveMode || leftArrow == null || rightArrow == null) return;
        if (grid.getWidth() <= 0) { grid.post(this::positionMoveArrows); return; }
        int inner = grid.getWidth() - grid.getPaddingLeft() - grid.getPaddingRight();
        int cell = COLUMNS > 0 ? inner / COLUMNS : inner;
        int tileW = AppAdapter.tileWidthPx(inner, COLUMNS, density);
        int tileH = AppAdapter.tileHeightPx(tileW);
        int[] gl = new int[2];
        int[] rl2 = new int[2];
        grid.getLocationInWindow(gl);
        root.getLocationInWindow(rl2);
        int cardTop = gl[1] - rl2[1] + grid.getPaddingTop() + (int) (14 * density);
        int cardCenterY = cardTop + tileH / 2;
        // The grabbed card is drawn 10% wider than its cell, so its edge reaches into the gap.
        // Shift the hint by the same bleed to keep the chevron centred in the visible gap.
        int bleed = Math.round((AppAdapter.FOCUS_SCALE - 1f) * tileW / 2f);
        int cellLeft = gl[0] - rl2[0] + grid.getPaddingLeft() + editSlot * cell;
        if (leftArrow.getVisibility() == View.VISIBLE) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) leftArrow.getLayoutParams();
            lp.leftMargin = cellLeft - bleed - arrowSizePx / 2;
            lp.topMargin = cardCenterY - arrowSizePx / 2;
            leftArrow.setLayoutParams(lp);
        }
        if (rightArrow.getVisibility() == View.VISIBLE) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) rightArrow.getLayoutParams();
            lp.leftMargin = cellLeft + cell + bleed - arrowSizePx / 2;
            lp.topMargin = cardCenterY - arrowSizePx / 2;
            rightArrow.setLayoutParams(lp);
        }
    }

    private void exitEditMode() {
        exitMoveMode();
        editMode = false;
        if (topOverlay != null) topOverlay.setVisibility(View.GONE);
        leftApplyBtn.setVisibility(View.GONE);
        if (leftDeleteBtn != null) leftDeleteBtn.setVisibility(View.GONE);
        leftSettingsBtn.setVisibility(View.VISIBLE);
        leftHideBtn.setVisibility(View.VISIBLE);
        leftButtons.setVisibility(View.GONE);
        leftButtonsVisible = false;
        leftButtonsFocus = 0;
        adapter.editing = false;
        // Leave the editor without saving: drop any unsaved add/remove and show the dock that is
        // actually stored (applyEdit persists it and keeps storedDock in step, so this is the same
        // list there — no config.json re-read on the UI thread).
        adapter.setFavourites(new ArrayList<>(storedDock));
        adapter.setHideBelow(hideBelow);
        reloadTiles();
        positionLeftButtons();
        focusTile(0);
    }

    /** Places the Delete button centred under the dock slot being configured. */
    private void positionDeleteButton() {
        if (leftDeleteBtn == null) return;
        if (grid.getWidth() <= 0) { grid.post(this::positionDeleteButton); return; }
        int inner = grid.getWidth() - grid.getPaddingLeft() - grid.getPaddingRight();
        int cell = COLUMNS > 0 ? inner / COLUMNS : inner;
        int tileW = AppAdapter.tileWidthPx(inner, COLUMNS, density);
        int tileH = AppAdapter.tileHeightPx(tileW);
        int[] gl = new int[2];
        int[] rl2 = new int[2];
        grid.getLocationInWindow(gl);
        root.getLocationInWindow(rl2);
        int gridLeft = gl[0] - rl2[0];
        int gridTop = gl[1] - rl2[1];
        int cardLeft = gridLeft + grid.getPaddingLeft() + editSlot * cell + (cell - tileW) / 2;
        int cardTop = gridTop + grid.getPaddingTop() + (int) (14 * density);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) leftDeleteBtn.getLayoutParams();
        lp.leftMargin = cardLeft + (tileW - leftBtnSizePx) / 2;
        lp.topMargin = cardTop + tileH + (int) (22 * density);
        leftDeleteBtn.setLayoutParams(lp);
    }

    /** Centres a short dock row (normal mode only); the editor keeps the full width for "+" slots. */
    private void applyDockCentering() {
        int fullInner = grid.getWidth();
        if (fullInner <= 0) return;
        int dockCount = editMode ? COLUMNS : adapter.getFavourites().size();
        int leftPad = 0, rightPad = 0;
        if (dockCount > 0 && dockCount < COLUMNS) {
            int cell = fullInner / COLUMNS;
            int pad = (fullInner - dockCount * cell) / 2;
            leftPad = pad;
            rightPad = pad;
        }
        if (grid.getPaddingLeft() != leftPad || grid.getPaddingRight() != rightPad) {
            grid.setPadding(leftPad, grid.getPaddingTop(), rightPad, grid.getPaddingBottom());
        }
    }

    /** Measures the dock bar's centre from the first dock tile and re-centres the left buttons. */
    private void centerLeftButtons() {
        if (leftButtons == null || grid.getChildCount() == 0) return;
        View first = grid.getChildAt(0);
        if (first == null) return;
        int[] cl = new int[2];
        int[] rl2 = new int[2];
        first.getLocationInWindow(cl);
        root.getLocationInWindow(rl2);
        int innerW = grid.getWidth() - grid.getPaddingLeft() - grid.getPaddingRight();
        int tileH = AppAdapter.tileHeightPx(AppAdapter.tileWidthPx(innerW, COLUMNS, density));
        int cardTop = cl[1] - rl2[1] + (int) (8 * density);
        int center = cardTop + tileH / 2;
        if (center == leftBarCenter) return;   // no loop
        leftBarCenter = center;
        positionLeftButtons();
    }

    /** In the editor, keeps the dock slot being configured enlarged, even while the top bar has focus. */
    private void scaleEditSlot() {
        if (!editMode) return;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View item = grid.getChildAt(i);
            int pos = grid.getChildAdapterPosition(item);
            View card = item.findViewById(R.id.card);
            if (card == null) continue;
            boolean on = pos == editSlot;
            card.animate().cancel();
            card.animate().scaleX(on ? AppAdapter.FOCUS_SCALE : 1f)
                    .scaleY(on ? AppAdapter.FOCUS_SCALE : 1f)
                    .setDuration(on ? 260 : 180)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
    }

    /** Places the left buttons so their group centre is the centre of the dock bar. */
    private void positionLeftButtons() {
        if (leftButtons == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) leftButtons.getLayoutParams();
        boolean deleteVisible = (leftDeleteBtn != null && leftDeleteBtn.getVisibility() == View.VISIBLE);
        int top;
        if (editMode) {
            int h = deleteVisible ? (2 * leftBtnSizePx + leftGapPx) : leftBtnSizePx;
            top = leftBarCenter - h / 2;
        } else {
            top = leftBarCenter - (2 * leftBtnSizePx + leftGapPx) / 2;
        }
        lp.topMargin = Math.max(0, top);
        leftButtons.setLayoutParams(lp);
    }

    private void buildTopOverlay() {
        // Rebuilt on every entry into the editor: apps installed since the previous session would
        // otherwise never show up as candidates until the home screen was recreated.
        if (topOverlay != null) {
            root.removeView(topOverlay);
            topOverlay = null;
            topAdapter = null;
        }
        topOverlay = new RecyclerView(this);
        topOverlay.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        topOverlay.setItemAnimator(null);
        topOverlay.setFocusable(true);
        List<String> pkgs = new ArrayList<>();
        PackageManager pm = getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            if (hiddenApps.contains(pkg)) continue;
            pkgs.add(pkg);
        }
        Collections.sort(pkgs);
        topAdapter = new SimpleAdapter(pkgs);
        topOverlay.setAdapter(topAdapter);
        // Match the real dock row exactly: same width, tile size and item layout, placed right above it.
        int inner = grid.getWidth() - grid.getPaddingLeft() - grid.getPaddingRight();
        int tileW = AppAdapter.tileWidthPx(inner, COLUMNS, density);
        int itemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(tileW), density);
        int rowH = itemH + (int) (18 * density);   // DockDecoration's 6dp + 12dp
        int[] gl = new int[2];
        int[] rl = new int[2];
        grid.getLocationInWindow(gl);
        root.getLocationInWindow(rl);
        int dockTop = gl[1] - rl[1] + grid.getPaddingTop();
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(inner, rowH);
        flp.leftMargin = gl[0] - rl[0] + grid.getPaddingLeft();
        // The card inside the overlay ends at top + 14dp + tileH; put it just above the dock card.
        flp.topMargin = Math.max(0, dockTop - AppAdapter.tileHeightPx(tileW) - (int) (38 * density));
        topOverlay.setBackground(null);
        topOverlay.setPadding(0, 0, 0, 0);
        topOverlay.addItemDecoration(new DockDecoration(topAdapter.getItemCount(), density));
        root.addView(topOverlay, flp);
        if (!pkgs.isEmpty()) editCandidate = pkgs.get(0);
        topAdapter.setSelected(0);
        topOverlay.scrollToPosition(0);
    }

    /** Simple adapter for the top overlay of installed apps. */
    private class SimpleAdapter extends RecyclerView.Adapter<SimpleAdapter.H> {
        private final List<String> pkgs;
        private int selected = 0;
        SimpleAdapter(List<String> pkgs) { this.pkgs = pkgs; setHasStableIds(true); }
        @Override public long getItemId(int position) { return pkgs.get(position).hashCode(); }
        void setSelected(int i) {
            if (i < 0 || i >= pkgs.size()) return;
            int old = selected;
            selected = i;
            editCandidate = pkgs.get(i);
            if (old != i) {
                if (old >= 0 && old < pkgs.size()) notifyItemChanged(old);
                notifyItemChanged(i);
            }
        }
        String getPkg(int i) { return (i >= 0 && i < pkgs.size()) ? pkgs.get(i) : null; }
        @Override public int getItemCount() { return pkgs.size(); }
        @Override public H onCreateViewHolder(ViewGroup p, int t) {
            View v = getLayoutInflater().inflate(R.layout.item_app, p, false);
            // One cell of the dock: the card is centred in it, so the row lines up with the real dock.
            int inner = grid.getWidth() - grid.getPaddingLeft() - grid.getPaddingRight();
            int cell = COLUMNS > 0 ? inner / COLUMNS : inner;
            int w = AppAdapter.tileWidthPx(inner, COLUMNS, density);
            int h = AppAdapter.tileHeightPx(w);
            View card = v.findViewById(R.id.card);
            ViewGroup.LayoutParams cardLp = card.getLayoutParams();
            cardLp.width = w;
            cardLp.height = h;
            card.setLayoutParams(cardLp);
            // Same rounded shape as a dock tile: clip the banner to the tile's corners.
            card.setClipToOutline(true);
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                            AppAdapter.cornerRadius(view.getWidth(), density));
                }
            });
            View name = v.findViewById(R.id.name);
            ViewGroup.MarginLayoutParams nameLp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
            nameLp.width = w;
            nameLp.topMargin = h + Math.round(7 * density);
            name.setLayoutParams(nameLp);
            v.setLayoutParams(new RecyclerView.LayoutParams(cell, AppAdapter.itemHeightPx(h, density)));
            return new H(v);
        }
        @Override public void onBindViewHolder(H h, int pos) {
            String pkg = pkgs.get(pos);
            Drawable icon = null, banner = null;
            String label = pkg;
            try {
                PackageManager pm = getPackageManager();
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                icon = pm.getApplicationIcon(ai);
                banner = ai.loadBanner(pm);
                label = String.valueOf(pm.getApplicationLabel(ai));
            } catch (Exception ignored) {
            }
            h.banner.setImageDrawable(banner);
            h.banner.setVisibility(banner != null ? View.VISIBLE : View.GONE);
            h.icon.setImageDrawable(icon);
            h.icon.setVisibility(banner == null ? View.VISIBLE : View.GONE);
            h.name.setText(Cards.cleanLabel(label, pkg));
            h.name.setVisibility(View.INVISIBLE);   // the dock row has no captions
            // Focus drives the enlargement, exactly like a dock tile (animated, with overshoot).
            float initScale = h.card.hasFocus() ? AppAdapter.FOCUS_SCALE : 1f;
            h.card.setScaleX(initScale);
            h.card.setScaleY(initScale);
            h.card.setOnClickListener(v -> setSelected(pos));
            h.card.setOnFocusChangeListener((v, focused) -> {
                v.animate().cancel();
                float s = focused ? AppAdapter.FOCUS_SCALE : 1f;
                v.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 260 : 180)
                        .setInterpolator(focused
                                ? new android.view.animation.OvershootInterpolator(1.6f)
                                : new android.view.animation.DecelerateInterpolator())
                        .withLayer().start();
            });
            h.card.setOnKeyListener((vv, code, ev) -> {
                if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (selected > 0) {
                        setSelected(selected - 1);
                        topOverlay.scrollToPosition(selected);
                        topOverlay.post(() -> {
                            RecyclerView.ViewHolder vh = topOverlay.findViewHolderForAdapterPosition(selected);
                            if (vh != null) vh.itemView.findViewById(R.id.card).requestFocus();
                        });
                    }
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (selected < pkgs.size() - 1) {
                        setSelected(selected + 1);
                        topOverlay.scrollToPosition(selected);
                        topOverlay.post(() -> {
                            RecyclerView.ViewHolder vh = topOverlay.findViewHolderForAdapterPosition(selected);
                            if (vh != null) vh.itemView.findViewById(R.id.card).requestFocus();
                        });
                    }
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_UP) return true;
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    // Change your mind: go back to picking a slot in the dock.
                    topActive = false;
                    if (topAdapter != null) topAdapter.notifyDataSetChanged();
                    editFocusTop = false;
                    focusEditSlot();
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                    // Replace the chosen dock slot with this candidate, or fill an empty "+" slot.
                    String cand = pkgs.get(pos);
                    List<String> favs = adapter.getFavourites();
                    if (editSlot >= 0 && editSlot < favs.size()) {
                        String old = favs.get(editSlot);
                        favs.set(editSlot, cand);
                        int dup = favs.indexOf(cand);
                        if (dup >= 0 && dup != editSlot) favs.set(dup, old);
                        adapter.setFavourites(favs);
                    } else if (editSlot == favs.size() && favs.size() < COLUMNS && !favs.contains(cand)) {
                        // Empty "+" slot: add the app instead of replacing.
                        favs.add(cand);
                        adapter.setFavourites(favs);
                    }
                    topActive = false;
                    topAdapter.notifyDataSetChanged();
                    editFocusTop = false;
                    focusEditSlot();
                    return true;
                }
                return false;
            });
        }
        class H extends RecyclerView.ViewHolder {
            View card; ImageView banner, icon; TextView name;
            H(View v) {
                super(v);
                card = v.findViewById(R.id.card);
                banner = v.findViewById(R.id.banner);
                icon = v.findViewById(R.id.icon);
                name = v.findViewById(R.id.name);
            }
        }
    }

}
