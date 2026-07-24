package com.exio.inkleaf.data.ocr

import java.io.File

enum class OcrModelVariant(
    val id: String,
    val displayName: String,
    val description: String,
    val languageSummary: String,
    val version: String,
    val files: List<OcrModelFileSpec>,
) {
    SMALL(
        id = "ppocrv6_small",
        displayName = "PP-OCRv6 Small",
        description = "识别能力更完整，支持日文，适合大多数设备。",
        languageSummary = "支持 50 种语言，包括中文、英文和日文",
        version = "ppocrv6_small_1",
        files = listOf(
            OcrModelFileSpec("rec/inference.yml", 150_579L, "ab078671bb49f06228eadccd34f1bb501e157f7a047095ffb943ba81512c77d1", "PP-OCRv6_small_rec_onnx", "inference.yml"),
            OcrModelFileSpec("det/inference.onnx", 9_880_512L, "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e", "PP-OCRv6_small_det_onnx", "inference.onnx"),
            OcrModelFileSpec("rec/inference.onnx", 21_159_378L, "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634", "PP-OCRv6_small_rec_onnx", "inference.onnx"),
        ),
    ),
    TINY(
        id = "ppocrv6_tiny",
        displayName = "PP-OCRv6 Tiny",
        description = "体积更小、速度更快，但不支持日文。",
        languageSummary = "支持 49 种语言，不包含日文",
        version = "ppocrv6_tiny_1",
        files = listOf(
            OcrModelFileSpec("rec/inference.yml", 55_571L, "66170210bad538e83fff3c4a3867e547d6bf20b50d64b20347c4b913f3034ea1", "PP-OCRv6_tiny_rec_onnx", "inference.yml"),
            OcrModelFileSpec("det/inference.onnx", 1_780_590L, "193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8", "PP-OCRv6_tiny_det_onnx", "inference.onnx"),
            OcrModelFileSpec("rec/inference.onnx", 4_462_639L, "9ef676d6ed3c88256a2d92c640c44f25b0c40947e111b14b8be8f594091563e6", "PP-OCRv6_tiny_rec_onnx", "inference.onnx"),
        ),
    ),
}

val OcrModelVariant.totalBytes: Long get() = files.sumOf { it.sizeBytes }

data class OcrModelFileSpec(
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val repo: String,
    val fileName: String,
)

// Backward-compatible aliases for the original Small-only API.
internal const val OCR_MODEL_VERSION = "ppocrv6_small_1"
internal val OCR_MODEL_FILES: List<OcrModelFileSpec> get() = OcrModelVariant.SMALL.files
internal val OCR_MODEL_TOTAL_BYTES: Long get() = OcrModelVariant.SMALL.totalBytes

internal data class OcrModelSource(
    val name: String,
    val resolveUrl: (repo: String, fileName: String) -> String,
)

internal val OCR_MODEL_SOURCES = listOf(
    OcrModelSource("HuggingFace") { repo, file -> "https://huggingface.co/PaddlePaddle/$repo/resolve/main/$file" },
    OcrModelSource("ModelScope") { repo, file -> "https://modelscope.cn/models/PaddlePaddle/$repo/resolve/master/$file" },
    OcrModelSource("hf-mirror") { repo, file -> "https://hf-mirror.com/PaddlePaddle/$repo/resolve/main/$file" },
)

internal fun ocrModelDir(filesDir: File, variant: OcrModelVariant = OcrModelVariant.SMALL): File =
    File(filesDir, "ocr/${variant.id}")

internal fun isOcrModelReady(filesDir: File, variant: OcrModelVariant = OcrModelVariant.SMALL): Boolean {
    val dir = ocrModelDir(filesDir, variant)
    if (!File(dir, ".version").let { it.exists() && it.readText().trim() == variant.version }) return false
    return variant.files.all { spec ->
        val file = File(dir, spec.relativePath)
        file.exists() && file.length() == spec.sizeBytes
    }
}
