package de.codevoid.gpslog.model

/**
 * One GPS fix. `latitude`, `longitude`, `timeMillis` and `elapsedRealtimeNanos` are always
 * present; the remaining fields mirror `Location.hasX()` and are null when the fix did not
 * provide them.
 *
 * `timeMillis` is the UTC wall-clock fix time (for GPX `<time>`); `elapsedRealtimeNanos` is the
 * monotonic clock (for rate/duration math that must survive system-clock changes).
 */
data class GpsRecord(
    val timeMillis: Long,
    val elapsedRealtimeNanos: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracy: Float?,
    val verticalAccuracy: Float?,
    val speed: Float?,
    val speedAccuracy: Float?,
    val bearing: Float?,
    val bearingAccuracy: Float?,
)
