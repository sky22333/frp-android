package com.sky22333.frpandroid.feature.logs

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.sky22333.frpandroid.core.frp.FrpLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogsFilter(
    val instanceId: String = "",
    val type: String = "",
    val level: String = "",
    val keyword: String = "",
    val paused: Boolean = false,
)

data class LogsUiState(
    val filter: LogsFilter = LogsFilter(),
    val logs: List<FrpLog> = emptyList(),
)

class LogsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppGraph.repository(application)
    private val appContext = application.applicationContext
    private val filter = MutableStateFlow(LogsFilter())
    private val frozenLogs = MutableStateFlow<List<FrpLog>>(emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<LogsUiState> = filter.flatMapLatest { current ->
        repository.observeLogs(
            instanceId = current.instanceId,
            type = current.type,
            level = current.level,
            keyword = current.keyword,
            limit = 300,
        ).combine(filter) { logs, latestFilter ->
            if (!latestFilter.paused) frozenLogs.value = logs
            LogsUiState(latestFilter, if (latestFilter.paused) frozenLogs.value else logs)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogsUiState())

    fun setInstanceId(value: String) = filter.update { it.copy(instanceId = value) }
    fun setKeyword(value: String) = filter.update { it.copy(keyword = value) }
    fun setLevel(value: String) = filter.update { it.copy(level = value) }
    fun setType(value: String) = filter.update { it.copy(type = value) }
    fun setPaused(value: Boolean) = filter.update { it.copy(paused = value) }
    fun showErrorsOnly() = filter.update { it.copy(level = "error") }
    fun clearLogs() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            repository.clearLogs()
        }
    }
    fun exportLogs(uri: Uri, logs: List<FrpLog>) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            writeLogs(appContext, uri, formatLogs(logs))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogsScreen(
    viewModel: LogsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val timeFormatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    var pendingExportLogs by remember { mutableStateOf<List<FrpLog>>(emptyList()) }
    var showClearDialog by remember { mutableStateOf(false) }
    val copiedErrorsText = stringResource(R.string.logs_copied_errors)
    val noErrorsText = stringResource(R.string.logs_no_errors)
    val exportStartedText = stringResource(R.string.logs_export_started)
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            viewModel.exportLogs(uri, pendingExportLogs)
            snackbarScope.launch { snackbarHostState.showSnackbar(exportStartedText) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.filter.instanceId,
                        onValueChange = viewModel::setInstanceId,
                        label = { Text(stringResource(R.string.logs_instance)) },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = state.filter.keyword,
                        onValueChange = viewModel::setKeyword,
                        label = { Text(stringResource(R.string.logs_keyword)) },
                        modifier = Modifier.weight(1f),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("", "frp", "client", "server").forEach { type ->
                        FilterChip(
                            selected = state.filter.type == type,
                            onClick = { viewModel.setType(type) },
                            label = { Text(type.ifBlank { stringResource(R.string.logs_all) }) },
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("", "info", "warn", "error").forEach { level ->
                        FilterChip(
                            selected = state.filter.level == level,
                            onClick = { viewModel.setLevel(level) },
                            label = { Text(level.ifBlank { stringResource(R.string.logs_all) }) },
                        )
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.logs_pause_scroll))
                    Switch(checked = state.filter.paused, onCheckedChange = viewModel::setPaused)
                    FilledTonalButton(
                        onClick = {
                            val count = copyErrors(context, state.logs)
                            snackbarScope.launch {
                                snackbarHostState.showSnackbar(
                                    if (count > 0) copiedErrorsText.format(count) else noErrorsText,
                                )
                            }
                        },
                    ) {
                        Text(stringResource(R.string.logs_copy_errors))
                    }
                    FilledTonalButton(onClick = viewModel::showErrorsOnly) {
                        Text(stringResource(R.string.logs_errors_only))
                    }
                    FilledTonalButton(
                        onClick = { showClearDialog = true },
                        enabled = state.logs.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.logs_clear))
                    }
                    FilledTonalButton(
                        onClick = {
                            pendingExportLogs = state.logs
                            exportLauncher.launch("frp-android-logs.txt")
                        },
                        enabled = state.logs.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.logs_export))
                    }
                }
            }
            if (state.logs.isEmpty()) {
                Text(stringResource(R.string.logs_empty), modifier = Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                itemsIndexed(
                    state.logs,
                    key = { index, log -> if (log.uid > 0) log.uid else "${log.time}-${log.instanceId}-$index" },
                ) { _, log ->
                    ListItem(
                        headlineContent = { Text("[${log.level}] ${log.message}") },
                        supportingContent = {
                            Text("${log.type}/${log.instanceId.ifBlank { "-" }} · ${timeFormatter.format(Date(log.time))}")
                        },
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.logs_clear_confirm_title)) },
            text = { Text(stringResource(R.string.logs_clear_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearLogs()
                        showClearDialog = false
                    },
                ) {
                    Text(stringResource(R.string.logs_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(androidx.compose.ui.res.stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

private fun copyErrors(context: Context, logs: List<FrpLog>): Int {
    val errors = logs.filter { it.level == "error" }
    if (errors.isEmpty()) return 0
    val text = errors.joinToString("\n") { it.message }
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("frp errors", text))
    return errors.size
}

private fun formatLogs(logs: List<FrpLog>): String =
    logs.joinToString("\n") { log ->
        "${log.time} ${log.type}/${log.instanceId} [${log.level}] ${log.message}"
    }

private fun writeLogs(context: Context, uri: Uri, text: String) {
    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
        writer.write(text)
    }
}
