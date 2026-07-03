package com.exio.inkleaf.data

/**
 * 章节文件名的自然排序：把文件名拆成数字段与非数字段，按段比较。
 * 这样 "2.pdf" 会排在 "10.pdf" 之前，"3.5.pdf" 排在 "4.pdf" 之前。
 */
object ChapterSort {

    fun compareNatural(a: String, b: String): Int {
        val chunksA = splitChunks(a)
        val chunksB = splitChunks(b)
        val len = minOf(chunksA.size, chunksB.size)
        for (i in 0 until len) {
            val ca = chunksA[i]
            val cb = chunksB[i]
            val cmp = if (ca.isNumber && cb.isNumber) {
                val na = ca.value.toLongOrNull() ?: 0L
                val nb = cb.value.toLongOrNull() ?: 0L
                na.compareTo(nb)
            } else {
                ca.value.compareTo(cb.value, ignoreCase = true)
            }
            if (cmp != 0) return cmp
        }
        return chunksA.size.compareTo(chunksB.size)
    }

    private fun splitChunks(name: String): List<Chunk> {
        val result = mutableListOf<Chunk>()
        val current = StringBuilder()
        var wasDigit: Boolean? = null
        for (ch in name) {
            val isDigit = ch.isDigit()
            if (wasDigit != null && wasDigit != isDigit && current.isNotEmpty()) {
                result.add(Chunk(current.toString(), wasDigit))
                current.clear()
            }
            current.append(ch)
            wasDigit = isDigit
        }
        if (current.isNotEmpty()) {
            result.add(Chunk(current.toString(), wasDigit ?: false))
        }
        return result
    }

    private data class Chunk(val value: String, val isNumber: Boolean)
}
