# Implementation Notes: Unified Page Turn Actions

## Problem

在线阅读器的章节过渡项没有真实页面 key。过渡项显示时，`currentPageStateKey` 与
`zoomedPage` 都是 `null`，原有相等判断会把它误判为已缩放页面，导致左右区域点击被
直接忽略；原生 Pager 滑动不受影响。

## Decision

- 只有当前页 key 非空且与缩放页 key 相同，才视为当前页已缩放。
- 点击翻页使用现有 `ReaderTransitionDirection.PREVIOUS/NEXT` 作为逻辑方向入口。
- 逻辑方向集中映射到相邻页 delta，供当前点击和未来按键输入复用。
- 滑动继续由 `HorizontalPager` 原生驱动，避免在滑动后重复触发动画。
- 保留既有 Loading、Error、Boundary 和 Ready 过渡状态行为，不实现音量键翻页。

## Changes

- 增加当前页缩放判定 helper，消除 `null == null` 误判。
- 增加逻辑翻页方向到相邻页 delta 的映射。
- 将阅读器左右点击从裸 `-1/+1` 改为 `PREVIOUS/NEXT` 方向调用。
- 让点按抑制、Pager 滑动开关和顶部栏共享同一个缩放判定结果。

## JVM Tests

- RED：新增测试先因 helper 尚不存在而失败，Gradle daemon 日志确认预期的 unresolved
  reference。
- GREEN：
  `./gradlew.bat :app:testDebugUnitTest --tests com.exio.inkleaf.ui.ReaderChapterWindowTest --console=plain`
  通过。
- 全量 JVM 回归：`./gradlew.bat :app:testDebugUnitTest --console=plain` 通过，共 52 个测试
  套件、321 个测试，0 failure、0 error、0 skipped。
- 覆盖空当前页 key、匹配/不匹配缩放 key、逻辑方向映射，以及既有 Ready/未准备过渡项
  的前后翻页行为。
- Compose `pointerInput` 接线按项目规则做静态审查；未增加 Android/instrumentation 测试。

## Deviations

- 实现方案无偏离。
- 首次 RED 命令超过 shell 的 124 秒等待上限；Gradle 在后台完成后，从 daemon 日志确认
  了预期失败。GREEN 重跑正常完成。
