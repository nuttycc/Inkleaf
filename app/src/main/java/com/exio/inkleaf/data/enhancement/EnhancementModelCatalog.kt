package com.exio.inkleaf.data.enhancement

data class EnhancementModelArtifact(
    val filename: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
    val bundledFilename: String? = null,
)

/**
 * Relative processing speed compared with other catalog models on the same device.
 * Actual time also depends on the device and source image dimensions.
 */
enum class EnhancementModelSpeed(val label: String) {
    FAST("较快"),
    MEDIUM("中等"),
    SLOW("较慢"),
}

data class EnhancementModelDescriptor(
    val id: String,
    val displayName: String,
    val family: String,
    val version: String,
    val variant: String,
    val scale: Int,
    val targetBackend: String,
    val speed: EnhancementModelSpeed,
    val capabilities: List<String>,
    val recommendedUse: String,
    val license: String,
    val sourceUrl: String,
    val artifacts: List<EnhancementModelArtifact>,
    val bundledAssetDirectory: String? = null,
) {
    val installedSize: Long get() = artifacts.sumOf(EnhancementModelArtifact::bytes)
    val downloadSize: Long get() = installedSize
    val isBundled: Boolean get() = bundledAssetDirectory != null
    val revision: String get() = artifacts.joinToString(separator = "-") { it.sha256 }

    fun bundledAssetPath(artifact: EnhancementModelArtifact): String? =
        bundledAssetDirectory?.let { directory ->
            "$directory/${artifact.bundledFilename ?: artifact.filename}"
        }
}

object EnhancementSelectionIds {
    const val ORIGINAL = "original"

    val builtIn: Set<String> = setOf(ORIGINAL)

    fun isValid(id: String): Boolean = id in builtIn || EnhancementModelCatalog.find(id) != null
}

object EnhancementModelCatalog {
    private const val REAL_ESRGAN_FAMILY = "Real-ESRGAN"
    private const val REAL_ESRGAN_VERSION = "v0.2.5.0 · adapter 37026f4"
    private const val REAL_ESRGAN_BACKEND = "ncnn · Vulkan / CPU"
    private const val REAL_ESRGAN_LICENSE =
        "BSD-3-Clause（项目；模型权重随官方 release 发布，未另附限制）"
    private const val REAL_ESRGAN_SOURCE_URL = "https://github.com/xinntao/Real-ESRGAN"
    private const val REAL_ESRGAN_NCNN_BASE_URL =
        "https://huggingface.co/vozzy/inkleaf-models/resolve/main/" +
                "releases/realesrgan-ncnn-vulkan/v0.2.5.0"

    private fun realEsrganArtifact(
        modelDirectory: String,
        filename: String,
        bytes: Long,
        sha256: String,
        bundledFilename: String? = null,
    ) = EnhancementModelArtifact(
        filename = filename,
        url = "$REAL_ESRGAN_NCNN_BASE_URL/$modelDirectory/$filename",
        bytes = bytes,
        sha256 = sha256,
        bundledFilename = bundledFilename,
    )

    val models: List<EnhancementModelDescriptor> = listOf(
        EnhancementModelDescriptor(
            id = "realcugan-2x-nose",
            displayName = "Real-CUGAN 2x NoSE",
            family = "Real-CUGAN",
            version = "commit 395302c",
            variant = "NoSE / no denoise",
            scale = 2,
            targetBackend = "ncnn · Vulkan",
            speed = EnhancementModelSpeed.MEDIUM,
            capabilities = listOf("改善线条", "保留原有噪点"),
            recommendedUse = "线条清晰或轻微模糊的漫画",
            license = "MIT（nihui/realcugan-ncnn-vulkan；模型源自 bilibili/ailab Real-CUGAN）",
            sourceUrl = "https://github.com/nihui/realcugan-ncnn-vulkan",
            artifacts = listOf(
                EnhancementModelArtifact(
                    filename = "up2x-no-denoise.bin",
                    url = "https://raw.githubusercontent.com/nihui/realcugan-ncnn-vulkan/395302c5c70f1bff604c974e92e0a87e45c9f9ee/models/models-nose/up2x-no-denoise.bin",
                    bytes = 2_551_408,
                    sha256 = "2b6f4db8fdc04336ac68ba954b4cd3b280beb5e7b6b0bcd97f769accc512cf4a",
                ),
                EnhancementModelArtifact(
                    filename = "up2x-no-denoise.param",
                    url = "https://raw.githubusercontent.com/nihui/realcugan-ncnn-vulkan/395302c5c70f1bff604c974e92e0a87e45c9f9ee/models/models-nose/up2x-no-denoise.param",
                    bytes = 3_309,
                    sha256 = "c370b70321d45301b9c682121130ad167b3dd1bc8e122c1d77c7ac44f1943708",
                ),
            ),
        ),
        EnhancementModelDescriptor(
            id = "realcugan-2x-conservative",
            displayName = "Real-CUGAN 2x Conservative",
            family = "Real-CUGAN",
            version = "commit 395302c",
            variant = "SE / conservative denoise",
            scale = 2,
            targetBackend = "ncnn · Vulkan",
            speed = EnhancementModelSpeed.MEDIUM,
            capabilities = listOf("改善线条", "减少部分噪点"),
            recommendedUse = "有压缩痕迹或噪点的扫描漫画",
            license = "MIT（nihui/realcugan-ncnn-vulkan；模型源自 bilibili/ailab Real-CUGAN）",
            sourceUrl = "https://github.com/nihui/realcugan-ncnn-vulkan",
            artifacts = listOf(
                EnhancementModelArtifact(
                    filename = "up2x-conservative.bin",
                    url = "https://raw.githubusercontent.com/nihui/realcugan-ncnn-vulkan/395302c5c70f1bff604c974e92e0a87e45c9f9ee/models/models-se/up2x-conservative.bin",
                    bytes = 2_573_648,
                    sha256 = "91c72c136e7ff8556323d4449c5ceeff22d9829bf8463a01137cadb8d59b84a0",
                ),
                EnhancementModelArtifact(
                    filename = "up2x-conservative.param",
                    url = "https://raw.githubusercontent.com/nihui/realcugan-ncnn-vulkan/395302c5c70f1bff604c974e92e0a87e45c9f9ee/models/models-se/up2x-conservative.param",
                    bytes = 4_761,
                    sha256 = "4b3b5c5710ad1d00503c8243417aa75f13fe497723e8b4952e8b1abfc08f9b84",
                ),
            ),
            bundledAssetDirectory = "enhancement_models/realcugan-2x-conservative",
        ),
        EnhancementModelDescriptor(
            id = "waifu2x-upconv7-anime-2x",
            displayName = "Waifu2x UpConv7 Anime 2x",
            family = "Waifu2x",
            version = "commit 6491466",
            variant = "UpConv7 anime style art RGB",
            scale = 2,
            targetBackend = "ncnn · Vulkan",
            speed = EnhancementModelSpeed.FAST,
            capabilities = listOf("改善动漫风格线条"),
            recommendedUse = "动漫风格的彩色图片",
            license = "MIT（nihui/waifu2x-ncnn-vulkan；模型源自 nagadomi/waifu2x）",
            sourceUrl = "https://github.com/nihui/waifu2x-ncnn-vulkan",
            artifacts = listOf(
                EnhancementModelArtifact(
                    filename = "scale2.0x_model.bin",
                    url = "https://raw.githubusercontent.com/nihui/waifu2x-ncnn-vulkan/64914665c45893135c9e50c1c296170a121b9f77/models/models-upconv_7_anime_style_art_rgb/scale2.0x_model.bin",
                    bytes = 1_106_248,
                    sha256 = "27d880903ff81390a3b4a900b67af3381c465279e34297546c9a58781855f7ef",
                ),
                EnhancementModelArtifact(
                    filename = "scale2.0x_model.param",
                    url = "https://raw.githubusercontent.com/nihui/waifu2x-ncnn-vulkan/64914665c45893135c9e50c1c296170a121b9f77/models/models-upconv_7_anime_style_art_rgb/scale2.0x_model.param",
                    bytes = 1_047,
                    sha256 = "413195ffde05b4d43807792c6c020c916cecdf25dcf002ee83f5e28d5cc246c6",
                ),
            ),
            bundledAssetDirectory = "enhancement_models/waifu2x-upconv7-anime-2x",
        ),
        EnhancementModelDescriptor(
            id = "realesrgan-animevideov3-2x",
            displayName = "Real-ESRGAN AnimeVideo-v3 2x",
            family = REAL_ESRGAN_FAMILY,
            version = REAL_ESRGAN_VERSION,
            variant = "AnimeVideo-v3 / native 2×",
            scale = 2,
            targetBackend = REAL_ESRGAN_BACKEND,
            speed = EnhancementModelSpeed.FAST,
            capabilities = listOf("改善动漫风格线条", "改善轻微失真"),
            recommendedUse = "漫画和动漫风格图片",
            license = REAL_ESRGAN_LICENSE,
            sourceUrl = REAL_ESRGAN_SOURCE_URL,
            artifacts = listOf(
                realEsrganArtifact(
                    modelDirectory = "animevideov3-x2",
                    filename = "realesr-animevideov3-x2.bin",
                    bytes = 1_247_368,
                    sha256 = "548a36f9c3f4ab8da56cd3b13badf23968bee207b396dad14d04b830e5f2ab2d",
                    bundledFilename = "shared.bin",
                ),
                realEsrganArtifact(
                    modelDirectory = "animevideov3-x2",
                    filename = "realesr-animevideov3-x2.param",
                    bytes = 3_173,
                    sha256 = "b88ff4f00ebf019a7fdac17fdd45a7fd3665d37509efc5baf2e4da2e24420a04",
                ),
            ),
            bundledAssetDirectory = "enhancement_models/realesrgan-animevideov3",
        ),
        EnhancementModelDescriptor(
            id = "realesrgan-animevideov3-4x",
            displayName = "Real-ESRGAN AnimeVideo-v3 4x",
            family = REAL_ESRGAN_FAMILY,
            version = REAL_ESRGAN_VERSION,
            variant = "AnimeVideo-v3 / native 4×",
            scale = 4,
            targetBackend = REAL_ESRGAN_BACKEND,
            speed = EnhancementModelSpeed.MEDIUM,
            capabilities = listOf("改善动漫风格线条", "改善轻微失真"),
            recommendedUse = "尺寸较小的漫画和动漫风格图片",
            license = REAL_ESRGAN_LICENSE,
            sourceUrl = REAL_ESRGAN_SOURCE_URL,
            artifacts = listOf(
                realEsrganArtifact(
                    modelDirectory = "animevideov3-x4",
                    filename = "realesr-animevideov3-x4.bin",
                    bytes = 1_247_368,
                    sha256 = "548a36f9c3f4ab8da56cd3b13badf23968bee207b396dad14d04b830e5f2ab2d",
                    bundledFilename = "shared.bin",
                ),
                realEsrganArtifact(
                    modelDirectory = "animevideov3-x4",
                    filename = "realesr-animevideov3-x4.param",
                    bytes = 3_077,
                    sha256 = "850a248e7c14c27e5bd8cf7265113a9441036a7db63963bb8aa5169d788a435e",
                ),
            ),
            bundledAssetDirectory = "enhancement_models/realesrgan-animevideov3",
        ),
        EnhancementModelDescriptor(
            id = "realesrgan-x4plus-anime-4x",
            displayName = "Real-ESRGAN x4plus Anime 4x",
            family = REAL_ESRGAN_FAMILY,
            version = REAL_ESRGAN_VERSION,
            variant = "x4plus Anime 6B / native 4×",
            scale = 4,
            targetBackend = REAL_ESRGAN_BACKEND,
            speed = EnhancementModelSpeed.SLOW,
            capabilities = listOf("增强动漫插画细节", "改善明显失真"),
            recommendedUse = "需要放大细节的漫画和动漫插画",
            license = REAL_ESRGAN_LICENSE,
            sourceUrl = REAL_ESRGAN_SOURCE_URL,
            artifacts = listOf(
                realEsrganArtifact(
                    modelDirectory = "x4plus-anime",
                    filename = "realesrgan-x4plus-anime.bin",
                    bytes = 8_943_500,
                    sha256 = "fe01c269cfd10cdef8e018ab66ebe750cf79c7af4d1f9c16c737e1295229bacc",
                ),
                realEsrganArtifact(
                    modelDirectory = "x4plus-anime",
                    filename = "realesrgan-x4plus-anime.param",
                    bytes = 30_290,
                    sha256 = "2b8fb6e0ae4d2d85704ca08c119a2f5ea40add4f2ecd512eb7f4cd44b6127ed4",
                ),
            ),
        ),
        EnhancementModelDescriptor(
            id = "realesrgan-x4plus-4x",
            displayName = "Real-ESRGAN x4plus 4x",
            family = REAL_ESRGAN_FAMILY,
            version = REAL_ESRGAN_VERSION,
            variant = "x4plus / native 4×",
            scale = 4,
            targetBackend = REAL_ESRGAN_BACKEND,
            speed = EnhancementModelSpeed.SLOW,
            capabilities = listOf("增强图片细节", "改善明显失真"),
            recommendedUse = "写实风格漫画、照片和纹理较多的图片",
            license = REAL_ESRGAN_LICENSE,
            sourceUrl = REAL_ESRGAN_SOURCE_URL,
            artifacts = listOf(
                realEsrganArtifact(
                    modelDirectory = "x4plus",
                    filename = "realesrgan-x4plus.bin",
                    bytes = 33_424_520,
                    sha256 = "713ee713b0353afaa27976f0563a64a5043bd70b9bd8936c2e26e25ebcdbcddf",
                ),
                realEsrganArtifact(
                    modelDirectory = "x4plus",
                    filename = "realesrgan-x4plus.param",
                    bytes = 116_029,
                    sha256 = "35330ececcea33b6c397a72548e788d5d53becee4734c50b7fada36e89f10a86",
                ),
            ),
        ),
    )

    private val modelsById = models.associateBy(EnhancementModelDescriptor::id)

    fun find(id: String): EnhancementModelDescriptor? = modelsById[id]

    fun require(id: String): EnhancementModelDescriptor =
        requireNotNull(find(id)) { "Unknown enhancement model: $id" }
}
