package com.exio.inkleaf.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 把图片字节写入系统相册 Pictures/Inkleaf，返回最终 Uri。minSdk 29 的 scoped storage 下无需权限。 */
suspend fun saveImageBytesToGallery(
    context: Context,
    bytes: ByteArray,
    displayName: String,
): Uri {
    val extension = imageExtension(bytes)
    return withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.$extension")
                put(MediaStore.Images.Media.MIME_TYPE, mimeTypeFor(extension))
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/Inkleaf",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

        val uri =
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法创建相册文件")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                output.write(bytes)
                (output as? java.io.FileOutputStream)?.fd?.sync()
            } ?: throw IOException("无法写入相册文件")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}

/** 从字节嗅探图片扩展名（jpg/png/webp/gif），未知格式回退 jpg */
internal fun imageExtension(bytes: ByteArray): String =
    when {
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte() -> "jpg"

        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() -> "png"

        bytes.size >= 12 &&
            bytes[0] == 0x52.toByte() &&
            bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() &&
            bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() &&
            bytes[9] == 0x45.toByte() &&
            bytes[10] == 0x42.toByte() &&
            bytes[11] == 0x50.toByte() -> "webp"

        bytes.size >= 6 &&
            bytes[0] == 0x47.toByte() &&
            bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() -> "gif"

        else -> "jpg"
    }

internal fun mimeTypeFor(extension: String): String =
    when (extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/jpeg"
    }

/** 文件名安全化：替换非法字符、截断、空串兜底 */
internal fun sanitizeFileName(value: String): String =
    value.replace(Regex("""[\\/:*?"<>|]"""), "_").take(48).ifBlank { "Inkleaf" }
