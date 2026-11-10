package tv.puregrid.launcher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Plain JVM tests for the letter the account pill draws when there is no profile photo. */
public class AccountAvatarViewTest {

    @Test
    public void noNameFallsBackToTheMonoG() {
        assertEquals("G", AccountAvatarView.letterFor(null));
        assertEquals("G", AccountAvatarView.letterFor(""));
    }

    @Test
    public void firstLetterIsTakenAndUppercased() {
        assertEquals("И", AccountAvatarView.letterFor("Иван"));
        assertEquals("B", AccountAvatarView.letterFor("benq"));
        assertEquals("A", AccountAvatarView.letterFor("ALICE"));
    }

    @Test
    public void fixedLocaleDoesNotTurkicizeTheI() {
        // A device set to Turkish must not turn "istanbul" into a dotted İ: the case mapping is
        // Locale.ROOT on purpose.
        assertEquals("I", AccountAvatarView.letterFor("istanbul"));
    }

    @Test
    public void surrogatePairsAreNeverSplit() {
        // Astral letters: the first code point is taken whole, not as a lone broken half.
        assertEquals("\uD835\uDC00", AccountAvatarView.letterFor("\uD835\uDC00bc"));   // 𝐀
        assertEquals("\uD801\uDC00", AccountAvatarView.letterFor("\uD801\uDC28x"));    // 𐐨 -> 𐐀
    }

    @Test
    public void leadingSpaceIsNotStripped() {
        // The name comes from the account API; only the first code point decides, whatever it is.
        assertEquals(" ", AccountAvatarView.letterFor(" Ivan"));
    }
}
