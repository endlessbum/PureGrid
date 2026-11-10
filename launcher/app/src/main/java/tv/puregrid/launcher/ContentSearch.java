package tv.puregrid.launcher;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Content search over the installed TV apps — the Leanback global-search protocol. Video apps
 * that want their titles searchable from the launcher (YouTube, Netflix, Kinopoisk, …) export a
 * ContentProvider with an intent-filter for {@link SearchManager#INTENT_ACTION_GLOBAL_SEARCH};
 * the launcher enumerates those providers, forwards the user's query and gets back suggestion
 * rows: a title, a poster and the pieces of the intent that opens that title inside the source
 * app. A provider may refuse the call (the GLOBAL_SEARCH permission is system-only) — that app
 * is then simply absent from the content rows.
 *
 * The pure row logic — launch-intent assembly and dedup — is written string-only so the JVM tests
 * can run it without an Android runtime.
 */
final class ContentSearch {

    /** Same tag the SearchActivity diagnostics use; one failed provider call is the usual reason
     *  an expected app is missing from the content rows (refused GLOBAL_SEARCH, a crashed provider). */
    private static final String TAG = "PureGridSearch";

    private ContentSearch() {
    }

    /** One search-capable app: the provider authority to query and the package for badge and fallback. */
    static final class Provider {
        final String authority;
        final String pkg;
        final String label;

        Provider(String authority, String pkg, String label) {
            this.authority = authority;
            this.pkg = pkg;
            this.label = label;
        }
    }

    /**
     * One suggestion row, verbatim from the provider. No Android types, so tests can build these.
     * All the intent* fields are optional (null): they say how the row opens.
     */
    static final class Result {
        final String title;
        final String subtitle;
        /** Poster image reference — a content:/android.resource:/https: URI — or null. */
        final String poster;
        final String pkg;
        final String intentAction;
        final String intentData;
        final String intentDataId;
        final String intentExtraData;
        final String intentQuery;

        Result(String title, String subtitle, String poster, String pkg,
               String intentAction, String intentData, String intentDataId,
               String intentExtraData, String intentQuery) {
            this.title = title;
            this.subtitle = subtitle;
            this.poster = poster;
            this.pkg = pkg;
            this.intentAction = intentAction;
            this.intentData = intentData;
            this.intentDataId = intentDataId;
            this.intentExtraData = intentExtraData;
            this.intentQuery = intentQuery;
        }
    }

    /** The intent a suggestion row asks for, as plain strings; {@link #toIntent} wraps it for real. */
    static final class LaunchSpec {
        /** Package of the provider that answered — {@link #toIntent} pins the launch to it. */
        final String pkg;
        final String action;
        final String data;
        final String query;
        final String extraData;

        LaunchSpec(String pkg, String action, String data, String query, String extraData) {
            this.pkg = pkg;
            this.action = action;
            this.data = data;
            this.query = query;
            this.extraData = extraData;
        }
    }

    /** Rows requested from a provider per query; a strip of eight cards reads well on a TV. The
     *  real value arrives from Config (search_provider_limit, clamped 1..40) — this constant only
     *  documents the shape the default was chosen for. */
    static final int LIMIT_PER_PROVIDER = 8;

    /** The poster-uri column is the leanback extension; the framework names only the blob one. */
    private static final String COLUMN_ICON_URI = "suggest_icon_1_uri";

    /** Lists every installed app that exports a search-suggestions provider. */
    static List<Provider> discoverProviders(PackageManager pm) {
        List<Provider> out = new ArrayList<>();
        List<ResolveInfo> found;
        try {
            found = pm.queryIntentContentProviders(
                    new Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH), 0);
        } catch (Exception e) {
            return out;
        }
        for (ResolveInfo ri : found) {
            ProviderInfo pi = ri.providerInfo;
            if (pi == null || pi.authority == null) continue;
            String label = pi.packageName;
            try {
                if (pi.applicationInfo != null) label = String.valueOf(pm.getApplicationLabel(pi.applicationInfo));
            } catch (Exception ignored) {
            }
            // A provider can serve several authorities ("a;b"); each is its own query target.
            for (String authority : pi.authority.split(";")) {
                if (authority.isEmpty()) continue;
                boolean known = false;
                for (Provider p : out) known |= p.authority.equals(authority);
                if (!known) out.add(new Provider(authority, pi.packageName, label));
            }
        }
        return out;
    }

    /**
     * Queries one provider on the caller's thread. Returns the rows, deduped, or an empty list
     * when the provider is closed to us or misbehaves — a silent skip, since the app itself is
     * still reachable through the app search.
     */
    static List<Result> query(Context ctx, Provider provider, String query, int limit) {
        // The custom-suggestions contract: the query is the path segment after
        // search_suggest_query, and the provider is asked to cap the answer.
        Uri uri = Uri.parse("content://" + provider.authority).buildUpon()
                .appendPath(SearchManager.SUGGEST_URI_PATH_QUERY)
                .appendPath(query)
                .appendQueryParameter(SearchManager.SUGGEST_PARAMETER_LIMIT, String.valueOf(limit))
                .build();
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, null, null, null, null);
            if (c == null) {
                Log.w(TAG, provider.authority + " [" + provider.pkg + "] answered null for \"" + query + "\"");
                return new ArrayList<>();
            }
            List<Result> out = new ArrayList<>();
            while (c.moveToNext() && out.size() < limit) {
                Result r = readRow(c, provider.pkg);
                if (r != null) out.add(r);
            }
            return dedup(out);
        } catch (Exception e) {
            Log.w(TAG, provider.authority + " [" + provider.pkg + "] query \"" + query + "\" failed: " + e);
            return new ArrayList<>();
        } finally {
            if (c != null) c.close();
        }
    }

    private static Result readRow(Cursor c, String pkg) {
        String title = string(c, SearchManager.SUGGEST_COLUMN_TEXT_1);
        if (title == null || title.trim().isEmpty()) return null;
        String poster = string(c, COLUMN_ICON_URI);
        if (poster == null) poster = string(c, SearchManager.SUGGEST_COLUMN_ICON_1);
        return new Result(title.trim(), string(c, SearchManager.SUGGEST_COLUMN_TEXT_2), poster, pkg,
                string(c, SearchManager.SUGGEST_COLUMN_INTENT_ACTION),
                string(c, SearchManager.SUGGEST_COLUMN_INTENT_DATA),
                string(c, SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID),
                string(c, SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA),
                string(c, SearchManager.SUGGEST_COLUMN_QUERY));
    }

    private static String string(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 ? null : c.getString(i);
    }

    /**
     * The intent the row asks for, as plain strings, per the global-search contract: the row's
     * action, or VIEW when there is data to open, or SEARCH when there is only a query; the data
     * Uri is the data column with the data id appended, or content://{authority} when only the id
     * is given. Null when the row carries no way to open anything — the caller then just launches
     * the app.
     */
    static LaunchSpec buildLaunch(Result r, String authority) {
        if (r == null) return null;
        String data = blankToNull(r.intentData);
        String dataId = blankToNull(r.intentDataId);
        String query = blankToNull(r.intentQuery);
        if (data == null && dataId != null) data = "content://" + authority;
        if (data != null && dataId != null) data = appendPath(data, dataId);
        String action = blankToNull(r.intentAction);
        if (action == null) {
            // Intent.ACTION_VIEW / ACTION_SEARCH are compile-time constants, inlined here, so the
            // JVM tests run this without an Android runtime.
            if (data != null) action = "android.intent.action.VIEW";
            else if (query != null) action = "android.intent.action.SEARCH";
            else return null;
        }
        return new LaunchSpec(r.pkg, action, data, query, blankToNull(r.intentExtraData));
    }

    /** The Android-side half of {@link #buildLaunch}: the spec as a real Intent. */
    static Intent toIntent(LaunchSpec spec) {
        Intent i = new Intent(spec.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // The intent resolves inside the provider's own app, no matter what the row asked for:
        // the global-search contract hands intent building to the provider, and without the pin
        // an installed app could "press" any system action or another app's deep link on the
        // user's behalf. When nothing in that package matches, the caller falls back to a plain
        // app launch.
        if (spec.pkg != null) i.setPackage(spec.pkg);
        if (spec.data != null) i.setData(Uri.parse(spec.data));
        if (spec.query != null) i.putExtra(SearchManager.QUERY, spec.query);
        if (spec.extraData != null) i.putExtra(SearchManager.EXTRA_DATA_KEY, spec.extraData);
        return i;
    }

    /** Appends one path segment the way Uri.buildUpon().appendPath would — including the part
     *  that matters: a query string stays a query ("content://x/film?partner=1" + "42" becomes
     *  "content://x/film/42?partner=1"), and the id never lands inside a query value. Kept
     *  string-based so the JVM tests exercise the same code. */
    private static String appendPath(String base, String segment) {
        int q = base.indexOf('?');
        String head = q >= 0 ? base.substring(0, q) : base;
        String tail = q >= 0 ? base.substring(q) : "";
        if (!head.endsWith("/")) head += "/";
        return head + segment + tail;
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s;
    }

    /** Drops repeats: the same app offering the same title twice (row ids, shortcuts, seasons). */
    static List<Result> dedup(List<Result> in) {
        Map<String, Result> byKey = new LinkedHashMap<>();
        for (Result r : in) {
            String key = (r.pkg == null ? "" : r.pkg) + "|"
                    + (r.title == null ? "" : r.title.toLowerCase(Locale.ROOT));
            byKey.putIfAbsent(key, r);
        }
        return new ArrayList<>(byKey.values());
    }
}
