package de.codevoid.gpslog.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun RunsSurface(vm: MainViewModel, onGoToRecord: () -> Unit) {
    val runs by vm.runs.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val active by vm.activeRun.collectAsStateWithLifecycle()
    RunsTab(
        modifier = Modifier.fillMaxSize(),
        runs = runs,
        selected = selected,
        activeRunId = active?.id,
        onToggleSelect = vm::toggleSelect,
        onDeleteSelected = vm::deleteSelected,
        onMergeSelected = vm::mergeSelected,
    )
}
