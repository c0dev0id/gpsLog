package de.codevoid.gpslog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.codevoid.gpslog.data.FilterSettings
import de.codevoid.gpslog.data.RunInfo
import de.codevoid.gpslog.service.LoggingState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
private val dateTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GpsLogScreen(
    vm: MainViewModel,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by vm.loggingState.collectAsStateWithLifecycle()
    val preciseLocation by vm.preciseLocation.collectAsStateWithLifecycle()
    val runs by vm.runs.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("gpsLog") }) }
    ) { padding ->
        // One lazy list for the whole screen so the controls scroll with the runs
        // (in landscape the list would otherwise start below the visible area).
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!preciseLocation) {
                item(key = "precise") { PreciseLocationBanner(onOpenSettings) }
            }
            item(key = "start") {
                StartStopButton(
                    isLogging = state.isLogging,
                    enabled = preciseLocation || state.isLogging,
                    onStart = vm::start,
                    onStop = vm::stop,
                )
            }
            if (state.isLogging) {
                item(key = "stats") { LiveStats(state) }
            }
            item(key = "filters") {
                FilterControls(
                    filters = filters,
                    onAccuracy = vm::setAccuracyMeters,
                    onDistance = vm::setDistanceMeters,
                    onTime = vm::setTimeSeconds,
                )
            }
            item(key = "export") {
                ExportControls(
                    selectedCount = selected.size,
                    onSave = onSave,
                    onShare = onShare,
                )
            }
            item(key = "runs-header") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    Text("Runs", style = MaterialTheme.typography.titleMedium)
                }
            }
            items(runs, key = { it.id }) { run ->
                RunRow(
                    run = run,
                    selected = run.id in selected,
                    isActive = run.id == state.runId && state.isLogging,
                    onToggleSelect = { vm.toggleSelect(run.id) },
                    onDelete = { vm.delete(run.id) },
                )
            }
        }
    }
}

/**
 * Without precise location Android silently degrades GPS_PROVIDER to fuzzed fixes every 10 min
 * and never delivers GnssStatus, so logging is pointless; say so instead of starting a dead run.
 */
@Composable
private fun PreciseLocationBanner(onOpenSettings: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Precise location is required. Allow \"Precise\" location for gpsLog in the system settings.",
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            OutlinedButton(onClick = onOpenSettings) { Text("Open app settings") }
        }
    }
}

@Composable
private fun StartStopButton(isLogging: Boolean, enabled: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Button(
        onClick = { if (isLogging) onStop() else onStart() },
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth(),
        colors = if (isLogging) {
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(if (isLogging) "Stop" else "Start", style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun LiveStats(state: LoggingState) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Stat("Started", state.startTimeMillis?.let { timeFmt.format(Instant.ofEpochMilli(it)) } ?: "—")
            Stat(
                "GPS",
                when {
                    !state.gpsEnabled -> "Disabled"
                    !state.gnssRunning -> "Receiver off"
                    state.hasFix -> "Fix"
                    else -> "Searching"
                },
                highlight = state.gpsEnabled && state.hasFix,
            )
            Stat("Satellites", "${state.satellitesUsedInFix} used / ${state.satellitesVisible} visible")
            Stat("Points", state.pointCount.toString())
            Stat("Rate", String.format(Locale.US, "%.1f Hz", state.updateRateHz))
            Stat("GPS time", state.lastFixTimeMillis?.let { timeFmt.format(Instant.ofEpochMilli(it)) } ?: "—")
            Stat("Speed", state.speedMetersPerSecond?.let { String.format(Locale.US, "%.1f m/s", it) } ?: "—")
            Stat("Accuracy", state.accuracyMeters?.let { String.format(Locale.US, "%.1f m", it) } ?: "—")
        }
    }
}

/** [highlight] null keeps the default colour; true/false colour the value as good/bad. */
@Composable
private fun Stat(label: String, value: String, highlight: Boolean? = null) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.outline)
        Text(
            value,
            color = when (highlight) {
                null -> Color.Unspecified
                true -> MaterialTheme.colorScheme.primary
                false -> MaterialTheme.colorScheme.error
            },
        )
    }
}

@Composable
private fun FilterControls(
    filters: FilterSettings,
    onAccuracy: (Int) -> Unit,
    onDistance: (Float) -> Unit,
    onTime: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IntFilterField(
            label = "Accuracy m",
            value = filters.accuracyMeters,
            range = 1..999,
            onChange = onAccuracy,
            modifier = Modifier.weight(1f),
        )
        FloatFilterField(
            label = "Distance m",
            value = filters.distanceMeters,
            onChange = onDistance,
            modifier = Modifier.weight(1f),
        )
        IntFilterField(
            label = "Time s",
            value = filters.timeSeconds,
            range = 0..999,
            onChange = onTime,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun IntFilterField(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            text = s
            s.toIntOrNull()?.let { if (it in range) onChange(it) }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun FloatFilterField(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(if (value == 0f) "0" else String.format(Locale.US, "%.1f", value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            text = s
            s.toFloatOrNull()?.let { if (it in 0f..999.9f) onChange(Math.round(it * 10f) / 10f) }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
private fun ExportControls(selectedCount: Int, onSave: () -> Unit, onShare: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = onSave,
            enabled = selectedCount > 0,
            modifier = Modifier.weight(1f),
        ) { Text("Save ($selectedCount)") }
        OutlinedButton(
            onClick = onShare,
            enabled = selectedCount > 0,
            modifier = Modifier.weight(1f),
        ) { Text("Share ($selectedCount)") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunRow(
    run: RunInfo,
    selected: Boolean,
    isActive: Boolean,
    onToggleSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onToggleSelect()
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onDelete()
                    true
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromEndToStart = !isActive,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val color = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primaryContainer
                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                SwipeToDismissBoxValue.Settled -> Color.Transparent
            }
            val alignment = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                else -> Alignment.CenterEnd
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = alignment,
            ) {
                when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> Text("Select")
                    SwipeToDismissBoxValue.EndToStart -> Text("Delete")
                    SwipeToDismissBoxValue.Settled -> {}
                }
            }
        },
    ) {
        Card(
            colors = if (selected) {
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            } else {
                CardDefaults.cardColors()
            },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(dateTimeFmt.format(Instant.ofEpochMilli(run.startTimeMillis)))
                    Text(
                        "→ ${dateTimeFmt.format(Instant.ofEpochMilli(run.endTimeMillis))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Text("${run.pointCount} pts")
                if (selected) {
                    Text(
                        "✓",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                if (isActive) {
                    Box(
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(10.dp)
                            .background(MaterialTheme.colorScheme.error, CircleShape)
                    )
                }
            }
        }
    }
}
