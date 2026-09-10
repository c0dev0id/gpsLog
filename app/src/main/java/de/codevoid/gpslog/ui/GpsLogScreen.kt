package de.codevoid.gpslog.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.codevoid.gpslog.data.FilterSettings
import de.codevoid.gpslog.data.RunInfo
import de.codevoid.gpslog.service.LoggingState
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

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

    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("gpsLog") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { RecordTabLabel(isLogging = state.isLogging) },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("Runs") },
                )
            }
            when (tab) {
                0 -> RecordTab(
                    modifier = Modifier.weight(1f),
                    state = state,
                    preciseLocation = preciseLocation,
                    onStart = vm::start,
                    onStop = vm::stop,
                    onOpenSettings = onOpenSettings,
                )
                else -> RunsTab(
                    modifier = Modifier.weight(1f),
                    runs = runs,
                    selected = selected,
                    activeRunId = if (state.isLogging) state.runId else null,
                    filters = filters,
                    onToggleSelect = vm::toggleSelect,
                    onDelete = vm::delete,
                    onAccuracy = vm::setAccuracyMeters,
                    onDistance = vm::setDistanceMeters,
                    onTime = vm::setTimeSeconds,
                    onSave = onSave,
                    onShare = onShare,
                )
            }
        }
    }
}

@Composable
private fun RecordTabLabel(isLogging: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Record")
        if (isLogging) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape)
            )
        }
    }
}

@Composable
private fun RecordTab(
    modifier: Modifier,
    state: LoggingState,
    preciseLocation: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!preciseLocation) {
            PreciseLocationBanner(onOpenSettings)
        }
        StartStopButton(
            isLogging = state.isLogging,
            enabled = preciseLocation || state.isLogging,
            onStart = onStart,
            onStop = onStop,
        )
        if (state.isLogging) {
            LiveStats(state)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunsTab(
    modifier: Modifier,
    runs: List<RunInfo>,
    selected: Set<Long>,
    activeRunId: Long?,
    filters: FilterSettings,
    onToggleSelect: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onAccuracy: (Int) -> Unit,
    onDistance: (Float) -> Unit,
    onTime: (Int) -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        if (runs.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No runs yet — start recording on the Record tab.",
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(runs, key = { it.id }) { run ->
                    RunRow(
                        run = run,
                        selected = run.id in selected,
                        isActive = run.id == activeRunId,
                        onToggleSelect = { onToggleSelect(run.id) },
                        onDelete = { onDelete(run.id) },
                    )
                }
            }
        }

        if (selected.isNotEmpty()) {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = { showSheet = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text("Export (${selected.size})")
                }
            }
        }
    }

    if (showSheet) {
        ExportSheet(
            count = selected.size,
            filters = filters,
            onAccuracy = onAccuracy,
            onDistance = onDistance,
            onTime = onTime,
            onSave = { showSheet = false; onSave() },
            onShare = { showSheet = false; onShare() },
            onDismiss = { showSheet = false },
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheet(
    count: Int,
    filters: FilterSettings,
    onAccuracy: (Int) -> Unit,
    onDistance: (Float) -> Unit,
    onTime: (Int) -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Export $count ${if (count == 1) "run" else "runs"}", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f)) { Text("Save file") }
                Button(onClick = onShare, modifier = Modifier.weight(1f)) { Text("Share") }
            }
        }
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

/**
 * Selection is an explicit leading checkbox; delete is a two-step swipe-to-reveal: dragging the
 * card left uncovers a Delete button that must be tapped to confirm. A plain [draggable] +
 * [Animatable] is used (not AnchoredDraggable) because those APIs are stable across Compose
 * releases and this project cannot be built locally to catch breakage. The active run cannot be
 * revealed, so it cannot be deleted while logging.
 */
@Composable
private fun RunRow(
    run: RunInfo,
    selected: Boolean,
    isActive: Boolean,
    onToggleSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val revealPx = with(LocalDensity.current) { 88.dp.toPx() }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.CenterEnd,
        ) {
            TextButton(
                onClick = {
                    onDelete()
                    scope.launch { offsetX.snapTo(0f) }
                },
                modifier = Modifier.width(88.dp),
            ) {
                Text("Delete", color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        Card(
            shape = shape,
            colors = if (selected) {
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            } else {
                CardDefaults.cardColors()
            },
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .draggable(
                    enabled = !isActive,
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            offsetX.snapTo((offsetX.value + delta).coerceIn(-revealPx, 0f))
                        }
                    },
                    onDragStopped = {
                        val target = if (offsetX.value < -revealPx / 2f) -revealPx else 0f
                        offsetX.animateTo(target)
                    },
                )
                .pointerInput(Unit) {
                    detectTapGestures {
                        if (offsetX.value != 0f) scope.launch { offsetX.animateTo(0f) }
                    }
                },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
                Column(modifier = Modifier.weight(1f)) {
                    Text(dateTimeFmt.format(Instant.ofEpochMilli(run.startTimeMillis)))
                    Text(
                        "→ ${dateTimeFmt.format(Instant.ofEpochMilli(run.endTimeMillis))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Text("${run.pointCount} pts")
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
