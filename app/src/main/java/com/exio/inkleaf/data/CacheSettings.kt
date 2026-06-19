package com.exio.inkleaf.data

import android.content.Context
import android.os.StatFs
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 同名 DataStore 全局只能创建一次：顶层属性委托单例（同 shelfDataStore） */
private val Context.cacheSettingsDataStore by preferencesDataStore(name = "cache_settings")

/**
 * 漫画缓存（zip 副本）总量的阶梯档位。
 *
 * 注意预算小于单本漫画体积时（如 512 MB 档遇上 1 GB 的大书），
 * "当前正读的书永不淘汰"的规则使实际语义退化为"只保留当前书"——
 * 这是物理边界而非失效，UI 文案需如实说明。
 */
enum class CacheLimit(
    val label: String,
    val description: String,
    private val fixedBytes: Long?,
) {
    AUTO(
        label = "自动推荐",
        description = "按设备剩余空间动态调整，适合大多数情况",
        fixedBytes = null,
    ),
    M512(
        label = "512 MB",
        description = "节省空间，只保留少量近期阅读缓存",
        fixedBytes = 512L shl 20,
    ),
    G2(
        label = "2 GB",
        description = "推荐手动档，适合日常连续阅读",
        fixedBytes = 2L shl 30,
    ),
    G5(
        label = "5 GB",
        description = "适合经常切换多本漫画",
        fixedBytes = 5L shl 30,
    ),
    G10(
        label = "10 GB",
        description = "适合平板或存储空间充裕的设备",
        fixedBytes = 10L shl 30,
    );

    fun bytes(context: Context): Long = fixedBytes ?: recommendedBytes(context)

    companion object {
        private const val AUTO_FRACTION = 0.05
        private const val AUTO_MIN_BYTES = 1_073_741_824L
        private const val AUTO_MAX_BYTES = 8_589_934_592L

        fun fromStoredName(value: String?): CacheLimit = when (value) {
            null -> AUTO
            "M100", "M300" -> M512
            "G1" -> G2
            "G3" -> G5
            else -> entries.firstOrNull { it.name == value } ?: AUTO
        }

        private fun recommendedBytes(context: Context): Long {
            val availableBytes = runCatching {
                StatFs(context.cacheDir.absolutePath).availableBytes
            }.getOrDefault(AUTO_MIN_BYTES)

            return (availableBytes * AUTO_FRACTION)
                .toLong()
                .coerceIn(AUTO_MIN_BYTES, AUTO_MAX_BYTES)
        }
    }
}

/** 缓存设置的唯一持久化来源（模式同 ThemeSettingsRepository） */
class CacheSettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.cacheSettingsDataStore

    val limit: Flow<CacheLimit> = dataStore.data.map { prefs ->
        CacheLimit.fromStoredName(prefs[KEY_LIMIT])
    }

    suspend fun setLimit(value: CacheLimit) {
        dataStore.edit { it[KEY_LIMIT] = value.name }
    }

    companion object {
        private val KEY_LIMIT = stringPreferencesKey("cache_limit")
    }
}
