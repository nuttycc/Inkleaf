package com.exio.inkleaf.plugin

import com.exio.inkleaf.data.ComicOpenException

/**
 * The single owner of source-declared page image transforms: the codec validates what a plugin may
 * declare, the cache identity records which transform produced the bytes, and the reader applies it
 * on download. Keeping those three in one place is what stops a transform from being validated but
 * never applied, or applied but not reflected in the identity.
 */
internal object PageImageTransforms {
    /** Rejects a transform the host could not honour, at the boundary where plugin data enters. */
    fun validate(transform: PageImageTransform, chapterId: String) {
        if (chapterId.toLongOrNull()?.let { it >= 0 } != true) {
            throw PluginContentValidationException(
                "Transformed page chapter ids must be non-negative numbers"
            )
        }
        if (transform.scrambleId < 0) {
            throw PluginContentValidationException(
                "Image transform scramble ids must be non-negative"
            )
        }
    }

    /** The transform's contribution to a page identity, empty when the page declares none. */
    fun identitySuffix(transform: PageImageTransform?): String =
        transform?.let { ":transform=${it.type.name},${it.scrambleId}" }.orEmpty()

    /**
     * Restores source-declared obfuscation before the bytes reach the page cache.
     *
     * A transformed page whose chapter id is not a number fails rather than passing scrambled bytes
     * through: those bytes decode fine, so a silent fallback would show a broken page as valid. The
     * codec already rejects this shape, so the throw only fires on data that bypassed validation.
     */
    fun apply(bytes: ByteArray, chapterId: String, page: PageDescriptor): ByteArray {
        val transform = page.imageTransform ?: return bytes
        val chapterNumber =
            chapterId.toLongOrNull()
                ?: throw ComicOpenException("页面图像无法还原：章节标识不是数字")
        return when (transform.type) {
            PageImageTransformType.JM_SEGMENTATION ->
                JmSegmentationImage.apply(
                    bytes = bytes,
                    chapterId = chapterNumber,
                    scrambleId = transform.scrambleId.toLong(),
                    url = page.url,
                )
        }
    }
}
