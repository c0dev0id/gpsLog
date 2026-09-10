package de.codevoid.gpslog.ui

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.codevoid.gpslog.App
import de.codevoid.gpslog.data.RunInfo
import de.codevoid.gpslog.export.GpxExporter
import de.codevoid.gpslog.service.LoggingService
import de.codevoid.gpslog.service.LoggingStateHolder
import de.codevoid.gpslog.update.Nightly
import de.codevoid.gpslog.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/**
 * Screen state and actions. Reads the process-global logging state and the persisted settings;
 * owns the (ephemeral) run selection and the disk-derived run list. Service commands are fired as
 * fire-and-forget intents — the service reports back through [LoggingStateHolder].
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = App.from(app).runs
    private val settings = App.from(app).settings

    val loggingState = LoggingStateHolder.state
    val filters = settings.filters

    private val _runs = MutableStateFlow<List<RunInfo>>(emptyList())
    val runs = _runs.asStateFlow()

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected = _selected.asStateFlow()

    /** False when the app holds only approximate location; the Activity keeps this current. */
    private val _preciseLocation = MutableStateFlow(true)
    val preciseLocation = _preciseLocation.asStateFlow()

    /** e.g. `dev-abc1234` for a nightly, `0.0.1` for a tagged/local build. */
    val installedVersion: String =
        app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
            .versionName ?: "unknown"

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update = _update.asStateFlow()

    fun setPreciseLocation(granted: Boolean) {
        _preciseLocation.value = granted
    }

    /** The short commit of the installed nightly, or null when this isn't a nightly build. */
    private fun installedSha(): String? =
        installedVersion.takeIf { it.startsWith("dev-") }?.removePrefix("dev-")

    fun checkForUpdate() {
        _update.value = UpdateState.Checking
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { UpdateChecker.fetchLatest() } }
            _update.value = result.fold(
                onSuccess = { nightly ->
                    when {
                        nightly == null -> UpdateState.Error("No development build is published")
                        nightly.sha == installedSha() -> UpdateState.UpToDate
                        else -> UpdateState.Available(nightly)
                    }
                },
                onFailure = { UpdateState.Error(it.message ?: "Update check failed") },
            )
        }
    }

    /** Downloads the available nightly, then hands the file to [onDownloaded] to fire the installer. */
    fun downloadAndInstall(onDownloaded: (File) -> Unit) {
        val nightly = (_update.value as? UpdateState.Available)?.nightly ?: return
        _update.value = UpdateState.Downloading(0f)
        viewModelScope.launch {
            val dest = File(File(getApplication<Application>().cacheDir, "downloads"), "update.apk")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    UpdateChecker.download(nightly.downloadUrl, dest) { progress ->
                        _update.value = UpdateState.Downloading(progress)
                    }
                    dest
                }
            }
            result.fold(
                onSuccess = { file ->
                    _update.value = UpdateState.Idle
                    onDownloaded(file)
                },
                onFailure = { _update.value = UpdateState.Error(it.message ?: "Download failed") },
            )
        }
    }

    fun refreshRuns() {
        viewModelScope.launch {
            _runs.value = withContext(Dispatchers.IO) { repo.listRuns() }
        }
    }

    fun start() = LoggingService.start(getApplication())

    fun stop() = LoggingService.stop(getApplication())

    fun toggleSelect(id: Long) = _selected.update { if (id in it) it - id else it + id }

    fun delete(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.delete(id) }
            _selected.update { it - id }
            refreshRuns()
        }
    }

    fun setAccuracyMeters(value: Int) = settings.setAccuracyMeters(value)

    fun setDistanceMeters(value: Float) = settings.setDistanceMeters(value)

    fun setTimeSeconds(value: Int) = settings.setTimeSeconds(value)

    /**
     * Streams the selected runs as one GPX document to [out], closing it when done. Runs on IO;
     * [onDone] is invoked on the main thread afterwards (used to fire the share intent).
     */
    fun exportSelected(out: OutputStream, onDone: () -> Unit = {}) {
        val ids = _selected.value
        val current = _runs.value.filter { it.id in ids }
        val filterSnapshot = filters.value
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                out.use { GpxExporter.export(it, current, filterSnapshot) }
            }
            onDone()
        }
    }
}

/** State of the in-app nightly updater surfaced on the Record tab. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val nightly: Nightly) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data class Error(val message: String) : UpdateState
}
