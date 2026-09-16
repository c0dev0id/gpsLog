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

    /** Defaults are "a working internal GPS on a permitted app", so each test names only its case. */
    private fun status(
        state: LoggingState = LoggingState(),
        external: Boolean = false,
        preciseLocation: Boolean = true,
        hasInternalGps: Boolean = true,
        internalGpsEnabled: Boolean = true,
    ) = recordStatus(state, external, preciseLocation, hasInternalGps, internalGpsEnabled)

    @Test
    fun idleIsReadyOnlyWhenNothingBlocksARun() {
        assertEquals(RecordStatus.Idle, status())
        assertEquals(RecordStatus.Unavailable, status(preciseLocation = false))
        assertEquals(RecordStatus.Disabled, status(internalGpsEnabled = false))
        assertEquals(RecordStatus.NoDevice, status(hasInternalGps = false))
    }

    /** Outermost obstacle first: absent hardware, then the Location switch, then the permission. */
    @Test
    fun theIdleObstaclesAreRankedOutermostFirst() {
        assertEquals(
            RecordStatus.NoDevice,
            status(preciseLocation = false, hasInternalGps = false, internalGpsEnabled = false),
        )
        assertEquals(
            RecordStatus.Disabled,
            status(preciseLocation = false, internalGpsEnabled = false),
        )
    }

    @Test
    fun anExternalReceiverIsUnaffectedByTheInternalChipset() {
        assertEquals(
            RecordStatus.Idle,
            status(external = true, hasInternalGps = false, internalGpsEnabled = false),
        )
    }

    @Test
    fun theIdleObstaclesNeverOverrideARunInProgress() {
        assertEquals(
            RecordStatus.Fix,
            status(
                state = logging(gnssRunning = true, used = 5),
                hasInternalGps = false,
                internalGpsEnabled = false,
            ),
        )
    }

    @Test
    fun pausedBeatsEverythingElse() {
        assertEquals(RecordStatus.Paused, status(state = logging(paused = true, gpsEnabled = false)))
    }

    @Test
    fun providerOffIsDisabledOnlyForTheInternalSource() {
        assertEquals(RecordStatus.Disabled, status(state = logging(gpsEnabled = false)))
        // The service starts an external run with gpsEnabled = false and drops it on a link loss.
        assertEquals(RecordStatus.ReceiverOff, status(state = logging(gpsEnabled = false), external = true))
    }

    @Test
    fun engineStoppedIsReceiverOffForBothSources() {
        assertEquals(RecordStatus.ReceiverOff, status(state = logging(gnssRunning = false)))
        assertEquals(RecordStatus.ReceiverOff, status(state = logging(gnssRunning = false), external = true))
    }

    @Test
    fun runningEngineIsSearchingUntilSatellitesAreUsed() {
        assertEquals(RecordStatus.Searching, status(state = logging(gnssRunning = true, used = 0)))
        assertEquals(RecordStatus.Fix, status(state = logging(gnssRunning = true, used = 5)))
    }

    @Test
    fun revokedPreciseLocationDoesNotHideTheLiveState() {
        assertEquals(
            RecordStatus.Fix,
            status(state = logging(gnssRunning = true, used = 5), preciseLocation = false),
        )
    }

    @Test
    fun idleSubtitlesNameTheSource() {
        assertEquals("Internal GPS", recordSubtitle(RecordStatus.Idle, false, null, null, f))
        assertEquals("External receiver", recordSubtitle(RecordStatus.Idle, true, null, null, f))
        assertEquals("Precise location is off", recordSubtitle(RecordStatus.Unavailable, false, null, null, f))
        assertEquals(
            "Select an external receiver in Settings",
            recordSubtitle(RecordStatus.NoDevice, false, null, null, f),
        )
    }

    @Test
    fun problemSubtitlesSayWhatToDoAndKeepTheStartTime() {
        assertEquals("Turn on Location in system settings", recordSubtitle(RecordStatus.Disabled, false, null, null, f))
        assertEquals("Turn on Location in system settings · since 14:02", recordSubtitle(RecordStatus.Disabled, false, start, null, f))
        assertEquals("Waiting for the receiver · since 14:02", recordSubtitle(RecordStatus.ReceiverOff, true, start, null, f))
        assertEquals("GNSS engine stopped · since 14:02", recordSubtitle(RecordStatus.ReceiverOff, false, start, null, f))
        assertEquals("GNSS engine stopped", recordSubtitle(RecordStatus.ReceiverOff, false, null, null, f))
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
