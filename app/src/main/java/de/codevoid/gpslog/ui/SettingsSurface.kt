package de.codevoid.gpslog.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.time.ZoneId
import java.util.Locale

/**
 * Preferences as list rows in three groups: the recording source (an inline radio list, so the
 * choice and its alternatives are visible at once), diagnostics (the debug NMEA log and its share
 * row) and About (the version and the nightly updater).
 */
@Composable
internal fun SettingsSurface(vm: MainViewModel, onShareDebugLog: () -> Unit, onInstall: (File) -> Unit) {
    val recordingSource by vm.recordingSource.collectAsStateWithLifecycle()
    val debugLogging by vm.debugLogging.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val latestDebugLog by vm.latestDebugLog.collectAsStateWithLifecycle()
    val f = remember { Formats(Locale.getDefault(), ZoneId.systemDefault()) }

    Column(modifier = Modifier.fillMaxSize()) {
        SurfaceHeader("Settings")
        Column(
            modifier = Modifier
                .weight(1f)
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally)
                .verticalScroll(rememberScrollState())
                .padding(bottom = Gutter),
        ) {
            SectionHeader("Recording", modifier = Modifier.padding(horizontal = Gutter))
            SourceGroup(recordingSource = recordingSource, onSelectSource = vm::setRecordingSource)
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            SectionHeader("Diagnostics", modifier = Modifier.padding(horizontal = Gutter))
            DebugLogRow(enabled = debugLogging, onSetEnabled = vm::setDebugLogging)
            ShareLogRow(
                captured = latestDebugLog?.let { f.runTitle(it.capturedMillis) },
                onShare = onShareDebugLog,
            )
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            SectionHeader("About", modifier = Modifier.padding(horizontal = Gutter))
            ListItem(
                headlineContent = { Text("Version") },
                supportingContent = { Text(vm.installedVersion) },
            )
            UpdateRow(
                state = update,
                f = f,
                onCheck = vm::checkForUpdate,
                onInstall = { vm.downloadAndInstall(onInstall) },
            )
        }
    }
}

/**
 * Chooses the run's fix source: the internal GPS (`""`) or a paired classic-Bluetooth GNSS
 * receiver. Reading the paired-device list and its names needs `BLUETOOTH_CONNECT`, requested
 * lazily from an "Allow" row so an internal-only user is never prompted. Bluetooth Class-of-Device
 * carries no "GNSS" flag, so every classic/dual paired device is listed and the user picks.
 */
@SuppressLint("MissingPermission")
@Composable
private fun SourceGroup(recordingSource: String, onSelectSource: (String) -> Unit) {
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
    val external = recordingSource.isNotEmpty()

    Column(modifier = Modifier.selectableGroup()) {
        SourceOption(
            name = "Internal GPS",
            detail = "The phone's own chipset",
            selected = !external,
            onSelect = { onSelectSource("") },
        )
        if (!granted) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Gutter, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Allow Bluetooth access to list paired receivers.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
                    Text("Allow")
                }
            }
        }
        devices.forEach { (name, mac) ->
            SourceOption(
                name = name,
                detail = mac,
                selected = mac == recordingSource,
                onSelect = { onSelectSource(mac) },
            )
        }
        // A chosen receiver that is not in the list (unpaired since, or the list needs the grant)
        // still shows as the selection, by its address.
        if (external && devices.none { it.second == recordingSource }) {
            SourceOption(
                name = recordingSource,
                detail = if (granted) "No longer paired" else "Paired receiver",
                selected = true,
                onSelect = {},
            )
        }
    }
    if (external) {
        Text(
            "Records from the external receiver; the phone's own GPS stays free for navigation.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp),
        )
    }
}

/** One radio row; the whole row is the target and the button only mirrors it. */
@Composable
private fun SourceOption(name: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
    ListItem(
        headlineContent = { Text(name) },
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        supportingContent = { Text(detail) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
    )
}

/**
 * Diagnostics: when enabled, a run captures a text log — the raw NMEA plus, for the internal GPS,
 * the chipset model and capabilities — so the receiver's constellations, delivery rate and HDOP
 * can be inspected. Works for both sources (the internal chipset's NMEA comes via `addNmeaListener`).
 */
@Composable
private fun DebugLogRow(enabled: Boolean, onSetEnabled: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text("Debug NMEA log") },
        modifier = Modifier.toggleable(value = enabled, role = Role.Switch, onValueChange = onSetEnabled),
        supportingContent = {
            Text(
                "Records the raw NMEA to a text file you can share — plus the chipset model and " +
                    "capabilities when the internal GPS is selected."
            )
        },
        trailingContent = { Switch(checked = enabled, onCheckedChange = null) },
    )
}

/**
 * Shares the newest captured log; disabled while there is none, with the reason left fully legible.
 * Rows in this group carry no leading slot so their headlines share one left edge.
 */
@Composable
private fun ShareLogRow(captured: String?, onShare: () -> Unit) {
    val hasLog = captured != null
    val disabledAlpha = 0.38f
    ListItem(
        headlineContent = { Text("Share latest log") },
        modifier = Modifier.clickable(enabled = hasLog, onClick = onShare),
        supportingContent = { Text(if (captured != null) "Captured $captured" else "No log captured yet") },
        trailingContent = { Icon(Icons.Filled.Share, contentDescription = null) },
        colors = if (hasLog) {
            ListItemDefaults.colors()
        } else {
            ListItemDefaults.colors(
                headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha),
                trailingIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha),
            )
        },
    )
}

/**
 * One row whose slots follow the updater state; the state glyph sits in the trailing slot so the
 * headline keeps the group's left edge, and progress only animates while a request is in flight.
 */
@Composable
private fun UpdateRow(state: UpdateState, f: Formats, onCheck: () -> Unit, onInstall: () -> Unit) {
    when (state) {
        UpdateState.Idle -> ListItem(
            headlineContent = { Text("Check for updates") },
            modifier = Modifier.clickable(onClick = onCheck),
            supportingContent = { Text("Compares with the latest development build on GitHub") },
            trailingContent = { Icon(Icons.Filled.Refresh, contentDescription = null) },
        )
        UpdateState.Checking -> ListItem(
            headlineContent = { Text("Checking…") },
            supportingContent = { Text("Compares with the latest development build on GitHub") },
            trailingContent = { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) },
        )
        UpdateState.UpToDate -> ListItem(
            headlineContent = { Text("Up to date") },
            modifier = Modifier.clickable(onClick = onCheck),
            supportingContent = { Text("The installed build matches the latest development build") },
            trailingContent = {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
        )
        is UpdateState.Available -> ListItem(
            headlineContent = { Text("Update available") },
            supportingContent = { Text("dev-${state.nightly.sha}") },
            trailingContent = { Button(onClick = onInstall) { Text("Install") } },
        )
        is UpdateState.Downloading -> ListItem(
            headlineContent = { Text("Downloading… ${f.percent(state.progress)}") },
            supportingContent = {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            },
        )
        is UpdateState.Error -> ListItem(
            headlineContent = { Text("Check for updates") },
            modifier = Modifier.clickable(onClick = onCheck),
            supportingContent = { Text(state.message, color = MaterialTheme.colorScheme.error) },
            trailingContent = {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
        )
    }
}
