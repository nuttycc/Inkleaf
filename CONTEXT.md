# Inkleaf

An Android comic reader (local zip/cbz, PDF, and online plugin sources) with a shared reader pipeline. This glossary pins down the reading-domain language.

## Language

### Reading

**阅读模式 (Reading Mode)**:
The top-level way pages are presented: 翻页 (paged, one page per screen) or 条漫 (webtoon, continuous vertical strip).
_Avoid_: 阅读方向 (that is a separate concept), viewer, layout

**翻页模式 (Paged Mode)**:
Reading mode where one page fills the screen and the reader turns pages horizontally.
_Avoid_: 普通模式, 横向模式

**条漫模式 (Webtoon Mode)**:
Reading mode where pages are stitched seamlessly into one continuous vertical strip; pages fill the width.
_Avoid_: 长条模式, 滚动模式, vertical mode

**阅读方向 (Page Direction)**:
In paged mode only: the horizontal order pages are read — 左→右 (LTR) or 右→左 (RTL). Hidden in webtoon mode.
_Avoid_: 方向, 顺序

**当前页 (Current Page)**:
The page the reader's progress refers to. In paged mode: the settled page. In webtoon mode: the page occupying the largest share of the viewport.
_Avoid_: 可见页, 顶部页

**页引用 (Page Reference)**:
The identity a reader action points at: a namespace plus the page's own identity. The namespace is the volume's source revision offline and the chapter plus its revision online, so re-packing or re-fetching stops the old reference from matching. It also carries the page's storage index, which is used only to read the page — never to compare two references.
_Avoid_: 页码, page index, 全局页

**阅读标记 (Reader Mark)**:
What the reader records about the page they are on — a bookmark or a page snapshot favorite. One module owns the toggle, the same-page judgment, the in-flight guard and the outcome wording; each reader supplies an adapter for its own storage and undo.
_Avoid_: 收藏（alone, it names only one kind）, 书签状态

**在线阅读会话 (Online Reading Session)**:
One visit to an online chapter, from the first readable page until the reader ends it. Its active foreground time and start/end positions enter history only when it qualifies.
_Avoid_: 阅读记录, 阅读计时

**章节分隔条 (Chapter Divider)**:
In webtoon mode, a thin in-strip banner between chapters carrying the chapter title (and load/error state). The strip continues loading across it automatically.
_Avoid_: 边界页 (that is the paged-mode concept), 过渡页

### Discovery

**浏览会话 (Browse Session)**:
The retained loaded state of one feed and filter combination — its items, cursor, fetch time, cache revision, and source generation — for the current discover navigation entry. A session older than its source generation is discarded.
_Avoid_: feed cache, list cache

**页面筛选 (Page Filter)**:
A filter a loaded page supplies that the feed does not declare, such as an account's favorite folders. It belongs to its browse session; its first option is the default and is never sent.
_Avoid_: dynamic filter

### Updates

**应用更新 (App Update)**:
One check → download → install attempt. The manifest is re-checked before downloading, missing install permission defers the launch, and the downloaded APK is handed to the system installer.
_Avoid_: 升级流程, updater

### Sources

**源分区 (Source Area)**:
The settings and actions a source groups under one section, rendered as one area of the source detail screen. Content with no section is the generic area.
_Avoid_: group, tab, card

**登录状态提示 (Session Hint)**:
The host's record of whether a source is signed in, derived from the source's own login and sign-out outcomes, cleared by any `AUTH_REQUIRED` answer, and reconciled with its session check. One app-wide record per source; it is a display hint, never an authority on the server session.
_Avoid_: login state, auth state, token

