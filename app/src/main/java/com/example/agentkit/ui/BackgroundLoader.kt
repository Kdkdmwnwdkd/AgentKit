package com.example.agentkit.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * hotfix32/33：聊天背景图零依赖加载（SAF content:// → 采样缩放的 [ImageBitmap]）。
 *
 * 纪律：全 App 外部依赖零新增——不引 Coil/Picasso，参照仓库只读思路、净室重写。
 * 背景图是「大图小用」场景（4000x3000 的照片显示在 ~1080x2400 的屏幕上），
 * 直接原图解码必 OOM：两遍解码——第一遍只读边界（inJustDecodeBounds），
 * 第二遍按目标尺寸取 2 的幂降采样（inSampleSize），内存占用降到 1/4 ~ 1/16。
 *
 * 任何失败（URI 失效/权限被收/解码异常）都返回 null——调用方据此不渲染背景层，
 * 不崩、不留半张图。content:// 流用 use {} 关闭，不泄漏 fd。
 *
 * @param reqWidth 目标宽度（dp 值即可，只用于算采样比，非像素精度）
 * @param reqHeight 目标高度（同上）
 */
suspend fun loadChatBackground(
    context: Context,
    uriString: String,
    reqWidth: Int,
    reqHeight: Int
): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val uri = Uri.parse(uriString)
        // 第一遍：只读边界，不分配位图内存
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        // 第二遍：2 的幂降采样后真解码
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                bounds.outWidth, bounds.outHeight,
                reqWidth.coerceAtLeast(1), reqHeight.coerceAtLeast(1)
            )
        }
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return@runCatching null
        bmp.asImageBitmap()
    }.getOrNull()
}

/**
 * hotfix33：全局背景图层（挂 App 根层，随根 Box 铺满整屏）。
 *
 * 真机反馈「不只是要聊天的地方变啊，其他地方也要变啊」：hotfix32 只在对话页画
 * 背景，这里提升到整个 App——抽屉、设置、模型、记忆、日志、控制台全部透出照片。
 * 层序：照片（alpha = 用户浓度，100% 即原图）→ surface 色 25% scrim（保文字可读，
 * 深浅两套主题自动适配）。无图时本层不渲染任何内容（零开销，根背景色兜底可见）。
 *
 * 注意：调用方需在无图时保留自身不透明底色、有图时让底色透明，否则照片被盖住。
 */
@Composable
fun AppBackgroundLayer(vm: ChatViewModel) {
    val uri by vm.chatBackgroundUri.collectAsStateWithLifecycle()
    val alpha by vm.chatBackgroundAlpha.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val config = LocalConfiguration.current
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = uri?.takeIf { it.isNotBlank() }
            ?.let { loadChatBackground(context, it, config.screenWidthDp, config.screenHeightDp) }
    }
    val bmp = bitmap ?: return
    Image(
        bitmap = bmp,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alpha = alpha,
        modifier = Modifier.fillMaxSize()
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.25f))
    )
}

/** 标准 2 的幂采样：缩到「仍不小于目标尺寸」的最大一级，避免过度降采样发虚。 */
private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
    var sample = 1
    while (width / (sample * 2) >= reqWidth && height / (sample * 2) >= reqHeight) {
        sample *= 2
    }
    return max(1, sample)
}
