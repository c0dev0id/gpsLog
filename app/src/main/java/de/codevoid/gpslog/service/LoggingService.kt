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
import android.graphics.drawable.Icon
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
 * Foreground service that records raw GPS fixes at the chipset rate, from either the internal
 * provider or an external Bluetooth NMEA receiver. Fixes are delivered on a dedicated HandlerThread
 * (never the main Looper); each one is appended to the writer's in-memory buffer and flushed on a
 * fixed cadence, while UI state and the notification are updated at a throttled rate. GNSS satellite
 * status is tracked alongside so the UI can show why no points arrive (provider off, no fix) and how
 * many satellites are in view. No wake lock is held — the foreground-service location type keeps the
 * relevant subsystems running without pinning the CPU for the length of a run.
 *
 * The service exists only for the duration of a run and resumes an interrupted one on system-driven
 * restart (START_STICKY) or reboot (BootReceiver). Pausing releases the fix source entirely rather
 * than merely dropping fixes, and the paused state is persisted, so a paused run survives a restart
 * without drawing power.
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
    private var paused = false
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
            // Guarded: these also arrive from the notification, which can outlive a dead run.
            ACTION_PAUSE -> handler.post { if (running) setPaused(true) }
            ACTION_UNPAUSE -> handler.post { if (running) setPaused(false) }
            ACTION_START -> beginForegroundAndLog(newRun = true)
            else -> beginForegroundAndLog(newRun = false) // ACTION_RESUME or sticky restart (null)
        }
        return START_STICKY
    }

    private fun beginForegroundAndLog(newRun: Boolean) {
        // Before the id is computed, not after: a second ACTION_START — a double tap on an
        // undebounced button, or a redelivered intent — used to repoint the active-run marker at
        // an id nothing ever writes, and clear the paused flag of the run still in flight.
        if (running) return
        val id = if (newRun) {
            System.currentTimeMillis().also { settings.setActiveRun(it) }
        } else {
            settings.activeRunId()
        }
        if (id == null) {
            stopSelf()
            return
        }
        running = true
        val notification = buildNotification(getString(R.string.notif_starting))
        try {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: RuntimeException) {
            // The `location` service type needs ACCESS_COARSE or _FINE_LOCATION at runtime, and its
            // policy is while-in-use only, so a background start — BootReceiver, a sticky restart —
            // additionally needs ACCESS_BACKGROUND_LOCATION. The platform answers a shortfall by
            // throwing, and Service.startForeground swallows only RemoteException, so it surfaced
            // here on the main thread; with the marker outliving it, every boot died the same way.
            // RuntimeException is the right width: the background-start refusals arrive as
            // ForegroundServiceStartNotAllowedException, not SecurityException. Swallowing is safe
            // — the "did not call startForeground" ANR timer is cleared before the type is
            // validated, so catching cannot leave it armed.
            Log.w(TAG, "foreground service refused; not logging", e)
            running = false
            // A run that never began leaves no marker behind; an interrupted one keeps its own, so
            // it can still be resumed once the permission is there.
            if (newRun) settings.clearActiveRun()
            stopSelf()
            return
        }
        handler.post { startLogging(id, newRun) }
    }

    private fun startLogging(id: Long, newRun: Boolean) {
        val file = runs.runFile(id)
        runId = id
        pointCount = RunReader.pointCount(file)
        startTimeMillis = RunReader.firstRecord(file)?.timeMillis ?: id
        fixTimestampsNanos.clear()
        lastUiUpdateMs = 0L
        lastNotifUpdateMs = 0L
        // A run paused before a reboot or a process kill comes back paused: nothing is acquired
        // and nothing is recorded until the user resumes.
        paused = settings.isPaused()
        // Acquired before the writer exists so a refusal leaves no empty run file behind.
        if (!paused && !startSource()) {
            // Same rule as the foreground-service refusal above: only a run that never began
            // gives up its marker. This path is reached from RESUME and the sticky restart too,
            // and at BOOT_COMPLETED the Bluetooth adapter is routinely not up yet — clearing
            // here ended a live multi-hour run the user never stopped, permanently.
            stopLoggingInternal(clearActive = newRun)
            return
        }
        writer = RunWriter(file)
        LoggingStateHolder.set(
            LoggingState(
                isLogging = true,
                runId = id,
                startTimeMillis = startTimeMillis,
                pointCount = pointCount,
                isPaused = paused,
                // External "enabled" flips true once the first sentence arrives.
                gpsEnabled = isInternalSource() && internalGpsEnabled(),
            )
        )
        if (paused) {
            updateNotification(getString(R.string.notif_paused, pointCount))
        } else {
            handler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS)
        }
    }

    private fun isInternalSource(): Boolean = settings.recordingSource.value.isEmpty()

    /**
     * Whether the internal provider is switched on. Its documented return is "true if the provider
     * exists and is enabled", so a device with no chipset reads as off here too — which is all this
     * value is for. Telling *absent* from *switched off* is `hasProvider`'s job, and the two are
     * different words to the user, so do not collapse them.
     */
    private fun internalGpsEnabled(): Boolean =
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

    /**
     * Acquires the configured fix source. Returns false when it cannot be started (permission
     * refused, Bluetooth off), having registered nothing. Logger thread only: it reads [runId] and
     * assigns [bluetoothSource] / [debugLog].
     */
    private fun startSource(): Boolean {
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
            return false
        }
        // A device without a GNSS chipset has no GPS_PROVIDER at all, and requestLocationUpdates
        // throws IllegalArgumentException for a provider that does not exist — on this thread,
        // which would take the process with it. Refuse before anything is registered.
        if (internal && !locationManager.hasProvider(LocationManager.GPS_PROVIDER)) {
            Log.w(TAG, "device has no GPS provider; refusing to log")
            return false
        }
        val debugFile = if (settings.debugLogging.value) {
            File(File(cacheDir, "debug").apply { mkdirs() }, "nmea-$runId.log")
        } else {
            null
        }
        if (internal) {
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 0L, 0f, listener, handlerThread.looper,
                )
                // Documented to return true always — registration is client-side, and with no
                // GNSS engine the server side is a no-op — so it is not an availability check.
                locationManager.registerGnssStatusCallback(handlerExecutor, gnssCallback)
            } catch (e: SecurityException) {
                Log.w(TAG, "location updates refused", e)
                return false
            } catch (e: IllegalArgumentException) {
                // hasProvider above and this call are separate round trips to the system server,
                // so the provider can still go away in between. Refusing beats dying.
                Log.w(TAG, "GPS provider gone since it was checked; refusing to log", e)
                return false
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
                return false
            }
            bluetoothSource = BluetoothNmeaSource(
                context = this,
                adapter = adapter,
                deviceAddress = source,
                sink = this,
                deliver = handler,
                debugFile = debugFile,
            ).also { it.start() }
        }
        return true
    }

    /** Releases the fix source and its debug log. Safe to call when nothing is acquired. */
    private fun stopSource() {
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
        if (paused) return
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
        if (!enabled) updateNotification(getString(R.string.notif_receiver_off))
    }

    /**
     * Pause releases the fix source outright rather than just dropping fixes, so the GPS chipset
     * (or the Bluetooth link) powers down and a paused run costs no battery. The flag is persisted,
     * so a reboot or a process kill brings the run back paused. Resuming re-acquires the source; a
     * GPS warm start is quick because the ephemeris is still cached.
     */
    private fun setPaused(value: Boolean) {
        if (paused == value) return
        if (value) {
            paused = true
            settings.setPaused(true)
            handler.removeCallbacks(flushRunnable)
            stopSource()
            writer?.flush()
            fixTimestampsNanos.clear()
            LoggingStateHolder.update {
                it.copy(
                    isPaused = true,
                    gnssRunning = false,
                    satellitesVisible = 0,
                    satellitesUsedInFix = 0,
                    updateRateHz = 0f,
                    speedMetersPerSecond = null,
                    accuracyMeters = null,
                )
            }
            updateNotificationNow(getString(R.string.notif_paused, pointCount))
        } else {
            paused = false
            if (!startSource()) {
                // Stay paused rather than discard a run the user still owns; the notification
                // names the problem and Resume can be tapped again once it is fixed.
                paused = true
                stopSource()
                updateNotificationNow(getString(R.string.notif_paused_blocked))
                return
            }
            settings.setPaused(false)
            fixTimestampsNanos.clear()
            LoggingStateHolder.update {
                it.copy(
                    isPaused = false,
                    // Re-read rather than trust the pre-pause value: registering a listener does
                    // not report the provider's current state, and onProviderDisabled only fires
                    // on a change, so the provider may have been switched off while we were down.
                    gpsEnabled = isInternalSource() && internalGpsEnabled(),
                )
            }
            updateNotificationNow(loggingNotifText())
            handler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS)
        }
    }

    /**
     * Notification update that also stamps the throttle clock, so a fix or satellite-status update
     * cannot overwrite it for the next throttle window.
     */
    private fun updateNotificationNow(text: String) {
        lastNotifUpdateMs = SystemClock.elapsedRealtime()
        updateNotification(text)
    }

    private fun loggingNotifText(rate: Float = computeRateHz()): String =
        getString(R.string.notif_logging, pointCount, String.format(Locale.US, "%.1f", rate))

    /** [FixSink] — ~1 Hz from the GNSS engine (or NMEA GGA/GSV); drives the no-fix notification. */
    override fun onSatelliteStatus(visible: Int, usedInFix: Int) {
        if (paused) return
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
        if (paused) return
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
                updateNotification(loggingNotifText(rate))
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
        stopSource()
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
        stopSource()
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

    /** [action] adds the matching Pause or Resume button; null builds the plain notification. */
    private fun buildNotification(text: String, action: String? = null): Notification {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_logging)
            .setOngoing(true)
            .setContentIntent(tap)
        if (action != null) {
            val label = getString(
                if (action == ACTION_UNPAUSE) R.string.notif_action_resume
                else R.string.notif_action_pause
            )
            // No icon: the standard template does not draw action icons, and there is no
            // transport-control drawable in the project worth adding for one that is never shown.
            builder.addAction(
                Notification.Action.Builder(null as Icon?, label, serviceIntent(action)).build()
            )
        }
        // Stop is always offered, after the toggle so the Pause/Resume slot never moves. It asks
        // nothing, like the in-app Stop: a mis-tap loses no data and Merge rejoins the halves.
        builder.addAction(
            Notification.Action.Builder(
                null as Icon?, getString(R.string.notif_action_stop), serviceIntent(ACTION_STOP),
            ).build()
        )
        return builder.build()
    }

    /** Distinct request codes so the Pause and Resume intents cannot overwrite each other. */
    private fun serviceIntent(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        intent(this, action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Logger thread only — it reads [paused] to decide which action the notification offers. */
    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID,
            buildNotification(text, if (paused) ACTION_UNPAUSE else ACTION_PAUSE),
        )
    }

    companion object {
        const val ACTION_START = "de.codevoid.gpslog.action.START"
        const val ACTION_STOP = "de.codevoid.gpslog.action.STOP"
        const val ACTION_RESUME = "de.codevoid.gpslog.action.RESUME"
        const val ACTION_PAUSE = "de.codevoid.gpslog.action.PAUSE"
        const val ACTION_UNPAUSE = "de.codevoid.gpslog.action.UNPAUSE"

        private const val TAG = "LoggingService"
        private const val CHANNEL_ID = "logging"
        private const val NOTIF_ID = 1
        private const val FLUSH_INTERVAL_MS = 60_000L
        private const val UI_THROTTLE_MS = 2_000L
        private const val NOTIF_THROTTLE_MS = 2_000L
        private const val RATE_WINDOW = 10

        fun start(context: Context) = context.startForegroundService(intent(context, ACTION_START))
        fun stop(context: Context) = context.startService(intent(context, ACTION_STOP))
        fun resume(context: Context) = context.startForegroundService(intent(context, ACTION_RESUME))
        fun pause(context: Context) = context.startService(intent(context, ACTION_PAUSE))
        fun unpause(context: Context) = context.startService(intent(context, ACTION_UNPAUSE))

        fun intent(context: Context, action: String): Intent =
            Intent(context, LoggingService::class.java).setAction(action)
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
