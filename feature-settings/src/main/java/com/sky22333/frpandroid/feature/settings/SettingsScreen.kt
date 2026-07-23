package com.sky22333.frpandroid.feature.settings

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ScreenLockPortrait
import androidx.compose.material.icons.rounded.TipsAndUpdates
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sky22333.frpandroid.core.data.AppGraph
import com.sky22333.frpandroid.core.data.FrpSettings
import com.sky22333.frpandroid.core.frp.DEFAULT_THEME_SEED_COLOR
import com.sky22333.frpandroid.core.frp.LanguageMode
import com.sky22333.frpandroid.core.runtime.FrpForegroundService
import com.sky22333.frpandroid.core.runtime.FrpRetryWorker
import com.sky22333.frpandroid.core.runtime.RecoveryReason
import com.sky22333.frpandroid.core.ui.FrpListRow
import com.sky22333.frpandroid.core.ui.FrpUiTokens
import com.sky22333.frpandroid.core.ui.SectionTitle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private data class ThemeSeedOption(
    val color: Int,
    val labelRes: Int,
)

private val ThemeSeedOptions = listOf(
    ThemeSeedOption(DEFAULT_THEME_SEED_COLOR, R.string.settings_seed_teal),
    ThemeSeedOption(0xFF4D6BFE.toInt(), R.string.settings_seed_blue),
    ThemeSeedOption(0xFF6D5BD0.toInt(), R.string.settings_seed_violet),
    ThemeSeedOption(0xFF00875A.toInt(), R.string.settings_seed_green),
    ThemeSeedOption(0xFFC26A00.toInt(), R.string.settings_seed_amber),
    ThemeSeedOption(0xFFB3261E.toInt(), R.string.settings_seed_red),
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppGraph.repository(application)
    private val mutableKernelVersion = MutableStateFlow<String?>(null)
    val settings: StateFlow<FrpSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FrpSettings())
    val kernelVersion: StateFlow<String?> = mutableKernelVersion

    fun setBootStart(enabled: Boolean) = viewModelScope.launch { repository.setBootStartEnabled(enabled) }
    fun setNetworkReconnect(enabled: Boolean) = viewModelScope.launch {
        repository.setNetworkReconnectEnabled(enabled)
        if (!enabled) FrpRetryWorker.cancelAll(getApplication(), RecoveryReason.Network)
    }
    fun setAutoRetry(enabled: Boolean) = viewModelScope.launch {
        repository.setAutoRetryEnabled(enabled)
        if (!enabled) FrpRetryWorker.cancelAll(getApplication(), RecoveryReason.AutoRetry)
    }
    fun setScreenOffKeepAlive(enabled: Boolean) = viewModelScope.launch {
        repository.setScreenOffKeepAliveEnabled(enabled)
    }
    fun setRetention(days: Int) = viewModelScope.launch { repository.setLogRetentionDays(days) }
    fun setThemeSeedColor(color: Int) = viewModelScope.launch { repository.setThemeSeedColor(color) }
    fun setLanguage(mode: LanguageMode, onChanged: () -> Unit = {}) = viewModelScope.launch {
        repository.setLanguageMode(mode)
        onChanged()
    }
    fun loadKernelVersion() = viewModelScope.launch {
        if (mutableKernelVersion.value == null) {
            mutableKernelVersion.value = repository.kernelVersion()
        }
    }
    fun recoverPendingStart(context: Context) = viewModelScope.launch {
        val recoverableProfiles = repository.getDesiredRunningProfiles()
        val profiles = if (recoverableProfiles.isNotEmpty()) recoverableProfiles else repository.getAutoStartProfiles()
        profiles.forEach { profile ->
            FrpForegroundService.startProfile(context, profile.id)
        }
    }
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onLanguageChanged: () -> Unit = {},
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val kernelVersion by viewModel.kernelVersion.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var seedDialog by remember { mutableStateOf(false) }
    var languageDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(FrpUiTokens.ListSpacing),
    ) {
        if (settings.pendingStart) {
            item {
                FrpListRow(
                    icon = Icons.Rounded.PowerSettingsNew,
                    title = stringResource(R.string.settings_pending_start),
                    subtitle = stringResource(R.string.settings_pending_start_hint),
                    statusRunning = true,
                    modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding).clickable { viewModel.recoverPendingStart(context) },
                )
            }
        }
        item { SectionTitle(stringResource(R.string.settings_background_section)) }
        item {
            ToggleItem(
                icon = Icons.Rounded.PowerSettingsNew,
                title = stringResource(R.string.settings_boot_start),
                checked = settings.bootStartEnabled,
                onChange = viewModel::setBootStart,
            )
        }
        item {
            ToggleItem(
                icon = Icons.Rounded.Wifi,
                title = stringResource(R.string.settings_network_reconnect),
                checked = settings.networkReconnectEnabled,
                onChange = viewModel::setNetworkReconnect,
            )
        }
        item {
            ToggleItem(
                icon = Icons.Rounded.Refresh,
                title = stringResource(R.string.settings_auto_retry),
                checked = settings.autoRetryEnabled,
                onChange = viewModel::setAutoRetry,
            )
        }
        item {
            ToggleItem(
                icon = Icons.Rounded.ScreenLockPortrait,
                title = stringResource(R.string.settings_screen_off_keep_alive),
                checked = settings.screenOffKeepAliveEnabled,
                onChange = viewModel::setScreenOffKeepAlive,
            )
        }
        item {
            FrpListRow(
                icon = Icons.Rounded.BatterySaver,
                title = stringResource(R.string.settings_battery),
                subtitle = stringResource(
                    if (isIgnoringBatteryOptimizations(context)) {
                        R.string.settings_battery_allowed
                    } else {
                        R.string.settings_battery_restricted
                    },
                ),
                modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding).clickable { openBatterySettings(context) },
            )
        }
        item {
            FrpListRow(
                icon = Icons.Rounded.TipsAndUpdates,
                title = stringResource(R.string.settings_autostart_guide),
                subtitle = stringResource(R.string.settings_autostart_guide_hint),
                modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding).clickable { openAppDetails(context) },
            )
        }
        item {
            FrpListRow(
                icon = Icons.Rounded.Notifications,
                title = stringResource(R.string.settings_notifications),
                subtitle = stringResource(R.string.settings_notifications_hint),
                modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding).clickable { openNotificationSettings(context) },
            )
        }
        item { SectionTitle(stringResource(R.string.settings_appearance_section)) }
        item {
            FrpListRow(
                icon = Icons.Rounded.Palette,
                title = stringResource(R.string.settings_theme_seed),
                subtitle = themeSeedLabel(settings.themeSeedColor),
                modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding).clickable { seedDialog = true },
                trailing = { SeedSwatch(settings.themeSeedColor) },
            )
        }
        item {
            FrpListRow(
                icon = Icons.Rounded.Language,
                title = stringResource(R.string.settings_language),
                subtitle = languageLabel(settings.languageMode),
                modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding).clickable { languageDialog = true },
            )
        }
        item { SectionTitle(stringResource(R.string.settings_logs_section)) }
        item {
            Surface(
                modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding).fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Column(Modifier.padding(horizontal = FrpUiTokens.ScreenPadding, vertical = 12.dp)) {
                    Text("${stringResource(R.string.settings_log_retention)}: ${settings.logRetentionDays}")
                    Slider(
                        value = settings.logRetentionDays.toFloat(),
                        onValueChange = { viewModel.setRetention(it.toInt()) },
                        valueRange = 1f..30f,
                        steps = 28,
                    )
                }
            }
        }
        item { SectionTitle(stringResource(R.string.settings_about_section)) }
        item {
            FrpListRow(
                icon = Icons.Rounded.Info,
                title = stringResource(R.string.settings_app_version),
                subtitle = BuildConfig.APP_VERSION_NAME,
                modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding).clickable { openProjectPage(context) },
            )
        }
        item {
            FrpListRow(
                icon = Icons.Rounded.Memory,
                title = stringResource(R.string.settings_kernel_version),
                subtitle = kernelVersion ?: stringResource(R.string.settings_kernel_version_hint),
                modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding).clickable { viewModel.loadKernelVersion() },
            )
        }
    }

    if (seedDialog) {
        SeedColorDialog(
            selectedColor = settings.themeSeedColor,
            onSelect = viewModel::setThemeSeedColor,
            onDismiss = { seedDialog = false },
        )
    }
    if (languageDialog) {
        ChoiceDialog(
            title = stringResource(R.string.settings_language),
            onDismiss = { languageDialog = false },
            entries = listOf(
                stringResource(R.string.settings_system) to { viewModel.setLanguage(LanguageMode.System, onLanguageChanged) },
                stringResource(R.string.settings_chinese) to { viewModel.setLanguage(LanguageMode.Chinese, onLanguageChanged) },
                stringResource(R.string.settings_english) to { viewModel.setLanguage(LanguageMode.English, onLanguageChanged) },
            ),
        )
    }
}

@Composable
private fun SeedSwatch(color: Int) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(Color(color), CircleShape),
    )
}

@Composable
private fun SeedColorDialog(
    selectedColor: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_theme_seed)) },
        text = {
            Column {
                ThemeSeedOptions.forEach { option ->
                    FilterChip(
                        selected = selectedColor == option.color,
                        onClick = {
                            onSelect(option.color)
                            onDismiss()
                        },
                        label = { Text(stringResource(option.labelRes)) },
                        leadingIcon = { SeedSwatch(option.color) },
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

@Composable
private fun ToggleItem(icon: ImageVector, title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    FrpListRow(
        icon = icon,
        title = title,
        subtitle = stringResource(if (checked) R.string.settings_enabled else R.string.settings_disabled),
        modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding),
        trailing = { Switch(checked = checked, onCheckedChange = onChange) },
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
    if (!isIgnoringBatteryOptimizations(context)) {
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

private fun openAppDetails(context: Context) {
    context.startActivity(
        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}")),
    )
}

private fun openProjectPage(context: Context) {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/sky22333/frp-android")),
    )
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(PowerManager::class.java)
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

@Composable
private fun themeSeedLabel(color: Int): String =
    stringResource(ThemeSeedOptions.firstOrNull { it.color == color }?.labelRes ?: R.string.settings_seed_custom)

@Composable
private fun languageLabel(mode: LanguageMode): String =
    when (mode) {
        LanguageMode.System -> stringResource(R.string.settings_system)
        LanguageMode.Chinese -> stringResource(R.string.settings_chinese)
        LanguageMode.English -> stringResource(R.string.settings_english)
    }
