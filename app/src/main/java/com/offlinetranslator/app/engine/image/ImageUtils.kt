package com.offlinetranslator.app.engine.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * 把相册 Uri 解码为适合 Gemma 视觉输入的 Bitmap（最长边缩到 target px）。失败返回 null。
 * target=896 贴近 Gemma 视觉编码器原生输入分辨率——密集文字（海报/文档）多留细节，
 * 再大引擎也会缩回去，只浪费内存。
 */
fun decodeBitmapForGemma(context: Context, uri: Uri, target: Int = 896): Bitmap? =
    runCatching {
        context.contentResolver.openInputStream(uri).use { input ->
            val original = BitmapFactory.decodeStream(input) ?: return null
            resizeForGemma(original, target)
        }
    }.getOrNull()

private fun resizeForGemma(src: Bitmap, target: Int): Bitmap {
    val w = src.width
    val h = src.height
    val scale = target.toFloat() / maxOf(w, h)
    if (scale >= 1f) return src
    val nw = (w * scale).toInt().coerceAtLeast(1)
    val nh = (h * scale).toInt().coerceAtLeast(1)
    val resized = Bitmap.createScaledBitmap(src, nw, nh, true)
    if (resized !== src) runCatching { src.recycle() }
    return resized
}
