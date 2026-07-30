package com.exio.inkleaf.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** DataStore 实例必须全局唯一：用顶层属性委托挂在 Context 上是官方惯用法。 同名 DataStore 创建两次会直接抛异常——这是 DataStore 的第一坑。 */
private val Context.shelfDataStore by preferencesDataStore(name = "shelf_settings")

/** 网格列数：自适应（按最小宽度自动算列数）或固定列数 */
enum class GridColumnsMode(val fixedCount: Int?) {
    ADAPTIVE(null),
    FIXED_3(3),
    FIXED_4(4),
    FIXED_5(5),
}

/** 封面宽高比 */
enum class CoverAspect(val ratio: Float) {
    STANDARD(0.70f), // 2:3，漫画单行本常见比例
    BOOK(0.75f), // 3:4
    SQUARE(1f), // 1:1
}

/** 封面在卡片内的填充方式（纯显示期行为，与封面文件的生成无关） */
enum class CoverCrop {
    CROP, // 居中裁剪铺满
    TOP_CROP, // 顶部对齐裁剪（漫画封面的标题通常在上半部分）
    CONTAIN, // 完整显示，不足处留白
}

data class ShelfLayoutSettings(
    val columns: GridColumnsMode = GridColumnsMode.ADAPTIVE,
    val aspect: CoverAspect = CoverAspect.STANDARD,
    val crop: CoverCrop = CoverCrop.CROP,
)

enum class ShelfGroupFilterKind {
    ALL,
    LOCAL,
    ONLINE,
    UNGROUPED,
    GROUP,
}

data class ShelfGroupSelection(
    val kind: ShelfGroupFilterKind = ShelfGroupFilterKind.ALL,
    val groupId: Long? = null,
)

/** 书架排版设置的唯一持久化来源。 读取是一个 Flow：任何一处写入，所有订阅者（书架网格、排版抽屉）自动收到 新值——"点选即生效 + 持久化 + 各处一致"由同一条数据链路保证。 */
class ShelfSettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.shelfDataStore

    val settings: Flow<ShelfLayoutSettings> =
        dataStore.data.map { prefs ->
            ShelfLayoutSettings(
                columns = prefs[KEY_COLUMNS].toEnum(GridColumnsMode.ADAPTIVE),
                aspect = prefs[KEY_ASPECT].toEnum(CoverAspect.STANDARD),
                crop = prefs[KEY_CROP].toEnum(CoverCrop.CROP),
            )
        }

    val selectedGroup: Flow<ShelfGroupSelection> =
        dataStore.data.map { prefs ->
            val kind = prefs[KEY_GROUP_FILTER_KIND].toEnum(ShelfGroupFilterKind.ALL)
            val groupId = prefs[KEY_GROUP_FILTER_ID]
            ShelfGroupSelection(
                kind =
                    if (kind == ShelfGroupFilterKind.GROUP && groupId == null) {
                        ShelfGroupFilterKind.ALL
                    } else {
                        kind
                    },
                groupId = groupId.takeIf { kind == ShelfGroupFilterKind.GROUP },
            )
        }

    suspend fun setColumns(value: GridColumnsMode) {
        dataStore.edit { it[KEY_COLUMNS] = value.name }
    }

    suspend fun setAspect(value: CoverAspect) {
        dataStore.edit { it[KEY_ASPECT] = value.name }
    }

    suspend fun setCrop(value: CoverCrop) {
        dataStore.edit { it[KEY_CROP] = value.name }
    }

    val lastPickedFolder: Flow<String?> =
        dataStore.data
            .map { it[KEY_LAST_PICKED_FOLDER] }
            .catch { error ->
                if (error is IOException) emit(null) else throw error
            }

    suspend fun rememberLastPickedFolder(uri: String) {
        try {
            dataStore.edit { it[KEY_LAST_PICKED_FOLDER] = uri }
        } catch (_: IOException) {
            // This hint must never block the operation the user selected.
        }
    }

    suspend fun setSelectedGroup(value: ShelfGroupSelection) {
        dataStore.edit {
            it[KEY_GROUP_FILTER_KIND] = value.kind.name
            val groupId = value.groupId
            if (value.kind == ShelfGroupFilterKind.GROUP && groupId != null) {
                it[KEY_GROUP_FILTER_ID] = groupId
            } else {
                it.remove(KEY_GROUP_FILTER_ID)
            }
        }
    }

    companion object {
        private val KEY_COLUMNS = stringPreferencesKey("grid_columns")
        private val KEY_ASPECT = stringPreferencesKey("cover_aspect")
        private val KEY_CROP = stringPreferencesKey("cover_crop")
        private val KEY_GROUP_FILTER_KIND = stringPreferencesKey("group_filter_kind")
        private val KEY_GROUP_FILTER_ID = longPreferencesKey("group_filter_id")
        private val KEY_LAST_PICKED_FOLDER = stringPreferencesKey("last_picked_folder")
    }
}
