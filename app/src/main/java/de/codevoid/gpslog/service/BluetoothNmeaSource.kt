package de.codevoid.gpslog.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import de.codevoid.gpslog.data.NmeaParser
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID

/**
 * Records fixes from an external classic-Bluetooth GNSS receiver speaking NMEA over the SPP profile.
 *
 * The blocking RFCOMM read runs on this class's **own** reader thread — never the caller's
 * `gps-logger` HandlerThread, which must stay free to run the flush and notification callbacks. Each
 * parsed fix and status change is posted back onto [deliver] (the logger handler), so the sink — and
 * therefore the [de.codevoid.gpslog.data.RunWriter] and the handler-thread counters — are only ever
 * touched on that single thread, exactly as with the internal provider.
 *
 * While [start]ed, the reader auto-reconnects after a dropped link and reports the receiver as off
 * in the meantime; it keeps retrying until [stop]. A missing `BLUETOOTH_CONNECT` permission stops it.
 */
class BluetoothNmeaSource(
    private val adapter: BluetoothAdapter,
    private val deviceAddress: String,
    private val sink: FixSink,
    private val deliver: Handler,
    private val debugFile: File? = null,
) {

    @Volatile
    private var running = false
    private var socket: BluetoothSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::runLoop, "gps-bt-reader").also { it.start() }
    }

    fun stop() {
        running = false
        closeSocket()
        thread?.interrupt()
        thread = null
    }

    private fun runLoop() {
        val debug = debugFile?.let { runCatching { NmeaDebugLog(it) }.getOrNull() }
        debug?.note("session start; device=$deviceAddress")
        try {
            while (running) {
                var lastVisible = -1
                var lastUsed = -1
                var lastSatPostMs = 0L
                val parser = NmeaParser()
                try {
                    debug?.note("connecting")
                    val s = adapter.getRemoteDevice(deviceAddress)
                        .createRfcommSocketToServiceRecord(SPP_UUID)
                    socket = s
                    s.connect()
                    debug?.note("connected")
                    deliver.post { sink.onSourceEnabled(true) }
                    val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.US_ASCII))
                    while (running) {
                        val line = reader.readLine() ?: break
                        debug?.line(line)
                        val result = parser.parse(line, SystemClock.elapsedRealtimeNanos())
                        val nowMs = SystemClock.elapsedRealtime()
                        if ((result.satellitesVisible != lastVisible || result.satellitesUsedInFix != lastUsed) &&
                            nowMs - lastSatPostMs >= SAT_THROTTLE_MS
                        ) {
                            lastVisible = result.satellitesVisible
                            lastUsed = result.satellitesUsedInFix
                            lastSatPostMs = nowMs
                            deliver.post {
                                sink.onSatelliteStatus(result.satellitesVisible, result.satellitesUsedInFix)
                            }
                        }
                        result.record?.let { record -> deliver.post { sink.onFix(record) } }
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "BLUETOOTH_CONNECT missing; stopping Bluetooth source", e)
                    debug?.note("permission denied; stopping")
                    running = false
                } catch (e: IOException) {
                    Log.w(TAG, "Bluetooth link error; will retry", e)
                    debug?.note("link error: ${e.message}")
                } finally {
                    closeSocket()
                }
                if (running) {
                    deliver.post { sink.onSourceEnabled(false) }
                    debug?.note("disconnected; retrying in ${RECONNECT_DELAY_MS} ms")
                    try {
                        Thread.sleep(RECONNECT_DELAY_MS)
                    } catch (_: InterruptedException) {
                        // stop() interrupts us; the running flag ends the loop.
                    }
                }
            }
        } finally {
            debug?.note("session end")
            debug?.close()
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    private companion object {
        const val TAG = "BluetoothNmeaSource"
        const val RECONNECT_DELAY_MS = 3_000L
        const val SAT_THROTTLE_MS = 2_000L
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
