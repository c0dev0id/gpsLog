package de.codevoid.gpslog

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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

    override fun onResume() {
        super.onResume()
        vm.setPreciseLocation(
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
        vm.refreshRuns()
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

    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }
    }

    /**
     * Hands the downloaded APK to the system package installer. An in-place update only succeeds
     * when the new APK is signed with the same key as the installed build (the CI keystore). The
     * first time, the user must grant "install unknown apps"; we route them there and they re-tap.
     */
    private fun installUpdate(file: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:$packageName"))
            runCatching { startActivity(intent) }
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
    }

    private fun exportForShare() {
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, suggestedName())
        vm.exportSelected(file.outputStream()) {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = MIME_GPX
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share GPX"))
        }
    }

    private fun shareDebugLog() {
        val file = vm.latestDebugLog() ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TEXT
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share debug log"))
    }

    private fun suggestedName(): String =
        "gpslog-${FILE_FMT.format(Instant.now())}.gpx"

    private companion object {
        const val MIME_GPX = "application/gpx+xml"
        const val MIME_TEXT = "text/plain"
        val FILE_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())
    }
}
