package com.sky22333.frpandroid.feature.settings

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sky22333.frpandroid.core.data.AppGraph
import com.sky22333.frpandroid.core.data.FrpDiagnostics
import com.sky22333.frpandroid.core.data.FrpSettings
import com.sky22333.frpandroid.core.frp.LanguageMode
import com.sky22333.frpandroid.core.frp.ThemeMode
import com.sky22333.frpandroid.core.runtime.FrpForegroundService
import com.sky22333.frpandroid.core.ui.SectionTitle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val diagnostics: FrpDiagnostics? = null,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppGraph.repository(application)
    private val mutableUiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = mutableUiState
    val settings: StateFlow<FrpSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FrpSettings())

    fun setBootStart(enabled: Boolean) = viewModelScope.launch { repository.setBootStartEnabled(enabled) }
    fun setNetworkReconnect(enabled: Boolean) = viewModelScope.launch { repository.setNetworkReconnectEnabled(enabled) }
    fun setAutoRetry(enabled: Boolean) = viewModelScope.launch { repository.setAutoRetryEnabled(enabled) }
    fun setDiagnostics(enabled: Boolean) = viewModelScope.launch { repository.setDiagnosticsSamplingEnabled(enabled) }
    fun setRetention(days: Int) = viewModelScope.launch { repository.setLogRetentionDays(days) }
    fun setTheme(mode: ThemeMode) = viewModelScope.launch { repository.setThemeMode(mode) }
    fun setLanguage(mode: LanguageMode) = viewModelScope.launch { repository.setLanguageMode(mode) }
    fun refreshDiagnostics() = viewModelScope.launch {
        repository.initialize()
        mutableUiState.update { it.copy(diagnostics = repository.diagnostics()) }
    }
    fun recoverPendingStart(context: Context) = viewModelScope.launch {
        val recoverableProfiles = repository.getNetworkRecoverableProfiles()
        val profiles = if (recoverableProfiles.isNotEmpty()) recoverableProfiles else repository.getAutoStartProfiles()
        profiles.forEach { profile ->
            FrpForegroundService.startProfile(context, profile.id)
        }
    }
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var themeDialog by remember { mutableStateOf(false) }
    var languageDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshDiagnostics()
    }

    LazyColumn(Modifier.fillMaxSize()) {
        if (settings.pendingStart) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_pending_start)) },
                    leadingContent = { Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null) },
                    modifier = Modifier.clickable { viewModel.recoverPendingStart(context) },
                )
            }
        }
        item { SectionTitle(stringResource(R.string.settings_background_section)) }
        item {
            ToggleItem(
                title = stringResource(R.string.settings_boot_start),
                checked = settings.bootStartEnabled,
                onChange = viewModel::setBootStart,
            )
        }
        item {
            ToggleItem(
                title = stringResource(R.string.settings_network_reconnect),
                checked = settings.networkReconnectEnabled,
                onChange = viewModel::setNetworkReconnect,
            )
        }
        item {
            ToggleItem(
                title = stringResource(R.string.settings_auto_retry),
                checked = settings.autoRetryEnabled,
                onChange = viewModel::setAutoRetry,
            )
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_battery)) },
                supportingContent = {
                    Text(
                        stringResource(
                            if (isIgnoringBatteryOptimizations(context)) {
                                R.string.settings_battery_allowed
                            } else {
                                R.string.settings_battery_restricted
                            },
                        ),
                    )
                },
                leadingContent = { Icon(Icons.Rounded.BatterySaver, contentDescription = null) },
                modifier = Modifier.clickable { openBatterySettings(context) },
            )
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_notifications)) },
                leadingContent = { Icon(Icons.Rounded.Notifications, contentDescription = null) },
                modifier = Modifier.clickable { openNotificationSettings(context) },
            )
        }
        item { SectionTitle(stringResource(R.string.settings_appearance_section)) }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_theme)) },
                supportingContent = { Text(themeLabel(settings.themeMode)) },
                leadingContent = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                modifier = Modifier.clickable { themeDialog = true },
            )
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_language)) },
                supportingContent = { Text(languageLabel(settings.languageMode)) },
                modifier = Modifier.clickable { languageDialog = true },
            )
        }
        item { SectionTitle(stringResource(R.string.settings_logs_section)) }
        item {
            ListItem(
                headlineContent = { Text("${stringResource(R.string.settings_log_retention)}: ${settings.logRetentionDays}") },
                supportingContent = {
                    Slider(
                        value = settings.logRetentionDays.toFloat(),
                        onValueChange = { viewModel.setRetention(it.toInt()) },
                        valueRange = 1f..30f,
                        steps = 28,
                    )
                },
            )
        }
        item { SectionTitle(stringResource(R.string.settings_diagnostics_section)) }
        item {
            ToggleItem(
                title = stringResource(R.string.settings_diagnostics),
                checked = settings.diagnosticsSamplingEnabled,
                onChange = viewModel::setDiagnostics,
            )
        }
        uiState.diagnostics?.let { diagnostics ->
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_diagnostics_summary)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                R.string.settings_diagnostics_details,
                                diagnostics.nativeAvailable,
                                diagnostics.runtimeInitialized,
                                diagnostics.tempDirStatus,
                                diagnostics.runningCount,
                                diagnostics.failedCount,
                                diagnostics.pendingStart,
                                diagnostics.lastError ?: "-",
                            ),
                        )
                    },
                    leadingContent = { Icon(Icons.Rounded.BugReport, contentDescription = null) },
                    modifier = Modifier.clickable { viewModel.refreshDiagnostics() },
                )
            }
        }
        item { SectionTitle(stringResource(R.string.settings_about_section)) }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_version)) },
                supportingContent = { Text("0.1.0") },
            )
        }
    }

    if (themeDialog) {
        ChoiceDialog(
            title = stringResource(R.string.settings_theme),
            onDismiss = { themeDialog = false },
            entries = listOf(
                stringResource(R.string.settings_system) to { viewModel.setTheme(ThemeMode.System) },
                stringResource(R.string.settings_light) to { viewModel.setTheme(ThemeMode.Light) },
                stringResource(R.string.settings_dark) to { viewModel.setTheme(ThemeMode.Dark) },
                stringResource(R.string.settings_amoled) to { viewModel.setTheme(ThemeMode.Amoled) },
            ),
        )
    }
    if (languageDialog) {
        ChoiceDialog(
            title = stringResource(R.string.settings_language),
            onDismiss = { languageDialog = false },
            entries = listOf(
                stringResource(R.string.settings_system) to { viewModel.setLanguage(LanguageMode.System) },
                stringResource(R.string.settings_chinese) to { viewModel.setLanguage(LanguageMode.Chinese) },
                stringResource(R.string.settings_english) to { viewModel.setLanguage(LanguageMode.English) },
            ),
        )
    }
}

@Composable
private fun ToggleItem(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
private fun ChoiceDialog(
    title: String,
    onDismiss: () -> Unit,
    entries: List<Pair<String, () -> Unit>>,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                entries.forEach { (label, action) ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            action()
                            onDismiss()
                        },
                        label = { Text(label) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(android.R.string.cancel))
            }
        },
    )
}

private fun openNotificationSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
    }
    context.startActivity(intent)
}

private fun openBatterySettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isIgnoringBatteryOptimizations(context)) {
        val requestIntent = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        runCatching {
            context.startActivity(requestIntent)
        }.onSuccess {
            return
        }
    }
    val detailsIntent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:${context.packageName}"))
    runCatching {
        context.startActivity(detailsIntent)
    }.onFailure {
        context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val powerManager = context.getSystemService(PowerManager::class.java)
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

@Composable
private fun themeLabel(mode: ThemeMode): String =
    when (mode) {
        ThemeMode.System -> stringResource(R.string.settings_system)
        ThemeMode.Light -> stringResource(R.string.settings_light)
        ThemeMode.Dark -> stringResource(R.string.settings_dark)
        ThemeMode.Amoled -> stringResource(R.string.settings_amoled)
    }

@Composable
private fun languageLabel(mode: LanguageMode): String =
    when (mode) {
        LanguageMode.System -> stringResource(R.string.settings_system)
        LanguageMode.Chinese -> stringResource(R.string.settings_chinese)
        LanguageMode.English -> stringResource(R.string.settings_english)
    }
