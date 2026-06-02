package com.sky22333.frpandroid.feature.editor

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sky22333.frpandroid.core.data.AppGraph
import com.sky22333.frpandroid.core.frp.FrpProfile
import com.sky22333.frpandroid.core.frp.TomlValidator
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
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppGraph.repository(application)
    private val validator = TomlValidator()
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
        val result = validator.validate(mutableState.value.toml)
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
        viewModelScope.launch {
            val updated = profile.copy(name = state.name, toml = state.toml)
            repository.upsertProfile(updated)
            mutableState.update { it.copy(profile = updated) }
        }
    }

    fun saveAndRestart(context: Context) {
        val state = mutableState.value
        val profile = state.profile ?: return
        viewModelScope.launch {
            val updated = profile.copy(name = state.name, toml = state.toml)
            repository.upsertProfile(updated)
            repository.stop(updated)
            FrpForegroundService.startProfile(context, updated.id)
            mutableState.update { it.copy(profile = updated) }
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
            FilledTonalButton(onClick = { viewModel.validate(validText) }) {
                Text(stringResource(R.string.editor_validate))
            }
            FilledTonalButton(onClick = viewModel::save) {
                Text(stringResource(R.string.editor_save))
            }
            Button(onClick = { viewModel.saveAndRestart(context) }) {
                Text(stringResource(R.string.editor_save_restart))
            }
        }
    }
}
