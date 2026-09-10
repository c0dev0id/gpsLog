package de.codevoid.gpslog.data

import de.codevoid.gpslog.model.GpsRecord
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Outcome of feeding one NMEA line to [NmeaParser]. [record] is non-null only on a fixed RMC. */
data class NmeaResult(
    val record: GpsRecord?,
    val satellitesVisible: Int,
    val satellitesUsedInFix: Int,
)

/**
 * Stateful NMEA-0183 parser for a classic-Bluetooth GNSS receiver (e.g. a MediaTek MT3333).
 *
 * Talker-agnostic: the leading two-letter talker (`GP`/`GN`/`GL`/`GA`/`GB`/…) is ignored and lines
 * are dispatched on the three-letter sentence type. `GGA` and `RMC` are the required pair — together
 * they carry position, UTC date+time, altitude, fix quality, satellites-in-use and HDOP, so a full
 * [GpsRecord] is produced from them alone. `GSV` is optional enrichment: it supplies the
 * satellites-in-view count, summed across per-constellation talkers.
 *
 * A [GpsRecord] is emitted once per valid `RMC` (status `A`) — RMC is the only sentence carrying the
 * date, so it is the emit trigger — enriched with the most recent `GGA`. NMEA has no meters-accuracy
 * field, so accuracy is derived as `HDOP × 5 m` (a nominal UERE) to survive the export accuracy
 * filter. Pure Kotlin (no Android): the caller stamps the monotonic [elapsedNanos] per line.
 */
class NmeaParser {

    // Most recent GGA state (valid while the receiver reports a fix).
    private var altitude: Double? = null
    private var hdop: Float? = null
    private var satellitesUsedInFix: Int = 0

    // Satellites in view, summed across constellations; keyed by talker so each cycle refreshes it.
    private var satellitesVisible: Int = 0
    private val gsvTotals = HashMap<String, Int>()

    fun parse(line: String, elapsedNanos: Long): NmeaResult {
        val body = validate(line) ?: return current(null)
        val fields = body.split(',')
        val id = fields[0]
        if (id.length < 3) return current(null)
        return when (id.takeLast(3)) {
            "GGA" -> { parseGga(fields); current(null) }
            "GSV" -> { parseGsv(fields, id.dropLast(3)); current(null) }
            "RMC" -> current(parseRmc(fields, elapsedNanos))
            else -> current(null)
        }
    }

    private fun current(record: GpsRecord?) =
        NmeaResult(record, satellitesVisible, satellitesUsedInFix)

    /** Returns the sentence body (between `$` and `*`) if the XOR checksum matches, else null. */
    private fun validate(line: String): String? {
        val start = line.indexOf('$')
        if (start < 0) return null
        val star = line.indexOf('*', start)
        if (star < 0 || star + 3 > line.length) return null
        val body = line.substring(start + 1, star)
        var xor = 0
        for (c in body) xor = xor xor c.code
        val expected = line.substring(star + 1, star + 3)
        if (!xor.toString(16).padStart(2, '0').equals(expected, ignoreCase = true)) return null
        return body
    }

    private fun parseGga(f: List<String>) {
        val quality = f.getOrNull(6)?.toIntOrNull() ?: 0
        if (quality <= 0) {
            satellitesUsedInFix = 0
            altitude = null
            hdop = null
            return
        }
        satellitesUsedInFix = f.getOrNull(7)?.toIntOrNull() ?: 0
        hdop = f.getOrNull(8)?.toFloatOrNull()
        altitude = f.getOrNull(9)?.toDoubleOrNull()
    }

    private fun parseGsv(f: List<String>, talker: String) {
        val totalInView = f.getOrNull(3)?.toIntOrNull() ?: return
        gsvTotals[talker] = totalInView
        satellitesVisible = gsvTotals.values.sum()
    }

    private fun parseRmc(f: List<String>, elapsedNanos: Long): GpsRecord? {
        if (f.getOrNull(2) != "A") return null
        val lat = parseCoord(f.getOrNull(3), f.getOrNull(4)) ?: return null
        val lon = parseCoord(f.getOrNull(5), f.getOrNull(6)) ?: return null
        val millis = toEpochMillis(f.getOrNull(9), f.getOrNull(1)) ?: return null
        val speed = f.getOrNull(7)?.toFloatOrNull()?.let { it * KNOTS_TO_MPS }
        val bearing = f.getOrNull(8)?.toFloatOrNull()
        return GpsRecord(
            timeMillis = millis,
            elapsedRealtimeNanos = elapsedNanos,
            latitude = lat,
            longitude = lon,
            altitude = altitude,
            accuracy = hdop?.let { it * HDOP_TO_METERS },
            verticalAccuracy = null,
            speed = speed,
            speedAccuracy = null,
            bearing = bearing,
            bearingAccuracy = null,
        )
    }

    /** NMEA coordinate `dddmm.mmmm` + hemisphere to signed decimal degrees. */
    private fun parseCoord(raw: String?, hemi: String?): Double? {
        if (raw.isNullOrEmpty() || hemi.isNullOrEmpty()) return null
        val v = raw.toDoubleOrNull() ?: return null
        val deg = (v / 100).toInt()
        val decimal = deg + (v - deg * 100) / 60.0
        return if (hemi == "S" || hemi == "W") -decimal else decimal
    }

    /** RMC `ddmmyy` date + `hhmmss[.sss]` UTC time to epoch millis. */
    private fun toEpochMillis(date: String?, time: String?): Long? {
        if (date == null || time == null || date.length < 6 || time.length < 6) return null
        return try {
            val year = 2000 + date.substring(4, 6).toInt()
            val month = date.substring(2, 4).toInt()
            val day = date.substring(0, 2).toInt()
            val hour = time.substring(0, 2).toInt()
            val minute = time.substring(2, 4).toInt()
            val second = time.substring(4, 6).toInt()
            val millis = time.substringAfter('.', "").take(3).padEnd(3, '0').toIntOrNull() ?: 0
            LocalDateTime.of(year, month, day, hour, minute, second, millis * 1_000_000)
                .toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val KNOTS_TO_MPS = 0.514444f
        const val HDOP_TO_METERS = 5.0f
    }
}
