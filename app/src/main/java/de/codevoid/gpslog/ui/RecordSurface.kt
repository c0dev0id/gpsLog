package de.codevoid.gpslog.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * a fix" reads at arm's length; six tabular-numeral tiles carry the details and stay mounted while
 * idle (every value is a dash) so nothing jumps on Start. This is the only surface that collects
 * [MainViewModel.loggingState]; its children take Strings and Booleans, so a 2 s tick recomposes
 * only the tiles whose text changed.
 */
@Composable
internal fun RecordSurface(vm: MainViewModel, onOpenSettings: () -> Unit) {
    val state by vm.loggingState.collectAsStateWithLifecycle()
    val preciseLocation by vm.preciseLocation.collectAsStateWithLifecycle()
    val recordingSource by vm.recordingSource.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val f = remember { Formats(Locale.getDefault(), ZoneId.systemDefault()) }

    val external = recordingSource.isNotEmpty()
    val status = recordStatus(state, external, preciseLocation)
    // Receiver-dependent values mean nothing unless the source is actually delivering.
    val live = state.isLogging && !state.isPaused && state.gpsEnabled && state.gnssRunning
    val accuracy = state.accuracyMeters

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(Gutter),
        ) {
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
                word = status.label,
                wordColor = statusColor(status),
                subtitle = recordSubtitle(status, external, state.startTimeMillis, state.lastFixTimeMillis, f),
                modifier = Modifier.padding(top = 8.dp),
            )
            RecordControls(
                isLogging = state.isLogging,
                isPaused = state.isPaused,
                canStart = preciseLocation,
                onStart = vm::start,
                onStop = vm::stop,
                onPause = vm::pause,
                onUnpause = vm::unpause,
                modifier = Modifier.padding(top = 24.dp),
            )
            StatsGrid(
                satellitesUsed = if (live) state.satellitesUsedInFix.toString() else DASH,
                satellitesVisible = state.satellitesVisible.toString(),
                accuracy = if (live) f.decimal(accuracy) else DASH,
                accuracyOverLimit = live && accuracy != null && accuracy > filters.accuracyMeters,
                points = if (state.isLogging) f.count(state.pointCount) else DASH,
                rate = if (live) f.decimal(state.updateRateHz) else DASH,
                speed = if (live) f.decimal(state.speedMetersPerSecond) else DASH,
                gpsTime = if (state.isLogging) f.clock(state.lastFixTimeMillis) else DASH,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
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

/** Three fixed rows of two tiles — no flow layout, so a tick costs no measurement pass. */
@Composable
private fun StatsGrid(
    satellitesUsed: String,
    satellitesVisible: String,
    accuracy: String,
    accuracyOverLimit: Boolean,
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
            StatTile(
                label = if (accuracyOverLimit) "Accuracy · over limit" else "Accuracy",
                value = accuracy,
                unit = "m",
                alert = accuracyOverLimit,
                modifier = Modifier.weight(1f),
            )
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

/**
 * One label over one numeral. TalkBack reads the tile as a single node ("Rate, 1.0 Hz"). The label
 * may take a second line only in the alert state, so the idle grid never grows.
 */
@Composable
private fun StatTile(
    label: String,
    value: String,
    unit: String?,
    modifier: Modifier = Modifier,
    alert: Boolean = false,
) {
    Column(modifier = modifier.semantics(mergeDescendants = true) {}) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (alert) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
        ValueWithUnit(
            value = value,
            unit = unit,
            valueStyle = MaterialTheme.typography.headlineSmall,
            valueColor = when {
                value == DASH -> MaterialTheme.colorScheme.outline
                alert -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
