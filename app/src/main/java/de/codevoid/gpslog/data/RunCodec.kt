package de.codevoid.gpslog.data

import de.codevoid.gpslog.model.GpsRecord
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary on-disk format for a single run. Pure Kotlin / java.nio only so it can be unit-tested
 * on the JVM without an emulator.
 *
 * Layout: a 16-byte header followed by fixed-size [RECORD_SIZE] records, big-endian.
 * Optional fields are marked present via the per-record [flags] bitmask; absent fields are
 * written as NaN and read back as null. Fixed record size keeps point count and random access
 * as O(1) arithmetic on the file length.
 */
object RunCodec {

    val MAGIC = byteArrayOf('G'.code.toByte(), 'P'.code.toByte(), 'S'.code.toByte(), 'L'.code.toByte())
    const val VERSION: Int = 1
    const val HEADER_SIZE: Int = 16
    const val RECORD_SIZE: Int = 68

    private const val FLAG_ALTITUDE = 1 shl 0
    private const val FLAG_ACCURACY = 1 shl 1
    private const val FLAG_VERTICAL_ACCURACY = 1 shl 2
    private const val FLAG_SPEED = 1 shl 3
    private const val FLAG_SPEED_ACCURACY = 1 shl 4
    private const val FLAG_BEARING = 1 shl 5
    private const val FLAG_BEARING_ACCURACY = 1 shl 6

    /** Serialize the file header. Written once, before any record. */
    fun header(): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.put(MAGIC)
        buf.put(VERSION.toByte())
        buf.putShort(RECORD_SIZE.toShort())
        // remaining bytes stay zero (reserved)
        return buf.array()
    }

    /** Validate a header and return the record size it declares. Throws on a bad/short header. */
    fun readHeader(bytes: ByteArray): Int {
        require(bytes.size >= HEADER_SIZE) { "header too short: ${bytes.size}" }
        for (i in MAGIC.indices) {
            require(bytes[i] == MAGIC[i]) { "bad magic at $i" }
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val version = buf.get(4).toInt()
        require(version == VERSION) { "unsupported version: $version" }
        val recordSize = buf.getShort(5).toInt() and 0xFFFF
        require(recordSize == RECORD_SIZE) { "unexpected record size: $recordSize" }
        return recordSize
    }

    /** Number of whole records in a file of [fileSizeBytes]; a torn trailing partial is ignored. */
    fun recordCount(fileSizeBytes: Long): Long {
        if (fileSizeBytes <= HEADER_SIZE) return 0
        return (fileSizeBytes - HEADER_SIZE) / RECORD_SIZE
    }

    fun encodeRecord(r: GpsRecord): ByteArray {
        val buf = ByteBuffer.allocate(RECORD_SIZE).order(ByteOrder.BIG_ENDIAN)
        var flags = 0
        if (r.altitude != null) flags = flags or FLAG_ALTITUDE
        if (r.accuracy != null) flags = flags or FLAG_ACCURACY
        if (r.verticalAccuracy != null) flags = flags or FLAG_VERTICAL_ACCURACY
        if (r.speed != null) flags = flags or FLAG_SPEED
        if (r.speedAccuracy != null) flags = flags or FLAG_SPEED_ACCURACY
        if (r.bearing != null) flags = flags or FLAG_BEARING
        if (r.bearingAccuracy != null) flags = flags or FLAG_BEARING_ACCURACY

        buf.putInt(flags)
        buf.putLong(r.timeMillis)
        buf.putLong(r.elapsedRealtimeNanos)
        buf.putDouble(r.latitude)
        buf.putDouble(r.longitude)
        buf.putDouble(r.altitude ?: Double.NaN)
        buf.putFloat(r.accuracy ?: Float.NaN)
        buf.putFloat(r.verticalAccuracy ?: Float.NaN)
        buf.putFloat(r.speed ?: Float.NaN)
        buf.putFloat(r.speedAccuracy ?: Float.NaN)
        buf.putFloat(r.bearing ?: Float.NaN)
        buf.putFloat(r.bearingAccuracy ?: Float.NaN)
        return buf.array()
    }

    fun decodeRecord(bytes: ByteArray, offset: Int = 0): GpsRecord {
        require(bytes.size - offset >= RECORD_SIZE) { "record too short at offset $offset" }
        val buf = ByteBuffer.wrap(bytes, offset, RECORD_SIZE).order(ByteOrder.BIG_ENDIAN)
        val flags = buf.int
        val timeMillis = buf.long
        val elapsedRealtimeNanos = buf.long
        val latitude = buf.double
        val longitude = buf.double
        val altitude = buf.double
        val accuracy = buf.float
        val verticalAccuracy = buf.float
        val speed = buf.float
        val speedAccuracy = buf.float
        val bearing = buf.float
        val bearingAccuracy = buf.float
        return GpsRecord(
            timeMillis = timeMillis,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            latitude = latitude,
            longitude = longitude,
            altitude = if (flags and FLAG_ALTITUDE != 0) altitude else null,
            accuracy = if (flags and FLAG_ACCURACY != 0) accuracy else null,
            verticalAccuracy = if (flags and FLAG_VERTICAL_ACCURACY != 0) verticalAccuracy else null,
            speed = if (flags and FLAG_SPEED != 0) speed else null,
            speedAccuracy = if (flags and FLAG_SPEED_ACCURACY != 0) speedAccuracy else null,
            bearing = if (flags and FLAG_BEARING != 0) bearing else null,
            bearingAccuracy = if (flags and FLAG_BEARING_ACCURACY != 0) bearingAccuracy else null,
        )
    }
}
