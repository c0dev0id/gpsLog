package de.codevoid.gpslog.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Live state of the current logging run, mirrored to the UI. */
data class LoggingState(
    val isLogging: Boolean = false,
    val runId: Long? = null,
    val startTimeMillis: Long? = null,
    val pointCount: Long = 0,
    val updateRateHz: Float = 0f,
    val lastFixTimeMillis: Long? = null,
    val speedMetersPerSecond: Float? = null,
    val accuracyMeters: Float? = null,
)

/**
 * Process-global holder for [LoggingState]. The service writes it at the (throttled) UI rate; the
 * ViewModel collects it. Single process, so a plain singleton is sufficient — no Binder needed.
 * State does not survive process death; "is a run active" is authoritative in SettingsStore.
 */
object LoggingStateHolder {
    private val _state = MutableStateFlow(LoggingState())
    val state: StateFlow<LoggingState> = _state.asStateFlow()

    fun set(state: LoggingState) {
        _state.value = state
    }

    fun update(transform: (LoggingState) -> LoggingState) {
        _state.update(transform)
    }

    fun reset() {
        _state.value = LoggingState()
    }
}
