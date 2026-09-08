package de.codevoid.gpslog.data

import de.codevoid.gpslog.model.GpsRecord
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Appends records to a single run file. The header is written and fsync'd once when the file is
 * first created; on resume (existing non-empty file) it is left untouched and records are appended.
 * Records are buffered in memory and only hit disk on [flush], which the service calls on a fixed
 * cadence so a crash loses at most one flush interval.
 */
class RunWriter(private val file: File) {

    private val buffer = ArrayList<ByteArray>()

    init {
        file.parentFile?.mkdirs()
        if (!file.exists() || file.length() == 0L) {
            FileOutputStream(file).use { fos ->
                fos.write(RunCodec.header())
                fos.flush()
                fos.fd.sync()
            }
        }
    }

    @Synchronized
    fun append(record: GpsRecord) {
        buffer.add(RunCodec.encodeRecord(record))
    }

    @Synchronized
    fun bufferedCount(): Int = buffer.size

    /** Write and fsync everything buffered so far. No-op when the buffer is empty. */
    @Synchronized
    fun flush() {
        if (buffer.isEmpty()) return
        FileOutputStream(file, true).use { fos ->
            for (bytes in buffer) fos.write(bytes)
            fos.flush()
            fos.fd.sync()
        }
        buffer.clear()
    }

    @Synchronized
    fun close() = flush()
}

/** Read-only access to a run file. A torn trailing partial record is ignored throughout. */
object RunReader {

    fun pointCount(file: File): Long = RunCodec.recordCount(file.length())

    fun firstRecord(file: File): GpsRecord? = recordAt(file, 0)

    fun lastRecord(file: File): GpsRecord? {
        val count = RunCodec.recordCount(file.length())
        if (count <= 0) return null
        return recordAt(file, count - 1)
    }

    /** Load every whole record. Used at export time; one run at a time. */
    fun readAll(file: File): List<GpsRecord> {
        val count = RunCodec.recordCount(file.length())
        if (count <= 0) return emptyList()
        val result = ArrayList<GpsRecord>(count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(RunCodec.HEADER_SIZE)
            raf.readFully(header)
            RunCodec.readHeader(header)
            val buf = ByteArray(RunCodec.RECORD_SIZE)
            var i = 0L
            while (i < count) {
                raf.readFully(buf)
                result.add(RunCodec.decodeRecord(buf))
                i++
            }
        }
        return result
    }

    private fun recordAt(file: File, index: Long): GpsRecord? {
        val count = RunCodec.recordCount(file.length())
        if (index < 0 || index >= count) return null
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(RunCodec.HEADER_SIZE + index * RunCodec.RECORD_SIZE)
            val buf = ByteArray(RunCodec.RECORD_SIZE)
            raf.readFully(buf)
            return RunCodec.decodeRecord(buf)
        }
    }
}
