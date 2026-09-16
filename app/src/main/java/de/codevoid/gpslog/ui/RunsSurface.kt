package de.codevoid.gpslog.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.ZoneId
import java.util.Locale

/**
 * The past runs, newest first, with checkbox multiselect (the whole row toggles). Delete and
 * Merge act on the selection from a tray below the list; the active run may be selected for
 * export but is excluded from both while its file is being written. Collects
 * [MainViewModel.activeRun] rather than the logging state, so point ticks never reach this
 * surface; the active row shows the disk-derived count like every other row.
 */
@Composable
internal fun RunsSurface(vm: MainViewModel, onGoToRecord: () -> Unit) {
    val runs by vm.runs.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val active by vm.activeRun.collectAsStateWithLifecycle()
    val f = remember { Formats(Locale.getDefault(), ZoneId.systemDefault()) }

    val activeId = active?.id
    val activePaused = active?.paused == true
    val actionable = selected.count { it != activeId }
    val summary = remember(runs) { f.runsSummary(runs.size, runs.sumOf { it.pointCount }) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    // The tray keeps its last count while it shrinks away instead of flashing "Delete (0)".
    var lastActionable by remember { mutableIntStateOf(0) }
    LaunchedEffect(actionable) {
        if (actionable > 0) lastActionable = actionable
    }
    val trayCount = if (actionable > 0) actionable else lastActionable

    Column(modifier = Modifier.fillMaxSize()) {
        SurfaceHeader("Runs") {
            if (selected.isEmpty()) {
                // The empty state below already says there are no runs.
                if (runs.isNotEmpty()) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    "${selected.size} selected",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = vm::clearSelection) { Text("Clear") }
            }
        }
        if (runs.isEmpty()) {
            RunsEmptyState(onGoToRecord = onGoToRecord, modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(runs, key = { it.id }, contentType = { "run" }) { run ->
                    RunRow(
                        title = f.runTitle(run.startTimeMillis),
                        subtitle = f.runSubtitle(run.endTimeMillis - run.startTimeMillis, run.pointCount),
                        selected = run.id in selected,
                        active = run.id == activeId,
                        activePaused = activePaused,
                        onToggle = { vm.toggleSelect(run.id) },
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = actionable > 0,
            enter = RevealEnter,
            exit = RevealExit,
        ) {
            SelectionTray(
                actionable = trayCount,
                onDelete = { confirmDelete = true },
                onMerge = vm::mergeSelected,
            )
        }
    }

    // The guard covers a flag restored after process death alongside an empty selection.
    if (confirmDelete && actionable > 0) {
        DeleteRunsDialog(
            count = actionable,
            onConfirm = {
                confirmDelete = false
                vm.deleteSelected()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** A stock two-line list row; the checkbox is the row's own semantics, not a second target. */
@Composable
private fun RunRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    active: Boolean,
    activePaused: Boolean,
    onToggle: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        modifier = Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() }),
        supportingContent = { Text(subtitle) },
        leadingContent = { Checkbox(checked = selected, onCheckedChange = null) },
        trailingContent = if (active) {
            { ActiveTag(paused = activePaused) }
        } else {
            null
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        ),
    )
}

/** Dot plus word, so the state is never carried by colour alone. */
@Composable
private fun ActiveTag(paused: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RecordingDot(paused = paused)
        Text(
            if (paused) "Paused" else "Recording",
            style = MaterialTheme.typography.labelMedium,
            color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
    }
}

/** Destructive verb on the left, away from the right thumb. Merge is lossless, so it asks nothing. */
@Composable
private fun SelectionTray(actionable: Int, onDelete: () -> Unit, onMerge: () -> Unit) {
    ActionTray {
        OutlinedButton(
            onClick = onDelete,
            enabled = actionable >= 1,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier
                .weight(1f)
                .height(ControlHeight),
        ) {
            ControlLabel("Delete ($actionable)")
        }
        FilledTonalButton(
            onClick = onMerge,
            enabled = actionable >= 2,
            modifier = Modifier
                .weight(1f)
                .height(ControlHeight),
        ) {
            ControlLabel("Merge ($actionable)")
        }
    }
}

/** Delete is the app's one irreversible action, so it is the one that asks. */
@Composable
private fun DeleteRunsDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Delete this run?" else "Delete $count runs?") },
        text = {
            Text("The recorded points are removed from this device. GPX files you already shared are not affected.")
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun RunsEmptyState(onGoToRecord: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No runs yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Press Start on the Record screen to record one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        TextButton(onClick = onGoToRecord, modifier = Modifier.padding(top = 8.dp)) { Text("Go to Record") }
    }
}
