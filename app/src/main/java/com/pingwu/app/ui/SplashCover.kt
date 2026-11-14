package com.pingwu.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.pingwu.app.R
import com.pingwu.app.ui.theme.ThemeMode
import kotlinx.coroutines.delay

/** 开屏封面：一整张图铺满，停一会儿再淡出（深墨/纸白两版跟着主题走）。 */
@Composable
fun PingWuSplash(mode: ThemeMode, onFinish: () -> Unit) {
    val cover = if (mode == ThemeMode.PAPER) R.drawable.splash_paper else R.drawable.splash_dark
    val fade = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        delay(HOLD_MS)
        fade.animateTo(0f, animationSpec = tween(FADE_MS))
        onFinish()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .alpha(fade.value),
    ) {
        Image(
            painter = painterResource(cover),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private const val HOLD_MS = 1000L
private const val FADE_MS = 320
