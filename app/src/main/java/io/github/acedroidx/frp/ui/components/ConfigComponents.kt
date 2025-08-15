package io.github.acedroidx.frp.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsState
import io.github.acedroidx.frp.FrpConfig
import io.github.acedroidx.frp.FrpType
import io.github.acedroidx.frp.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ConfigCard(
    config: FrpConfig,
    isRunning: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    // 使用remember来避免不必要的重组
    val configKey = remember(config) { "${config.type.typeName}_${config.fileName}" }
    
    // 生命周期感知动画 - 电量优化
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    val isResumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    
    val scale by animateFloatAsState(
        targetValue = if (isRunning) 1.02f else 1f,
        animationSpec = if (isResumed) {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        } else {
            snap() // 后台时禁用动画
        },
        label = "ConfigCardScale_$configKey"
    )

    ModernCard(
        modifier = modifier.scale(scale)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusIndicator(isActive = isRunning)
                    
                    InfoChip(
                        text = config.type.typeName.uppercase(),
                        color = if (config.type == FrpType.FRPC) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                    )
                    
                    if (isRunning) {
                        InfoChip(
                            text = "运行中",
                            color = SuccessColor
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = config.fileName.removeSuffix(".toml"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Text(
                    text = formatConfigDate(config.fileName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ActionButton(
                    icon = Icons.Default.Edit,
                    contentDescription = "编辑配置",
                    onClick = onEdit,
                    enabled = !isRunning
                )
                
                ActionButton(
                    icon = Icons.Default.Delete,
                    contentDescription = "删除配置",
                    onClick = onDelete,
                    enabled = !isRunning,
                    tint = ErrorColor
                )
                
                Switch(
                    checked = isRunning,
                    onCheckedChange = onToggle,
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
fun EmptyConfigState(
    onAddConfig: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        
        Text(
            text = "暂无配置",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Text(
            text = "点击下方按钮添加您的第一个frp配置",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Button(
            onClick = onAddConfig,
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("添加配置")
        }
    }
}

@Composable
fun ConfigTypeSelector(
    onSelectType: (FrpType) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "选择配置类型",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ConfigTypeCard(
                    type = FrpType.FRPC,
                    title = "frpc (客户端)",
                    description = "连接到frp服务器，将本地服务暴露到公网",
                    onClick = { onSelectType(FrpType.FRPC) }
                )
                
                ConfigTypeCard(
                    type = FrpType.FRPS,
                    title = "frps (服务端)",
                    description = "作为frp服务器，接受客户端连接",
                    onClick = { onSelectType(FrpType.FRPS) }
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun ConfigTypeCard(
    type: FrpType,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    ModernCard(
        onClick = onClick
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = if (type == FrpType.FRPC) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) 
                               else MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (type == FrpType.FRPC) Icons.Default.Devices 
                                 else Icons.Default.Storage,
                    contentDescription = null,
                    tint = if (type == FrpType.FRPC) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatConfigDate(fileName: String): String {
    return try {
        val dateStr = fileName.removeSuffix(".toml")
        val inputFormat = SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.getDefault())
        val outputFormat = SimpleDateFormat("MM月dd日 HH:mm", Locale.getDefault())
        val date = inputFormat.parse(dateStr)
        date?.let { outputFormat.format(it) } ?: "未知时间"
    } catch (e: Exception) {
        "自定义配置"
    }
}