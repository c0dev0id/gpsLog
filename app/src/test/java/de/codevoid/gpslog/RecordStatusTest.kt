package de.codevoid.gpslog

import de.codevoid.gpslog.service.LoggingState
import de.codevoid.gpslog.ui.Formats
import de.codevoid.gpslog.ui.RecordStatus
import de.codevoid.gpslog.ui.recordStatus
import de.codevoid.gpslog.ui.recordSubtitle
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class RecordStatusTest {

    private val f = Formats(Locale.US, ZoneId.of("UTC"))
    private val start = 50_520_000L // 14:02:00 UTC
    private val lastFix = start + 4_980_000L // 1 h 23 min later

    private fun logging(
        paused: Boolean = false,
        gpsEnabled: Boolean = true,
        gnssRunning: Boolean = false,
        used: Int = 0,
    ) = LoggingState(
        isLogging = true,
        runId = 1L,
        isPaused = paused,
        gpsEnabled = gpsEnabled,
        gnssRunning = gnssRunning,
        satellitesUsedInFix = used,
    )

    @Test
    fun idleDependsOnPreciseLocation() {
        assertEquals(RecordStatus.Idle, recordStatus(LoggingState(), external = false, preciseLocation = true))
        assertEquals(RecordStatus.Unavailable, recordStatus(LoggingState(), external = false, preciseLocation = false))
    }

    @Test
    fun pausedBeatsEverythingElse() {
        assertEquals(RecordStatus.Paused, recordStatus(logging(paused = true, gpsEnabled = false), false, true))
    }

    @Test
    fun providerOffIsDisabledOnlyForTheInternalSource() {
        assertEquals(RecordStatus.Disabled, recordStatus(logging(gpsEnabled = false), external = false, preciseLocation = true))
        // The service starts an external run with gpsEnabled = false and drops it on a link loss.
        assertEquals(RecordStatus.ReceiverOff, recordStatus(logging(gpsEnabled = false), external = true, preciseLocation = true))
    }

    @Test
    fun engineStoppedIsReceiverOffForBothSources() {
        assertEquals(RecordStatus.ReceiverOff, recordStatus(logging(gnssRunning = false), false, true))
        assertEquals(RecordStatus.ReceiverOff, recordStatus(logging(gnssRunning = false), true, true))
    }

    @Test
    fun runningEngineIsSearchingUntilSatellitesAreUsed() {
        assertEquals(RecordStatus.Searching, recordStatus(logging(gnssRunning = true, used = 0), false, true))
        assertEquals(RecordStatus.Fix, recordStatus(logging(gnssRunning = true, used = 5), false, true))
    }

    @Test
    fun revokedPreciseLocationDoesNotHideTheLiveState() {
        assertEquals(RecordStatus.Fix, recordStatus(logging(gnssRunning = true, used = 5), false, preciseLocation = false))
    }

    @Test
    fun idleSubtitlesNameTheSource() {
        assertEquals("Internal GPS", recordSubtitle(RecordStatus.Idle, false, null, null, f))
        assertEquals("External receiver", recordSubtitle(RecordStatus.Idle, true, null, null, f))
        assertEquals("Precise location is off", recordSubtitle(RecordStatus.Unavailable, false, null, null, f))
    }

    @Test
    fun problemSubtitlesSayWhatToDo() {
        assertEquals("Turn on Location in system settings", recordSubtitle(RecordStatus.Disabled, false, start, null, f))
        assertEquals("Waiting for the receiver", recordSubtitle(RecordStatus.ReceiverOff, true, start, null, f))
        assertEquals("GNSS engine stopped", recordSubtitle(RecordStatus.ReceiverOff, false, start, null, f))
    }

    @Test
    fun liveSubtitlesCarryStartAndRecordedSpan() {
        assertEquals("Waiting for a fix · since 14:02", recordSubtitle(RecordStatus.Searching, false, start, null, f))
        assertEquals("Since 14:02 · 1 h 23 min", recordSubtitle(RecordStatus.Fix, false, start, lastFix, f))
        assertEquals("Since 14:02", recordSubtitle(RecordStatus.Fix, false, start, null, f))
        assertEquals("Since 14:02 · 1 h 23 min recorded", recordSubtitle(RecordStatus.Paused, false, start, lastFix, f))
        assertEquals("Since 14:02", recordSubtitle(RecordStatus.Paused, false, start, null, f))
    }

    @Test
    fun fixBeforeStartIsNotASpan() {
        assertEquals("Since 14:02", recordSubtitle(RecordStatus.Fix, false, start, start - 1L, f))
    }
}
