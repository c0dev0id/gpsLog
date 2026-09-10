package de.codevoid.gpslog.service

import de.codevoid.gpslog.model.GpsRecord

/**
 * Destination for fixes and receiver status, implemented by [LoggingService]. It decouples a fix
 * source (the internal provider or a [BluetoothNmeaSource]) from the writer/state-holder plumbing so
 * a source can be unit-tested against a fake sink. All callbacks are invoked on the `gps-logger`
 * HandlerThread (the source marshals them there), preserving the single-threaded writer contract.
 */
interface FixSink {
    fun onFix(record: GpsRecord)
    fun onSatelliteStatus(visible: Int, usedInFix: Int)

    /** Provider/receiver on/off — the external analogue of `onProviderEnabled/Disabled`. */
    fun onSourceEnabled(enabled: Boolean)
}
