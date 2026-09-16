package de.codevoid.gpslog.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File

/** A phone's bar keeps Material's default glyph; the rail is read from further away, so it grows. */
private val BarIconSize = 24.dp
private val RailIconSize = 28.dp

private const val TAB_RECORD = 0
private const val TAB_RUNS = 1
private const val TAB_SETTINGS = 2

/** One top-level destination; the icon pair follows Material's filled-when-selected convention. */
private class Destination(val index: Int, val label: String, val selectedIcon: ImageVector, val icon: ImageVector)

private val destinations = listOf(
    Destination(TAB_RECORD, "Record", Icons.Filled.LocationOn, Icons.Outlined.LocationOn),
    Destination(TAB_RUNS, "Runs", Icons.AutoMirrored.Filled.List, Icons.AutoMirrored.Filled.List),
    Destination(TAB_SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
)

/**
 * The one screen: three always-reachable destinations behind a navigation bar, or a navigation
 * rail on the side once the window is [WideWindowMinWidth] or wider (a phone in landscape, a
 * tablet). Export is not a destination but a page pushed over whichever destination opened it —
 * it exists exactly while [MainViewModel.exportTarget] is non-empty, and Back, the page's arrow or
 * any navigation item closes it. The shell collects only what the navigation needs (the active
 * run and the export subject); every surface collects its own flows, so a logging-state tick
 * recomposes the Record surface alone.
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
    val exportTarget by vm.exportTarget.collectAsStateWithLifecycle()
    // A saved index from a build with four destinations still lands on Settings via the `else`.
    var tab by rememberSaveable { mutableIntStateOf(TAB_RECORD) }
    val wide = LocalWindowInfo.current.containerDpSize.width >= WideWindowMinWidth
    // Hoisted so the Runs list keeps its scroll position under the Export page pushed over it.
    val runsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val recording = active != null
    val paused = active?.paused == true
    val select: (Int) -> Unit = { index ->
        vm.closeExport()
        tab = index
    }

    Scaffold(
        bottomBar = {
            if (!wide) {
                NavigationBar {
                    destinations.forEach { d ->
                        NavigationBarItem(
                            selected = tab == d.index,
                            onClick = { select(d.index) },
                            icon = { DestinationIcon(d, tab == d.index, recording, paused, BarIconSize) },
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
                            onClick = { select(d.index) },
                            icon = { DestinationIcon(d, tab == d.index, recording, paused, RailIconSize) },
                            label = {
                                Text(d.label, maxLines = 1, style = MaterialTheme.typography.labelLarge)
                            },
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                if (exportTarget.isNotEmpty()) {
                    ExportSurface(vm = vm, wide = wide, onShare = onShare)
                } else {
                    when (tab) {
                        TAB_RECORD -> RecordSurface(vm = vm, wide = wide, onOpenSettings = onOpenSettings)
                        TAB_RUNS -> RunsSurface(vm = vm, listState = runsListState, onGoToRecord = { tab = TAB_RECORD })
                        else -> SettingsSurface(vm = vm, onShareDebugLog = onShareDebugLog, onInstall = onInstall)
                    }
                }
            }
        }
    }
}

/**
 * A destination's icon with its state badge: a dot on Record while a run is active (error while
 * recording, the neutral outline while paused — dynamic tertiary can land on red for some
 * wallpapers).
 */
@Composable
private fun DestinationIcon(
    destination: Destination,
    selected: Boolean,
    recording: Boolean,
    paused: Boolean,
    iconSize: Dp,
) {
    val icon: @Composable () -> Unit = {
        Icon(
            if (selected) destination.selectedIcon else destination.icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
        )
    }
    if (destination.index == TAB_RECORD) {
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
    } else {
        icon()
    }
}
