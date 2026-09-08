package de.codevoid.gpslog.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import de.codevoid.gpslog.App
import de.codevoid.gpslog.MainActivity
import de.codevoid.gpslog.R
import de.codevoid.gpslog.data.RunReader
import de.codevoid.gpslog.data.RunRepository
import de.codevoid.gpslog.data.RunWriter
import de.codevoid.gpslog.data.SettingsStore
import de.codevoid.gpslog.model.GpsRecord
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
class LoggingService : Service() {

    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler
    private val handlerExecutor = Executor { handler.post(it) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var locationManager: LocationManager
    private lateinit var settings: SettingsStore
    private lateinit var runs: RunRepository
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var running = false

    // Handler-thread-only state.
    private var writer: RunWriter? = null
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
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gpslog:logging")
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
        acquireWakeLock()
        handler.post { startLogging(id) }
    }

    private fun startLogging(id: Long) {
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
                gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER),
            )
        )
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 0L, 0f, listener, handlerThread.looper,
            )
            locationManager.registerGnssStatusCallback(handlerExecutor, gnssCallback)
        } catch (e: SecurityException) {
            stopLoggingInternal(clearActive = true)
            return
        }
        handler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS)
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)
        override fun onProviderEnabled(provider: String) = setGpsEnabled(true)
        override fun onProviderDisabled(provider: String) = setGpsEnabled(false)
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            onGnssStatus(visible = status.satelliteCount, usedInFix = used)
        }

        override fun onStopped() = onGnssStatus(visible = 0, usedInFix = 0)
    }

    private fun setGpsEnabled(enabled: Boolean) {
        LoggingStateHolder.update { it.copy(gpsEnabled = enabled) }
    }

    /** ~1 Hz from the GNSS engine; drives the notification only while there is no fix. */
    private fun onGnssStatus(visible: Int, usedInFix: Int) {
        LoggingStateHolder.update { it.copy(satellitesVisible = visible, satellitesUsedInFix = usedInFix) }
        if (usedInFix > 0) return
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastNotifUpdateMs >= NOTIF_THROTTLE_MS) {
            lastNotifUpdateMs = nowMs
            updateNotification(getString(R.string.notif_no_fix, visible))
        }
    }

    private fun onFix(location: Location) {
        val w = writer ?: return
        w.append(location.toRecord())
        pointCount += 1

        fixTimestampsNanos.addLast(location.elapsedRealtimeNanos)
        while (fixTimestampsNanos.size > RATE_WINDOW) fixTimestampsNanos.removeFirst()
        val rate = computeRateHz()

        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastUiUpdateMs >= UI_THROTTLE_MS) {
            lastUiUpdateMs = nowMs
            LoggingStateHolder.update {
                it.copy(
                    pointCount = pointCount,
                    updateRateHz = rate,
                    lastFixTimeMillis = location.time,
                    speedMetersPerSecond = if (location.hasSpeed()) location.speed else null,
                    accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                )
            }
        }
        if (nowMs - lastNotifUpdateMs >= NOTIF_THROTTLE_MS) {
            lastNotifUpdateMs = nowMs
            updateNotification(
                getString(R.string.notif_logging, pointCount, String.format(Locale.US, "%.1f", rate))
            )
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
        try {
            locationManager.removeUpdates(listener)
            locationManager.unregisterGnssStatusCallback(gnssCallback)
        } catch (_: Exception) {
        }
        writer?.close()
        writer = null
        running = false
        if (clearActive) settings.clearActiveRun()
        LoggingStateHolder.reset()
        mainHandler.post {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(flushRunnable)
        try {
            locationManager.removeUpdates(listener)
            locationManager.unregisterGnssStatusCallback(gnssCallback)
        } catch (_: Exception) {
        }
        writer?.close()
        writer = null
        releaseWakeLock()
        handlerThread.quitSafely()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        wakeLock?.let { if (!it.isHeld) it.acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
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

        private const val CHANNEL_ID = "logging"
        private const val NOTIF_ID = 1
        private const val FLUSH_INTERVAL_MS = 10_000L
        private const val UI_THROTTLE_MS = 250L
        private const val NOTIF_THROTTLE_MS = 1_000L
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
