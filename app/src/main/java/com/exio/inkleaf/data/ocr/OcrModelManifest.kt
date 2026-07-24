// OCR 模型清单：版本、文件列表、校验值、下载源地址。
package com.exio.inkleaf.data.ocr

import java.io.File

/** 模型版本标识，版本变化时触发重新下载。 */
internal const val OCR_MODEL_VERSION = "ppocrv6_small_1"

/** 模型文件总大小（字节），用于进度展示。 */
internal const val OCR_MODEL_TOTAL_BYTES = 9_880_512L + 21_159_378L + 150_579L

internal data class OcrModelFileSpec(
    /** 相对于模型根目录的路径，如 "det/inference.onnx"。 */
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
)

internal val OCR_MODEL_FILES = listOf(
    OcrModelFileSpec(
        relativePath = "rec/inference.yml",
        sizeBytes = 150_579L,
        sha256 = "ab078671bb49f06228eadccd34f1bb501e157f7a047095ffb943ba81512c77d1",
    ),
    OcrModelFileSpec(
        relativePath = "det/inference.onnx",
        sizeBytes = 9_880_512L,
        sha256 = "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e",
    ),
    OcrModelFileSpec(
        relativePath = "rec/inference.onnx",
        sizeBytes = 21_159_378L,
        sha256 = "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634",
    ),
)

/**
 * 下载源定义。每个源提供 URL 模板，[resolveUrl] 将 repo 和文件名拼接为完整下载地址。
 */
internal data class OcrModelSource(
    val name: String,
    /** 用于测速和下载的 URL 构造器：(repoPath, fileName) -> URL */
    val resolveUrl: (repo: String, fileName: String) -> String,
)

internal val OCR_MODEL_SOURCES = listOf(
    OcrModelSource(
        name = "HuggingFace",
        resolveUrl = { repo, file ->
            "https://huggingface.co/PaddlePaddle/$repo/resolve/main/$file"
        },
    ),
    OcrModelSource(
        name = "ModelScope",
        resolveUrl = { repo, file ->
            "https://modelscope.cn/models/PaddlePaddle/$repo/resolve/master/$file"
        },
    ),
    OcrModelSource(
        name = "hf-mirror",
        resolveUrl = { repo, file ->
            "https://hf-mirror.com/PaddlePaddle/$repo/resolve/main/$file"
        },
    ),
)

/** 每个模型文件对应的远端 repo 和文件名。 */
internal data class OcrModelRemoteRef(
    val repo: String,
    val fileName: String,
)

internal fun OcrModelFileSpec.remoteRef(): OcrModelRemoteRef = when (relativePath) {
    "det/inference.onnx" -> OcrModelRemoteRef("PP-OCRv6_small_det_onnx", "inference.onnx")
    "rec/inference.onnx" -> OcrModelRemoteRef("PP-OCRv6_small_rec_onnx", "inference.onnx")
    "rec/inference.yml" -> OcrModelRemoteRef("PP-OCRv6_small_rec_onnx", "inference.yml")
    else -> error("Unknown model file: $relativePath")
}

/** 模型文件在本地的根目录。 */
internal fun ocrModelDir(filesDir: File): File = File(filesDir, "ocr/ppocrv6_small")

/** 检查本地模型是否完整且版本匹配。 */
internal fun isOcrModelReady(filesDir: File): Boolean {
    val dir = ocrModelDir(filesDir)
    val versionFile = File(dir, ".version")
    if (!versionFile.exists() || versionFile.readText().trim() != OCR_MODEL_VERSION) return false
    return OCR_MODEL_FILES.all { spec ->
        val file = File(dir, spec.relativePath)
        file.exists() && file.length() == spec.sizeBytes
    }
}
