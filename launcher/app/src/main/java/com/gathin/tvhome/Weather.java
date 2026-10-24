package com.gathin.tvhome;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The clock's weather slide. The reading comes from Yandex Weather when an API key has been
 * pushed ({@code settings put secure yandex_weather_key <key>}, free key from yandex.ru/dev/weather)
 * and, keyless by default, from Open-Meteo (open-meteo.com) — free, no key, no registration, the
 * same national forecast models the big apps blend. Until the network or the location can be had,
 * {@link #fetch} returns null and the clock simply never gives way. Setting
 * {@code settings put secure yandex_weather_test 1} plays a fixed reading for checking the slide.
 */
final class Weather {

    private Weather() {
    }

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
            String resp = http("https://api.weather.yandex.ru/graphql/query", body, key.trim());
            if (resp == null) return null;
            JSONObject now = new JSONObject(resp)
                    .getJSONObject("data").getJSONObject("weatherByPoint").getJSONObject("now");
            int temp = now.optInt("temperature", Integer.MIN_VALUE);
            if (temp == Integer.MIN_VALUE) return null;
            dbg(ctx, "weather from yandex: " + temp + "deg");
            return new Reading(temp, conditionResYandex(now.optString("condition")));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Open-Meteo, the keyless default: a blend of the national weather models (the same data the
     * consumer apps show), free for any use, no key and no registration to keep alive.
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
            String resp = http(url, null, null);
            if (resp == null) {
                dbg(ctx, "weather: open-meteo unreachable");
                return null;
            }
            JSONObject now = new JSONObject(resp).getJSONObject("current");
            int temp = (int) Math.round(now.getDouble("temperature_2m"));
            dbg(ctx, "weather from open-meteo: " + temp + "deg code="
                    + now.optInt("weather_code"));
            return new Reading(temp, conditionResWmo(now.optInt("weather_code", -1)));
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
            String resp = http("https://ipapi.co/json/", null, null);
            if (resp == null) return null;
            JSONObject o = new JSONObject(resp);
            return new double[]{o.getDouble("latitude"), o.getDouble("longitude")};
        } catch (Exception e) {
            return null;
        }
    }

    /** One helper for both calls: POST with a body and the key header, or GET without either. */
    private static String http(String url, String body, String key) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            if (key != null) c.setRequestProperty("X-Yandex-Weather-Key", key);
            if (body != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[4096];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Yandex's condition names, as the API spells them, to what the slide shows. */
    private static int conditionResYandex(String condition) {
        switch (condition) {
            case "clear": return R.string.condition_clear;
            case "partly-cloudy": return R.string.condition_partly_cloudy;
            case "cloudy": return R.string.condition_cloudy;
            case "overcast": return R.string.condition_overcast;
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
    private static int conditionResWmo(int code) {
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
