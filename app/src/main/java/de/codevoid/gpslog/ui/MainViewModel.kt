package de.codevoid.gpslog.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.codevoid.gpslog.App
import de.codevoid.gpslog.data.RunInfo
import de.codevoid.gpslog.export.GpxExporter
import de.codevoid.gpslog.service.LoggingService
import de.codevoid.gpslog.service.LoggingStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    fun setPreciseLocation(granted: Boolean) {
        _preciseLocation.value = granted
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
