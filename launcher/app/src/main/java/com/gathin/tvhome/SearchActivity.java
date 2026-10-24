package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Locale;

public class SearchActivity extends Activity {
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

    private final List<AppEntry> all = new ArrayList<>();
    private final List<AppEntry> shown = new ArrayList<>();
    private Adapter adapter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Drawable placeholder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);
        android.widget.EditText input = findViewById(R.id.searchInput);
        RecyclerView list = findViewById(R.id.searchList);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        list.setAdapter(adapter);
        placeholder = getDrawable(R.drawable.ic_placeholder_banner);

        PackageManager pm = getPackageManager();
        // The same apps the grid shows: never the launcher itself, never hidden_apps.
        Set<String> hidden = new HashSet<>(Config.load(this).hiddenApps);
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            String pkg = ai.packageName;
            if (pkg.equals(getPackageName()) || hidden.contains(pkg)) continue;
            String label = String.valueOf(ri.loadLabel(pm));
            Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            all.add(new AppEntry(label, pkg, launch, ri));
        }
        Collections.sort(all, (a, b) -> a.label.compareToIgnoreCase(b.label));
        filter("");
        loadIcons(pm);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { filter(s.toString()); }
            public void afterTextChanged(Editable s) {}
        });
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    /** Loads every icon off the main thread — loadIcon decodes bitmaps, and doing that for the whole
     *  list on the way in stalled the first frame on slow TVs — then refreshes the rows once. */
    private void loadIcons(PackageManager pm) {
        executor.execute(() -> {
            for (AppEntry e : all) {
                if (e.icon != null) continue;
                try {
                    e.icon = e.info.loadIcon(pm);
                } catch (Exception ignored) {
                }
            }
            runOnUiThread(() -> adapter.notifyDataSetChanged());
        });
    }

    static boolean matches(String label, String pkg, String query) {
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return true;
        String l = label == null ? "" : label.toLowerCase(Locale.ROOT);
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.ROOT);
        return l.contains(needle) || p.contains(needle);
    }

    private void filter(String q) {
        executor.execute(() -> {
            List<AppEntry> result = new ArrayList<>();
            for (AppEntry e : all) {
                if (matches(e.label, e.pkg, q)) result.add(e);
            }
            runOnUiThread(() -> {
                shown.clear();
                shown.addAll(result);
                adapter.notifyDataSetChanged();
            });
        });
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.H> {
        class H extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView label;
            H(View v) {
                super(v);
                icon = v.findViewById(R.id.icon);
                label = v.findViewById(R.id.label);
            }
        }

        @Override
        public H onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = getLayoutInflater().inflate(R.layout.item_search, parent, false);
            return new H(v);
        }

        @Override
        public void onBindViewHolder(H h, int position) {
            final AppEntry e = shown.get(position);
            h.icon.setImageDrawable(e.icon != null ? e.icon : placeholder);
            h.label.setText(e.label);
            h.itemView.setOnClickListener(v -> startActivity(e.launch));
            h.itemView.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                    startActivity(e.launch);
                    return true;
                }
                return false;
            });
        }

        @Override
        public int getItemCount() { return shown.size(); }
    }
}
