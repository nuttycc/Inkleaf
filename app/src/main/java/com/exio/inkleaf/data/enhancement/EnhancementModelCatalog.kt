package com.exio.inkleaf.data.enhancement

data class EnhancementModelArtifact(
    val filename: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
    val archiveEntry: String? = null,
)

data class EnhancementModelArchive(
    val packageId: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
)

data class EnhancementModelDescriptor(
    val id: String,
    val displayName: String,
    val family: String,
    val version: String,
    val variant: String,
    val scale: Int,
    val targetBackend: String,
    val downloadSize: Long,
    val capabilities: List<String>,
    val recommendedFor: List<String>,
    val license: String,
    val sourceUrl: String,
    val artifacts: List<EnhancementModelArtifact>,
    val archive: EnhancementModelArchive? = null,
) {
    val installedSize: Long get() = artifacts.sumOf(EnhancementModelArtifact::bytes)
}

object EnhancementSelectionIds {
    const val ORIGINAL = "original"
    const val QUICK_CLARITY = "quick_clarity"

    val builtIn: Set<String> = setOf(ORIGINAL, QUICK_CLARITY)

    fun isValid(id: String): Boolean = id in builtIn || EnhancementModelCatalog.find(id) != null
}

object EnhancementModelCatalog {
    private val realEsrganNcnnArchive = EnhancementModelArchive(
        packageId = "realesrgan-ncnn-vulkan-20220424-ubuntu",
        url = "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesrgan-ncnn-vulkan-20220424-ubuntu.zip",
        bytes = 46_931_474,
        sha256 = "e5aa6eb131234b87c0c51f82b89390f5e3e642b7b70f2b9bbe95b6a285a40c96",
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
            downloadSize = 2_554_717,
            capabilities = listOf("2× 超分", "线条修复", "不降噪"),
            recommendedFor = listOf("干净线稿", "轻微模糊漫画"),
            license = "代码仓库 MIT；模型权重许可未验证",
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
            downloadSize = 2_578_409,
            capabilities = listOf("2× 超分", "线条修复", "保守降噪"),
            recommendedFor = listOf("JPEG 压缩页", "有噪扫描稿"),
            license = "代码仓库 MIT；模型权重许可未验证",
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
        ),
        EnhancementModelDescriptor(
            id = "waifu2x-upconv7-anime-2x",
            displayName = "Waifu2x UpConv7 Anime 2x",
            family = "Waifu2x",
            version = "commit 6491466",
            variant = "UpConv7 anime style art RGB",
            scale = 2,
            targetBackend = "ncnn · Vulkan",
            downloadSize = 1_107_295,
            capabilities = listOf("2× 超分", "动漫线条增强"),
            recommendedFor = listOf("动漫风彩图", "较低性能设备"),
            license = "代码仓库 MIT；模型权重许可未验证",
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
        ),
        EnhancementModelDescriptor(
            id = "realesrgan-animevideov3-2x",
            displayName = "Real-ESRGAN AnimeVideo-v3 2x",
            family = "Real-ESRGAN",
            version = "v0.2.5.0 · adapter 37026f4",
            variant = "AnimeVideo-v3 / native 2×",
            scale = 2,
            targetBackend = "ncnn · Vulkan / CPU",
            downloadSize = realEsrganNcnnArchive.bytes,
            capabilities = listOf("2× 超分", "动漫线条增强", "轻量 GAN 修复"),
            recommendedFor = listOf("漫画阅读实时增强", "动漫彩图", "性能较低设备"),
            license = "主项目 BSD-3-Clause；ncnn 实现 MIT；模型权重未单独声明",
            sourceUrl = "https://github.com/xinntao/Real-ESRGAN",
            artifacts = listOf(
                EnhancementModelArtifact(
                    filename = "realesr-animevideov3-x2.bin",
                    url = "${realEsrganNcnnArchive.url}#models/realesr-animevideov3-x2.bin",
                    bytes = 1_247_368,
                    sha256 = "548a36f9c3f4ab8da56cd3b13badf23968bee207b396dad14d04b830e5f2ab2d",
                    archiveEntry = "models/realesr-animevideov3-x2.bin",
                ),
                EnhancementModelArtifact(
                    filename = "realesr-animevideov3-x2.param",
                    url = "${realEsrganNcnnArchive.url}#models/realesr-animevideov3-x2.param",
                    bytes = 3_173,
                    sha256 = "b88ff4f00ebf019a7fdac17fdd45a7fd3665d37509efc5baf2e4da2e24420a04",
                    archiveEntry = "models/realesr-animevideov3-x2.param",
                ),
            ),
            archive = realEsrganNcnnArchive,
        ),
        EnhancementModelDescriptor(
            id = "realesrgan-x4plus-anime-4x",
            displayName = "Real-ESRGAN x4plus Anime 4x",
            family = "Real-ESRGAN",
            version = "v0.2.5.0 · adapter 37026f4",
            variant = "x4plus Anime 6B / native 4×",
            scale = 4,
            targetBackend = "ncnn · Vulkan / CPU",
            downloadSize = realEsrganNcnnArchive.bytes,
            capabilities = listOf("4× 超分", "动漫插画增强", "高质量 GAN 修复"),
            recommendedFor = listOf("小尺寸漫画页", "动漫插画", "高性能 Vulkan 设备"),
            license = "主项目 BSD-3-Clause；ncnn 实现 MIT；模型权重未单独声明",
            sourceUrl = "https://github.com/xinntao/Real-ESRGAN",
            artifacts = listOf(
                EnhancementModelArtifact(
                    filename = "realesrgan-x4plus-anime.bin",
                    url = "${realEsrganNcnnArchive.url}#models/realesrgan-x4plus-anime.bin",
                    bytes = 8_943_500,
                    sha256 = "fe01c269cfd10cdef8e018ab66ebe750cf79c7af4d1f9c16c737e1295229bacc",
                    archiveEntry = "models/realesrgan-x4plus-anime.bin",
                ),
                EnhancementModelArtifact(
                    filename = "realesrgan-x4plus-anime.param",
                    url = "${realEsrganNcnnArchive.url}#models/realesrgan-x4plus-anime.param",
                    bytes = 30_290,
                    sha256 = "2b8fb6e0ae4d2d85704ca08c119a2f5ea40add4f2ecd512eb7f4cd44b6127ed4",
                    archiveEntry = "models/realesrgan-x4plus-anime.param",
                ),
            ),
            archive = realEsrganNcnnArchive,
        ),
    )

    private val modelsById = models.associateBy(EnhancementModelDescriptor::id)

    fun find(id: String): EnhancementModelDescriptor? = modelsById[id]

    fun require(id: String): EnhancementModelDescriptor =
        requireNotNull(find(id)) { "Unknown enhancement model: $id" }
}
