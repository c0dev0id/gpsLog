package de.codevoid.gpslog.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.ZoneId
import java.util.Locale

/**
 * Start/stop/pause and the live state. A status word leads, so "is it recording and does it have
 * a fix" reads at arm's length; while logging, six tabular-numeral tiles carry the details, and
 * while idle the same slot holds the latest run with a Share glyph — the on-ramp to its Export
 * page, so a finished run is one tap from being shared. The controls sit above the slot in both
 * layouts, so swapping its content moves nothing under the finger. In a wide window the status and
 * controls sit beside the slot instead of above it. This is the only surface that collects
 * [MainViewModel.loggingState]; its children take Strings and Booleans, so a 2 s tick recomposes
 * only the tiles whose text changed.
 */
@Composable
internal fun RecordSurface(vm: MainViewModel, wide: Boolean, onOpenSettings: () -> Unit) {
    val state by vm.loggingState.collectAsStateWithLifecycle()
    val preciseLocation by vm.preciseLocation.collectAsStateWithLifecycle()
    val recordingSource by vm.recordingSource.collectAsStateWithLifecycle()
    // Emits only on a refresh (resume, delete/merge, a run starting or ending), never on a fix.
    val runs by vm.runs.collectAsStateWithLifecycle()
    val f = remember { Formats(Locale.getDefault(), ZoneId.systemDefault()) }

    val external = recordingSource.isNotEmpty()
    val status = recordStatus(state, external, preciseLocation)
    // Receiver-dependent values mean nothing unless the source is actually delivering.
    val live = state.isLogging && !state.isPaused && state.gpsEnabled && state.gnssRunning

    val primary: @Composable (Modifier) -> Unit = { modifier ->
        RecordPrimary(
            preciseLocation = preciseLocation,
            onOpenSettings = onOpenSettings,
            word = status.label,
            wordColor = statusColor(status),
            subtitle = recordSubtitle(status, external, state.startTimeMillis, state.lastFixTimeMillis, f),
            isLogging = state.isLogging,
            isPaused = state.isPaused,
            onStart = vm::start,
            onStop = vm::stop,
            onPause = vm::pause,
            onUnpause = vm::unpause,
            modifier = modifier,
        )
    }
    // The list is newest first, so this is the run that just ended (or the last one recorded).
    val latest = runs.firstOrNull()
    val hasSecondary = state.isLogging || latest != null
    val secondary: @Composable (Modifier) -> Unit = { modifier ->
        if (state.isLogging) {
            StatsGrid(
                satellitesUsed = if (live) state.satellitesUsedInFix.toString() else DASH,
                satellitesVisible = state.satellitesVisible.toString(),
                accuracy = if (live) f.decimal(state.accuracyMeters) else DASH,
                points = f.count(state.pointCount),
                rate = if (live) f.decimal(state.updateRateHz) else DASH,
                speed = if (live) f.decimal(state.speedMetersPerSecond) else DASH,
                gpsTime = f.clock(state.lastFixTimeMillis),
                modifier = modifier,
            )
        } else if (latest != null) {
            LatestRunRow(
                title = f.runTitle(latest.startTimeMillis),
                subtitle = f.runSubtitle(latest.endTimeMillis - latest.startTimeMillis, latest.pointCount),
                onExport = { vm.openExport(setOf(latest.id)) },
                modifier = modifier,
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        if (wide) {
            Row(
                modifier = Modifier
                    .widthIn(max = WideContentMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(Gutter),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                primary(Modifier.weight(1f))
                if (hasSecondary) secondary(Modifier.weight(1f)) else Spacer(modifier = Modifier.weight(1f))
            }
        } else {
            Column(
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(Gutter),
            ) {
                primary(Modifier)
                if (hasSecondary) secondary(Modifier.padding(top = 16.dp))
            }
        }
    }
}

/** The most recent run, in the tile slot while idle; a tap opens its Export page. */
@Composable
private fun LatestRunRow(title: String, subtitle: String, onExport: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        headlineContent = { Text(title) },
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .clickable(onClickLabel = "Export", onClick = onExport),
        overlineContent = { Text("Latest run") },
        supportingContent = { Text(subtitle) },
        trailingContent = { Icon(Icons.Outlined.Share, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}

/** The banner, the status hero and the controls — the half of the surface that is not the tiles. */
@Composable
private fun RecordPrimary(
    preciseLocation: Boolean,
    onOpenSettings: () -> Unit,
    word: String,
    wordColor: Color,
    subtitle: String,
    isLogging: Boolean,
    isPaused: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onUnpause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        AnimatedVisibility(
            visible = !preciseLocation,
            enter = RevealEnter,
            exit = RevealExit,
        ) {
            PreciseLocationBanner(
                onOpenSettings = onOpenSettings,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
        StateHero(
            word = word,
            wordColor = wordColor,
            subtitle = subtitle,
            modifier = Modifier.padding(top = 8.dp),
        )
        RecordControls(
            isLogging = isLogging,
            isPaused = isPaused,
            canStart = preciseLocation,
            onStart = onStart,
            onStop = onStop,
            onPause = onPause,
            onUnpause = onUnpause,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}

@Composable
private fun statusColor(status: RecordStatus): Color = when (status) {
    RecordStatus.Fix -> MaterialTheme.colorScheme.primary
    RecordStatus.Idle, RecordStatus.Searching -> MaterialTheme.colorScheme.onSurface
    RecordStatus.Paused -> MaterialTheme.colorScheme.onSurfaceVariant
    RecordStatus.Unavailable, RecordStatus.Disabled, RecordStatus.ReceiverOff -> MaterialTheme.colorScheme.error
}

/**
 * Without precise location Android silently degrades GPS_PROVIDER to fuzzed fixes every 10 min
 * and never delivers GnssStatus, so logging is pointless; say so instead of starting a dead run.
 */
@Composable
private fun PreciseLocationBanner(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(modifier = Modifier.padding(Gutter)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Warning, contentDescription = null, modifier = Modifier.size(24.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Precise location is off", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "gpsLog cannot record usable fixes without it. Allow \"Precise\" location " +
                            "for gpsLog in the system settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            TextButton(
                onClick = onOpenSettings,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 4.dp),
            ) {
                Text("Open app settings")
            }
        }
    }
}

/** The status word and its one-line explanation. A plain text swap on change, no crossfade. */
@Composable
private fun StateHero(word: String, wordColor: Color, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            word,
            style = MaterialTheme.typography.displaySmall,
            color = wordColor,
            modifier = Modifier.semantics { contentDescription = "GPS status: $word" },
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Stop on the left, Pause/Resume under the right thumb. Exactly one filled primary button is on
 * screen at a time and it is always the "make it record" verb; Stop wears the container tone
 * because a mis-tap loses nothing (Merge rejoins the runs). Takes primitives so it skips on ticks.
 */
@Composable
private fun RecordControls(
    isLogging: Boolean,
    isPaused: Boolean,
    canStart: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onUnpause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val buttonModifier = Modifier
            .weight(1f)
            .height(ControlHeight)
        if (!isLogging) {
            Button(onClick = onStart, enabled = canStart, modifier = buttonModifier) {
                ControlLabel("Start")
            }
        } else {
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = buttonModifier,
            ) {
                ControlLabel("Stop")
            }
            if (isPaused) {
                Button(onClick = onUnpause, modifier = buttonModifier) { ControlLabel("Resume") }
            } else {
                FilledTonalButton(onClick = onPause, modifier = buttonModifier) { ControlLabel("Pause") }
            }
        }
    }
}

/** Three fixed rows of two tiles, shown only while logging — no flow layout, so a tick costs no measurement pass. */
@Composable
private fun StatsGrid(
    satellitesUsed: String,
    satellitesVisible: String,
    accuracy: String,
    points: String,
    rate: String,
    speed: String,
    gpsTime: String,
    modifier: Modifier = Modifier,
) {
    Panel(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatTile(
                label = "Satellites in use",
                value = satellitesUsed,
                unit = "of $satellitesVisible",
                modifier = Modifier.weight(1f),
            )
            StatTile(label = "Accuracy", value = accuracy, unit = "m", modifier = Modifier.weight(1f))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatTile(label = "Points", value = points, unit = null, modifier = Modifier.weight(1f))
            StatTile(label = "Rate", value = rate, unit = "Hz", modifier = Modifier.weight(1f))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatTile(label = "Speed", value = speed, unit = "m/s", modifier = Modifier.weight(1f))
            StatTile(label = "GPS time", value = gpsTime, unit = null, modifier = Modifier.weight(1f))
        }
    }
}

/** One label over one numeral. TalkBack reads the tile as a single node ("Rate, 1.0 Hz"). */
@Composable
private fun StatTile(label: String, value: String, unit: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier.semantics(mergeDescendants = true) {}) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ValueWithUnit(
            value = value,
            unit = unit,
            valueStyle = MaterialTheme.typography.headlineSmall,
            valueColor = if (value == DASH) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
