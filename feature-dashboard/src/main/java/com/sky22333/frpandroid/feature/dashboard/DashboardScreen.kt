package com.sky22333.frpandroid.feature.dashboard

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
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
import com.sky22333.frpandroid.core.ui.ErrorText
import com.sky22333.frpandroid.core.ui.InfoCard
import com.sky22333.frpandroid.core.ui.SectionTitle
import com.sky22333.frpandroid.core.ui.StatusChip
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DashboardUiState(
    val selectedType: FrpType = FrpType.Client,
    val profiles: List<FrpProfile> = emptyList(),
    val states: List<FrpRuntimeState> = emptyList(),
    val settings: FrpSettings = FrpSettings(),
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppGraph.repository(application)
    private val selectedType = kotlinx.coroutines.flow.MutableStateFlow(FrpType.Client)

    val uiState: StateFlow<DashboardUiState> = combine(
        selectedType,
        repository.profiles,
        repository.runtimeStates,
        repository.settings,
    ) { type, profiles, states, settings ->
        DashboardUiState(type, profiles.filter { it.type == type }, states, settings)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun selectType(type: FrpType) {
        selectedType.value = type
    }

    fun stopAll(context: Context) {
        FrpForegroundService.stopAll(context)
    }

    fun start(context: Context, profile: FrpProfile) {
        FrpForegroundService.startProfile(context, profile.id)
    }

    fun stop(context: Context, profile: FrpProfile) {
        FrpForegroundService.stopProfile(context, profile.id)
    }

    fun restart(context: Context, profile: FrpProfile) {
        viewModelScope.launch {
            repository.stop(profile)
            FrpForegroundService.startProfile(context, profile.id)
        }
    }
}

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onOpenProfiles: () -> Unit,
    onNewTunnel: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val running = state.states.count { it.state == FrpInstanceStatus.Running }
    val failed = state.states.count { it.state == FrpInstanceStatus.Failed }
    var pendingStartProfile by remember { mutableStateOf<FrpProfile?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val profile = pendingStartProfile
        pendingStartProfile = null
        if (granted && profile != null) {
            viewModel.start(context, profile)
        }
    }

    fun startWithPermission(profile: FrpProfile) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.start(context, profile)
        } else {
            pendingStartProfile = profile
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            TypeSelector(state.selectedType, viewModel::selectType)
        }
        item {
            ElevatedCard(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(20.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text = if (running > 0) stringResource(R.string.dashboard_running) else stringResource(R.string.dashboard_stopped),
                            style = MaterialTheme.typography.headlineMedium,
                            color = if (running > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.dashboard_instances_summary, running, failed))
                    }
                    FilledTonalButton(onClick = { viewModel.stopAll(context) }) {
                        Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.dashboard_stop_all))
                        Text(stringResource(R.string.dashboard_stop_all), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusChip(
                    text = stringResource(R.string.dashboard_foreground_service),
                    running = running > 0,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(
                    text = stringResource(R.string.dashboard_boot_start),
                    running = state.settings.bootStartEnabled,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            SectionTitle(
                title = stringResource(R.string.dashboard_profiles),
                action = {
                    FilledTonalButton(onClick = onOpenProfiles) {
                        Text(stringResource(R.string.dashboard_view_all))
                    }
                },
            )
        }
        if (state.profiles.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.dashboard_no_profiles),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.profiles, key = { it.id }) { profile ->
            val runtimeState = state.states.firstOrNull { it.id == profile.id }
            ProfileRuntimeCard(
                profile = profile,
                state = runtimeState,
                onStart = { startWithPermission(profile) },
                onStop = { viewModel.stop(context, profile) },
                onRestart = {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    ) {
                        viewModel.restart(context, profile)
                    } else {
                        pendingStartProfile = profile
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
        }
        item {
            Button(
                onClick = onNewTunnel,
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
            ) {
                Text("+ ${stringResource(R.string.dashboard_new_tunnel)}")
            }
        }
    }
}

@Composable
private fun TypeSelector(selectedType: FrpType, onSelect: (FrpType) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
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
private fun ProfileRuntimeCard(
    profile: FrpProfile,
    state: FrpRuntimeState?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
) {
    val running = state?.state == FrpInstanceStatus.Running
    InfoCard(
        modifier = Modifier.padding(horizontal = 16.dp),
        icon = if (profile.type == FrpType.Client) Icons.Rounded.CloudSync else Icons.Rounded.Dns,
        title = profile.name,
        subtitle = "${profile.type.name.lowercase()} · ${state?.state?.name ?: FrpInstanceStatus.Stopped.name}",
        trailing = {
            Row {
                IconButton(onClick = if (running) onStop else onStart) {
                    Icon(
                        imageVector = if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(if (running) R.string.dashboard_stop else R.string.dashboard_start),
                    )
                }
                IconButton(onClick = onRestart) {
                    Icon(
                        imageVector = Icons.Rounded.RestartAlt,
                        contentDescription = stringResource(R.string.dashboard_restart),
                    )
                }
            }
        },
    )
    val lastError = state?.lastError
    if (!lastError.isNullOrBlank()) {
        ErrorText(
            text = lastError,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
    }
}
