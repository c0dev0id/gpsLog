package de.codevoid.gpslog.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File

private const val TAB_RECORD = 0
private const val TAB_RUNS = 1
private const val TAB_EXPORT = 2
private const val TAB_SETTINGS = 3

/** One top-level destination; the icon pair follows Material's filled-when-selected convention. */
private class Destination(val index: Int, val label: String, val selectedIcon: ImageVector, val icon: ImageVector)

private val destinations = listOf(
    Destination(TAB_RECORD, "Record", Icons.Filled.LocationOn, Icons.Outlined.LocationOn),
    Destination(TAB_RUNS, "Runs", Icons.AutoMirrored.Filled.List, Icons.AutoMirrored.Filled.List),
    Destination(TAB_EXPORT, "Export", Icons.Filled.Share, Icons.Outlined.Share),
    Destination(TAB_SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
)

/**
 * The one screen: four top-level surfaces behind a navigation bar, or a navigation rail on the
 * side once the window is [WideWindowMinWidth] or wider (a phone in landscape, a tablet), which
 * also gives back the height the bar took. The shell collects only what the navigation needs (the
 * active run and the selection size); every surface collects its own flows, so a logging-state
 * tick recomposes the Record surface alone.
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
    val wide = LocalWindowInfo.current.containerDpSize.width >= WideWindowMinWidth
    val recording = active != null
    val paused = active?.paused == true
    val exportCount = selected.size

    // Export is only reachable with a selection; a restored index lands on Runs when it is empty.
    LaunchedEffect(selected.isEmpty()) {
        if (selected.isEmpty() && tab == TAB_EXPORT) tab = TAB_RUNS
    }

    Scaffold(
        bottomBar = {
            if (!wide) {
                NavigationBar {
                    destinations.forEach { d ->
                        NavigationBarItem(
                            selected = tab == d.index,
                            onClick = { tab = d.index },
                            enabled = d.index != TAB_EXPORT || exportCount > 0,
                            icon = { DestinationIcon(d, tab == d.index, recording, paused, exportCount) },
                            label = { Text(d.label, maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // Consuming the padded insets lets a surface's imePadding() measure from the bar, not the
        // window edge, so a pinned button row lands on the keyboard instead of a bar's height above it.
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            if (wide) {
                NavigationRail {
                    destinations.forEach { d ->
                        NavigationRailItem(
                            selected = tab == d.index,
                            onClick = { tab = d.index },
                            enabled = d.index != TAB_EXPORT || exportCount > 0,
                            icon = { DestinationIcon(d, tab == d.index, recording, paused, exportCount) },
                            label = { Text(d.label, maxLines = 1) },
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                when (tab) {
                    TAB_RECORD -> RecordSurface(vm = vm, wide = wide, onOpenSettings = onOpenSettings)
                    TAB_RUNS -> RunsSurface(vm = vm, onGoToRecord = { tab = TAB_RECORD })
                    TAB_EXPORT -> ExportSurface(vm = vm, wide = wide, onShare = onShare)
                    else -> SettingsSurface(vm = vm, onShareDebugLog = onShareDebugLog, onInstall = onInstall)
                }
            }
        }
    }
}

/**
 * A destination's icon with its state badge: a dot on Record while a run is active (error while
 * recording, the neutral outline while paused — dynamic tertiary can land on red for some
 * wallpapers) and the selection count on Export.
 */
@Composable
private fun DestinationIcon(
    destination: Destination,
    selected: Boolean,
    recording: Boolean,
    paused: Boolean,
    exportCount: Int,
) {
    val icon: @Composable () -> Unit = {
        Icon(if (selected) destination.selectedIcon else destination.icon, contentDescription = null)
    }
    when (destination.index) {
        TAB_RECORD -> {
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
                                MaterialTheme.colorScheme.outline
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                },
                modifier = Modifier.semantics { contentDescription = description },
            ) {
                icon()
            }
        }
        TAB_EXPORT -> BadgedBox(badge = { if (exportCount > 0) Badge { Text("$exportCount") } }) { icon() }
        else -> icon()
    }
}
