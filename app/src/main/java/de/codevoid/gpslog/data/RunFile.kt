package de.codevoid.gpslog.data

import de.codevoid.gpslog.model.GpsRecord
import java.io.ByteArrayOutputStream
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

    private val buffer = ByteArrayOutputStream()

    init {
        file.parentFile?.mkdirs()
        if (!file.exists() || file.length() == 0L) {
            FileOutputStream(file).use { fos ->
                fos.write(RunCodec.header())
                fos.fd.sync()
            }
        }
    }

    @Synchronized
    fun append(record: GpsRecord) {
        buffer.write(RunCodec.encodeRecord(record))
    }

    /** Write everything buffered so far to the OS page cache in one call. No-op when the buffer is empty. */
    @Synchronized
    fun flush() {
        if (buffer.size() == 0) return
        FileOutputStream(file, true).use { buffer.writeTo(it) }
        buffer.reset()
    }

    @Synchronized
    fun close() = flush()
}

/** Read-only access to a run file. A torn trailing partial record is ignored throughout. */
object RunReader {

    /** What the run list and a resuming service need, read from one open of the file. */
    class Summary(val pointCount: Long, val first: GpsRecord?, val last: GpsRecord?)

    fun summary(file: File): Summary {
        val count = RunCodec.recordCount(file.length())
        if (count <= 0) return Summary(0, null, null)
        RandomAccessFile(file, "r").use { raf ->
            val first = recordAt(raf, 0)
            val last = if (count == 1L) first else recordAt(raf, count - 1)
            return Summary(count, first, last)
        }
    }

    /** Load every whole record, validating the header. Used at export time; one run at a time. */
    fun readAll(file: File): List<GpsRecord> {
        val count = RunCodec.recordCount(file.length())
        if (count <= 0) return emptyList()
        val result = ArrayList<GpsRecord>(count.toInt())
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(RunCodec.HEADER_SIZE)
            raf.readFully(header)
            RunCodec.readHeader(header)
            // One read per chunk, not per record.
            val chunk = ByteArray(RunCodec.RECORD_SIZE * READ_CHUNK_RECORDS)
            var remaining = count
            while (remaining > 0) {
                val n = remaining.coerceAtMost(READ_CHUNK_RECORDS.toLong()).toInt()
                raf.readFully(chunk, 0, n * RunCodec.RECORD_SIZE)
                for (i in 0 until n) result.add(RunCodec.decodeRecord(chunk, i * RunCodec.RECORD_SIZE))
                remaining -= n
            }
        }
        return result
    }

    private fun recordAt(raf: RandomAccessFile, index: Long): GpsRecord {
        raf.seek(RunCodec.HEADER_SIZE + index * RunCodec.RECORD_SIZE)
        val buf = ByteArray(RunCodec.RECORD_SIZE)
        raf.readFully(buf)
        return RunCodec.decodeRecord(buf)
    }

    private const val READ_CHUNK_RECORDS = 1024
}
