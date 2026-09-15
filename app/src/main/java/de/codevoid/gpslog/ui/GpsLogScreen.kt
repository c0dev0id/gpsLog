package de.codevoid.gpslog.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File

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
