package de.codevoid.gpslog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.ZoneId
import java.util.Locale

/**
 * The past runs, newest first, following the platform list idiom: a tap opens the run's Export
 * page, a long-press selects it and starts selection mode (checkboxes appear, the header turns into
 * a count with Close, Back leaves the mode), and the tray below acts on the selection with Delete,
 * Merge and Export. The active run may be exported but is excluded from Delete and Merge while its
 * file is being written. Collects [MainViewModel.activeRun] rather than the logging state, so point
 * ticks never reach this surface; the active row shows the disk-derived count like every other row.
 */
@Composable
internal fun RunsSurface(vm: MainViewModel, listState: LazyListState, onGoToRecord: () -> Unit) {
    val runs by vm.runs.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val active by vm.activeRun.collectAsStateWithLifecycle()
    val f = remember { Formats(Locale.getDefault(), ZoneId.systemDefault()) }

    val activeId = active?.id
    val activePaused = active?.paused == true
    // Selection mode is derived, not a flag: it is on exactly while something is selected.
    val selecting = selected.isNotEmpty()
    val actionable = selected.count { it != activeId }
    val summary = remember(runs) { f.runsSummary(runs.size, runs.sumOf { it.pointCount }) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = selecting) { vm.clearSelection() }
    // A flag restored after process death outlives the selection it belonged to; drop it with it.
    LaunchedEffect(actionable) {
        if (actionable == 0) confirmDelete = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (selecting) {
            SurfaceHeader(
                title = "${selected.size} selected",
                leading = {
                    IconButton(onClick = vm::clearSelection) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                    }
                },
            )
        } else {
            SurfaceHeader("Runs") {
                // The empty state below already says there are no runs.
                if (runs.isNotEmpty()) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (runs.isEmpty()) {
            RunsEmptyState(onGoToRecord = onGoToRecord, modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                itemsIndexed(
                    runs,
                    key = { _, run -> run.id },
                    contentType = { _, _ -> "run" },
                ) { index, run ->
                    Column {
                        RunRow(
                            title = f.runTitle(run.startTimeMillis),
                            subtitle = f.runSubtitle(run.endTimeMillis - run.startTimeMillis, run.pointCount),
                            selecting = selecting,
                            isSelected = run.id in selected,
                            active = run.id == activeId,
                            activePaused = activePaused,
                            onToggle = { vm.toggleSelect(run.id) },
                            onExport = { vm.openExport(setOf(run.id)) },
                        )
                        // Without a divider the rows have no visible bounds: the container is
                        // transparent and a short date does not fill the width of a wide window.
                        if (index < runs.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = selecting,
            enter = RevealEnter,
            exit = RevealExit,
        ) {
            SelectionTray(
                actionable = actionable,
                onDelete = { confirmDelete = true },
                onMerge = vm::mergeSelected,
                onExport = { vm.openExport(selected) },
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

/**
 * A stock two-line list row. One `combinedClickable` in both modes — its lambdas branch on
 * [selecting] — so the modifier is never swapped under a finger that is still down; the checkbox
 * exists only in selection mode and mirrors the row, it is not a second target.
 *
 * The trailing Share glyph is the row's affordance: it is the only thing saying a tap exports this
 * run, so it is drawn whenever a tap would do that. The active run's tag sits in the overline
 * rather than the trailing slot, where a wide window stranded it far from the run it describes.
 */
@Composable
private fun RunRow(
    title: String,
    subtitle: String,
    selecting: Boolean,
    isSelected: Boolean,
    active: Boolean,
    activePaused: Boolean,
    onToggle: () -> Unit,
    onExport: () -> Unit,
) {
    val toggleLabel = if (isSelected) "Deselect" else "Select"
    ListItem(
        headlineContent = { Text(title) },
        modifier = Modifier
            .semantics { selected = isSelected }
            .combinedClickable(
                onClickLabel = if (selecting) toggleLabel else "Export",
                onLongClickLabel = toggleLabel,
                onLongClick = onToggle,
                onClick = { if (selecting) onToggle() else onExport() },
            ),
        overlineContent = if (active) {
            { ActiveTag(paused = activePaused) }
        } else {
            null
        },
        supportingContent = { Text(subtitle) },
        leadingContent = if (selecting) {
            { Checkbox(checked = isSelected, onCheckedChange = null) }
        } else {
            null
        },
        trailingContent = if (selecting) {
            null
        } else {
            { Icon(Icons.Outlined.Share, contentDescription = null) }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
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

/**
 * Destructive verb on the left, away from the right thumb; Export, the common verb, under it.
 * Merge is lossless, so it asks nothing. The header carries the count, so the labels do not; the
 * narrow padding is what lets three labels share a 360 dp phone at large font sizes.
 */
@Composable
private fun SelectionTray(actionable: Int, onDelete: () -> Unit, onMerge: () -> Unit, onExport: () -> Unit) {
    val padding = PaddingValues(horizontal = 8.dp)
    ActionTray {
        OutlinedButton(
            onClick = onDelete,
            enabled = actionable >= 1,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            contentPadding = padding,
            modifier = Modifier
                .weight(1f)
                .height(ControlHeight),
        ) {
            ControlLabel("Delete")
        }
        FilledTonalButton(
            onClick = onMerge,
            enabled = actionable >= 2,
            contentPadding = padding,
            modifier = Modifier
                .weight(1f)
                .height(ControlHeight),
        ) {
            ControlLabel("Merge")
        }
        Button(
            onClick = onExport,
            contentPadding = padding,
            modifier = Modifier
                .weight(1f)
                .height(ControlHeight),
        ) {
            ControlLabel("Export")
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
