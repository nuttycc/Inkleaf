package com.exio.inkleaf.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 封面缩略图生成工具 */
object Covers {
    private const val TARGET_WIDTH = 400

    /**
     * 把一页漫画的原始字节缩小保存为封面 JPEG，返回封面文件；失败返回 null
     * （封面只是锦上添花，任何失败都不应影响阅读功能）。
     */
    suspend fun createCoverFile(context: Context, comicId: Long, pageBytes: ByteArray): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bitmap = decodeSampled(pageBytes, TARGET_WIDTH)
                    ?: return@runCatching null

                val dir = File(context.filesDir, "covers").apply { mkdirs() }
                val file = File(dir, "$comicId.jpg")
                file.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                bitmap.recycle()
                file
            }.getOrNull()
        }

    /**
     * 两步降采样解码（封面与胶片缩略图共用）。
     *
     * 为什么不直接解码原图再缩小？一张 2000x3000 的页解码后占
     * 2000*3000*4 ≈ 23MB 内存，只为了生成一张缩略图太浪费，还可能 OOM。
     * 标准做法分两步：
     * 1. inJustDecodeBounds = true 只读出图片尺寸，不分配像素内存
     * 2. 按目标宽度算 inSampleSize（只能取 2 的幂，每翻倍宽高各减半），
     *    解码时直接按 1/N 尺寸读出，内存占用降为 1/N²
     *
     * config 传 RGB_565 时每像素 2 字节（无透明通道），缩略图场景
     * 画质无损感、内存再减半；null = 用系统默认（ARGB_8888）。
     */
    fun decodeSampled(bytes: ByteArray, targetWidth: Int, config: Bitmap.Config? = null): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= targetWidth) sampleSize *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            config?.let { inPreferredConfig = it }
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
