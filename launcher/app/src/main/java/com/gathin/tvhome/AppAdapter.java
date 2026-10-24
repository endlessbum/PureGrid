package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (favourites, fixed order),
 * then the module cards the owner chose to show (by label), the rest is alphabetical.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    /** Synthetic package for the empty "add app here" slots of the dock editor. */
    private static final String ADD_PKG = "com.gathin.tvhome.add";

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for a D-pad direction key on a tile. Return true when handled (focus stays put). */
        boolean onTileVerticalKey(int position, int keyCode);

        /** A long OK press on a tile (left to the host to decide what it means). */
        void onTileLongPress(int position);
    }

    static final class Entry {
        static final int APP = 0, HOME_SETTINGS = 1, CARD = 2, ADD = 3;

        final int kind;
        final String label;
        final ComponentName component;
        final String pkg;
        Drawable banner;
        Drawable icon;
        /** Text under the tile when it is focused in the grid: the label, plus the caption for a card. */
        final String name;
        /** Ambient glow colour; 0 until sampled from the banner, or the card's accent from the start. */
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this(APP, label, component, banner, icon, null, 0);
        }

        Entry(int kind, String label, ComponentName component, Drawable banner, Drawable icon, String caption, int accent) {
            this.kind = kind;
            this.label = label;
            this.component = component;
            this.pkg = component == null ? "" : component.getPackageName();
            this.banner = banner;
            this.icon = icon;
            this.name = caption == null ? label : label + " \u00B7 " + caption;
            this.ambient = accent;
        }
    }

    static final float FOCUS_SCALE = 1.10f;
    /** Design size of a tile (dp); tiles shrink from here when the columns don't fit, see tileWidthPx. */
    static final float DESIGN_W_DP = 180f, DESIGN_H_DP = 101f;
    /**
     * How wide a banner may be, in dp. A card is 180dp and the focus zoom makes it a little
     * wider, so 288dp of artwork is enough: on a 1080p TV at 320dpi that is 576 pixels, and
     * a banner that is wider is decoded smaller instead of taking its full 1920 into the heap.
     */
    static final float BANNER_WIDTH_DP = 288f;

    /**
     * Tile width for a grid of this inner width: the cell minus room for the focus zoom and the glass
     * highlight, capped at the design size so 4 columns on a 1080p TV look exactly as before.
     */
    static int tileWidthPx(int innerWidthPx, int columns, float density) {
        int design = Math.round(DESIGN_W_DP * density);
        if (innerWidthPx <= 0 || columns <= 0) return design;
        int cell = innerWidthPx / columns;
        int fit = (int) ((cell - 12 * density) / FOCUS_SCALE);
        return Math.max(Math.round(80 * density), Math.min(design, fit));
    }

    static int tileHeightPx(int tileWidthPx) {
        return Math.round(tileWidthPx * DESIGN_H_DP / DESIGN_W_DP);
    }

    /** Height of one grid item: the tile, the name label under it and the item's own padding. */
    static int itemHeightPx(int tileHeightPx, float density) {
        return tileHeightPx + Math.round(45 * density);
    }

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    /** Dock order (config.xml dock_apps, or a theme's config.json). */
    private List<String> favourites;
    private boolean hideBelow = false;
    public boolean editing = false;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final boolean animations;
    private final boolean advancedEffects;
    private final boolean showHomeSettings;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): no overshoot, shorter fades. */
    private final boolean reducedMotion;
    private final List<Entry> items = new ArrayList<>();
    // Background executor for heavy loading (icons, banners).
    private final java.util.concurrent.ExecutorService bgExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
    // Simple LRU caches for loaded drawables.
    private final android.util.LruCache<String, Drawable> bannerCache = new android.util.LruCache<>(50);
    private final android.util.LruCache<String, Drawable> iconCache = new android.util.LruCache<>(100);
    // Handler to post UI updates from background threads.
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    AppAdapter(Context ctx, TileListener listener, int dockSize, Config cfg) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = new ArrayList<>(cfg.dockApps);
        this.hidden = new HashSet<>(cfg.hiddenApps);
        this.animations = cfg.animations;
        this.advancedEffects = cfg.animations && cfg.advancedEffects;
        this.showHomeSettings = cfg.showHomeSettingsShortcut;
        this.reducedMotion = cfg.reducedMotion;
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    /**
     * Rebuilds the tiles. A shown module card stands in for every tile of its package (same component,
     * so focus follows it); hidden_apps wins over a card, and a card's package in dock_apps takes that slot.
     */
    void reload(List<Cards.Card> cards) {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        Map<String, Cards.Card> cardByPkg = new HashMap<>();
        for (Cards.Card c : cards) cardByPkg.put(c.pkg, c);
        List<Entry> fresh = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;
            if (hideBelow && !favourites.contains(pkg)) continue;
            ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
            Cards.Card card = cardByPkg.get(pkg);
            if (card != null && !card.component.equals(component)) continue;   // the card stands in for this tile
            Drawable banner = loadBanner(pm, ri);
            String iconKey = pkg + ":icon";
            Drawable icon = iconCache.get(iconKey);
            if (icon == null) {
                // Placeholder now; the real icon lands on the entry when the load finishes.
                icon = ctx.getDrawable(R.drawable.ic_placeholder_banner);
                bgExecutor.submit(() -> {
                    Drawable realIcon;
                    try {
                        realIcon = ri.loadIcon(pm);
                    } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ex) {
                        // The package or its resources vanished between the query and this load;
                        // the task just leaves the placeholder in place.
                        return;
                    }
                    if (realIcon != null) {
                        iconCache.put(iconKey, realIcon);
                        uiHandler.post(() -> applyLoadedArtwork(pkg, false, realIcon));
                    }
                });
            }
            if (card != null) {
                fresh.add(new Entry(Entry.CARD, card.label, component, banner, icon, card.caption, card.accent));
            } else {
                // Same cleaner as the card prompt: an app label with a bidi override or line separator must not
                // redraw the tile name as something else (#117).
                fresh.add(new Entry(Cards.cleanLabel(String.valueOf(ri.loadLabel(pm)), pkg), component, banner, icon));
            }
        }
        Collections.sort(fresh, (a, b) -> {
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            // Cards come right after the configured dock apps, before the alphabetical grid.
            boolean ca = a.kind == Entry.CARD, cb = b.kind == Entry.CARD;
            if (ca != cb) return ca ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
        // In the dock editor, pad a short dock with "add" slots so the owner can fill it.
        if (editing && favourites.size() < dockSize) {
            Drawable plus = ctx.getDrawable(R.drawable.ic_plus);
            // Never beyond the list end: a dock package can be missing from the resolved apps.
            int at = Math.min(favourites.size(), fresh.size());
            for (int i = favourites.size(); i < dockSize; i++) {
                // A synthetic component keeps RecyclerView's stable ids happy (getItemId needs one).
                fresh.add(at++, new Entry(Entry.ADD, "+",
                        new ComponentName(ADD_PKG, "slot" + i), null, plus, null, 0));
            }
        }
        // Retain the optional Default-home tile until dependent tile-ordering work is merged.
        if (showHomeSettings
                && pm.resolveActivity(new Intent(Settings.ACTION_HOME_SETTINGS), 0) != null) {
            fresh.add(new Entry(Entry.HOME_SETTINGS, ctx.getString(R.string.home_settings),
                    new ComponentName(ctx, HomeSettingsActivity.class), null,
                    ctx.getDrawable(R.drawable.ic_home_settings), null, 0));
        }
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    /** Replaces the dock order (used by the on-TV dock editor) and rebuilds the tiles. */
    public void setFavourites(List<String> favs) {
        this.favourites = new ArrayList<>(favs);
        reload(new ArrayList<>());
    }

    public List<String> getFavourites() { return new ArrayList<>(favourites); }

    /**
     * Editor-only: move the dock app at {@code from} to {@code to} (a neighbour swap), touching
     * just those two tiles so the editor does not rebuild or lose focus.
     */
    boolean moveDockApp(int from, int to) {
        if (from == to) return false;
        if (from < 0 || to < 0 || from >= favourites.size() || to >= favourites.size()) return false;
        // Swap the tiles by package, not by slot index: a dock app that is no longer installed
        // leaves a favourites entry with no tile, and the two lists then disagree on indexes.
        String fromPkg = favourites.get(from), toPkg = favourites.get(to);
        Collections.swap(favourites, from, to);
        int a = dockItemPosition(fromPkg), b = dockItemPosition(toPkg);
        if (a >= 0 && b >= 0 && a != b) {
            Collections.swap(items, a, b);
            notifyItemMoved(a, b);
        }
        return true;
    }

    /** Adapter position of the tile showing this package, or -1 when it has none (an app that is
     *  no longer installed, say). Only real tiles match; "+" slots and Default home never do. */
    private int dockItemPosition(String pkg) {
        for (int i = 0; i < items.size(); i++) {
            Entry e = items.get(i);
            if ((e.kind == Entry.APP || e.kind == Entry.CARD) && e.pkg.equals(pkg)) return i;
        }
        return -1;
    }

    /**
     * Editor-only: remove the dock app at {@code index} and leave a matching "+" slot, updating just
     * the affected tiles (no full rebuild, so the editor does not flicker or lose focus).
     */
    void removeDockApp(int index) {
        if (index < 0 || index >= favourites.size()) return;
        // Remove the tile of the app this slot stands for, not the tile that happens to sit at the
        // same position: a dock app that is no longer installed leaves a favourites entry with no
        // tile, and the two lists then disagree on indexes.
        String removed = favourites.remove(index);
        int pos = dockItemPosition(removed);
        if (pos >= 0) {
            items.remove(pos);
            notifyItemRemoved(pos);
        }
        int adds = 0;
        for (Entry e : items) if (e.kind == Entry.ADD) adds++;
        int want = Math.max(0, dockSize - favourites.size());
        if (adds < want) {
            Drawable plus = ctx.getDrawable(R.drawable.ic_plus);
            int at = Math.min(favourites.size(), items.size());
            for (int k = adds; k < want; k++) {
                items.add(at, new Entry(Entry.ADD, "+",
                        new ComponentName(ADD_PKG, "slot" + k), null, plus, null, 0));
                notifyItemInserted(at);
                at++;
            }
        }
    }

    public void setHideBelow(boolean v) {
        this.hideBelow = v;
        reload(new ArrayList<>());
    }

    private Drawable loadBanner(PackageManager pm, ResolveInfo ri) {
        // Return placeholder immediately and schedule background loading of the real banner.
        final String pkg = ri.activityInfo.packageName;
        final String cacheKey = pkg + ":banner";
        Drawable cached = bannerCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        final Drawable placeholder = ctx.getDrawable(R.drawable.ic_placeholder_banner);
        // Schedule async load.
        bgExecutor.submit(() -> {
            Drawable real = loadBannerSync(pm, ri);
            if (real != null) {
                bannerCache.put(cacheKey, real);
                uiHandler.post(() -> applyLoadedArtwork(pkg, true, real));
            }
        });
        return placeholder;
    }

    /**
     * Puts a freshly loaded banner or icon on the entry that shows this package, on the UI thread:
     * the list is only ever read here, so a reload that runs while the load is in flight cannot
     * race it, and the notified position is the real one. APP and CARD entries both stand for the
     * package's own artwork; HOME_SETTINGS and ADD never match a loaded package.
     */
    private void applyLoadedArtwork(String pkg, boolean banner, Drawable real) {
        for (int i = 0; i < items.size(); i++) {
            Entry e = items.get(i);
            if ((e.kind == Entry.APP || e.kind == Entry.CARD) && e.pkg.equals(pkg)) {
                if (banner) e.banner = real;
                else e.icon = real;
                notifyItemChanged(i);
                return;
            }
        }
    }

    /** Heavy banner loading (original implementation) – runs on a background thread. */
    private Drawable loadBannerSync(PackageManager pm, ResolveInfo ri) {
        int id = ri.activityInfo.banner;
        if (id == 0) id = ri.activityInfo.applicationInfo.banner;
        if (id != 0) {
            try {
                Resources resources = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                Drawable small = decodeBanner(resources, id);
                if (small != null) return small;
                int density = Math.max(DisplayMetrics.DENSITY_XHIGH, ctx.getResources().getDisplayMetrics().densityDpi);
                return scaledBanner(resources.getDrawableForDensity(id, density, null));
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
                // fall through
            }
        }
        // The last leg runs outside the try above: it re-asks the package for the banner, and the
        // package can be gone by then. Null keeps the placeholder; the caller skips null loads.
        try {
            return scaledBanner(ri.activityInfo.loadBanner(pm));
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            return null;
        }
    }

    /**
     * Decodes a banner at no more than the width a card can show. A TV banner is usually
     * 1920x1080, which is eight megabytes of bitmap for a card 360 pixels wide, and the launcher
     * holds every banner for as long as it runs. Decoding straight into a small bitmap never
     * allocates the big one at all, which is the whole point: a drawn-and-shrunk banner still
     * costs the full-size bitmap first.
     *
     * Returns null when the banner is not a bitmap (an XML drawable, say) or is already small
     * enough, and the caller falls back to the plain drawable.
     */
    private Drawable decodeBanner(Resources resources, int id) {
        int max = maxBannerWidthPx();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resources.openRawResource(id)) {
            BitmapFactory.decodeStream(in, null, bounds);
        } catch (Exception ignored) {
            return null;      // not a file-backed bitmap: the plain drawable is the way
        }
        if (bounds.outWidth <= 0) return null;
        if (bounds.outWidth <= max) return null;            // already small enough
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= max) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bitmap;
        try (InputStream in = resources.openRawResource(id)) {
            bitmap = BitmapFactory.decodeStream(in, null, opts);
        } catch (Exception ignored) {
            return null;
        }
        if (bitmap == null) return null;
        // inSampleSize only halves, so the result can still be wider than the card needs.
        if (bitmap.getWidth() > max) {
            float scale = max / (float) bitmap.getWidth();
            Bitmap smaller = Bitmap.createScaledBitmap(bitmap, max,
                    Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
            if (smaller != bitmap) {
                bitmap.recycle();
                bitmap = smaller;
            }
        }
        return new BitmapDrawable(ctx.getResources(), bitmap);
    }

    /**
     * Draws a drawable into a bitmap no wider than the card can show. This is the fallback for
     * banners that are not plain bitmaps; the big drawable is released as soon as it is drawn.
     */
    private Drawable scaledBanner(Drawable source) {
        if (source == null) return null;
        int width = source.getIntrinsicWidth();
        int height = source.getIntrinsicHeight();
        if (width <= 0 || height <= 0) return source;      // a drawable without a size: leave it alone
        int max = maxBannerWidthPx();
        if (width <= max) return source;                    // already small enough
        float scale = max / (float) width;
        int w = Math.max(1, Math.round(width * scale));
        int h = Math.max(1, Math.round(height * scale));
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Rect saved = new Rect(source.getBounds());
        source.setBounds(0, 0, w, h);
        source.draw(canvas);
        source.setBounds(saved);
        return new BitmapDrawable(ctx.getResources(), bitmap);
    }

    /**
     * How wide a banner may be. A card is 180dp wide and the focus zoom makes it a little
     * wider than that, so one and a half card widths of artwork is enough: 288dp, which is
     * 576 pixels on a 1080p TV at 320dpi.
     */
    private int maxBannerWidthPx() {
        float density = ctx.getResources().getDisplayMetrics().density;
        return Math.round(BANNER_WIDTH_DP * density);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        float density = parent.getResources().getDisplayMetrics().density;
        int width = parent.getWidth() > 0 ? parent.getWidth() : parent.getMeasuredWidth();
        int inner = width - parent.getPaddingLeft() - parent.getPaddingRight();
        int w = tileWidthPx(inner, dockSize, density), h = tileHeightPx(w);
        View card = v.findViewById(R.id.card);
        ViewGroup.LayoutParams cardLp = card.getLayoutParams();
        cardLp.width = w;
        cardLp.height = h;
        card.setLayoutParams(cardLp);
        View name = v.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams nameLp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        nameLp.width = w;
        nameLp.topMargin = h + Math.round(7 * density);
        name.setLayoutParams(nameLp);
        return new Holder(v, this, listener, animations, advancedEffects, reducedMotion, dockSize);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        applyTileSize(h);
        Entry e = items.get(position);
        h.name.setText(e.name);
        h.name.setVisibility(position < dockSize ? View.INVISIBLE : View.VISIBLE);   // the dock has no labels
        h.dock = position < dockSize;
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setAlpha(focused ? 1f : 0f);
        h.card.setScaleX(focused && animations ? focusScale() : 1f);
        h.card.setScaleY(focused && animations ? focusScale() : 1f);
        h.card.setTranslationY(focused ? h.lift() : 0f);
        h.card.setTranslationZ(focused && advancedEffects ? 8f * ctx.getResources().getDisplayMetrics().density : 0f);
        h.banner.setScaleX(focused && advancedEffects ? 1.035f : 1f);
        h.banner.setScaleY(focused && advancedEffects ? 1.035f : 1f);
        if (e.kind == Entry.ADD) {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setVisibility(View.GONE);
            h.name.setVisibility(View.INVISIBLE);
        } else if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            // In the dock editor a long OK on a dock app grabs it for re-ordering; elsewhere the
            // long press keeps its original job (opening the app's system info page).
            if (editing && listener != null && pos != RecyclerView.NO_POSITION && pos < dockSize) {
                listener.onTileLongPress(pos);
                return true;
            }
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
            } catch (ActivityNotFoundException ignored) {
            }
            return true;
        });
    }

    /** Opens the tile's activity by explicit component, with no extras: a card can name nothing else. */
    private void launch(Entry e) {
        if (editing) return;
        if (e.kind == Entry.HOME_SETTINGS) {
            ctx.startActivity(new Intent(ctx, HomeSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
            if (advancedEffects && ctx instanceof Activity) {
                ((Activity) ctx).overridePendingTransition(R.anim.app_open_enter, R.anim.app_open_exit);
            }
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Keeps the tile at the size the grid's current inner width implies. onCreateViewHolder sizes
     * the card from the padding at creation time; the dock centring that lands after the first
     * layout pass changes that padding, and holders created before it would keep the old size
     * forever. The compare makes this a no-op whenever the size already matches — always, except
     * right after such a change.
     */
    private void applyTileSize(Holder h) {
        ViewGroup parent = (ViewGroup) h.itemView.getParent();
        if (parent == null || parent.getWidth() <= 0) return;
        float density = h.density;
        int inner = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
        int w = tileWidthPx(inner, dockSize, density);
        int cardH = tileHeightPx(w);
        ViewGroup.LayoutParams cardLp = h.card.getLayoutParams();
        ViewGroup.MarginLayoutParams nameLp = (ViewGroup.MarginLayoutParams) h.name.getLayoutParams();
        if (cardLp.width == w && cardLp.height == cardH && nameLp.width == w) return;
        cardLp.width = w;
        cardLp.height = cardH;
        h.card.setLayoutParams(cardLp);
        nameLp.width = w;
        nameLp.topMargin = cardH + Math.round(7 * density);
        h.name.setLayoutParams(nameLp);
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        holder.cancelLongPress();
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        Entry e = items.get(position);
        // "+" slots carry a synthetic component; guard anyway so a null never kills the launcher.
        return (e.component == null ? (ADD_PKG + ":" + position) : e.component.flattenToShortString()).hashCode();
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        if (position < 0 || position >= items.size()) return null;
        Entry e = items.get(position);
        return e.component == null ? null : e.component.flattenToShortString();
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            Entry e = items.get(i);
            if (e.component != null && e.component.flattenToShortString().equals(key)) return i;
        }
        return -1;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    float focusScale() {
        return !animations ? 1f : advancedEffects ? 1.12f : 1.10f;
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry; a card's accent when it set one. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    /** Corner radius for a tile of this width: 16dp at the design width, proportionally less when smaller. */
    static float cornerRadius(int tileWidthPx, float density) {
        return 16f * density * Math.min(1f, tileWidthPx / (DESIGN_W_DP * density));
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;
        final boolean animations;
        final boolean advancedEffects;
        final boolean reducedMotion;
        /** True while this tile sits in the dock row, where it grows from its own centre. */
        boolean dock;
        final float density;
        private final AppAdapter owner;
        private Runnable longPressRunnable;
        private boolean longPressFired;

        Holder(View v, AppAdapter owner, TileListener listener, boolean animations, boolean advancedEffects,
               boolean reducedMotion, int dockSize) {
            super(v);
            this.owner = owner;
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);
            this.animations = animations;
            this.advancedEffects = advancedEffects;
            this.reducedMotion = reducedMotion;
            this.density = v.getResources().getDisplayMetrics().density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    // 16dp at the design width, scaled down with the tile so small tiles keep the same shape.
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius(view.getWidth(), density));
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
                // In the dock editor OK has two jobs: a tap opens the candidate bar, a long hold
                // grabs the tile for re-ordering. Handled here (instead of the View's long-click)
                // so the two never fire for the same press.
                if (owner.editing && ok) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        boolean alreadyLong = (event.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0
                                || event.getRepeatCount() > 0;
                        if (alreadyLong) {
                            // The system already decided this is a hold (repeat or FLAG_LONG_PRESS):
                            // grab now, and suppress the short-press action on the matching up.
                            if (!longPressFired) {
                                if (longPressRunnable != null) {
                                    card.removeCallbacks(longPressRunnable);
                                    longPressRunnable = null;
                                }
                                longPressFired = true;
                                int p = getBindingAdapterPosition();
                                if (listener != null && p != RecyclerView.NO_POSITION) listener.onTileLongPress(p);
                            }
                        } else if (event.getRepeatCount() == 0) {
                            // Remote sends a plain down and no repeats: run our own hold timer.
                            cancelLongPress();
                            longPressRunnable = () -> {
                                longPressFired = true;
                                int p = getBindingAdapterPosition();
                                if (listener != null && p != RecyclerView.NO_POSITION) listener.onTileLongPress(p);
                            };
                            card.postDelayed(longPressRunnable, 550);
                        }
                        return true;
                    }
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        boolean fired = longPressFired;
                        cancelLongPress();
                        int p = getBindingAdapterPosition();
                        if (!fired && listener != null && p != RecyclerView.NO_POSITION) {
                            listener.onTileVerticalKey(p, keyCode);
                        }
                        return true;
                    }
                    return true;
                }
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                if (!focused) cancelLongPress();
                view.animate().cancel();
                banner.animate().cancel();
                name.animate().cancel();
                int pos = getBindingAdapterPosition();
                // A card in the dock sits in the band and grows from its own centre; a card in the
                // grid is lifted a little, the way a poster comes off the wall.
                dock = pos != RecyclerView.NO_POSITION && pos < dockSize;
                if (!animations) {
                    view.setScaleX(1f);
                    view.setScaleY(1f);
                    view.setTranslationX(0f);
                    view.setTranslationY(0f);
                    view.setTranslationZ(0f);
                    banner.setScaleX(1f);
                    banner.setScaleY(1f);
                    name.setAlpha(focused ? 1f : 0f);
                    name.setTranslationY(0f);
                } else if (reducedMotion) {
                    view.animate().scaleX(focused ? owner.focusScale() : 1f).scaleY(focused ? owner.focusScale() : 1f)
                            .translationY(focused ? lift() : 0f)
                            .translationZ(focused && advancedEffects ? 8f * density : 0f)
                            .setDuration(focused ? 120 : 80)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                    name.animate().alpha(focused ? 1f : 0f).setDuration(100).start();
                } else {
                    // Plain focus motion: the active tile smoothly grows to the focus scale, the
                    // one left behind shrinks back — one short decelerated move each, nothing else.
                    float s = focused ? owner.focusScale() : 1f;
                    view.animate().scaleX(s).scaleY(s)
                            .translationY(focused ? lift() : 0f)
                            .translationZ(focused && advancedEffects ? 8f * density : 0f)
                            .setDuration(focused ? 200 : 150)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                    if (advancedEffects) {
                        banner.animate().scaleX(focused ? 1.035f : 1f).scaleY(focused ? 1.035f : 1f)
                                .setDuration(200).start();
                    }
                    name.animate().alpha(focused ? 1f : 0f)
                            .translationY(focused ? density * 4f : -density * 6f)
                            .setDuration(200).start();
                }
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }

        /**
         * How far the focused card is lifted. A card in the dock stays put: it already has the
         * band around it, and lifting it inside the band looks lopsided. A card in the grid is
         * lifted a little, the way a poster comes off the wall.
         */
        float lift() {
            if (!advancedEffects) return 0f;
            return dock ? 0f : -4f * density;
        }

        /** Drops a pending "hold OK" timer (focus lost, view recycled, or the press was handled). */
        void cancelLongPress() {
            if (longPressRunnable != null) {
                card.removeCallbacks(longPressRunnable);
                longPressRunnable = null;
            }
            longPressFired = false;
        }
    }
}
