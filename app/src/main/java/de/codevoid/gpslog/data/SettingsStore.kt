package de.codevoid.gpslog.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Export filter values. Defaults: accuracy 10 m, distance/time off. */
data class FilterSettings(
    val accuracyMeters: Int = 10,
    val distanceMeters: Float = 0f,
    val timeSeconds: Int = 0,
)

/**
 * SharedPreferences-backed settings. Holds the three export filters (surfaced as a reactive
 * [StateFlow] for the UI) and the active-run marker used to resume logging after a process kill or
 * device reboot.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _filters = MutableStateFlow(
        FilterSettings(
            accuracyMeters = prefs.getInt(KEY_ACCURACY, 10),
            distanceMeters = prefs.getFloat(KEY_DISTANCE, 0f),
            timeSeconds = prefs.getInt(KEY_TIME, 0),
        )
    )
    val filters: StateFlow<FilterSettings> = _filters.asStateFlow()

    fun setAccuracyMeters(value: Int) {
        prefs.edit().putInt(KEY_ACCURACY, value).apply()
        _filters.value = _filters.value.copy(accuracyMeters = value)
    }

    fun setDistanceMeters(value: Float) {
        prefs.edit().putFloat(KEY_DISTANCE, value).apply()
        _filters.value = _filters.value.copy(distanceMeters = value)
    }

    fun setTimeSeconds(value: Int) {
        prefs.edit().putInt(KEY_TIME, value).apply()
        _filters.value = _filters.value.copy(timeSeconds = value)
    }

    /** Run id currently being logged, or null when no run is active. */
    fun activeRunId(): Long? = prefs.getLong(KEY_ACTIVE_RUN, NO_RUN).takeIf { it != NO_RUN }

    fun setActiveRun(id: Long) {
        prefs.edit().putLong(KEY_ACTIVE_RUN, id).apply()
    }

    fun clearActiveRun() {
        prefs.edit().remove(KEY_ACTIVE_RUN).apply()
    }

    private companion object {
        const val PREFS = "gpslog"
        const val KEY_ACCURACY = "filter_accuracy_m"
        const val KEY_DISTANCE = "filter_distance_m"
        const val KEY_TIME = "filter_time_s"
        const val KEY_ACTIVE_RUN = "active_run_id"
        const val NO_RUN = -1L
    }
}
