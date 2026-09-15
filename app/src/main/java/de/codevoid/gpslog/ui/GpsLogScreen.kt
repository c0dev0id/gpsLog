package de.codevoid.gpslog.ui

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.codevoid.gpslog.data.FilterSettings
import de.codevoid.gpslog.data.RunInfo
import de.codevoid.gpslog.service.LoggingState
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val dateTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private const val TAB_RECORD = 0
private const val TAB_RUNS = 1
private const val TAB_EXPORT = 2
private const val TAB_SETTINGS = 3

/**
 * The one screen: four top-level surfaces behind a bottom navigation bar. The shell collects only
 * what the bar needs (the active run and the selection size); every surface collects its own flows,
 * so a logging-state tick recomposes the Record surface alone.
 */
@Composable
fun GpsLogScreen(
    vm: MainViewModel,
    onShare: () -> Unit,
    onShareDebugLog: () -> Unit,
    onOpenSettings: () -> Unit,
    onInstall: (File) -> Unit,
) {
    val active by vm.activeRun.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(TAB_RECORD) }

    // Export is only reachable with a selection; a restored index lands on Runs when it is empty.
    LaunchedEffect(selected.isEmpty()) {
        if (selected.isEmpty() && tab == TAB_EXPORT) tab = TAB_RUNS
    }

    Scaffold(
        bottomBar = {
            GpsLogNavBar(
                tab = tab,
                onTab = { tab = it },
                recording = active != null,
                paused = active?.paused == true,
                exportCount = selected.size,
            )
        },
    ) { padding ->
        // Consuming the padded insets lets a surface's imePadding() measure from the bar, not the
        // window edge, so a pinned button row lands on the keyboard instead of a bar's height above it.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            when (tab) {
                TAB_RECORD -> RecordSurface(vm = vm, onOpenSettings = onOpenSettings)
                TAB_RUNS -> RunsSurface(vm = vm, onGoToRecord = { tab = TAB_RECORD })
                TAB_EXPORT -> ExportSurface(vm = vm, onShare = onShare)
                else -> SettingsSurface(vm = vm, onShareDebugLog = onShareDebugLog, onInstall = onInstall)
            }
        }
    }
}

/**
 * Stock navigation bar. The recording state rides on the Record item as a dot badge (error while
 * recording, tertiary while paused) and the selection count on the Export item as a number badge;
 * Export is disabled while nothing is selected.
 */
@Composable
private fun GpsLogNavBar(
    tab: Int,
    onTab: (Int) -> Unit,
    recording: Boolean,
    paused: Boolean,
    exportCount: Int,
) {
    NavigationBar {
        NavigationBarItem(
            selected = tab == TAB_RECORD,
            onClick = { onTab(TAB_RECORD) },
            icon = {
                val description = when {
                    !recording -> "Record"
                    paused -> "Record, paused"
                    else -> "Record, recording"
                }
                BadgedBox(
                    badge = {
                        if (recording) {
                            Badge(
                                containerColor = if (paused) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    },
                    modifier = Modifier.semantics { contentDescription = description },
                ) {
                    Icon(
                        if (tab == TAB_RECORD) Icons.Filled.LocationOn else Icons.Outlined.LocationOn,
                        contentDescription = null,
                    )
                }
            },
            label = { Text("Record", maxLines = 1) },
        )
        NavigationBarItem(
            selected = tab == TAB_RUNS,
            onClick = { onTab(TAB_RUNS) },
            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
            label = { Text("Runs", maxLines = 1) },
        )
        NavigationBarItem(
            selected = tab == TAB_EXPORT,
            onClick = { onTab(TAB_EXPORT) },
            enabled = exportCount > 0,
            icon = {
                BadgedBox(
                    badge = { if (exportCount > 0) Badge { Text("$exportCount") } },
                ) {
                    Icon(
                        if (tab == TAB_EXPORT) Icons.Filled.Share else Icons.Outlined.Share,
                        contentDescription = null,
                    )
                }
            },
            label = { Text("Export", maxLines = 1) },
        )
        NavigationBarItem(
            selected = tab == TAB_SETTINGS,
            onClick = { onTab(TAB_SETTINGS) },
            icon = {
                Icon(
                    if (tab == TAB_SETTINGS) Icons.Filled.Settings else Icons.Outlined.Settings,
                    contentDescription = null,
                )
            },
            label = { Text("Settings", maxLines = 1) },
        )
    }
}

/** Recording-source picker plus the in-app nightly updater; home for future preferences. */
@Composable
internal fun SettingsTab(
    modifier: Modifier,
    recordingSource: String,
    onSelectSource: (String) -> Unit,
    debugLogging: Boolean,
    onSetDebugLogging: (Boolean) -> Unit,
    onShareDebugLog: () -> Unit,
    installedVersion: String,
    updateState: UpdateState,
    onCheckUpdate: () -> Unit,
    onDownloadInstall: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RecordingSourceSection(
            recordingSource = recordingSource,
            onSelectSource = onSelectSource,
        )
        DebugSection(
            debugLogging = debugLogging,
            onSetDebugLogging = onSetDebugLogging,
            onShareDebugLog = onShareDebugLog,
        )
        UpdateSection(
            installedVersion = installedVersion,
            updateState = updateState,
            onCheckUpdate = onCheckUpdate,
            onDownloadInstall = onDownloadInstall,
        )
    }
}

/**
 * Chooses the run's fix source: the internal GPS (`""`) or a paired classic-Bluetooth GNSS
 * receiver. Reading the paired-device list and its names needs `BLUETOOTH_CONNECT`, requested lazily
 * here so an internal-only user is never prompted. Bluetooth Class-of-Device carries no "GNSS" flag,
 * so every classic/dual paired device is listed and the user picks the right one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
private fun RecordingSourceSection(
    recordingSource: String,
    onSelectSource: (String) -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    val adapter = remember { context.getSystemService(BluetoothManager::class.java)?.adapter }
    val devices = remember(granted, adapter) {
        if (granted && adapter != null) {
            adapter.bondedDevices.orEmpty()
                .filter {
                    it.type == BluetoothDevice.DEVICE_TYPE_CLASSIC ||
                        it.type == BluetoothDevice.DEVICE_TYPE_DUAL
                }
                .map { (it.name ?: it.address) to it.address }
                .sortedBy { it.first.lowercase(Locale.getDefault()) }
        } else {
            emptyList()
        }
    }
    val options = listOf("Internal" to "") + devices
    // A previously-selected device that is no longer paired still shows its raw MAC.
    val currentLabel = options.firstOrNull { it.second == recordingSource }?.first ?: recordingSource

    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("GPS device", style = MaterialTheme.typography.titleMedium)

            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                OutlinedTextField(
                    value = currentLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Recording source") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    options.forEach { (label, mac) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                onSelectSource(mac)
                                expanded = false
                            },
                        )
                    }
                }
            }

            if (!granted) {
                Text(
                    "Grant Bluetooth access to record from a paired external receiver.",
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = { launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Grant Bluetooth access")
                }
            } else if (recordingSource.isNotEmpty()) {
                Text(
                    "Records from the external receiver; the phone's own GPS stays free for navigation.",
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun UpdateSection(
    installedVersion: String,
    updateState: UpdateState,
    onCheckUpdate: () -> Unit,
    onDownloadInstall: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Stat("Version", installedVersion)
            when (val s = updateState) {
                is UpdateState.Downloading -> {
                    Text("Downloading… ${(s.progress * 100).roundToInt()}%")
                    LinearProgressIndicator(
                        progress = { s.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is UpdateState.Available -> {
                    Text(
                        "Update available: dev-${s.nightly.sha}",
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Button(onClick = onDownloadInstall, modifier = Modifier.fillMaxWidth()) {
                        Text("Download & install")
                    }
                }
                else -> {
                    OutlinedButton(
                        onClick = onCheckUpdate,
                        enabled = s != UpdateState.Checking,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (s == UpdateState.Checking) "Checking…" else "Check for updates")
                    }
                    when (s) {
                        UpdateState.UpToDate -> Text(
                            "You're on the latest development build.",
                            color = MaterialTheme.colorScheme.outline,
                        )
                        is UpdateState.Error -> Text(
                            s.message,
                            color = MaterialTheme.colorScheme.error,
                        )
                        else -> {}
                    }
                }
            }
        }
    }
}

/**
 * Diagnostics: when enabled, a run captures a text log — the raw NMEA plus, for the internal GPS,
 * the chipset model and capabilities — so the receiver's constellations, delivery rate and HDOP can
 * be inspected. Works for both sources (the internal chipset's NMEA is read via `addNmeaListener`).
 */
@Composable
private fun DebugSection(
    debugLogging: Boolean,
    onSetDebugLogging: (Boolean) -> Unit,
    onShareDebugLog: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Debug NMEA log", style = MaterialTheme.typography.titleMedium)
                Switch(checked = debugLogging, onCheckedChange = onSetDebugLogging)
            }
            Text(
                "Records the raw NMEA to a text file you can share — plus the chipset model and " +
                    "capabilities when the internal GPS is selected.",
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onShareDebugLog, modifier = Modifier.fillMaxWidth()) {
                Text("Share debug log")
            }
        }
    }
}

@Composable
internal fun RunsTab(
    modifier: Modifier,
    runs: List<RunInfo>,
    selected: Set<Long>,
    activeRunId: Long?,
    onToggleSelect: (Long) -> Unit,
    onDeleteSelected: () -> Unit,
    onMergeSelected: () -> Unit,
) {
    // The active run can be selected (for export) but not deleted or merged while it is logging.
    val actionable = selected.count { it != activeRunId }

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
                    )
                }
            }
        }

        if (selected.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onMergeSelected,
                    enabled = actionable >= 2,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Merge ($actionable)")
                }
                Button(
                    onClick = onDeleteSelected,
                    enabled = actionable >= 1,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Delete ($actionable)")
                }
            }
        }
    }
}

/**
 * The selection's export surface: a summary of what is selected, the accuracy/distance/time
 * filters, a live preview of the filtered result, and a single Export action that hands the GPX to
 * the system share sheet (saving to disk is just sharing to a file manager).
 */
@Composable
internal fun ExportTab(
    modifier: Modifier,
    selectedRuns: Int,
    selectedPoints: Long,
    filters: FilterSettings,
    preview: ExportPreview,
    onAccuracy: (Int) -> Unit,
    onDistance: (Float) -> Unit,
    onTime: (Int) -> Unit,
    onShare: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "$selectedRuns ${if (selectedRuns == 1) "run" else "runs"} selected " +
                "($selectedPoints ${if (selectedPoints == 1L) "point" else "points"})",
            style = MaterialTheme.typography.titleMedium,
        )
        Card {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Filters", style = MaterialTheme.typography.titleSmall)
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
            }
        }
        ExportResult(preview)
        Button(
            onClick = onShare,
            enabled = preview is ExportPreview.Ready,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Export")
        }
    }
}

@Composable
private fun ExportResult(preview: ExportPreview) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Result", style = MaterialTheme.typography.titleSmall)
            when (preview) {
                ExportPreview.Empty -> Text("—", color = MaterialTheme.colorScheme.outline)
                ExportPreview.Computing -> Text("Calculating…", color = MaterialTheme.colorScheme.outline)
                is ExportPreview.Ready -> {
                    val reduction = if (preview.totalPoints > 0L) {
                        ((preview.totalPoints - preview.keptPoints) * 100 / preview.totalPoints).toInt()
                    } else {
                        0
                    }
                    Text(
                        "${preview.tracks} ${if (preview.tracks == 1) "track" else "tracks"}, " +
                            "${preview.keptPoints} ${if (preview.keptPoints == 1L) "point" else "points"} " +
                            "($reduction% reduction)",
                    )
                }
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
 * Selection is an explicit checkbox; the whole card is also clickable to toggle it. Deletion and
 * merging act on the multiselection from the buttons below the list, so the row carries no per-row
 * action. The active run still shows its recording dot and cannot be deleted or merged.
 */
@Composable
private fun RunRow(
    run: RunInfo,
    selected: Boolean,
    isActive: Boolean,
    onToggleSelect: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleSelect() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, onCheckedChange = null)
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
