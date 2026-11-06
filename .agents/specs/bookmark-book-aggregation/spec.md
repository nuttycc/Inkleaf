# 书签按「书」聚合（在线阅读跨话 + 本地统一展示模型）

## 1. 背景与已确认结论

- 存储层本身已按书聚合：
  - 在线：`filesDir/online-content/state.json` 中每个 `OnlineComicRecord` 按 `OnlineContentIdentity(pluginId, sourceId)` 存整本书的 `pageBookmarks`。
  - 本地：Room `BookmarkEntity.comicId` + 书内全局 `globalPageIndex`。
- 现网问题只出在在线阅读器展示层：`OnlineReaderViewModel.refreshUserRecords()` 用
  `location.identity.chapter == chapterIdentity` 过滤，导致只显示当前话书签；
  同时 `bookmarkEntriesByKey` 也只装当前话，跨话删除/撤销失效。
- 本地 PDF 系列 `pagerChapterWindow == null`，点击书签走 `scrollToPage(globalPage)`，跨话本来可用；
  单本 zip 只有一章。**本地不得退化**。

## 2. 范围

### 做
1. 引入在线/本地统一的书签展示模型与排序、文案纯函数。
2. 在线书签聚合纯函数：整本书所有话 → 展示条目；未知/不可用章节排最后并标不可用。
3. 在线 ViewModel 去掉章节过滤；跨话点击切话并定位；跨话删除/撤销按 identity 工作。
4. 在线/本地界面接线；本地 PDF 系列与单本 zip 行为保持不变。

### 不做
- 不新增 series 身份，不做多卷 zip 系列聚合。
- 不修改 Room 版本号（保持 18），不写迁移脚本。
- 不批量重写旧在线 JSON；只做兼容读取（`chapterTitleSnapshot`、`chapterRevision` 可空）。
- 不为跨话条目后台打开目标话拉缩略图（用占位缩略图）。

## 3. 数据模型（`ReaderPresentationModels.kt`）

```kotlin
internal sealed interface ReaderBookmarkTarget {
    data class Local(val globalPage: Int) : ReaderBookmarkTarget
    data class Online(
        val chapterId: String,
        val chapterPageIndex: Int?,
        val pageId: String?,
    ) : ReaderBookmarkTarget
}

internal data class ReaderBookmarkItem(
    val key: String,
    val target: ReaderBookmarkTarget,
    val chapterOrder: Int,      // 书内话序号；未知章节为 Int.MAX_VALUE
    val pageIndex: Int?,        // 话内页码；未知为 null，不编造
    val globalPage: Int?,       // 仅本地有全书页号
    val chapterTitle: String,
    val stale: Boolean,         // 需二次确认，打开近似位置
    val available: Boolean,     // false = 章节找不到/不可用，排最后且不可点
    val addedAtMs: Long,
    val thumbnailPage: Int?,    // 仅当前打开上下文中可取的缩略图页；跨话为 null
)
```

替换原 `ReaderBookmarkItem` 字段（原 `chapterIndex` 由 `chapterOrder` 替代）。

### 纯函数
- `sortedReaderBookmarks(list)`：
  排序键 `(available ? chapterOrder : Int.MAX_VALUE, pageIndex ?: Int.MAX_VALUE, addedAtMs, key)`。
  满足「按（章节序号, 章节内页码, addedAtMs）排序；找不到章节的排最后」。
- `readerBookmarkPageLabel(item): String?`：
  - 有全书页号 + 话内页号：`全书第 N 页 · 章节第 M 页`
  - 只有话内页号：`章节第 M 页`
  - 只有全书页号：`全书第 N 页`
  - 都没有：`null`（只显示章节标题）

## 4. 在线聚合纯函数（新文件 `OnlineBookmarkAggregation.kt`）

```kotlin
internal fun onlineBookmarkItems(
    bookmarks: List<OnlinePageBookmark>,
    chapters: List<ChapterSummary>,
    currentChapterId: String?,
    currentChapterRevision: String?,
    currentChapterPageCount: Int,
    currentPageDescriptors: List<PageDescriptor>?,
    keyOf: (OnlinePageIdentity) -> String,
): List<ReaderBookmarkItem>
```

规则：
1. 用 `chapterId` 在 `chapters` 中查序号；查不到或 `available == false`：
   - `available=false`、`chapterOrder=Int.MAX_VALUE`、`pageIndex=null`、`globalPage=null`
   - 标题取 `chapterTitleSnapshot`，否则章节标题，否则 `"未知章节"`
   - `thumbnailPage=null`、`target=Online(chapterId, null, pageId)`
2. 当前打开的话且能拿到 `PageDescriptor`：
   - 先 `resolveOnlinePageReference(pageId, fallback.pageIndex, fallback.chapterRevision,
     currentChapterRevision, pages)`
   - 命中：`pageIndex=命中页`、`stale=false`
   - 未命中：`pageIndex=location.pageIndex.coerceIn(0, max(0, pageCount-1))`、`stale=true`
3. 跨话：
   - `identity.fallback != null`：`pageIndex=fallback.pageIndex`；
     `stale = chapter.revision != null && fallback.chapterRevision != chapter.revision`
   - `identity.pageId != null`：`pageIndex=null`（未打开目标话前不编造页号）；
     `stale = chapter.revision != null && location.chapterRevision != chapter.revision`
     （revision 变过时先确认，再在目标话内按 pageId 精确解析，失败则近似）
   - 标题：`chapterTitleSnapshot` → 章节标题 → `"未命名章节"`
   - `chapterOrder=章节序号`、`thumbnailPage=null`、`target=Online(chapterId, pageIndex, pageId)`
4. 当前话条目的 `thumbnailPage = pageIndex`；非当前话一律 `null`。
5. 旧 JSON：标题快照为空、revision 为空均按上述回退处理，不重写存储。

### 跨话定位纯函数
```kotlin
internal data class OnlineBookmarkJump(val page: Int, val approximate: Boolean)

internal fun resolveOnlineBookmarkJump(
    target: ReaderBookmarkTarget.Online,
    currentRevision: String,
    pages: List<PageDescriptor>,
): OnlineBookmarkJump
```
- 先按 `pageId` 精确解析；命中即 `approximate=false`。
- 否则 `chapterPageIndex` 夹取到 `pages.indices`；越界/为空取 0；`approximate=chapterPageIndex` 与解析页不一致或为空。

## 5. 书签面板（`ReaderBookmarksSheet.kt`）

- `onSelect: (ReaderBookmarkItem) -> Unit`（由页码回调改为条目回调）。
- 列表用 `sortedReaderBookmarks`；删除前排序不变。
- 行文案用 `readerBookmarkPageLabel`；为 `null` 时只显示章节标题。
- 缩略图：仅当 `thumbnailPage != null && !stale` 时加载/显示；否则占位图标。
  `LaunchedEffect(thumbnailPage)`、`onNeedThumbnail(thumbnailPage)`。
- `available == false`：整行禁用（`Surface(enabled=false)`），显示
  `"章节已不可用，无法定位"`，不提供点击跳转。
- `stale == true` 点击先弹确认；文案通用化为
  `"书签添加后章节内容可能已变化，将尝试打开最接近的位置，仍要打开吗？"`，
  确认后 `onSelect(item)`。
- 删除/撤销逻辑不变；`removalsInFlight` 仍按 `item.key`。

## 6. 接线（`ReaderScreen.kt` / `OnlineReaderScreen.kt`）

`ReaderPresentationActions` 新增：
```kotlin
val onBookmarkSelected: ((ReaderBookmarkItem) -> Unit)? = null
```

`SharedReaderScreen` 书签面板点击：
1. `activePanel = null`。
2. 若 `target is Online` 且 `pageIndex != null` 且 `chapterId == currentWindowPage.chapter.chapterId`：
   走 `windowIndexForCurrentChapterPage(pageIndex)` + `scrollToPage`；失败弹
   `"无法定位到该书签所在页面"`。
3. 否则若 `actions.onBookmarkSelected != null`：调用它（在线跨话）。
4. 否则 `target is Local`：`windowIndexForCurrentChapterPage(globalPage)` 跳转；失败弹明确提示。

- 本地 `ReaderScreen`：`onBookmarkSelected` 保持 `null`；书签映射为
  `Local(globalPage)`、`chapterOrder=bookmark.chapterIndex`、`pageIndex=bookmark.pageIndex`、
  `globalPage=resolved.globalPage`、`thumbnailPage=resolved.globalPage`、
  `addedAtMs=bookmark.addedAt`、`available=true`、`stale=resolved.stale`。
- 在线 `OnlineReaderScreen`：`onBookmarkSelected = viewModel::openBookmark`。

## 7. 在线 ViewModel（`OnlineReaderViewModel.kt`）

### 7.1 `refreshUserRecords()`
- 不再过滤 `pageBookmarks`。
- 用 `onlineBookmarkItems(...)` 生成全部条目。
- `bookmarkEntriesByKey = record.pageBookmarks.associateBy { bookmarkKey(identity) }`（整本书）。
- `bookmarks` 写入 `sortedReaderBookmarks(items)`。
- 当前页书签图标：仅 `available && !stale && thumbnailPage != null` 的条目写入
  `bookmarkPages` / `bookmarkPageKeys`（即仅当前话，行为不变）。
- 收藏（favorites）逻辑保持仅当前话，不在本次范围。

### 7.2 `openBookmark(item)`
```kotlin
fun openBookmark(item: ReaderBookmarkItem)
```
- 非 `Online` target → 返回。
- `chapterSummaries.indexOfFirst { chapterId }`：
  - 找不到 → `readerMessage = "该书签所在章节已不存在，无法跳转"`。
  - `available == false` → `"该书签所在章节当前不可用，无法跳转"`。
- 章节加载中 → `"正在切换章节，请稍后再试"`。
- 目标话就是当前话且 `pageIndex != null`：由面板直接滚动处理（此处不重复）。
- 否则设置 `pendingBookmarkTarget`，`prepareAndCommitChapter(chapter, index, MANUAL)`。

### 7.3 `prepareAndCommitChapter`
- `startPage` 优先级：显式 `pendingBookmarkTarget` 解析结果 → 原逻辑。
- 解析用 `resolveOnlineBookmarkJump(...)`；`approximate` 时提交完成后
  `readerMessage = "无法精确定位该书签，已打开最接近的页面"`；否则清空消息。
- 在 `finally` 清空 `pendingBookmarkTarget`，避免污染下一次切话。
- MANUAL 其他行为（持久化位置、`selectChapter`）不变。

### 7.4 删除/撤销
- 依赖 7.1 的全量 `bookmarkEntriesByKey`：
  `removeBookmark(item)` 用 identity 调 `repository.removePageBookmark`，
  `undo` 用 `restorePageBookmark`，与当前打开话无关。
- 无存储格式变更；旧书签原 `addedAtMs` 保留。

## 8. 验证

### JVM 单元测试（新增）
- `OnlineBookmarkAggregationTest`
  - 跨话 fallback 身份：页码已知、按章节序号排序
  - 跨话 pageId 身份：页码为 null、标题取快照/章节名
  - 未知章节：`available=false`、`chapterOrder=MAX`、排最后
  - 章节不可用：同上
  - 当前话 pageId 精确命中：页号正确、`stale=false`
  - 当前话无法命中：夹取页号、`stale=true`
  - revision 变化：`stale=true`
  - 标题/ revision 为空（旧 JSON 兼容）
  - `resolveOnlineBookmarkJump`：pageId 命中、fallback 近似、越界取 0
- `ReaderBookmarkPresentationTest`
  - 混合本地/在线排序；不可用排最后；同章节 null 页排后；`addedAtMs` 次序
  - 页签文案四种分支

### 命令
- `.\gradlew.bat testDebugUnitTest`
- 提交前 `spotlessApply` + `spotlessCheck`
- 除单元测试外不跑编译/打包任务

### 仅实机验证
- 跨话点击是否切话并停在目标页
- revision 变化的确认弹窗与近似打开
- 跨话缩略图占位、当前话缩略图不串页
- 跨话删除 + Snackbar 撤销
- 本地 PDF 系列跨话书签跳转、单本 zip 无回归
- 章节不可用条目的禁用态与文案

## 9. 提交计划（Conventional Commits，小批量）
1. `feat(reader): add unified bookmark display model and ordering`
2. `feat(reader-online): aggregate bookmarks across chapters`
3. `feat(reader-online): jump to bookmarked chapter and page`
4. `feat(reader): wire cross-chapter bookmarks and tests`

## 10. 实施顺序与进度上报
- 完成第 1–2 步（展示模型 + 在线聚合纯函数及测试）后先给简短进度报告。
- 再完成第 3–4 步（ViewModel 跳转/删除、界面接线）。
