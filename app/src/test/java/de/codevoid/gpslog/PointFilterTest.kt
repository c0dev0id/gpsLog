package de.codevoid.gpslog

import de.codevoid.gpslog.export.PointFilter
import de.codevoid.gpslog.model.GpsRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class PointFilterTest {

    /** ~111.19 m of northward distance per 0.001 degrees of latitude. */
    private fun point(
        timeSec: Long,
        latDeg: Double = 0.0,
        accuracy: Float? = 5f,
    ) = GpsRecord(
        timeMillis = timeSec * 1000L,
        elapsedRealtimeNanos = timeSec * 1_000_000_000L,
        latitude = latDeg,
        longitude = 0.0,
        altitude = null,
        accuracy = accuracy,
        verticalAccuracy = null,
        speed = null,
        speedAccuracy = null,
        bearing = null,
        bearingAccuracy = null,
    )

    private fun times(result: List<GpsRecord>) = result.map { it.timeMillis / 1000L }

    @Test
    fun accuracyDropsMissingAndWorse() {
        val input = listOf(
            point(0, accuracy = 5f),
            point(1, accuracy = 20f),   // worse than 10 -> dropped
            point(2, accuracy = null),  // missing -> dropped
            point(3, accuracy = 9f),
        )
        val out = PointFilter.filter(input, accuracyMeters = 10, distanceMeters = 0f, timeSeconds = 0)
        assertEquals(listOf(0L, 3L), times(out))
    }

    @Test
    fun bothFiltersOffKeepsAllAccuracyPassing() {
        val input = (0..4).map { point(it.toLong()) }
        val out = PointFilter.filter(input, accuracyMeters = 10, distanceMeters = 0f, timeSeconds = 0)
        assertEquals(5, out.size)
    }

    @Test
    fun distanceThinsBySpacing() {
        // 0.001 deg steps ~= 111.19 m apart
        val input = (0..4).map { point(it.toLong(), latDeg = it * 0.001) }
        val out = PointFilter.filter(input, accuracyMeters = 10, distanceMeters = 200f, timeSeconds = 0)
        // 0->1 = 111 (<200 skip), 0->2 = 222 (keep), 2->3 skip, 2->4 keep
        assertEquals(listOf(0L, 2L, 4L), times(out))
    }

    @Test
    fun timeSamplesAtInterval() {
        val input = (0..10).map { point(it.toLong()) } // one point per second, stationary
        val out = PointFilter.filter(input, accuracyMeters = 10, distanceMeters = 0f, timeSeconds = 3)
        assertEquals(listOf(0L, 3L, 6L, 9L, 10L), times(out))
    }

    @Test
    fun timeTieBreaksByBetterAccuracy() {
        val input = listOf(
            point(0, accuracy = 5f),
            point(2, accuracy = 5f),   // 1000 ms before tick=3000
            point(4, accuracy = 3f),   // 1000 ms after tick=3000, better accuracy -> chosen
        )
        val out = PointFilter.filter(input, accuracyMeters = 10, distanceMeters = 0f, timeSeconds = 3)
        assertEquals(listOf(0L, 4L), times(out))
    }

    @Test
    fun distanceBeatsTime_movingFarRecordsBeforeTick() {
        val input = listOf(
            point(0, latDeg = 0.0),
            point(2, latDeg = 0.001), // ~111 m at t=2s, well before the 10s tick
        )
        val out = PointFilter.filter(input, accuracyMeters = 10, distanceMeters = 100f, timeSeconds = 10)
        assertEquals(listOf(0L, 2L), times(out))
    }

    @Test
    fun distanceBeatsTime_stationaryAtTickNotRecorded() {
        val input = listOf(
            point(0, latDeg = 0.0),
            point(10, latDeg = 0.0), // same spot at the 10s tick -> distance gate blocks it
        )
        val out = PointFilter.filter(input, accuracyMeters = 10, distanceMeters = 100f, timeSeconds = 10)
        assertEquals(listOf(0L), times(out))
    }
}
