package com.sky22333.frpandroid.feature.editor

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sky22333.frpandroid.core.data.AppGraph
import com.sky22333.frpandroid.core.frp.FrpProfile
import com.sky22333.frpandroid.core.runtime.FrpForegroundService
import com.sky22333.frpandroid.core.ui.ErrorText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditorUiState(
    val profile: FrpProfile? = null,
    val name: String = "",
    val toml: String = "",
    val validationMessage: String? = null,
    val validationError: Boolean = false,
    val isBusy: Boolean = false,
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppGraph.repository(application)
    private val mutableState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = mutableState

    fun load(profileId: String) {
        viewModelScope.launch {
            val profile = repository.getProfile(profileId)
            mutableState.value = EditorUiState(
                profile = profile,
                name = profile?.name.orEmpty(),
                toml = profile?.toml.orEmpty(),
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
                if (result.isSuccess && !wasActive) {
                    FrpForegroundService.startProfile(context, updated.id)
                }
                mutableState.update {
                    it.copy(
                        profile = if (result.isSuccess || result.isAlreadyRunning) updated else profile,
                        validationMessage = if (result.isSuccess) null else result.message,
                        validationError = !result.isSuccess,
                    )
                }
            } finally {
                mutableState.update { it.copy(isBusy = false) }
            }
        }
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
    var pendingSaveRestart by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingSaveRestart) {
            viewModel.saveAndRestart(context)
        }
        pendingSaveRestart = false
    }

    fun saveAndRestartWithPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.saveAndRestart(context)
        } else {
            pendingSaveRestart = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(profileId) {
        viewModel.load(profileId)
    }

    if (state.profile == null) {
        Text(stringResource(R.string.editor_profile_missing), modifier = Modifier.padding(16.dp))
        return
    }

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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        }
    }
}
