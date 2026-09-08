package de.codevoid.gpslog.export

import de.codevoid.gpslog.model.GpsRecord
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Applies the export filters to a run's points. Pure Kotlin so it is unit-tested on the JVM.
 *
 * Order and semantics (from the spec):
 *  1. Accuracy (always on, min 1 m): drop points whose accuracy is missing or worse than the
 *     threshold.
 *  2/3. Distance and time. Distance is a mandatory minimum spacing; "distance beats time". The two
 *     example rules — a point is recorded once you have moved >= D even before the next time tick,
 *     and a due time tick is suppressed when you have not moved >= D — both reduce to: while D > 0
 *     the decision is governed entirely by distance and time has no additional effect. Time only
 *     governs when D == 0 (steady cadence, e.g. while stationary). With both off, all
 *     accuracy-passing points are kept.
 *
 * Time sampling (D == 0, T > 0) targets one point per T seconds, ticks anchored to the last kept
 * point's time; for each tick the point nearest the tick is chosen, ties broken by better accuracy.
 */
object PointFilter {

    private const val EARTH_RADIUS_M = 6_371_000.0

    fun filter(
        records: List<GpsRecord>,
        accuracyMeters: Int,
        distanceMeters: Float,
        timeSeconds: Int,
    ): List<GpsRecord> {
        val accepted = records.filter { it.accuracy != null && it.accuracy <= accuracyMeters.toFloat() }
        if (accepted.isEmpty()) return emptyList()
        return when {
            distanceMeters <= 0f && timeSeconds <= 0 -> accepted
            distanceMeters > 0f -> distanceThin(accepted, distanceMeters.toDouble())
            else -> timeSample(accepted, timeSeconds.toLong() * 1000L)
        }
    }

    private fun distanceThin(points: List<GpsRecord>, minMeters: Double): List<GpsRecord> {
        val out = ArrayList<GpsRecord>()
        var last = points[0]
        out.add(last)
        for (i in 1 until points.size) {
            val p = points[i]
            if (haversineMeters(last, p) >= minMeters) {
                out.add(p)
                last = p
            }
        }
        return out
    }

    private fun timeSample(points: List<GpsRecord>, intervalMs: Long): List<GpsRecord> {
        val out = ArrayList<GpsRecord>()
        out.add(points[0])
        var nextTick = points[0].timeMillis + intervalMs
        var i = 1
        val n = points.size
        while (i < n) {
            var best: GpsRecord? = null
            var bestIdx = -1
            var k = i
            while (k < n && points[k].timeMillis <= nextTick) {
                best = points[k]
                bestIdx = k
                k++
            }
            val after = if (k < n) points[k] else null

            val chosen: GpsRecord
            val chosenIdx: Int
            when {
                best == null && after == null -> return out
                best == null -> {
                    chosen = after!!
                    chosenIdx = k
                }
                after == null -> {
                    chosen = best
                    chosenIdx = bestIdx
                }
                else -> {
                    val distBefore = nextTick - best.timeMillis
                    val distAfter = after.timeMillis - nextTick
                    if (distBefore < distAfter || (distBefore == distAfter && accuracyAtLeastAsGood(best, after))) {
                        chosen = best
                        chosenIdx = bestIdx
                    } else {
                        chosen = after
                        chosenIdx = k
                    }
                }
            }
            out.add(chosen)
            nextTick = chosen.timeMillis + intervalMs
            i = chosenIdx + 1
        }
        return out
    }

    private fun accuracyAtLeastAsGood(a: GpsRecord, b: GpsRecord): Boolean {
        val aa = a.accuracy ?: Float.MAX_VALUE
        val bb = b.accuracy ?: Float.MAX_VALUE
        return aa <= bb
    }

    private fun haversineMeters(a: GpsRecord, b: GpsRecord): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }
}
