package com.sky22333.frpandroid.core.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.ResizeMode.Companion.scaleToBounds
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale

/** Profiles↔Editor 容器变换与正文淡入共用时长，避免结束交接闪一下。 */
const val ProfileCardTransformMs = 280

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

val LocalNavAnimatedVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** Profiles 行 ↔ Editor：轻量等比缩放容器变换（Fit，无 fade、无逐帧重测）。 */
@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
fun Modifier.profileCardSharedBounds(profileId: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(shared) {
        then(
            Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = "profile-card-$profileId"),
                animatedVisibilityScope = animated,
                enter = EnterTransition.None,
                exit = ExitTransition.None,
                resizeMode = scaleToBounds(ContentScale.Fit, Alignment.Center),
                boundsTransform = { _, _ ->
                    tween(durationMillis = ProfileCardTransformMs, easing = FastOutSlowInEasing)
                },
                // 与 Editor 全屏矩形落点一致，避免 overlay 卸下时圆角突变闪一下
                clipInOverlayDuringTransition = OverlayClip(RectangleShape),
            ),
        )
    }
}
