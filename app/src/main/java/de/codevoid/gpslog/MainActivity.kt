package de.codevoid.gpslog

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import de.codevoid.gpslog.service.LoggingStateHolder
import de.codevoid.gpslog.ui.GpsLogScreen
import de.codevoid.gpslog.ui.GpsLogTheme
import de.codevoid.gpslog.ui.MainViewModel
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val requestForeground = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fineOrCoarse = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineOrCoarse) requestBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else requestBatteryExemption()
    }

    private val requestBackground = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { requestBatteryExemption() }

    /**
     * Record blocks Start while Location is off and says to turn it on — and the switch usually
     * gets flipped from the quick-settings shade, which does not reliably pause the Activity. A
     * resume-only read would therefore strand the user behind the very message telling them what
     * to do. This is the platform's own signal for it, so no polling and no timer.
     */
    private val providersChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = vm.refreshInternalGpsEnabled()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissionsUpFront()
        setContent {
            GpsLogTheme {
                GpsLogScreen(
                    vm = vm,
                    onShare = { exportForShare() },
                    onShareDebugLog = { shareDebugLog() },
                    onOpenSettings = { openAppSettings() },
                    onInstall = { installUpdate(it) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(
            providersChanged,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
            Context.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(providersChanged)
    }

    override fun onResume() {
        super.onResume()
        LoggingStateHolder.uiVisible = true
        vm.setPreciseLocation(
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
        vm.refreshInternalGpsEnabled()
        vm.refreshRuns()
    }

    override fun onPause() {
        super.onPause()
        LoggingStateHolder.uiVisible = false
    }

    private fun requestPermissionsUpFront() {
        requestForeground.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        )
    }

    private val packageUri: Uri get() = Uri.parse("package:$packageName")

    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(packageUri)) }
    }

    private fun openAppSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(packageUri)) }
    }

    /**
     * Hands the downloaded APK to the system package installer. An in-place update only succeeds
     * when the new APK is signed with the same key as the installed build (the CI keystore). The
     * first time, the user must grant "install unknown apps"; we route them there and they re-tap.
     */
    private fun installUpdate(file: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).setData(packageUri)) }
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri(file), "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
    }

    private fun exportForShare() {
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "gpslog-${FILE_FMT.format(Instant.now())}.gpx")
        vm.export(file.outputStream()) { share(file, MIME_GPX, "Share GPX") }
    }

    private fun shareDebugLog() {
        val file = vm.latestDebugLog.value?.file ?: return
        share(file, MIME_TEXT, "Share debug log")
    }

    private fun share(file: File, mime: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, contentUri(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, title))
    }

    private fun contentUri(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

    private companion object {
        const val MIME_GPX = "application/gpx+xml"
        const val MIME_TEXT = "text/plain"
        val FILE_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())
    }
}
