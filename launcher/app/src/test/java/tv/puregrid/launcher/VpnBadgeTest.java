package tv.puregrid.launcher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Plain JVM tests for the capsule's state-to-colour mapping (the lock icon carries the rest). */
public class VpnBadgeTest {

    @Test
    public void upIsGreenWithInk() {
        assertEquals(0xFF30D158, VpnBadge.fillFor(VpnBadge.UP));
        assertEquals(0xFF06290F, VpnBadge.inkFor(VpnBadge.UP));
    }

    @Test
    public void blockedIsAmberWithInk() {
        // The same amber as the network pill's warn tint (res/values/colors.xml warn_amber).
        assertEquals(0xFFFF9F0A, VpnBadge.fillFor(VpnBadge.BLOCKED));
        assertEquals(0xFF06290F, VpnBadge.inkFor(VpnBadge.BLOCKED));
    }

    @Test
    public void offIsWhiteWithDarkInk() {
        assertEquals(0xFFFFFFFF, VpnBadge.fillFor(VpnBadge.OFF));
        assertEquals(0xFF111111, VpnBadge.inkFor(VpnBadge.OFF));
    }

    @Test
    public void unknownStateFallsBackToWhite() {
        // The constructor's ternary chain treats anything unknown like OFF for the fill but keeps
        // the standard ink; the service never passes such a state, this pins the fallback.
        assertEquals(0xFFFFFFFF, VpnBadge.fillFor(99));
        assertEquals(0xFF06290F, VpnBadge.inkFor(99));
    }
}
