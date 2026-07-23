package com.sky22333.frpandroid.feature.dashboard

import android.app.Application
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sky22333.frpandroid.core.data.AppGraph
import com.sky22333.frpandroid.core.data.FrpSettings
import com.sky22333.frpandroid.core.frp.FrpInstanceStatus
import com.sky22333.frpandroid.core.frp.FrpProfile
import com.sky22333.frpandroid.core.frp.FrpRuntimeState
import com.sky22333.frpandroid.core.frp.FrpType
import com.sky22333.frpandroid.core.runtime.FrpForegroundService
import com.sky22333.frpandroid.core.runtime.FrpRuntimePermissions
import com.sky22333.frpandroid.core.ui.ErrorText
import com.sky22333.frpandroid.core.ui.EmptyState
import com.sky22333.frpandroid.core.ui.FrpListRow
import com.sky22333.frpandroid.core.ui.FrpUiTokens
import com.sky22333.frpandroid.core.ui.SectionTitle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DashboardUiState(
    val selectedType: FrpType = FrpType.Client,
    val profiles: List<FrpProfile> = emptyList(),
    val states: List<FrpRuntimeState> = emptyList(),
    val settings: FrpSettings = FrpSettings(),
    val busyProfileIds: Set<String> = emptySet(),
    val stopAllBusy: Boolean = false,
    val actionError: String? = null,
)

private enum class PendingRuntimeAction {
    Start,
    Restart,
}

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppGraph.repository(application)
    private val selectedType = MutableStateFlow(FrpType.Client)
    private val busyProfileIds = MutableStateFlow<Set<String>>(emptySet())
    private val stopAllBusy = MutableStateFlow(false)
    private val actionError = MutableStateFlow<String?>(null)

    private val baseUiState = combine(
        selectedType,
        repository.profiles,
        repository.runtimeStates,
        repository.settings,
    ) { type, profiles, states, settings ->
        DashboardUiState(type, profiles.filter { it.type == type }, states, settings)
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        baseUiState,
        busyProfileIds,
        stopAllBusy,
        actionError,
    ) { state, busyIds, allBusy, error ->
        state.copy(busyProfileIds = busyIds, stopAllBusy = allBusy, actionError = error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    init {
        viewModelScope.launch {
            repository.runtimeStates.collect { states ->
                val knownIds = states.map { it.id }.toSet()
                busyProfileIds.update { busy -> busy - knownIds }
                if (states.none { it.state == FrpInstanceStatus.Stopping }) {
                    stopAllBusy.value = false
                }
            }
        }
    }

    fun selectType(type: FrpType) {
        selectedType.value = type
    }

    fun stopAll(context: Context) {
        if (stopAllBusy.value) return
        stopAllBusy.value = true
        actionError.value = null
        runCatching {
            FrpForegroundService.stopAll(context)
        }.onFailure {
            actionError.value = it.message
            stopAllBusy.value = false
        }
    }

    fun start(context: Context, profile: FrpProfile) {
        if (!markBusy(profile.id)) return
        actionError.value = null
        runCatching {
            FrpForegroundService.startProfile(context, profile.id)
        }.onFailure {
            actionError.value = it.message
            busyProfileIds.update { it - profile.id }
        }
    }

    fun stop(context: Context, profile: FrpProfile) {
        if (!markBusy(profile.id)) return
        actionError.value = null
        runCatching {
            FrpForegroundService.stopProfile(context, profile.id)
        }.onFailure {
            actionError.value = it.message
            busyProfileIds.update { it - profile.id }
        }
    }

    fun restart(context: Context, profile: FrpProfile) {
        if (!markBusy(profile.id)) return
        actionError.value = null
        runCatching {
            FrpForegroundService.restartProfile(context, profile.id)
        }.onFailure {
            actionError.value = it.message
            busyProfileIds.update { it - profile.id }
        }
    }

    private fun markBusy(id: String): Boolean {
        if (id in busyProfileIds.value) return false
        busyProfileIds.update { it + id }
        return true
    }
}

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val active = state.states.count { it.state == FrpInstanceStatus.Running || it.state == FrpInstanceStatus.Stopping }
    val running = state.states.count { it.state == FrpInstanceStatus.Running }
    val failed = state.states.count { it.state == FrpInstanceStatus.Failed }
    var pendingStartProfile by remember { mutableStateOf<FrpProfile?>(null) }
    var pendingRuntimeAction by remember { mutableStateOf<PendingRuntimeAction?>(null) }
    val runtimePermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val profile = pendingStartProfile
        val action = pendingRuntimeAction
        pendingStartProfile = null
        pendingRuntimeAction = null
        if (profile != null && result.values.all { it }) {
            when (action) {
                PendingRuntimeAction.Restart -> viewModel.restart(context, profile)
                else -> viewModel.start(context, profile)
            }
        }
    }

    fun runWithRuntimePermissions(profile: FrpProfile, action: PendingRuntimeAction) {
        val missing = FrpRuntimePermissions.missingPermissions(context)
        if (missing.isEmpty()) {
            when (action) {
                PendingRuntimeAction.Start -> viewModel.start(context, profile)
                PendingRuntimeAction.Restart -> viewModel.restart(context, profile)
            }
        } else {
            pendingStartProfile = profile
            pendingRuntimeAction = action
            runtimePermissionsLauncher.launch(missing)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(FrpUiTokens.ListSpacing),
    ) {
        item {
            TypeSelector(state.selectedType, viewModel::selectType)
        }
        state.actionError?.let { error ->
            item {
                ErrorText(
                    text = error,
                    modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding),
                )
            }
        }
        item {
            RuntimeOverviewCard(
                active = active,
                running = running,
                failed = failed,
                busy = state.stopAllBusy,
                onStopAll = { viewModel.stopAll(context) },
            )
        }
        item {
            Row(
                modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FrpUiTokens.ListSpacing),
            ) {
                FrpListRow(
                    icon = Icons.Rounded.Dns,
                    title = stringResource(R.string.dashboard_foreground_service),
                    subtitle = if (active > 0) stringResource(R.string.dashboard_running) else stringResource(R.string.dashboard_stopped),
                    statusRunning = active > 0,
                    modifier = Modifier.weight(1f),
                )
                FrpListRow(
                    icon = Icons.Rounded.PowerSettingsNew,
                    title = stringResource(R.string.dashboard_boot_start),
                    subtitle = if (state.settings.bootStartEnabled) stringResource(R.string.dashboard_enabled) else stringResource(R.string.dashboard_disabled),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            SectionTitle(title = stringResource(R.string.dashboard_profiles))
        }
        if (state.profiles.isEmpty()) {
            item {
                EmptyState(text = stringResource(R.string.dashboard_no_profiles))
            }
        }
        items(state.profiles, key = { it.id }) { profile ->
            val runtimeState = state.states.firstOrNull { it.id == profile.id }
            ProfileRuntimeCard(
                profile = profile,
                state = runtimeState,
                busy = profile.id in state.busyProfileIds,
                onStart = { runWithRuntimePermissions(profile, PendingRuntimeAction.Start) },
                onStop = { viewModel.stop(context, profile) },
                onRestart = { runWithRuntimePermissions(profile, PendingRuntimeAction.Restart) },
            )
        }
    }
}

@Composable
private fun TypeSelector(selectedType: FrpType, onSelect: (FrpType) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(FrpUiTokens.ScreenPadding).fillMaxWidth()) {
        SegmentedButton(
            selected = selectedType == FrpType.Client,
            onClick = { onSelect(FrpType.Client) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) {
            Text("frpc")
        }
        SegmentedButton(
            selected = selectedType == FrpType.Server,
            onClick = { onSelect(FrpType.Server) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) {
            Text("frps")
        }
    }
}

@Composable
private fun RuntimeOverviewCard(
    active: Int,
    running: Int,
    failed: Int,
    busy: Boolean,
    onStopAll: () -> Unit,
) {
    Surface(
        modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding).fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(20.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (active > 0) stringResource(R.string.dashboard_running) else stringResource(R.string.dashboard_stopped),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (active > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.dashboard_instances_summary, running, failed))
            }
            FilledTonalButton(
                onClick = onStopAll,
                enabled = active > 0 && !busy,
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.dashboard_stop_all))
                }
                Text(stringResource(R.string.dashboard_stop_all), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun ProfileRuntimeCard(
    profile: FrpProfile,
    state: FrpRuntimeState?,
    busy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
) {
    val running = state?.state == FrpInstanceStatus.Running || state?.state == FrpInstanceStatus.Stopping
    val stopping = state?.state == FrpInstanceStatus.Stopping
    val lastError = state?.lastError
    FrpListRow(
        modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding),
        icon = if (profile.type == FrpType.Client) Icons.Rounded.CloudSync else Icons.Rounded.Dns,
        title = profile.name,
        subtitle = "${profile.type.name.lowercase()} · ${state?.state?.name ?: FrpInstanceStatus.Stopped.name}",
        status = lastError,
        statusRunning = running && lastError.isNullOrBlank(),
        trailing = {
            Row {
                IconButton(
                    onClick = if (running) onStop else onStart,
                    enabled = !busy,
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(if (running) R.string.dashboard_stop else R.string.dashboard_start),
                        )
                    }
                }
                IconButton(onClick = onRestart, enabled = !busy && !stopping) {
                    Icon(
                        imageVector = Icons.Rounded.RestartAlt,
                        contentDescription = stringResource(R.string.dashboard_restart),
                    )
                }
            }
        },
    )
}

