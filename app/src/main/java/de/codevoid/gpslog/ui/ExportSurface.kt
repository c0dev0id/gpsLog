package de.codevoid.gpslog.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun ExportSurface(vm: MainViewModel, onShare: () -> Unit) {
    val selected by vm.selected.collectAsStateWithLifecycle()
    val runs by vm.runs.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val preview by vm.exportPreview.collectAsStateWithLifecycle()
    ExportTab(
        modifier = Modifier.fillMaxSize(),
        selectedRuns = selected.size,
        selectedPoints = runs.filter { it.id in selected }.sumOf { it.pointCount },
        filters = filters,
        preview = preview,
        onAccuracy = vm::setAccuracyMeters,
        onDistance = vm::setDistanceMeters,
        onTime = vm::setTimeSeconds,
        onShare = onShare,
    )
}
