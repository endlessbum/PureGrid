package tv.puregrid.launcher;

import org.junit.Test;
import org.json.JSONException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * Plain JVM tests for the four provider answer parsers (the real org.json runs on the test
 * classpath): fixtures in the shape the services actually answer, plus the malformed and
 * no-temperature cases the callers turn into a silent fallback to the next provider.
 */
public class WeatherParsingTest {

    private static void assertMalformed(String json) {
        try {
            Weather.parseMetNo(json);
            fail("expected JSONException for " + json);
        } catch (JSONException expected) {
        }
    }

    // ---- MET Norway -------------------------------------------------------------------------------

    @Test
    public void metNoPrefersTheComingHourSymbol() throws JSONException {
        Weather.Reading r = Weather.parseMetNo("{\"properties\":{\"timeseries\":[{\"data\":{"
                + "\"instant\":{\"details\":{\"air_temperature\":12.4}},"
                + "\"next_1_hours\":{\"summary\":{\"symbol_code\":\"partlycloudy_day\"}}}}]}}");
        assertNotNull(r);
        assertEquals(12, r.temperature);
        assertEquals(R.string.condition_partly_cloudy, r.conditionRes);
    }

    @Test
    public void metNoFallsBackToNextSixHoursThenToNoSymbol() throws JSONException {
        Weather.Reading six = Weather.parseMetNo("{\"properties\":{\"timeseries\":[{\"data\":{"
                + "\"instant\":{\"details\":{\"air_temperature\":0.4}},"
                + "\"next_6_hours\":{\"summary\":{\"symbol_code\":\"rain\"}}}}]}}");
        assertEquals(R.string.condition_rain, six.conditionRes);
        Weather.Reading none = Weather.parseMetNo("{\"properties\":{\"timeseries\":[{\"data\":{"
                + "\"instant\":{\"details\":{\"air_temperature\":-2.5}}}}]}}");
        assertEquals(-2, none.temperature);   // Math.round rounds half toward positive infinity
        assertEquals(0, none.conditionRes);
    }

    @Test
    public void metNoWithoutTemperatureIsANullNotAThrow() throws JSONException {
        assertNull(Weather.parseMetNo("{\"properties\":{\"timeseries\":[{\"data\":{"
                + "\"instant\":{\"details\":{}}}}]}}"));
    }

    @Test
    public void metNoMalformedAnswerThrows() throws JSONException {
        assertMalformed("not json");
        assertMalformed("{\"properties\":{\"timeseries\":[]}}");
    }

    // ---- wttr.in ----------------------------------------------------------------------------------

    @Test
    public void wttrReadsCurrentCondition() throws JSONException {
        Weather.Reading r = Weather.parseWttr("{\"current_condition\":["
                + "{\"temp_C\":\"12.6\",\"weatherCode\":\"116\"}]}");
        assertEquals(13, r.temperature);
        assertEquals(R.string.condition_partly_cloudy, r.conditionRes);
    }

    @Test
    public void wttrWithoutTempCIsNull() throws JSONException {
        assertNull(Weather.parseWttr("{\"current_condition\":[{\"weatherCode\":\"116\"}]}"));
    }

    // ---- Open-Meteo --------------------------------------------------------------------------------

    @Test
    public void openMeteoReadsTheCurrentBlock() throws JSONException {
        Weather.Reading r = Weather.parseOpenMeteo(
                "{\"current\":{\"temperature_2m\":21.6,\"weather_code\":63}}");
        assertEquals(22, r.temperature);
        assertEquals(R.string.condition_rain, r.conditionRes);
    }

    // ---- Yandex ------------------------------------------------------------------------------------

    @Test
    public void yandexReadsThePointNow() throws JSONException {
        Weather.Reading r = Weather.parseYandex(
                "{\"data\":{\"weatherByPoint\":{\"now\":{\"temperature\":5,\"condition\":\"clear\"}}}}");
        assertEquals(5, r.temperature);
        assertEquals(R.string.condition_clear, r.conditionRes);
    }

    @Test
    public void yandexWithoutTemperatureIsNull() throws JSONException {
        assertNull(Weather.parseYandex(
                "{\"data\":{\"weatherByPoint\":{\"now\":{\"condition\":\"clear\"}}}}"));
    }
}
