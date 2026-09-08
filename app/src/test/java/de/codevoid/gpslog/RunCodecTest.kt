package de.codevoid.gpslog

import de.codevoid.gpslog.data.RunCodec
import de.codevoid.gpslog.model.GpsRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunCodecTest {

    @Test
    fun headerRoundTrips() {
        val header = RunCodec.header()
        assertEquals(RunCodec.HEADER_SIZE, header.size)
        assertEquals(RunCodec.RECORD_SIZE, RunCodec.readHeader(header))
    }

    @Test
    fun fullRecordRoundTrips() {
        val r = GpsRecord(
            timeMillis = 1_725_000_000_000L,
            elapsedRealtimeNanos = 987_654_321L,
            latitude = 52.5200,
            longitude = 13.4050,
            altitude = 34.2,
            accuracy = 4.5f,
            verticalAccuracy = 6.0f,
            speed = 12.3f,
            speedAccuracy = 0.5f,
            bearing = 270.0f,
            bearingAccuracy = 1.5f,
        )
        val bytes = RunCodec.encodeRecord(r)
        assertEquals(RunCodec.RECORD_SIZE, bytes.size)
        assertEquals(r, RunCodec.decodeRecord(bytes))
    }

    @Test
    fun optionalFieldsDecodeAsNull() {
        val r = GpsRecord(
            timeMillis = 42L,
            elapsedRealtimeNanos = 7L,
            latitude = 0.0,
            longitude = 0.0,
            altitude = null,
            accuracy = null,
            verticalAccuracy = null,
            speed = null,
            speedAccuracy = null,
            bearing = null,
            bearingAccuracy = null,
        )
        val decoded = RunCodec.decodeRecord(RunCodec.encodeRecord(r))
        assertEquals(r, decoded)
        assertNull(decoded.altitude)
        assertNull(decoded.accuracy)
        assertNull(decoded.bearing)
    }

    @Test
    fun decodeAtOffsetReadsSecondRecord() {
        val a = sample(1)
        val b = sample(2)
        val buf = RunCodec.encodeRecord(a) + RunCodec.encodeRecord(b)
        assertEquals(a, RunCodec.decodeRecord(buf, 0))
        assertEquals(b, RunCodec.decodeRecord(buf, RunCodec.RECORD_SIZE))
    }

    @Test
    fun recordCountFloorsTornTrailingPartial() {
        val header = RunCodec.HEADER_SIZE.toLong()
        assertEquals(0L, RunCodec.recordCount(header))
        assertEquals(0L, RunCodec.recordCount(header + RunCodec.RECORD_SIZE - 1))
        assertEquals(1L, RunCodec.recordCount(header + RunCodec.RECORD_SIZE.toLong()))
        // two whole records plus a torn partial -> two
        assertEquals(2L, RunCodec.recordCount(header + 2L * RunCodec.RECORD_SIZE + 5))
    }

    @Test(expected = IllegalArgumentException::class)
    fun readHeaderRejectsBadMagic() {
        val bad = RunCodec.header().copyOf()
        bad[0] = 'X'.code.toByte()
        RunCodec.readHeader(bad)
    }

    private fun sample(i: Int) = GpsRecord(
        timeMillis = 1000L + i,
        elapsedRealtimeNanos = i.toLong(),
        latitude = i.toDouble(),
        longitude = -i.toDouble(),
        altitude = i.toDouble(),
        accuracy = i.toFloat(),
        verticalAccuracy = null,
        speed = null,
        speedAccuracy = null,
        bearing = null,
        bearingAccuracy = null,
    )
}
