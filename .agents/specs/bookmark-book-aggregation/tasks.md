# 任务清单

## 1. 统一展示模型与书签面板
- [x] `ReaderPresentationModels.kt`：新增 `ReaderBookmarkTarget`；扩展 `ReaderBookmarkItem` 字段；新增 `sortedReaderBookmarks`、`readerBookmarkPageLabel`；`ReaderPresentationActions` 增加 `onBookmarkSelected`。
- [x] `ReaderBookmarksSheet.kt`：按条目回调、统一排序、页签文案、占位缩略图、不可用禁用态、通用 stale 确认文案。
- [x] `ReaderScreen.kt`（本地）：书签映射到新模型；面板点击支持 Local 跨话跳转，失败 Snackbar。

## 2. 在线聚合纯函数与测试
- [x] 新增 `OnlineBookmarkAggregation.kt`：`onlineBookmarkItems`、`resolveOnlineBookmarkJump`。
- [x] 新增 `OnlineBookmarkAggregationTest`。
- [x] 新增 `ReaderBookmarkPresentationTest`（排序 + 文案）。
- [x] 运行 `testDebugUnitTest`，提交第 1–2 步。
- [x] **向用户发简短进度报告。**

## 3. 在线 ViewModel
- [x] `refreshUserRecords` 去章节过滤；全量 `bookmarkEntriesByKey`；全量 `bookmarks`；当前页图标仍仅当前话。
- [x] `openBookmark` + `pendingBookmarkTarget`；`prepareAndCommitChapter` 支持书签目标页与近似提示。
- [x] 跨话删除/撤销按 identity 工作（依赖全量映射）。
- [x] 章节缺失/不可用/加载中的明确 `readerMessage`。

## 4. 在线接线
- [x] `OnlineReaderScreen.kt`：`onBookmarkSelected = viewModel::openBookmark`。
- [x] `ReaderScreen.kt`：当前话书签直接滚动，跨话走回调；不可跳时明确提示。

## 5. 收尾
- [x] `.\gradlew.bat testDebugUnitTest` 全绿。
- [x] `spotlessApply` + `spotlessCheck`。
- [x] 按小批次 Conventional Commits 提交。
- [x] 输出中文结论：改动文件、关键逻辑、提交记录、测试结果、仅实机验证项。
