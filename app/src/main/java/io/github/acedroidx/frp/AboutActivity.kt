package io.github.acedroidx.frp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.acedroidx.frp.ui.theme.FrpTheme
import io.github.acedroidx.frp.ui.theme.*
import io.github.acedroidx.frp.ui.components.*

class AboutActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            FrpTheme {
                Scaffold(
                    topBar = {
                        ModernTopAppBar(
                            title = "关于应用",
                            subtitle = "frp for Android",
                            showBackButton = true,
                            onBackClick = { finish() }
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
        val uriHandler = LocalUriHandler.current
        
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 应用信息卡片
            item {
                AppInfoCard()
            }
            
            // 版本信息卡片
            item {
                VersionInfoCard()
            }
            
            // 项目链接卡片
            item {
                ProjectLinksCard(uriHandler = uriHandler)
            }
            
            // 功能特性卡片
            item {
                FeaturesCard()
            }
            
            // 开发者信息卡片
            item {
                DeveloperCard()
            }
        }
    }
    
    @Composable
    fun AppInfoCard() {
        ModernCard {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
                            ),
                            RoundedCornerShape(20.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Router,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = androidx.compose.ui.graphics.Color.White
                    )
                }
                
                Text(
                    text = "frp for Android",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                
                Text(
                    text = "快速反向代理工具的Android客户端\n让您轻松将本地服务暴露到公网",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
    
    @Composable
    fun VersionInfoCard() {
        ModernCard {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "版本信息",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                
                InfoRow(
                    icon = Icons.Default.PhoneAndroid,
                    label = "应用版本",
                    value = BuildConfig.VERSION_NAME
                )
                
                InfoRow(
                    icon = Icons.Default.Code,
                    label = "版本代码",
                    value = BuildConfig.VERSION_CODE.toString()
                )
                
                InfoRow(
                    icon = Icons.Default.Storage,
                    label = "frp内核版本",
                    value = BuildConfig.FrpVersion
                )
            }
        }
    }
    
    @Composable
    fun ProjectLinksCard(uriHandler: androidx.compose.ui.platform.UriHandler) {
        ModernCard {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "项目链接",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                
                LinkItem(
                    icon = Icons.Default.Code,
                    title = "frp-Android",
                    subtitle = "Android客户端源码",
                    url = "https://github.com/AceDroidX/frp-Android",
                    onClick = { uriHandler.openUri(it) }
                )
                
                LinkItem(
                    icon = Icons.Default.Router,
                    title = "frp",
                    subtitle = "frp官方项目",
                    url = "https://github.com/fatedier/frp",
                    onClick = { uriHandler.openUri(it) }
                )
            }
        }
    }
    
    @Composable
    fun FeaturesCard() {
        ModernCard {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "主要特性",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                
                FeatureItem(
                    icon = Icons.Default.FlashOn,
                    title = "高性能",
                    description = "基于Go语言开发，零依赖，高效稳定"
                )
                
                FeatureItem(
                    icon = Icons.Default.Security,
                    title = "安全可靠",
                    description = "支持多种加密方式，保障数据传输安全"
                )
                
                FeatureItem(
                    icon = Icons.Default.Settings,
                    title = "易于配置",
                    description = "简洁的TOML配置格式，支持多种代理类型"
                )
                
                FeatureItem(
                    icon = Icons.Default.PowerSettingsNew,
                    title = "开机自启",
                    description = "支持开机自动启动，无需手动干预"
                )
            }
        }
    }
    
    @Composable
    fun DeveloperCard() {
        ModernCard {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "开发者",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                
                Text(
                    text = "本项目基于frp官方项目开发，感谢fatedier及其团队的贡献。\n\n如果您在使用过程中遇到问题，欢迎在GitHub上提交Issue。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    
    @Composable
    fun InfoRow(
        icon: ImageVector,
        label: String,
        value: String
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
    
    @Composable
    fun LinkItem(
        icon: ImageVector,
        title: String,
        subtitle: String,
        url: String,
        onClick: (String) -> Unit
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)),
            onClick = { onClick(url) },
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    
    @Composable
    fun FeatureItem(
        icon: ImageVector,
        title: String,
        description: String
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
