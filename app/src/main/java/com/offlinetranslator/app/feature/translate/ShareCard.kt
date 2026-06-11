package com.offlinetranslator.app.feature.translate

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.offlinetranslator.app.R
import java.io.File

/**
 * 译文品牌分享卡片：暖色对角渐变底 + 白色圆角卡（原文/译文）+ 品牌署名，
 * 渲染成 PNG 后调系统分享面板（微信/保存到相册等）。
 */
object ShareCard {

    fun share(context: Context, source: String, translated: String) {
        val file = render(context, source, translated)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }

    private fun render(context: Context, source: String, translated: String): File {
        val w = 1080
        val pad = 72
        val cardPad = 56
        val textW = w - pad * 2 - cardPad * 2

        fun layout(text: String, paint: TextPaint): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, textW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.25f)
                .build()

        val srcPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8A8A8E.toInt(); textSize = 44f
        }
        val dstPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1C1C1E.toInt(); textSize = 56f; typeface = Typeface.DEFAULT_BOLD
        }
        val srcL = layout(source, srcPaint)
        val dstL = layout(translated, dstPaint)

        val headerH = 150
        val footerH = 110
        val cardH = cardPad * 2 + srcL.height + 40 + dstL.height
        val h = pad + headerH + cardH + footerH + pad

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // 品牌对角渐变底（与 logo/启动页同三色）。
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(0xFFFFB152.toInt(), 0xFFFF6F61.toInt(), 0xFFC2479B.toInt()),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
        })

        // 头部品牌名 + 副标语。
        val brandPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 64f; typeface = Typeface.DEFAULT_BOLD
        }
        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xD9FFFFFF.toInt(); textSize = 36f
        }
        c.drawText(context.getString(R.string.app_name), pad.toFloat(), (pad + 70).toFloat(), brandPaint)
        c.drawText(context.getString(R.string.splash_tagline_1), pad.toFloat(), (pad + 126).toFloat(), subPaint)

        // 白色圆角内容卡。
        val cardTop = (pad + headerH).toFloat()
        c.drawRoundRect(
            RectF(pad.toFloat(), cardTop, (w - pad).toFloat(), cardTop + cardH),
            40f, 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE },
        )
        c.save()
        c.translate((pad + cardPad).toFloat(), cardTop + cardPad)
        srcL.draw(c)
        c.translate(0f, srcL.height + 40f)
        dstL.draw(c)
        c.restore()

        // 底部署名。
        c.drawText(
            "github.com/chunguangwei/offline-translator",
            pad.toFloat(), (h - pad - 20).toFloat(), subPaint,
        )

        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "yiren-share-${System.currentTimeMillis()}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f
    }
}
