package de.codevoid.gpslog.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Content columns stop growing here so a tablet or landscape phone does not stretch the layout. */
internal val ContentMaxWidth = 600.dp

/** Horizontal page margin. */
internal val Gutter = 16.dp

/** Height of the primary verb buttons (Start/Stop/Pause, Delete/Merge, Export). */
internal val ControlHeight = 56.dp

/**
 * The one reveal in the app (precise-location banner, selection tray). Built once: a transition
 * holds lambdas, so one built inline would differ on every recomposition and defeat skipping.
 */
internal val RevealEnter: EnterTransition = expandVertically() + fadeIn()
internal val RevealExit: ExitTransition = shrinkVertically() + fadeOut()

/** Label of a [ControlHeight] button: one step up from the button default so it reads at 56 dp. */
@Composable
internal fun ControlLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

/** A surface's title row; there is no app bar. [trailing] sits at the end of the row. */
@Composable
internal fun SurfaceHeader(title: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Row(
            modifier = Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = Gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.weight(1f))
            trailing()
        }
    }
}

/** A small primary-coloured label above a group of rows or a panel. */
@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

/** The one grouping container: a low tonal surface with large corners and a 16 dp inset. */
@Composable
internal fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(Gutter), content = content)
    }
}

/** A pinned row of verb buttons at the bottom of a surface; callers give each button weight(1f). */
@Composable
internal fun ActionTray(content: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = Gutter, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

/** The recording marker: error while recording, the neutral outline while paused. Callers add the word. */
@Composable
internal fun RecordingDot(paused: Boolean, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    val color = if (paused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error
    Box(modifier = modifier.size(size).background(color, CircleShape))
}

/** A numeral with its unit hung off the same baseline; the unit is dropped while the value is a dash. */
@Composable
internal fun ValueWithUnit(
    value: String,
    unit: String?,
    valueStyle: TextStyle,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        Text(
            value,
            style = valueStyle,
            color = valueColor,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.alignByBaseline(),
        )
        if (unit != null && value != DASH) {
            Text(
                unit,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .alignByBaseline()
                    .padding(start = 4.dp),
            )
        }
    }
}
