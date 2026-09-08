package de.codevoid.gpslog.export

import android.util.Xml
import de.codevoid.gpslog.data.FilterSettings
import de.codevoid.gpslog.data.RunInfo
import de.codevoid.gpslog.data.RunReader
import de.codevoid.gpslog.model.GpsRecord
import org.xmlpull.v1.XmlSerializer
import java.io.OutputStream
import java.time.Instant
import java.util.Locale

/**
 * Writes selected runs as a single GPX 1.1 document, one `<trk>` per run, streamed straight to the
 * output. A `<trkseg>` is split whenever consecutive points are more than [GAP_THRESHOLD_MS] apart
 * (e.g. a reboot pause) so viewers don't draw a straight line across the gap.
 */
object GpxExporter {

    private const val GAP_THRESHOLD_MS = 60_000L
    private const val GPX_NS = "http://www.topografix.com/GPX/1/1"

    fun export(out: OutputStream, runs: List<RunInfo>, filters: FilterSettings) {
        val s = Xml.newSerializer()
        s.setOutput(out, "UTF-8")
        s.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)
        s.startDocument("UTF-8", true)
        s.startTag(null, "gpx")
        s.attribute(null, "version", "1.1")
        s.attribute(null, "creator", "gpsLog")
        s.attribute(null, "xmlns", GPX_NS)
        for (run in runs) {
            writeTrack(s, run, filters)
        }
        s.endTag(null, "gpx")
        s.endDocument()
        s.flush()
    }

    private fun writeTrack(s: XmlSerializer, run: RunInfo, filters: FilterSettings) {
        val points = PointFilter.filter(
            RunReader.readAll(run.file),
            filters.accuracyMeters,
            filters.distanceMeters,
            filters.timeSeconds,
        )
        s.startTag(null, "trk")
        s.startTag(null, "name")
        s.text("gpsLog " + Instant.ofEpochMilli(run.startTimeMillis).toString())
        s.endTag(null, "name")

        var segOpen = false
        var prevTime = 0L
        for (p in points) {
            if (!segOpen || p.timeMillis - prevTime > GAP_THRESHOLD_MS) {
                if (segOpen) s.endTag(null, "trkseg")
                s.startTag(null, "trkseg")
                segOpen = true
            }
            writePoint(s, p)
            prevTime = p.timeMillis
        }
        if (segOpen) s.endTag(null, "trkseg")
        s.endTag(null, "trk")
    }

    private fun writePoint(s: XmlSerializer, p: GpsRecord) {
        s.startTag(null, "trkpt")
        s.attribute(null, "lat", String.format(Locale.US, "%.7f", p.latitude))
        s.attribute(null, "lon", String.format(Locale.US, "%.7f", p.longitude))
        p.altitude?.let {
            s.startTag(null, "ele")
            s.text(String.format(Locale.US, "%.2f", it))
            s.endTag(null, "ele")
        }
        s.startTag(null, "time")
        s.text(Instant.ofEpochMilli(p.timeMillis).toString())
        s.endTag(null, "time")
        s.endTag(null, "trkpt")
    }
}
