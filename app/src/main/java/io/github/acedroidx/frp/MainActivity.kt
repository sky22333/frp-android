package io.github.acedroidx.frp

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.acedroidx.frp.ui.theme.FrpTheme
import io.github.acedroidx.frp.ui.theme.*
import io.github.acedroidx.frp.ui.components.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class MainActivity : ComponentActivity() {
    private val isStartup = MutableStateFlow(false)
    private val logText = MutableStateFlow("")
    private val frpcConfigList = MutableStateFlow<List<FrpConfig>>(emptyList())
    private val frpsConfigList = MutableStateFlow<List<FrpConfig>>(emptyList())
    private val runningConfigList = MutableStateFlow<List<FrpConfig>>(emptyList())
    private val showAddDialog = MutableStateFlow(false)
    private val isLoading = MutableStateFlow(false)
    private val errorMessage = MutableStateFlow<String?>(null)
    
    // 添加协程作用域管理
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var preferences: SharedPreferences

    private lateinit var mService: ShellService
    private var mBound: Boolean = false

    /** Defines callbacks for service binding, passed to bindService()  */
    private val connection = object : ServiceConnection {

        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            val binder = service as ShellService.LocalBinder
            mService = binder.getService()
            mBound = true

            // 使用Activity的协程作用域，确保在Activity销毁时自动取消
            activityScope.launch {
                mService.processThreads.collect { processThreads ->
                    runningConfigList.value = processThreads.keys.toList()
                }
            }
            activityScope.launch {
                mService.logText.collect {
                    logText.value = it
                }
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            mBound = false
        }
    }

    private val configActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { activityResult ->
            updateConfigList()
        }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        preferences = getSharedPreferences("data", MODE_PRIVATE)
        isStartup.value = preferences.getBoolean(PreferencesKey.AUTO_START, false)

        checkConfig()
        updateConfigList()
        checkNotificationPermission()
        createBGNotificationChannel()

        enableEdgeToEdge()
        setContent {
            FrpTheme {
                Scaffold(
                    topBar = {
                        ModernTopAppBar(
                            title = "frp for Android",
                            subtitle = "v${BuildConfig.VERSION_NAME}"
                        )
                    },
                    floatingActionButton = {
                        FloatingActionButton(
                            onClick = { showAddDialog.value = true },
                            containerColor = Primary,
                            contentColor = androidx.compose.ui.graphics.Color.White
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "添加配置"
                            )
                        }
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

        if (!mBound) {
            val intent = Intent(this, ShellService::class.java)
            bindService(intent, connection, BIND_AUTO_CREATE)
        }
    }

    @Preview(showBackground = true)
    @Composable
    fun MainContent() {
        val frpcConfigList by frpcConfigList.collectAsStateWithLifecycle(emptyList())
        val frpsConfigList by frpsConfigList.collectAsStateWithLifecycle(emptyList())
        val runningConfigList by runningConfigList.collectAsStateWithLifecycle(emptyList())
        val clipboardManager = LocalClipboardManager.current
        val logText by logText.collectAsStateWithLifecycle("")
        val openDialog by showAddDialog.collectAsStateWithLifecycle()
        val isLogExpanded = remember { mutableStateOf(false) }
        
        val allConfigs = frpcConfigList + frpsConfigList
        val runningCount = runningConfigList.size

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            // 性能优化：预加载更多项目
            contentPadding = PaddingValues(bottom = 88.dp) // 为FAB留出足够空间
        ) {
            // 状态概览卡片
            item {
                StatusOverviewCard(
                    totalConfigs = allConfigs.size,
                    runningConfigs = runningCount,
                    isAutoStartEnabled = isStartup.collectAsStateWithLifecycle(false).value
                )
            }

            // 配置列表
            if (allConfigs.isEmpty()) {
                item {
                    EmptyConfigState(
                        onAddConfig = { showAddDialog.value = true }
                    )
                }
            } else {
                // frpc 配置
                if (frpcConfigList.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "客户端配置",
                            subtitle = "${frpcConfigList.size} 个配置"
                        )
                    }
                    items(
                        items = frpcConfigList,
                        key = { config -> "${config.type.typeName}_${config.fileName}" }
                    ) { config ->
                        ConfigCard(
                            config = config,
                            isRunning = runningConfigList.contains(config),
                            onEdit = { startConfigActivity(config) },
                            onDelete = { deleteConfig(config) },
                            onToggle = { if (it) startShell(config) else stopShell(config) }
                        )
                    }
                }

                // frps 配置
                if (frpsConfigList.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "服务端配置",
                            subtitle = "${frpsConfigList.size} 个配置"
                        )
                    }
                    items(
                        items = frpsConfigList,
                        key = { config -> "${config.type.typeName}_${config.fileName}" }
                    ) { config ->
                        ConfigCard(
                            config = config,
                            isRunning = runningConfigList.contains(config),
                            onEdit = { startConfigActivity(config) },
                            onDelete = { deleteConfig(config) },
                            onToggle = { if (it) startShell(config) else stopShell(config) }
                        )
                    }
                }
            }

            // 设置区域
            item {
                SettingsSection(
                    isAutoStartEnabled = isStartup.collectAsStateWithLifecycle(false).value,
                    onAutoStartChange = {
                        val editor = preferences.edit()
                        editor.putBoolean(PreferencesKey.AUTO_START, it)
                        editor.apply()
                        isStartup.value = it
                    },
                    onAddConfig = { showAddDialog.value = true },
                    onAbout = { startActivity(Intent(this@MainActivity, AboutActivity::class.java)) }
                )
            }

            // 日志区域
            item {
                LogSection(
                    logText = logText,
                    isExpanded = isLogExpanded.value,
                    onExpandChange = { isLogExpanded.value = it },
                    onClearLog = { if (::mService.isInitialized) mService.clearLog() },
                    onCopyLog = {
                        clipboardManager.setText(AnnotatedString(logText))
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                            Toast.makeText(this@MainActivity, getString(R.string.copied), Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }

        if (openDialog) {
            ConfigTypeSelector(
                onSelectType = { type ->
                    startConfigActivity(type)
                    showAddDialog.value = false
                },
                onDismiss = { showAddDialog.value = false }
            )
        }
    }



    override fun onDestroy() {
        super.onDestroy()
        
        // 先取消协程，避免在清理过程中继续更新StateFlow
        activityScope.cancel()
        
        // 解绑服务
        if (mBound) {
            try {
                unbindService(connection)
            } catch (e: Exception) {
                Log.w("MainActivity", "Error unbinding service", e)
            }
            mBound = false
        }
        
        // 清理StateFlow，避免内存泄漏
        try {
            isStartup.value = false
            logText.value = ""
            frpcConfigList.value = emptyList()
            frpsConfigList.value = emptyList()
            runningConfigList.value = emptyList()
            showAddDialog.value = false
            isLoading.value = false
            errorMessage.value = null
        } catch (e: Exception) {
            Log.w("MainActivity", "Error clearing StateFlow", e)
        }
    }

    fun checkConfig() {
        val frpcDir = FrpType.FRPC.getDir(this)
        if (frpcDir.exists() && !frpcDir.isDirectory) {
            frpcDir.delete()
        }
        if (!frpcDir.exists()) frpcDir.mkdirs()
        val frpsDir = FrpType.FRPS.getDir(this)
        if (frpsDir.exists() && !frpsDir.isDirectory) {
            frpsDir.delete()
        }
        if (!frpsDir.exists()) frpsDir.mkdirs()
        // v1.1旧版本配置迁移
        // 遍历文件夹内的所有文件
        this.filesDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".toml")) {
                // 构建目标文件路径
                val destination = File(frpcDir, file.name)
                // 移动文件
                if (file.renameTo(destination)) {
                    Log.d("adx", "Moved: ${file.name} to ${destination.absolutePath}")
                } else {
                    Log.e("adx", "Failed to move: ${file.name}")
                }
            }
        }
    }

    private fun deleteConfig(config: FrpConfig) {
        try {
            val file = config.getFile(this)
            if (file.exists() && file.isFile) {
                val deleted = file.delete()
                if (deleted) {
                    Toast.makeText(this, "配置删除成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "配置删除失败", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error deleting config", e)
            Toast.makeText(this, "删除配置时出错: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            updateConfigList()
        }
    }

    private fun startConfigActivity(type: FrpType) {
        try {
            val currentDate = Date()
            val formatter = SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.getDefault())
            val formattedDateTime = formatter.format(currentDate)
            val fileName = "$formattedDateTime.toml"
            val file = File(type.getDir(this), fileName)
            
            // 确保目录存在
            file.parentFile?.mkdirs()
            
            // 安全地复制资源文件
            resources.assets.open(type.getConfigAssetsName()).use { inputStream ->
                file.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            
            val config = FrpConfig(type, fileName)
            startConfigActivity(config)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error creating config file", e)
            Toast.makeText(this, "创建配置文件失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startConfigActivity(config: FrpConfig) {
        val intent = Intent(this, ConfigActivity::class.java)
        intent.putExtra(IntentExtraKey.FrpConfig, config)
        configActivityLauncher.launch(intent)
    }

    private fun startShell(config: FrpConfig) {
        val intent = Intent(this, ShellService::class.java)
        intent.setAction(ShellServiceAction.START)
        intent.putExtra(IntentExtraKey.FrpConfig, arrayListOf(config))
        startService(intent)
    }

    private fun stopShell(config: FrpConfig) {
        val intent = Intent(this, ShellService::class.java)
        intent.setAction(ShellServiceAction.STOP)
        intent.putExtra(IntentExtraKey.FrpConfig, arrayListOf(config))
        startService(intent)
    }

    private fun checkNotificationPermission() {
        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                // Permission is granted. Continue the action or workflow in your
                // app.
            } else {
                // Explain to the user that the feature is unavailable because the
                // feature requires a permission that the user has denied. At the
                // same time, respect the user's decision. Don't link to system
                // settings in an effort to convince the user to change their
                // decision.
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun createBGNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel_name)
            val descriptionText = getString(R.string.notification_channel_desc)
            val importance = NotificationManager.IMPORTANCE_MIN
            val channel = NotificationChannel("shell_bg", name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            val notificationManager: NotificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateConfigList() {
        activityScope.launch {
            try {
                isLoading.value = true
                errorMessage.value = null
                
                // 在IO线程中执行文件操作
                val frpcConfigs = withContext(Dispatchers.IO) {
                    (FrpType.FRPC.getDir(this@MainActivity).list()?.toList() ?: listOf()).map {
                        FrpConfig(FrpType.FRPC, it)
                    }
                }
                
                val frpsConfigs = withContext(Dispatchers.IO) {
                    (FrpType.FRPS.getDir(this@MainActivity).list()?.toList() ?: listOf()).map {
                        FrpConfig(FrpType.FRPS, it)
                    }
                }
                
                // 在主线程中更新UI
                frpcConfigList.value = frpcConfigs
                frpsConfigList.value = frpsConfigs
                
            } catch (e: Exception) {
                Log.e("MainActivity", "Error updating config list", e)
                errorMessage.value = "更新配置列表失败: ${e.message}"
                // 设置为空列表以避免崩溃
                frpcConfigList.value = emptyList()
                frpsConfigList.value = emptyList()
            } finally {
                isLoading.value = false
            }
        }

        // 检查自启动列表中是否含有已经删除的配置
        val frpcAutoStartList =
            preferences.getStringSet(PreferencesKey.AUTO_START_FRPC_LIST, emptySet())?.filter {
                frpcConfigList.value.contains(
                    FrpConfig(FrpType.FRPC, it)
                )
            }
        with(preferences.edit()) {
            putStringSet(PreferencesKey.AUTO_START_FRPC_LIST, frpcAutoStartList?.toSet())
            apply()
        }
        val frpsAutoStartList =
            preferences.getStringSet(PreferencesKey.AUTO_START_FRPS_LIST, emptySet())?.filter {
                frpsConfigList.value.contains(
                    FrpConfig(FrpType.FRPS, it)
                )
            }
        with(preferences.edit()) {
            putStringSet(PreferencesKey.AUTO_START_FRPS_LIST, frpsAutoStartList?.toSet())
            apply()
        }
    }

    @Composable
    fun StatusOverviewCard(
        totalConfigs: Int,
        runningConfigs: Int,
        isAutoStartEnabled: Boolean
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
                    Text(
                        text = "frp for Android",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    InfoChip(
                        text = "v${BuildConfig.VERSION_NAME}",
                        color = Primary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    StatusItem(
                        title = "总配置",
                        value = totalConfigs.toString(),
                        color = InfoColor
                    )
                    StatusItem(
                        title = "运行中",
                        value = runningConfigs.toString(),
                        color = if (runningConfigs > 0) SuccessColor else MaterialTheme.colorScheme.outline
                    )
                    StatusItem(
                        title = "自启动",
                        value = if (isAutoStartEnabled) "开启" else "关闭",
                        color = if (isAutoStartEnabled) SuccessColor else WarningColor
                    )
                }
            }
        }
    }

    @Composable
    fun StatusItem(
        title: String,
        value: String,
        color: androidx.compose.ui.graphics.Color
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 尝试将value转换为数字以使用动画计数器
            val numericValue = value.toIntOrNull()
            if (numericValue != null) {
                AnimatedCounter(
                    targetValue = numericValue,
                    style = MaterialTheme.typography.headlineMedium.copy(color = color)
                )
            } else {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    @Composable
    fun SettingsSection(
        isAutoStartEnabled: Boolean,
        onAutoStartChange: (Boolean) -> Unit,
        onAddConfig: () -> Unit,
        onAbout: () -> Unit
    ) {
        ModernCard {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )

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
                            text = "系统启动时自动运行已配置的服务",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isAutoStartEnabled,
                        onCheckedChange = onAutoStartChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SuccessColor,
                            checkedTrackColor = SuccessColor.copy(alpha = 0.5f)
                        )
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GradientButton(
                        onClick = onAddConfig,
                        text = "添加配置",
                        icon = Icons.Default.Add,
                        modifier = Modifier.weight(1f)
                    )
                    
                    OutlinedButton(
                        onClick = onAbout,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("关于")
                    }
                }
            }
        }
    }

    @Composable
    fun LogSection(
        logText: String,
        isExpanded: Boolean,
        onExpandChange: (Boolean) -> Unit,
        onClearLog: () -> Unit,
        onCopyLog: () -> Unit
    ) {
        ExpandableCard(
            title = "运行日志",
            subtitle = if (logText.isEmpty()) "暂无日志" else "点击展开查看详细日志",
            isExpanded = isExpanded,
            onExpandChange = onExpandChange,
            headerContent = {
                if (logText.isNotEmpty()) {
                    ActionButton(
                        icon = Icons.Default.ContentCopy,
                        contentDescription = "复制日志",
                        onClick = onCopyLog
                    )
                    ActionButton(
                        icon = Icons.Default.Clear,
                        contentDescription = "清空日志",
                        onClick = onClearLog,
                        tint = ErrorColor
                    )
                }
            }
        ) {
            if (logText.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = "暂无日志输出",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // 限制日志长度以避免内存问题
                val truncatedLogText = remember(logText) {
                    val lines = logText.lines()
                    if (lines.size > 1000) {
                        "...(显示最近1000行)\n" + lines.takeLast(1000).joinToString("\n")
                    } else {
                        logText
                    }
                }
                
                SelectionContainer {
                    Text(
                        text = truncatedLogText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }}
