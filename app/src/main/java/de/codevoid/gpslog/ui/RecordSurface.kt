package de.codevoid.gpslog.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun RecordSurface(vm: MainViewModel, onOpenSettings: () -> Unit) {
    val state by vm.loggingState.collectAsStateWithLifecycle()
    val preciseLocation by vm.preciseLocation.collectAsStateWithLifecycle()
    RecordTab(
        modifier = Modifier.fillMaxSize(),
        state = state,
        preciseLocation = preciseLocation,
        onStart = vm::start,
        onStop = vm::stop,
        onPause = vm::pause,
        onUnpause = vm::unpause,
        onOpenSettings = onOpenSettings,
    )
}
