package tv.puregrid.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

/**
 * The overlay value checks and the theme-choice reset decision (the pure, Android-free parts of
 * {@link Config}): type strictness, the clamps every range goes through, and when a theme push
 * clears the choices made on the TV. org.json is not on the JVM classpath, so the tests feed the
 * {@code Object} overloads directly — the same overloads the JSON parsing calls into.
 */
public class ConfigTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ---- the type/range checks behind every overlay key ----

    @Test
    public void integerInRangeSurvives() {
        assertEquals(4, Config.integer(4, 1, 1, 8));
        assertEquals(2, Config.integer(2.0, 1, 1, 8));   // a JSON number arrives as a Double
        assertEquals(0, Config.integer(0, 1, 0, 3));
        assertEquals(100, Config.integer(100, 60, 0, 100));
        assertEquals(40, Config.integer(40, 8, 1, 40));
        assertEquals(300, Config.integer(300, 2500, 300, 15_000));
        assertEquals(15_000, Config.integer(15_000, 2500, 300, 15_000));
    }

    @Test
    public void integerOutOfRangeFallsBack() {
        // columns: a theme cannot ask for a zero- or nine-column grid
        assertEquals(4, Config.integer(0, 4, 1, 8));
        assertEquals(4, Config.integer(9, 4, 1, 8));
        // search_provider_timeout_ms: no 40-second hang from one provider
        assertEquals(2500, Config.integer(40_000, 2500, 300, 15_000));
        assertEquals(2500, Config.integer(299, 2500, 300, 15_000));
        // sky_intensity below zero does not turn into a negative alpha
        assertEquals(60, Config.integer(-1, 60, 0, 100));
    }

    @Test
    public void integerWrongTypeFallsBack() {
        assertEquals(4, Config.integer(null, 4, 1, 8));
        assertEquals(4, Config.integer("four", 4, 1, 8));
        assertEquals(4, Config.integer(Boolean.TRUE, 4, 1, 8));
        assertEquals(4, Config.integer(4L, 4, 1, 8));   // a Long number is still a number
    }

    @Test
    public void stringOnlyAcceptsStrings() {
        assertEquals("pkg", Config.string("  pkg  ", "fallback"));
        assertEquals("fallback", Config.string(42, "fallback"));
        assertEquals("fallback", Config.string(null, "fallback"));
        assertEquals("fallback", Config.string(Boolean.TRUE, "fallback"));
    }

    @Test
    public void boolOnlyAcceptsBooleans() {
        assertTrue(Config.bool(Boolean.TRUE, false));
        assertFalse(Config.bool(Boolean.FALSE, true));
        assertTrue(Config.bool("true", true));
        assertFalse(Config.bool(null, false));
        assertFalse(Config.bool(1, false));
    }

    // ---- theme signature: when on-TV choices are cleared ----

    @Test
    public void firstUseWithMarkerClearsChoices() {
        // The tools wrote a theme_revision before this launcher version first ran: the stored
        // columns/animations may contradict the theme that was just pushed.
        assertTrue(Config.themeChanged(null, "sig", "abc123"));
    }

    @Test
    public void firstUseWithoutMarkerKeepsChoices() {
        // An app upgrade alone must not wipe what the owner chose on the TV.
        assertFalse(Config.themeChanged(null, "absent:absent", "absent"));
    }

    @Test
    public void changedSignatureClearsChoices() {
        assertFalse(Config.themeChanged("sig:tok", "sig:tok", "tok"));
        assertTrue(Config.themeChanged("old:sig", "new:sig", "tok"));
        assertTrue(Config.themeChanged("sig:oldtok", "sig:newtok", "newtok"));
    }

    @Test
    public void revisionTokenReadsSmallFileOnly() throws Exception {
        File marker = tmp.newFile("theme_revision");
        try (FileOutputStream out = new FileOutputStream(marker)) {
            out.write("a-token".getBytes(StandardCharsets.US_ASCII));
        }
        assertEquals("a-token", Config.revisionToken(marker));
        byte[] tooBig = new byte[65];
        try (FileOutputStream out = new FileOutputStream(marker)) {
            out.write(tooBig);
        }
        assertEquals("absent", Config.revisionToken(marker));
        assertEquals("absent", Config.revisionToken(new File(tmp.getRoot(), "missing")));
        assertEquals("absent", Config.revisionToken(null));
    }

    @Test
    public void fileStampIsStablePerContentAndAbsentForMissing() throws Exception {
        File absent = new File(tmp.getRoot(), "nope.json");
        assertEquals("absent", Config.fileStamp(null));
        assertEquals("absent", Config.fileStamp(absent));
        File cfg = tmp.newFile("config.json");
        try (FileOutputStream out = new FileOutputStream(cfg)) {
            out.write("{\"columns\":5}".getBytes(StandardCharsets.UTF_8));
        }
        String stamp = Config.fileStamp(cfg);
        assertTrue(stamp.contains("@"));
        assertEquals(stamp, Config.fileStamp(cfg));
        try (FileOutputStream out = new FileOutputStream(cfg)) {
            out.write("{\"columns\":6}".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(!stamp.equals(Config.fileStamp(cfg)));
    }

    // ---- the dock order chosen on the TV ----

    @Test
    public void chosenDockParsesWithBlanks() {
        assertEquals(Arrays.asList("a.pkg", "b.pkg", "c.pkg"),
                Config.parseChosenDock(" a.pkg , b.pkg ,, c.pkg "));
        assertNull(Config.parseChosenDock(null));
        assertNull(Config.parseChosenDock("   "));
        assertNull(Config.parseChosenDock(" , , "));
        assertEquals(Collections.singletonList("one.pkg"), Config.parseChosenDock("one.pkg"));
    }
}
