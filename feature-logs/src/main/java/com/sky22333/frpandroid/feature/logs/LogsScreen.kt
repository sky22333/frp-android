package com.sky22333.frpandroid.feature.logs

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

private data class LogsTextFilter(val instanceId: String, val keyword: String)

private data class LogsImmediateFilter(
    val type: String,
    val level: String,
    val paused: Boolean,
)

class LogsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppGraph.repository(application)
    private val filter = MutableStateFlow(LogsFilter())
    private val frozenLogs = MutableStateFlow<List<FrpLog>>(emptyList())
    private val latestLogs = MutableStateFlow<List<FrpLog>>(emptyList())

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private val logs = combine(
        filter
            .map { LogsTextFilter(it.instanceId, it.keyword) }
            .distinctUntilChanged()
            .debounce(300),
        filter
            .map { LogsImmediateFilter(it.type, it.level, it.paused) }
            .distinctUntilChanged(),
    ) { text, immediate ->
        LogsFilter(
            instanceId = text.instanceId,
            keyword = text.keyword,
            type = immediate.type,
            level = immediate.level,
            paused = immediate.paused,
        )
    }.flatMapLatest { current ->
        if (current.paused) {
            flowOf(frozenLogs.value)
        } else {
            repository.observeLogs(
                instanceId = current.instanceId,
                type = current.type,
                level = current.level,
                keyword = current.keyword,
                limit = 150,
            )
        }
    }

    val uiState: StateFlow<LogsUiState> = combine(filter, logs) { current, currentLogs ->
        if (!current.paused) latestLogs.value = currentLogs
        LogsUiState(current, currentLogs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogsUiState())

    fun setInstanceId(value: String) = filter.update { it.copy(instanceId = value) }
    fun setKeyword(value: String) = filter.update { it.copy(keyword = value) }
    fun setLevel(value: String) = filter.update { it.copy(level = value) }
    fun setType(value: String) = filter.update { it.copy(type = value) }
    fun setPaused(value: Boolean) {
        if (value) frozenLogs.value = latestLogs.value
        filter.update { it.copy(paused = value) }
    }
    fun clearLogs() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            repository.clearLogs()
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
    val copiedLogsText = stringResource(R.string.logs_copied_logs)
    val noLogsText = stringResource(R.string.logs_no_logs_to_copy)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
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
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf("", "frp", "client", "server").forEach { type ->
                        FilterChip(
                            selected = state.filter.type == type,
                            onClick = { viewModel.setType(type) },
                            label = { Text(type.ifBlank { stringResource(R.string.logs_all) }) },
                        )
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf("", "info", "warn", "error").forEach { level ->
                        FilterChip(
                            selected = state.filter.level == level,
                            onClick = { viewModel.setLevel(level) },
                            label = { Text(level.ifBlank { stringResource(R.string.logs_all) }) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.logs_pause_scroll))
                        Switch(checked = state.filter.paused, onCheckedChange = viewModel::setPaused)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            val count = copyLogs(context, state.logs)
                            snackbarScope.launch {
                                snackbarHostState.showSnackbar(
                                    if (count > 0) copiedLogsText.format(count) else noLogsText,
                                )
                            }
                        },
                        enabled = state.logs.isNotEmpty(),
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.logs_copy_logs))
                    }
                    IconButton(
                        onClick = viewModel::clearLogs,
                        enabled = state.logs.isNotEmpty(),
                    ) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = stringResource(R.string.logs_clear))
                    }
                }
            }
            if (state.logs.isEmpty()) {
                Text(stringResource(R.string.logs_empty), modifier = Modifier.padding(16.dp))
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                itemsIndexed(
                    state.logs,
                    key = { index, log -> if (log.uid > 0) log.uid else "${log.time}-${log.instanceId}-$index" },
                    contentType = { _, _ -> "log" },
                ) { _, log ->
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Text(text = log.message, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${log.level.uppercase()} · ${log.type}/${log.instanceId.ifBlank { "-" }} · ${timeFormatter.format(Date(log.time))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (log.level == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private fun copyLogs(context: Context, logs: List<FrpLog>): Int {
    if (logs.isEmpty()) return 0
    val text = logs.joinToString("\n") { it.message }
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("frp logs", text))
    return logs.size
}
