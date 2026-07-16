---
target: ReaderScreen
total_score: 27
p0_count: 0
p1_count: 4
timestamp: 2026-07-16T04-36-30Z
slug: src-main-java-com-exio-inkleaf-ui-readerscreen-kt
---

# ReaderScreen 设计评审

## Design Health Score

| #         | Heuristic                       |     Score | Key Issue                                                                      |
|-----------|---------------------------------|----------:|--------------------------------------------------------------------------------|
| 1         | Visibility of System Status     |       3/4 | 页码、加载和错误可见；控件状态隐藏且没有自动收起/恢复提示。                                                 |
| 2         | Match System / Real World       |       3/4 | 胶片条、滑杆符合阅读器心智模型，但左/中/右点击区没有说明。                                                 |
| 3         | User Control and Freedom        |       3/4 | Back、滑杆、胶片跳页完整；缺少缩放/复位，控件持续遮挡时也无自动恢复。                                          |
| 4         | Consistency and Standards       |       3/4 | Material Button/IconButton/Slider 使用正确；Toast 和低层 pointerInput 偏离 Android 推荐路径。 |
| 5         | Error Prevention                |       3/4 | 页码边界和损坏文件有保护；“从书架移除”无需确认。                                                      |
| 6         | Recognition Rather Than Recall  |       2/4 | 读者必须记住点按分区和中心点按规则，图标动作也无文字标签。                                                  |
| 7         | Flexibility and Efficiency      |       2/4 | 胶片+滑杆支持快速跳页，但没有缩放、双击复位或章节直达。                                                   |
| 8         | Aesthetic and Minimalist Design |       4/4 | 黑色阅读舞台、临时工具栏和单一强调色让漫画保持主角。                                                     |
| 9         | Error Recovery                  |       3/4 | 打开失败有返回/移除路径，单页失败只有文本，没有重试动作。                                                  |
| 10        | Help and Documentation          |       1/4 | 没有手势提示、首次引导或上下文帮助。                                                             |
| **Total** |                                 | **27/40** | **Acceptable：骨架可靠，但阅读舒适度与可发现性需先补强。**                                           |

## Anti-Patterns Verdict

**LLM assessment：** 不像 AI 生成的界面。黑色舞台、按需出现的上下工具栏、主题强调色仅用于当前状态，和项目“让漫画退场
UI”原则一致。主要问题不是视觉套模板，而是把关键能力藏进自定义手势，导致“安静”变成“需要猜”。

**Deterministic scan：** 对 `ReaderScreen.kt` 及 `app/src/main` 的 detector 均为 exit 0、`[]`、0
findings；但该 detector 的目录规则只覆盖 HTML/CSS/JS/TS/Vue/Svelte/Astro，不覆盖 Kotlin，因此这是 false
negative 风险，不能当作无问题证明。Android Compose 原生目标没有可用浏览器/localhost overlay。

## Overall Impression

这是一个有明确阅读哲学的实现：加载、边界、系统栏和页码都经过认真处理。最大的机会是把它从“熟悉阅读器的人能用”提升到“第一次打开也知道如何读，并且能舒适读小字”：补齐缩放/平移，给隐藏手势提供语义和提示，让工具栏真正短暂存在。

## What's Working

1. `Color.Black` 阅读舞台和半透明黑色工具栏（`ReaderScreen.kt:160-195, 331-336, 397-403`
   ）有效隔离主题色与漫画内容，符合 Design System 的 Comic Owns the Stage Rule。
2. `BackHandler`、退出时恢复 system bars、顶部/底部 insets（`ReaderScreen.kt:124-157, 335, 401`）是稳健的
   Android 行为；系统返回不会把沉浸态带回书架。
3. 胶片缩略图 + 滑杆的两级定位（`ReaderScreen.kt:410-500`）兼顾粗跳和精确选择；缩略图预热与去重也让控制栏出现时不至于空白。

## Priority Issues

### [P1] 隐藏的整屏点按区没有可发现性或无障碍入口

**证据：** `ReaderScreen.kt:249-257` 用 `pointerInput`/`detectTapGestures` 把左 1/3、中央、右 1/3
分别绑定为上一页、控件开关、下一页，但没有 `semantics`、`onClickLabel` 或 custom actions。

**为什么重要：** 视觉用户也必须靠试错发现规则；TalkBack、键盘/辅助开关用户无法得到“上一页/下一页/显示控件”的动作。Android
官方指出，低层 `pointerInput` 不会自动提供 `clickable` 的语义、焦点和键盘支持，复杂手势应补充自定义语义动作。

**修复：** 保留点按分区作为快捷路径，同时在舞台语义上提供“上一页/下一页/显示或隐藏控件” custom
actions；第一次进入或帮助按钮显示一句可关闭提示。对图标动作补 `onClickLabel`，让 TalkBack 读出当前页和状态。

### [P1] 没有缩放/平移，直接损害漫画阅读舒适度

**证据：** `ReaderScreen.kt` 只有 `HorizontalPager`、`detectTapGestures` 和缩略图 `graphicsLayer`；
`ComicPage`（`688-788`）没有 `transformable`/pinch zoom、双击放大或平移状态。

**为什么重要：** 项目产品目的明确包含 zooming；固定 `ContentScale.Fit`
在手机上会把小字压缩到不可读，用户只能离开阅读页或依赖系统放大，核心任务被打断。

**修复：** 为单页内容加入 `transformable` + 双击切换倍率 + 最小/最大缩放和复位动作；放大时把水平 pager
拖动与页内 pan 分层处理，工具栏中显示“恢复 100%”。优先设备上验证横竖屏和边缘手势冲突。

### [P1] 工具栏只会手动关闭，长期遮挡内容

**证据：** `showControls` 在 `ReaderScreen.kt:110` 初始化，在 `ComicPager` 中仅由中心点按切换（
`249-257, 273-309`）；没有 inactivity timeout。

**为什么重要：** 用户为收藏/跳页打开工具栏后，必须再次猜中心点按才能退场；黑色胶片条和顶部条会持续遮挡漫画，违背“controls
recede behind the comic”。

**修复：** 每次工具栏交互后 2–3 秒自动收起；拖动 Slider/横滑胶片时暂停计时，完成后重新计时；提供无障碍可读的“控件已显示/已隐藏”状态，系统
Remove animations 时即时切换。

### [P1] Toast 反馈短暂且不可操作，状态更新也不够明确

**证据：** `ReaderScreen.kt:116-121` 把 `ReaderViewModel.readerMessage` 转成 `Toast`；收藏/封面动作在
`ReaderViewModel.kt:173-214` 期间没有向 UI 暴露 in-flight 状态。

**为什么重要：** Toast 很容易被沉浸式系统栏或用户翻页错过，TalkBack 也不一定读到；重复点击时图标可能短暂显示旧状态。Android
Material 建议用 Snackbar 承载应用内瞬时反馈，必要时提供撤销/重试。

**修复：** 使用 `SnackbarHostState` 或工具栏内联状态；收藏按钮立即反映 pending/selected/failed
三态并在失败时提供重试，封面设置成功后保留短暂可见确认。

### [P2] “从书架移除”是不可逆感知动作，却没有确认

**证据：** `ErrorView` 中的 `OutlinedButton(onClick = onRemove)`（`ReaderScreen.kt:842-848`）直接删除书架记录。

**为什么重要：** 用户在错误恢复时可能只是想返回，第二个按钮与返回动作相邻，误触会增加重新导入成本。

**修复：** 先展示 Material `AlertDialog`，明确“仅移除书架记录，原文件不会删除”，默认焦点放在取消；删除完成后用
Snackbar 提供撤销或重新导入入口。

## Cognitive Load

- 控件隐藏时，用户要记住三分区点按规则；这属于 recall 而非 recognition。
- 控件显示后同时出现返回、设封面、收藏、缩略图条和滑杆，超过四个可选动作；层级尚可，但没有“当前最常用动作”的突出说明。
- 多章节页码把章节标题和全局计数拼在一起（`ReaderScreen.kt:273-277`），用户可能误读为“本章第 N
  页”。建议明确“本章 x/y · 全书 z”。

## Emotional Journey

- **进入：** 黑底 + 延迟 spinner/crossfade 是安静的正向开场。
- **阅读中：** 胶片和页码提供安全感；但遇到小字时没有缩放是明显的挫败点。
- **探索：** 第一次点按中心/左右区没有任何提示，容易形成“应用没反应”的情绪谷底。
- **出错：** 返回和移除路径清楚，但直接删除会让恢复阶段变得紧张。

## Persona Red Flags

**Jordan（第一次使用）：** 看不到左右点按区的标签或帮助；顶部两个图标只有图形，无法判断“设为封面”和“收藏本页”；中心点按若无反应反馈，可能以为页面卡住。

**Sam（无障碍用户）：** 整屏 `pointerInput` 没有 custom actions；页图、缩略图、页码之间的语义关系没有表达当前页/可翻页状态；Toast
反馈可能被漏读。

**Casey（单手、易中断）：** 底部工具栏在打开后持续占据阅读区；恢复后需要重新找到中心点按。进度本身由
ViewModel 节流保存是优点，但控件显隐使用普通 `remember`，配置变化后不会保留用户当下的工具栏状态。

## Minor Observations

- `ReaderScreen.kt:287` 页码浮层使用 `Color.Black.copy(alpha = 0.5f)`；在白色漫画底图上合成灰色背景时，白色
  label 可能低于 4.5:1，建议改为更不透明的 scrim 或取样验证。
- `ReaderScreen.kt:324-327, 391-394, 558, 568, 612-620, 774` 有多处动画；源码未显式提供
  reduced-motion 分支，应在系统“移除动画”下验证组合效果，尤其是 Coil crossfade。
- API 29–30 没有 blur 占位（`ReaderScreen.kt:726-737`），远跳时会从黑底直接等 spinner；可提供低成本色块/静态缩略图
  fallback。
- `ReaderScreen.kt` 内约 11 个用户可见中文字符串硬编码，应迁移到 `strings.xml`，同时为 content
  descriptions 做本地化。
- `FilmstripThumb` 的 `56×80dp` 点击区、IconButton 默认目标和 8dp 间距符合 48dp 触控基线；固定 Reader
  Black/White 也符合 DESIGN.md，不应为了 detector 误报而改成主题色。

## Questions to Consider

1. 下一轮你想先解决哪一组？
    - A：先做缩放/平移（直接提升阅读舒适度）
    - B：先做隐藏手势的提示与 TalkBack 语义（提升可发现性/无障碍）
    - C：先做工具栏自动收起与 Snackbar 反馈（减少遮挡和状态不确定）
2. 缩放交互的目标形态更接近哪种？
    - A：双击放大 + 双指平移，保持当前分页模型
    - B：双指缩放时允许连续画布阅读，松手再回到分页
    - C：暂不加入缩放，只先优化翻页和章节跳转
