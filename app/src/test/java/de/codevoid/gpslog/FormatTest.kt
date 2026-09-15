package de.codevoid.gpslog

import de.codevoid.gpslog.ui.DASH
import de.codevoid.gpslog.ui.Formats
import de.codevoid.gpslog.ui.filterText
import de.codevoid.gpslog.ui.formatDuration
import de.codevoid.gpslog.ui.parseDistance
import de.codevoid.gpslog.ui.parseIntIn
import de.codevoid.gpslog.ui.reductionPercent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class FormatTest {

    private val us = Formats(Locale.US, ZoneId.of("UTC"))
    private val de = Formats(Locale.GERMANY, ZoneId.of("UTC"))

    @Test
    fun countGroupsDigitsPerLocale() {
        assertEquals("0", us.count(0))
        assertEquals("4,812", us.count(4_812))
        assertEquals("1,234,567", us.count(1_234_567))
        assertEquals("4.812", de.count(4_812))
    }

    @Test
    fun pluralPicksTheNounByCount() {
        assertEquals("1 run", us.plural(1, "run", "runs"))
        assertEquals("3 runs", us.plural(3, "run", "runs"))
        assertEquals("0 points", us.plural(0, "point", "points"))
        assertEquals("4,812 points", us.plural(4_812, "point", "points"))
    }

    @Test
    fun decimalRoundsHalfUpToOnePlace() {
        assertEquals("3.2", us.decimal(3.24f))
        assertEquals("3.3", us.decimal(3.25f))
        assertEquals("0.0", us.decimal(0f))
        assertEquals("13.0", us.decimal(12.96f))
        assertEquals("123.5", us.decimal(123.46f))
        assertEquals("3,2", de.decimal(3.24f))
        assertEquals(DASH, us.decimal(null))
    }

    @Test
    fun clockFormatsTimeOfDayInTheGivenZone() {
        assertEquals("14:23:07", us.clock(51_787_000L))
        assertEquals("00:00:00", us.clock(0L))
        assertEquals(DASH, us.clock(null))
        assertEquals("14:23", us.clockShort(51_787_000L))
        assertEquals("15:23:07", Formats(Locale.US, ZoneId.of("UTC+1")).clock(51_787_000L))
    }

    @Test
    fun runTitleIsAnAbsoluteDate() {
        // 2026-09-12T08:14:00Z
        assertEquals("12 Sep 2026, 08:14", us.runTitle(1_789_200_840_000L))
    }

    @Test
    fun runSubtitleJoinsDurationAndPoints() {
        assertEquals("1 h 23 min · 4,812 points", us.runSubtitle(4_980_000L, 4_812))
        assertEquals("42 s · 1 point", us.runSubtitle(42_000L, 1))
        assertEquals("No points", us.runSubtitle(4_980_000L, 0))
    }

    @Test
    fun runsSummaryCountsRunsAndPoints() {
        assertEquals("8 runs · 46,120 points", us.runsSummary(8, 46_120))
        assertEquals("1 run · 1 point", us.runsSummary(1, 1))
        assertEquals("No runs", us.runsSummary(0, 0))
    }

    @Test
    fun percentIsWholeAndClamped() {
        assertEquals("42%", us.percent(0.423f))
        assertEquals("100%", us.percent(1f))
        assertEquals("0%", us.percent(0.004f))
        assertEquals("100%", us.percent(1.5f))
        assertEquals("0%", us.percent(-0.2f))
    }

    @Test
    fun durationUsesTheLargestUnitThatApplies() {
        assertEquals("0 s", formatDuration(0L))
        assertEquals("42 s", formatDuration(42_000L))
        assertEquals("59 s", formatDuration(59_999L))
        assertEquals("1 min", formatDuration(60_000L))
        assertEquals("12 min", formatDuration(754_000L))
        assertEquals("1 h 0 min", formatDuration(3_600_000L))
        assertEquals("1 h 23 min", formatDuration(4_980_000L))
        assertEquals("26 h 5 min", formatDuration(93_900_000L))
        assertEquals("0 s", formatDuration(-5L))
    }

    @Test
    fun reductionPercentRoundsInsteadOfFlooring() {
        assertEquals(0, reductionPercent(0, 0))
        assertEquals(62, reductionPercent(100, 38))
        assertEquals(1, reductionPercent(1_000, 995))
        assertEquals(0, reductionPercent(10, 10))
        assertEquals(100, reductionPercent(10, 0))
        assertEquals(67, reductionPercent(3, 1))
    }

    @Test
    fun parseIntInAcceptsOnlyWholeNumbersInRange() {
        assertEquals(10, parseIntIn("10", 1..999))
        assertEquals(7, parseIntIn(" 7 ", 0..999))
        assertEquals(0, parseIntIn("0", 0..999))
        assertNull(parseIntIn("0", 1..999))
        assertNull(parseIntIn("1000", 1..999))
        assertNull(parseIntIn("", 1..999))
        assertNull(parseIntIn("12.5", 1..999))
        assertNull(parseIntIn("abc", 1..999))
    }

    @Test
    fun parseDistanceRoundsToTenthsAndAcceptsAComma() {
        assertEquals(0f, parseDistance("0"))
        assertEquals(12.5f, parseDistance("12.5"))
        assertEquals(12.5f, parseDistance("12,5"))
        assertEquals(12.6f, parseDistance("12.55"))
        assertEquals(999.9f, parseDistance("999.9"))
        assertNull(parseDistance("1000"))
        assertNull(parseDistance("-1"))
        assertNull(parseDistance(""))
        assertNull(parseDistance("abc"))
        assertNull(parseDistance("NaN"))
    }

    @Test
    fun filterTextShowsWholeValuesWithoutAFraction() {
        assertEquals("10", filterText(10))
        assertEquals("0", filterText(0f))
        assertEquals("12", filterText(12f))
        assertEquals("2.5", filterText(2.5f))
        assertEquals("999.9", filterText(999.9f))
    }
}
