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
import io.github.acedroidx.frp.BuildConfig
import io.github.acedroidx.frp.ui.theme.*

@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
    isLoading: Boolean = true
) {
    if (isLoading) {
        val infiniteTransition = rememberInfiniteTransition()
        val rotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        )

        Box(
            modifier = modifier.size(48.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(40.dp)
                    .rotate(rotation),
                color = Primary,
                strokeWidth = 3.dp
            )
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
            imageVector = Icons.Default.Router,
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
    var currentValue by remember { mutableStateOf(0) }
    
    LaunchedEffect(targetValue) {
        if (targetValue != currentValue) {
            val animationSpec = tween<Int>(
                durationMillis = 800,
                easing = FastOutSlowInEasing
            )
            animate(
                initialValue = currentValue.toFloat(),
                targetValue = targetValue.toFloat(),
                animationSpec = animationSpec
            ) { value, _ ->
                currentValue = value.toInt()
            }
        }
    }
    
    Text(
        text = currentValue.toString(),
        modifier = modifier,
        style = style,
        fontWeight = FontWeight.Bold
    )
}