package tv.puregrid.launcher;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.provider.Settings;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The clock's weather slide. The reading comes from Yandex Weather when an API key has been
 * pushed ({@code settings put secure yandex_weather_key <key>}, free key from yandex.ru/dev/weather)
 * and, keyless by default, from MET Norway (api.met.no) — free, no key, no registration, their
 * own forecast models; the terms only ask for an identifiable User-Agent, without which the
 * endpoint answers 403. With MET Norway unreachable, wttr.in and Open-Meteo (open-meteo.com)
 * follow — all three serve the blend of national models the big apps show, and all three were
 * checked against the networks they actually reach (from some Russian ISPs Open-Meteo's host is
 * filtered outright and wttr.in trickles, while MET Norway answers in a fraction of a second).
 * Until the network or the location can be had, {@link #fetch} returns null and the clock simply
 * never gives way. Setting {@code settings put secure yandex_weather_test 1} plays a fixed
 * reading for checking the slide.
 */
final class Weather {

    private Weather() {
    }

    /** MET Norway refuses unidentified clients with a 403: every request names the launcher. */
    private static final String USER_AGENT =
            "PureGrid-TV/2.4 (Android TV home launcher; https://github.com/endlessbum/PureGrid)";

    /** One reading: the temperature in degrees Celsius and the condition's string resource (0 = none). */
    static final class Reading {
        final int temperature;
        final int conditionRes;

        Reading(int temperature, int conditionRes) {
            this.temperature = temperature;
            this.conditionRes = conditionRes;
        }
    }

    /** Debug-only log line: silent in the release installs, where the debuggable flag reads false. */
    private static void dbg(Context ctx, String message) {
        if ((ctx.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            android.util.Log.d("PureGrid", message);
        }
    }

    /** Fetches the current weather, or null when it cannot be had. Runs on a background thread. */
    static Reading fetch(Context ctx) {
        String test = Settings.Secure.getString(ctx.getContentResolver(), "yandex_weather_test");
        dbg(ctx, "weather test flag=" + test);
        if ("1".equals(test)) {
            return new Reading(21, R.string.condition_clear);
        }
        // ADB check hook: forces the no-connection state without taking the network down
        // (disabling Wi-Fi would also drop the debugging channel).
        if ("1".equals(Settings.Secure.getString(ctx.getContentResolver(), "yandex_weather_offline"))) {
            dbg(ctx, "weather: offline forced (test)");
            return null;
        }
        Reading r = yandex(ctx);
        if (r != null) return r;
        r = metNo(ctx);
        if (r != null) return r;
        r = wttr(ctx);
        return r != null ? r : openMeteo(ctx);
    }

    /**
     * True when the slide must show the crossed cloud instead of a reading: the system sees no
     * validated internet route (the same state its own status bar reports). The ADB test flag
     * {@code yandex_weather_offline} forces this state.
     */
    static boolean offline(Context ctx) {
        if ("1".equals(Settings.Secure.getString(ctx.getContentResolver(), "yandex_weather_offline"))) {
            return true;
        }
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            android.net.Network n = cm.getActiveNetwork();
            if (n == null) return true;
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            return caps == null
                    || !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception e) {
            return true;
        }
    }

    /** Yandex Weather's own API, used only when a key has been pushed to the TV. */
    private static Reading yandex(Context ctx) {
        try {
            String key = Settings.Secure.getString(ctx.getContentResolver(), "yandex_weather_key");
            if (key == null || key.trim().isEmpty()) return null;
            double[] ll = locate(ctx);
            if (ll == null) return null;
            String query = "{weatherByPoint(request:{lat:" + ll[0] + ",lon:" + ll[1]
                    + "}){now{temperature condition}}}";
            String body = new JSONObject().put("query", query).toString();
            String resp = http(ctx, "https://api.weather.yandex.ru/graphql/query", body, key.trim(), null);
            if (resp == null) return null;
            Reading r = parseYandex(resp);
            if (r == null) {
                dbg(ctx, "weather: yandex answer has no temperature");
                return null;
            }
            dbg(ctx, "weather from yandex: " + r.temperature + "deg");
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * MET Norway (api.met.no), the primary keyless source: free, no key, no registration, their
     * own forecast models. The endpoint refuses unidentified clients (403 for the stock Dalvik
     * User-Agent too), so the request carries the launcher's own. The word comes from the coming
     * hour's symbol when there is one, so the slide shows what is about to be on the street.
     */
    private static Reading metNo(Context ctx) {
        try {
            double[] ll = locate(ctx);
            if (ll == null) {
                dbg(ctx, "weather: no location");
                return null;
            }
            String url = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat="
                    + String.format(java.util.Locale.US, "%.4f", ll[0])
                    + "&lon=" + String.format(java.util.Locale.US, "%.4f", ll[1]);
            String resp = http(ctx, url, null, null, USER_AGENT);
            if (resp == null) {
                dbg(ctx, "weather: met.no unreachable");
                return null;
            }
            Reading r = parseMetNo(resp);
            if (r == null) {
                dbg(ctx, "weather: met.no answer has no temperature");
                return null;
            }
            dbg(ctx, "weather from met.no: " + r.temperature + "deg");
            return r;
        } catch (Exception e) {
            dbg(ctx, "weather: met.no failed " + e);
            return null;
        }
    }

    /**
     * wttr.in, the first keyless fallback: free, no key, no registration, the WWO model blend.
     * Coordinates go into the URL rounded to two decimals — enough for weather, and it lets the
     * service's own per-location cache answer. It can take a while to answer (tens of seconds
     * from some networks), which is fine here: the fetch always runs off the UI thread.
     */
    private static Reading wttr(Context ctx) {
        try {
            double[] ll = locate(ctx);
            if (ll == null) {
                dbg(ctx, "weather: no location");
                return null;
            }
            String where = String.format(java.util.Locale.US, "%.2f,%.2f", ll[0], ll[1]);
            String resp = http(ctx, "https://wttr.in/" + where + "?format=j1", null, null, null);
            if (resp == null) {
                dbg(ctx, "weather: wttr.in unreachable");
                return null;
            }
            Reading r = parseWttr(resp);
            if (r == null) {
                dbg(ctx, "weather: wttr.in answer has no temperature");
                return null;
            }
            dbg(ctx, "weather from wttr.in: " + r.temperature + "deg");
            return r;
        } catch (Exception e) {
            dbg(ctx, "weather: wttr.in failed " + e);
            return null;
        }
    }

    /**
     * Open-Meteo, the keyless fallback for the networks wttr.in cannot be had from: a blend of
     * the national weather models (the same data the consumer apps show), free for any use, no
     * key and no registration to keep alive.
     */
    private static Reading openMeteo(Context ctx) {
        try {
            double[] ll = locate(ctx);
            if (ll == null) {
                dbg(ctx, "weather: no location");
                return null;
            }
            String url = "https://api.open-meteo.com/v1/forecast?latitude=" + ll[0]
                    + "&longitude=" + ll[1]
                    + "&current=temperature_2m,weather_code&timezone=auto";
            String resp = http(ctx, url, null, null, null);
            if (resp == null) {
                dbg(ctx, "weather: open-meteo unreachable");
                return null;
            }
            Reading r = parseOpenMeteo(resp);
            dbg(ctx, "weather from open-meteo: " + r.temperature + "deg");
            return r;
        } catch (Exception e) {
            dbg(ctx, "weather: open-meteo failed " + e);
            return null;
        }
    }

    /**
     * Where the weather is for. A TV rarely has a location fix, so the last known location (the
     * launcher holds ACCESS_FINE_LOCATION) is taken when there is one, and the network's idea of
     * where we are (ipapi.co, no key) is the fallback.
     */
    private static double[] locate(Context ctx) {
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                Location best = null;
                for (String provider : lm.getProviders(true)) {
                    try {
                        Location l = lm.getLastKnownLocation(provider);
                        if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                    } catch (SecurityException ignored) {
                    }
                }
                if (best != null) return new double[]{best.getLatitude(), best.getLongitude()};
            }
        } catch (Exception ignored) {
        }
        try {
            // The IP lookup is a third-party call: the answer is kept for good (see cachedCoords).
            double[] cached = cachedCoords(ctx);
            if (cached != null) return cached;
            // ipwho.is first: free, HTTPS, no key, and reachable where ipapi.co is not.
            String resp = http(ctx, "https://ipwho.is/", null, null, null);
            if (resp != null) {
                JSONObject o = new JSONObject(resp);
                if (o.optBoolean("success", false)) {
                    double[] out = new double[]{o.getDouble("latitude"), o.getDouble("longitude")};
                    rememberCoords(ctx, out);
                    return out;
                }
                dbg(ctx, "weather: ipwho.is answered success=" + o.optBoolean("success"));
            }
            // ipapi.co stays as the fallback for the day ipwho.is goes away.
            resp = http(ctx, "https://ipapi.co/json/", null, null, null);
            if (resp == null) return null;
            JSONObject o = new JSONObject(resp);
            double[] out = new double[]{o.getDouble("latitude"), o.getDouble("longitude")};
            rememberCoords(ctx, out);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** Where the IP lookup landed, so a TV without a location fix does not phone ipapi.co on
     *  every refresh. The answer is kept for good: a TV does not move, and a re-ask on a timer
     *  would turn one failed lookup into permanent weather loss — with no GPS fix to fall
     *  back to, the slide would never come back until the lookup happened to succeed again. */
    private static final String COORDS_PREF = "weather_ip_coords";

    private static double[] cachedCoords(Context ctx) {
        try {
            android.content.SharedPreferences p =
                    ctx.getSharedPreferences(COORDS_PREF, Context.MODE_PRIVATE);
            if (p.getLong("at", 0) == 0) return null;
            double lat = Double.longBitsToDouble(p.getLong("lat", 0));
            double lon = Double.longBitsToDouble(p.getLong("lon", 0));
            if (lat == 0 && lon == 0) return null;
            return new double[]{lat, lon};
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void rememberCoords(Context ctx, double[] coords) {
        try {
            ctx.getSharedPreferences(COORDS_PREF, Context.MODE_PRIVATE).edit()
                    .putLong("at", System.currentTimeMillis())
                    .putLong("lat", Double.doubleToLongBits(coords[0]))
                    .putLong("lon", Double.doubleToLongBits(coords[1]))
                    .apply();
        } catch (Exception ignored) {
        }
    }

    /** One helper for both calls: POST with a body and the key header, or GET without either.
     *  The timeouts are long on purpose (wttr.in can think for tens of seconds), the fetches
     *  always run off the UI thread. Failures are logged with their reason when debuggable. */
    private static String http(Context ctx, String url, String body, String key, String ua) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(20_000);
            c.setReadTimeout(20_000);
            if (ua != null) c.setRequestProperty("User-Agent", ua);
            if (key != null) c.setRequestProperty("X-Yandex-Weather-Key", key);
            if (body != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                dbg(ctx, "http: " + url + " -> " + code);
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[4096];
                // Capped: a broken proxy answering with gigabytes must not fill the heap; the
                // biggest honest answer here is a couple of kilobytes of JSON.
                int cap = 256 * 1024;
                for (int n; out.size() < cap && (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            dbg(ctx, "http: " + url + " failed " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---- response parsing -------------------------------------------------------------------------
    // Package-visible so the JVM tests (with the real org.json on the test classpath) parse
    // recorded answers; the callers above only catch and log. A malformed answer throws
    // JSONException, which the caller's catch turns into null; null here itself means the
    // answer was fine but carried no temperature at all.

    /** MET Norway's compact locationforecast: the first timeseries entry's temperature, and the
     *  coming hour's symbol when there is one (next_1_hours, else next_6_hours, else none). */
    static Reading parseMetNo(String json) throws JSONException {
        JSONObject first = new JSONObject(json).getJSONObject("properties")
                .getJSONArray("timeseries").getJSONObject(0);
        JSONObject data = first.getJSONObject("data");
        double t = data.getJSONObject("instant").getJSONObject("details")
                .optDouble("air_temperature", Double.NaN);
        if (Double.isNaN(t)) return null;
        String symbol = "";
        JSONObject ahead = data.optJSONObject("next_1_hours");
        if (ahead == null) ahead = data.optJSONObject("next_6_hours");
        if (ahead != null && ahead.optJSONObject("summary") != null) {
            symbol = ahead.getJSONObject("summary").optString("symbol_code");
        }
        return new Reading((int) Math.round(t), conditionResMetNo(symbol));
    }

    /** wttr.in's j1 format: the current condition's temperature and WWO weather code. */
    static Reading parseWttr(String json) throws JSONException {
        JSONObject now = new JSONObject(json).getJSONArray("current_condition").getJSONObject(0);
        double t = now.optDouble("temp_C", Double.NaN);
        if (Double.isNaN(t)) return null;
        return new Reading((int) Math.round(t), conditionResWwo(now.optInt("weatherCode", -1)));
    }

    /** Open-Meteo's forecast: the current block's temperature and WMO weather code. */
    static Reading parseOpenMeteo(String json) throws JSONException {
        JSONObject now = new JSONObject(json).getJSONObject("current");
        return new Reading((int) Math.round(now.getDouble("temperature_2m")),
                conditionResWmo(now.optInt("weather_code", -1)));
    }

    /** Yandex Weather's GraphQL answer: the point's current temperature and condition name. */
    static Reading parseYandex(String json) throws JSONException {
        JSONObject now = new JSONObject(json)
                .getJSONObject("data").getJSONObject("weatherByPoint").getJSONObject("now");
        int temp = now.optInt("temperature", Integer.MIN_VALUE);
        if (temp == Integer.MIN_VALUE) return null;
        return new Reading(temp, conditionResYandex(now.optString("condition")));
    }

    /** MET Norway's symbol codes ("lightrainshowers_day") to the conditions the slide knows.
     *  Package-visible so the JVM tests exercise the same tables the launcher runs. */
    static int conditionResMetNo(String symbol) {
        if (symbol == null || symbol.isEmpty()) return 0;
        if (symbol.contains("thunder")) {
            if (symbol.contains("hail")) return R.string.condition_thunderstorm_with_hail;
            return R.string.condition_thunderstorm_with_rain;
        }
        // The time-of-day tail ("_day", "_night", "_polartwilight") carries no weather of its own.
        int cut = symbol.indexOf('_');
        String base = cut >= 0 ? symbol.substring(0, cut) : symbol;
        switch (base) {
            case "clearsky": return R.string.condition_clear;
            case "fair":
            case "partlycloudy": return R.string.condition_partly_cloudy;
            case "cloudy": return R.string.condition_cloudy;
            case "overcast": return R.string.condition_overcast;
            case "fog": return R.string.condition_fog;
            case "drizzle":
            case "lightrain":
            case "lightrainshowers": return R.string.condition_light_rain;
            case "rain": return R.string.condition_rain;
            case "rainshowers": return R.string.condition_showers;
            case "heavyrain":
            case "heavyrainshowers": return R.string.condition_heavy_rain;
            case "lightsnow":
            case "lightsnowshowers": return R.string.condition_light_snow;
            case "snow":
            case "heavysnow": return R.string.condition_snow;
            case "snowshowers": return R.string.condition_snow_showers;
            case "sleet":
            case "sleetshowers":
            case "lightsleet":
            case "heavysleet":
            case "lightsleetshowers":
            case "heavysleetshowers": return R.string.condition_wet_snow;
            default: return 0;
        }
    }

    /** wttr.in's WWO weather codes to the same conditions the slide knows. */
    static int conditionResWwo(int code) {
        switch (code) {
            case 113: return R.string.condition_clear;
            case 116: return R.string.condition_partly_cloudy;
            case 119: return R.string.condition_cloudy;
            case 122: return R.string.condition_overcast;
            case 143:
            case 248:
            case 260: return R.string.condition_fog;
            case 176:
            case 263:
            case 266:
            case 281:
            case 293:
            case 296: return R.string.condition_light_rain;
            case 299:
            case 302:
            case 311:
            case 314: return R.string.condition_rain;
            case 305:
            case 308:
            case 353:
            case 356:
            case 359: return R.string.condition_heavy_rain;
            case 200:
            case 386:
            case 389: return R.string.condition_thunderstorm;
            case 179:
            case 323:
            case 326:
            case 368: return R.string.condition_light_snow;
            case 182:
            case 185:
            case 317:
            case 320:
            case 350:
            case 362:
            case 365:
            case 374:
            case 377: return R.string.condition_wet_snow;
            case 227:
            case 230:
            case 329:
            case 332:
            case 335:
            case 371: return R.string.condition_snow;
            default: return 0;
        }
    }

    /** Yandex's condition names, as the API spells them, to what the slide shows. */
    static int conditionResYandex(String condition) {
        switch (condition) {
            case "clear": return R.string.condition_clear;
            case "partly-cloudy": return R.string.condition_partly_cloudy;
            case "cloudy": return R.string.condition_cloudy;
            case "overcast": return R.string.condition_overcast;
            case "fog": return R.string.condition_fog;
            case "drizzle": return R.string.condition_light_rain;   // морось
            case "light-rain": return R.string.condition_light_rain;
            case "rain": return R.string.condition_rain;
            case "heavy-rain": return R.string.condition_heavy_rain;
            case "showers": return R.string.condition_showers;
            case "wet-snow": return R.string.condition_wet_snow;
            case "light-snow": return R.string.condition_light_snow;
            case "snow": return R.string.condition_snow;
            case "snow-showers": return R.string.condition_snow_showers;
            case "hail": return R.string.condition_hail;
            case "thunderstorm": return R.string.condition_thunderstorm;
            case "thunderstorm-with-rain": return R.string.condition_thunderstorm_with_rain;
            case "thunderstorm-with-hail": return R.string.condition_thunderstorm_with_hail;
            default: return 0;
        }
    }

    /** Open-Meteo's WMO weather codes to the same conditions the slide knows. */
    static int conditionResWmo(int code) {
        switch (code) {
            case 0: return R.string.condition_clear;
            case 1:
            case 2: return R.string.condition_partly_cloudy;
            case 3: return R.string.condition_overcast;
            case 45:
            case 48: return R.string.condition_fog;
            case 51:
            case 53:
            case 55:
            case 56:
            case 57:
            case 61:
            case 80: return R.string.condition_light_rain;
            case 63: return R.string.condition_rain;
            case 65:
            case 82: return R.string.condition_heavy_rain;
            case 66:
            case 67: return R.string.condition_wet_snow;
            case 71:
            case 77: return R.string.condition_light_snow;
            case 73: return R.string.condition_snow;
            case 75:
            case 85:
            case 86: return R.string.condition_snow_showers;
            case 81: return R.string.condition_showers;
            case 95: return R.string.condition_thunderstorm;
            case 96:
            case 99: return R.string.condition_thunderstorm_with_hail;
            default: return 0;
        }
    }
}
