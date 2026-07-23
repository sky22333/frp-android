package com.sky22333.frpandroid.feature.profiles

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sky22333.frpandroid.core.data.AppGraph
import com.sky22333.frpandroid.core.frp.FrpProfile
import com.sky22333.frpandroid.core.frp.FrpType
import com.sky22333.frpandroid.core.frp.TomlValidator
import com.sky22333.frpandroid.core.runtime.FrpRetryWorker
import com.sky22333.frpandroid.core.ui.EmptyState
import com.sky22333.frpandroid.core.ui.ErrorText
import com.sky22333.frpandroid.core.ui.FrpListRow
import com.sky22333.frpandroid.core.ui.FrpUiTokens
import com.sky22333.frpandroid.core.ui.profileCardSharedBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ProfilesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppGraph.repository(application)
    private val appContext = application.applicationContext
    val profiles: StateFlow<List<FrpProfile>> = repository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val mutableBusyProfileIds = MutableStateFlow<Set<String>>(emptySet())
    val busyProfileIds: StateFlow<Set<String>> = mutableBusyProfileIds
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError
    private val mutableImportDraft = MutableStateFlow<String?>(null)
    val importDraft: StateFlow<String?> = mutableImportDraft

    fun create(type: FrpType, toml: String? = null) {
        val id = UUID.randomUUID().toString()
        val name = if (type == FrpType.Client) "frpc-$id".take(13) else "frps-$id".take(13)
        viewModelScope.launch {
            repository.upsertProfile(
                FrpProfile(
                    id = id,
                    name = name,
                    type = type,
                    toml = toml ?: defaultToml(type),
                    autoStart = false,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun toggleAutoStart(profile: FrpProfile, enabled: Boolean) {
        viewModelScope.launch { repository.upsertProfile(profile.copy(autoStart = enabled)) }
    }

    fun delete(profile: FrpProfile) {
        if (profile.id in mutableBusyProfileIds.value) return
        mutableBusyProfileIds.update { it + profile.id }
        mutableError.value = null
        viewModelScope.launch {
            try {
                FrpRetryWorker.cancel(appContext, profile.id)
                val result = repository.deleteProfile(profile.id)
                if (!result.isSuccess) {
                    mutableError.value = result.message
                }
            } finally {
                mutableBusyProfileIds.update { it - profile.id }
            }
        }
    }

    fun openPasteImport() {
        mutableImportDraft.value = ""
    }

    fun openFileImport(uri: Uri) {
        viewModelScope.launch {
            mutableImportDraft.value = withContext(Dispatchers.IO) { readToml(appContext, uri) }
        }
    }

    fun dismissImport() {
        mutableImportDraft.value = null
    }

    fun confirmImport(type: FrpType, toml: String) {
        create(type, toml)
        dismissImport()
    }

    private fun defaultToml(type: FrpType): String =
        if (type == FrpType.Client) {
            """
            serverAddr = "127.0.0.1"
            serverPort = 7000

            [[proxies]]
            name = "nas-web"
            type = "tcp"
            localIP = "127.0.0.1"
            localPort = 8080
            remotePort = 8080
            """.trimIndent()
        } else {
            """
            bindPort = 7000
            """.trimIndent()
        }
}

@Composable
fun ProfilesScreen(
    viewModel: ProfilesViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onEditProfile: (String) -> Unit,
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val busyProfileIds by viewModel.busyProfileIds.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val importDraft by viewModel.importDraft.collectAsStateWithLifecycle()
    var newDialog by remember { mutableStateOf(false) }
    var importChoiceDialog by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<FrpProfile?>(null) }
    val documentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.openFileImport(uri)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(FrpUiTokens.ScreenPadding).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FrpUiTokens.ListSpacing),
        ) {
            Button(onClick = { newDialog = true }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.profiles_new))
            }
            FilledTonalButton(onClick = { importChoiceDialog = true }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.profiles_import))
            }
        }

        if (profiles.isEmpty()) {
            EmptyState(text = stringResource(R.string.profiles_empty))
        }
        error?.let { message ->
            ErrorText(
                text = message,
                modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding, vertical = FrpUiTokens.ListSpacing),
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(FrpUiTokens.ListSpacing),
        ) {
            items(profiles, key = { it.id }) { profile ->
                val busy = profile.id in busyProfileIds
                FrpListRow(
                    modifier = Modifier
                        .padding(horizontal = FrpUiTokens.ScreenPadding)
                        .profileCardSharedBounds(profile.id)
                        .clickable(enabled = !busy) { onEditProfile(profile.id) },
                    icon = if (profile.type == FrpType.Client) Icons.Rounded.CloudSync else Icons.Rounded.Dns,
                    title = profile.name,
                    subtitle = "${profile.type.name} · ${
                        stringResource(
                            if (profile.autoStart) {
                                R.string.profiles_auto_start
                            } else {
                                R.string.profiles_manual_start
                            },
                        )
                    }",
                    trailing = {
                        Row {
                            Switch(
                                checked = profile.autoStart,
                                enabled = !busy,
                                onCheckedChange = { viewModel.toggleAutoStart(profile, it) },
                            )
                            IconButton(onClick = { deleteCandidate = profile }, enabled = !busy) {
                                if (busy) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.profiles_delete))
                                }
                            }
                        }
                    },
                )
            }
        }
    }

    if (newDialog) {
        ProfileActionDialog(
            title = stringResource(R.string.profiles_new),
            onDismiss = { newDialog = false },
            entries = listOf(
                stringResource(R.string.profiles_new_frpc) to {
                    viewModel.create(FrpType.Client)
                    newDialog = false
                },
                stringResource(R.string.profiles_new_frps) to {
                    viewModel.create(FrpType.Server)
                    newDialog = false
                },
            ),
        )
    }

    if (importChoiceDialog) {
        ProfileActionDialog(
            title = stringResource(R.string.profiles_import),
            onDismiss = { importChoiceDialog = false },
            entries = listOf(
                stringResource(R.string.profiles_import_file) to {
                    importChoiceDialog = false
                    documentLauncher.launch(arrayOf("text/*", "application/octet-stream"))
                },
                stringResource(R.string.profiles_paste_import) to {
                    importChoiceDialog = false
                    viewModel.openPasteImport()
                },
            ),
        )
    }

    importDraft?.let { draft ->
        ImportTomlDialog(
            initialToml = draft,
            onDismiss = viewModel::dismissImport,
            onImport = viewModel::confirmImport,
        )
    }

    deleteCandidate?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(R.string.profiles_delete)) },
            text = { Text(stringResource(R.string.profiles_delete_message, profile.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(profile)
                        deleteCandidate = null
                    },
                ) {
                    Text(stringResource(R.string.profiles_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text(androidx.compose.ui.res.stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ProfileActionDialog(
    title: String,
    onDismiss: () -> Unit,
    entries: List<Pair<String, () -> Unit>>,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                entries.forEach { (label, action) ->
                    FilledTonalButton(onClick = action, modifier = Modifier.fillMaxWidth()) {
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(android.R.string.cancel))
            }
        },
    )
}

private fun readToml(context: Context, uri: Uri): String =
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()

@Composable
private fun ImportTomlDialog(
    initialToml: String,
    onDismiss: () -> Unit,
    onImport: (FrpType, String) -> Unit,
) {
    val validator = remember { TomlValidator() }
    var toml by remember(initialToml) { mutableStateOf(initialToml) }
    var type by remember(initialToml) {
        mutableStateOf(validator.suggestType(initialToml) ?: FrpType.Client)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profiles_import)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == FrpType.Client,
                        onClick = { type = FrpType.Client },
                        label = { Text("frpc") },
                    )
                    FilterChip(
                        selected = type == FrpType.Server,
                        onClick = { type = FrpType.Server },
                        label = { Text("frps") },
                    )
                }
                TextField(
                    value = toml,
                    onValueChange = { value ->
                        toml = value
                        validator.suggestType(value)?.let { type = it }
                    },
                    minLines = 8,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(type, toml) },
                enabled = toml.isNotBlank(),
            ) {
                Text(stringResource(R.string.profiles_import))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(android.R.string.cancel))
            }
        },
    )
}
