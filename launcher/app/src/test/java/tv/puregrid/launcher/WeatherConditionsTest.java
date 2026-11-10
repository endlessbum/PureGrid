package tv.puregrid.launcher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Plain JVM tests for the four provider condition tables: one wrong row means a wrong weather
 * word for every user of that provider. Unknown inputs must map to 0 ("no condition text").
 */
public class WeatherConditionsTest {

    // ---- MET Norway (primary provider) -----------------------------------------------------------

    @Test
    public void metNoStripsTheTimeOfDayTail() {
        assertEquals(R.string.condition_clear, Weather.conditionResMetNo("clearsky_day"));
        assertEquals(R.string.condition_clear, Weather.conditionResMetNo("clearsky_night"));
        assertEquals(R.string.condition_partly_cloudy, Weather.conditionResMetNo("partlycloudy_polartwilight"));
        assertEquals(R.string.condition_light_rain, Weather.conditionResMetNo("lightrainshowers_day"));
    }

    @Test
    public void metNoThunderBeatsTheTailAndChecksHail() {
        assertEquals(R.string.condition_thunderstorm_with_rain, Weather.conditionResMetNo("thunderstorm_day"));
        assertEquals(R.string.condition_thunderstorm_with_hail, Weather.conditionResMetNo("thunderstormhail"));
    }

    @Test
    public void metNoSnowAndSleetFamilies() {
        assertEquals(R.string.condition_snow, Weather.conditionResMetNo("snow"));
        assertEquals(R.string.condition_light_snow, Weather.conditionResMetNo("lightsnowshowers_night"));
        assertEquals(R.string.condition_wet_snow, Weather.conditionResMetNo("sleetshowers_day"));
    }

    @Test
    public void metNoUnknownOrNullIsZero() {
        assertEquals(0, Weather.conditionResMetNo("meteorshower"));
        assertEquals(0, Weather.conditionResMetNo(""));
        assertEquals(0, Weather.conditionResMetNo(null));
    }

    // ---- wttr.in (WWO codes) ---------------------------------------------------------------------

    @Test
    public void wwoKnownCodes() {
        assertEquals(R.string.condition_clear, Weather.conditionResWwo(113));
        assertEquals(R.string.condition_partly_cloudy, Weather.conditionResWwo(116));
        assertEquals(R.string.condition_fog, Weather.conditionResWwo(248));
        assertEquals(R.string.condition_heavy_rain, Weather.conditionResWwo(359));
        assertEquals(R.string.condition_thunderstorm, Weather.conditionResWwo(386));
        assertEquals(R.string.condition_snow, Weather.conditionResWwo(227));
        assertEquals(R.string.condition_wet_snow, Weather.conditionResWwo(350));
    }

    @Test
    public void wwoUnknownCodeIsZero() {
        assertEquals(0, Weather.conditionResWwo(999));
        assertEquals(0, Weather.conditionResWwo(0));
    }

    // ---- Yandex ----------------------------------------------------------------------------------

    @Test
    public void yandexSpellsConditionsAsTheApiDoes() {
        assertEquals(R.string.condition_clear, Weather.conditionResYandex("clear"));
        assertEquals(R.string.condition_partly_cloudy, Weather.conditionResYandex("partly-cloudy"));
        assertEquals(R.string.condition_light_rain, Weather.conditionResYandex("drizzle"));
        assertEquals(R.string.condition_showers, Weather.conditionResYandex("showers"));
        assertEquals(R.string.condition_thunderstorm_with_hail, Weather.conditionResYandex("thunderstorm-with-hail"));
    }

    @Test
    public void yandexUnknownIsZero() {
        assertEquals(0, Weather.conditionResYandex("bogus"));
        assertEquals(0, Weather.conditionResYandex("Partly-Cloudy"));   // case sensitive by contract
    }

    // ---- Open-Meteo (WMO codes) ------------------------------------------------------------------

    @Test
    public void wmoKnownCodes() {
        assertEquals(R.string.condition_clear, Weather.conditionResWmo(0));
        assertEquals(R.string.condition_partly_cloudy, Weather.conditionResWmo(2));
        assertEquals(R.string.condition_overcast, Weather.conditionResWmo(3));
        assertEquals(R.string.condition_fog, Weather.conditionResWmo(45));
        assertEquals(R.string.condition_light_rain, Weather.conditionResWmo(80));
        assertEquals(R.string.condition_heavy_rain, Weather.conditionResWmo(65));
        assertEquals(R.string.condition_wet_snow, Weather.conditionResWmo(66));
        assertEquals(R.string.condition_snow_showers, Weather.conditionResWmo(85));
        assertEquals(R.string.condition_thunderstorm, Weather.conditionResWmo(95));
        assertEquals(R.string.condition_thunderstorm_with_hail, Weather.conditionResWmo(96));
    }

    @Test
    public void wmoUnknownCodeIsZero() {
        assertEquals(0, Weather.conditionResWmo(42));
        assertEquals(0, Weather.conditionResWmo(-1));
    }
}
