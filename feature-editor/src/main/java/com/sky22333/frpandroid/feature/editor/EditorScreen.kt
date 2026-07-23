package com.sky22333.frpandroid.feature.editor

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sky22333.frpandroid.core.data.AppGraph
import com.sky22333.frpandroid.core.data.TlsFileInfo
import com.sky22333.frpandroid.core.data.TlsFileRole
import com.sky22333.frpandroid.core.frp.FrpProfile
import com.sky22333.frpandroid.core.runtime.FrpForegroundService
import com.sky22333.frpandroid.core.runtime.FrpRuntimePermissions
import com.sky22333.frpandroid.core.ui.ErrorText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EditorUiState(
    val profile: FrpProfile? = null,
    val name: String = "",
    val toml: String = "",
    val validationMessage: String? = null,
    val validationError: Boolean = false,
    val isBusy: Boolean = false,
    val tlsFiles: List<TlsFileInfo> = emptyList(),
    val tlsMessage: String? = null,
    val tlsPathToCopy: String? = null,
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppGraph.repository(application)
    private val mutableState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = mutableState

    fun load(profileId: String) {
        viewModelScope.launch {
            val profile = withContext(Dispatchers.IO) { repository.getProfile(profileId) }
            val tlsFiles = withContext(Dispatchers.IO) {
                profile?.let { repository.getTlsFiles(it.id) }.orEmpty()
            }
            mutableState.value = EditorUiState(
                profile = profile,
                name = profile?.name.orEmpty(),
                toml = profile?.toml.orEmpty(),
                tlsFiles = tlsFiles,
            )
        }
    }

    fun setName(name: String) {
        mutableState.update { it.copy(name = name) }
    }

    fun setToml(toml: String) {
        mutableState.update { it.copy(toml = toml) }
    }

    fun validate(successMessage: String) {
        val result = repository.validateToml(mutableState.value.toml)
        mutableState.update {
            it.copy(
                validationMessage = if (result.isSuccess) successMessage else result.message,
                validationError = !result.isSuccess,
            )
        }
    }

    fun save() {
        val state = mutableState.value
        val profile = state.profile ?: return
        if (state.isBusy) return
        viewModelScope.launch {
            mutableState.update { it.copy(isBusy = true) }
            try {
                val updated = profile.copy(name = state.name, toml = state.toml)
                repository.upsertProfile(updated)
                mutableState.update { it.copy(profile = updated) }
            } finally {
                mutableState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun saveAndRestart(context: Context) {
        val state = mutableState.value
        val profile = state.profile ?: return
        if (state.isBusy) return
        viewModelScope.launch {
            mutableState.update { it.copy(isBusy = true) }
            try {
                val updated = profile.copy(name = state.name, toml = state.toml)
                val wasActive = repository.isProfileActive(updated.id)
                val result = repository.saveAndRestart(updated)
                var launchError: String? = null
                if (result.isSuccess && !wasActive) {
                    runCatching {
                        FrpForegroundService.startProfile(context, updated.id)
                    }.onFailure {
                        launchError = it.message
                    }
                }
                mutableState.update {
                    it.copy(
                        profile = if ((result.isSuccess && launchError == null) || result.isAlreadyRunning) updated else profile,
                        validationMessage = launchError ?: if (result.isSuccess) null else result.message,
                        validationError = launchError != null || !result.isSuccess,
                    )
                }
            } finally {
                mutableState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun importTlsFile(uri: Uri, role: TlsFileRole, successMessage: String, openFailedMessage: String) {
        val profile = mutableState.value.profile ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.let { input ->
                        repository.importTlsFile(profile.id, role, input)
                    } ?: error(openFailedMessage)
                }
            }
            val tlsFiles = withContext(Dispatchers.IO) { repository.getTlsFiles(profile.id) }
            mutableState.update {
                it.copy(
                    tlsFiles = tlsFiles,
                    tlsMessage = result.fold(
                        onSuccess = { successMessage },
                        onFailure = { error -> error.message ?: openFailedMessage },
                    ),
                    tlsPathToCopy = result.getOrNull()?.path,
                )
            }
        }
    }

    fun deleteTlsFile(role: TlsFileRole, successMessage: String, failureMessage: String) {
        val profile = mutableState.value.profile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val deleted = repository.deleteTlsFile(profile.id, role)
            mutableState.update {
                it.copy(
                    tlsFiles = repository.getTlsFiles(profile.id),
                    tlsMessage = if (deleted) successMessage else failureMessage,
                )
            }
        }
    }

    fun consumeTlsMessage() {
        mutableState.update { it.copy(tlsMessage = null, tlsPathToCopy = null) }
    }
}

@Composable
fun EditorScreen(
    profileId: String,
    viewModel: EditorViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val validText = stringResource(R.string.editor_valid)
    val tlsImportedText = stringResource(R.string.editor_tls_imported)
    val tlsOpenFailedText = stringResource(R.string.editor_tls_open_failed)
    val tlsDeletedText = stringResource(R.string.editor_tls_deleted)
    val tlsDeleteFailedText = stringResource(R.string.editor_tls_delete_failed)
    val tlsPathCopiedText = stringResource(R.string.editor_tls_path_copied)
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    var pendingSaveRestart by remember { mutableStateOf(false) }
    var tlsDialog by remember { mutableStateOf(false) }
    var pendingTlsUri by remember { mutableStateOf<Uri?>(null) }
    var deleteTlsRole by remember { mutableStateOf<TlsFileRole?>(null) }
    val runtimePermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it } && pendingSaveRestart) {
            viewModel.saveAndRestart(context)
        }
        pendingSaveRestart = false
    }
    val tlsFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingTlsUri = uri
    }

    fun saveAndRestartWithPermission() {
        val missing = FrpRuntimePermissions.missingPermissions(context)
        if (missing.isEmpty()) {
            viewModel.saveAndRestart(context)
        } else {
            pendingSaveRestart = true
            runtimePermissionsLauncher.launch(missing)
        }
    }

    LaunchedEffect(profileId) {
        viewModel.load(profileId)
    }
    LaunchedEffect(state.tlsMessage) {
        state.tlsMessage?.let { message ->
            state.tlsPathToCopy?.let { path -> copyText(context, path) }
            snackbarHostState.showSnackbar(message)
            viewModel.consumeTlsMessage()
        }
    }

    if (state.profile == null) {
        Text(stringResource(R.string.editor_profile_missing), modifier = Modifier.padding(16.dp))
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text(stringResource(R.string.editor_name)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.toml,
                onValueChange = viewModel::setToml,
                label = { Text(stringResource(R.string.editor_title)) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                minLines = 14,
            )
            state.validationMessage?.let { message ->
                if (state.validationError) {
                    ErrorText(message)
                } else {
                    Text(message, color = MaterialTheme.colorScheme.primary)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = { viewModel.validate(validText) }, enabled = !state.isBusy) {
                    Text(stringResource(R.string.editor_validate))
                }
                FilledTonalButton(onClick = viewModel::save, enabled = !state.isBusy) {
                    Text(stringResource(R.string.editor_save))
                }
                Button(onClick = { saveAndRestartWithPermission() }, enabled = !state.isBusy) {
                    if (state.isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.editor_save_restart))
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { tlsDialog = true }) {
                    Icon(
                        imageVector = Icons.Rounded.Security,
                        contentDescription = stringResource(R.string.editor_tls_files),
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (tlsDialog) {
        TlsFilesDialog(
            files = state.tlsFiles,
            onDismiss = { tlsDialog = false },
            onImport = { tlsFileLauncher.launch(arrayOf("*/*")) },
            onCopyPath = { file ->
                copyText(context, file.path)
                snackbarScope.launch {
                    snackbarHostState.showSnackbar(tlsPathCopiedText)
                }
            },
            onDelete = { file ->
                if (file.role == TlsFileRole.PrivateKey) {
                    deleteTlsRole = file.role
                } else {
                    viewModel.deleteTlsFile(file.role, tlsDeletedText, tlsDeleteFailedText)
                }
            },
        )
    }

    pendingTlsUri?.let { uri ->
        TlsRoleDialog(
            onDismiss = { pendingTlsUri = null },
            onSelect = { role ->
                pendingTlsUri = null
                viewModel.importTlsFile(uri, role, tlsImportedText, tlsOpenFailedText)
            },
        )
    }

    deleteTlsRole?.let { role ->
        AlertDialog(
            onDismissRequest = { deleteTlsRole = null },
            title = { Text(stringResource(R.string.editor_tls_delete_private_key)) },
            text = { Text(stringResource(R.string.editor_tls_delete_private_key_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTlsRole = null
                        viewModel.deleteTlsFile(role, tlsDeletedText, tlsDeleteFailedText)
                    },
                ) {
                    Text(stringResource(R.string.editor_tls_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTlsRole = null }) {
                    Text(androidx.compose.ui.res.stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun TlsFilesDialog(
    files: List<TlsFileInfo>,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
    onCopyPath: (TlsFileInfo) -> Unit,
    onDelete: (TlsFileInfo) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.editor_tls_files), modifier = Modifier.weight(1f))
                IconButton(onClick = onImport) {
                    Icon(
                        imageVector = Icons.Rounded.FileOpen,
                        contentDescription = stringResource(R.string.editor_tls_import),
                    )
                }
            }
        },
        text = {
            if (files.isEmpty()) {
                Text(
                    text = stringResource(R.string.editor_tls_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column {
                    files.forEachIndexed { index, file ->
                        TlsFileRow(file, onCopyPath, onDelete)
                        if (index < files.lastIndex) HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.editor_tls_close))
            }
        },
    )
}

@Composable
private fun TlsFileRow(
    file: TlsFileInfo,
    onCopyPath: (TlsFileInfo) -> Unit,
    onDelete: (TlsFileInfo) -> Unit,
) {
    val icon = when (file.role) {
        TlsFileRole.TrustedCa -> Icons.Rounded.VerifiedUser
        TlsFileRole.Certificate -> Icons.Rounded.Badge
        TlsFileRole.PrivateKey -> Icons.Rounded.Key
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(tlsRoleLabel(file.role), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onCopyPath(file) }) {
            Icon(
                imageVector = Icons.Rounded.ContentCopy,
                contentDescription = stringResource(R.string.editor_tls_copy_path),
            )
        }
        IconButton(onClick = { onDelete(file) }) {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = stringResource(R.string.editor_tls_delete),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun TlsRoleDialog(
    onDismiss: () -> Unit,
    onSelect: (TlsFileRole) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_tls_choose_role)) },
        text = {
            Column {
                TlsFileRole.entries.forEach { role ->
                    TextButton(
                        onClick = { onSelect(role) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(tlsRoleLabel(role), modifier = Modifier.fillMaxWidth())
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

@Composable
private fun tlsRoleLabel(role: TlsFileRole): String =
    stringResource(
        when (role) {
            TlsFileRole.TrustedCa -> R.string.editor_tls_trusted_ca
            TlsFileRole.Certificate -> R.string.editor_tls_certificate
            TlsFileRole.PrivateKey -> R.string.editor_tls_private_key
        },
    )

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("TLS file path", text))
}
