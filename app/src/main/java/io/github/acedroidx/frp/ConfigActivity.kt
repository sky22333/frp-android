package io.github.acedroidx.frp

import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.acedroidx.frp.ui.theme.FrpTheme
import io.github.acedroidx.frp.ui.theme.*
import io.github.acedroidx.frp.ui.components.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

class ConfigActivity : ComponentActivity() {
    private val configEditText = MutableStateFlow("")
    private val isAutoStart = MutableStateFlow(false)
    private lateinit var configFile: File
    private lateinit var autoStartPreferencesKey: String
    private lateinit var preferences: SharedPreferences

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val frpConfig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.extras?.getParcelable(IntentExtraKey.FrpConfig, FrpConfig::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.extras?.getParcelable(IntentExtraKey.FrpConfig)
        }
        if (frpConfig == null) {
            Log.e("adx", "frp config is null")
            Toast.makeText(this, "frp config is null", Toast.LENGTH_SHORT).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        configFile = frpConfig.getFile(this)
        autoStartPreferencesKey = frpConfig.type.getAutoStartPreferencesKey()
        preferences = getSharedPreferences("data", MODE_PRIVATE)
        readConfig()
        readIsAutoStart()

        enableEdgeToEdge()
        setContent {
            FrpTheme {
                Scaffold(
                    topBar = {
                        ModernTopAppBar(
                            title = "配置编辑",
                            subtitle = if (::configFile.isInitialized) configFile.name.removeSuffix(".toml") else "",
                            showBackButton = true,
                            onBackClick = { closeActivity() }
                        )
                    }
                ) { contentPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(contentPadding)
                    ) {
                        MainContent()
                    }
                }
            }
        }
    }

    @Preview(showBackground = true)
    @Composable
    fun MainContent() {
        val openDialog = remember { mutableStateOf(false) }
        val configText by configEditText.collectAsStateWithLifecycle("")
        val autoStart by isAutoStart.collectAsStateWithLifecycle(false)
        
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 配置信息卡片
            item {
                ConfigInfoCard(
                    fileName = configFile.name.removeSuffix(".toml"),
                    configType = if (::configFile.isInitialized) {
                        if (configFile.parent?.endsWith("frpc") == true) "客户端配置" else "服务端配置"
                    } else "配置文件",
                    isAutoStart = autoStart,
                    onAutoStartChange = { setAutoStart(it) },
                    onRename = { openDialog.value = true }
                )
            }
            
            // 操作按钮
            item {
                ActionButtonsCard(
                    onSave = { saveConfig(); closeActivity() },
                    onCancel = { closeActivity() }
                )
            }
            
            // 配置编辑器
            item {
                ConfigEditorCard(
                    configText = configText,
                    onConfigChange = { configEditText.value = it }
                )
            }
        }
        
        if (openDialog.value) {
            RenameDialog(
                originalName = configFile.name.removeSuffix(".toml"),
                onConfirm = { newName ->
                    renameConfig("$newName.toml")
                    openDialog.value = false
                },
                onDismiss = { openDialog.value = false }
            )
        }
    }

    @Composable
    fun RenameDialog(
        originalName: String,
        onConfirm: (String) -> Unit,
        onDismiss: () -> Unit
    ) {
        var text by remember { mutableStateOf(originalName) }
        var isError by remember { mutableStateOf(false) }
        
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "重命名配置",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "请输入新的配置名称",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    OutlinedTextField(
                        value = text,
                        onValueChange = { 
                            text = it
                            isError = it.isBlank() || it.contains(Regex("[<>:\"/\\\\|?*]"))
                        },
                        label = { Text("配置名称") },
                        isError = isError,
                        supportingText = if (isError) {
                            { Text("名称不能为空且不能包含特殊字符", color = ErrorColor) }
                        } else null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { 
                        if (!isError && text.isNotBlank()) {
                            onConfirm(text)
                        }
                    },
                    enabled = !isError && text.isNotBlank(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        )
    }

    fun readConfig() {
        try {
            if (configFile.exists()) {
                configEditText.value = configFile.readText()
            } else {
                Log.e("ConfigActivity", "config file does not exist: ${configFile.absolutePath}")
                Toast.makeText(this, "配置文件不存在", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("ConfigActivity", "Error reading config file", e)
            Toast.makeText(this, "读取配置文件失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun saveConfig() {
        try {
            configFile.writeText(configEditText.value)
            Toast.makeText(this, "配置保存成功", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("ConfigActivity", "Error saving config file", e)
            Toast.makeText(this, "保存配置失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun renameConfig(newName: String) {
        try {
            val originAutoStart = isAutoStart.value
            setAutoStart(false)
            
            val newFile = File(configFile.parent, newName)
            val renamed = configFile.renameTo(newFile)
            
            if (renamed) {
                configFile = newFile
                setAutoStart(originAutoStart)
                Toast.makeText(this, "重命名成功", Toast.LENGTH_SHORT).show()
            } else {
                setAutoStart(originAutoStart)
                Toast.makeText(this, "重命名失败", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("ConfigActivity", "Error renaming config", e)
            Toast.makeText(this, "重命名失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun readIsAutoStart() {
        isAutoStart.value =
            preferences.getStringSet(autoStartPreferencesKey, emptySet())?.contains(configFile.name)
                ?: false
    }

    fun setAutoStart(value: Boolean) {
        val editor = preferences.edit()
        val set = preferences.getStringSet(autoStartPreferencesKey, emptySet())?.toMutableSet()
        if (value) {
            set?.add(configFile.name)
        } else {
            set?.remove(configFile.name)
        }
        editor.putStringSet(autoStartPreferencesKey, set)
        editor.apply()
        isAutoStart.value = value
    }

    fun closeActivity() {
        setResult(RESULT_OK)
        finish()
    }
    
    @Composable
    fun ConfigInfoCard(
        fileName: String,
        configType: String,
        isAutoStart: Boolean,
        onAutoStartChange: (Boolean) -> Unit,
        onRename: () -> Unit
    ) {
        ModernCard {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = configType,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    ActionButton(
                        icon = Icons.Default.Edit,
                        contentDescription = "重命名",
                        onClick = onRename
                    )
                }
                
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "开机自启",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "系统启动时自动运行此配置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isAutoStart,
                        onCheckedChange = onAutoStartChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SuccessColor,
                            checkedTrackColor = SuccessColor.copy(alpha = 0.5f)
                        )
                    )
                }
            }
        }
    }
    
    @Composable
    fun ActionButtonsCard(
        onSave: () -> Unit,
        onCancel: () -> Unit
    ) {
        ModernCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("保存配置")
                }
                
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("取消")
                }
            }
        }
    }
    
    @Composable
    fun ConfigEditorCard(
        configText: String,
        onConfigChange: (String) -> Unit
    ) {
        ModernCard {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "配置编辑器",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                OutlinedTextField(
                    value = configText,
                    onValueChange = onConfigChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 300.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    placeholder = {
                        Text(
                            text = "请输入TOML格式的配置内容...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
                
                Text(
                    text = "提示：请确保配置格式正确，错误的配置可能导致服务无法启动",
                    style = MaterialTheme.typography.bodySmall,
                    color = WarningColor,
                    modifier = Modifier
                        .background(
                            WarningColor.copy(alpha = 0.1f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(8.dp)
                )
            }
        }
    }
}