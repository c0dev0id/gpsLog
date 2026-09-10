package de.codevoid.gpslog.service

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Appends the raw NMEA stream (and a few connection notes) to a plain-text file the user can share
 * for diagnostics. Each line is prefixed with a wall-clock timestamp so the actual delivery rate and
 * any gaps are measurable from the log. Written only from the Bluetooth reader thread, so no locking.
 */
class NmeaDebugLog(file: File) {

    private val writer: BufferedWriter = BufferedWriter(FileWriter(file, /* append = */ true))

    /** A `#`-prefixed annotation (connect/disconnect/error), not part of the NMEA stream. */
    fun note(text: String) = writeLine("# $text")

    fun line(nmea: String) = writeLine(nmea)

    private fun writeLine(text: String) {
        writer.write(TIME_FMT.format(Instant.now()))
        writer.write("  ")
        writer.write(text)
        writer.newLine()
        writer.flush()
    }

    fun close() {
        runCatching { writer.flush(); writer.close() }
    }

    private companion object {
        val TIME_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    }
}
