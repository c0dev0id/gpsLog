package de.codevoid.gpslog.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File

@Composable
internal fun SettingsSurface(vm: MainViewModel, onShareDebugLog: () -> Unit, onInstall: (File) -> Unit) {
    val recordingSource by vm.recordingSource.collectAsStateWithLifecycle()
    val debugLogging by vm.debugLogging.collectAsStateWithLifecycle()
    val updateState by vm.update.collectAsStateWithLifecycle()
    SettingsTab(
        modifier = Modifier.fillMaxSize(),
        recordingSource = recordingSource,
        onSelectSource = vm::setRecordingSource,
        debugLogging = debugLogging,
        onSetDebugLogging = vm::setDebugLogging,
        onShareDebugLog = onShareDebugLog,
        installedVersion = vm.installedVersion,
        updateState = updateState,
        onCheckUpdate = vm::checkForUpdate,
        onDownloadInstall = { vm.downloadAndInstall(onInstall) },
    )
}
