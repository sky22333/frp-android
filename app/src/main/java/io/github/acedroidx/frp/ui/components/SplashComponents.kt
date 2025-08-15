package io.github.acedroidx.frp.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.acedroidx.frp.BuildConfig
import io.github.acedroidx.frp.ui.theme.*

@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
    isLoading: Boolean = true
) {
    if (isLoading) {
        // 生命周期感知 - 电量优化
        val lifecycleOwner = LocalLifecycleOwner.current
        val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
        val isResumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
        
        if (isResumed) {
            val infiniteTransition = rememberInfiniteTransition(label = "LoadingRotation")
            val rotation by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = LinearEasing), // 稍微减慢动画速度
                    repeatMode = RepeatMode.Restart
                ),
                label = "LoadingRotation"
            )

            Box(
                modifier = modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(40.dp)
                        .rotate(rotation),
                    strokeWidth = 3.dp
                )
            }
        } else {
            // 后台时显示静态加载指示器
            Box(
                modifier = modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    strokeWidth = 3.dp
                )
            }
        }
    }
}

@Composable
fun AppLogo(
    modifier: Modifier = Modifier,
    size: Int = 80
) {
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        )
    )

    Box(
        modifier = modifier
            .size(size.dp)
            .scale(scale)
            .background(
                Brush.radialGradient(
                    colors = listOf(GradientStart, GradientEnd)
                ),
                RoundedCornerShape((size / 4).dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Router,
            contentDescription = null,
            modifier = Modifier.size((size / 2).dp),
            tint = Color.White
        )
    }
}

@Composable
fun StatusBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.1f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun AnimatedCounter(
    targetValue: Int,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.headlineMedium
) {
    // 生命周期感知 - 只在Activity处于前台时执行动画
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val isResumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    
    // 电量优化：后台时禁用动画，直接显示目标值
    val animatedValue by animateIntAsState(
        targetValue = targetValue,
        animationSpec = if (isResumed) {
            tween(
                durationMillis = 600, // 减少动画时长以节省电量
                easing = FastOutSlowInEasing
            )
        } else {
            snap() // 后台时立即显示目标值，不执行动画
        },
        label = "AnimatedCounter"
    )
    
    Text(
        text = animatedValue.toString(),
        modifier = modifier,
        style = style,
        fontWeight = FontWeight.Bold
    )
}