package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Remote-friendly settings owned by the launcher; system settings stay one row away.
 *
 * The screen follows the home screen's language: a black backdrop with an ambient glow, thin
 * Outfit type, and frosted pills that turn white under focus. The first choice is the background
 * accent: Auto keeps sampling a colour from each app, a fixed colour keeps the glow the same
 * however the focus moves.
 */
public final class LauncherSettingsActivity extends Activity {
    /** The ten fixed accents offered after Auto, in the order they appear on screen. */
    private static final int[] ACCENT_VALUES = {
            0xFF4DAE6D, 0xFF3FC9A5, 0xFF35B6D9, 0xFF4C8DFF, 0xFF7B6CFF,
            0xFFA66BFF, 0xFFFF5E8A, 0xFFF0555B, 0xFFF28C3A, 0xFFE4B33C
    };
    private static final int[] ACCENT_NAMES = {
            R.string.accent_green, R.string.accent_mint, R.string.accent_cyan,
            R.string.accent_blue, R.string.accent_indigo, R.string.accent_violet,
            R.string.accent_pink, R.string.accent_red, R.string.accent_orange,
            R.string.accent_amber
    };
    private static final String PREF_ACCENT = "accent_color";

    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private TextView animationsChoice;
    private TextView greetingChoice;
    private TextView weatherChoice;
    private TextView followThemeChoice;
    private TextView clearButtonChoice;
    private TextView searchButtonChoice;
    private TextView accountButtonChoice;
    private View settingsGlow;
    private ScrollView settingsScroll;
    private TextView accentSummary;
    private TextView[] accentSwatches;
    private int columns;
    private boolean animations;
    private boolean greeting;
    private boolean weather;
    private boolean clearButtonHidden;
    private boolean searchButtonHidden;
    private boolean accountButtonHidden;
    /** 0 means Auto; any other value is one of {@link #ACCENT_VALUES}. */
    private int fixedAccent;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
        animations = config.animations;
        greeting = choices.getBoolean("greeting_enabled", true);
        weather = choices.getBoolean("weather_enabled", true);
        fixedAccent = choices.getInt(PREF_ACCENT, 0);
        if (accentIndex(fixedAccent) < 0) fixedAccent = 0;

        settingsGlow = findViewById(R.id.settingsGlow);
        settingsScroll = findViewById(R.id.settingsScroll);
        accentSummary = findViewById(R.id.accentSummary);
        attachSmoothScroll(findViewById(R.id.settingsContent));
        buildAccentSwatches();

        findViewById(R.id.doneButton).setOnClickListener(v -> finish());

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        animationsChoice = findViewById(R.id.animationsChoice);
        // One switch owns all motion: on plays the full focus animation with its extras, off is
        // a still grid. Both keys are written so themes and Config keep working unchanged.
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", animations).apply();
            setResult(RESULT_OK);
            refresh();
        });
        greetingChoice = findViewById(R.id.greetingChoice);
        greetingChoice.setOnClickListener(v -> {
            greeting = !greeting;
            choices.edit().putBoolean("greeting_enabled", greeting).apply();
            setResult(RESULT_OK);
            refresh();
        });
        weatherChoice = findViewById(R.id.weatherChoice);
        weatherChoice.setOnClickListener(v -> {
            weather = !weather;
            choices.edit().putBoolean("weather_enabled", weather).apply();
            setResult(RESULT_OK);
            refresh();
        });
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove("animations").remove("advanced_effects").apply();
            Config current = Config.load(this);
            columns = current.columns;
            animations = current.animations;
            setResult(RESULT_OK);
            refresh();
        });
        clearButtonChoice = findViewById(R.id.clearButtonChoice);
        clearButtonHidden = choices.getBoolean("clear_button_hidden", false);
        clearButtonChoice.setOnClickListener(v -> {
            clearButtonHidden = !clearButtonHidden;
            choices.edit().putBoolean("clear_button_hidden", clearButtonHidden).apply();
            setResult(RESULT_OK);
            refresh();
        });
        searchButtonChoice = findViewById(R.id.searchButtonChoice);
        searchButtonHidden = choices.getBoolean("search_button_hidden", false);
        searchButtonChoice.setOnClickListener(v -> {
            searchButtonHidden = !searchButtonHidden;
            choices.edit().putBoolean("search_button_hidden", searchButtonHidden).apply();
            setResult(RESULT_OK);
            refresh();
        });
        accountButtonChoice = findViewById(R.id.accountButtonChoice);
        accountButtonHidden = choices.getBoolean("account_button_hidden", false);
        accountButtonChoice.setOnClickListener(v -> {
            accountButtonHidden = !accountButtonHidden;
            choices.edit().putBoolean("account_button_hidden", accountButtonHidden).apply();
            setResult(RESULT_OK);
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(new Intent(Settings.ACTION_SETTINGS)));
        refresh();
        accentSwatches[Math.max(0, accentIndex(fixedAccent))].requestFocus();
    }

    /** Builds the Auto swatch plus the ten fixed colours, all keyboard- and remote-navigable. */
    private void buildAccentSwatches() {
        LinearLayout row = findViewById(R.id.accentRow);
        accentSwatches = new TextView[ACCENT_VALUES.length + 1];
        int size = dp(46);
        int gap = dp(10);
        for (int i = 0; i < accentSwatches.length; i++) {
            final int index = i;
            TextView swatch = new TextView(this);
            swatch.setGravity(Gravity.CENTER);
            swatch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            swatch.setTypeface(getResources().getFont(R.font.outfit_medium));
            swatch.setFocusable(true);
            swatch.setClickable(true);
            swatch.setTag(accentValue(index));
            swatch.setContentDescription(accentLabel(accentValue(index)));
            swatch.setOnFocusChangeListener((v, focused) -> {
                v.setBackground(swatchBackground(accentValue(index), focused,
                        fixedAccent == accentValue(index)));
                v.animate().scaleX(focused ? 1.05f : 1f).scaleY(focused ? 1.05f : 1f)
                        .setDuration(120).start();
                if (focused) smoothScrollTo(v);
            });
            swatch.setOnClickListener(v -> {
                fixedAccent = accentValue(index);
                choices.edit().putInt(PREF_ACCENT, fixedAccent).apply();
                setResult(RESULT_OK);
                refresh();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            if (i > 0) lp.setMarginStart(gap);
            row.addView(swatch, lp);
            accentSwatches[i] = swatch;
        }
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        animationsChoice.setSelected(animations);
        greetingChoice.setText(getString(R.string.greeting_state,
                getString(greeting ? R.string.on : R.string.off)));
        greetingChoice.setSelected(greeting);
        weatherChoice.setText(getString(R.string.weather_state,
                getString(weather ? R.string.on : R.string.off)));
        weatherChoice.setSelected(weather);
        followThemeChoice.setSelected(!hasOnTvChoices());
        clearButtonChoice.setText(getString(R.string.clear_button_state,
                getString(clearButtonHidden ? R.string.on : R.string.off)));
        clearButtonChoice.setSelected(clearButtonHidden);
        searchButtonChoice.setText(getString(R.string.search_button_state,
                getString(searchButtonHidden ? R.string.on : R.string.off)));
        searchButtonChoice.setSelected(searchButtonHidden);
        accountButtonChoice.setText(getString(R.string.account_button_state,
                getString(accountButtonHidden ? R.string.on : R.string.off)));
        accountButtonChoice.setSelected(accountButtonHidden);
        refreshAccent();
    }

    /** Repaints the swatches, the summary line and the glow behind the whole screen. */
    private void refreshAccent() {
        if (accentSwatches == null) return;
        for (int i = 0; i < accentSwatches.length; i++) {
            int value = accentValue(i);
            TextView swatch = accentSwatches[i];
            boolean selected = fixedAccent == value;
            swatch.setSelected(selected);
            swatch.setText(i == 0 ? getString(R.string.accent_auto_short)
                    : selected ? "\u2713" : "");
            swatch.setTextColor(i == 0 || !isLight(value) ? Color.WHITE : 0xFF101419);
            swatch.setBackground(swatchBackground(value, swatch.hasFocus(), selected));
        }
        int preview = fixedAccent == 0 ? 0xFF6E8BB8 : fixedAccent;
        settingsGlow.setBackground(screenGlow(preview));
        accentSummary.setText(fixedAccent == 0
                ? getString(R.string.accent_follows_app)
                : getString(R.string.accent_fixed, accentLabel(fixedAccent)));
    }

    private Drawable swatchBackground(int value, boolean focused, boolean selected) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        if (value == 0) {
            // Auto: a small spectrum, so it reads as "whatever the app brings".
            shape.setGradientType(GradientDrawable.LINEAR_GRADIENT);
            shape.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
            shape.setColors(new int[]{0xFF4C8DFF, 0xFFA66BFF, 0xFF4DAE6D});
        } else {
            shape.setColor(value);
        }
        shape.setStroke(focused ? dp(3) : selected ? dp(2) : dp(1),
                focused ? 0xFFFFFFFF : selected ? 0xE6FFFFFF : 0x33FFFFFF);
        return shape;
    }

    /** One soft radial wash in the top-left corner, like the home screen's ambient glow. */
    private Drawable screenGlow(int color) {
        GradientDrawable glow = new GradientDrawable();
        glow.setShape(GradientDrawable.RECTANGLE);
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(0.18f, 0.04f);
        glow.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.9f);
        int rgb = color & 0x00FFFFFF;
        glow.setColors(new int[]{rgb | 0x66000000, rgb | 0x1A000000, 0x00000000});
        return glow;
    }

    private int accentValue(int index) {
        return index == 0 ? 0 : ACCENT_VALUES[index - 1];
    }

    /** Position of a fixed colour in the swatch row, or -1 when it is not one of the ten. */
    private int accentIndex(int value) {
        if (value == 0) return 0;
        for (int i = 0; i < ACCENT_VALUES.length; i++) {
            if (ACCENT_VALUES[i] == value) return i + 1;
        }
        return -1;
    }

    private String accentLabel(int value) {
        int index = value == 0 ? -1 : accentIndex(value) - 1;
        return index < 0 || index >= ACCENT_NAMES.length
                ? getString(R.string.accent_auto) : getString(ACCENT_NAMES[index]);
    }

    /** True for the pale accents whose check mark needs ink instead of white. */
    private static boolean isLight(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000 > 168;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Keeps the focused control in view with a smooth scroll instead of jumping at the page edge. */
    private void smoothScrollTo(View view) {
        if (settingsScroll == null || view == null) return;
        Rect rect = new Rect();
        view.getDrawingRect(rect);
        settingsScroll.requestChildRectangleOnScreen(view, rect, false);
    }

    /** Attaches the smooth-scroll behaviour to every focusable control on the settings page. */
    private void attachSmoothScroll(View root) {
        if (root == null) return;
        if (root.isFocusable()) {
            View target = root;
            target.setOnFocusChangeListener((v, focused) -> {
                if (focused) smoothScrollTo(v);
            });
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                attachSmoothScroll(group.getChildAt(i));
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains("animations")
                || choices.contains("advanced_effects");
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
