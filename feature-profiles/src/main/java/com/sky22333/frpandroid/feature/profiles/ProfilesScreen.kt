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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class ProfilesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppGraph.repository(application)
    private val appContext = application.applicationContext
    val profiles: StateFlow<List<FrpProfile>> = repository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
        viewModelScope.launch { repository.deleteProfile(profile.id) }
    }

    fun importToml(uri: Uri) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val toml = readToml(appContext, uri)
            create(detectType(toml), toml)
        }
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
    var importDialog by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<FrpProfile?>(null) }
    val documentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.importToml(uri)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { viewModel.create(FrpType.Client) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.profiles_new_frpc))
            }
            FilledTonalButton(onClick = { viewModel.create(FrpType.Server) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.profiles_new_frps))
            }
            IconButton(onClick = { documentLauncher.launch(arrayOf("text/*", "application/octet-stream")) }) {
                Icon(Icons.Rounded.UploadFile, contentDescription = stringResource(R.string.profiles_import))
            }
            FilledTonalButton(onClick = { importDialog = true }) {
                Text(stringResource(R.string.profiles_paste_import))
            }
        }

        if (profiles.isEmpty()) {
            Text(stringResource(R.string.profiles_empty), modifier = Modifier.padding(16.dp))
        }

        LazyColumn {
            items(profiles, key = { it.id }) { profile ->
                ListItem(
                    modifier = Modifier.clickable { onEditProfile(profile.id) },
                    leadingContent = {
                        Icon(
                            if (profile.type == FrpType.Client) Icons.Rounded.CloudSync else Icons.Rounded.Dns,
                            contentDescription = null,
                        )
                    },
                    headlineContent = { Text(profile.name) },
                    supportingContent = { Text(profile.type.name) },
                    trailingContent = {
                        Row {
                            Switch(
                                checked = profile.autoStart,
                                onCheckedChange = { viewModel.toggleAutoStart(profile, it) },
                            )
                            IconButton(onClick = { deleteCandidate = profile }) {
                                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.profiles_delete))
                            }
                        }
                    },
                )
            }
        }
    }

    if (importDialog) {
        ImportTomlDialog(
            onDismiss = { importDialog = false },
            onImport = { type, toml ->
                viewModel.create(type, toml)
                importDialog = false
            },
        )
    }

    deleteCandidate?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(R.string.profiles_delete)) },
            text = { Text(profile.name) },
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

private fun readToml(context: Context, uri: Uri): String =
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()

private fun detectType(toml: String): FrpType =
    if (toml.contains("bindPort") && !toml.contains("serverAddr")) FrpType.Server else FrpType.Client

@Composable
private fun ImportTomlDialog(
    onDismiss: () -> Unit,
    onImport: (FrpType, String) -> Unit,
) {
    var toml by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(FrpType.Client) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profiles_import)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { type = FrpType.Client }) { Text("frpc") }
                    FilledTonalButton(onClick = { type = FrpType.Server }) { Text("frps") }
                }
                TextField(value = toml, onValueChange = { toml = it }, minLines = 8)
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(type, toml) }) {
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
