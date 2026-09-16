package de.codevoid.gpslog.ui

import de.codevoid.gpslog.service.LoggingState

/** The one word the Record surface leads with. */
enum class RecordStatus(val label: String) {
    /** Not logging, ready to start. */
    Idle("Ready"),
    /** Not logging: the internal source is chosen but this device has no GPS chipset. */
    NoDevice("No GPS device"),
    /** Not logging and precise location is denied, so Start is refused. */
    Unavailable("Unavailable"),
    Paused("Paused"),
    /** Internal source: the GPS provider is switched off in system settings. */
    Disabled("GPS disabled"),
    /** The receiver is not delivering: Bluetooth link down, or the GNSS engine stopped. */
    ReceiverOff("Receiver off"),
    Searching("Searching"),
    Fix("Fix"),
}

/**
 * Derives the status from the live state. [external] matters for `gpsEnabled`: the service
 * initialises it as `isInternalSource && providerEnabled` and drops it on a Bluetooth disconnect,
 * so for an external receiver a false value means "receiver not connected", never "GPS disabled".
 *
 * [hasInternalGps] is a hardware fact, so it only blocks the internal source, and only while
 * idle: a run that is already recording reports what it is doing. It outranks [preciseLocation]
 * because a missing chipset is not something the user can grant their way out of.
 */
fun recordStatus(
    state: LoggingState,
    external: Boolean,
    preciseLocation: Boolean,
    hasInternalGps: Boolean,
): RecordStatus = when {
    !state.isLogging -> when {
        !external && !hasInternalGps -> RecordStatus.NoDevice
        !preciseLocation -> RecordStatus.Unavailable
        else -> RecordStatus.Idle
    }
    state.isPaused -> RecordStatus.Paused
    !state.gpsEnabled -> if (external) RecordStatus.ReceiverOff else RecordStatus.Disabled
    !state.gnssRunning -> RecordStatus.ReceiverOff
    state.hasFix -> RecordStatus.Fix
    else -> RecordStatus.Searching
}

/**
 * The line under the status word. While logging it carries the start time and, once fixes have
 * arrived, the recorded span `lastFix − start`, which advances with the fixes and freezes while
 * paused — no clock ticks needed.
 */
fun recordSubtitle(
    status: RecordStatus,
    external: Boolean,
    startMillis: Long?,
    lastFixMillis: Long?,
    f: Formats,
): String {
    val since = startMillis?.let { "Since ${f.clockShort(it)}" }
    val span = if (startMillis != null && lastFixMillis != null && lastFixMillis >= startMillis) {
        formatDuration(lastFixMillis - startMillis)
    } else {
        null
    }
    return when (status) {
        RecordStatus.Idle -> if (external) "External receiver" else "Internal GPS"
        RecordStatus.NoDevice -> "Select an external receiver in Settings"
        RecordStatus.Unavailable -> "Precise location is off"
        RecordStatus.Disabled -> problem("Turn on Location in system settings", since)
        RecordStatus.ReceiverOff -> problem(if (external) "Waiting for the receiver" else "GNSS engine stopped", since)
        RecordStatus.Searching -> problem("Waiting for a fix", since)
        RecordStatus.Fix -> listOfNotNull(since, span).joinToString(" · ")
        RecordStatus.Paused -> listOfNotNull(since, span?.let { "$it recorded" }).joinToString(" · ")
    }
}

/** "<what is wrong> · since 14:02" — the run's start time stays visible while it is in trouble. */
private fun problem(text: String, since: String?): String =
    listOfNotNull(text, since?.replaceFirstChar { it.lowercase() }).joinToString(" · ")
