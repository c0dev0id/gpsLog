package de.codevoid.gpslog

import de.codevoid.gpslog.data.NmeaParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class NmeaParserTest {

    /** Wraps an NMEA body in `$…*HH` with the correct XOR checksum. */
    private fun nmea(body: String): String {
        var xor = 0
        for (c in body) xor = xor xor c.code
        return "\$$body*${xor.toString(16).uppercase().padStart(2, '0')}"
    }

    private val gga =
        nmea("GNGGA,120000.000,5047.1234,N,00711.5678,E,1,08,1.20,150.5,M,47.0,M,,")
    private val rmc =
        nmea("GNRMC,120000.000,A,5047.1234,N,00711.5678,E,2.5,54.7,150925,,,A")

    @Test
    fun ggaThenRmcEmitsRecord() {
        val parser = NmeaParser()
        assertNull("GGA alone must not emit a record", parser.parse(gga, elapsedNanos = 1L).record)

        val result = parser.parse(rmc, elapsedNanos = 2L)
        val record = result.record
        assertNotNull(record)
        requireNotNull(record)

        assertEquals(50.785390, record.latitude, 1e-6)
        assertEquals(7.192797, record.longitude, 1e-6)
        assertEquals(150.5, record.altitude!!, 1e-6)
        assertEquals(6.0f, record.accuracy!!, 1e-4f)          // HDOP 1.20 × 5 m
        assertEquals(2.5f * 0.514444f, record.speed!!, 1e-4f) // knots → m/s
        assertEquals(54.7f, record.bearing!!, 1e-4f)
        assertEquals(2L, record.elapsedRealtimeNanos)

        val expectedMillis = LocalDateTime.of(2025, 9, 15, 12, 0, 0, 0)
            .toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(expectedMillis, record.timeMillis)
        assertEquals(8, result.satellitesUsedInFix)
    }

    @Test
    fun rmcWithVoidStatusEmitsNothing() {
        val parser = NmeaParser()
        val void = nmea("GNRMC,120000.000,V,,,,,,,150925,,,N")
        assertNull(parser.parse(void, elapsedNanos = 1L).record)
    }

    @Test
    fun badChecksumIsIgnored() {
        val parser = NmeaParser()
        parser.parse(gga, elapsedNanos = 1L)
        // Flip the checksum: a corrupt RMC must not emit a record.
        val corrupt = rmc.dropLast(2) + "00"
        assertNull(parser.parse(corrupt, elapsedNanos = 2L).record)
    }

    @Test
    fun truncatedLineIsIgnored() {
        val parser = NmeaParser()
        assertNull(parser.parse("\$GNRMC,120000.000,A,5047", elapsedNanos = 1L).record)
    }

    @Test
    fun gsvSumsAcrossConstellations() {
        val parser = NmeaParser()
        val gpgsv = nmea("GPGSV,3,1,11,01,05,110,00")
        val glgsv = nmea("GLGSV,2,1,07,65,05,110,00")
        assertEquals(11, parser.parse(gpgsv, elapsedNanos = 1L).satellitesVisible)
        assertEquals(18, parser.parse(glgsv, elapsedNanos = 2L).satellitesVisible)
    }
}
