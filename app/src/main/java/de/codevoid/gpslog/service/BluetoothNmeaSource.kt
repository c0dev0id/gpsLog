package de.codevoid.gpslog.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import de.codevoid.gpslog.data.NmeaParser
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
 * Retries back off from [MIN_RECONNECT_DELAY_MS] to [MAX_RECONNECT_DELAY_MS] so a receiver that is
 * switched off or out of range for a long time does not cost a connect attempt every few seconds,
 * and an ACL_CONNECTED broadcast cuts the wait short the moment the device is actually back.
 */
class BluetoothNmeaSource(
    context: Context,
    private val adapter: BluetoothAdapter,
    private val deviceAddress: String,
    private val sink: FixSink,
    private val deliver: Handler,
    private val debugFile: File? = null,
) {

    private val appContext = context.applicationContext

    @Volatile
    private var running = false
    private var socket: BluetoothSocket? = null
    private var thread: Thread? = null

    /** Non-null only while the reader thread is waiting out a backoff; counted down to wake it. */
    @Volatile
    private var reconnectSignal: CountDownLatch? = null

    /**
     * Android broadcasts ACL_CONNECTED when the paired receiver re-establishes a link — a free,
     * event-driven signal that it is back in range, with nothing polled and nothing held awake.
     * It only shortcuts the backoff: the reader thread still owns the reconnect, so a receiver that
     * never re-initiates on its own is covered by the backoff alone and this is pure upside.
     */
    private val aclReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device =
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            if (device?.address == deviceAddress) reconnectSignal?.countDown()
        }
    }

    fun start() {
        if (running) return
        running = true
        appContext.registerReceiver(
            aclReceiver,
            IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED),
            Context.RECEIVER_NOT_EXPORTED,
        )
        thread = Thread(::runLoop, "gps-bt-reader").also { it.start() }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { appContext.unregisterReceiver(aclReceiver) }
        closeSocket()
        reconnectSignal?.countDown()
        thread?.interrupt()
        thread = null
    }

    private fun runLoop() {
        val debug = debugFile?.let { runCatching { NmeaDebugLog(it) }.getOrNull() }
        debug?.note("session start; device=$deviceAddress")
        var retryDelayMs = MIN_RECONNECT_DELAY_MS
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
                    retryDelayMs = MIN_RECONNECT_DELAY_MS
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
                    debug?.note("disconnected; retrying in $retryDelayMs ms or on reconnect")
                    awaitRetry(retryDelayMs)
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
                }
            }
        } finally {
            debug?.note("session end")
            debug?.close()
        }
    }

    /**
     * Parks the reader thread until the next connect attempt is due, returning early when the
     * receiver's ACL link comes back or when [stop] is called. The thread is parked, not spinning,
     * so a receiver that stays off costs nothing between attempts.
     */
    private fun awaitRetry(millis: Long) {
        val latch = CountDownLatch(1)
        reconnectSignal = latch
        try {
            latch.await(millis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            // stop() interrupts us; the running flag ends the loop.
        } finally {
            reconnectSignal = null
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
        const val MIN_RECONNECT_DELAY_MS = 3_000L
        const val MAX_RECONNECT_DELAY_MS = 60_000L
        const val SAT_THROTTLE_MS = 2_000L
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
