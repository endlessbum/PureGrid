package tv.puregrid.launcher;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

/** Plain JVM tests for the Gregorian to Jalali conversion the clock greeting shows. */
public class ShamsiTest {

    @Test
    public void nowruzLandsOnFarvardinFirst() {
        assertArrayEquals(new int[]{1403, 1, 1}, Shamsi.toJalali(2024, 3, 20));
        assertArrayEquals(new int[]{1404, 1, 1}, Shamsi.toJalali(2025, 3, 21));
        assertArrayEquals(new int[]{1405, 1, 1}, Shamsi.toJalali(2026, 3, 21));
    }

    @Test
    public void leapYearEsfandHasThirtyDays() {
        // 1403 is a leap year: it ends with Esfand 30, and the next Nowruz is March 21.
        assertArrayEquals(new int[]{1403, 12, 30}, Shamsi.toJalali(2025, 3, 20));
        // 1404 is common: it ends with Esfand 29, and the next Nowruz is March 21 again.
        assertArrayEquals(new int[]{1404, 12, 29}, Shamsi.toJalali(2026, 3, 20));
    }

    @Test
    public void commonYearEsfandHasTwentyNineDays() {
        // 1402 is not a leap year either.
        assertArrayEquals(new int[]{1402, 12, 29}, Shamsi.toJalali(2024, 3, 19));
    }

    @Test
    public void firstSixMonthsHaveThirtyOneDays() {
        // Farvardin 1 = Mar 21, Ordibehesht 1 = Apr 21, Khordad 1 = May 22 in 2026.
        assertArrayEquals(new int[]{1405, 1, 1}, Shamsi.toJalali(2026, 3, 21));
        assertArrayEquals(new int[]{1405, 2, 1}, Shamsi.toJalali(2026, 4, 21));
        assertArrayEquals(new int[]{1405, 3, 1}, Shamsi.toJalali(2026, 5, 22));
    }

    @Test
    public void monthsSevenToElevenHaveThirtyDays() {
        // After five 31-day months (Farvardin..Shahrivar = 155 days from Mar 21): Mehr 1 = Sep 23.
        assertArrayEquals(new int[]{1405, 7, 1}, Shamsi.toJalali(2026, 9, 23));
        assertArrayEquals(new int[]{1405, 5, 1}, Shamsi.toJalali(2026, 7, 23));
        // Dey 1 sits on the winter solstice in 1404.
        assertArrayEquals(new int[]{1404, 10, 1}, Shamsi.toJalali(2025, 12, 22));
    }

    @Test
    public void bahamanIsThirtyAndEsfandStartsAfterIt() {
        assertArrayEquals(new int[]{1404, 11, 30}, Shamsi.toJalali(2026, 2, 19));
        assertArrayEquals(new int[]{1404, 12, 1}, Shamsi.toJalali(2026, 2, 20));
    }

    @Test
    public void the33YearLeapCycleHoldsAcrossDecades() {
        // Published calendar: Nowruz 2000 fell on March 20 (1379), Nowruz 2001 on March 21 (1380),
        // and 1379 was a leap year ending with Esfand 30.
        assertArrayEquals(new int[]{1379, 1, 1}, Shamsi.toJalali(2000, 3, 20));
        assertArrayEquals(new int[]{1380, 1, 1}, Shamsi.toJalali(2001, 3, 21));
        assertArrayEquals(new int[]{1379, 12, 30}, Shamsi.toJalali(2001, 3, 20));
        // The class doc promises exactness back to AP 1178: 1178-01-01 = 1799-03-21.
        assertArrayEquals(new int[]{1178, 1, 1}, Shamsi.toJalali(1799, 3, 21));
    }

    @Test
    public void midYearSampleMatchesThePublishedCalendar() {
        // 19 Aban 1405 = 10 November 2026.
        assertArrayEquals(new int[]{1405, 8, 19}, Shamsi.toJalali(2026, 11, 10));
    }
}
