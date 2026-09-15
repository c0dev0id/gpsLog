package de.codevoid.gpslog.ui

import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Placeholder for a value that does not exist yet (idle stats, missing optional fields). */
const val DASH = "—"

/**
 * Locale- and zone-aware formatting for every number, time and count the screens display. Pure
 * JVM code so it is unit-tested; a surface creates one instance (`remember`) and hands the
 * resulting Strings to its leaf composables, which never see this class.
 */
class Formats(locale: Locale, zone: ZoneId) {

    private val integer: NumberFormat = NumberFormat.getIntegerInstance(locale)
    private val oneDecimal: NumberFormat = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
        roundingMode = RoundingMode.HALF_UP
    }
    private val clockFmt = DateTimeFormatter.ofPattern("HH:mm:ss", locale).withZone(zone)
    private val clockShortFmt = DateTimeFormatter.ofPattern("HH:mm", locale).withZone(zone)
    private val dateTimeFmt = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale).withZone(zone)

    /** Grouped integer: 4812 → "4,812" (US) / "4.812" (DE). */
    fun count(n: Long): String = integer.format(n)

    /** "1 run", "3 runs", "4,812 points". */
    fun plural(n: Long, one: String, many: String): String = "${count(n)} ${if (n == 1L) one else many}"

    /** One decimal, half-up: 3.24 → "3.2", 12.96 → "13.0"; null → [DASH]. */
    fun decimal(v: Float?): String = v?.let { oneDecimal.format(it.toDouble()) } ?: DASH

    /** "12 / 24" — satellites used in the fix versus visible. */
    fun satellites(used: Int, visible: Int): String = "$used / $visible"

    /** Wall-clock time of day with seconds; null → [DASH]. */
    fun clock(millis: Long?): String = millis?.let { clockFmt.format(Instant.ofEpochMilli(it)) } ?: DASH

    /** Wall-clock time of day without seconds. */
    fun clockShort(millis: Long): String = clockShortFmt.format(Instant.ofEpochMilli(millis))

    /** Absolute run title: "12 Sep 2026, 08:14". Deterministic — no "Today"/"Yesterday". */
    fun runTitle(millis: Long): String = dateTimeFmt.format(Instant.ofEpochMilli(millis))

    /** "1 h 23 min · 4,812 points", or "No points" for an empty run whose duration means nothing. */
    fun runSubtitle(durationMillis: Long, points: Long): String =
        if (points == 0L) "No points" else "${formatDuration(durationMillis)} · ${plural(points, "point", "points")}"

    /** "8 runs · 46,120 points", or "No runs". */
    fun runsSummary(runs: Int, points: Long): String =
        if (runs == 0) "No runs" else "${plural(runs.toLong(), "run", "runs")} · ${plural(points, "point", "points")}"

    /** A 0..1 fraction as a whole percentage: 0.423 → "42%". Clamped. */
    fun percent(fraction: Float): String = "${(fraction.coerceIn(0f, 1f) * 100f).roundToInt()}%"
}

/** "42 s", "12 min", "1 h 23 min". Negative input reads as zero. Locale-free by design. */
fun formatDuration(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = totalSeconds % 3600L / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> "$hours h $minutes min"
        minutes > 0L -> "$minutes min"
        else -> "$seconds s"
    }
}

/** Whole-percent reduction from [total] to [kept], rounded (995 of 1000 kept → 1). 0 when nothing to reduce. */
fun reductionPercent(total: Long, kept: Long): Int =
    if (total <= 0L) 0 else ((total - kept) * 100.0 / total).roundToInt().coerceIn(0, 100)

/** An integer within [range], or null for anything else (blank, decimal, out of range). */
fun parseIntIn(text: String, range: IntRange): Int? = text.trim().toIntOrNull()?.takeIf { it in range }

/**
 * A distance in metres, 0..999.9, rounded half-up to 0.1. A comma is accepted as the decimal
 * separator because some locales' decimal keyboards offer only that. Null for anything else.
 */
fun parseDistance(text: String): Float? {
    val value = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
    if (!value.isFinite() || value < 0.0 || value > 999.9) return null
    return (Math.round(value * 10.0) / 10.0).toFloat()
}

/** Text a filter field shows for a persisted integer value. */
fun filterText(value: Int): String = value.toString()

/** Text a filter field shows for a persisted distance: whole values without a fraction, "2.5" otherwise. */
fun filterText(value: Float): String {
    val whole = value.roundToInt()
    return if (whole.toFloat() == value) whole.toString() else String.format(Locale.US, "%.1f", value)
}
