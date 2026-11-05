# 阶段 C：阅读会话记录接口（ReaderSessionRecorder）

## 1. 目标

把两个 Reader ViewModel 里各自编写的「会话何时开始 / 页面何时可见 / 前后台切换 / 检查点 / 结束」编排收口到一个接口，
两个既有实现作为适配器接入，状态机与持久化语义保持不变。

非目标：

- 不合并、不重写 `ReadingSessionStateMachine` 与 `OnlineReadingSession`。
- 不动 ACTIVE/PAUSED/COMPLETED、中断超时、进程恢复、检查点间隔等离线持久化语义。
- 不动 `.gitignore`、`CONTEXT.md`（用户尚未决定其去留）。
- 不跑 gradle 编译/打包，只跑 JVM 单元测试；提交前 `spotlessApply` + `spotlessCheck`。

## 2. 新模块：`ui/ReaderSessionRecorder.kt`

```kotlin
internal interface ReaderSessionRecorder<P : ReadingPosition<P>> {
    /** 本次阅读访问是否已结束。 */
    val isEnded: Boolean

    /** 阅读器打开了内容；一次阅读生命周期内幂等。 */
    fun begin()

    /** 页面可读且已上屏；null 表示当前没有可读页面。 */
    fun pageVisible(page: P?)

    /** 交互式前台变化。 */
    fun foregroundChanged(foreground: Boolean)

    /** 周期性检查点；没有该机制的实现忽略。 */
    fun checkpoint()

    /** 结束本次访问；重复调用无效果。 */
    fun end(reason: ReadingSessionEndReason, finalPage: P?)
}
```

设计说明：

- 位置类型用泛型 `P : ReadingPosition<P>`，让离线快照与在线位置各自保持类型，同时共享同一个端口。
- `pageVisible(null)` 表达「当前没有可读页」（在线切章/加载失败），这是「章节未就绪不计时」的接口表达。
- `checkpoint()` 在离线适配器内由检查点循环驱动；在线适配器为 no-op（在线只在段边界累计，无周期落库）。

### 2.1 离线适配器 `OfflineReaderSessionRecorder`

构造参数：`ReadingSessionRepository`、`ReadingSessionComicRef`、`openScope: CoroutineScope`（`viewModelScope`）、
`terminalScope: CoroutineScope`（`applicationScope`）、`checkpointIntervalMillis`（默认 `ReadingSessionRules.CHECKPOINT_INTERVAL_MS`）。

事件映射（全部经由 `ReadingSessionRepository.dispatch`）：

| 接口调用 | 状态机事件 | 说明 |
| --- | --- | --- |
| `begin()` | `OpenComic(comic)` | 只有身份，源修订仍等首个可见页；保留现有最早结算旧会话的时机 |
| `pageVisible(p)`（首次非 null） | `ReaderReady(p)` + `PageVisible(p)` | 首个真实页面把「内容就绪」与「页面上屏」合并上报 |
| `pageVisible(p)`（后续） | `PageVisible(p)` | |
| `foregroundChanged(true)` | `EnteredInteractiveForeground`，并启动检查点循环 | |
| `foregroundChanged(false)` | 停止检查点循环 + `LeftInteractiveForeground`（terminalScope） | 对齐现有 `onPause` / `onCleared` 的落库时机 |
| `checkpoint()` | `CheckpointTick` | 循环每 `checkpointIntervalMillis` 调一次 |
| `end(reason, _)` | 停止循环 + `LeaveReader(reason)`（terminalScope） | 结束位置仍由状态机用 `lastVisiblePosition` 决定 |

- 适配器持有 `ended` 标志：结束后的任何调用都被忽略；异常吞掉（会话记录不能影响阅读）。
- 首个 `pageVisible` 合并 `ReaderReady` 的语义差异：会话开始时机从「内容 Ready」推迟到「首个页面被上报可见」。
  Ready 单独也不足以开始计时，因此只影响旧会话的 settle 决策推迟到页面出现，属于更贴近真实阅读的收紧。

### 2.2 在线适配器 `OnlineReaderSessionRecorder`

构造参数：`OnlineReadingSession`、`content: OnlineContentIdentity`、`title: () -> String`、`pageAt: () -> OnlinePageLocation?`、
`timeZoneId: () -> String`、`onRecord: (OnlineReadingSessionRecord) -> Unit`（由 ViewModel 在 `applicationScope` 里落库）。

| 接口调用 | `OnlineReadingSession` 调用 |
| --- | --- |
| `begin()` | 有位置时 `begin(location)`，已开始则 `moveTo(location)` |
| `pageVisible(p != null)` | `moveTo(p)` + `setChapterReady(true)` + 前台时 `resumeSegment()` |
| `pageVisible(null)` | `pauseSegment()` + `setChapterReady(false)` |
| `foregroundChanged(true)` | 记录前台并 `resumeSegment()` |
| `foregroundChanged(false)` | 记录后台并 `pauseSegment()` |
| `checkpoint()` | no-op |
| `end(reason, finalPage)` | `finish(finalPage ?: pageAt())`，经 `OnlineReadingSessions.record` 过滤后回调 `onRecord` |

- 资格判定（30 秒或位置变化）仍由 `OnlineReadingSessions.record` 单点负责，适配器只负责把结束事实交给它。

## 3. ViewModel 接线

### 3.1 `ReaderViewModel`（离线）

- 删除 `dispatchSessionEvent` / `dispatchTerminalSessionEvent` / `checkpointJob` / `startCheckpointLoop` / `stopCheckpointLoop`。
- 新增可空字段 `sessionRecorder: ReaderSessionRecorder<ReadingPositionSnapshot>?`；预览模式不创建。
- 漫画实体加载后：创建 `OfflineReaderSessionRecorder` 并调用 `begin()`（替代 `OpenComic`）。
- 打开完成、算出 `safeStartPage` 后：`pageVisible(positionSnapshot(opened, safeStartPage))`（替代 `ReaderReady`）。
- `saveProgress`：`pageVisible(positionSnapshot(opened, globalPage))`。
- 生命周期观察者：`onResume → foregroundChanged(true)`，`onPause → foregroundChanged(false)`。
- `attachProcessLifecycle` 若已 RESUMED：`foregroundChanged(true)`（替代手动启动循环）。
- `endReadingSession`：保留 `sessionEnded` 防重，调用 `end(reason, null)`。
- `onCleared`：未显式结束时调用 `foregroundChanged(false)`（暂停而非结束）。
- 保留 `persistCurrentProgressInApplicationScope`、`sessionEnded`、生命周期挂载/卸载逻辑。

### 3.2 `OnlineReaderViewModel`（在线）

- 删除 `readingSession` 字段与 `resumeActiveSegmentIfProcessResumed` 的调用点，改为 `sessionRecorder`（构造即初始化）。
- `restorePage` 里的 `readingSession.hasStarted` 改成本地 `chapterOpened` 标志（首次成功载入后置 true）。
  注意 `restorePage` 在 `begin()` 之前执行，语义与现状一致。
- `loadActiveChapter`：
  - 成功路径：`begin()`（内部完成 begin/moveTo）→ `pageVisible(initialLocation)` → `attachProcessLifecycle()`；
    删除 `setChapterReady(true)` 与 `resumeActiveSegmentIfProcessResumed()`。
  - 失败路径：`pageVisible(null)`。
- 切章提交前：`pauseSegment()` + `setChapterReady(false)` → `pageVisible(null)`。
- 切章提交后：`moveTo(committedLocation)` → `pageVisible(committedLocation)`；删除 `setChapterReady(true)` / resume 调用。
- 提交失败：`pageVisible(locationForOrNull(currentPage))`（有可读页则恢复计时，否则 null）。
- `saveProgress`：`moveTo` → `pageVisible(location)`。
- 生命周期观察者：`foregroundChanged(true/false)`。
- `attachProcessLifecycle`：`isEnded` 守卫改用接口属性；已 RESUMED 时 `foregroundChanged(true)`。
- `finishSession`：`if (sessionRecorder.isEnded) return`；`end(LEFT_READER, locationForOrNull(currentPage))`；
  随后 detach、关闭进度队列、在 `applicationScope` 等队列排空后写入最终位置。
  `onRecord` 在 `applicationScope` 内 `runCatching { repository.recordReadingSession(record) }`。
  历史上「先写位置再写历史」的顺序变为历史写入可先启动；两张表互不依赖，记录为已知差异。

## 4. 测试

### 4.1 契约测试（新增 `ReaderSessionRecorderContractTest`）

用在线适配器 + 内存中的 `OnlineReadingSession`、假时钟、内存记录收集器，覆盖：

1. `begin` 前没有可读页 → 结束不留记录。
2. `end` 幂等（重复结束后仍只有一条记录）。
3. 章节未就绪（`begin` 后只有前台，无 `pageVisible`）→ 30 秒前台时间不计时、无记录。
4. 前台时间只在「有可读页 + 前台」时累计（迁移原 `active time only counts while the chapter is ready`）。
5. 后台时间不累计。
6. `end` 会暂停运行中的段并写入结束墙钟（迁移原 `finish pauses a running segment...`）。
7. `end` 回退到最近可见页；优先显式结束页（迁移 `finish falls back...` / `finish prefers...`）。
8. 页面不可见（切章）后不再恢复计时（迁移 `a chapter that is no longer ready...`）。
9. `endedAt` 不早于 `startedAt`。
10. 短暂静止访问不写历史；达到共享阈值或位置变化则写入。
11. 保留记录携带身份、标题、时区、起止位置（从 `OnlineReadingSessionsTest` 迁移）。

删除 `OnlineReadingSessionTest.kt`（其生命周期断言全部由契约测试经适配器覆盖）。
`OnlineReadingSessionsTest.kt` 只保留纯策略断言，移除「record carries the captured facts」。

### 4.2 次要收益

- `GalleryExport.kt`：新增两个 `galleryDisplayName` 重载（离线：标题 + 页；在线：标题 + 章节 + 页），
  两个 ViewModel 的拼接改为调用它们；`GalleryExportTest` 补页码从 1 开始、非法字符、空标题兜底。
- 新增 `ReaderProgressWriteQueueTest.kt`：把 `ReaderProgressRestorePolicyTest` 里的 3 个队列测试迁出，
  并补「延迟窗口内连续提交只落一次写」。

## 5. 验收

- `.\gradlew.bat testDebugUnitTest` 全绿（仅 JVM 测试）。
- `.\gradlew.bat spotlessApply` 后 `.\gradlew.bat spotlessCheck` 通过。
- Conventional Commits 小批量提交（代码 / 测试 / 相簿 / 队列各独立）。

## 6. 只能实机验证

- 真实 `ProcessLifecycleOwner` 前后台时序、锁屏/分屏失焦。
- 离线进程死亡恢复（`ProcessRestored`）、中断超时结算。
- 在线 30 秒永久化阈值、后台暂停时间排除、切章期间不计时。
- 相簿实际写入文件名（`MediaStore` 展示名）。

## 7. 实施顺序

1. 新增 `ReaderSessionRecorder.kt`（接口 + 两个适配器），提交代码。
2. 改两个 ViewModel 接线，提交代码。
3. 契约测试 + 旧测试迁移/删除，独立提交。
4. 相簿文件名纯函数 + 测试，独立提交。
5. 进度写队列测试迁移 + 新增，独立提交。
