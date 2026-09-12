package de.codevoid.gpslog.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import de.codevoid.gpslog.App
import de.codevoid.gpslog.MainActivity
import de.codevoid.gpslog.R
import de.codevoid.gpslog.data.RunReader
import de.codevoid.gpslog.data.RunRepository
import de.codevoid.gpslog.data.RunWriter
import de.codevoid.gpslog.data.SettingsStore
import de.codevoid.gpslog.model.GpsRecord
import java.io.File
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Foreground service that records raw GPS fixes at the chipset rate. Fixes are delivered on a
 * dedicated HandlerThread (never the main Looper); every fix is written to disk while UI state and
 * the notification are updated at a throttled rate. GNSS satellite status is tracked alongside so
 * the UI can show why no points arrive (provider off, no fix) and how many satellites are in view.
 * A partial wake lock keeps writes flowing under Doze. The service exists only for the duration of
 * a run and resumes an interrupted run on system-driven restart (START_STICKY) or reboot
 * (BootReceiver).
 */
class LoggingService : Service(), FixSink {

    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler
    private val handlerExecutor = Executor { handler.post(it) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var locationManager: LocationManager
    private lateinit var settings: SettingsStore
    private lateinit var runs: RunRepository

    @Volatile
    private var running = false

    // Handler-thread-only state.
    private var writer: RunWriter? = null
    private var bluetoothSource: BluetoothNmeaSource? = null
    private var debugLog: NmeaDebugLog? = null
    private var runId: Long = -1L
    private var startTimeMillis: Long = 0L
    private var pointCount: Long = 0
    private val fixTimestampsNanos = ArrayDeque<Long>()
    private var lastUiUpdateMs = 0L
    private var lastNotifUpdateMs = 0L

    override fun onCreate() {
        super.onCreate()
        val app = App.from(this)
        settings = app.settings
        runs = app.runs
        locationManager = getSystemService(LocationManager::class.java)
        createChannel()
        handlerThread = HandlerThread("gps-logger").apply { start() }
        handler = Handler(handlerThread.looper)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopLogging()
                return START_NOT_STICKY
            }
            ACTION_START -> beginForegroundAndLog(newRun = true)
            else -> beginForegroundAndLog(newRun = false) // ACTION_RESUME or sticky restart (null)
        }
        return START_STICKY
    }

    private fun beginForegroundAndLog(newRun: Boolean) {
        val id = if (newRun) {
            System.currentTimeMillis().also { settings.setActiveRun(it) }
        } else {
            settings.activeRunId()
        }
        if (id == null) {
            stopSelf()
            return
        }
        if (running) return
        running = true
        startForeground(
            NOTIF_ID,
            buildNotification(getString(R.string.notif_starting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        handler.post { startLogging(id) }
    }

    private fun startLogging(id: Long) {
        val source = settings.recordingSource.value
        val internal = source.isEmpty()
        // Internal: coarse-only access does not throw but GPS_PROVIDER is silently fuzzed and
        // throttled to one fix per 10 min and GnssStatus never fires, so refuse rather than record a
        // dead run. External: reading the socket needs BLUETOOTH_CONNECT.
        val required =
            if (internal) Manifest.permission.ACCESS_FINE_LOCATION
            else Manifest.permission.BLUETOOTH_CONNECT
        if (checkSelfPermission(required) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "$required not granted; refusing to log")
            stopLoggingInternal(clearActive = true)
            return
        }
        val file = runs.runFile(id)
        runId = id
        pointCount = RunReader.pointCount(file)
        startTimeMillis = RunReader.firstRecord(file)?.timeMillis ?: id
        writer = RunWriter(file)
        fixTimestampsNanos.clear()
        lastUiUpdateMs = 0L
        lastNotifUpdateMs = 0L
        LoggingStateHolder.set(
            LoggingState(
                isLogging = true,
                runId = id,
                startTimeMillis = startTimeMillis,
                pointCount = pointCount,
                // External "enabled" flips true once the first sentence arrives.
                gpsEnabled = internal && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER),
            )
        )
        val debugFile = if (settings.debugLogging.value) {
            File(File(cacheDir, "debug").apply { mkdirs() }, "nmea-$id.log")
        } else {
            null
        }
        if (internal) {
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 0L, 0f, listener, handlerThread.looper,
                )
                if (!locationManager.registerGnssStatusCallback(handlerExecutor, gnssCallback)) {
                    Log.w(TAG, "GnssStatus callback not registered; satellite info unavailable")
                }
            } catch (e: SecurityException) {
                stopLoggingInternal(clearActive = true)
                return
            }
            // Debug: dump the chipset's identity/capabilities and tee its NMEA, at the fix rate the
            // HAL emits — the only app-level probe of the internal receiver's real rate/constellations.
            debugFile?.let { f ->
                debugLog = runCatching { NmeaDebugLog(f) }.getOrNull()?.also { log ->
                    log.note("session start; internal GPS_PROVIDER")
                    log.note("hardwareModel=${locationManager.gnssHardwareModelName ?: "unknown"}")
                    log.note("hardwareYear=${locationManager.gnssYearOfHardware}")
                    log.note("capabilities=${locationManager.gnssCapabilities}")
                    runCatching { locationManager.addNmeaListener(handlerExecutor, nmeaListener) }
                }
            }
        } else {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) {
                Log.w(TAG, "Bluetooth unavailable or disabled; refusing to log")
                stopLoggingInternal(clearActive = true)
                return
            }
            bluetoothSource = BluetoothNmeaSource(adapter, source, this, handler, debugFile)
                .also { it.start() }
        }
        handler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS)
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location.toRecord())
        override fun onProviderEnabled(provider: String) = onSourceEnabled(true)
        override fun onProviderDisabled(provider: String) = onSourceEnabled(false)
    }

    /** Debug only: tees the internal chipset's NMEA (delivered on the handler thread) to the log. */
    private val nmeaListener = OnNmeaMessageListener { message, _ -> debugLog?.line(message.trim()) }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onStarted() {
            LoggingStateHolder.update { it.copy(gnssRunning = true) }
        }

        override fun onStopped() {
            LoggingStateHolder.update {
                it.copy(gnssRunning = false, satellitesVisible = 0, satellitesUsedInFix = 0)
            }
        }

        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            onSatelliteStatus(visible = status.satelliteCount, usedInFix = used)
        }
    }

    /** [FixSink] — receiver/provider on/off; off also drops the (now meaningless) satellite state. */
    override fun onSourceEnabled(enabled: Boolean) {
        LoggingStateHolder.update {
            if (enabled) {
                it.copy(gpsEnabled = true)
            } else {
                it.copy(
                    gpsEnabled = false,
                    gnssRunning = false,
                    satellitesVisible = 0,
                    satellitesUsedInFix = 0,
                )
            }
        }
    }

    /** [FixSink] — ~1 Hz from the GNSS engine (or NMEA GGA/GSV); drives the no-fix notification. */
    override fun onSatelliteStatus(visible: Int, usedInFix: Int) {
        LoggingStateHolder.update { state ->
            if (LoggingStateHolder.uiVisible) {
                state.copy(gnssRunning = true, satellitesVisible = visible, satellitesUsedInFix = usedInFix)
            } else {
                // gnssRunning must stay accurate for the BT path (the only signal the receiver is live).
                // StateFlow skips emission when the value is unchanged, so this is free once it's true.
                state.copy(gnssRunning = true)
            }
        }
        if (usedInFix > 0) return
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastNotifUpdateMs >= NOTIF_THROTTLE_MS) {
            lastNotifUpdateMs = nowMs
            updateNotification(getString(R.string.notif_no_fix, visible))
        }
    }

    /** [FixSink] — one recorded fix, from the internal provider or an external NMEA source. */
    override fun onFix(record: GpsRecord) {
        val w = writer ?: return
        w.append(record)
        pointCount += 1

        fixTimestampsNanos.addLast(record.elapsedRealtimeNanos)
        while (fixTimestampsNanos.size > RATE_WINDOW) fixTimestampsNanos.removeFirst()

        val nowMs = SystemClock.elapsedRealtime()
        val uiDue = LoggingStateHolder.uiVisible && nowMs - lastUiUpdateMs >= UI_THROTTLE_MS
        val notifDue = nowMs - lastNotifUpdateMs >= NOTIF_THROTTLE_MS
        if (uiDue || notifDue) {
            val rate = computeRateHz()
            if (uiDue) {
                lastUiUpdateMs = nowMs
                LoggingStateHolder.update {
                    it.copy(
                        pointCount = pointCount,
                        updateRateHz = rate,
                        lastFixTimeMillis = record.timeMillis,
                        speedMetersPerSecond = record.speed,
                        accuracyMeters = record.accuracy,
                    )
                }
            }
            if (notifDue) {
                lastNotifUpdateMs = nowMs
                updateNotification(
                    getString(R.string.notif_logging, pointCount, String.format(Locale.US, "%.1f", rate))
                )
            }
        }
    }

    private fun computeRateHz(): Float {
        if (fixTimestampsNanos.size < 2) return 0f
        val span = fixTimestampsNanos.last() - fixTimestampsNanos.first()
        if (span <= 0L) return 0f
        return (fixTimestampsNanos.size - 1) * 1_000_000_000f / span
    }

    private val flushRunnable = object : Runnable {
        override fun run() {
            writer?.flush()
            handler.postDelayed(this, FLUSH_INTERVAL_MS)
        }
    }

    private fun stopLogging() {
        handler.post { stopLoggingInternal(clearActive = true) }
    }

    private fun stopLoggingInternal(clearActive: Boolean) {
        handler.removeCallbacks(flushRunnable)
        bluetoothSource?.stop()
        bluetoothSource = null
        try {
            locationManager.removeUpdates(listener)
            locationManager.unregisterGnssStatusCallback(gnssCallback)
            locationManager.removeNmeaListener(nmeaListener)
        } catch (_: Exception) {
        }
        debugLog?.note("session end")
        debugLog?.close()
        debugLog = null
        writer?.close()
        writer = null
        running = false
        if (clearActive) settings.clearActiveRun()
        LoggingStateHolder.reset()
        mainHandler.post {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(flushRunnable)
        bluetoothSource?.stop()
        bluetoothSource = null
        try {
            locationManager.removeUpdates(listener)
            locationManager.unregisterGnssStatusCallback(gnssCallback)
            locationManager.removeNmeaListener(nmeaListener)
        } catch (_: Exception) {
        }
        debugLog?.note("session end")
        debugLog?.close()
        debugLog = null
        writer?.close()
        writer = null
        handlerThread.quitSafely()
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_logging)
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_START = "de.codevoid.gpslog.action.START"
        const val ACTION_STOP = "de.codevoid.gpslog.action.STOP"
        const val ACTION_RESUME = "de.codevoid.gpslog.action.RESUME"

        private const val TAG = "LoggingService"
        private const val CHANNEL_ID = "logging"
        private const val NOTIF_ID = 1
        private const val FLUSH_INTERVAL_MS = 60_000L
        private const val UI_THROTTLE_MS = 2_000L
        private const val NOTIF_THROTTLE_MS = 2_000L
        private const val RATE_WINDOW = 10

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, LoggingService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LoggingService::class.java).setAction(ACTION_STOP)
            )
        }

        fun resume(context: Context) {
            context.startForegroundService(
                Intent(context, LoggingService::class.java).setAction(ACTION_RESUME)
            )
        }
    }
}

private fun Location.toRecord(): GpsRecord = GpsRecord(
    timeMillis = time,
    elapsedRealtimeNanos = elapsedRealtimeNanos,
    latitude = latitude,
    longitude = longitude,
    altitude = if (hasAltitude()) altitude else null,
    accuracy = if (hasAccuracy()) accuracy else null,
    verticalAccuracy = if (hasVerticalAccuracy()) verticalAccuracyMeters else null,
    speed = if (hasSpeed()) speed else null,
    speedAccuracy = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
    bearing = if (hasBearing()) bearing else null,
    bearingAccuracy = if (hasBearingAccuracy()) bearingAccuracyDegrees else null,
)
