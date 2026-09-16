package de.codevoid.gpslog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.codevoid.gpslog.data.FilterSettings
import java.time.ZoneId
import java.util.Locale

/**
 * The selection's export: a summary, the accuracy/distance/time filters, a live preview of the
 * filtered result and one Export action that hands the GPX to the system share sheet (saving to
 * disk is sharing to a file manager). Only reachable with a selection; the navigation gates it.
 * In a wide window the filters sit beside the result.
 */
@Composable
internal fun ExportSurface(vm: MainViewModel, wide: Boolean, onShare: () -> Unit) {
    val target by vm.exportTarget.collectAsStateWithLifecycle()
    val runs by vm.runs.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val preview by vm.exportPreview.collectAsStateWithLifecycle()
    val f = remember { Formats(Locale.getDefault(), ZoneId.systemDefault()) }
    val chosen = remember(runs, target) { runs.filter { it.id in target } }
    // One run reads as that run's page; several read as a count.
    val one = chosen.singleOrNull()

    // imePadding keeps the pinned Export row above the keyboard while a filter field is focused.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        SurfaceHeader("Export")
        Column(
            modifier = Modifier
                .weight(1f)
                .widthIn(max = if (wide) WideContentMaxWidth else ContentMaxWidth)
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Gutter, vertical = 8.dp),
        ) {
            Text(
                if (one != null) f.runTitle(one.startTimeMillis) else f.plural(chosen.size.toLong(), "run", "runs"),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (one != null) {
                    f.runSubtitle(one.endTimeMillis - one.startTimeMillis, one.pointCount)
                } else {
                    f.plural(chosen.sumOf { it.pointCount }, "raw point", "raw points")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        SectionHeader("Filters")
                        FiltersPanel(filters, vm::setAccuracyMeters, vm::setDistanceMeters, vm::setTimeSeconds)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        SectionHeader("Result")
                        ResultPanel(preview = preview, f = f)
                    }
                }
            } else {
                SectionHeader("Filters")
                FiltersPanel(filters, vm::setAccuracyMeters, vm::setDistanceMeters, vm::setTimeSeconds)
                SectionHeader("Result")
                ResultPanel(preview = preview, f = f)
            }
        }
        ActionTray {
            Button(
                onClick = onShare,
                enabled = preview is ExportPreview.Ready,
                modifier = Modifier
                    .weight(1f)
                    .height(ControlHeight),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
                ControlLabel("Export GPX")
            }
        }
    }
}

/** The three persisted filters; a valid entry commits at once and the preview follows. */
@Composable
private fun FiltersPanel(
    filters: FilterSettings,
    onAccuracy: (Int) -> Unit,
    onDistance: (Float) -> Unit,
    onTime: (Int) -> Unit,
) {
    Panel {
        FilterRow(
            name = "Accuracy",
            helper = "Drop fixes worse than this",
            errorHelper = "Enter 1–999",
            value = filters.accuracyMeters,
            format = { filterText(it) },
            parse = { parseIntIn(it, 1..999) },
            onCommit = onAccuracy,
            unit = "m",
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next,
        )
        FilterRow(
            name = "Distance",
            helper = "Minimum spacing between points · 0 = off",
            errorHelper = "Enter 0–999.9",
            value = filters.distanceMeters,
            format = { filterText(it) },
            parse = { parseDistance(it) },
            onCommit = onDistance,
            unit = "m",
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Next,
            modifier = Modifier.padding(top = 12.dp),
        )
        FilterRow(
            name = "Time",
            // PointFilter's rule: a distance decides on its own; time only governs cadence
            // without one. The field stays enabled so time can be set before clearing distance.
            helper = if (filters.distanceMeters > 0f) {
                "Ignored while a distance is set"
            } else {
                "Minimum interval between points · 0 = off"
            },
            errorHelper = "Enter 0–999",
            value = filters.timeSeconds,
            format = { filterText(it) },
            parse = { parseIntIn(it, 0..999) },
            onCommit = onTime,
            unit = "s",
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/**
 * One filter: its meaning on the left, a narrow number field on the right. A valid entry commits
 * immediately (persisted, and the preview follows); an invalid one commits nothing, flags the field
 * and swaps the helper for the range; losing focus snaps the text back to the value in effect, so
 * the field never shows a number that is not being applied.
 */
@Composable
private fun <T : Any> FilterRow(
    name: String,
    helper: String,
    errorHelper: String,
    value: T,
    format: (T) -> String,
    parse: (String) -> T?,
    onCommit: (T) -> Unit,
    unit: String,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(format(value)) }
    val valid = parse(text) != null
    val focusManager = LocalFocusManager.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (valid) helper else errorHelper,
                style = MaterialTheme.typography.bodySmall,
                color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = { typed ->
                text = typed
                parse(typed)?.let(onCommit)
            },
            modifier = Modifier
                .width(120.dp)
                .semantics { contentDescription = name }
                .onFocusChanged { if (!it.isFocused) text = format(value) },
            singleLine = true,
            isError = !valid,
            suffix = { Text(unit) },
            textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.End, fontFeatureSettings = "tnum"),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            // Done only hides the keyboard by default; dropping focus is what triggers the snap-back.
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        )
    }
}

/**
 * The filtered result. While a recompute runs the last result stays visible and dims as a whole,
 * so a keystroke never flashes dashes; the panel keeps a minimum height so nothing below it moves.
 */
@Composable
private fun ResultPanel(preview: ExportPreview, f: Formats) {
    var lastReady by remember { mutableStateOf<ExportPreview.Ready?>(null) }
    LaunchedEffect(preview) {
        if (preview is ExportPreview.Ready) lastReady = preview
    }
    val shown = preview as? ExportPreview.Ready ?: lastReady

    Panel(modifier = Modifier.defaultMinSize(minHeight = 96.dp)) {
        if (shown == null) {
            Text(DASH, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.outline)
        } else {
            val empty = shown.totalPoints == 0L
            val none = !empty && shown.keptPoints == 0L
            Column(modifier = Modifier.alpha(if (preview is ExportPreview.Ready) 1f else 0.6f)) {
                ValueWithUnit(
                    value = f.count(shown.keptPoints),
                    unit = if (shown.keptPoints == 1L) "point" else "points",
                    valueStyle = MaterialTheme.typography.headlineLarge,
                    valueColor = if (none) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                val (detail, detailColor) = when {
                    empty -> "The selected runs have no points" to MaterialTheme.colorScheme.onSurfaceVariant
                    none -> "Every point is filtered out — raise the accuracy limit" to MaterialTheme.colorScheme.error
                    else -> "of ${f.count(shown.totalPoints)} · " +
                        "${f.plural(shown.tracks.toLong(), "track", "tracks")} · " +
                        "${reductionPercent(shown.totalPoints, shown.keptPoints)}% fewer" to
                        MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = detailColor,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
