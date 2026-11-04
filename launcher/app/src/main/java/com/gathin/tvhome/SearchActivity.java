package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Search. The first strip is the app search that was always here — the leanback launchers the
 * grid shows, matched by name; the input filters it as the user types. Below it, one strip per
 * installed video app that exports a search provider (the Leanback global-search protocol,
 * see {@link ContentSearch}): that app's content as poster cards, where a click opens the title
 * inside the app itself. Apps without a provider simply have no strip.
 */
public class SearchActivity extends Activity {
    /** Log tag for the search diagnostics: providers found and rows answered, `adb logcat -s PureGridSearch`. */
    private static final String TAG = "PureGridSearch";
    /** Providers are queried after this much typing silence; the app filter stays instant. */
    private static final long DEBOUNCE_MS = 300;
    /** Width a decoded poster is pulled down to, px: the 110dp card at the focus zoom, with headroom. */
    private static final int POSTER_MAX_PX = 512;
    /** Poster bytes read into memory before decode. Generous: a legit 512-px poster fits well
     *  inside, while a hostile or broken multi-megabyte stream dies here, not in the heap. */
    private static final int POSTER_MAX_BYTES = 1024 * 1024;
    /** Poster cache budget: about ten portrait posters at the decoded size. */
    private static final int POSTER_CACHE_BYTES = 10 * 1024 * 1024;
    private static final int STRIP_TYPE_APP = 0, STRIP_TYPE_CONTENT = 1;

    private static class AppEntry {
        final String label;
        final String pkg;
        final Intent launch;
        final ResolveInfo info;
        /** The app's icon; the placeholder until the background load puts the real one in. */
        Drawable icon;
        AppEntry(String label, String pkg, Intent launch, ResolveInfo info) {
            this.label = label; this.pkg = pkg; this.launch = launch; this.info = info;
        }
    }

    /** One horizontal strip: the apps row, or one provider's content row. */
    private static final class Row {
        final String title;
        final List<AppEntry> apps;
        final List<ContentSearch.Result> content;
        /** The authority this strip answered for; null on the apps row. A package may export
         *  several search authorities, and the click must build content://{this one}/{id}, not
         *  whichever comes first in the provider list. */
        final String authority;

        Row(String title, List<AppEntry> apps, List<ContentSearch.Result> content, String authority) {
            this.title = title;
            this.apps = apps;
            this.content = content;
            this.authority = authority;
        }
    }

    private final List<AppEntry> all = new ArrayList<>();
    private final List<AppEntry> shown = new ArrayList<>();
    /** The pkg/activity components the current list was built from; onResume compares a fresh
     *  query against it so the list is rebuilt only when the launcher set really changed. */
    private final Set<String> listed = new HashSet<>();
    /** The text the strips are filtered/queried by; a rebuild keeps the query. */
    private String query = "";
    private List<ContentSearch.Provider> providers = new ArrayList<>();
    /** Latest answers by provider authority; an entry (even empty) means the provider answered. */
    private final Map<String, List<ContentSearch.Result>> content = new HashMap<>();
    /** The query generation each stored answer belongs to. A stale answer — one from an older
     *  keystroke, or from before the provider set changed — must not show under a newer query,
     *  and a provider that failed the current query must not look like it answered it. */
    private final Map<String, Integer> contentGen = new HashMap<>();
    private final List<Row> rows = new ArrayList<>();
    private RowsAdapter adapter;
    private TextView empty;
    /** Local work (app icons, provider discovery, poster loads), one at a time by design. */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** Bumps on every buildApps request; a slow build publishes only if it is still the newest. */
    private int buildEpoch;
    /** The per-provider orchestrators; each spawns its own bounded query thread. A fixed pool,
     *  not a cached one: the binder call inside is not interruptible, so on a 1 GB TV with a
     *  hung provider every keypress would otherwise park a thread that outlives its timeout. */
    private final ExecutorService providerPool = Executors.newFixedThreadPool(8);
    private final ExecutorService posterPool = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable pendingContentSearch;
    /** Bumps on every content search; an answer from a superseded query is dropped. */
    private int generation;
    private Drawable placeholder;
    private final Map<String, Drawable> sourceIcons = new HashMap<>();
    private final LruCache<String, Bitmap> posters = new LruCache<String, Bitmap>(POSTER_CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final Set<String> postersLoading = Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** The same three motion tiers the grid's tiles use (Config: animations / advanced / reduced). */
    private boolean animations, advancedEffects, reducedMotion;
    private float density;
    /** Settings for the content search: rows per provider and the wait per provider. */
    private int providerLimit;
    private long providerTimeoutMs;
    /** Package-set watcher: install, update, removal, enable/disable refresh the app strips and the
     *  provider discovery while the screen is open, no leave-and-return through onResume needed. */
    private final BroadcastReceiver appsWatcher = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Uri data = intent.getData();
            String pkg = data == null ? null : data.getSchemeSpecificPart();
            if (pkg == null || pkg.equals(getPackageName())) return;
            log("packages changed: " + intent.getAction() + " " + pkg);
            buildApps();
            discoverProviders();
        }
    };

    /** One search-diagnostic line; read with `adb logcat -s PureGridSearch`. Informational level so
     *  the release build reports it too — discovery answers and per-provider row counts are the only
     *  way to tell a "nothing found" screen apart from no provider apps at all. */
    private static void log(String message) {
        Log.i(TAG, message);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);
        density = getResources().getDisplayMetrics().density;
        EditText input = findViewById(R.id.searchInput);
        RecyclerView list = findViewById(R.id.searchList);
        empty = findViewById(R.id.searchEmpty);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RowsAdapter();
        list.setAdapter(adapter);
        placeholder = getDrawable(R.drawable.ic_placeholder_banner);
        Config cfg = Config.load(this);
        animations = cfg.animations;
        advancedEffects = cfg.animations && cfg.advancedEffects;
        reducedMotion = cfg.reducedMotion;
        providerLimit = cfg.searchProviderLimit;
        providerTimeoutMs = cfg.searchProviderTimeoutMs;
        // Package broadcasts are protected system broadcasts: a non-exported receiver still
        // receives them from the system, while other apps cannot address the receiver directly.
        IntentFilter appChanges = new IntentFilter();
        appChanges.addAction(Intent.ACTION_PACKAGE_ADDED);
        appChanges.addAction(Intent.ACTION_PACKAGE_REPLACED);
        appChanges.addAction(Intent.ACTION_PACKAGE_REMOVED);
        appChanges.addAction(Intent.ACTION_PACKAGE_CHANGED);
        appChanges.addDataScheme("package");
        registerReceiver(appsWatcher, appChanges,
                Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_NOT_EXPORTED : 0);
        buildApps();
        discoverProviders();
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                filter(s.toString());
                scheduleContentSearch(s.toString());
            }
            public void afterTextChanged(Editable s) {}
        });
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(appsWatcher);
        } catch (Exception ignored) {
        }
        ui.removeCallbacks(pendingContentSearch);
        executor.shutdownNow();
        providerPool.shutdownNow();
        posterPool.shutdownNow();
        super.onDestroy();
    }

    /** Back on a remote: first clear the query (and wait for the strips to follow), then leave —
     *  wiping a typo must not cost a full reopen of the screen. */
    @Override
    public void onBackPressed() {
        EditText input = findViewById(R.id.searchInput);
        if (input != null && input.getText().length() > 0) {
            input.setText("");
            input.requestFocus();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The list is built once, but apps can be uninstalled or disabled while the search sat in
        // the background — a row would then offer a launch that cannot succeed. Re-query and
        // rebuild only when the visible launcher set actually changed; the query is binder work,
        // so the whole check runs off the UI thread.
        executor.execute(() -> {
            Set<String> hidden = new HashSet<>(Config.load(this).hiddenApps);
            Set<String> now = new HashSet<>();
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
                String pkg = ri.activityInfo.applicationInfo.packageName;
                if (pkg.equals(getPackageName()) || hidden.contains(pkg)) continue;
                now.add(pkg + "/" + ri.activityInfo.name);
            }
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (!now.equals(listed)) buildApps();
                // A video app (or its search provider) may have arrived while we were away; the
                // discovery swaps the providers and re-runs the query only when the set really changed.
                discoverProviders();
            });
        });
    }

    /** Queries the leanback launchers the grid shows — never this launcher, never hidden_apps —
     *  into {@code all}, then refreshes the strips and starts the icon load. The query and the
     *  per-app labels are binder calls, so they run off the UI thread and publish when done. */
    private void buildApps() {
        final int epoch = ++buildEpoch;
        executor.execute(() -> {
            PackageManager pm = getPackageManager();
            // The same apps the grid shows: never the launcher itself, never hidden_apps.
            Set<String> hidden = new HashSet<>(Config.load(this).hiddenApps);
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            Set<String> listedNow = new HashSet<>();
            List<AppEntry> fresh = new ArrayList<>();
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                ApplicationInfo ai = ri.activityInfo.applicationInfo;
                String pkg = ai.packageName;
                if (pkg.equals(getPackageName()) || hidden.contains(pkg)) continue;
                listedNow.add(pkg + "/" + ri.activityInfo.name);
                String label = String.valueOf(ri.loadLabel(pm));
                Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                        .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                fresh.add(new AppEntry(label, pkg, launch, ri));
            }
            Collections.sort(fresh, (a, b) -> a.label.compareToIgnoreCase(b.label));
            runOnUiThread(() -> {
                if (epoch != buildEpoch || isDestroyed()) return;
                listed.clear();
                listed.addAll(listedNow);
                all.clear();
                all.addAll(fresh);
                filter(query);
                loadIcons(pm);
            });
        });
    }

    /** A snapshot of {@code all} taken on the UI thread: the executor tasks below must not iterate
     *  the live list, which buildApps (also UI) clears and refills while they run. */
    private List<AppEntry> allSnapshot() {
        return new ArrayList<>(all);
    }

    /** A row can outlive its app: it is disabled or uninstalled after the list was built. Android
     *  throws in that case — say so instead of crashing, and drop the stale list. */
    private void launch(AppEntry e) {
        try {
            startActivity(e.launch);
        } catch (ActivityNotFoundException | SecurityException ex) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            buildApps();
        }
    }

    /** Opens a suggestion: the intent the provider spelled out, or the app itself when the row
     *  carries nothing usable or the app refuses the deep link. {@code authority} is the authority
     *  the strip answered for — a package may export several. */
    private void launchContent(ContentSearch.Result r, String authority) {
        ContentSearch.LaunchSpec spec = ContentSearch.buildLaunch(r,
                authority != null ? authority : "");
        if (spec != null) {
            try {
                startActivity(ContentSearch.toIntent(spec));
                return;
            } catch (Exception ignored) {
                // fall through to the plain app launch
            }
        }
        ContentSearch.Provider p = providerFor(r.pkg);
        Intent app = getPackageManager().getLeanbackLaunchIntentForPackage(p != null ? p.pkg : r.pkg);
        if (app != null) {
            try {
                startActivity(app);
                return;
            } catch (Exception ignored) {
            }
        }
        Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
    }

    private ContentSearch.Provider providerFor(String pkg) {
        for (ContentSearch.Provider p : providers) {
            if (p.pkg.equals(pkg)) return p;
        }
        return null;
    }

    /** Loads every app icon off the main thread — loadIcon decodes bitmaps, and doing that for the
     *  whole list on the way in stalled the first frame on slow TVs — then refreshes once. */
    private void loadIcons(PackageManager pm) {
        List<AppEntry> snapshot = allSnapshot();
        executor.execute(() -> {
            for (AppEntry e : snapshot) {
                if (e.icon != null) continue;
                try {
                    e.icon = e.info.loadIcon(pm);
                } catch (Exception ignored) {
                }
            }
            runOnUiThread(() -> adapter.notifyDataSetChanged());
        });
    }

    /** Enumerates the search providers off the UI thread; on a changed set, the strips rebuild
     *  and the current query runs again so a just-installed app answers without retyping. */
    private void discoverProviders() {
        PackageManager pm = getPackageManager();
        // Snapshot the current set on the UI thread: the executor task must not read the field a
        // concurrent discovery may be about to replace.
        List<ContentSearch.Provider> current = new ArrayList<>(providers);
        executor.execute(() -> {
            List<ContentSearch.Provider> fresh = ContentSearch.discoverProviders(pm);
            Map<String, Drawable> icons = new HashMap<>();
            for (ContentSearch.Provider p : fresh) {
                if (icons.containsKey(p.pkg)) continue;
                try {
                    icons.put(p.pkg, pm.getApplicationIcon(p.pkg));
                } catch (Exception ignored) {
                }
            }
            boolean same = sameAuthorities(current, fresh);
            StringBuilder discovered = new StringBuilder("search providers: ").append(fresh.size());
            for (ContentSearch.Provider p : fresh) {
                discovered.append("; ").append(p.authority).append(" [").append(p.pkg).append(']');
            }
            log(discovered.toString());
            runOnUiThread(() -> {
                providers = fresh;
                sourceIcons.clear();
                sourceIcons.putAll(icons);
                if (!same) {
                    content.clear();
                    contentGen.clear();
                    runContentSearch(query);
                }
                rebuildRows();
            });
        });
    }

    private static boolean sameAuthorities(List<ContentSearch.Provider> a, List<ContentSearch.Provider> b) {
        if (a.size() != b.size()) return false;
        Set<String> left = new HashSet<>();
        for (ContentSearch.Provider p : a) left.add(p.authority);
        for (ContentSearch.Provider p : b) {
            if (!left.remove(p.authority)) return false;
        }
        return left.isEmpty();
    }

    static boolean matches(String label, String pkg, String query) {
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return true;
        String l = label == null ? "" : label.toLowerCase(Locale.ROOT);
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.ROOT);
        return l.contains(needle) || p.contains(needle);
    }

    private void filter(String q) {
        query = q;
        List<AppEntry> snapshot = allSnapshot();
        executor.execute(() -> {
            List<AppEntry> result = new ArrayList<>();
            for (AppEntry e : snapshot) {
                if (matches(e.label, e.pkg, q)) result.add(e);
            }
            runOnUiThread(() -> {
                shown.clear();
                shown.addAll(result);
                rebuildRows();
            });
        });
    }

    private void scheduleContentSearch(String q) {
        if (pendingContentSearch != null) ui.removeCallbacks(pendingContentSearch);
        pendingContentSearch = () -> runContentSearch(q);
        ui.postDelayed(pendingContentSearch, DEBOUNCE_MS);
    }

    /** Sends the query to every provider; each strip fills in as its answer arrives. */
    private void runContentSearch(String q) {
        generation++;
        final int gen = generation;
        final String what = q.trim();
        if (what.isEmpty() || providers.isEmpty()) {
            if (!what.isEmpty()) log("no search-provider apps; content search skipped for \"" + what + "\"");
            content.clear();
            contentGen.clear();
            rebuildRows();
            return;
        }
        for (ContentSearch.Provider p : providers) {
            providerPool.execute(() -> {
                // One provider, one bounded wait: the query runs on its own thread joined with a
                // timeout, so a provider that never answers costs this query only its own wait,
                // not a pool thread that stays eaten forever.
                FutureTask<List<ContentSearch.Result>> task = new FutureTask<>(
                        () -> ContentSearch.query(SearchActivity.this, p, what, providerLimit));
                Thread thread = new Thread(task, "content-search");
                long started = System.currentTimeMillis();
                thread.start();
                List<ContentSearch.Result> res;
                try {
                    res = task.get(providerTimeoutMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    task.cancel(true);
                    log("\"" + what + "\" → " + p.authority + ": timed out after " + providerTimeoutMs
                            + " ms, skipped");
                    // Timed out, not silent: an empty answer counts as answered, so the "nothing
                    // found" state can appear once every provider has had its chance.
                    ui.post(() -> publishAnswer(p, gen, Collections.emptyList()));
                    return;
                } catch (Exception e) {
                    log("\"" + what + "\" → " + p.authority + ": failed, " + e);
                    ui.post(() -> publishAnswer(p, gen, Collections.emptyList()));
                    return;
                }
                log("\"" + what + "\" → " + p.authority + ": " + res.size() + " row(s) in "
                        + (System.currentTimeMillis() - started) + " ms");
                ui.post(() -> publishAnswer(p, gen, res));
            });
        }
    }

    /** Publishes one provider's answer for the current query generation — a real list, or an empty
     *  one when the provider timed out or failed (it has answered either way, so the empty state
     *  can appear once every provider has spoken). Answers from a superseded keystroke are
     *  dropped, and a stored answer only shows under the generation it was given for. */
    private void publishAnswer(ContentSearch.Provider p, int gen, List<ContentSearch.Result> res) {
        if (gen != generation) return;   // a newer keystroke has superseded this answer
        content.put(p.authority, res);
        contentGen.put(p.authority, gen);
        rebuildRows();
    }

    /** Rebuilds the strip list: the apps row, then one row per provider that has answered with
     *  content, then the empty-state text when there is nothing to show at all. */
    private void rebuildRows() {
        rows.clear();
        if (!shown.isEmpty()) {
            rows.add(new Row(getString(R.string.search_section_apps), shown, null, null));
        }
        for (ContentSearch.Provider p : providers) {
            // Only answers given to the current query count: a stale success (an older keystroke)
            // or a provider that has not answered this query yet shows neither rows nor readiness.
            Integer g = contentGen.get(p.authority);
            if (g == null || g != generation) continue;
            List<ContentSearch.Result> res = content.get(p.authority);
            if (res != null && !res.isEmpty()) {
                rows.add(new Row(p.label, null, res, p.authority));
            }
        }
        adapter.notifyDataSetChanged();
        boolean hasQuery = !query.trim().isEmpty();
        if (rows.isEmpty() && hasQuery) {
            // While providers are still answering, keep the space quiet instead of flashing
            // "nothing found" for a moment before the rows land.
            boolean allAnswered = true;
            for (ContentSearch.Provider p : providers) {
                Integer g = contentGen.get(p.authority);
                if (g == null || g != generation) allAnswered = false;
            }
            if (allAnswered) {
                empty.setText(providers.isEmpty()
                        ? R.string.search_no_content_hint : R.string.search_empty);
                empty.setVisibility(View.VISIBLE);
            } else {
                empty.setVisibility(View.GONE);
            }
        } else {
            empty.setVisibility(View.GONE);
        }
    }

    private void animateFocus(View card, boolean focused) {
        card.animate().cancel();
        if (!animations) {
            card.setScaleX(1f);
            card.setScaleY(1f);
            card.setTranslationY(0f);
            card.setTranslationZ(0f);
            return;
        }
        float scale = focused ? (advancedEffects ? 1.12f : 1.10f) : 1f;
        float lift = focused && advancedEffects ? -3f * density : 0f;
        float z = focused && advancedEffects ? 6f * density : 0f;
        if (reducedMotion) {
            card.animate().scaleX(scale).scaleY(scale).translationY(lift).translationZ(z)
                    .setDuration(focused ? 120 : 80)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        } else {
            card.animate().scaleX(scale).scaleY(scale).translationY(lift).translationZ(z)
                    .setDuration(focused ? 180 : 140)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    /** Shows a poster: cache hit goes up at once, a miss loads off the thread pool and patches
     *  only a view still bound to the same poster. */
    private void showPoster(ImageView view, String src) {
        view.setTag(src);
        Bitmap cached = src == null ? null : posters.get(src);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(placeholder);
        if (src == null || !postersLoading.add(src)) return;
        posterPool.execute(() -> {
            Bitmap bmp = decodePoster(this, src, POSTER_MAX_PX);
            postersLoading.remove(src);
            if (bmp != null) posters.put(src, bmp);
            if (bmp == null) return;
            ui.post(() -> {
                if (src.equals(view.getTag())) view.setImageBitmap(bmp);
            });
        });
    }

    /** Reads the stream up to {@link #POSTER_MAX_BYTES}; poster streams (network, cross-app) are
     *  one-shot, and the two-pass decode below needs the bytes twice. A provider answering with
     *  megabytes must not fill the heap of a 1 GB TV before the decode even starts — the same
     *  guard the weather fetch applies to its own responses. */
    private static byte[] readAll(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        for (int n; (n = in.read(buf)) > 0; ) {
            total += n;
            if (total > limit) throw new Exception("poster stream over " + limit + " bytes");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * Decodes a poster at no more than {@code max} px wide: http(s) straight from the network
     * (the same timeouts the weather fetch uses), content/resource/file URIs through the
     * resolver. Null when it cannot be had — the card keeps its placeholder.
     */
    static Bitmap decodePoster(Context ctx, String src, int max) {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            if (src.startsWith("http://") || src.startsWith("https://")) {
                conn = (HttpURLConnection) new URL(src).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
                in = conn.getInputStream();
            } else if (src.startsWith("content:") || src.startsWith("android.resource:")
                    || src.startsWith("file:")) {
                in = ctx.getContentResolver().openInputStream(Uri.parse(src));
            } else {
                return null;
            }
            byte[] bytes = readAll(in, POSTER_MAX_BYTES);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0) return null;
            // The same trick the grid's banners use: decode at a power-of-two sample so the big
            // bitmap is never allocated at all, then scale the remainder down if it still spills.
            // Both sides count: a "576 wide, 100 000 tall" poster must sample down like a wide one.
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= max || bounds.outHeight / (sample * 2) >= max) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            if (bmp != null && bmp.getWidth() > max) {
                Bitmap smaller = Bitmap.createScaledBitmap(bmp, max,
                        Math.max(1, Math.round(bmp.getHeight() * (max / (float) bmp.getWidth()))), true);
                if (smaller != bmp) {
                    bmp.recycle();
                    bmp = smaller;
                }
            }
            return bmp;
        } catch (OutOfMemoryError e) {
            // Even with the byte cap and the sample, a decoded poster is the biggest allocation
            // this screen makes: one card keeps its placeholder instead of the screen dying.
            log("poster OOM: " + src);
            return null;
        } catch (Exception e) {
            // Not silent: a cleartext http:// uri fails here on modern targets, and "all cards
            // keep placeholders" must be explainable from logcat.
            log("poster failed: " + src + ", " + e);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
            if (conn != null) conn.disconnect();
        }
    }

    /** The vertical list of strips. */
    private final class RowsAdapter extends RecyclerView.Adapter<RowsAdapter.RowHolder> {
        @NonNull
        @Override
        public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_search_row, parent, false);
            return new RowHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RowHolder h, int position) {
            Row row = rows.get(position);
            h.title.setText(row.title);
            h.strip.setData(row);
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        class RowHolder extends RecyclerView.ViewHolder {
            final TextView title;
            final RecyclerView stripView;
            final StripAdapter strip = new StripAdapter();

            RowHolder(View v) {
                super(v);
                title = v.findViewById(R.id.rowTitle);
                stripView = v.findViewById(R.id.rowItems);
                stripView.setLayoutManager(new LinearLayoutManager(
                        SearchActivity.this, RecyclerView.HORIZONTAL, false));
                stripView.setAdapter(strip);
            }
        }
    }

    /** One strip's cards: app tiles or content posters, per the row it is bound to. */
    private final class StripAdapter extends RecyclerView.Adapter<StripHolder> {
        private Row bound;

        void setData(Row row) {
            bound = row;
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            return bound.apps != null ? STRIP_TYPE_APP : STRIP_TYPE_CONTENT;
        }

        @NonNull
        @Override
        public StripHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(
                    viewType == STRIP_TYPE_APP ? R.layout.item_search_app_card : R.layout.item_search_card,
                    parent, false);
            return new StripHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull StripHolder h, int position) {
            if (bound.apps != null) {
                AppEntry e = bound.apps.get(position);
                h.icon.setImageDrawable(e.icon != null ? e.icon : placeholder);
                h.label.setText(e.label);
                h.card.setOnClickListener(v -> launch(e));
            } else {
                ContentSearch.Result r = bound.content.get(position);
                h.title.setText(r.title);
                Drawable badge = sourceIcons.get(r.pkg);
                h.badge.setImageDrawable(badge != null ? badge : placeholder);
                showPoster(h.poster, r.poster);
                final String rowAuthority = bound.authority;
                h.card.setOnClickListener(v -> launchContent(r, rowAuthority));
            }
        }

        @Override
        public int getItemCount() {
            if (bound == null) return 0;
            return bound.apps != null ? bound.apps.size() : bound.content.size();
        }
    }

    private class StripHolder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView poster;
        final ImageView badge;
        final ImageView icon;
        final TextView label;
        final TextView title;

        StripHolder(View v) {
            super(v);
            card = v.findViewById(R.id.card);
            poster = v.findViewById(R.id.poster);
            badge = v.findViewById(R.id.badge);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            title = v.findViewById(R.id.title);
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                            AppAdapter.cornerRadius(view.getWidth(), density));
                }
            });
            card.setClipToOutline(true);
            card.setOnFocusChangeListener((view, focused) -> animateFocus(view, focused));
            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    view.performClick();
                    return true;
                }
                return false;
            });
        }
    }
}
