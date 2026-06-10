package com.offlinetranslator.app.feature.splash

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinetranslator.app.BuildConfig
import com.offlinetranslator.app.R
import kotlinx.coroutines.delay
import kotlin.random.Random

// 与 branding/yiren-icon-master.svg 完全一致的角色路径（1024 视口）。
// 头 = 对话气泡 + 左下尾巴；身 = 肩部弧线。眼睛单独画，才能眨。
private const val HEAD_PATH =
    "M512 230 a150 150 0 1 1 -104 257 l-80 46 l30 -82 a150 150 0 0 1 154 -221 z"
private const val BODY_PATH =
    "M300 858 C300 622 392 520 512 520 C632 520 724 622 724 858 Z"
private const val EYE_Y = 392f
private const val EYE_R = 26f
private val EYE_XS = listOf(476f, 548f)

/** logo 同款暖色对角渐变（#FFB152 → #FF6F61 → #C2479B）。 */
private val BrandSplashGradient = listOf(
    Color(0xFFFFB152),
    Color(0xFFFF6F61),
    Color(0xFFC2479B),
)

private const val SPLASH_DURATION_SEC = 6

/**
 * 启动页：暖色渐变 + 眨眼 logo 角色 + 轮换功能标语 + 版本号。
 * 若远程配置了启动图（[SplashViewModel.remoteImage] 非空）则整屏显示远程图。
 * 展示 [SPLASH_DURATION_SEC] 秒（右上角可随时跳过）后回调 [onFinished]，
 * 由调用方做切换动画。
 */
@Composable
fun SplashScreen(
    onFinished: () -> Unit,
    vm: SplashViewModel = hiltViewModel(),
) {
    val remoteImage by vm.remoteImage.collectAsStateWithLifecycle()

    var remainingSec by remember { mutableIntStateOf(SPLASH_DURATION_SEC) }
    LaunchedEffect(Unit) {
        while (remainingSec > 0) {
            delay(1000)
            remainingSec--
        }
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(BrandSplashGradient)),
    ) {
        val img = remoteImage
        if (img != null) {
            // 远程运营图：整屏展示，让图自己说话。
            Image(
                bitmap = img.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 默认：居中眨眼 logo + 名称，底部轮换标语。
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BlinkingLogo(modifier = Modifier.size(170.dp))
                Spacer(Modifier.height(28.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.splash_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
            RotatingTaglines(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 88.dp),
            )
        }
        Text(
            text = "v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
            color = Color.White.copy(alpha = 0.55f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp),
        )
        // 右上角「跳过」：半透明胶囊 + 剩余秒数，随时可点。
        Text(
            text = stringResource(R.string.splash_skip, remainingSec),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 12.dp, end = 16.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.22f))
                .clickable(onClick = onFinished)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

/**
 * 用原 SVG 路径重绘的白色角色，眼睛以 DstOut 镂空透出底层渐变。
 * 眨眼 = 眼圆以眼心为轴做 scaleY 压扁，随机间隔，30% 概率连眨两下。
 */
@Composable
private fun BlinkingLogo(modifier: Modifier = Modifier) {
    val headPath = remember { PathParser().parsePathString(HEAD_PATH).toPath() }
    val bodyPath = remember { PathParser().parsePathString(BODY_PATH).toPath() }
    val blink = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(1800, 3400))
            blink.animateTo(0.06f, tween(80, easing = FastOutLinearInEasing))
            blink.animateTo(1f, tween(130, easing = LinearOutSlowInEasing))
            if (Random.nextFloat() < 0.3f) { // 偶尔俏皮地连眨两下
                delay(150)
                blink.animateTo(0.06f, tween(80, easing = FastOutLinearInEasing))
                blink.animateTo(1f, tween(130, easing = LinearOutSlowInEasing))
            }
        }
    }

    androidx.compose.foundation.Canvas(
        // Offscreen 合成层：DstOut 才能真正"擦"出镂空眼睛。
        modifier = modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        val s = size.minDimension / 1024f
        withTransform({ scale(s, s, pivot = Offset.Zero) }) {
            drawPath(headPath, Color.White)
            drawPath(bodyPath, Color.White)
            for (cx in EYE_XS) {
                withTransform({ scale(1f, blink.value, pivot = Offset(cx, EYE_Y)) }) {
                    drawCircle(
                        color = Color.Black,
                        radius = EYE_R,
                        center = Offset(cx, EYE_Y),
                        blendMode = BlendMode.DstOut,
                    )
                }
            }
        }
    }
}

/** 底部轮换的功能标语（淡入淡出），让等待的两秒"有内容"。 */
@Composable
private fun RotatingTaglines(modifier: Modifier = Modifier) {
    val tags = listOf(
        stringResource(R.string.splash_tagline_1),
        stringResource(R.string.splash_tagline_2),
        stringResource(R.string.splash_tagline_3),
    )
    var idx by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            idx = (idx + 1) % tags.size
        }
    }
    AnimatedContent(
        targetState = idx,
        transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(320)) },
        label = "splashTagline",
        modifier = modifier,
    ) { i ->
        Text(
            text = tags[i],
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.92f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
