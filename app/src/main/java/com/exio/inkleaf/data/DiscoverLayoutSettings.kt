package com.exio.inkleaf.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** 同名 DataStore 全局只能创建一次：顶层属性委托单例（同 shelfDataStore） */
private val Context.discoverDataStore by preferencesDataStore(name = "discover_settings")

/** 发现页条目排布方式 */
enum class DiscoverLayoutMode {
    /** 封面网格：以图找书 */
    GRID,
    /** 单列信息行：以文字扫读，一屏容纳更多条目 */
    LIST,
}

data class DiscoverLayoutSettings(
    val layout: DiscoverLayoutMode = DiscoverLayoutMode.GRID,
    val columns: GridColumnsMode = GridColumnsMode.ADAPTIVE,
)

/**
 * 发现页排版设置的唯一持久化来源。
 *
 * 与书架的 ShelfSettingsRepository 刻意分开存：本地书架是"我的收藏"，发现页是"在线浏览"，两处的
 * 舒适密度通常不同——书架想看大封面，发现页想快速扫过几百条结果。共用一份设置会让其中一处别扭。
 */
class DiscoverSettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.discoverDataStore

    val settings: Flow<DiscoverLayoutSettings> =
        dataStore.data.map { prefs ->
            DiscoverLayoutSettings(
                layout = prefs[KEY_LAYOUT].toEnum(DiscoverLayoutMode.GRID),
                columns = prefs[KEY_COLUMNS].toEnum(GridColumnsMode.ADAPTIVE),
            )
        }

    suspend fun setLayout(value: DiscoverLayoutMode) {
        dataStore.edit { it[KEY_LAYOUT] = value.name }
    }

    suspend fun setColumns(value: GridColumnsMode) {
        dataStore.edit { it[KEY_COLUMNS] = value.name }
    }

    /** 各源分类 chips 的自定义顺序：pluginId -> 有序 feed key 列表。 */
    val feedOrder: Flow<Map<String, List<String>>> =
        dataStore.data.map { prefs ->
            prefs[KEY_FEED_ORDER]?.let(::decodeFeedOrder) ?: emptyMap()
        }

    suspend fun setFeedOrder(pluginId: String, keys: List<String>) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_FEED_ORDER]?.let(::decodeFeedOrder) ?: emptyMap()
            prefs[KEY_FEED_ORDER] = encodeFeedOrder(current + (pluginId to keys))
        }
    }

    private companion object {
        val KEY_LAYOUT = stringPreferencesKey("layout_mode")
        val KEY_COLUMNS = stringPreferencesKey("grid_columns")
        val KEY_FEED_ORDER = stringPreferencesKey("feed_order")
    }
}

private val feedOrderJson = Json

/** JSON 编码保序：Preferences 的 stringSet 不保证顺序，feed key 列表必须按数组存。 */
internal fun encodeFeedOrder(order: Map<String, List<String>>): String =
    feedOrderJson.encodeToString(order)

internal fun decodeFeedOrder(raw: String): Map<String, List<String>> =
    try {
        feedOrderJson.decodeFromString<Map<String, List<String>>>(raw)
    } catch (_: Exception) {
        // 历史损坏或格式不兼容时回到默认顺序，不阻塞浏览
        emptyMap()
    }
